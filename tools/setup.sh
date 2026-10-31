#!/data/data/com.termux/files/usr/bin/bash
# Reproduce the heavy, downloadable build inputs (kept out of git and out of backups).
#   - Android platform android.jar (API from version.properties)
#   - core-lambda-stubs.jar (javac needs LambdaMetafactory.metafactory; android.jar lacks it)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
. "$ROOT/version.properties"
CACHE="$ROOT/.cache"; mkdir -p "$CACHE/stubs"
log() { printf '[setup] %s\n' "$*"; }

for pkg in openjdk-21 aapt2 d8 apksigner zipalign; do
  command -v "${pkg/openjdk-21/javac}" >/dev/null || { log "missing tool: $pkg (pkg install $pkg)"; exit 1; }
done

JAR="$CACHE/android-$COMPILE_SDK/android.jar"
if [ ! -f "$JAR" ]; then
  ZIP="$CACHE/platform-${COMPILE_SDK}.zip"
  # resolve the exact archive name from Google's repository manifest
  URL=$(python3 - "$COMPILE_SDK" <<'PY'
import sys, urllib.request, xml.etree.ElementTree as ET
sdk = sys.argv[1]
root = ET.fromstring(urllib.request.urlopen("https://dl.google.com/android/repository/repository2-3.xml", timeout=60).read())
for rp in root.iter("remotePackage"):
    if rp.get("path") == "platforms;android-" + sdk:
        print("https://dl.google.com/android/repository/" + rp.find(".//archive/complete/url").text); break
PY
)
  [ -n "$URL" ] || { log "platform android-$COMPILE_SDK not found in repository manifest"; exit 1; }
  log "downloading $URL"
  curl -fSL -C - --retry 5 --retry-delay 2 -o "$ZIP" "$URL"
  unzip -tq "$ZIP" >/dev/null || { log "corrupt download, removing"; rm -f "$ZIP"; exit 1; }   # integrity before use
  unzip -o -q -j "$ZIP" "android-*/android.jar" -d "$CACHE/android-$COMPILE_SDK"
  rm -f "$ZIP"
fi
log "android.jar: $(du -h "$JAR" | cut -f1)"

if [ ! -f "$CACHE/stubs/core-lambda-stubs.jar" ]; then
  T=$(mktemp -d); javac --release 8 -Xlint:-options -d "$T" "$ROOT/tools/stubs/src/java/lang/invoke/LambdaMetafactory.java"
  (cd "$T" && jar cf "$CACHE/stubs/core-lambda-stubs.jar" java); rm -rf "$T"
fi
log "lambda stubs ready"

# ---- JVM test libraries (checksums pinned; used by tools/run-tests.sh only, never shipped) -----------------------------
TL="$CACHE/testlibs"; mkdir -p "$TL"
fetch_pinned() { # url sha256 dest
  local url="$1" sum="$2" dest="$3"
  if [ -f "$dest" ] && [ "$(sha256sum "$dest" | cut -d' ' -f1)" = "$sum" ]; then return 0; fi
  log "downloading $(basename "$dest")"
  curl -fSL --http1.1 -C - --retry 5 --retry-delay 2 -o "$dest.part" "$url"
  [ "$(sha256sum "$dest.part" | cut -d' ' -f1)" = "$sum" ] || { rm -f "$dest.part"; log "checksum mismatch for $(basename "$dest")"; exit 1; }
  mv "$dest.part" "$dest"
}
fetch_pinned https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar 8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3 "$TL/junit-4.13.2.jar"
fetch_pinned https://repo1.maven.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar 66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9 "$TL/hamcrest-core-1.3.jar"
fetch_pinned https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar 3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed "$TL/json-20240303.jar"
log "test libraries ready"

bash "$ROOT/tools/setup-native.sh"   # vosk-android + jna AARs for app.jarvis.wake (see tools/setup-native.sh)
