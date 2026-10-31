# Device checklist: kit1 (theme, grid, motion, icons)

Not verified on the phone yet: ADB to the vivo I2407 was off during this wave. Run these when it is back.
Variant package `app.jarvis.kit1`. Everything that brings the app to the front runs under `tools/with-screen.sh`.

Setup
1. `export ANDROID_ADB_SERVER_PORT=5038; D=10.61.19.159:38569; adb devices` → the phone is listed.
2. Build the variant: `JV_APP_ID=app.jarvis.kit1 JV_APK_OUT=$PWD/build/outputs/kit1.apk bash build.sh debug` (done once, 0.1.0 debug).
3. Install: `adb -s $D install -r build/outputs/kit1.apk`.
4. Start: `bash tools/with-screen.sh adb -s $D shell am start -n app.jarvis.kit1/app.jarvis.MainActivity`.
5. Export the focus guard: `export JV_DEVICE=$D JV_PKG=app.jarvis.kit1`.

Theme (S-11, U-01)
6. Settings (gear) → Внешний вид → Тема → «Светлая». Expected: the whole window switches at once, no restart; the status-bar
   and navigation-bar icons turn dark. Shot: `bash tools/with-screen.sh bash tools/dev-shot.sh build/device/kit1_light_appearance.png`.
7. Type text into «Имя или обращение», then switch «Тёмная» → «Светлая». Expected: the typed text stays; the scroll position of the page stays.
8. Repeat for Home, Agent, Settings root in both themes; compare with `build/preview/theme_*_{dark,light}.png`.
9. «Системная»: the owner toggles system dark mode (we do not change system settings). Expected: the app follows within a second, without restart.

Motion (S-12, U-04, U-08)
10. Appearance → Анимация → «Полная». Tap the «Пример переключателя» toggle while recording:
    `adb -s $D shell screenrecord --time-limit 2 /sdcard/kit1_toggle.mp4 &` then tap, then
    `adb -s $D pull /sdcard/kit1_toggle.mp4 build/device/` and `ffmpeg -i build/device/kit1_toggle.mp4 -vf fps=30 build/device/kit1_toggle_%02d.png`.
    Expected: the thumb slides over about 200 ms with a slight overshoot and the track colour changes; frames show intermediate positions.
11. Tap «Окно»: expected a dialog that scales and fades in (about 200 ms), and fades out on «Закрыть».
12. Tap «Шторка»: expected a sheet rising from the bottom (about 320 ms); drag it down: it closes.
13. Switch between dock items (Главная → Агент → Файлы): expected a horizontal slide with fade in the dock direction; Settings from the gear: fade-through.
14. «Сниженная»: no slides or scale, toggle snaps (fade ≤ 80 ms). «Выкл»: everything instant. Check each with step 10 frames.
15. Haptics: the toggle gives a short tick (owner feels it; the device's haptic setting must be on).

Layout and borders (U-02, S-04, V-01)
16. Settings root: cards have a visible gap (about 12 dp) between them; card edges have a hairline border.
17. Tap a field (Appearance name field, LLM key field): the border turns lavender within about 120 ms; leaves → back to grey.
18. Voice tab (V-01): cards do not overlap; the info box does not cover the first card. Shot of the tab.
19. Sandbox → Память ассистента: «Что ассистент запомнил» is not squeezed between the buttons.

Icons (U-03, B-03)
20. Settings → «Языковые модели» (LLM): the brain icon draws (it was empty before the arc fix).
21. Settings → Песочница → Инструменты агента: tool icons (still wrench for all; see UI_KIT.md) draw.
22. Chat: the attachment (paperclip) and send icons draw; copy and retry icons are visible on assistant turns.

Clean up
23. `adb -s $D uninstall app.jarvis.kit1`.
