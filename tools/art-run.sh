#!/data/data/com.termux/files/usr/bin/bash
# Run a main class on this phone's real Android runtime (ART + framework) via app_process.
#   tools/art-run.sh <classpath: dex|jar|apk[:more]> <MainClass> [args...]
# Android refuses writable dex files, so every classpath entry is chmod 0400 first.
# JV_ART_OPTS: extra JVM options for app_process, e.g. JV_ART_OPTS="-Djna.boot.library.path=/path -Djna.library.path=/path"
set -euo pipefail
CP="$1"; shift
IFS=: read -ra parts <<< "$CP"
for p in "${parts[@]}"; do chmod 0400 "$p"; done
exec env -u LD_LIBRARY_PATH -u LD_PRELOAD CLASSPATH="$CP" /system/bin/app_process -Xnoimage-dex2oat ${JV_ART_OPTS:-} / "$@"
