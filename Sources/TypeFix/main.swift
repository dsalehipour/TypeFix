import AppKit

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
// Run as a menu-bar agent: no Dock icon, no main window.
app.setActivationPolicy(.accessory)
app.run()
