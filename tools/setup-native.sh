#!/data/data/com.termux/files/usr/bin/bash
# Third-party native/Java dependencies for app.jarvis.wake (offline wake word / STT on Vosk).
# Downloads the two AARs from Maven Central with pinned SHA-256, then extracts:
#   classes.jar        -> .cache/appjars/<name>.jar      (compile classpath + d8 dex-merge, see build.sh)
#   jni/arm64-v8a/*.so -> .cache/native/arm64-v8a/       (packaged into lib/<abi>/ by mkapk.py)
# Idempotent, resumable (curl --http1.1 -C -), never leaves a partial file at the final path.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE="$ROOT/.cache"; AAR="$CACHE/aar"; APPJARS="$CACHE/appjars"; NATIVE="$CACHE/native/arm64-v8a"
M2="https://repo1.maven.org/maven2"
mkdir -p "$AAR" "$APPJARS" "$NATIVE"
log() { printf '[setup-native] %s\n' "$*"; }
die() { printf '[setup-native] %s\n' "$*" >&2; exit 1; }

# name          version  artifact                        size      sha256
# vosk-android  0.3.75   com.alphacephei:vosk-android    13472638  ab2f8b91ac8051561aa325546b35fed9a68b36b8121bac5c6fb927525c4adfad
# jna           5.18.1   net.java.dev.jna:jna (aar)       522677  7f053e3ec99e14dd71259c82c1c8a02738d64a13c31226b2acc170f3060951e0
VOSK_AAR="$AAR/vosk-android-0.3.75.aar"
VOSK_URL="$M2/com/alphacephei/vosk-android/0.3.75/vosk-android-0.3.75.aar"
VOSK_SHA="ab2f8b91ac8051561aa325546b35fed9a68b36b8121bac5c6fb927525c4adfad"
VOSK_CLASSES=17718
VOSK_SO=10042800
JNA_AAR="$AAR/jna-5.18.1.aar"
JNA_URL="$M2/net/java/dev/jna/jna/5.18.1/jna-5.18.1.aar"
JNA_SHA="7f053e3ec99e14dd71259c82c1c8a02738d64a13c31226b2acc170f3060951e0"
JNA_CLASSES=220845
JNA_SO=176520

fetch() { # <url> <dest> <sha256>
  local url="$1" dest="$2" want="$3"
  if [ -f "$dest" ]; then
    if echo "$want  $dest" | sha256sum -c - >/dev/null 2>&1; then log "cached $(basename "$dest")"; return 0; fi
    log "existing $(basename "$dest") has wrong hash, redownloading"
    rm -f "$dest"
  fi
  log "downloading $url"
  # partial data lives in <dest>.part; the final path only ever sees a fully verified file
  curl -fsSL --http1.1 -C - --retry 5 --retry-delay 2 -o "$dest.part" "$url" || die "download failed: $url"
  mv -f "$dest.part" "$dest"
  echo "$want  $dest" | sha256sum -c - >/dev/null 2>&1 || { rm -f "$dest"; die "SHA-256 mismatch for $(basename "$dest")"; }
}

entry_size() { unzip -l "$1" | awk -v n="$2" '$4 == n { print $1 }'; }

extract_one() { # <aar> <classes-size> <so-name> <so-size> <jar-name>
  local aar="$1" csize="$2" so="$3" sosize="$4" jarname="$5"
  unzip -tq "$aar" >/dev/null || die "corrupt AAR: $aar"
  local have
  have=$(entry_size "$aar" "classes.jar")
  [ "$have" = "$csize" ] || die "classes.jar size $have in $(basename "$aar") != expected $csize"
  have=$(entry_size "$aar" "jni/arm64-v8a/$so")
  [ "$have" = "$sosize" ] || die "jni/arm64-v8a/$so size $have in $(basename "$aar") != expected $sosize"
  rm -f "$APPJARS/$jarname.tmp" "$NATIVE/$so.tmp"
  unzip -p "$aar" classes.jar > "$APPJARS/$jarname.tmp"
  unzip -p "$aar" "jni/arm64-v8a/$so" > "$NATIVE/$so.tmp"
  # entry size was checked against the AAR, and unzip -tq verified the archive CRCs
  mv -f "$APPJARS/$jarname.tmp" "$APPJARS/$jarname"
  mv -f "$NATIVE/$so.tmp" "$NATIVE/$so"
  log "extracted $jarname ($(du -h "$APPJARS/$jarname" | cut -f1)), $so ($(du -h "$NATIVE/$so" | cut -f1))"
}

fetch "$VOSK_URL" "$VOSK_AAR" "$VOSK_SHA"
fetch "$JNA_URL" "$JNA_AAR" "$JNA_SHA"
extract_one "$VOSK_AAR" "$VOSK_CLASSES" libvosk.so "$VOSK_SO" vosk-android.jar
extract_one "$JNA_AAR" "$JNA_CLASSES" libjnidispatch.so "$JNA_SO" jna.jar
log "done: $APPJARS/{vosk-android,jna}.jar + $NATIVE/{libvosk,libjnidispatch}.so"
