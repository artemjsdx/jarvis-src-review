# MCP-мост Jarvis — спецификация (M-01 … M-08)

Цель владельца: «внедри в приложение MCP; при запуске приложения MCP запускается; это мост, который соединит агента с приложением;
весь функционал, все логи, любые действия доступны удалённо; предусмотреть ко всему, и к новому, и к старому; чтобы агент сам всё протестировал».
Владелец при этом пользуется телефоном, поэтому по ADB можно только запустить приложение, и оно должно работать в фоне без показа окна.

## 1. Архитектура

```
opencode / субагенты (Termux)          телефон
  MCP-клиент --stdio--> jarvis-mcp-shim.py --HTTP 127.0.0.1:PORT/mcp (Bearer)--> McpServer в процессе app.jarvis
                              |                                                   |-- McpRegistry (инструменты по областям)
                              |-- adb (host-side инструменты: запуск, установка, logcat)     |-- UiHost (live / headless)
                              `-- токен: adb shell cat /sdcard/Android/data/app.jarvis/files/mcp/token
```

- **Сервер в приложении** (`app.jarvis.core.mcp` — чистая Java, тестируется на JVM; `app.jarvis.mcp` — Android-часть).
  Только `ServerSocket` на `127.0.0.1` (никаких внешних интерфейсов), HTTP/1.1 с keep-alive, без внешних библиотек.
- **Транспорт**: Streamable HTTP (спецификация MCP 2025-03-26 / 2025-06-18), `POST /mcp` с JSON-RPC 2.0 (одиночный запрос или batch).
  Ответ: `application/json`; SSE только если клиент не принимает JSON. `GET /mcp` → 405 (серверных уведомлений в v1 нет).
  `GET /health` без токена: `{"ok":true,"app":"app.jarvis","version":"…","flavor":"…","uptimeMs":…}` (для прокладки).
  Заголовок `Mcp-Session-Id` выдаётся на `initialize`. Методы: `initialize`, `notifications/initialized`, `ping`, `tools/list`, `tools/call`,
  `resources/list`, `resources/read`.
- **Защита**: `Authorization: Bearer <токен>` (случайные 32 байта hex, файл `files/mcp/token` во внешнем каталоге приложения, права 600 где возможно;
  читается по ADB без `run-as`), проверка `Host` (127.0.0.1/localhost + порт) и `Origin` (если есть — только loopback) против DNS-rebinding.
  Сравнение токена постоянного времени. Лимит тела запроса 4 МиБ, 8 одновременных соединений, таймаут чтения 30 с.
- **Включение**: настройка `mcp.enabled`; по умолчанию true в debug и personal, false в release. Порт по умолчанию 8767, при занятости следующий
  свободный; фактический адрес пишется в `files/mcp/endpoint.json` (`host`, `port`, `pid`, `started`).
- **Жизненный цикл**: `JarvisApp.onCreate` вызывает `McpBridge.installIfEnabled()`; `McpService` (foreground, уведомление «Мост для агента активен»
  с действием «Остановить») удерживает процесс. Запуск по ADB без окна: `adb shell am start -n app.jarvis/.mcp.McpLaunchActivity`
  (Theme.NoDisplay, excludeFromRecents, сразу finish; стартует McpService). Обычная «Главная» при этом не показывается.
- **Прокладка** `tools/jarvis-mcp-shim.py` (Python stdlib, stdio MCP, строки JSON): отвечает на `initialize` сама (клиент стартует мгновенно, даже если
  приложение закрыто); `tools/list` = инструменты приложения (кэш на диске при недоступности) + host-side инструменты прокладки;
  `tools/call` пересылает в приложение; при отказе соединения сама делает `ensure_app()` (ADB-подключение, `am start` NoDisplay-активити, ожидание
  `/health`, чтение токена) и повторяет вызов один раз. ADB выбирается так: `ANDROID_ADB_SERVER_PORT` (по умолчанию 5038), устройство из
  `JV_DEVICE` или единственное подключённое. Host-side инструменты: `device_status`, `app_launch`, `app_stop`, `apk_install(path)`, `device_logcat(n,level)`,
  `device_screenshot` (adb screencap с проверкой фокуса, пропускает чужие окна).
- **Конфиг opencode** (`~/.config/opencode/opencode.jsonc`, ключ `mcp.jarvis`): `{"type":"local","command":["python3","/data/data/com.termux/files/home/jarvis/tools/jarvis-mcp-shim.py"],"timeout":180000,"enabled":true}`.
  Субагенты фермы получают тот же сервер в своём `.claude`/`mcp` конфиге.

## 2. Правила поведения (чтобы не мешать владельцу)

1. Любой UI-инструмент принимает `host`: `headless` (по умолчанию, если доступен) или `live` (то, что видит владелец; только по явной просьбе).
2. Звук и микрофон выключены по умолчанию: `voice_speak` пишет в файл и возвращает тайминги; воспроизведение (`output=speaker`) и `mic_*`
   требуют `mcp.allowAudio=true`. `voice_inject` подаёт готовый звук в конвейер вместо микрофона.
3. Тестовые чаты: `chat_*` без `session` работают в автоматической сессии `mcp-<sid>`; такие сессии скрыты в боковом меню, пока не включено «показывать тестовые».
4. Опасные операции (`secrets_get`, `db_exec`, `settings_reset`, удаление вне песочницы) требуют `mcp.allowDestructive=true` (по умолчанию false).
   `secrets_list` показывает только имена, последние 4 символа и время сохранения.
5. Каждый вызов пишется в журнал (категория `mcp`: инструмент, аргументы с маскированием и обрезкой > 200 символов, длительность, ok/ошибка).
6. Ошибки инструмента: `isError: true` и JSON `{"error":{"code","message","technical"}}`; исключения не выходят наружу как HTTP 500.
7. Длительные инструменты (`chat_send wait=true`, `voice_stt_file`) имеют `timeoutMs` (максимум 120 000) и корректно отменяются.

## 3. Каталог инструментов v1

Имена: `<область>_<глагол>` (допустимы `a-z0-9_`). Параметры в скобках; * — обязательные. Все результаты JSON в `content[0].text`; картинки как `image` (base64).

- **app**: `app_info` (версия, флейвор, SDK, uptime, процесс, разрешения, состояние служб), `app_health` (память, потоки, CPU, батарея, сеть),
  `app_capabilities` (реестр функций и покрытие инструментами/сценариями), `app_quit`.
- **journal**: `journal_tail(n, level, cat, since, text)`, `journal_mark(label)` (метка начала теста), `journal_clear` (destructive).
- **settings**: `settings_list(prefix)`, `settings_get(key*)`, `settings_set(key*, value*)`, `settings_reset(key*)` (destructive).
  **secrets**: `secrets_list`, `secrets_set(name*, value*)`, `secrets_get` (destructive).
- **db**: `db_schema`, `db_query(sql*, params, limit)` — только SELECT/PRAGMA, лимит строк; `db_exec` (destructive).
- **chat**: `chat_sessions`, `chat_new(title)`, `chat_send(text*, session, wait, timeoutMs, source=text|voice)` → ответ, события, вызовы инструментов, тайминги;
  `chat_history(session, n)`, `chat_clear_context(session)`, `chat_cancel(session)`, `chat_wait_idle(session, timeoutMs)`, `chat_state`.
  `chat_seed(session, items[])` (destructive: только при mcp.allowDestructive) — готовые элементы чата без модели для скриншотов: kind user|assistant|tool|error|system, у tool поля tool/args/ok/ms/summary/result.
- **agent**: `agent_tools_list`, `agent_tool_call(name*, args)` — прямой вызов инструмента без LLM, `agent_prompt_dump(session)` (что реально уходит модели), `skills_*` (после X-03).
- **llm**: `llm_config`, `llm_models(provider)`, `llm_ping(provider, model)` (первый токен, итог), `llm_set(provider, model)`.
- **voice**: `voice_state`, `voice_speak(text*, provider, voice, output=file|speaker)` → путь, длительность, тайминги по этапам,
  `voice_stt_file(path|base64*, provider)`, `voice_inject(path|base64*, mode=wake|command)`, `tts_cache(stats|clear)`, `wake_state`, `wake_arm(on)`, `mic_level` (allowAudio).
- **ui**: `ui_hosts`, `ui_host_open(kind=headless|live)`, `ui_screens`, `ui_navigate(screen*)`, `ui_tree(host, maxDepth)`,
  `ui_screenshot(host, scale=0.5, format=jpeg|png|webp)`, `ui_tap(x,y | text | desc | path)`, `ui_long_press`, `ui_swipe(x1,y1,x2,y2,ms)`, `ui_type(text, selector)`,
  `ui_key(code)`, `ui_wait(selector*, timeoutMs)`, `ui_anim_probe(selector, property, durationMs)` (значения свойства по кадрам: для проверки анимаций),
  `ui_theme(dark|light|system)`.
- **perf**: `perf_snapshot`, `perf_turns(n)` (спаны голосовых ходов P-01), `perf_frames(durationMs)` (jank), `perf_prompt_size(session)`.
- **net**: `net_throttle(latencyMs, kbpsUp, kbpsDown, lossPct, durationMs)` — искусственные ограничения для всех исходящих запросов приложения (debug),
  `net_probe(hosts[])` (RTT, исходящая скорость, первый байт).
- **adb** (клиент внутри приложения): `adb_status`, `adb_pair(host,port,code)`, `adb_connect(host,port)`, `adb_shell(cmd, timeoutMs)`.
- **files**: `files_list(path)`, `files_read(path, max)`, `files_write(path, text)`, `shell_exec(cmd, timeoutMs)` — в песочнице приложения.
- **logcat**: `logcat_tail(n, level)` — только процесс приложения.

Ресурсы: `jarvis://state`, `jarvis://capabilities`, `jarvis://journal/tail`, `jarvis://settings`.

