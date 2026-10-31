#!/usr/bin/env bash
# CI: install the release signing key from secrets (or a throw-away key when they are absent). Prints only the PUBLIC certificate fingerprint.
set -euo pipefail
K="$HOME/.jarvis-keys"; mkdir -p "$K"; chmod 700 "$K"
if [ -n "${JARVIS_KEYSTORE_B64:-}" ] && [ -n "${JARVIS_KEYSTORE_PASS:-}" ]; then
  printf '%s' "$JARVIS_KEYSTORE_B64" | base64 -d > "$K/jarvis-release.p12"
  printf '%s' "$JARVIS_KEYSTORE_PASS" > "$K/keystore.pass"
  echo "signing: the real release key (APKs can update the app installed on the phone)"
else
  keytool -genkeypair -keystore "$K/jarvis-release.p12" -storetype PKCS12 -alias jarvis -keyalg RSA -keysize 2048 -validity 3650 \
    -dname "CN=Jarvis CI" -storepass ci-only-pass -keypass ci-only-pass >/dev/null
  printf 'ci-only-pass' > "$K/keystore.pass"
  echo "signing: THROW-AWAY CI key (APKs signed with it cannot update an app installed from the real key)"
fi
chmod 600 "$K"/jarvis-release.p12 "$K"/keystore.pass
keytool -list -v -keystore "$K/jarvis-release.p12" -storepass "$(cat "$K/keystore.pass")" -alias jarvis 2>/dev/null | grep -E "SHA256:" | sed 's/^ *//' || echo "(could not read the keystore)"
