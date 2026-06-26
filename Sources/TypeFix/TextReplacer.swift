import AppKit

/// Stamp placed on synthetic events so our own event tap can ignore the
/// keystrokes TypeFix injects (backspaces + paste) - important in Auto mode,
/// where the tap captures continuously.
let typeFixSyntheticMarker: Int64 = 0x7459_7046 // "tYpF"

/// Replaces the just-typed gibberish in the frontmost app with corrected text.
///
/// Strategy: synthesize `deleteCount` backspaces to erase what the user typed,
/// then paste the corrected text via the pasteboard + Cmd+V.
///
/// Why paste instead of typing each character: when text is injected as
/// individual keystrokes, the target app/system applies its own text features
/// to it - autocapitalization, "double-space → period", autocorrect - which
/// corrupts the result (stray periods, dropped spaces). A paste is inserted
/// literally and bypasses all of that. The previous clipboard contents are
/// restored afterwards (unless the user copied something in the meantime).
final class TextReplacer {
    static let shared = TextReplacer()

    private let queue = DispatchQueue(label: "com.typefix.replacer")

    private enum Key {
        static let delete: CGKeyCode = 51
        static let v: CGKeyCode = 9
    }

    func replace(deleteCount: Int, with text: String) {
        queue.async {
            let pasteboard = NSPasteboard.general
            let previousString = pasteboard.string(forType: .string)
            let source = CGEventSource(stateID: .combinedSessionState)

            // 1. Erase the gibberish.
            for _ in 0..<max(0, deleteCount) {
                self.postKeystroke(Key.delete, source: source)
                usleep(2_500)
            }
            usleep(40_000) // let the deletes finish before inserting

            // 2. Put the corrected text on the pasteboard.
            pasteboard.clearContents()
            pasteboard.setString(text, forType: .string)
            let stampedChangeCount = pasteboard.changeCount
            usleep(40_000) // let the pasteboard server register it

            // 3. Paste.
            self.postPaste(source: source)

            // 4. Wait for the paste to be consumed, then restore the user's
            //    clipboard - but only if they didn't copy something meanwhile.
            usleep(450_000)
            if pasteboard.changeCount == stampedChangeCount {
                pasteboard.clearContents()
                if let previousString {
                    pasteboard.setString(previousString, forType: .string)
                }
            }
        }
    }

    private func postPaste(source: CGEventSource?) {
        if let down = CGEvent(keyboardEventSource: source, virtualKey: Key.v, keyDown: true) {
            down.flags = .maskCommand
            down.setIntegerValueField(.eventSourceUserData, value: typeFixSyntheticMarker)
            down.post(tap: .cgAnnotatedSessionEventTap)
        }
        if let up = CGEvent(keyboardEventSource: source, virtualKey: Key.v, keyDown: false) {
            up.flags = .maskCommand
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