## 4. UiHost (M-04)

Интерфейс `UiHost`: `id`, `root()`, `post(Runnable)`, `sizePx()`, `screenshot(scale, format)`, `tree(maxDepth)`, `dispatchTouch(...)`, `dispatchKey(...)`, `navigate(screen)`.
- `LiveUiHost` — decor-вью видимой `MainActivity` (скриншот через `PixelCopy`, при невидимом окне — программная отрисовка).
- `HeadlessUiHost` — реализован в mcp4 без виртуального дисплея, см. раздел 10 (план ниже оставлен для истории: `Presentation` на частном дисплее не понадобился).
  Исходный план:
  (`DisplayManager.createVirtualDisplay(name, w, h, dpi, imageReader.getSurface(), OWN_CONTENT_ONLY | PRESENTATION)`); кадры из `ImageReader`,
  касания и клавиши через `decorView.dispatchTouchEvent/dispatchKeyEvent`. Нужно, чтобы `Shell` зависел от интерфейса `Host`, а не от `MainActivity`.
  **Спайк S1** проверяет на vivo: рисуется ли `RenderEffect`/AGSL на виртуальном дисплее, идут ли анимации, принимаются ли касания.
  Запасной вариант: рендер в `Bitmap` без окна (`View.draw`), без блюра и без анимаций; об этом честно сообщает `ui_hosts`.
