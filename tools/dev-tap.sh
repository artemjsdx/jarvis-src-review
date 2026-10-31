#!/data/data/com.termux/files/usr/bin/bash
# Tap / key / text on the real device ONLY if app.jarvis has window focus at the moment of the call
# (check and input run in ONE adb shell invocation, so the race window is a few ms).
# usage: tools/dev-tap.sh tap X Y | key CODE | text 'ascii%stext' | swipe X1 Y1 X2 Y2 [ms]
set -u
D="${JV_DEVICE:-10.61.19.159:35933}"
export ANDROID_ADB_SERVER_PORT="${ANDROID_ADB_SERVER_PORT:-5038}"
PKG="${JV_PKG:-app.jarvis}"
kind="${1:?tap|key|text|swipe}"; shift
case "$kind" in
  tap|key|text|swipe) ;;
  *) echo "bad kind"; exit 2;;
esac
args=""; for a in "$@"; do case "$a" in *[!A-Za-z0-9%._-]*) echo "unsafe arg: $a"; exit 2;; esac; args="$args $a"; done
out="$(timeout 20 adb -s "$D" shell "if dumpsys window | grep mCurrentFocus | grep -q ' $PKG/'; then input $kind$args && echo DONE; else echo SKIP_FOCUS; fi" 2>&1 | tr -d '\r')"
echo "$out"
[ "$out" = "DONE" ]
