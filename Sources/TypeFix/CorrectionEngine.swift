import AppKit

enum CaptureState {
    case idle        // nothing pending
    case capturing   // manual: active session / auto: text pending a fix
    case processing  // calling the LLM / replacing text
}

/// Coordinates capture -> LLM -> replacement for both correction modes.
///
/// - Manual: double-Shift starts a session, double-Shift again corrects it.
/// - Auto: keystrokes are captured continuously while armed; after a typing
///   pause (`autoDelay`) the pending chunk is corrected in place. The chunk is
///   abandoned if the insertion point moves (arrows, clicks, Enter, Tab,
///   shortcuts) since we can no longer safely delete-and-replace.
///
/// All mutable state is touched on the main thread.
final class CorrectionEngine {
    private let settings: AppSettings
    private let tap = KeyEventTap()
    private let corrector = TextCorrector()

    private(set) var state: CaptureState = .idle
    var onStateChange: (() -> Void)?
    var onError: ((String) -> Void)?
    /// Fired on the main thread when a correction is applied to the focused field.
    var onCorrectionApplied: ((CorrectionRecord) -> Void)?
    /// Fired (Auto mode) each time the idle countdown (re)starts, with its duration.
    var onAutoCountdown: ((TimeInterval) -> Void)?
    /// Fired (Auto mode) when a pause fires but the text is below the minimum
    /// length: (currentCount, threshold).
    var onAutoBelowThreshold: ((Int, Int) -> Void)?
    /// Fired when the user presses the "copy last original" shortcut (⌥⇧⌘C).
    var onCopyLast: (() -> Void)?
    /// Fired (with the unchanged text) when the model returned no changes, so a
    /// guardrail can still check it for a remaining typo.
    var onNoChange: ((String) -> Void)?
    /// Fired after the last correction was reverted.
    var onReverted: (() -> Void)?

    /// The most recent applied correction, while it's still safe to revert in
    /// place (cleared as soon as the user types, moves, or triggers anything).
    private var revertable: (original: String, corrected: String)?

    // Manual mode
    private var manualBuffer = ""

    // Auto mode
    private var autoBuffer = ""
    private var idleTimer: Timer?
    /// Set when the user types, deletes, or moves the cursor while a correction is
    /// in flight or being pasted, so its result / revert can be discarded.
    private var typedDuringProcessing = false

    /// Bumped whenever pending work is hard-invalidated (mode change, boundary,
    /// reset). Async LLM results and replacement completions captured under an old
    /// value are stale and ignored.
    private var generation = 0

    private var lastMode: CorrectionMode

    // The foreground app, tracked so the per-app disable list can gate capture
    // without querying NSWorkspace on every keystroke.
    private var frontmostBundleID: String?
    private var frontmostAppName: String?
    private var appActivationObserver: NSObjectProtocol?

    init(settings: AppSettings) {
        self.settings = settings
        self.lastMode = settings.correctionMode

        let front = NSWorkspace.shared.frontmostApplication
        if front?.bundleIdentifier != Bundle.main.bundleIdentifier {
            frontmostBundleID = front?.bundleIdentifier
            frontmostAppName = front?.localizedName
        }
        appActivationObserver = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didActivateApplicationNotification,
            object: nil,
            queue: .main
        ) { [weak self] note in
            self?.handleAppActivation(note)
        }

