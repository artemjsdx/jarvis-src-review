# DEVICE CHECKLIST — set1 (Voice tab and settings pages)

Not verified on the phone: the owner's vivo I2407 was not reachable over ADB (wave 3, mobile data).
Run these when ADB is back. Variant: `app.jarvis.set1`. Use `tools/with-screen.sh` for anything that opens the app.

Setup: `export ANDROID_ADB_SERVER_PORT=5038; D=10.61.19.159:38569`; install
`adb -s $D install -r build/outputs/set1.apk`; `JV_PKG=app.jarvis.set1`.

## A. Voice tab (V-01..V-03)
1. Open «Голос». Expected: every provider card has two-line button labels; «Проверить», «Голоса», «Квота», «Настройки» are fully readable (no «Пров…»).
2. Tap the «?» on the chain card and on a provider card. Expected: bottom sheet with a title and 2–3 lines; «Понятно» closes it.
3. Scroll to «Текст проверки». Expected: three full phrases as wrapped rows (none cut); a counter «NN / 300» under the field.
4. Tap a phrase. Expected: it lands in the field; the counter updates.
5. Tap «Озвучить» (to the file only; do not play sound out of the speaker). Expected: progress text, then the result row; «Стоп» stops it.
6. Screenshot every state for the owner: top, chain card, test box, help sheet.

## B. Settings list and voice/wake/ADB pages (S-08, S-16)
1. Open «Настройки». Expected: «Языковая модель (LLM)» shows a robot icon; «Голос и озвучка» shows «скорость 1.00×»; «ADB по Wi-Fi» shows «не настроено» or the saved address.
2. Tap the «?» on each row. Expected: short Russian help sheet, no crash.
3. Open «Голос и озвучка». Expected: no «в разработке»; speed slider works; the test button plays to the speaker only after tap (skip if sound is off).
4. Open «Обращение и распознавание». Expected: the real page (no «в разработке»).
5. Open «ADB по Wi-Fi». Expected: real form (paste field, pair/connect, status). Do NOT pair with the owner's adb keys; only check the layout.

## C. Memory list (S-10)
1. Settings → «Песочница и инструменты» → «Память ассистента». Add two notes with `Добавить`, one of them 300+ characters.
2. Expected: newest note first; each row shows the text (up to 3 lines, no mid-word breaks) and «дд.мм чч:мм».
3. Tap the long note. Expected: sheet with the full text (scrolls), «Изменить», «Копировать», «Удалить».
4. «Копировать» then paste into the chat input: the full text appears.
5. «Удалить» → confirm → note disappears from the list.

## D. Light theme
Not implemented in this build: the palette is read once at start (U-01 is open with kit1). Check after U-01 lands.

## Pass criteria
All steps show the expected result, no crash in `adb logcat -d` for `app.jarvis.set1`. Attach screenshots to the report.
