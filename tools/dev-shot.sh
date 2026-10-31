#!/data/data/com.termux/files/usr/bin/bash
# Скриншот реального устройства ТОЛЬКО если в фокусе нужное приложение ($JV_PKG, по умолчанию app.jarvis; до и после съёмки).
# Телефон может быть в активном использовании: чужой экран в кадр попасть не должен.
# usage: tools/dev-shot.sh <name> [--raise]   -> build/device/<name>.png
set -u
D="${JV_DEVICE:-10.61.19.159:35933}"
export ANDROID_ADB_SERVER_PORT="${ANDROID_ADB_SERVER_PORT:-5038}"
PKG="${JV_PKG:-app.jarvis}"
NAME="${1:?name}"; RAISE="${2:-}"
OUT="$(dirname "$0")/../build/device"; mkdir -p "$OUT"
focus() { timeout 15 adb -s "$D" shell 'dumpsys window | grep mCurrentFocus' 2>/dev/null | tr -d '\r'; }
if [ "$RAISE" = "--raise" ]; then
  timeout 15 adb -s "$D" shell "am start -n $PKG/app.jarvis.MainActivity" >/dev/null 2>&1; sleep 1.5
fi
f1="$(focus)"
case "$f1" in *" $PKG/"*) ;; *) echo "SKIP: focus is not $PKG ($f1)"; exit 3;; esac
timeout 30 adb -s "$D" exec-out screencap -p > "$OUT/$NAME.png"
f2="$(focus)"
case "$f2" in *" $PKG/"*) echo "OK $OUT/$NAME.png $(stat -c %s "$OUT/$NAME.png") bytes";;
  *) rm -f "$OUT/$NAME.png"; echo "DROPPED: focus changed during capture ($f2)"; exit 4;; esac
