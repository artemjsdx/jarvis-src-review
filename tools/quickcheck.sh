#!/data/data/com.termux/files/usr/bin/bash
# Compile selected sources (and only what they pull in through -sourcepath) against android.jar: a fast way to type-check
# my own files while other workers have half-written files elsewhere in the tree.
#   bash tools/quickcheck.sh app/src/main/java/app/jarvis/rt/*.java ...
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
W="$ROOT/build/qc-$$"; mkdir -p "$W"; trap 'rm -rf "$W"' EXIT
CP=""; for j in "$ROOT"/.cache/appjars/*.jar; do [ -f "$j" ] && CP="$CP:$j"; done
javac -source 8 -target 8 -Xlint:-options -nowarn -encoding UTF-8 -bootclasspath "$ROOT/.cache/android-36/android.jar:$ROOT/.cache/stubs/core-lambda-stubs.jar" \
  -sourcepath "$ROOT/app/src/main/java:$ROOT/app/src/debug/java:$ROOT/.cache/gen" ${CP:+-classpath "${CP#:}"} -implicit:class -d "$W/out" "$@" 2>&1 | grep -v '^Note:' 
echo "quickcheck done"