- `ui_tree`: для каждого вью класс, `resName` (если есть id), текст/contentDescription, границы, видимость, enabled/clickable/focused/checked, путь.
  Новые вью должны получать читаемые id/`contentDescription` (правило для всех экранов, иначе их нельзя адресовать).

## 5. Реестр возможностей и правило «нет крючка, нет готовности» (M-06)

`app.jarvis.mcp.Capabilities` — список функций: `id`, название, инструменты MCP, файл сценария `tests/mcp/<id>.json`. `app_capabilities` возвращает покрытие.
JVM-тест `CapabilitiesTest` падает, если у функции нет инструмента или сценария. Стартовый список функций (старые): `chat`, `agent_loop`, `tools_fs`, `tools_shell`,
`tools_http`, `tools_download`, `tools_memory`, `tools_music`, `tools_apps`, `tools_notes`, `tools_adb`, `llm_providers`, `tts_edge`, `tts_elevenlabs`, `tts_gtts`,
`tts_cache`, `stt_chain`, `wake`, `voice_pipeline`, `ui_home`, `ui_agent`, `ui_files`, `ui_terminal`, `ui_voice`, `ui_journal`, `ui_settings_*`, `settings`, `secrets`,
`journal`, `db_recovery`, `service_lifecycle`. Каждая новая функция из BACKLOG добавляется в реестр в том же коммите, что и код.

## 6. Сценарии проверки (M-07)

