# Device checklist: glass wiring and visual polish (wire1)

Run when the owner's phone is reachable over ADB again. Nothing here has been done on the device. Everything runs in the variant
package `app.jarvis.wire1` (side by side with the owner's `app.jarvis`; never touch the owner's package).

Setup (once):
```
export ANDROID_ADB_SERVER_PORT=5038; D=10.61.19.159:38569
cd ~/jarvis-wt/wire1 && JV_APP_ID=app.jarvis.wire1 JV_APK_OUT=$PWD/build/outputs/wire1.apk bash build.sh debug
bash tools/with-screen.sh adb -s $D install -r build/outputs/wire1.apk
```
Rules: anything that brings the app to the front runs under `bash tools/with-screen.sh <cmd>`. Use `JV_DEVICE=$D JV_PKG=app.jarvis.wire1`
with `tools/dev-shot.sh`, `tools/dev-tap.sh`, `tools/dev-ui.sh`. No sound, no microphone. Open the app once before the checks.

## A. Dock and header, both themes (H-02, U-06)
1. Home screen, theme dark (Settings -> Внешний вид -> Тёмная). `dev-shot.sh`.
   Expect: the dock is a rounded translucent bar; the text behind it (chat list, status line) is soft, not sharp; a pill with a thin
   rim sits under the selected item.
2. Tap "Агент", then "Файлы", "Терминал", "Настройки". At each: the drop flows to the tapped item (about 0.35 s, slight overshoot),
   no clipping of the pill at the edges. Take a screenshot mid-flight if possible.
3. Agent screen with the keyboard open (tap the input). Expect: the dock hides, the composer sits at the keyboard top, nothing overlaps.
4. Repeat 1-3 with theme light (Settings -> Внешний вид -> Светлая). Expect: the glass is white-tinted, text stays readable.

## B. Journal filters (L-02) and event window (L-03)
1. Open "Журнал". Expect: the level control (Все / Предупр. / Ошибки / Отладка) is a glass bar under the header with a drop on "Все";
   the category row (Все, Система, Агент, LLM, ...) is a glass track with its own drop on "Все".
2. Tap "Ошибки": the level drop slides to it; the list shows only errors. Tap "LLM" in the category row: that drop slides to it and
   the list narrows. Scroll the category row sideways: the drop stays on the selected chip.
3. Tap any row. Expect: the event window is a glass sheet with the journal blurred behind it; "Закрыть" closes it.

## C. Scroll-to-bottom button (C-08, U-05)
1. Agent screen with a long chat (about 30 messages). Scroll up by a few screens.
   Expect: the round "↓" button appears with a small scale-and-fade entrance, sitting over the bottom-end corner of the chat, just above
   the composer, and the chat text under it is soft (blurred), not sharp.
2. Ask for a new reply while scrolled up (text, not voice). Expect: the unread badge appears on the button; the number stays readable
   on the accent fill.
3. Tap the button. Expect: the chat jumps to the newest message and the button disappears.
4. Rotate the phone once (if the app allows) and repeat step 1: the button stays at the bottom-end corner.

## D. Drawer (chat sessions)
1. Agent screen, swipe from the left edge (or tap the list icon). Expect: the panel slides in; its fill is translucent with the
   wallpaper blur behind it. Known limitation: the chat text behind the drawer is not blurred (the drawer lives in the content layer;
   see `docs/GLASS.md`). Decide on the phone whether that is acceptable.
2. Swipe the panel closed; tap the scrim; confirm the swipe-to-open gesture still works from the edge.

## E. Glass level setting (`ui.glass`)
1. Settings -> Внешний вид -> "Размытие стекла". Tap "?": the help sheet explains the four levels.
2. With the Agent screen open in the background, tap "Выкл": the dock and the "↓" button lose the blur at once (no restart).
   Tap "Полный", then "Упрощ.", then "Авто". Each change applies without restarting the app.
3. Expect: "Выкл" shows a plain translucent gradient; "Упрощ." shows a weaker blur than "Полный".

## F. Frame pacing and blur cost (120 Hz screen)
1. Reset stats: `adb -s $D shell dumpsys gfxinfo app.jarvis.wire1 reset`.
2. Set "Полный". Open the journal and tap the category chips fast for 20 s. Then `adb -s $D shell dumpsys gfxinfo app.jarvis.wire1`.
   Record: "Janky frames" percentage and "90th/95th percentile" frame times.
3. Repeat with "Выкл". Record the same numbers.
4. Repeat steps 2-3 on the Agent screen with the "↓" button visible and the chat scrolling.
5. Expect: "Полный" frame times within about 2 ms of "Выкл" on this phone. If the 95th percentile exceeds 8.3 ms (120 Hz) on
   "Полный", set "Авто" and confirm the governor steps down without a visible flicker. Write the numbers into this file.

## G. Light theme, same screens
1. Repeat B, C and A.3 with theme light. Expect the same structure, the drop visible on a white-tinted bar, text readable.

## H. Cleanup
1. `bash tools/with-screen.sh adb -s $D uninstall app.jarvis.wire1`.
