import SwiftUI
import AppKit

/// A click-to-record control that captures a modifier + key shortcut.
struct ShortcutRecorder: NSViewRepresentable {
    @Binding var hotkey: Hotkey

    func makeCoordinator() -> Coordinator { Coordinator(hotkey: $hotkey) }

    func makeNSView(context: Context) -> RecorderView {
        let view = RecorderView()
        view.hotkey = hotkey
        view.onChange = { [coordinator = context.coordinator] newHotkey in
            coordinator.hotkey.wrappedValue = newHotkey
        }
        return view
    }

    func updateNSView(_ nsView: RecorderView, context: Context) {
        if nsView.hotkey != hotkey {
            nsView.hotkey = hotkey
        }
    }

    final class Coordinator {
        let hotkey: Binding<Hotkey>
        init(hotkey: Binding<Hotkey>) { self.hotkey = hotkey }
    }
}

final class RecorderView: NSView {
    var onChange: ((Hotkey) -> Void)?

    var hotkey: Hotkey = .bothShifts {
        didSet { updateAppearance() }
    }

    private let label = NSTextField(labelWithString: "")
    private var monitor: Any?
    private var isRecording = false {
        didSet { updateAppearance() }
    }

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        setup()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setup()
    }

    deinit { stopRecording() }

    private func setup() {
        wantsLayer = true
        layer?.cornerRadius = 6
        layer?.borderWidth = 1

        label.alignment = .center
        label.font = .systemFont(ofSize: 13, weight: .medium)
        label.translatesAutoresizingMaskIntoConstraints = false
        addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: centerXAnchor),
            label.centerYAnchor.constraint(equalTo: centerYAnchor),
            label.leadingAnchor.constraint(greaterThanOrEqualTo: leadingAnchor, constant: 8),
            label.trailingAnchor.constraint(lessThanOrEqualTo: trailingAnchor, constant: -8),
        ])
        updateAppearance()
    }

    override var intrinsicContentSize: NSSize {
        NSSize(width: NSView.noIntrinsicMetric, height: 30)
    }

    override func mouseDown(with event: NSEvent) {
        if isRecording {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private func startRecording() {
        guard monitor == nil else { return }
        isRecording = true
        monitor = NSEvent.addLocalMonitorForEvents(matching: [.keyDown]) { [weak self] event in
            guard let self, self.isRecording else { return event }

            if event.keyCode == 53 { // Escape cancels
                self.stopRecording()
                return nil
            }

            let modifiers = HotkeyModifiers(nsFlags: event.modifierFlags)
            guard modifiers.hasNonShiftModifier else {
                NSSound.beep() // need ⌘/⌥/⌃ so it can't collide with typing
                return nil
            }

            let newHotkey = Hotkey(
                useBothShifts: false,
                keyCode: Int(event.keyCode),
                modifiers: modifiers,
                keyLabel: RecorderView.keyLabel(for: event)
            )
            self.hotkey = newHotkey
            self.onChange?(newHotkey)
            self.stopRecording()
            return nil
        }
    }

    private func stopRecording() {
        if let monitor {
            NSEvent.removeMonitor(monitor)
        }
        monitor = nil
        isRecording = false
    }

    private func updateAppearance() {
        if isRecording {
            label.stringValue = "Press a shortcut…  (Esc to cancel)"
            label.textColor = .secondaryLabelColor
            layer?.borderColor = NSColor.controlAccentColor.cgColor
            layer?.backgroundColor = NSColor.controlAccentColor.withAlphaComponent(0.08).cgColor
        } else {
            label.stringValue = hotkey.displayString
            label.textColor = .labelColor
            layer?.borderColor = NSColor.separatorColor.cgColor
            layer?.backgroundColor = NSColor.controlBackgroundColor.cgColor
        }
    }

    private static let specialKeys: [Int: String] = [
        49: "Space", 36: "Return", 76: "Enter", 48: "Tab", 51: "Delete", 117: "⌦",
        123: "←", 124: "→", 125: "↓", 126: "↑",
        115: "Home", 119: "End", 116: "Page Up", 121: "Page Down",
        122: "F1", 120: "F2", 99: "F3", 118: "F4", 96: "F5", 97: "F6",
        98: "F7", 100: "F8", 101: "F9", 109: "F10", 103: "F11", 111: "F12",
    ]

    static func keyLabel(for event: NSEvent) -> String {
        if let special = specialKeys[Int(event.keyCode)] {
            return special
        }
        let characters = (event.charactersIgnoringModifiers ?? "").uppercased()
        if let first = characters.first, !first.isWhitespace, first.isASCII {
            return String(first)
        }
        return "Key \(event.keyCode)"
    }
}
