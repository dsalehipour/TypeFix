import AppKit

struct HotkeyModifiers: OptionSet, Codable, Equatable {
    let rawValue: Int
    init(rawValue: Int) { self.rawValue = rawValue }

    static let command = HotkeyModifiers(rawValue: 1 << 0)
    static let option = HotkeyModifiers(rawValue: 1 << 1)
    static let control = HotkeyModifiers(rawValue: 1 << 2)
    static let shift = HotkeyModifiers(rawValue: 1 << 3)

    init(nsFlags: NSEvent.ModifierFlags) {
        var mods: HotkeyModifiers = []
        if nsFlags.contains(.command) { mods.insert(.command) }
        if nsFlags.contains(.option) { mods.insert(.option) }
        if nsFlags.contains(.control) { mods.insert(.control) }
        if nsFlags.contains(.shift) { mods.insert(.shift) }
        self = mods
    }

    /// At least one non-shift modifier, required so a shortcut can't collide
    /// with ordinary typing (e.g. Shift+letter).
    var hasNonShiftModifier: Bool {
        contains(.command) || contains(.option) || contains(.control)
    }

    var cgFlags: CGEventFlags {
        var flags: CGEventFlags = []
        if contains(.control) { flags.insert(.maskControl) }
        if contains(.option) { flags.insert(.maskAlternate) }
        if contains(.shift) { flags.insert(.maskShift) }
        if contains(.command) { flags.insert(.maskCommand) }
        return flags
    }

    /// Symbols in the conventional macOS order: ⌃⌥⇧⌘.
    var symbols: String {
        var result = ""
        if contains(.control) { result += "⌃" }
        if contains(.option) { result += "⌥" }
        if contains(.shift) { result += "⇧" }
        if contains(.command) { result += "⌘" }
        return result
    }
}

/// The trigger that starts / submits a correction.
struct Hotkey: Codable, Equatable {
    /// When true, the trigger is "both Shift keys pressed together" (the default).
    var useBothShifts: Bool
    var keyCode: Int
    var modifiers: HotkeyModifiers
    /// Human-readable key name for display (e.g. "C", "Space", "F5").
    var keyLabel: String

    static let bothShifts = Hotkey(useBothShifts: true, keyCode: 0, modifiers: [], keyLabel: "")

    /// Compact form for menus and the HUD.
    var menuSymbol: String {
        useBothShifts ? "⇧⇧" : modifiers.symbols + keyLabel
    }

    /// Longer form for settings.
    var displayString: String {
        useBothShifts ? "Both Shift keys (⇧⇧)" : modifiers.symbols + keyLabel
    }

    /// Whether a key-down event matches this (combo) hotkey.
    func matches(keyCode eventKeyCode: Int, flags: CGEventFlags) -> Bool {
        guard !useBothShifts, eventKeyCode == keyCode else { return false }
        let relevant: CGEventFlags = [.maskCommand, .maskAlternate, .maskControl, .maskShift]
        return flags.intersection(relevant) == modifiers.cgFlags
    }
}
