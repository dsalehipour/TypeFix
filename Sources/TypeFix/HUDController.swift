import AppKit

/// A small floating, non-activating overlay that shows TypeFix's activity.
///
/// In Auto mode it acts like a traffic light:
///   🟢 green  — you're typing
///   🟡 yellow — counting down (about to fix), shows the remaining seconds
///   🔴 red    — thinking (calling the API), with a spinner
///
/// It never becomes key and never activates the app, so it does not steal focus
/// from the field you're typing into.
final class HUDController {
    private var panel: NSPanel?
    private let iconView = NSImageView()
    private let spinner = NSProgressIndicator()
    private let label = NSTextField(labelWithString: "")

    private var hideTimer: Timer?
    private var flashUntil: Date?

    private var countdownTimer: Timer?
    private var countdownDeadline: Date?
    private var countdownTotal: TimeInterval = 1.5

    /// Symbol shown in the manual "Recording" message; set by the app before `update`.
    var hotkeySymbol: String = "⇧⇧"

    // A separate, deliberately smaller/dimmer pill for low-importance notes
    // (e.g. "too short to fix") so it reads as clearly different from the main HUD.
    private var notePanel: NSPanel?
    private let noteLabel = NSTextField(labelWithString: "")
    private let noteIcon = NSImageView()
    private var noteHideTimer: Timer?

    private enum Light {
        case green, yellow, red
        var color: NSColor {
            switch self {
            case .green: return .systemGreen
            case .yellow: return .systemYellow
            case .red: return .systemRed
            }
        }
    }

    // MARK: - State-driven

    func update(state: CaptureState, mode: CorrectionMode, armed: Bool, trusted: Bool) {
        guard trusted, armed else { stopCountdown(); hide(); hideNote(); return }

        switch state {
        case .idle:
            if let flashUntil, Date() < flashUntil { return } // keep a flash visible
            stopCountdown()
            hide()
        case .capturing:
            switch mode {
            case .manual:
                stopCountdown()
                show(symbol: "record.circle", tint: .systemRed, spinning: false,
                     text: "Recording · \(hotkeySymbol) to fix")
            case .auto:
                // The countdown timer drives the green/yellow display; only paint a
                // green light here if a countdown isn't already running.
                if countdownDeadline == nil {
                    showLight(.green, text: "Typing…")
                }
            }
        case .processing:
            stopCountdown()
            show(symbol: "circle.fill", tint: .systemRed, spinning: true, text: "Thinking…")
        }
    }

    // MARK: - Auto countdown (green → yellow)