`tests/mcp/<функция>.json`:
```json
{"name":"chat basic","needs":["chat_new","chat_send"],
 "steps":[{"call":"chat_new","args":{"title":"t"},"save":{"s":"$.session"}},
          {"call":"chat_send","args":{"session":"$s","text":"Ответь одним словом: ок","wait":true},
           "expect":[{"path":"$.reply","contains":"ок","ci":true},{"path":"$.timings.firstTokenMs","lt":8000}]}]}
```
`tools/mcp-smoke.py [--only id] [--host headless]` — прямой клиент HTTP (без opencode), прогоняет сценарии, пишет отчёт `build/device/mcp-report-<время>.md`
(ok/fail по шагам, тайминги, ссылки на скриншоты). Субагенты и я запускаем его после каждого изменения; зелёный отчёт + скриншот = статус `[v]` в BACKLOG.

## 7. Порядок реализации

1. Ядро (`core/mcp`): JSON-RPC, HTTP-сервер, токен, реестр, JVM-тесты на loopback. Инструменты app/journal/settings/db/chat/agent/llm/files.
2. Android-часть: `McpBridge`, `McpService`, `McpLaunchActivity`, манифест, настройка `mcp.enabled`; прокладка; запись в конфиг opencode; первый сквозной вызов с телефона.
3. Voice/perf/net/adb инструменты (после трассировки P-01); `LiveUiHost` + `ui_*`.
4. Спайк S1 → `HeadlessUiHost`.
5. Реестр возможностей, инвентарь старых функций, сценарии на каждую.

## 8. Реализация (mcp2): отличия от спецификации, как проверять

Состояние: JVM-тесты и сценарии зелёные, сборка есть; всё, что требует телефона, перечислено в `docs/DEVICE_CHECKLIST_mcp2.md`.

**Отличия от разделов 1–7**
1. **Прокладка работает без ADB** (`tools/jarvis-mcp-shim.py`): loopback телефона общий с Termux. Порядок: env `JV_MCP_TOKEN`/`JV_MCP_PORT` → `~/.jarvis-keys/mcp-<pkg>.json` (`{"token","port"}`, пишется прокладкой с правами 600) →
   adb (только если устройство видно) → перебор портов 8767..8780 + порт пакета (`8768 + hash(pkg) % 500`) по `/health` → понятная ошибка с инструкцией. На 401 источник токена перечитывается один раз.
   Env/`--jvm` токены в файл не пишутся. Тесты: `python3 tests/mcp/test_shim.py` (поддельный сервер, 49 проверок) и `python3 tests/mcp/shim_jvm.py` (настоящий Java-сервер, 12 проверок).
2. **Запуск без окна и без adb**: экспортируемый `mcp.McpStartReceiver` (action `app.jarvis.mcp.START`): `am broadcast -n <pkg>/app.jarvis.mcp.McpStartReceiver -a app.jarvis.mcp.START`. Прокладка `app_launch` пробует его, затем adb `am start` `McpLaunchActivity`, затем просит открыть приложение.
   Только что установленное или принудительно остановленное приложение не получает broadcast, пока его не откроют один раз (правило Android «stopped state»). Приложение целится в SDK 28, ограничение фонового FGS Android 12 на него не действует; `startForegroundService` из ресивера обёрнут в try/catch, сервер работает и без службы. Не проверено на устройстве.
3. **Токен**: хранится в `files/mcp/token` (внешний каталог приложения; переживает перезапуск и `install -r`). Если в секретах есть `mcp.token` (его кладёт `tools/make-provisioned.py` в personal-сборку и в `~/.jarvis-keys/mcp-app.jarvis.json`), берётся он. Страница «Мост для агента» (`ui/settings/McpPage`): переключатель, статус (порт, число вызовов, последний вызов), «Скопировать токен», «Сбросить токен», `mcp.allowAudio`, `mcp.allowDestructive`, `mcp.showTestSessions`, `mcp.mirrorToSharedStorage` (копия токена в Download/jarvis-mcp, по умолчанию выкл.).
   `settings_set` не может менять `mcp.allowAudio`, `mcp.allowDestructive`, `mcp.enabled` (агент не снимает собственные замки).
