# DEVICE CHECKLIST mcp2 — что не проверено без телефона

Телефон владельца: ADB выключен. Всё ниже выполняется, когда доступен ADB или владелец сам ставит сборку и открывает приложение.
Сборка: `JV_APP_ID=app.jarvis.mcp2 JV_APK_OUT=$PWD/build/outputs/mcp2.apk bash build.sh debug` (в worktree mcp2). После проверки: `adb -s $D uninstall app.jarvis.mcp2`.
Переменные: `export ANDROID_ADB_SERVER_PORT=5038 JV_PKG=app.jarvis.mcp2`. Звук и микрофон не использовать; UI-команды под `bash tools/with-screen.sh`.

## A. Запуск и токен (без ADB после первой установки)
1. Установить APK (владелец или `adb install -r`), один раз открыть приложение. Ожидание: Настройки → «Мост для агента»: переключатель включён (debug), статус «порт NNNN».
2. В Termux: `curl -s http://127.0.0.1:8767/health` (для `app.jarvis`) или порт из статуса страницы. Ожидание: JSON с `"app":"app.jarvis.mcp2"`.
3. «Скопировать токен», положить в `~/.jarvis-keys/mcp-app.jarvis.mcp2.json` как `{"token":"…"}`. (Для personal-сборки токен уже там: `python3 tools/make-provisioned.py` создаёт его и пишет `mcp.token` в provisioned.json.)
4. `JV_PKG=app.jarvis.mcp2 python3 tools/mcp-smoke.py`. Ожидание: отчёт в `build/device/`, нет FAIL; все SKIP объяснены.
5. Перезапуск приложения и `adb install -r`: токен прежний (файл `/sdcard/Android/data/<pkg>/files/mcp/token` сохранился). «Сбросить токен» → старый токен даёт 401, прокладка читает новый файл.

## B. Старт без окна (McpStartReceiver)
6. Принудительно остановить приложение (Настройки Android → Приложения) и НЕ открывать. `am broadcast -n app.jarvis.mcp2/app.jarvis.mcp.McpStartReceiver -a app.jarvis.mcp.START` из Termux. Ожидание: broadcast не доставлен (stopped state) — это документированное поведение, прокладка просит открыть приложение.
7. Открыть приложение один раз, свернуть, смахнуть из недавних (процесс жив, пока работает служба) и повторить пункт 6 после `am kill`/смерти процесса: ожидание `Broadcast completed`, затем `/health` отвечает ≤ 5 с, окно не показывается, уведомление «Мост для агента активен» есть. Если `startForegroundService` отклонён из фона, в журнале (cat mcp) запись «Служба моста не запущена из фона», сервер всё равно отвечает.
8. `python3 tools/jarvis-mcp-shim.py` с ADB выключенным: клиент вызывает `app_launch`, затем `app_info`. Ожидание: метод `broadcast` в ответе.
9. С ADB: `am start -n app.jarvis.mcp2/app.jarvis.mcp.McpLaunchActivity` — окна нет, `/health` отвечает.

## C. Android-реализации инструментов (не проверены JVM-тестами)
10. `python3 tools/mcp-smoke.py` — все сценарии: chat (нужен ключ: `secrets_set key.openrouter`, модель `inception/mercury-3`), sessions, agent_loop, db_recovery (SQLite), llm_providers, tools_*, journal, settings, secrets.
11. Гейты: при выключенных `mcp.allowAudio`/`mcp.allowDestructive` сценарии `gate` проходят; включить оба в странице моста, перезапустить раннер — проходят парные сценарии `*_on`; потом выключить.
12. Журнал: после вызовов в `journal_tail(cat="mcp")` есть записи «MCP <tool> ok», аргументы замаскированы (`secrets_set` → `***`).
13. Тихий чат: `chat_send` без allowAudio — динамик молчит (на слух; `adb shell dumpsys audio` не показывает активного плеера).

## D. Окно (LiveUiHost) и спайк S1
14. Открыть приложение. `ui_hosts`: live available=true, visible=true. `ui_navigate home` → `ui_tree`: узлы с `res`/`tag`, `ui_screenshot scale=0.5` — картинка совпадает с экраном, размытие на месте (PixelCopy).
15. Свернуть приложение: `ui_hosts` visible=false; `ui_tree` работает; `ui_screenshot` рисуется программно (без размытия — зафиксировать в отчёте).
16. Сценарий `ui_host` (needsUi): навигация в настройки, `ui_tap` по тексту «Мост для агента», страница открылась, `ui_swipe`, `ui_long_press`, `ui_key BACK`. Проверить, что нажатия реально сработали (`ui_tree` после каждого).
17. `ui_type` в поле чата (экран agent): текст появился; `ui_anim_probe` по виду (например `tag` кнопки) при открытии страницы: `changedFrames` > 0, `avgFrameMs` ≈ 16.
18. **Спайк S1 (HeadlessUiHost)**. Эксперимент: `DisplayManager.createVirtualDisplay("jarvis-mcp", 1080, 2400, 440, imageReader.getSurface(), VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | VIRTUAL_DISPLAY_FLAG_PRESENTATION)` → `Presentation` с `Shell` → снимок из `ImageReader`.
    Критерии успеха: (a) кадр приходит ≤ 1 с и не чёрный; (b) `RenderEffect`/AGSL-размытие видно на кадре; (c) `ValueAnimator` идёт (≥ 30 кадров за 500 мс через `ui_anim_probe`); (d) `dispatchTouchEvent` по виду срабатывает; (e) окно владельца не мигает, звук/фокус не меняются; (f) расход CPU ≤ 10 % в простое.
    Если (b) или (c) не выполняются — режим «рендер в Bitmap без окна» (`View.draw`), `ui_hosts` честно пишет «без блюра и анимаций». Результат записать в docs/MCP.md раздел 4.

## E. Страница «Мост для агента» (визуально)
19. `JV_PKG=app.jarvis.mcp2 bash tools/dev-shot.sh` на странице: подписи не обрезаны, переключатели работают, «Скопировать токен» кладёт 64 hex в буфер (проверить `adb shell cmd clipboard` недоступно — вставить в поле ввода и прочитать `ui_tree`), «Сбросить токен» показывает диалог.
