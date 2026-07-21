#!/usr/bin/env bash
# Generates a local release keystore for signing xSSH release APKs.
# Run ONCE, then keep the .jks file OFFLINE. NEVER commit it.
set -euo pipefail
KEYSTORE=${1:-xssh-release.jks}
ALIAS=${2:-xssh}
VALIDITY=${3:-10000}
if [ -f "$KEYSTORE" ]; then
    echo "Keystore $KEYSTORE already exists — refusing to overwrite." >&2
    exit 1
fi
keytool -genkeypair -keystore "$KEYSTORE" -alias "$ALIAS" \
    -keyalg EC -groupname secp256r1 -validity "$VALIDITY" -storetype PKCS12
echo
echo "Created: $KEYSTORE"
keytool -list -v -keystore "$KEYSTORE" -alias "$ALIAS" | grep -E 'SHA(1|256):' | head -2
echo
echo "For CI: base64 -w 0 $KEYSTORE  →  paste into GitHub secret KEYSTORE_B64"