4. **Включение**: debug и personal — `mcp.enabled=true` по умолчанию (`make-provisioned.py` пишет его явно), release — выкл.
5. **Сессии чата**: пока нет `rt/Sessions` (ветка sess1), `chat_*` работают на `ChatStore` (id сессии = id диалога строкой, принимается и название). `chat_new` по умолчанию создаёт скрытую сессию `mcp-<название>`; без аргумента `session` используется автосессия `mcp-<id транспорта>`. `chat_clear_context` добавляет системную метку `— контекст очищен —`. Когда появится `Sessions`, нужно переписать только `ChatTools.createSession/clearContext/resolve`.
   Без `mcp.allowAudio` ответы в сессиях моста не озвучиваются (`AgentRunner.silentConvs`, минимальная правка), `source=voice` отклоняется.
6. **Дополнительные инструменты** сверх раздела 3: `chat_delete`, `trace_recent` (TraceStore), `perf_turns` (= трассы), `perf_prompt_size`, `app_capabilities`, `adb_*` (обёртки над инструментами агента `adb_*`), `files_*` с `area=app` (чтение служебных файлов приложения, `secrets.json` закрыт).
   Заглушки `NOT_IMPLEMENTED` (владелец функции заменяет их через `registry.put`, список в `ToolCatalog.STUBS`): `voice_state`, `voice_speak`, `voice_stt_file`, `voice_inject`, `tts_cache`, `wake_state`, `wake_arm`, `mic_level`, `perf_frames`, `net_throttle`, `net_probe`.
   `web_*`/`docs_search` доступны через `agent_tool_call`, как только агент их зарегистрирует.
7. **UI-хосты**: `ui_*` принимают `host=live|headless`, по умолчанию live. `LiveUiHost` — дерево видов (класс, res-id, tag, текст, desc, границы, состояния, путь), снимок (PixelCopy для видимого окна, программная отрисовка в фоне), касания и клавиши через `dispatchTouchEvent/dispatchKeyEvent`, `setText` для полей, `ui_wait`, покадровый `ui_anim_probe` (Choreographer). Локатор: `text` (подстрока, `=` — точное), `desc`, `res`, `tag`, `class`, `path`, `index`, либо объект `selector`.
   Окно регистрируют три строки в `MainActivity` (`LiveUi.resumed/gone`). `HeadlessUiHost` — заглушка `NOT_IMPLEMENTED: needs device spike S1`; план спайка — в `docs/DEVICE_CHECKLIST_mcp2.md`.
8. **Реестр возможностей**: `core/mcp/Capabilities` (функции → инструменты → `tests/mcp/<id>.json`), `core/mcp/ToolCatalog` (имена реализованных инструментов и заглушек). `CapabilitiesTest` падает, если у функции нет инструмента/сценария, если сценарий вызывает неизвестный инструмент, если инструмент не упомянут ни в одной функции и если каталог расходится с исходниками `app/mcp/*.java`. Функции без готового хука помечены `planned` с указанием, чего они ждут.

**Проверка без телефона**
- `bash tools/run-tests.sh 'app.jarvis.core.mcp.*'`
- `python3 tools/mcp-smoke.py --jvm` (сервер `McpSmokeServer` из тестов: настоящий транспорт, настоящие инструменты fs/shell/memory/http/download на временной песочнице, поддельные журнал/настройки/чат-эхо/дерево). Для закрытых сценариев: `--jvm-flags=--destructive` / `--jvm-flags=--audio`.
  Это проверяет протокол, ограничения, раннер и сценарии, **но не Android-реализацию** инструментов (db_query на SQLite, PixelCopy, ChatStore, AgentRunner): они проверяются только на устройстве (`python3 tools/mcp-smoke.py` без `--jvm`).
- Сценарии (формат — раздел 6) имеют поля `jvm:false`, `gate`, `needsLlm`, `needsUi`; пропуски в отчёте всегда названы, не скрыты.

## 9. Реализация mcp3: заглушек больше нет

Состояние: все инструменты каталога раздела 3 зарегистрированы; `NOT_IMPLEMENTED` остался только у `ui_*` с `host=headless` (спайк S1 не сделан, `OffscreenRenderer` на master нет). Списки реализованных и заглушек — `core/mcp/ToolCatalog.java`; функции — `core/mcp/Capabilities.java`.

