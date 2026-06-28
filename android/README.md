# TypeFix Keyboard (Android)

An Android port of [TypeFix](../) as a **soft keyboard (IME)** that fixes your
typos in place using an **on-device LLM** (or, optionally, a cloud model). It
reuses TypeFix's exact correction prompt, output cleaning, spell-check
guardrail, and settings — adapted to Android.

The local-LLM approach is inspired by **PrivateLM**
(`local-llm-cross-platform/cross-platform-llm-client`), which runs llama.cpp and
LiteRT-LM on Android. Here we use Google's **MediaPipe LLM Inference** API (the
productized sibling of that stack) behind a swappable `InferenceEngine`.

## Install on your phone (sideload)

**[⬇️ Download the latest APK](https://github.com/dsalehipour/typefix/releases/download/android-v0.1.0/TypeFix-Keyboard.apk)**
— or browse [all releases](https://github.com/dsalehipour/typefix/releases).

1. Open that link **on your Android phone** (sign in to GitHub if the repo
   prompts you).
2. Tap the downloaded `TypeFix-Keyboard.apk`. Android asks to **allow installs
   from this source** the first time — enable it, then **Install**.
3. Launch **TypeFix** → **Enable TypeFix keyboard** → **Switch to TypeFix**.
4. Pick a backend for corrections (download an on-device model, or paste a cloud
   API key) in Settings (the keyboard's **⋯ → Settings**, or the app icon).
   **GIF search works out of the box** (KLIPY key built in); you can paste your
   own KLIPY key to override it.

> Requires **Android 9 (API 28)+**. This is a debug-signed build for sideloading,
> so Android shows the usual "unknown source" prompt — that's expected.

## Why a keyboard?

On macOS, TypeFix fights the OS: a global event tap mirrors keystrokes into a
buffer and corrections are applied by simulating backspaces + paste. On Android,
an `InputMethodService` **owns** the text via `InputConnection`, so we read and
replace text directly — no event tap, no clipboard hacks.

## What it does

- **Manual mode:** tap the ✨ Fix key to correct the current line.
- **Auto mode:** correction fires after you pause typing (`autoDelay`), once the
  line passes `autoMinChars`.
- **Spell-check guardrail:** after the model rewrites text, Android's
  `SpellCheckerSession` flags (or auto-fixes) any residual typos.
- **Protected words:** never changed/flagged; appended to the system prompt.
- **Password fields are never sent to a model.**
- The status strip above the keys is the Android version of the macOS HUD.

## Project layout

```
app/src/main/java/com/typefix/keyboard/
├── ime/                     # the keyboard
│   ├── TypeFixImeService.kt # InputMethodService, correction triggering
│   └── KeyboardView.kt      # hand-rolled QWERTY + status strip + Fix key
├── correction/
│   ├── CorrectionText.kt    # system prompt + output cleaning (ported 1:1)
│   └── SpellCheckGuard.kt   # SpellCheckerSession guardrail (ported TypoChecker)
├── inference/
│   ├── InferenceEngine.kt   # the swappable seam
│   ├── LocalLlmEngine.kt    # MediaPipe LLM Inference (.task models)
│   ├── CloudEngines.kt      # OpenAI-compatible + Anthropic
│   ├── Corrector.kt         # orchestrator (picks backend, cleans, guards)
│   ├── InferenceController.kt # owns the loaded on-device model
│   ├── InferenceService.kt  # foreground service to keep the model warm
│   └── ModelManager.kt      # download / import / locate model files
├── settings/AppSettings.kt  # all settings (ported from macOS AppSettings)
├── model/                   # Provider, CorrectionMode enums
└── ui/                      # Compose settings screen
```

## Build & run

This machine has no JDK/Android SDK, so build in **Android Studio** (it bundles
its own JDK + SDK + NDK):

1. Open the `android/` folder in Android Studio (Ladybug or newer).
2. Let it sync; accept any SDK downloads it prompts for.
3. Run the `app` configuration on a device/emulator (Android 9 / API 28+).
4. In the app: **Enable TypeFix keyboard** → **Switch to TypeFix**.
5. For on-device mode, download a model in Settings (or import a `.task` file).

If you have the Gradle CLI installed you can instead run
`gradle wrapper` once to generate `gradlew`, then `./gradlew assembleDebug`.

## Settings ↔ macOS TypeFix mapping

| macOS setting | Here |
|---|---|
| provider | `Provider` (LOCAL / OPENAI / ANTHROPIC / CUSTOM) |
| model / baseURL / apiKey | same |
| correctionMode | Manual (Fix key) / Auto (pause) |
| autoDelay | `autoDelayMs` slider (600–4000 ms) |
| autoMinChars | slider (1–100) |
| spellCheckAfterCorrection | toggle |
| autoFixResidualTypos | toggle |
| protectedWords | add/remove list |
| hotkey (⇧⇧) | the ✨ Fix key |

## Notes / known caveats

- **MediaPipe model files** are `.task` exports (Gemma/Qwen/Phi). The catalog
  URLs may require accepting a license or a token; importing a `.task` file you
  already have always works.
- The **MediaPipe `tasks-genai` API** evolves; if a builder method name differs
  in the version Android Studio resolves, adjust `LocalLlmEngine.kt` (the seam
  is small and isolated).
- To swap in **llama.cpp** or **LiteRT-LM** (as PrivateLM does), implement
  `InferenceEngine` and wire it in `Corrector`/`InferenceController`.
- The keyboard layout is intentionally minimal — the focus of this project is
  the correction pipeline, not a full keyboard.
```
