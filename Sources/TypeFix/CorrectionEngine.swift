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

    // Manual mode
    private var manualBuffer = ""

    // Auto mode
    private var autoBuffer = ""
    private var idleTimer: Timer?
    private var typedDuringProcessing = false

    private var lastMode: CorrectionMode

    init(settings: AppSettings) {
        self.settings = settings
        self.lastMode = settings.correctionMode

        tap.shouldCapture = { [weak self] in self?.shouldCapture() ?? false }
        tap.isArmed = { [weak self] in self?.isArmed ?? false }
        tap.currentHotkey = { [weak self] in self?.settings.hotkey ?? .bothShifts }
        tap.onHotkey = { [weak self] in self?.handleHotkey() }
        tap.onCopyLast = { [weak self] in self?.onCopyLast?() }
        tap.onCharacters = { [weak self] characters in self?.handleCharacters(characters) }
        tap.onBackspace = { [weak self] in self?.handleBackspace() }
        tap.onEnter = { [weak self] in self?.handleEnter() }
        tap.onTab = { [weak self] in self?.handleTab() }
        tap.onCancel = { [weak self] in self?.handleEscape() }
        tap.onNavigation = { [weak self] in self?.handleNavigation() }
    }

    /// The app is active whenever it is running.
    var isArmed: Bool { true }
    var mode: CorrectionMode { settings.correctionMode }

    @discardableResult
    func start() -> Bool { tap.start() }

    /// Menu equivalent of pressing both Shift keys.
    func triggerHotkey() { handleHotkey() }

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
        case .manual: return state == .capturing
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
        if state == .processing {
            typedDuringProcessing = true // abort any in-flight replacement
        } else {
            setState(.idle)
        }
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
        switch mode {
        case .manual:
            switch state {
            case .idle:
                manualBuffer = ""
                setState(.capturing)
            case .capturing:
                finishManual()
            case .processing:
                break
            }
        case .auto:
            flushAuto(force: true) // explicit "fix now" ignores the length threshold
        }
    }

    private func handleCharacters(_ characters: String) {
        syncModeIfNeeded()
        guard isArmed else { return }
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

    private func handleBackspace() {
        syncModeIfNeeded()
        guard isArmed else { return }
        switch mode {
        case .manual:
            guard state == .capturing, !manualBuffer.isEmpty else { return }
            manualBuffer.removeLast()
        case .auto:
            if state == .processing { typedDuringProcessing = true }
            if !autoBuffer.isEmpty { autoBuffer.removeLast() }
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

    private func handleEnter() {
        syncModeIfNeeded()
        guard isArmed else { return }
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
                if self.autoBuffer.isEmpty {
                    self.setState(.idle)
                } else {
                    self.setState(.capturing)
                    self.scheduleIdle()
                    self.onAutoCountdown?(self.settings.autoDelay)
                }
            } else if corrected == text {
                // Already correct, so don't erase and retype; just acknowledge.
                self.onNoChange?(text)
                self.autoBuffer = ""
                self.setState(.idle)
            } else {
                let appName = NSWorkspace.shared.frontmostApplication?.localizedName
                TextReplacer.shared.replace(deleteCount: deleteCount, with: corrected)
                self.onCorrectionApplied?(
                    CorrectionRecord(original: text, corrected: corrected, appName: appName)
                )
                self.autoBuffer = ""
                self.setState(.idle)
            }
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

        setState(.processing)
        runCorrection(text: text) { [weak self] corrected in
            guard let self else { return }
            if corrected == text {
                self.onNoChange?(text)
            } else {
                let appName = NSWorkspace.shared.frontmostApplication?.localizedName
                TextReplacer.shared.replace(deleteCount: deleteCount, with: corrected)
                self.onCorrectionApplied?(
                    CorrectionRecord(original: text, corrected: corrected, appName: appName)
                )
            }
            self.setState(.idle)
        }
    }

    // MARK: - Shared

    private func runCorrection(text: String, onSuccess: @escaping (String) -> Void) {
        let config = settings.makeCorrectionConfig()
        let autoFixTypos = settings.autoFixResidualTypos
        Task { [weak self] in
            guard let self else { return }
            do {
                var corrected = try await self.corrector.correct(text, config: config)
                // Off the main thread: optionally auto-fix any leftover misspelling
                // with the system's top suggestion before it gets typed.
                if autoFixTypos {
                    corrected = TypoChecker.autoFixed(corrected)
                }
                await MainActor.run { onSuccess(corrected) }
            } catch {
                await MainActor.run {
                    self.autoBuffer = "" // avoid retry storms on a bad key/model
                    self.setState(.idle)
                    let message = (error as? CorrectorError)?.message ?? error.localizedDescription
                    self.onError?(message)
                }
            }
        }
    }
}