**Как владельцу подключить** (3 строки):
1. Установить APK (сборка с MCP: debug или personal) и открыть приложение один раз.
2. Ничего больше не делать: мост поднимается сам, токен в `files/mcp/token`.
3. Агент запускает `python3 tools/jv-check.py` (отчёт OK/WARN/FAIL по ~15 инструментам) и дальше `python3 tools/jv.py <инструмент> '{json}'`.

**Новые и заменённые инструменты**

| Область | Инструменты | Где | Примечание |
|---|---|---|---|
| trace | `trace_recent(n)`, `perf_turns(n)`, `trace_current()`, `perf_summary(n=20)` | `core/mcp/TraceTools` | медианы и p90 по `to_sound`, `stt`, `llm_ttft`, `llm_total`, `tts_ttfa` отдельно для голоса и текста (`core/mcp/PerfSummary`) |
| perf | `perf_frames(durationMs)` | `mcp/PerfTools` | FrameMetrics видимого окна: кадры, jank %, p50/p90/p99, 5 самых долгих; без окна — `NOT_VISIBLE` |
| net | `net_probe(hosts[], timeoutMs, uploadUrl, uploadKb)`, `net_throttle(latencyMs, kbpsUp, kbpsDown, lossPct, durationMs, off)`, `net_state()` | `core/mcp/NetTools`, `core/net/NetLab`, `core/net/NetThrottle` | throttle в памяти, сам истекает (до 10 мин), сбрасывается при перезапуске; при выключенном — без накладных расходов |
| voice | `voice_state`, `voice_speak(text, provider, voice, speed, output)`, `voice_stt_file(path|base64, provider)`, `voice_inject(path|base64, mode, speak)`, `tts_cache(action)`, `wake_state`, `wake_arm(on)`, `mic_level` | `mcp/VoiceTools` | `voice_speak` пишет в `files/mcp/audio/`; микрофон и звук только при `mcp.allowAudio` |
| sessions | `sessions_list/search/create/select/rename/pin/archive/delete/clear_context` | `mcp/SessionTools` | поверх `rt/Sessions`; `sessions_delete` — destructive |
| ui | `perf_frames`, `NOT_VISIBLE` в `ui_*` | `mcp/UiTools`, `mcp/LiveUiHost` | сообщение: «откройте приложение на телефоне» |

Ограничения throttle: задержка добавляется один раз перед первым чтением каждого ответа (SSE, `Http.readText`, WebSocket, `net_probe`); пропускная способность — по байтам; потери — сброс соединения после `100000/lossPct` байт. Вызовы HTTP, которые читают поток напрямую (`c.getInputStream()` без `SseReader`/`Http.readText`), throttle не видят (их переводят на `Http.readText` / `SseReader` по мере необходимости).

**Примеры (jv.py)**
```
python3 tools/jv.py perf_summary '{"n":20}'
python3 tools/jv.py net_throttle '{"latencyMs":300,"kbpsDown":400,"durationMs":60000}'
python3 tools/jv.py net_probe '{"hosts":["openrouter.ai","api.groq.com"],"uploadUrl":"https://speed.cloudflare.com/__up","uploadKb":64}'
python3 tools/jv.py voice_speak '{"text":"Привет, это проверка связи."}'
python3 tools/jv.py voice_stt_file '{"base64":"<WAV 16 кГц моно в base64>"}'
python3 tools/jv.py sessions_create '{"title":"проба"}'
```

**Проверка**
- JVM: `bash tools/run-tests.sh` (CapabilitiesTest, NetLabTest, PerfSummaryTest, FrameStatsTest, PcmConvertTest, WavIoTest и остальные).
- Сценарии без телефона: `python3 tools/mcp-smoke.py --jvm` (сценарии с `needs`, которых нет в учебном сервере, пропускаются с причиной); `python3 tools/jv-check.py --jvm --no-llm`.
- На телефоне: `docs/DEVICE_CHECKLIST_mcp3.md` (пошагово, с ожидаемыми значениями).

## 10. Реализация mcp4: HeadlessUiHost (невидимый экран Jarvis)

Состояние: собран, проверен на ART этого телефона (`HeadlessUiCheck`) и JVM-тестами; на телефоне владельца не проверен (ADB выключен), см. `docs/DEVICE_CHECKLIST_mcp4.md`.

