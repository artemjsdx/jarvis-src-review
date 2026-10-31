#!/usr/bin/env bash
set -Eeuo pipefail

# This runs on a real booted Android emulator, not only a Java compiler.
mkdir -p smoke-output
trap 'adb logcat -d -v brief -s AndroidRuntime:E > smoke-output/runtime-errors.txt 2>/dev/null || true' EXIT

adb wait-for-device
adb install -r smoke-input/Jarvis-uifix.apk
adb shell settings put global window_animation_scale 0.5
adb shell settings put global transition_animation_scale 0.5
adb shell settings put global animator_duration_scale 0.5
adb shell monkey -p app.jarvis.uifix -c android.intent.category.LAUNCHER 1
sleep 8
adb shell pidof app.jarvis.uifix
adb shell screencap -p /sdcard/ui-home.png
adb pull /sdcard/ui-home.png smoke-output/home.png

size=$(adb shell wm size | tr -d '\r' | tail -1)
dimensions=$(printf '%s' "$size" | grep -oE '[0-9]+x[0-9]+' | tail -1)
test -n "$dimensions"
width=${dimensions%x*}
height=${dimensions#*x}
adb shell input tap "$((width - 65))" "$((height * 8 / 100))"
sleep 3
adb shell screencap -p /sdcard/ui-settings.png
adb pull /sdcard/ui-settings.png smoke-output/settings.png

adb shell input swipe "$((width / 2))" "$((height * 7 / 10))" "$((width / 2))" "$((height * 3 / 10))" 520
sleep 2
adb shell screencap -p /sdcard/ui-settings-scroll.png
adb pull /sdcard/ui-settings-scroll.png smoke-output/settings-scroll.png

adb shell dumpsys gfxinfo app.jarvis.uifix framestats > smoke-output/gfxinfo.txt
adb shell uiautomator dump /sdcard/ui-hierarchy.xml >/dev/null 2>&1 || true
adb pull /sdcard/ui-hierarchy.xml smoke-output/ui-hierarchy.xml >/dev/null 2>&1 || true
adb logcat -d -v brief -s AndroidRuntime:E > smoke-output/runtime-errors.txt
if grep -E 'FATAL EXCEPTION|Process: app.jarvis.uifix' smoke-output/runtime-errors.txt; then
  echo "::error::Android runtime exception detected in Jarvis"
  exit 1
fi
adb shell pidof app.jarvis.uifix
