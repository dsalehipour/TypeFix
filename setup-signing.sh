#!/bin/bash
# Creates a STABLE self-signed code-signing identity for TypeFix.
#
# Why: ad-hoc signing changes the code hash on every build, so macOS treats each
# rebuild as a new app and the Accessibility permission goes stale. A stable
# identity keeps the same designated requirement across rebuilds, so you grant
# Accessibility once and it sticks.
#
# The identity lives in a dedicated keychain with a known password, so this needs
# no interaction and never touches your login keychain's secrets.
set -euo pipefail
cd "$(dirname "$0")"

IDENTITY_NAME="TypeFix Self-Signed"
KEYCHAIN="typefix-signing.keychain-db"
KEYCHAIN_PASS="typefix"
KEYCHAIN_PATH="$HOME/Library/Keychains/$KEYCHAIN"

if security find-identity -v -p codesigning 2>/dev/null | grep -q "$IDENTITY_NAME"; then
    echo "==> Signing identity '$IDENTITY_NAME' already exists. Nothing to do."
    exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "==> Generating self-signed code-signing certificate…"
cat > "$TMP/cert.cnf" <<'EOF'
[req]
distinguished_name = dn
x509_extensions = v3
prompt = no
[dn]
CN = TypeFix Self-Signed
[v3]
basicConstraints = critical,CA:false
keyUsage = critical,digitalSignature
extendedKeyUsage = critical,codeSigning
EOF

/usr/bin/openssl req -x509 -newkey rsa:2048 -nodes \
    -keyout "$TMP/key.pem" -out "$TMP/cert.pem" \
    -days 3650 -config "$TMP/cert.cnf" >/dev/null 2>&1

echo "==> Creating dedicated signing keychain…"
if [ ! -f "$KEYCHAIN_PATH" ]; then
    security create-keychain -p "$KEYCHAIN_PASS" "$KEYCHAIN"
fi
security set-keychain-settings "$KEYCHAIN"            # no auto-lock
security unlock-keychain -p "$KEYCHAIN_PASS" "$KEYCHAIN"

# Add to the user search list (preserving what's already there).
EXISTING=$(security list-keychains -d user | sed -e 's/[[:space:]]*"//' -e 's/"$//')
# shellcheck disable=SC2086
security list-keychains -d user -s $EXISTING "$KEYCHAIN_PATH"

echo "==> Importing key + certificate as an identity…"
security import "$TMP/key.pem"  -k "$KEYCHAIN_PATH" -A -T /usr/bin/codesign >/dev/null 2>&1
security import "$TMP/cert.pem" -k "$KEYCHAIN_PATH" -A -T /usr/bin/codesign >/dev/null 2>&1

# Let codesign use the private key without GUI prompts.
security set-key-partition-list -S apple-tool:,apple: -s -k "$KEYCHAIN_PASS" "$KEYCHAIN_PATH" >/dev/null 2>&1 || true

# Note: the cert is self-signed and untrusted, so it won't appear under
# `find-identity -v` (valid only). codesign can still use it, and TCC matches on
# the certificate hash regardless of trust.
echo "==> Done."
if security find-identity -p codesigning | grep -q "$IDENTITY_NAME"; then
    echo "    Identity '$IDENTITY_NAME' is ready. Run ./build.sh to use it."
else
    echo "ERROR: identity not found after creation." >&2
    exit 1
fi
