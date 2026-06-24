# TypeFix

A macOS menu‑bar app that fixes your fast, sloppy typing with an LLM.

Type as fast as you want — even total gibberish — then let TypeFix figure out
what you *meant* and rewrite it in place, right where your cursor is, in any app.

```
you type:   whjkat m,ios th best thign swe dcan do to incmprve our converospn rates.
you get:    What is the best thing we can do to improve our conversion rates?
```

It runs quietly in the menu bar (no Dock icon), works in any text field, and
never sends your keystrokes anywhere except the AI provider *you* configure.

---

## Features

- **Two trigger modes**
  - **Manual** — tap your shortcut to start, type, tap again to fix.
  - **Auto** — it fixes automatically a moment after you stop typing.
- **Customizable shortcut** — defaults to tapping **both Shift keys** together; set any ⌘/⌥/⌃ combo instead.
- **On‑screen traffic light** (Auto mode) so you always know what's happening:
  🟢 typing → 🟡 counting down (shows seconds left) → 🔴 thinking → ✓ fixed.
- **Minimum‑length threshold** — don't bother fixing tiny fragments; a small, quiet note tells you why.
- **History** — see every original → corrected pair and copy your original back if you ever want it. Nothing is lost.
- **Bring your own key** — Anthropic or OpenAI, stored in the macOS Keychain.
- **Direct keystroke injection** — replaces text by typing it, so it never touches your clipboard.

## Requirements

- macOS 14 (Sonoma) or later
- Swift toolchain / Xcode command line tools (to build)
- An API key from **Anthropic** or **OpenAI**

## Install

```bash
# 1. Create a stable local signing identity (once) so the Accessibility
#    permission survives rebuilds.
./setup-signing.sh

# 2. Build the app bundle.
./build.sh release

# 3. Launch it.
open TypeFix.app
```

A keyboard icon appears in the menu bar.

> Because the app is signed with a **self‑signed** identity (not notarized), the
> first launch from Finder may need a right‑click → **Open**, or approval under
> System Settings → Privacy & Security.

## Grant permission

TypeFix needs **Accessibility** permission to read keystrokes and to type the
corrected text back.

1. Menu‑bar icon → **Open Accessibility Settings…**
   (or System Settings → Privacy & Security → **Accessibility**)
2. Enable **TypeFix**.

The icon turns from a warning triangle into a keyboard once granted — no relaunch
needed.

## Add your API key

1. Menu‑bar icon → **Settings…**
2. Choose **Anthropic** or **OpenAI** and paste your API key (stored in the Keychain).
3. Optionally change the model, then press **Run Test** to confirm it works.

Default models (fast + cheap):

| Provider  | Default model        | Higher quality       |
|-----------|----------------------|----------------------|
| Anthropic | `claude-haiku-4-5`   | `claude-sonnet-4-6`  |
| OpenAI    | `gpt-4o-mini`        | —                    |

Model ids change over time. If the API returns a "model not found" error, update
the **Model** field in Settings (or click **Reset to default**).

## Using it

Pick a mode in **Settings → Correction Behavior** (or toggle **Auto‑fix on
pause** straight from the menu).

### Manual (default)

1. Tap your shortcut (default: **both Shift keys** together) — the icon turns into a red record dot.
2. Type whatever; it appears normally as you go.
3. Tap the shortcut again to replace it with the corrected version.
4. Press **Esc** while capturing to cancel.

### Auto

1. Just type normally.
2. Stop for a moment (default **1.5s**, adjustable). TypeFix fixes the chunk in place.
3. Watch the on‑screen pill: 🟢 *Typing* → 🟡 *Fixing in 0.8s* → 🔴 *Thinking* → ✓ *Fixed*.

- Your shortcut does an instant **fix now** in Auto mode.
- A pending fix is **abandoned** if you click elsewhere, use arrows, or press Enter/Tab —
  because the cursor is no longer at the end of what you typed and an in‑place replace wouldn't be safe.
- Fragments shorter than the **minimum length** (default 10 chars) are left alone; a small grey note shows the count.

## Settings overview

| Setting | What it does |
|---------|--------------|
| Mode | Manual vs Auto (fix on pause) |
| Pause before fixing | Auto‑mode idle delay (0.6–4.0s) |
| Minimum characters | Skip auto‑fixing short fragments |
| Trigger shortcut | Both‑Shift (default) or a custom ⌘/⌥/⌃ combo |
| Provider / API key / Model | Your AI backend |
| Enable TypeFix | Master on/off |
| Launch at login | Start automatically |

## Privacy & security

- The text you capture is sent **only** to the AI provider you choose. Don't capture secrets you don't want leaving your machine.
- Your API key is stored in the login **Keychain** (service `com.typefix.app`), never in plain text.
- History is stored locally on your machine (capped to the last 300 entries).

## How it works

| File | Responsibility |
|------|----------------|
| `KeyEventTap.swift` | Active `CGEventTap`: shortcut detection, continuous capture, boundary detection |
| `CorrectionEngine.swift` | State machine for Manual + Auto (pause) modes |
| `TextCorrector.swift` | Calls the Anthropic / OpenAI APIs |
| `TextReplacer.swift` | Backspaces + types the fix as direct Unicode keystrokes (no clipboard) |
| `HUDController.swift` | The floating traffic‑light pill + low‑key notes |
| `Hotkey.swift` / `ShortcutRecorder.swift` | Custom shortcut model + recorder |
| `HistoryStore.swift` / `HistoryView.swift` | Correction log + History window |
| `AppDelegate.swift` | Menu‑bar item, windows, permission flow |
| `SettingsView.swift` / `AppSettings.swift` / `Keychain.swift` | Settings UI, preferences, secure key storage |

## Development

```bash
swift build            # debug build
swift run TypeFix      # run without bundling (Accessibility may not persist)
./build.sh release     # produce the signed .app
```

### Why the self‑signed identity?

macOS ties the Accessibility permission to the app's **code signature**. Ad‑hoc
signing produces a different signature on every build, so each rebuild looks like
a brand‑new app and the grant goes stale. `setup-signing.sh` creates a stable,
self‑signed identity in a dedicated keychain so you approve permissions once.

If you ever need a clean slate:

```bash
tccutil reset Accessibility com.typefix.app
```

## License

MIT — see [LICENSE](LICENSE).
