import AppKit
import SwiftUI

/// Observable state for the floating HUD capsule.
private final class HUDModel: ObservableObject {
    @Published var symbol: String?
    @Published var tint: Color = .secondary
    @Published var text: String = ""
    @Published var spinning: Bool = false
    /// Render a glowing colored dot (traffic-light states) instead of a symbol.
    @Published var useDot: Bool = false
}

/// A small floating, non-activating overlay that shows TypeFix's activity.
///
/// In Auto mode it acts like a traffic light:
///   🟢 green  - you're typing
///   🟡 yellow - counting down (about to fix), shows the remaining seconds
///   🔴 red    - thinking (calling the model), with a spinner
///
/// It never becomes key and never activates the app, so it does not steal focus
/// from the field you're typing into. Rendered as a modern translucent capsule.
final class HUDController {
    private let model = HUDModel()
    private let noteModel = HUDModel()

    private var panel: NSPanel?
    private var hostingView: NSHostingView<HUDContent>?

    private var notePanel: NSPanel?
    private var noteHostingView: NSHostingView<HUDNoteContent>?

    private var hideTimer: Timer?
    private var flashUntil: Date?

    private var countdownTimer: Timer?
    private var countdownDeadline: Date?
    private var countdownTotal: TimeInterval = 1.5

    private var noteHideTimer: Timer?

