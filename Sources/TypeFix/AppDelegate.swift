import AppKit
import SwiftUI

final class AppDelegate: NSObject, NSApplicationDelegate, NSMenuDelegate {
    private let settings = AppSettings()
    private let history = HistoryStore()
    private let hud = HUDController()
    private lazy var engine = CorrectionEngine(settings: settings)

    private var statusItem: NSStatusItem!
    private var settingsWindow: NSWindow?
    private var historyWindow: NSWindow?
    private var onboardingWindow: NSWindow?
    private var permissionTimer: Timer?

    // Menu items we update as state changes.
    private let noticeItem = NSMenuItem(title: "", action: nil, keyEquivalent: "")
    private let noticeSeparator = NSMenuItem.separator()
    private let toggleCaptureItem = NSMenuItem(title: "", action: #selector(toggleCapture), keyEquivalent: "")
    private let autoModeItem = NSMenuItem(title: "Enable Autofix", action: #selector(toggleAutoMode), keyEquivalent: "")
    private let pauseItem = NSMenuItem(title: "Pause TypeFix", action: #selector(togglePause), keyEquivalent: "")
    private let disableAppItem = NSMenuItem(title: "Disable in this app", action: #selector(toggleDisableApp), keyEquivalent: "")
    private let copyLastItem = NSMenuItem(title: "Copy last original", action: #selector(copyLastOriginal), keyEquivalent: "")
    private let undoFixItem = NSMenuItem(title: "Undo last fix", action: #selector(undoLastFix), keyEquivalent: "")

    func applicationDidFinishLaunching(_ notification: Notification) {
        setupMainMenu()
        setupStatusItem()
        setupServices()

        engine.onStateChange = { [weak self] in self?.refreshUI() }
        engine.onError = { [weak self] message in self?.handleError(message) }
        engine.onCorrectionApplied = { [weak self] record in
            guard let self else { return }
            // Post-LLM guardrail. Run the spell check OFF the main thread, since it can
            // block, and the main thread services the keystroke tap. Non-destructive.
            guard self.settings.spellCheckAfterCorrection else {
                self.history.add(record)
                self.hud.flashFixed()
                return
            }
            let corrected = record.corrected
            let allowed = self.settings.protectedWords
            DispatchQueue.global(qos: .userInitiated).async {
                let flagged = TypoChecker.hasLikelyTypo(in: corrected, allowing: allowed)
                DispatchQueue.main.async {
                    var record = record
                    record.flaggedResidualTypo = flagged
                    self.history.add(record)
                    flagged ? self.hud.flashFixedButCheck() : self.hud.flashFixed()
                }
            }
        }
        engine.onAutoCountdown = { [weak self] delay in
            self?.hud.beginCountdown(total: delay)
        }
        engine.onAutoBelowThreshold = { [weak self] count, threshold in
            self?.hud.flashTooShort(count: count, threshold: threshold)
        }
        engine.onCopyLast = { [weak self] in self?.copyLastOriginal() }
        engine.onNoChange = { [weak self] text in
            guard let self else { return }
            guard self.settings.spellCheckAfterCorrection else {
                self.hud.flashAllGood()
                return
            }
            let allowed = self.settings.protectedWords
            DispatchQueue.global(qos: .userInitiated).async {
                let flagged = TypoChecker.hasLikelyTypo(in: text, allowing: allowed)
                DispatchQueue.main.async {
                    flagged ? self.hud.flashPossibleTypo() : self.hud.flashAllGood()
                }
            }
        }
        engine.onReverted = { [weak self] in self?.hud.flashReverted() }

        startEngineOrRequestPermission()
        refreshUI()
        showOnboardingIfFirstLaunch()
    }

    // MARK: - Main menu

    /// Accessory (menu-bar) apps get no main menu by default, which means the
    /// standard Cut/Copy/Paste/Select-All shortcuts have nothing to dispatch to
    /// and macOS beeps. Installing an Edit menu wires those key equivalents to
    /// the focused text field's responder chain (the menu bar itself stays
    /// hidden for an accessory app).
    private func setupMainMenu() {
        let mainMenu = NSMenu()

        let appMenuItem = NSMenuItem()
        mainMenu.addItem(appMenuItem)
        let appMenu = NSMenu()
        appMenu.addItem(withTitle: "Quit TypeFix", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        appMenuItem.submenu = appMenu

        let editMenuItem = NSMenuItem()
        mainMenu.addItem(editMenuItem)
        let editMenu = NSMenu(title: "Edit")
        editMenu.addItem(withTitle: "Undo", action: Selector(("undo:")), keyEquivalent: "z")
        editMenu.addItem(withTitle: "Redo", action: Selector(("redo:")), keyEquivalent: "Z")
        editMenu.addItem(.separator())
        editMenu.addItem(withTitle: "Cut", action: Selector(("cut:")), keyEquivalent: "x")
        editMenu.addItem(withTitle: "Copy", action: Selector(("copy:")), keyEquivalent: "c")
        editMenu.addItem(withTitle: "Paste", action: Selector(("paste:")), keyEquivalent: "v")
        editMenu.addItem(withTitle: "Select All", action: Selector(("selectAll:")), keyEquivalent: "a")
        editMenuItem.submenu = editMenu

        NSApp.mainMenu = mainMenu
    }

    // MARK: - Services (system-wide "Fix with TypeFix")

    /// Register the macOS Services provider so "Fix with TypeFix" appears in the
    /// right-click / Services menu whenever text is selected in any app.
    private func setupServices() {
        NSApp.servicesProvider = self
        NSUpdateDynamicServices()
    }

    /// Invoked by the system when the user picks Services → Fix with TypeFix.
    /// We don't return text via the pasteboard; instead we fix the still-active
    /// selection in place (Accessibility, with a clipboard-paste fallback), which
    /// matches the hotkey behavior and works in editable fields.
    @objc func fixSelectedText(
        _ pboard: NSPasteboard,
        userData: String?,
        error: AutoreleasingUnsafeMutablePointer<NSString?>?
    ) {
        guard AXIsProcessTrusted() else {
            handleError("Grant Accessibility to TypeFix first.")
            requestAccessibilityPrompt()
            return
        }
        engine.fixSelectionNow()
    }

    // MARK: - Status bar

    private func setupStatusItem() {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)

        let menu = NSMenu()

        noticeItem.isEnabled = false
        menu.addItem(noticeItem)
        menu.addItem(noticeSeparator)

        autoModeItem.target = self
        autoModeItem.image = menuSymbol("wand.and.stars")
        autoModeItem.toolTip = "On: TypeFix fixes your text automatically after you stop typing.\nOff: you trigger each fix with the shortcut."
        menu.addItem(autoModeItem)

        toggleCaptureItem.target = self
        menu.addItem(toggleCaptureItem)

        menu.addItem(.separator())

        pauseItem.target = self
        menu.addItem(pauseItem)

        disableAppItem.target = self
        menu.addItem(disableAppItem)

        menu.addItem(.separator())

        copyLastItem.target = self
        copyLastItem.image = menuSymbol("doc.on.doc")
        copyLastItem.keyEquivalent = "c"
        copyLastItem.keyEquivalentModifierMask = [.command, .shift, .option]
        copyLastItem.toolTip = "Copy the original text of the most recent fix, in case the correction wasn't what you wanted."
        menu.addItem(copyLastItem)

        undoFixItem.target = self
        undoFixItem.image = menuSymbol("arrow.uturn.backward")
        undoFixItem.keyEquivalent = "z"
        undoFixItem.keyEquivalentModifierMask = [.command, .shift]
        undoFixItem.toolTip = "Revert the most recent fix back to what you originally typed (only right after a fix)."
        menu.addItem(undoFixItem)

        menu.addItem(.separator())

        let historyItem = NSMenuItem(title: "History…", action: #selector(openHistory), keyEquivalent: "y")
        historyItem.target = self
        menu.addItem(historyItem)

        let settingsItem = NSMenuItem(title: "Settings…", action: #selector(openSettings), keyEquivalent: ",")
        settingsItem.target = self
        menu.addItem(settingsItem)

        let howItem = NSMenuItem(title: "How TypeFix works…", action: #selector(showOnboardingFromMenu), keyEquivalent: "")
        howItem.target = self
        menu.addItem(howItem)

        let accessibilityItem = NSMenuItem(
            title: "Open Accessibility Settings…",
            action: #selector(openAccessibilitySettings),
            keyEquivalent: ""
        )
        accessibilityItem.target = self
        menu.addItem(accessibilityItem)

        menu.addItem(.separator())

        let quitItem = NSMenuItem(title: "Quit TypeFix", action: #selector(quit), keyEquivalent: "q")
        quitItem.target = self
        menu.addItem(quitItem)

        menu.delegate = self
        statusItem.menu = menu
    }

    /// The TypeFix brand mark (text caret + sparkle) as a monochrome template
    /// image, drawn as vectors so it stays crisp at any menu-bar scale.
    private static func brandGlyph() -> NSImage {
        let size = NSSize(width: 18, height: 18)
        let image = NSImage(size: size, flipped: false) { _ in
            NSColor.black.setFill()

            // I-beam caret, positioned slightly left to leave room for the sparkle.
            let caretX: CGFloat = 6.6
            let barW: CGFloat = 1.7
            let serifW: CGFloat = 6.0
            let topY: CGFloat = 15.0
            let botY: CGFloat = 3.0
            NSBezierPath(rect: NSRect(x: caretX - barW / 2, y: botY, width: barW, height: topY - botY)).fill()
            NSBezierPath(rect: NSRect(x: caretX - serifW / 2, y: topY - barW, width: serifW, height: barW)).fill()
            NSBezierPath(rect: NSRect(x: caretX - serifW / 2, y: botY, width: serifW, height: barW)).fill()

            // Four-point sparkle at the upper right.
            let star = NSBezierPath()
            let cx: CGFloat = 13.4, cy: CGFloat = 12.6
            let outer: CGFloat = 3.3, inner: CGFloat = 1.15
            let points: [(CGFloat, CGFloat)] = [
                (90, outer), (45, inner), (0, outer), (315, inner),
                (270, outer), (225, inner), (180, outer), (135, inner),
            ]
            for (index, point) in points.enumerated() {
                let radians = point.0 * .pi / 180
                let p = NSPoint(x: cx + cos(radians) * point.1, y: cy + sin(radians) * point.1)
                if index == 0 { star.move(to: p) } else { star.line(to: p) }
            }
            star.close()
            star.fill()
            return true
        }
        image.isTemplate = true
        return image
    }

    private func menuSymbol(_ name: String) -> NSImage? {
        let config = NSImage.SymbolConfiguration(pointSize: 13, weight: .regular)
        let image = NSImage(systemSymbolName: name, accessibilityDescription: nil)?
            .withSymbolConfiguration(config)
        image?.isTemplate = true
        return image
    }

    func menuNeedsUpdate(_ menu: NSMenu) {
        refreshUI()
    }

    private func refreshUI() {
        let trusted = AXIsProcessTrusted()
        let sym = settings.hotkey.menuSymbol

        // Menu-bar icon reflects the live state. At rest it shows the TypeFix
        // brand mark (caret + sparkle); transient states use status symbols.
        if let button = statusItem.button {
            if trusted, !engine.isArmed {
                // Paused or disabled in the current app.
                let paused = NSImage(systemSymbolName: "pause.circle", accessibilityDescription: "TypeFix paused")
                paused?.isTemplate = true
                button.image = paused
            } else if trusted, engine.state == .idle {
                button.image = Self.brandGlyph()
            } else {
                let symbol: String
                if !trusted {
                    symbol = "exclamationmark.triangle"
                } else {
                    switch engine.state {
                    case .idle: symbol = "keyboard"
                    case .capturing: symbol = engine.mode == .auto ? "pencil.line" : "record.circle"
                    case .processing: symbol = "sparkles"
                    }
                }
                button.image = NSImage(systemSymbolName: symbol, accessibilityDescription: "TypeFix")
                button.image?.isTemplate = true
            }
        }

        // A small notice only when setup is incomplete or TypeFix is off; otherwise
        // the menu is clean.
        let notice: String?
        if !trusted {
            notice = "Grant Accessibility to start →"
        } else if engine.isPaused {
            notice = "Paused"
        } else if engine.isFrontmostAppDisabled, let app = engine.frontmostApp {
            notice = "Off in \(app.name)"
        } else {
            switch settings.backendReadiness {
            case .ready:
                notice = nil
            case .needsSetup(let message):
                notice = message + " →"
            }
        }
        if let notice {
            noticeItem.attributedTitle = NSAttributedString(
                string: notice,
                attributes: [
                    .font: NSFont.menuFont(ofSize: NSFont.smallSystemFontSize),
                    .foregroundColor: NSColor.secondaryLabelColor,
                ]
            )
            noticeItem.isHidden = false
            noticeSeparator.isHidden = false
        } else {
            noticeItem.isHidden = true
            noticeSeparator.isHidden = true
        }

        autoModeItem.title = engine.mode == .auto ? "Disable Autofix" : "Enable Autofix"
        autoModeItem.isEnabled = trusted

        switch engine.mode {
        case .auto:
            toggleCaptureItem.title = "Fix what I just typed (\(sym))"
            toggleCaptureItem.image = menuSymbol("sparkles")
            toggleCaptureItem.toolTip = "Correct the current text right now instead of waiting for a pause."
        case .manual:
            if engine.state == .capturing {
                toggleCaptureItem.title = "Finish & fix now (\(sym))"
                toggleCaptureItem.image = menuSymbol("checkmark.circle")
                toggleCaptureItem.toolTip = "Stop recording and correct what you just typed."
            } else {
                toggleCaptureItem.title = "Start a correction (\(sym))"
                toggleCaptureItem.image = menuSymbol("play.circle")
                toggleCaptureItem.toolTip = "Begin recording your typing. Press \(sym) again to fix it."
            }
        }
        toggleCaptureItem.isEnabled = trusted

        pauseItem.title = engine.isPaused ? "Resume TypeFix" : "Pause TypeFix"
        pauseItem.image = menuSymbol(engine.isPaused ? "play.circle" : "pause.circle")
        pauseItem.isEnabled = trusted

        if let app = engine.frontmostApp {
            let disabled = engine.isFrontmostAppDisabled
            disableAppItem.isHidden = false
            disableAppItem.title = disabled ? "Enable in \(app.name)" : "Disable in \(app.name)"
            disableAppItem.image = menuSymbol(disabled ? "checkmark.circle" : "nosign")
            disableAppItem.isEnabled = trusted && !engine.isPaused
        } else {
            disableAppItem.isHidden = true
        }

        copyLastItem.isEnabled = !history.records.isEmpty
        undoFixItem.isEnabled = engine.canRevert()

        hud.hotkeySymbol = sym
        hud.update(state: engine.state, mode: engine.mode, armed: engine.isArmed, trusted: trusted)
    }

    // MARK: - Permission flow

    private func startEngineOrRequestPermission() {
        if engine.start() {
            return
        }
        requestAccessibilityPrompt()

        // Poll until the tap can be installed (after the user grants permission),
        // so the app starts working without requiring a relaunch.
        permissionTimer = Timer.scheduledTimer(withTimeInterval: 1.5, repeats: true) { [weak self] timer in
            guard let self else { return }
            if self.engine.start() {
                timer.invalidate()
                self.permissionTimer = nil
            }
            self.refreshUI()
        }
    }

    private func requestAccessibilityPrompt() {
        let key = kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String
        _ = AXIsProcessTrustedWithOptions([key: true] as CFDictionary)
    }

    // MARK: - Menu actions

    @objc private func toggleCapture() {
        engine.triggerHotkey()
    }

    @objc private func toggleAutoMode() {
        engine.setMode(engine.mode == .auto ? .manual : .auto)
    }

    @objc private func togglePause() {
        engine.setPaused(!engine.isPaused)
    }

    @objc private func toggleDisableApp() {
        engine.toggleDisabledForFrontmostApp()
    }

    @objc private func copyLastOriginal() {
        guard let record = history.records.first else {
            hud.flashInfo("Nothing to copy yet")
            return
        }
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        pasteboard.setString(record.original, forType: .string)
        hud.flashCopied()
    }

    @objc private func undoLastFix() {
        guard engine.canRevert() else {
            hud.flashInfo("Nothing to undo")
            return
        }
        engine.revertLast()
    }

    @objc private func showOnboardingFromMenu() {
        showOnboarding()
    }

    @objc private func openSettings() {
        if settingsWindow == nil {
            let hosting = NSHostingController(rootView: SettingsView(settings: settings))
            let window = NSWindow(contentViewController: hosting)
            window.title = "TypeFix Settings"
            window.styleMask = [.titled, .closable, .miniaturizable, .resizable, .fullSizeContentView]
            window.isReleasedWhenClosed = false
            window.setContentSize(NSSize(width: 740, height: 580))
            window.center()
            settingsWindow = window
        }
        NSApp.activate(ignoringOtherApps: true)
        settingsWindow?.makeKeyAndOrderFront(nil)
    }

    @objc private func openHistory() {
        if historyWindow == nil {
            let hosting = NSHostingController(rootView: HistoryView(store: history))
            let window = NSWindow(contentViewController: hosting)
            window.title = "TypeFix History"
            window.styleMask = [.titled, .closable, .miniaturizable, .resizable]
            window.isReleasedWhenClosed = false
            window.center()
            historyWindow = window
        }
        NSApp.activate(ignoringOtherApps: true)
        historyWindow?.makeKeyAndOrderFront(nil)
    }

    @objc private func openAccessibilitySettings() {
        let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")!
        NSWorkspace.shared.open(url)
    }

    // MARK: - Onboarding

    private func showOnboardingIfFirstLaunch() {
        let key = "didShowWelcome"
        guard !UserDefaults.standard.bool(forKey: key) else { return }
        UserDefaults.standard.set(true, forKey: key)
        showOnboarding()
    }

    private func showOnboarding() {
        if onboardingWindow == nil {
            let view = OnboardingView(
                settings: settings,
                hotkeySymbol: settings.hotkey.menuSymbol,
                onOpenSettings: { [weak self] in self?.openSettings() },
                onOpenAccessibility: { [weak self] in self?.openAccessibilitySettings() },
                onDone: { [weak self] in self?.onboardingWindow?.close() }
            )
            let window = NSWindow(contentViewController: NSHostingController(rootView: view))
            window.title = "Welcome to TypeFix"
            window.styleMask = [.titled, .closable]
            window.isReleasedWhenClosed = false
            window.center()
            onboardingWindow = window
        }
        NSApp.activate(ignoringOtherApps: true)
        onboardingWindow?.makeKeyAndOrderFront(nil)
    }

    @objc private func quit() {
        NSApp.terminate(nil)
    }

    // MARK: - Errors

    /// Show errors via the non-blocking HUD. (A modal alert would freeze the main
    /// run loop that services our event tap, delaying the user's keystrokes.)
    private func handleError(_ message: String) {
        hud.flashError(message)
    }
}
