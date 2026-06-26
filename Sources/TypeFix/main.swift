import AppKit

// Headless evaluation mode: `TypeFix --eval <cases.json>`. Runs the real
// correction path against the currently selected provider/model and exits.
if let evalIndex = CommandLine.arguments.firstIndex(of: "--eval") {
    let casesPath = evalIndex + 1 < CommandLine.arguments.count
        ? CommandLine.arguments[evalIndex + 1]
        : "eval/cases.json"
    let done = DispatchSemaphore(value: 0)
    Task {
        await Eval.run(casesPath: casesPath)
        done.signal()
    }
    done.wait()
    exit(0)
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
// Run as a menu-bar agent: no Dock icon, no main window.
app.setActivationPolicy(.accessory)
app.run()
