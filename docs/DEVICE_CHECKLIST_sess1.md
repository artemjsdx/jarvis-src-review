# DEVICE CHECKLIST — sess1 (run when the phone is reachable; nothing here was verified on the device)

Build: `JV_APP_ID=app.jarvis.sess1 JV_APK_OUT=$PWD/build/outputs/sess1.apk bash build.sh debug`, then
`export ANDROID_ADB_SERVER_PORT=5038; D=10.61.19.159:38569; adb -s $D install -r build/outputs/sess1.apk`.

1. Upgrade check: install over a previous `app.jarvis.sess1` build that has data. Expected: app starts, old messages visible, no crash in `adb logcat -d | grep -i sqlite`.
2. Open the chat, type "привет". Expected: a new session gets the title "привет" in the session list (once C-03 UI exists; until then `ui_tree` / `chat_sessions` MCP call).
3. Tap the "Очистить контекст" icon (top bar, SESSIONS_HOOK). Expected: toast "Контекст очищен"; old bubbles still visible; a divider "— контекст очищен —" appears (divider rendering is NOT verified: check it).
4. Send "что я говорил раньше?" after step 3. Expected: the model does not quote text from before the divider. Check `perf_prompt_size` (MCP) is smaller than before the clear.
5. Say (or send as text, no mic needed) "новый чат". Expected: a new empty session opens, reply "Открыл новый чат." is stored there.
6. Say "очисти контекст". Expected: reply "Контекст очищен." below a new divider.
7. Force-stop during a tool run (adb shell am force-stop $pkg) and restart. Expected: last active session restored (`ui.lastConv`), interrupted tool shows as interrupted, no lost messages.
8. Voice: "новый голосовой чат" via `Jarvis.startVoiceChat(0)` — start listening only with a headless/injected audio path (no microphone use by agents); verify the active session changed.
