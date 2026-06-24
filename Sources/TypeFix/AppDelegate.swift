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
    private var permissionTimer: Timer?

    // Menu items we update as state changes.
    private let statusMenuItem = NSMenuItem(title: "", action: nil, keyEquivalent: "")
    private let modeHintItem = NSMenuItem(title: "", action: nil, keyEquivalent: "")
    private let toggleCaptureItem = NSMenuItem(title: "", action: #selector(toggleCapture), keyEquivalent: "")
    private let armedItem = NSMenuItem(title: "Enabled", action: #selector(toggleArmed), keyEquivalent: "")
    private let autoModeItem = NSMenuItem(title: "Auto-fix on pause", action: #selector(toggleAutoMode), keyEquivalent: "")

    func applicationDidFinishLaunching(_ notification: Notification) {
        setupMainMenu()
        setupStatusItem()

        engine.onStateChange = { [weak self] in self?.refreshUI() }
        engine.onError = { [weak self] message in self?.handleError(message) }
        engine.onCorrectionApplied = { [weak self] record in
            self?.history.add(record)
            self?.hud.flashFixed()
        }
        engine.onAutoCountdown = { [weak self] delay in
            self?.hud.beginCountdown(total: delay)
        }
        engine.onAutoBelowThreshold = { [weak self] count, threshold in
            self?.hud.flashTooShort(count: count, threshold: threshold)
        }

        startEngineOrRequestPermission()
        refreshUI()
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

    // MARK: - Status bar

    private func setupStatusItem() {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)

        let menu = NSMenu()
        statusMenuItem.isEnabled = false
        menu.addItem(statusMenuItem)
        modeHintItem.isEnabled = false
        menu.addItem(modeHintItem)
        menu.addItem(.separator())

        armedItem.target = self
        armedItem.image = menuSymbol("power")
        armedItem.toolTip = "Master switch. When off, TypeFix ignores the shortcut and does nothing."
        menu.addItem(armedItem)

        autoModeItem.target = self
        autoModeItem.image = menuSymbol("wand.and.stars")
        autoModeItem.toolTip = "On: TypeFix fixes your text automatically after you stop typing.\nOff: you trigger each fix with the shortcut."
        menu.addItem(autoModeItem)

        menu.addItem(.separator())

        toggleCaptureItem.target = self
        menu.addItem(toggleCaptureItem)

        menu.addItem(.separator())

        let historyItem = NSMenuItem(title: "History…", action: #selector(openHistory), keyEquivalent: "y")
        historyItem.target = self
        menu.addItem(historyItem)

        let settingsItem = NSMenuItem(title: "Settings…", action: #selector(openSettings), keyEquivalent: ",")
        settingsItem.target = self
        menu.addItem(settingsItem)

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
        let symbol: String
        let statusText: String
        let hintText: String

        if !trusted {
            symbol = "exclamationmark.triangle"
            statusText = "TypeFix — needs permission"
            hintText = "Grant Accessibility below; TypeFix then turns on automatically."
        } else if !engine.isArmed {
            symbol = "keyboard.badge.ellipsis"
            statusText = "TypeFix — off"
            hintText = "Switch on “Enabled” to use the \(sym) shortcut."
        } else {
            switch engine.state {
            case .idle:
                symbol = "keyboard"
                switch engine.mode {
                case .auto:
                    statusText = "TypeFix — on · Auto"
                    hintText = "Just type — your text is fixed shortly after you pause."
                case .manual:
                    statusText = "TypeFix — on · Manual"
                    hintText = "Press \(sym), type, then \(sym) again to fix."
                }
            case .capturing:
                switch engine.mode {
                case .auto:
                    symbol = "pencil.line"
                    statusText = "TypeFix — on · Auto"
                    hintText = "Pause for a moment and I'll fix what you typed."
                case .manual:
                    symbol = "record.circle"
                    statusText = "TypeFix — recording…"
                    hintText = "Press \(sym) again to fix what you typed."
                }
            case .processing:
                symbol = "sparkles"
                statusText = "TypeFix — correcting…"
                hintText = "Asking \(settings.provider.displayName) to clean it up…"
            }
        }

        if let button = statusItem.button {
            button.image = NSImage(systemSymbolName: symbol, accessibilityDescription: "TypeFix")
            button.image?.isTemplate = true
        }

        statusMenuItem.title = statusText
        modeHintItem.attributedTitle = NSAttributedString(
            string: hintText,
            attributes: [
                .font: NSFont.menuFont(ofSize: NSFont.smallSystemFontSize),
                .foregroundColor: NSColor.secondaryLabelColor,
            ]
        )

        armedItem.state = engine.isArmed ? .on : .off
        autoModeItem.state = engine.mode == .auto ? .on : .off
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
        toggleCaptureItem.isEnabled = trusted && engine.isArmed

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

    @objc private func toggleArmed() {
        engine.setArmed(!engine.isArmed)
    }

    @objc private func toggleAutoMode() {
        engine.setMode(engine.mode == .auto ? .manual : .auto)
    }

    @objc private func openSettings() {
        if settingsWindow == nil {
            let hosting = NSHostingController(rootView: SettingsView(settings: settings))
            let window = NSWindow(contentViewController: hosting)
            window.title = "TypeFix Settings"
            window.styleMask = [.titled, .closable, .miniaturizable]
            window.isReleasedWhenClosed = false
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
