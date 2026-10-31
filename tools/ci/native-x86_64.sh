#!/usr/bin/env bash
# CI: x86_64 variants of the two native libraries (vosk, jna) from the already downloaded and checksum-verified AARs,
# so an emulator build can be installed (the phone build ships arm64 only).
set -euo pipefail
cd "$(dirname "$0")/../.."
D=.cache/native-emu/x86_64; mkdir -p "$D"
for pair in "vosk-android-0.3.75.aar:libvosk.so" "jna-5.18.1.aar:libjnidispatch.so"; do
  aar=".cache/aar/${pair%%:*}"; so="${pair##*:}"
  unzip -p "$aar" "jni/x86_64/$so" > "$D/$so.tmp"
  mv "$D/$so.tmp" "$D/$so"
  echo "x86_64 $so: $(du -h "$D/$so" | cut -f1)"
done
