import ApplicationServices
import AppKit

/// Reads and replaces the user's current text selection in the frontmost app via
/// the Accessibility API. This lets TypeFix fix already-on-screen text (not just
/// what was just typed) and avoids keystroke-replay desync.
///
/// Works in apps that expose AX text attributes (most native AppKit apps; some
/// web/Electron apps only partially). Callers should fall back to paste when
/// `replaceSelection` returns false.
enum AccessibilityText {
    struct Selection {
        let element: AXUIElement
        let text: String
    }

    /// The non-empty selected text in the focused UI element, if any.
    static func focusedSelection() -> Selection? {
        let systemWide = AXUIElementCreateSystemWide()

        var focusedValue: CFTypeRef?
        guard AXUIElementCopyAttributeValue(
            systemWide, kAXFocusedUIElementAttribute as CFString, &focusedValue
        ) == .success, let focused = focusedValue else {
            return nil
        }
        // CFTypeRef returned for a UI-element attribute is an AXUIElement.
        let element = focused as! AXUIElement

        var selectedValue: CFTypeRef?
        guard AXUIElementCopyAttributeValue(
            element, kAXSelectedTextAttribute as CFString, &selectedValue
        ) == .success, let selected = selectedValue as? String else {
            return nil
        }

        let trimmed = selected.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        return Selection(element: element, text: selected)
    }

    /// Replaces the current selection in `element` with `text`. Returns false if
    /// the app doesn't allow setting selected text (caller should fall back).
    @discardableResult
    static func replaceSelection(in element: AXUIElement, with text: String) -> Bool {
        let result = AXUIElementSetAttributeValue(
            element, kAXSelectedTextAttribute as CFString, text as CFString
        )
        return result == .success
    }
}
