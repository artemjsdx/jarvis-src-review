# Проверка на телефоне: mcp3 (замены заглушек моста)

Не проверено на устройстве (ADB к телефону был выключен). Выполнять, когда владелец откроет приложение. Пакет: `app.jarvis.mcp3` (side-by-side сборка) или `app.jarvis` (владелец).
Все команды через `jv.py` / `jv-check.py` (токен и порт находятся сами). Звук из динамика и микрофон НЕ использовать без явного разрешения владельца.

Подготовка
1. `python3 tools/jv.py --pkg <пакет> health` — приложение отвечает. Ожидаемо: строка с портом и версией.
2. `python3 tools/jv-check.py --pkg <пакет> --no-llm` — итог FAIL 0. WARN допустимы: «ходов ещё не было», «ошибки в журнале» (сверить вручную).

Перфоманс и окно
3. Экран включён, Jarvis на переднем плане. `python3 tools/jv.py --pkg <пакет> perf_frames '{"durationMs":3000}'` — ожидаемо: `frames` > 100, `jankPct` < 10 на простое, `p50Ms` около 8–17.
4. Свернуть приложение (Home) и повторить п.3 — ожидаемо: `NOT_VISIBLE` с текстом «откройте приложение на телефоне» (не падение и не пустой ответ).
5. `python3 tools/jv.py --pkg <пакет> ui_tree '{"maxDepth":3}'` при свёрнутом окне — `UI_UNAVAILABLE`/`NOT_VISIBLE` с подсказкой; при открытом — дерево с `resName`.

Сеть
6. `python3 tools/jv.py --pkg <пакет> net_throttle '{"latencyMs":300,"kbpsDown":400,"durationMs":60000}'` → `active: true`.
7. `python3 tools/jv.py --pkg <пакет> net_probe '{}'` — у каждого хоста `firstByteMs` ≥ 300 (задержка добавлена), `throttled: true`.
8. `python3 tools/jv.py --pkg <пакет> net_throttle '{"lossPct":50,"durationMs":60000}'`, затем `llm_ping` — ожидаемо: ошибка сети (соединение оборвано), а не зависание дольше таймаута.
9. `python3 tools/jv.py --pkg <пакет> net_throttle '{"off":true}'` → `active: false`; `net_probe` снова без задержки.
10. `net_throttle` с `latencyMs: 99999` → `BAD_ARGS`.

Озвучка (в файл, без звука)
11. `python3 tools/jv.py --pkg <пакет> voice_speak '{"text":"Привет, это проверка связи."}'` — `provider`, `bytes` > 0, `durationMs` > 0, `path` внутри `files/mcp/audio/`, `firstByteMs` и `totalMs` заполнены.
12. Повторить тот же вызов — `cacheHit: true`, `firstByteMs` заметно меньше (кэш работает).
13. `python3 tools/jv.py --pkg <пакет> tts_cache '{"action":"stats"}'` — записи и байты увеличились после п.11.
14. Для каждого провайдера с ключом (edge, elevenlabs, gtts): `voice_speak` с `"provider":"<id>"` — `provider` совпадает; у ElevenLabs без ключа ожидается понятная ошибка, а не падение.

Распознавание и инъекция
15. Подготовить WAV: `ffmpeg -i $TMPDIR/edge_ru-RU-SvetlanaNeural.mp3 -ac 1 -ar 16000 /tmp/svet.wav` и закодировать в base64 (файл маленький, ~100 КБ; на стенде отправка медленная — это свойство стенда).
16. `voice_stt_file` с `base64` из п.15 — `text` содержит «Привет» и «Джарвис»; `format: wav`, `ms` записан.
17. `voice_stt_file` с `path` на MP3 из `$TMPDIR` (в песочнице приложения, если путь разрешён) — тот же текст, `format: mp3`.
18. `voice_inject` с тем же WAV, `mode: command` — ответ `turn` с `marks`, `spans.stt`, `spans.llm_ttft`, `spans.to_sound`; `transcript` содержит «Привет»; динамик молчит (проверить на слух владельцем или по `journal_tail cat=tts`: нет записей «Воспроизведение»).
19. `perf_summary '{"n":10}'` — `voice.to_sound.n` вырос на число инъекций.

Wake и микрофон (нужен `mcp.allowAudio`; включать только с разрешения владельца)
20. Без `mcp.allowAudio`: `wake_arm '{"on":true}'` и `mic_level '{}'` → `FORBIDDEN`.
21. С разрешением: `wake_state '{}'` — `enabled`, `micActive`, список фраз; `wake_arm '{"on":false}'` возвращает состояние выключенным.

Сессии и веб
22. `python3 tools/mcp-smoke.py --pkg <пакет> --only sessions` — три сценария PASS (включая sessions_* create/rename/pin/search/archive/delete).
23. `agent_tool_call` `web_fetch` с `url` = `http://127.0.0.1:<порт из endpoint.json>/health` — `status` 200 в `data`.
24. `agent_tool_call` `web_search` с запросом «погода» — ответ без ошибки (если сеть и ключ доступны; иначе понятная ошибка провайдера).

Итог
25. `python3 tools/mcp-smoke.py --pkg <пакет>` — отчёт `build/device/mcp-report-*.md`; сохранить строки по `perf_*`, `net_*`, `voice_*`, `sessions`.
26. Владелец: `adb -s $D uninstall app.jarvis.mcp3` после проверки (только свою сборку).
