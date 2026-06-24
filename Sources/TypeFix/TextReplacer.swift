import AppKit

/// Stamp placed on synthetic events so our own event tap can ignore the
/// keystrokes TypeFix injects — important in Auto mode, where the tap captures
/// continuously.
let typeFixSyntheticMarker: Int64 = 0x7459_7046 // "tYpF"

/// Replaces the just-typed gibberish in the frontmost app with corrected text.
///
/// Strategy: synthesize `deleteCount` backspaces to erase what the user typed,
/// then type the corrected text directly as Unicode key events. We deliberately
/// avoid the clipboard + Cmd+V approach — that races (the paste can fire before
/// the new clipboard value registers, pasting stale/short text) and clobbers the
/// user's clipboard.
final class TextReplacer {
    static let shared = TextReplacer()

    private let queue = DispatchQueue(label: "com.typefix.replacer")

    private enum Key {
        static let delete: CGKeyCode = 51
        static let returnKey: CGKeyCode = 36
        static let tab: CGKeyCode = 48
    }

    func replace(deleteCount: Int, with text: String) {
        queue.async {
            let source = CGEventSource(stateID: .combinedSessionState)

            // 1. Erase the gibberish.
            for _ in 0..<max(0, deleteCount) {
                self.postKeystroke(Key.delete, source: source)
                usleep(1_800)
            }
            usleep(15_000) // let the deletes settle before inserting

            // 2. Type the corrected text directly.
            self.typeText(text, source: source)
        }
    }

    private func typeText(_ text: String, source: CGEventSource?) {
        for character in text {
            switch character {
            case "\n", "\r":
                postKeystroke(Key.returnKey, source: source)
            case "\t":
                postKeystroke(Key.tab, source: source)
            default:
                postUnicode(String(character), source: source)
            }
            usleep(1_100)
        }
    }

    /// Posts a real, printable character via its Unicode value (keycode 0).
    private func postUnicode(_ string: String, source: CGEventSource?) {
        var utf16 = Array(string.utf16)
        if let down = CGEvent(keyboardEventSource: source, virtualKey: 0, keyDown: true) {
            down.keyboardSetUnicodeString(stringLength: utf16.count, unicodeString: &utf16)
            down.setIntegerValueField(.eventSourceUserData, value: typeFixSyntheticMarker)
            down.post(tap: .cgAnnotatedSessionEventTap)
        }
        if let up = CGEvent(keyboardEventSource: source, virtualKey: 0, keyDown: false) {
            up.keyboardSetUnicodeString(stringLength: utf16.count, unicodeString: &utf16)
            up.setIntegerValueField(.eventSourceUserData, value: typeFixSyntheticMarker)
            up.post(tap: .cgAnnotatedSessionEventTap)
        }
    }

    private func postKeystroke(_ key: CGKeyCode, source: CGEventSource?) {
        if let down = CGEvent(keyboardEventSource: source, virtualKey: key, keyDown: true) {
            down.setIntegerValueField(.eventSourceUserData, value: typeFixSyntheticMarker)
            down.post(tap: .cgAnnotatedSessionEventTap)
        }
        if let up = CGEvent(keyboardEventSource: source, virtualKey: key, keyDown: false) {
            up.setIntegerValueField(.eventSourceUserData, value: typeFixSyntheticMarker)
            up.post(tap: .cgAnnotatedSessionEventTap)
        }
    }
}
