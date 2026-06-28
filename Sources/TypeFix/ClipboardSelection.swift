import AppKit

/// Grabs the current selection by synthesizing Cmd+C, for apps that don't expose
/// selected text through the Accessibility API (e.g. Electron/web apps like
/// Cursor, VS Code, browsers). Used as a fallback after `AccessibilityText`.
enum ClipboardSelection {
    /// Copies the current selection and returns its text plus the prior clipboard
    /// contents to restore later. Returns nil when nothing is selected (the
    /// clipboard doesn't change after Cmd+C).
    ///
    /// Must be called off the main thread: it briefly polls the pasteboard.
    static func copyCurrentSelection() -> (text: String, restore: String?)? {
        let pasteboard = NSPasteboard.general
        let restore = pasteboard.string(forType: .string)
        let before = pasteboard.changeCount

        postCommandC()

        // Wait (briefly) for the app to put the selection on the pasteboard.
        var waited = 0
        while pasteboard.changeCount == before && waited < 250_000 {
            usleep(10_000)
            waited += 10_000
        }

        guard pasteboard.changeCount != before,
              let copied = pasteboard.string(forType: .string),
              !copied.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else {
            return nil
        }
        return (copied, restore)
    }

    private static func postCommandC() {
        let source = CGEventSource(stateID: .combinedSessionState)
        let cKey: CGKeyCode = 8
        for keyDown in [true, false] {
            if let event = CGEvent(keyboardEventSource: source, virtualKey: cKey, keyDown: keyDown) {
                event.flags = .maskCommand
                // Marked so our own event tap ignores this synthetic copy.
                event.setIntegerValueField(.eventSourceUserData, value: typeFixSyntheticMarker)
                event.post(tap: .cgAnnotatedSessionEventTap)
            }
        }
    }
}