    /// Called on each Auto-mode keystroke; (re)starts the visual countdown.
    func beginCountdown(total: TimeInterval) {
        countdownTotal = max(total, 0.1)
        countdownDeadline = Date().addingTimeInterval(countdownTotal)
        flashUntil = nil
        hideTimer?.invalidate()
        hideTimer = nil
        if countdownTimer == nil {
            countdownTimer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] _ in
                self?.renderCountdown()
            }
        }
        renderCountdown()
    }

    private func renderCountdown() {
        guard let deadline = countdownDeadline else { stopCountdown(); return }
        let remaining = deadline.timeIntervalSinceNow
        let elapsed = countdownTotal - remaining

        if remaining <= 0 {
            showLight(.yellow, text: "Fixing…", fixedWidth: 180)
        } else if elapsed < 0.3 {
            showLight(.green, text: "Typing…", fixedWidth: 180)
        } else {
            showLight(.yellow, text: String(format: "Fixing in %.1fs", remaining), fixedWidth: 180)
        }
    }

    private func stopCountdown() {
        countdownTimer?.invalidate()
        countdownTimer = nil
        countdownDeadline = nil
    }

    // MARK: - Flashes

    func flashFixed() {
        stopCountdown()
        flashUntil = Date().addingTimeInterval(1.4)
        show(symbol: "checkmark.circle.fill", tint: .systemGreen, spinning: false, text: "Fixed")
        scheduleHide(after: 1.4)
    }

    func flashError(_ message: String) {
        stopCountdown()
        flashUntil = Date().addingTimeInterval(2.5)
        show(symbol: "exclamationmark.triangle.fill", tint: .systemOrange, spinning: false, text: message)
        scheduleHide(after: 2.5)
    }

    func flashCopied() {
        stopCountdown()
        flashUntil = Date().addingTimeInterval(1.4)
        show(symbol: "doc.on.doc.fill", tint: .controlAccentColor, spinning: false, text: "Copied original text")
        scheduleHide(after: 1.4)
    }

    /// Shown when the text was already correct and nothing was changed.
    func flashAllGood() {
        stopCountdown()
        flashUntil = Date().addingTimeInterval(1.3)
        show(symbol: "checkmark.seal.fill", tint: .systemGreen, spinning: false, text: "Looks good")
        scheduleHide(after: 1.3)
    }

    func hide() {
        hideTimer?.invalidate()
        hideTimer = nil
        flashUntil = nil
        spinner.stopAnimation(nil)
        panel?.orderOut(nil)
    }

    // MARK: - Low-importance note (visually distinct)

    /// Quietly note that the text is too short to auto-fix yet.
    func flashTooShort(count: Int, threshold: Int) {
        showNote("Too short to fix · \(count)/\(threshold)")
    }

    /// A low-importance grey note for transient information.
    func flashInfo(_ text: String) {
        showNote(text)
    }

    private func showNote(_ text: String) {
        stopCountdown()
        hide() // clear the main pill; the note replaces it
        let panel = notePanelOrCreate()
        noteLabel.stringValue = text

        panel.layoutIfNeeded()
        let fitting = panel.contentView?.fittingSize ?? NSSize(width: 150, height: 24)
        panel.setContentSize(NSSize(width: max(fitting.width, 120), height: 26))
        if let screen = NSScreen.main {
            let visible = screen.visibleFrame
            panel.setFrameOrigin(NSPoint(x: visible.midX - panel.frame.width / 2, y: visible.minY + 50))
        }
        panel.alphaValue = 0.92
        panel.orderFrontRegardless()

        noteHideTimer?.invalidate()
        noteHideTimer = Timer.scheduledTimer(withTimeInterval: 1.8, repeats: false) { [weak self] _ in
            self?.hideNote()
        }
    }

    private func hideNote() {
        noteHideTimer?.invalidate()
        noteHideTimer = nil
        notePanel?.orderOut(nil)
    }

    private func notePanelOrCreate() -> NSPanel {
        if let notePanel { return notePanel }

        let panel = NSPanel(
            contentRect: NSRect(x: 0, y: 0, width: 150, height: 26),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        panel.level = .statusBar
        panel.isFloatingPanel = true
        panel.hidesOnDeactivate = false
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.ignoresMouseEvents = true
        panel.hasShadow = false // softer than the main pill
        panel.collectionBehavior = [.canJoinAllSpaces, .stationary, .fullScreenAuxiliary, .ignoresCycle]

        let content = panel.contentView!

        let effect = NSVisualEffectView()
        effect.translatesAutoresizingMaskIntoConstraints = false
        effect.material = .popover // different material than the main HUD
        effect.blendingMode = .behindWindow
        effect.state = .active
        effect.wantsLayer = true
        effect.layer?.cornerRadius = 9
        effect.layer?.masksToBounds = true
        content.addSubview(effect)

        noteIcon.translatesAutoresizingMaskIntoConstraints = false
        noteIcon.image = NSImage(systemSymbolName: "info.circle", accessibilityDescription: nil)
        noteIcon.symbolConfiguration = NSImage.SymbolConfiguration(pointSize: 10, weight: .regular)
        noteIcon.contentTintColor = .tertiaryLabelColor
        noteIcon.setContentHuggingPriority(.required, for: .horizontal)

        noteLabel.font = .systemFont(ofSize: 11, weight: .regular)
        noteLabel.textColor = .secondaryLabelColor
        noteLabel.lineBreakMode = .byTruncatingTail
        noteLabel.maximumNumberOfLines = 1
        noteLabel.translatesAutoresizingMaskIntoConstraints = false

        let stack = NSStackView(views: [noteIcon, noteLabel])
        stack.orientation = .horizontal
        stack.alignment = .centerY
        stack.spacing = 5
        stack.translatesAutoresizingMaskIntoConstraints = false
        effect.addSubview(stack)

        NSLayoutConstraint.activate([
            effect.leadingAnchor.constraint(equalTo: content.leadingAnchor),
            effect.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            effect.topAnchor.constraint(equalTo: content.topAnchor),
            effect.bottomAnchor.constraint(equalTo: content.bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: effect.leadingAnchor, constant: 11),
            stack.trailingAnchor.constraint(equalTo: effect.trailingAnchor, constant: -11),
            stack.centerYAnchor.constraint(equalTo: effect.centerYAnchor),
        ])

        self.notePanel = panel
        return panel
    }

    // MARK: - Rendering

    private func showLight(_ light: Light, text: String, fixedWidth: CGFloat? = nil) {
        show(symbol: "circle.fill", tint: light.color, spinning: false, text: text, fixedWidth: fixedWidth)
    }

    private func show(symbol: String?, tint: NSColor?, spinning: Bool, text: String, fixedWidth: CGFloat? = nil) {
        let panel = panelOrCreate()

        label.stringValue = text
        if let symbol {
            iconView.image = NSImage(systemSymbolName: symbol, accessibilityDescription: nil)
            iconView.contentTintColor = tint
            iconView.isHidden = false
        } else {
            iconView.isHidden = true
        }
        if spinning {
            spinner.startAnimation(nil)
        } else {
            spinner.stopAnimation(nil)
        }
        spinner.isHidden = !spinning

        let width: CGFloat
        if let fixedWidth {
            width = fixedWidth
        } else {
            panel.layoutIfNeeded()
            let fitting = panel.contentView?.fittingSize ?? NSSize(width: 200, height: 44)
            width = max(fitting.width, 130)
        }
        panel.setContentSize(NSSize(width: width, height: 44))

        if let screen = NSScreen.main {
            let visible = screen.visibleFrame
            panel.setFrameOrigin(NSPoint(x: visible.midX - panel.frame.width / 2, y: visible.minY + 50))
        }
        panel.orderFrontRegardless()
    }

    private func scheduleHide(after seconds: TimeInterval) {
        hideTimer?.invalidate()
        hideTimer = Timer.scheduledTimer(withTimeInterval: seconds, repeats: false) { [weak self] _ in
            self?.hide()
        }
    }

    private func panelOrCreate() -> NSPanel {
        if let panel { return panel }

        let panel = NSPanel(
            contentRect: NSRect(x: 0, y: 0, width: 180, height: 44),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        panel.level = .statusBar
        panel.isFloatingPanel = true
        panel.hidesOnDeactivate = false
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.ignoresMouseEvents = true
        panel.hasShadow = true
        panel.collectionBehavior = [.canJoinAllSpaces, .stationary, .fullScreenAuxiliary, .ignoresCycle]

        let content = panel.contentView!

        let effect = NSVisualEffectView()
        effect.translatesAutoresizingMaskIntoConstraints = false
        effect.material = .hudWindow
        effect.blendingMode = .behindWindow
        effect.state = .active
        effect.wantsLayer = true
        effect.layer?.cornerRadius = 14
        effect.layer?.masksToBounds = true
        content.addSubview(effect)

        iconView.translatesAutoresizingMaskIntoConstraints = false
        iconView.symbolConfiguration = NSImage.SymbolConfiguration(pointSize: 14, weight: .semibold)
        iconView.setContentHuggingPriority(.required, for: .horizontal)
        iconView.setContentCompressionResistancePriority(.required, for: .horizontal)

        spinner.style = .spinning
        spinner.controlSize = .small
        spinner.isDisplayedWhenStopped = false
        spinner.translatesAutoresizingMaskIntoConstraints = false
        spinner.setContentHuggingPriority(.required, for: .horizontal)

        label.font = .systemFont(ofSize: 13, weight: .medium)
        label.textColor = .labelColor
        label.lineBreakMode = .byTruncatingTail
        label.maximumNumberOfLines = 1
        label.translatesAutoresizingMaskIntoConstraints = false

        let stack = NSStackView(views: [iconView, spinner, label])
        stack.orientation = .horizontal
        stack.alignment = .centerY
        stack.spacing = 8
        stack.translatesAutoresizingMaskIntoConstraints = false
        effect.addSubview(stack)

        NSLayoutConstraint.activate([
            effect.leadingAnchor.constraint(equalTo: content.leadingAnchor),
            effect.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            effect.topAnchor.constraint(equalTo: content.topAnchor),
            effect.bottomAnchor.constraint(equalTo: content.bottomAnchor),
            stack.leadingAnchor.constraint(equalTo: effect.leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(lessThanOrEqualTo: effect.trailingAnchor, constant: -16),
            stack.centerYAnchor.constraint(equalTo: effect.centerYAnchor),
        ])

        self.panel = panel
        return panel
    }
}
