#!/bin/bash
# Builds a release TypeFix.app and wraps it in a distributable disk image.
#
# NOTE: This produces a self-signed (NOT Apple-notarized) build. The recipient
# must right-click → Open once (see the Read Me inside the DMG). For a no-prompt
# experience you'd need an Apple Developer ID + notarization.
set -euo pipefail

cd "$(dirname "$0")"

APP="TypeFix.app"
DMG="TypeFix.dmg"
VOL="TypeFix"
STAGING=".dmg-staging"

echo "==> Building release app…"
./build.sh release

echo "==> Staging disk image contents…"
rm -rf "$STAGING" "$DMG"
mkdir -p "$STAGING"
cp -R "$APP" "$STAGING/$APP"
ln -s /Applications "$STAGING/Applications"

cat > "$STAGING/Read Me - First Launch.txt" <<'TXT'
TypeFix: first launch

1. Drag TypeFix onto the Applications folder shown in this window.

2. Open Applications, RIGHT-CLICK TypeFix, choose Open, then click Open.
   If macOS still blocks it, go to:
     System Settings → Privacy & Security → scroll down → "Open Anyway".
   This one-time step is only needed because the app isn't notarized by Apple.

3. A text cursor icon appears in the menu bar (TypeFix has no Dock icon).

4. Grant Accessibility when prompted (menu bar → Open Accessibility Settings…)
   so TypeFix can read and rewrite text in any app.

5. Menu bar → Settings… to pick your AI backend:
   • Cloud: paste an Anthropic or OpenAI key, or
   • On this Mac (private): Ollama, a custom local server, or the built-in
     MLX model (downloads once, then runs fully offline).

Requirements: macOS 14 or later, on an Apple Silicon Mac.
TXT

echo "==> Creating $DMG…"
hdiutil create -volname "$VOL" -srcfolder "$STAGING" -ov -format UDZO "$DMG" >/dev/null
rm -rf "$STAGING"

echo "==> Done: $(pwd)/$DMG"
du -h "$DMG"