    /// Symbol shown in the manual "Recording" message; set by the app before `update`.
    var hotkeySymbol: String = "⇧⇧"

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
                show(symbol: "record.circle.fill", tint: .systemRed, spinning: false,
                     text: "Recording · \(hotkeySymbol) to fix")
            case .auto:
                if countdownDeadline == nil {
                    showLight(.green, text: "Typing…")
                }
            }
        case .processing:
            stopCountdown()
            show(symbol: nil, tint: .systemRed, spinning: true, text: "Thinking…")
        }
    }

    // MARK: - Auto countdown (green → yellow)

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
            showLight(.yellow, text: "Fixing…")
        } else if elapsed < 0.3 {
            showLight(.green, text: "Typing…")
        } else {
            showLight(.yellow, text: String(format: "Fixing in %.1fs", remaining))
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

    /// Like `flashFixed`, but warns that a likely typo may remain in the result.
    func flashFixedButCheck() {
        stopCountdown()
        flashUntil = Date().addingTimeInterval(2.0)
        show(symbol: "checkmark.circle.fill", tint: .systemOrange, spinning: false, text: "Fixed · check spelling")
        scheduleHide(after: 2.0)
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

    /// Shown when nothing was changed but a likely typo remains in the text.
    func flashPossibleTypo() {
        stopCountdown()
        flashUntil = Date().addingTimeInterval(2.0)
        show(symbol: "exclamationmark.triangle.fill", tint: .systemOrange, spinning: false, text: "Possible typo")
        scheduleHide(after: 2.0)
    }

    func hide() {
        hideTimer?.invalidate()
        hideTimer = nil
        flashUntil = nil
        model.spinning = false
        panel?.orderOut(nil)
    }

    // MARK: - Low-importance note (visually distinct)

    func flashTooShort(count: Int, threshold: Int) {
        showNote("Too short to fix · \(count)/\(threshold)")
    }

    func flashInfo(_ text: String) {
        showNote(text)
    }

    private func showNote(_ text: String) {
        stopCountdown()
        hide() // clear the main pill; the note replaces it
        _ = notePanelOrCreate()
        noteModel.text = text
        noteHostingView?.rootView = HUDNoteContent(model: noteModel)
        layoutAndShow(notePanel, hostingView: noteHostingView, pillPadding: 22)

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

    // MARK: - Rendering

    private func showLight(_ light: Light, text: String) {
        show(symbol: "circle.fill", tint: light.color, spinning: false, text: text)
    }

    private func show(symbol: String?, tint: NSColor?, spinning: Bool, text: String) {
        _ = panelOrCreate()
        let isDot = (symbol == "circle.fill") && !spinning
        model.text = text
        model.spinning = spinning
        model.useDot = isDot
        model.symbol = isDot ? nil : symbol
        if let tint { model.tint = Color(nsColor: tint) }
        // Reassigning rootView forces a synchronous re-measure; otherwise SwiftUI
        // updates async and fittingSize lags the new text, clipping longer pills.
        hostingView?.rootView = HUDContent(model: model)
        layoutAndShow(panel, hostingView: hostingView, pillPadding: 32)
    }

    private func scheduleHide(after seconds: TimeInterval) {
        hideTimer?.invalidate()
        hideTimer = Timer.scheduledTimer(withTimeInterval: seconds, repeats: false) { [weak self] _ in
            self?.hide()
        }
    }

    // MARK: - Panels

    /// Anchors the pill to the bottom-right of the screen. `pillPadding` is the
    /// transparent margin baked into the SwiftUI content (so the shadow isn't
    /// clipped); we subtract it so the visible capsule sits the desired gap from
    /// the screen edges.
    private func layoutAndShow<V: View>(_ panel: NSPanel?, hostingView: NSHostingView<V>?, pillPadding: CGFloat) {
        guard let panel, let hostingView else { return }
        hostingView.layoutSubtreeIfNeeded()
        let size = hostingView.fittingSize
        if abs(panel.frame.width - size.width) > 0.5 || abs(panel.frame.height - size.height) > 0.5 {
            panel.setContentSize(size)
        }
        if let screen = NSScreen.main {
            let visible = screen.visibleFrame
            let rightGap: CGFloat = 22
            let bottomGap: CGFloat = 22
            let x = visible.maxX - panel.frame.width + (pillPadding - rightGap)
            let y = visible.minY + (bottomGap - pillPadding)
            panel.setFrameOrigin(NSPoint(x: x, y: y))
        }
        panel.orderFrontRegardless()
    }

    private func makePanel(size: NSSize) -> NSPanel {
        let panel = NSPanel(
            contentRect: NSRect(origin: .zero, size: size),
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
        panel.hasShadow = false // the SwiftUI capsule draws its own shadow
        panel.collectionBehavior = [.canJoinAllSpaces, .stationary, .fullScreenAuxiliary, .ignoresCycle]
        return panel
    }

    @discardableResult
    private func panelOrCreate() -> NSPanel {
        if let panel { return panel }
        let hosting = NSHostingView(rootView: HUDContent(model: model))
        let panel = makePanel(size: hosting.fittingSize)
        panel.contentView = hosting
        self.hostingView = hosting
        self.panel = panel
        return panel
    }

    @discardableResult
    private func notePanelOrCreate() -> NSPanel {
        if let notePanel { return notePanel }
        let hosting = NSHostingView(rootView: HUDNoteContent(model: noteModel))
        let panel = makePanel(size: hosting.fittingSize)
        panel.contentView = hosting
        self.noteHostingView = hosting
        self.notePanel = panel
        return panel
    }
}

// MARK: - SwiftUI capsules

private struct HUDContent: View {
    @ObservedObject var model: HUDModel

    var body: some View {
        HStack(spacing: 9) {
            glyph
            Text(model.text)
                .font(.system(size: 13, weight: .semibold).monospacedDigit())
                .foregroundStyle(.primary)
                .fixedSize()
        }
        .padding(.horizontal, 15)
        .padding(.vertical, 9)
        .background(Capsule(style: .continuous).fill(.regularMaterial))
        .overlay(Capsule(style: .continuous).strokeBorder(.primary.opacity(0.08), lineWidth: 1))
        .shadow(color: .black.opacity(0.22), radius: 12, y: 4)
        // Generous transparent margin so the shadow fully fades before the
        // (rectangular) panel edge - otherwise the shadow looks clipped.
        .padding(32)
        .fixedSize()
    }

    @ViewBuilder private var glyph: some View {
        if model.spinning {
            ProgressView()
                .controlSize(.small)
                .scaleEffect(0.8)
                .frame(width: 14, height: 14)
        } else if model.useDot {
            Circle()
                .fill(model.tint)
                .frame(width: 9, height: 9)
                .shadow(color: model.tint.opacity(0.85), radius: 3)
        } else if let symbol = model.symbol {
            Image(systemName: symbol)
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(model.tint)
        }
    }
}

private struct HUDNoteContent: View {
    @ObservedObject var model: HUDModel

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "info.circle")
                .font(.system(size: 10))
                .foregroundStyle(.tertiary)
            Text(model.text)
                .font(.system(size: 11))
                .foregroundStyle(.secondary)
                .fixedSize()
        }
        .padding(.horizontal, 11)
        .padding(.vertical, 6)
        .background(Capsule(style: .continuous).fill(.ultraThinMaterial))
        .overlay(Capsule(style: .continuous).strokeBorder(.primary.opacity(0.06), lineWidth: 1))
        .shadow(color: .black.opacity(0.12), radius: 8, y: 3)
        .padding(22)
        .fixedSize()
    }
}
