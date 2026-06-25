#!/bin/bash
# Builds TypeFix.app and code-signs it so macOS remembers the Accessibility
# permission across launches.
#
# We build with `xcodebuild` (not `swift build`) because the embedded on-device
# model (MLX) needs its Metal shader library (`default.metallib`). Xcode compiles
# the Metal kernels; a plain `swift build` does NOT, which makes the app crash the
# moment it tries to run a local model. We then copy that metallib next to the
# binary as `mlx.metallib`, where MLX looks for it first.
set -euo pipefail

cd "$(dirname "$0")"

CONFIG="${1:-release}"
case "$CONFIG" in
    release|Release) XCCONFIG=Release ;;
    debug|Debug) XCCONFIG=Debug ;;
    *) echo "Unknown configuration '$CONFIG' (use 'release' or 'debug')"; exit 1 ;;
esac

APP="TypeFix.app"
BIN_NAME="TypeFix"
DERIVED=".xcode"

SIGN_IDENTITY="TypeFix Self-Signed"
SIGN_KEYCHAIN="typefix-signing.keychain-db"
SIGN_KEYCHAIN_PASS="typefix"

echo "==> Building ($XCCONFIG) with xcodebuild (compiles MLX Metal shaders)…"
xcodebuild \
    -scheme "$BIN_NAME" \
    -configuration "$XCCONFIG" \
    -derivedDataPath "$DERIVED" \
    -destination 'platform=macOS' \
    build

PRODUCTS="$DERIVED/Build/Products/$XCCONFIG"
BIN_PATH="$PRODUCTS/$BIN_NAME"
METALLIB="$PRODUCTS/mlx-swift_Cmlx.bundle/Contents/Resources/default.metallib"

[ -f "$BIN_PATH" ] || { echo "error: binary not found at $BIN_PATH"; exit 1; }
[ -f "$METALLIB" ] || { echo "error: metallib not found at $METALLIB"; exit 1; }

echo "==> Assembling $APP…"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
mkdir -p "$APP/Contents/Resources"

cp "$BIN_PATH" "$APP/Contents/MacOS/$BIN_NAME"
# MLX loads its Metal shader library colocated with the binary as `mlx.metallib`.
cp "$METALLIB" "$APP/Contents/MacOS/mlx.metallib"
cp "Resources/Info.plist" "$APP/Contents/Info.plist"
cp "Resources/AppIcon.icns" "$APP/Contents/Resources/AppIcon.icns"

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
