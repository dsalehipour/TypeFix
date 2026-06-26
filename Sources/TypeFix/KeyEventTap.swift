import AppKit

/// Installs a session-level CGEventTap to observe keystrokes and mouse clicks.
///
/// - Detects "both shift keys held at once" to fire `onBothShifts` exactly once
///   per press (re-armed when either shift is released).
/// - While `shouldCapture()` returns true, classifies each event and forwards it
///   to the engine: typed characters, backspace, enter, tab, escape-to-cancel,
///   and "navigation" events (arrows / home / end / page keys / mouse clicks /
///   modifier shortcuts) that move the insertion point.
///
/// The tap is an *active* (default) tap that passes every event through
/// unmodified, so it only requires Accessibility permission. Events that
/// TypeFix itself synthesizes are ignored via a marker.
final class KeyEventTap {
    var shouldCapture: () -> Bool = { false }
    var isArmed: () -> Bool = { false }
    var currentHotkey: () -> Hotkey = { .bothShifts }
    var onHotkey: (() -> Void)?
    var onCopyLast: (() -> Void)?
    var onCharacters: ((String) -> Void)?
    var onBackspace: (() -> Void)?
    var onDeleteWord: (() -> Void)?        // Option+Delete
    var onDeleteToLineStart: (() -> Void)? // Cmd+Delete
    var onEnter: (() -> Void)?
    var onTab: (() -> Void)?
    var onCancel: (() -> Void)?
    var onNavigation: (() -> Void)?

    private var eventTap: CFMachPort?
    private var runLoopSource: CFRunLoopSource?
    private var isRunning = false

    // Device-dependent modifier flag bits (from IOKit's NX_DEVICE*KEYMASK).
    private static let leftShiftBit: UInt64 = 0x0000_0002
    private static let rightShiftBit: UInt64 = 0x0000_0004

    private var bothShiftsLatched = false

    private enum Key {
        static let returnKey = 36
        static let tab = 48
        static let delete = 51
        static let escape = 53
        static let keypadEnter = 76
        static let forwardDelete = 117
        // Cursor-moving keys.
        static let navigation: Set<Int> = [
            123, 124, 125, 126, // arrows
            115, 119,           // home, end
            116, 121,           // page up, page down
            117,                // forward delete
        ]
    }

    @discardableResult
    func start() -> Bool {
        if isRunning { return true }

        let mask = (1 << CGEventType.keyDown.rawValue)
            | (1 << CGEventType.flagsChanged.rawValue)
            | (1 << CGEventType.leftMouseDown.rawValue)
            | (1 << CGEventType.rightMouseDown.rawValue)
            | (1 << CGEventType.otherMouseDown.rawValue)
        let selfPtr = Unmanaged.passUnretained(self).toOpaque()

        guard let tap = CGEvent.tapCreate(
            tap: .cgSessionEventTap,
            place: .headInsertEventTap,
            options: .defaultTap,
            eventsOfInterest: CGEventMask(mask),
            callback: { _, type, event, refcon in
                guard let refcon else { return Unmanaged.passUnretained(event) }
                let manager = Unmanaged<KeyEventTap>.fromOpaque(refcon).takeUnretainedValue()
                return manager.handle(type: type, event: event)
            },
            userInfo: selfPtr
        ) else {
            return false
        }

        let source = CFMachPortCreateRunLoopSource(kCFAllocatorDefault, tap, 0)
        CFRunLoopAddSource(CFRunLoopGetMain(), source, .commonModes)
        CGEvent.tapEnable(tap: tap, enable: true)

        self.eventTap = tap
        self.runLoopSource = source
        self.isRunning = true
        return true
    }

    private func handle(type: CGEventType, event: CGEvent) -> Unmanaged<CGEvent>? {
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
            if let eventTap { CGEvent.tapEnable(tap: eventTap, enable: true) }
            return Unmanaged.passUnretained(event)
        }

        // Never react to keystrokes TypeFix itself injected.
        if event.getIntegerValueField(.eventSourceUserData) == typeFixSyntheticMarker {
            return Unmanaged.passUnretained(event)
        }