        tap.shouldCapture = { [weak self] in self?.shouldCapture() ?? false }
        tap.isArmed = { [weak self] in self?.isArmed ?? false }
        tap.currentHotkey = { [weak self] in self?.settings.hotkey ?? .bothShifts }
        tap.onHotkey = { [weak self] in self?.handleHotkey() }
        tap.onCopyLast = { [weak self] in self?.onCopyLast?() }
        tap.onCharacters = { [weak self] characters in self?.handleCharacters(characters) }
        tap.onBackspace = { [weak self] in self?.handleBackspace() }
        tap.onDeleteWord = { [weak self] in self?.handleDeleteWord() }
        tap.onDeleteToLineStart = { [weak self] in self?.handleDeleteToLineStart() }
        tap.onEnter = { [weak self] in self?.handleEnter() }
        tap.onTab = { [weak self] in self?.handleTab() }
        tap.onCancel = { [weak self] in self?.handleEscape() }
        tap.onNavigation = { [weak self] in self?.handleNavigation() }
        tap.onRevert = { [weak self] in self?.revertLast() }
        tap.canRevert = { [weak self] in self?.revertable != nil }
        tap.onUserInput = { [weak self] in self?.revertable = nil }
    }

    deinit {
        if let appActivationObserver {
            NSWorkspace.shared.notificationCenter.removeObserver(appActivationObserver)
        }
    }

    func canRevert() -> Bool { revertable != nil }

    /// Undo the last applied correction in place (delete the corrected text and
    /// re-type the original). Only valid right after a fix, before further input.
    func revertLast() {
        guard let revert = revertable else { return }
        revertable = nil
        TextReplacer.shared.replace(deleteCount: revert.corrected.count, with: revert.original)
        onReverted?()
    }

    /// Active unless globally paused or the foreground app is on the disable list.
    var isArmed: Bool {
        guard !settings.isPaused else { return false }
        return !settings.isDisabled(bundleID: frontmostBundleID)
    }

    var mode: CorrectionMode { settings.correctionMode }

    // MARK: - Pause & per-app disable

    var isPaused: Bool { settings.isPaused }

    func setPaused(_ paused: Bool) {
        guard settings.isPaused != paused else { return }
        settings.isPaused = paused
        if !isArmed { resetPending() }
        onStateChange?()
    }

    /// The current foreground app (bundle id + display name) for the per-app
    /// disable menu, or nil when it's unknown or TypeFix itself.
    var frontmostApp: (bundleID: String, name: String)? {
        guard let bundleID = frontmostBundleID else { return nil }
        return (bundleID, frontmostAppName ?? bundleID)
    }

    var isFrontmostAppDisabled: Bool {
        settings.isDisabled(bundleID: frontmostBundleID)
    }

    func toggleDisabledForFrontmostApp() {
        guard let bundleID = frontmostBundleID else { return }
        if settings.isDisabled(bundleID: bundleID) {
            settings.enableApp(bundleID: bundleID)
        } else {
            settings.disableApp(bundleID: bundleID, name: frontmostAppName ?? bundleID)
        }
        if !isArmed { resetPending() }
        onStateChange?()
    }

    private func handleAppActivation(_ note: Notification) {
        let app = note.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication
        guard let bundleID = app?.bundleIdentifier,
              bundleID != Bundle.main.bundleIdentifier else { return }
        frontmostBundleID = bundleID
        frontmostAppName = app?.localizedName
        // Switching into a disabled app (or any change while unarmed) drops any
        // pending capture so a queued fix can't fire into it.
        if !isArmed { resetPending() }
        onStateChange?()
    }

    @discardableResult
    func start() -> Bool { tap.start() }

    /// Menu equivalent of pressing both Shift keys.
    func triggerHotkey() { handleHotkey() }

    /// Fix the current selection right now (used by the macOS "Fix with TypeFix"
    /// Services menu item). Reads the highlighted text via Accessibility, falling
    /// back to a clipboard copy for Electron/web apps, then replaces it in place.
    func fixSelectionNow() {
        syncModeIfNeeded()
        guard isArmed, state == .idle else { return }
        attemptSelectionFix { [weak self] handled in
            if !handled { self?.onError?("Select some text first, then choose Fix with TypeFix.") }
        }
    }

    func setMode(_ newMode: CorrectionMode) {
        guard settings.correctionMode != newMode else { return }
        settings.correctionMode = newMode
        lastMode = newMode
        resetPending()
        onStateChange?()
    }

    // MARK: - Capture gating

    private func shouldCapture() -> Bool {
        guard isArmed else { return false }
        switch mode {
        // Also observe input while a manual fix is processing so a cursor move or
        // fresh keystroke during the LLM wait can abort it (the delete position
        // would otherwise be wrong). Those events only invalidate; they aren't
        // buffered (see invalidatePendingManualInput).
        case .manual: return state == .capturing || state == .processing
        case .auto: return true
        }
    }

    private func setState(_ newState: CaptureState) {
        guard state != newState else { return }
        state = newState
        onStateChange?()
    }

    private func resetPending() {
        idleTimer?.invalidate()
        idleTimer = nil
        manualBuffer = ""
        autoBuffer = ""
        generation &+= 1 // stale-out any in-flight LLM call / replacement
        if state == .processing {
            typedDuringProcessing = true // abort any in-flight replacement
        } else {
            setState(.idle)
        }
    }

    /// In manual mode, real input while a fix is processing invalidates it (the
    /// delete position is about to be wrong). Returns true when the event was
    /// consumed as an invalidation so the caller should not buffer it.
    private func invalidatePendingManualInput() -> Bool {
        guard mode == .manual, state == .processing else { return false }
        typedDuringProcessing = true
        return true
    }

    private func syncModeIfNeeded() {
        guard mode != lastMode else { return }
        lastMode = mode
        resetPending()
    }

    // MARK: - Event handlers

    private func handleHotkey() {
        syncModeIfNeeded()
        guard isArmed else { return }
        switch state {
        case .processing:
            return
        case .capturing:
            // Mid-session: finish what was typed.
            switch mode {
            case .manual: finishManual()
            case .auto: flushAuto(force: true)
            }
        case .idle:
            // If the user has text highlighted anywhere, fix that selection in
            // place. Otherwise do the mode's normal idle action.
            attemptSelectionFix { [weak self] handled in
                guard let self, !handled else { return }
                switch self.mode {
                case .manual:
                    self.manualBuffer = ""
                    self.setState(.capturing)
                case .auto:
                    self.flushAuto(force: true)
                }
            }
        }
    }

    private func handleCharacters(_ characters: String) {
        syncModeIfNeeded()
        guard isArmed else { return }
        if invalidatePendingManualInput() { return }
        switch mode {
        case .manual:
            guard state == .capturing else { return }
            manualBuffer += characters
        case .auto:
            autoBuffer += characters
            if state == .processing {
                typedDuringProcessing = true
            } else {
                setState(.capturing)
                onAutoCountdown?(settings.autoDelay)
            }
            scheduleIdle()
        }
    }

    private func handleBackspace() { applyDeletion(Self.removingLastCharacter) }
    private func handleDeleteWord() { applyDeletion(Self.removingLastWord) }
    private func handleDeleteToLineStart() { applyDeletion(Self.removingToLineStart) }

    /// Mirrors a deletion (single char, Option+word, or Cmd+line) on the active
    /// capture buffer so it stays in sync with the text field.
    private func applyDeletion(_ transform: (String) -> String) {
        syncModeIfNeeded()
        guard isArmed else { return }
        if invalidatePendingManualInput() { return }
        switch mode {
        case .manual:
            guard state == .capturing else { return }
            manualBuffer = transform(manualBuffer)
        case .auto:
            if state == .processing { typedDuringProcessing = true }
            autoBuffer = transform(autoBuffer)
            if autoBuffer.isEmpty {
                idleTimer?.invalidate()
                idleTimer = nil
                if state != .processing { setState(.idle) }
            } else {
                scheduleIdle()
                if state != .processing { onAutoCountdown?(settings.autoDelay) }
            }
        }
    }

    private static func removingLastCharacter(_ text: String) -> String {
        var text = text
        if !text.isEmpty { text.removeLast() }
        return text
    }

    /// Approximates macOS Option+Delete: drop trailing whitespace, then the
    /// trailing run of non-whitespace (the previous word).
    private static func removingLastWord(_ text: String) -> String {
        var characters = Array(text)
        while let last = characters.last, last.isWhitespace { characters.removeLast() }
        while let last = characters.last, !last.isWhitespace { characters.removeLast() }
        return String(characters)
    }

    /// Approximates macOS Cmd+Delete: drop back to the start of the current line.
    private static func removingToLineStart(_ text: String) -> String {
        if let newlineIndex = text.lastIndex(of: "\n") {
            return String(text[...newlineIndex])
        }
        return ""
    }

    private func handleEnter() {
        syncModeIfNeeded()
        guard isArmed else { return }
        if invalidatePendingManualInput() { return }
        switch mode {
        case .manual:
            guard state == .capturing else { return }
            manualBuffer += "\n"
        case .auto:
            // Enter usually submits or breaks the line; we can't fix after that.
            boundaryReset()
        }
    }

    private func handleTab() {
        syncModeIfNeeded()
        guard isArmed else { return }
        if invalidatePendingManualInput() { return }
        switch mode {
        case .manual:
            guard state == .capturing else { return }
            manualBuffer += "\t"
        case .auto:
            boundaryReset() // Tab usually moves focus
        }
    }

    private func handleEscape() {
        syncModeIfNeeded()
        guard isArmed else { return }
        if invalidatePendingManualInput() { return }
        switch mode {
        case .manual:
            guard state == .capturing else { return }
            manualBuffer = ""
            setState(.idle)
        case .auto:
            boundaryReset()
        }
    }

    private func handleNavigation() {
        syncModeIfNeeded()
        guard isArmed else { return }
        if invalidatePendingManualInput() { return }
        switch mode {
        case .manual:
            // Cursor moved; the delete-count assumption is broken. Cancel safely.
            if state == .capturing {
                manualBuffer = ""
                setState(.idle)
            }
        case .auto:
            boundaryReset()
        }
    }

    /// Auto mode: the insertion point can no longer be trusted, so drop whatever
    /// is pending (and abort an in-flight replacement).
    private func boundaryReset() {
        idleTimer?.invalidate()
        idleTimer = nil
        autoBuffer = ""
        generation &+= 1 // stale-out any in-flight LLM call / replacement
        if state == .processing {
            typedDuringProcessing = true
        } else {
            setState(.idle)
        }
    }

    // MARK: - Auto correction

    private func scheduleIdle() {
        idleTimer?.invalidate()
        idleTimer = Timer.scheduledTimer(withTimeInterval: settings.autoDelay, repeats: false) { [weak self] _ in
            self?.flushAuto()
        }
    }

    private func flushAuto(force: Bool = false) {
        idleTimer?.invalidate()
        idleTimer = nil
        guard isArmed, mode == .auto, state != .processing else { return }

        let text = autoBuffer
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            autoBuffer = ""
            setState(.idle)
            return
        }

        // Below the minimum length: don't fix automatically. Keep the buffer so
        // it can grow as the user keeps typing, and quietly tell them why.
        if !force, trimmed.count < settings.autoMinChars {
            setState(.idle)
            onAutoBelowThreshold?(trimmed.count, settings.autoMinChars)
            return
        }

        if case .needsSetup(let message) = settings.backendReadiness {
            autoBuffer = ""
            setState(.idle)
            onError?(message)
            return
        }

        let deleteCount = text.count
        typedDuringProcessing = false
        setState(.processing)

        runCorrection(text: text) { [weak self] corrected in
            guard let self else { return }
            if self.typedDuringProcessing {
                // The user kept typing or moved the cursor while we waited.
                // Discard this result; re-correct the (now larger) buffer later.
                self.resumeAutoAfterDiscard()
            } else if corrected == text {
                // Already correct, so don't erase and retype; just acknowledge.
                self.onNoChange?(text)
                self.autoBuffer = ""
                self.setState(.idle)
            } else {
                let appName = NSWorkspace.shared.frontmostApplication?.localizedName
                self.autoBuffer = ""
                // Stay in .processing while the synthetic backspaces + paste run so
                // we don't capture or act on keystrokes mid-replacement.
                let gen = self.generation
                TextReplacer.shared.replace(deleteCount: deleteCount, with: corrected) { [weak self] in
                    self?.finishAutoReplacement(
                        original: text, corrected: corrected, appName: appName, gen: gen
                    )
                }
            }
        }
    }

    /// Auto mode: the pending fix was discarded (user typed/moved while we
    /// waited). Resume capturing the now-larger buffer, or go idle if empty.
    private func resumeAutoAfterDiscard() {
        if autoBuffer.isEmpty {
            setState(.idle)
        } else {
            setState(.capturing)
            scheduleIdle()
            onAutoCountdown?(settings.autoDelay)
        }
    }

    /// Auto mode: runs on the main thread once the paste finishes. Records the
    /// correction and offers an in-place revert only if nothing invalidated it
    /// while the synthetic keystrokes were running.
    private func finishAutoReplacement(original: String, corrected: String, appName: String?, gen: Int) {
        if !typedDuringProcessing, gen == generation {
            revertable = (original: original, corrected: corrected)
        }
        onCorrectionApplied?(
            CorrectionRecord(original: original, corrected: corrected, appName: appName)
        )
        // If the user typed during the paste, pick that up for the next fix.
        if !autoBuffer.isEmpty, mode == .auto {
            setState(.capturing)
            scheduleIdle()
            onAutoCountdown?(settings.autoDelay)
        } else {
            setState(.idle)
        }
    }

    // MARK: - Manual correction

    private func finishManual() {
        let text = manualBuffer
        let deleteCount = text.count
        manualBuffer = ""

        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            setState(.idle)
            return
        }
        if case .needsSetup(let message) = settings.backendReadiness {
            setState(.idle)
            onError?(message)
            return
        }

        typedDuringProcessing = false
        setState(.processing)
        runCorrection(text: text) { [weak self] corrected in
            guard let self else { return }
            if self.typedDuringProcessing {
                // The cursor moved or the user typed during the wait; applying now
                // would delete the wrong text. Abandon this fix.
                self.setState(.idle)
                return
            }
            if corrected == text {
                self.onNoChange?(text)
                self.setState(.idle)
            } else {
                let appName = NSWorkspace.shared.frontmostApplication?.localizedName
                let gen = self.generation
                TextReplacer.shared.replace(deleteCount: deleteCount, with: corrected) { [weak self] in
                    guard let self else { return }
                    if !self.typedDuringProcessing, gen == self.generation {
                        self.revertable = (original: text, corrected: corrected)
                    }
                    self.onCorrectionApplied?(
                        CorrectionRecord(original: text, corrected: corrected, appName: appName)
                    )
                    self.setState(.idle)
                }
            }
        }
    }

    // MARK: - Fix selection (Accessibility)

    /// Look for highlighted text to fix. Tries the Accessibility API first (native
    /// apps), then falls back to copying the selection via Cmd+C (Electron/web
    /// apps like Cursor). Calls `completion(true)` if a selection was found and a
    /// fix was started, `completion(false)` if there's nothing selected.
    private func attemptSelectionFix(completion: @escaping (Bool) -> Void) {
        if let selection = AccessibilityText.focusedSelection() {
            beginSelectionFix(original: selection.text, selection: selection, restoreClipboard: nil)
            completion(true)
            return
        }
        DispatchQueue.global(qos: .userInitiated).async {
            let grabbed = ClipboardSelection.copyCurrentSelection()
            DispatchQueue.main.async {
                if let grabbed {
                    self.beginSelectionFix(original: grabbed.text, selection: nil, restoreClipboard: grabbed.restore)
                    completion(true)
                } else {
                    completion(false)
                }
            }
        }
    }

    private func beginSelectionFix(
        original: String,
        selection: AccessibilityText.Selection?,
        restoreClipboard: String?
    ) {
        if case .needsSetup(let message) = settings.backendReadiness {
            onError?(message)
            if let restoreClipboard { restorePasteboard(restoreClipboard) }
            return
        }
        revertable = nil
        typedDuringProcessing = false
        setState(.processing)

        runCorrection(text: original) { [weak self] corrected in
            guard let self else { return }
            if self.typedDuringProcessing {
                // Focus or the selection changed while we waited; replacing now
                // could clobber the wrong text. Abandon and restore the clipboard.
                if let restoreClipboard { self.restorePasteboard(restoreClipboard) }
                self.setState(.idle)
                return
            }
            if corrected == original {
                self.onNoChange?(original)
                if let restoreClipboard { self.restorePasteboard(restoreClipboard) }
                self.setState(.idle)
                return
            }
            let appName = NSWorkspace.shared.frontmostApplication?.localizedName
            let replacedViaAX = selection.map {
                AccessibilityText.replaceSelection(in: $0.element, with: corrected)
            } ?? false
            if replacedViaAX {
                if let restoreClipboard { self.restorePasteboard(restoreClipboard) }
                self.revertable = (original: original, corrected: corrected)
                self.onCorrectionApplied?(
                    CorrectionRecord(original: original, corrected: corrected, appName: appName)
                )
                self.setState(.idle)
            } else {
                // Paste over the still-active selection (works in Cursor etc.).
                let gen = self.generation
                TextReplacer.shared.replaceSelectionByPasting(
                    corrected, restoreClipboard: restoreClipboard
                ) { [weak self] in
                    guard let self else { return }
                    if !self.typedDuringProcessing, gen == self.generation {
                        self.revertable = (original: original, corrected: corrected)
                    }
                    self.onCorrectionApplied?(
                        CorrectionRecord(original: original, corrected: corrected, appName: appName)
                    )
                    self.setState(.idle)
                }
            }
        }
    }

    private func restorePasteboard(_ contents: String) {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(contents, forType: .string)
    }

    // MARK: - Shared

    private func runCorrection(text: String, onSuccess: @escaping (String) -> Void) {
        let config = settings.makeCorrectionConfig()
        let autoFixTypos = settings.autoFixResidualTypos
        let protectedWords = settings.protectedWords
        let prompt = CorrectionText.composedPrompt(protectedWords: protectedWords)
        let gen = generation
        Task { [weak self] in
            guard let self else { return }
            do {
                var corrected = try await self.corrector.correct(text, config: config, systemPrompt: prompt)
                // Off the main thread: optionally auto-fix any leftover misspelling
                // with the system's top suggestion before it gets typed.
                if autoFixTypos {
                    corrected = TypoChecker.autoFixed(corrected, allowing: protectedWords)
                }
                await MainActor.run {
                    guard gen == self.generation else {
                        // A hard reset happened while we waited; its initiator
                        // already cleared the buffers, so just settle the state.
                        if self.state == .processing { self.setState(.idle) }
                        return
                    }
                    onSuccess(corrected)
                }
            } catch {
                await MainActor.run {
                    guard gen == self.generation else {
                        if self.state == .processing { self.setState(.idle) }
                        return
                    }
                    self.autoBuffer = "" // avoid retry storms on a bad key/model
                    self.setState(.idle)
                    let message = (error as? CorrectorError)?.message ?? error.localizedDescription
                    self.onError?(message)
                }
            }
        }
    }
}
