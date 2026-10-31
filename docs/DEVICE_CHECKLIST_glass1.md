# Device checklist: liquid glass (glass1)

Run when the owner's phone is reachable over ADB again. Nothing here has been done on the device yet. Everything runs in the
variant package `app.jarvis.glass1` (side by side with the owner's `app.jarvis`; never touch the owner's package).

Setup (once):
```
export ANDROID_ADB_SERVER_PORT=5038; D=10.61.19.159:38569
cd ~/jarvis-wt/glass1 && JV_APP_ID=app.jarvis.glass1 JV_APK_OUT=$PWD/build/outputs/glass1.apk bash build.sh debug
adb -s $D install -r build/outputs/glass1.apk
```
Rules: anything that brings the app to the front runs under `bash tools/with-screen.sh <cmd>`; use `JV_DEVICE=$D JV_PKG=app.jarvis.glass1`
with `tools/dev-shot.sh` / `dev-tap.sh`. No sound, no microphone.

## A. Dock blur and drop, static (Android 13+ expected to show refraction)
1. Launch `app.jarvis.glass1` (home screen). Take a screenshot (`dev-shot.sh`).
   Expect: the dock is a rounded translucent bar with a blur of what is behind it (text under the dock is soft, not sharp); a pill
   with a thin light rim sits under "Главная"; the icons stay readable on the dark theme.
2. Zoom in on the pill edges. Expect: a faint lens look (the edge of the pill shows the blurred background bending). If the pill looks flat
   (a tint and rim only), note it: the refraction may be absent on this build (see `docs/GLASS.md`, known limitations).
3. Switch the theme to light (Settings > Appearance). Expect: the dock turns light translucent; the label colour stays readable.

## B. Drop flow (the owner's main request)
4. Tap "Агент", then take three screenshots about 80 ms apart (`dev-shot.sh` in a loop, or `adb shell screenrecord` for 1 s and extract
   frames with `ffmpeg -i rec.mp4 -vf fps=12`). Expect: the pill moves from the old item to the new one in about 0.3 s, it stretches
   along the motion and does not pass through the middle items unevenly; it settles without ringing (no bounce back and forth).
5. Tap quickly from "Главная" to "Журнал" twice in a row. Expect: the pill redirects smoothly (no jump to the old target).
6. Settings > Appearance > animations "reduced" (or system animator scale 0). Expect: the pill jumps, with no flow.

## C. Dialogs and sheets over text
7. Open the journal (Журнал) and tap an event row (opens the event window). Expect: a rounded translucent panel, the log text behind it
   blurred, the screen behind dimmed by the scrim.
8. Open "Выбор" sheets (e.g. the model chooser in settings). Expect: the same blur under the sheet.
9. Toast (e.g. copy a text): expect a blurred pill above the dock.

## D. Journal filters (L-02) and scroll-to-bottom (C-08)
10. Only if the lead has wired `SegmentedGlass` / `Glass.floating` into LogScreen / AgentScreen: check the segmented control drop flows between
    filters, and the scroll-to-bottom button over chat text is blurred.

## E. Quality levels and cost
11. Settings > Appearance > glass (kit1 entry; if it is not in the build yet, skip this step and report): set `full`, then `reduced`, then `off`. Expect: `full` blur + drop; `reduced` blur with a flatter drop (no
    refraction); `off` the translucent gradient only. Each change applies without restarting the app.
12. Measure frame cost during the drop animation, three runs each for `full` and `off`:
    `adb -s $D shell dumpsys gfxinfo app.jarvis.glass1 reset`, then tap the dock 10 times, then
    `adb -s $D shell dumpsys gfxinfo app.jarvis.glass1 framestats | tail -n 40` (note the 90th/95th percentile of "Total duration").
    Record the numbers in `docs/GLASS.md` under "Measurements (phone)". Budget: 16.6 ms per frame at 60 Hz.
13. Auto: leave `auto`, scroll a long chat for 20 s. The governor steps down silently (no log line). Check with step 11 that the blur
    disappears or softens only if frames are actually slow; note the result.

## F. Other devices
14. Realme Note 50 (3 GB, the target): repeat A, B, C at `full`. Note the Android version. Expected on Android 12 (API 31/32): blur with no
    refraction; on Android 8-11: the translucent gradient with the same spring motion and no blur.

## G. Cleanup
15. `adb -s $D uninstall app.jarvis.glass1`. Report results with screenshot paths and the `dumpsys` numbers.
