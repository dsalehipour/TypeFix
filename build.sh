#!/bin/bash
# Builds TypeFix.app from the SwiftPM executable and ad-hoc code-signs it so
# macOS remembers the Accessibility permission across launches.
set -euo pipefail

cd "$(dirname "$0")"

CONFIG="${1:-release}"
APP="TypeFix.app"
BIN_NAME="TypeFix"

# Prefer a stable self-signed identity (so the Accessibility grant survives
# rebuilds). Fall back to ad-hoc if it hasn't been created yet.
SIGN_IDENTITY="TypeFix Self-Signed"
SIGN_KEYCHAIN="typefix-signing.keychain-db"
SIGN_KEYCHAIN_PASS="typefix"

echo "==> Building ($CONFIG)…"
swift build -c "$CONFIG"

BIN_PATH="$(swift build -c "$CONFIG" --show-bin-path)/$BIN_NAME"

echo "==> Assembling $APP…"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
mkdir -p "$APP/Contents/Resources"

cp "$BIN_PATH" "$APP/Contents/MacOS/$BIN_NAME"
cp "Resources/Info.plist" "$APP/Contents/Info.plist"

if security find-identity -p codesigning 2>/dev/null | grep -q "$SIGN_IDENTITY"; then
    echo "==> Code signing with stable identity '$SIGN_IDENTITY'…"
    security unlock-keychain -p "$SIGN_KEYCHAIN_PASS" "$SIGN_KEYCHAIN" 2>/dev/null || true
    codesign --force --deep --sign "$SIGN_IDENTITY" --identifier com.typefix.app "$APP"
else
    echo "==> No stable identity found; ad-hoc signing (permission will reset on rebuild)."
    echo "    Run ./setup-signing.sh once to make the Accessibility grant persist."
    codesign --force --deep --sign - "$APP"
fi

echo "==> Done: $(pwd)/$APP"
echo "    Launch with:  open $APP"
echo "    First launch will prompt for Accessibility permission."