**Как работает**
- В процессе приложения создаётся настоящий `Shell` (тот же код, что в окне) без окна и без `Activity`. Контекст: `ContextThemeWrapper(app, Theme_Jarvis)`. `Host` — `HeadlessUiHost.HostImpl`: `persistsLastScreen()=false`, системные окна (выбор файла, браузер, настройки батареи, шаринг) не открываются, а сообщают тостом.
- Кадры: `OffscreenRenderer.Session` (новый аддитивный API: один GPU-приёмник на размер, `draw(view)` без чтения пикселей, `readback()` по запросу). Драйвер на `Choreographer` тикает 1,5 с после последнего вызова `ui_*` (дольше, если вызов ждёт: `ui_wait`, `ui_anim_probe`, `ui_swipe`), рисует каждый второй vsync (~30 кадров/с). Вне этого окна драйвер не работает.
- Касания: `dispatchTouchEvent` на корне Shell (DOWN/MOVE/UP). Клик и долгое нажатие выполняются `performClick`/`performLongClick` у ближайшего кликабельного предка (см. ограничения). BACK идёт в `Shell.onBack()`, остальные клавиши — `dispatchKeyEvent`.
- Изоляция: Shell не подключён к слушателям `Jarvis`, поэтому навигация голоса, чата и `app_open` не попадает в него; `ui.lastScreen` не пишется; двигается только `ui_navigate host=headless`. Данные (настройки, чат, журнал) общие с владельцем.
- Дерево и селекторы общие с живым окном: `mcp/UiWalk.java`. Границы считаются от корня по раскладке, потому что `getLocationInWindow` у невидимого дерева не работает.

**Инструменты**
- `ui_host_open {kind:"headless", width?, height?}`: открыть (по умолчанию 1080×2340 px). `ui_hosts` показывает `open`, `sizePx`, `screen`, `framesRendered`, `lastRenderMs`, `avgRenderMs`.
- `ui_screenshot host=live|headless`: live — PixelCopy у видимого окна, иначе GPU-отрисовка decor-вью, при сбое программная; headless — кадр Shell.
- `ui_tree`, `ui_tap`, `ui_long_press`, `ui_swipe`, `ui_type`, `ui_key`, `ui_wait`, `ui_navigate`, `ui_anim_probe` работают с `host=headless` так же, как с `live`.

**Отличия от плана (раздел 4)**
1. Виртуальный дисплей не нужен: Shell рисуется в offscreen-цель, `Presentation` и `VirtualDisplay` не используются.
2. Маршрутизация `app_open`/голоса на headless не сделана: headless не слушает `Jarvis`, двигается только `ui_navigate`.
3. `density` не поддержан: плотность берётся из приложения.
4. Вставки фиксированные: 24 dp сверху, 48 dp снизу (из окна живого приложения не читаются).

**Ограничения (вид не привязан к окну)**
- `View.post`/`postDelayed` не выполняются: тосты не гаснут сами, pressed-состояние не сбрасывается. Клики выполняет хост напрямую.
- Замеры на этом стенде (vivo, ART, `HeadlessUiCheck`): открытие 936 мс (с инициализацией UI); дерево домашнего экрана 138 мс (46 узлов); снимок 0,5 масштаба 390–1270 мс; за прогон 215 кадров, последний 21 мс, средний 39 мс на кадр; RSS 218 МБ до открытия, 241 МБ после (+23 МБ на GPU-цели), 290 МБ в конце (+73 МБ, в основном копии пикселей для снимков). Анимация: `scaleX` кнопки дока менялась в 5 из 38 кадров (0,998–1,0001).

**Проверка**
- `bash tools/art-apk.sh app.jarvis.dev.checks.HeadlessUiCheck $PWD/build/headless`: PNG в `build/headless/` и строки `[headless]`. Прогон проходит: главный экран, тап по элементу на главной открывает «Языковая модель (LLM)», в терминале `pwd` выполняется и выводит путь песочницы.
- Сценарий для приложения: `tests/mcp/ui_headless.json` (`python3 tools/mcp-smoke.py --only ui_headless`, на телефоне).
- Сборка: `JV_APP_ID=app.jarvis.mcp4 JV_APK_OUT=$PWD/build/outputs/mcp4.apk bash build.sh debug`.