        switch type {
        case .flagsChanged:
            handleFlagsChanged(event)
        case .keyDown:
            // ⌘⇧C copies the last original text; swallow it.
            if isCopyLastShortcut(event) {
                onCopyLast?()
                return nil
            }
            // A matching custom shortcut fires the hotkey and is swallowed so it
            // doesn't also reach the focused app.
            if isHotkeyComboMatch(event) {
                onHotkey?()
                return nil
            }
            if shouldCapture() { classifyKeyDown(event) }
        case .leftMouseDown, .rightMouseDown, .otherMouseDown:
            if shouldCapture() { onNavigation?() }
        default:
            break
        }

        return Unmanaged.passUnretained(event)
    }

    private func isCopyLastShortcut(_ event: CGEvent) -> Bool {
        guard isArmed() else { return false }
        let keyCode = Int(event.getIntegerValueField(.keyboardEventKeycode))
        guard keyCode == 8 else { return false } // 'c'
        let relevant: CGEventFlags = [.maskCommand, .maskAlternate, .maskControl, .maskShift]
        return event.flags.intersection(relevant) == [.maskCommand, .maskShift, .maskAlternate]
    }

    private func isHotkeyComboMatch(_ event: CGEvent) -> Bool {
        let hotkey = currentHotkey()
        guard !hotkey.useBothShifts, isArmed() else { return false }
        let keyCode = Int(event.getIntegerValueField(.keyboardEventKeycode))
        return hotkey.matches(keyCode: keyCode, flags: event.flags)
    }

    private func handleFlagsChanged(_ event: CGEvent) {
        // Both-Shift detection only applies when that is the configured trigger.
        guard currentHotkey().useBothShifts else {
            bothShiftsLatched = false
            return
        }

        let raw = event.flags.rawValue
        let leftDown = (raw & Self.leftShiftBit) != 0
        let rightDown = (raw & Self.rightShiftBit) != 0

        if leftDown && rightDown {
            if !bothShiftsLatched {
                bothShiftsLatched = true
                onHotkey?()
            }
        } else {
            bothShiftsLatched = false
        }
    }

    private func classifyKeyDown(_ event: CGEvent) {
        let flags = event.flags
        let keyCode = Int(event.getIntegerValueField(.keyboardEventKeycode))

        // Delete and its modifier variants. Handle these BEFORE the generic
        // Command/Control shortcut check so multi-character deletes stay in sync
        // with the capture buffer: Cmd+Delete removes to the line start and
        // Option+Delete removes the previous word (a very common way to backspace).
        if keyCode == Key.delete {
            if flags.contains(.maskCommand) {
                onDeleteToLineStart?()
            } else if flags.contains(.maskAlternate) {
                onDeleteWord?()
            } else if flags.contains(.maskControl) {
                onNavigation?() // ambiguous; abandon the chunk to stay safe
            } else {
                onBackspace?()
            }
            return
        }

        // Other Command/Control combos move the cursor or change selection; treat
        // them as boundaries rather than typed text.
        if flags.contains(.maskCommand) || flags.contains(.maskControl) {
            onNavigation?()
            return
        }

        if Key.navigation.contains(keyCode) {
            onNavigation?()
            return
        }

        switch keyCode {
        case Key.escape:
            onCancel?()
        case Key.returnKey, Key.keypadEnter:
            onEnter?()
        case Key.tab:
            onTab?()
        default:
            if let text = typedString(from: event) {
                onCharacters?(text)
            }
        }
    }

    /// Extracts printable characters for a key event, dropping control codes
    /// and function-key private-use scalars.
    private func typedString(from event: CGEvent) -> String? {
        var length = 0
        var buffer = [UniChar](repeating: 0, count: 8)
        event.keyboardGetUnicodeString(maxStringLength: 8, actualStringLength: &length, unicodeString: &buffer)
        guard length > 0 else { return nil }

        let raw = String(utf16CodeUnits: buffer, count: length)
        let filtered = String(raw.unicodeScalars.filter { scalar in
            let value = scalar.value
            if value < 0x20 { return false }
            if (0xF700...0xF8FF).contains(value) { return false }
            return true
        })
        return filtered.isEmpty ? nil : filtered
    }
}
