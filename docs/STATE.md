# STATE — как устроена работа и где мы остановились (читать первым)

Обновлено 2026-10-29 вечером (локальное время). Если ведущий агент (opencode, Sonnet 5.5) остановился, этого файла достаточно, чтобы продолжить.


## 0. Правила владельца (обязательные, не обсуждаются)
* Версия приложения ОДНА: 0.1.0. Никаких 0.2.0 и т. п.; `version.properties` не трогать (VERSION_CODE растёт незаметно, иначе Android не поставит поверх).
* Название приложения одно: «Jarvis». Файл для владельца один: `Jarvis.apk` (лежит в `/storage/emulated/0/Download/` и в `releases/`). Других вариантов, копий вида «Jarvis [dev0]», сборок с версией в имени владельцу не отдавать и на его телефон не ставить.
* Все тесты UI и моста — в CI-эмуляторе или через мост в единственном установленном `app.jarvis`; побочные пакеты `app.jarvis.<имя>` на телефон владельца не ставятся.

## 1. Кто что делает
* **Владелец** диктует замечания голосом, ездит на мобильном интернете, ADB к телефону сейчас ВЫКЛЮЧЕН. Всё сказанное записано в `docs/BACKLOG.md` (ID, приёмка, статус).
* **Ведущий агент** (opencode в Termux): сливает ветки в `master`, чинит конфликты, запускает сборку и тесты, ведёт очередь субагентов. Исходники: `~/jarvis` (ветка `master`).
* **Субагенты** (Claude Code через OpenRouter, по умолчанию `anthropic/claude-haiku-5.5`, для трудного `claude-sonnet-5.5`): каждый в своём git worktree `~/jarvis-wt/<имя>` на ветке `<имя>`, задача лежит в `docs/TASK_*.md`.
  Общие правила для них: `docs/AGENT_BRIEF.md` (разделы Wave 2 и Wave 3).

## 2. Очередь субагентов (supervisor)
Демон `tools/farm/supervisor.py` (ссылка в `.../agentfarm/supervisor.py`) держит очередь: не больше `concurrency` агентов, перезапуск с `--resume` после сбоев сети, сторож зависаний, пауза при балансе ниже резерва.
Команды (из каталога фермы `/data/data/com.termux/files/usr/var/lib/proot-distro/containers/ubuntu/rootfs/home/claude/agentfarm`):
```
python3 supervisor.py status                    # очередь, состояние, баланс
python3 supervisor.py start | stop              # демон (агенты при stop продолжают работать)
python3 supervisor.py set concurrency 3         # также reserve_usd, stall_min, max_attempts
python3 supervisor.py inject <имя> "<текст>"    # новые указания агенту (перезапуск с --resume)
python3 supervisor.py retry|kill <имя>; setmodel <имя> <модель>; prio <имя> <N>; pause | unpause
python3 ~/jarvis/tools/farm/add-task.py <имя> TASK_X.md [--model M] [--priority N]   # worktree + постановка в очередь
```
Состояние: `supervisor.json` (пишет только демон; CLI кладёт команды в `supervisor.inbox`), журнал `supervisor.log`, логи агентов `logs/wave3/*.log`.
Итоговый отчёт агента: поле `final` в `supervisor.json` (`python3 -c "import json;print(json.load(open('supervisor.json'))['tasks']['<имя>']['final'])"`).

## 3. Слияние веток
`cd ~/jarvis && git merge --no-edit <ветка>`. Горячие точки конфликтов: `rt/AgentRunner.java`, `rt/VoiceController.java`, `core/agent/DirectCommands.java`, `ui/screens/SettingsScreen.java`, `rt/Jarvis.java`, `core/agent/Prompts.java`, `docs/SPEED*.md`.
Конфликты почти всегда «обе стороны добавили»: оставить оба блока, проверить `bash tools/quickcheck.sh <файл>` (типы только своих файлов; R.java в `.cache/gen` может быть старым — тогда ошибка про `R.drawable.*` ложная, полная сборка её уберёт).
После слияния: `bash build.sh debug` и `HOME=/data/data/com.termux/files/home bash tools/run-tests.sh` (HOME нужен тесту настоящего adb). Известный шум: `AdbInteropTest` может падать без HOME; `AgentLoopTest.cancelledDuringToolStillPairsEveryToolCall` — гонка по времени.
Ловушки: `.gitignore` содержит `secrets.*` (файл `tests/mcp/secrets.json` разрешён исключением); `__pycache__` игнорируется; секреты в репозиторий не кладём (проверка: сканер в истории работы, образцы не печатать).

## 4. Проверка без телефона
* `bash tools/preview.sh [Сцена…]` рендерит экраны на ART этого же телефона (PNG в `JV_PREVIEW_OUT`, по умолчанию `build/preview`), смотреть картинки обязательно. Не печатать в stdout сцены много строк: `preview.sh` режет вывод `head -80`, избыток вывода обрывает прогон.
* `bash tools/art-apk.sh <MainClass>` и `tools/art-run.sh` — проверки на ART; JVM-тесты — `tools/run-tests.sh [класс…]`.
* MCP без приложения: `bash tools/mcp-jvm-server.sh <порт> <токен>` (держать stdin открытым, например через FIFO) и `python3 tools/jv.py …`; `python3 tools/mcp-smoke.py --jvm`.
* CI на GitHub-зеркале (APK, JVM-тесты, эмулятор Android 14 с MCP-мостом, живой LLM-тест при наличии `OPENROUTER_KEY`): `docs/CI.md` (как запускать и читать отчёт, ограничения программного GPU). Запуск эмулятора: `ci_run -f emulator=true` на ветке агента (`CI_BRANCH`).

## 5. Мост MCP (приоритет №1 владельца)
Сервер внутри приложения слушает `127.0.0.1:8767` (варианты `app.jarvis.<имя>` — другой порт), токен в `files/mcp/token` и в `~/.jarvis-keys/mcp-app.jarvis.json` (создаёт `tools/make-provisioned.py`; в личную сборку токен вшит).
Клиент без перезапуска opencode: `python3 tools/jv.py health | tools | <инструмент> '{json}'`. Спецификация: `docs/MCP.md`; проверка здоровья одной командой после установки: `python3 tools/jv-check.py` (если уже создан агентом mcp3).
Что нужно от владельца: поставить APK и один раз открыть приложение (после этого оно держит мост в фоне).
Эмулятор в CI — второй по важности стенд моста: `tools/ci/emulator-smoke.sh` гоняет `mcp-smoke` (сценарии с `requires[]` пропускаются с причиной, а не падают), засевает чат (`chat_seed`, только в сборке эмулятора с `mcp.allowDestructive`) и снимает экраны; `ui.glass` в эмуляторной сборке по умолчанию `off` (программный GPU). Отчёт: `docs/CI.md`.

## 6. Деньги
Ключ OpenRouter общий: им платят и агенты, и сам ведущий агент. Баланс смотреть `GET /api/v1/credits` (supervisor показывает его в `status`).
Главная статья расхода — не агенты-Haiku (центы), а длинная сессия ведущего агента на Sonnet: каждый ход перечитывает весь контекст, а при простое > 5 минут кэш промпта сгорает (ход стоит около доллара). Сжимайте контекст (новая сессия + этот файл), делайте меньше и крупнее ходов, не печатайте длинные выводы.

## 7. Сделано и слито в master
mcp2 (мост: каталог инструментов, оболочка stdio, реестр возможностей), speed1+speed2+speed3 (трассировка ходов, очистка текста для озвучки, пул Edge, подтверждения, look-ahead TTS, кэш промпта, Opus/hedge STT, локальное обращение Vosk), chat1 (контейнер чата, блок действий, вложения), sess1 (сессии в БД и API), log1 (журнал: место ошибки, сводка wake), tools1 (web_search/web_fetch/docs_search), kit1 (тема/сетка/motion/иконки), set1 (страницы настроек, голос, обращение, ADB), files1 (Файлы, Терминал, мост Termux), adb2 (SPAKE2 интероп), assets1 (звуки + картинки). Фаза 0 ведущего: курсор, пустые ответы, параллельные инструменты, «папочка» убран, Файлы (Space/вес/gravity), пузыри (gravity).

## 8. В работе / в очереди (на момент записи)
В работе: glass1 (стекло + капля дока), home1 (подсказки LLM), drawer1 (меню сессий), mcp3 (заглушки голос/сеть/трассы). В очереди: models1 (возможности моделей), ctx1 (скиллы + память v2), snd1 (звуки/микрофон), mcpc1 (MCP-клиент для Telegram и др.).
Ещё не заведено: Telegram-аккаунты (`tg1`: telegram-mcp `chigwell`, транспорт `MCP_TRANSPORT=http`, `127.0.0.1:8765/mcp`, сессия строкой Telethon, мульти-аккаунт `TELEGRAM_SESSION_STRING_<LABEL>`, режим прав `TELEGRAM_EXPOSED_TOOLS`), переименование приложения (alias), иконка/орб (выбор владельца), `HeadlessUiHost`, Realme.

## 9. Решения владельца и открытые вопросы
Решено: стиль «перламутровое стекло», звуки хрустальные (набор `crystal`), мост в Termux — да, подсказки LLM на главной — да, «принял, смотрю» для голоса — да, цель скорости ≤ 2 с / ≤ 3,5 с до первого звука, Telegram: любые права, платные поисковые ключи (Tavily/Exa/Brave) — опционально.
Открыто: какую иконку и шар выбрать (`/storage/emulated/0/Jarvis-design/`, три стиля), ставить ли личную сборку (внутри ключи) на телефон, кто из аккаунтов Telegram подключается.

## 10. CI на GitHub (с 2026-10-30) — сборка, тесты и настоящий Android без телефона владельца
* Зеркало: приватный репозиторий `artemjsdx/jarvis-android` (личный «старый» аккаунт владельца; токен в `~/.jarvis-keys/github.token`, имя в `github.repo`, права 600, в репозиторий не попадают; остальные токены из `/storage/emulated/0/Цхранилище/API/07 Инфраструктура/` — расходные аккаунты, не трогать).
* Помощники: `source tools/ci/gh.sh` → `ci_push [src:dst]` (перед отправкой сам запускает `tools/ci/scan-secrets.py` и отказывается пушить находки), `ci_run [-f emulator=true]`, `ci_watch [минут]`, `ci_logs`, `ci_fetch [тег]`, `ci_apk_to_phone` (кладёт подписанный APK в `/storage/emulated/0/Download/` + sha256). Для ветки агента: `export CI_BRANCH=<ветка>`, `ci_push HEAD:refs/heads/<ветка>`.
* Workflow `.github/workflows/ci.yml`: `apk` (3 APK за ~1,5 мин: мост arm64 для телефона, x86_64 для эмулятора, debug), `tests` (все JVM-тесты на чистом Linux, ~5 мин, включая интероп с настоящим adb), `emulator` (Android 14 x86_64 с KVM: ставит APK, открывает приложение, ходит по MCP-мосту: `jv-check`, смоук-сценарии, скриншоты всех экранов тремя способами, журнал, logcat). Запускается на `[emu]` в сообщении коммита или вручную (`ci_run -f emulator=true`). Весь прогон ~8 мин.
* Секреты репозитория: `JARVIS_KEYSTORE_B64`/`JARVIS_KEYSTORE_PASS` (ключ подписи: APK обновляет установленное приложение, отпечаток `15:26:9E:BC…F9:CF` проверяется), `JARVIS_BRIDGE_B64` (токен моста + нейтральные настройки, БЕЗ API-ключей), `OPENROUTER_KEY` (только для сквозного теста чата в эмуляторе).
* Результаты кладутся в РЕЛИЗЫ репозитория (квота хранилища артефактов Actions у аккаунта исчерпана, пересчёт раз в 6–12 ч): `ci-apk` (Jarvis.apk (для телефона), Jarvis-emulator.apk, Jarvis-debug.apk) и `ci-emulator` (emulator-report.zip); для веток агентов теги получают суффикс `-<ветка>`.
* Всё, что проверял эмулятор, — на Android 14 без микрофона, с программным рендером (блюр и AGSL медленные: кадры по 150–400 мс). У владельца Android 16 и настоящий GPU.
* Первая находка эмулятора (исправлена): при выключенных анимациях экраны накладывались друг на друга (`Shell.navigate` присваивал `current` после синхронного конца анимации выхода). Регрессионная сцена `ShellStackScenes`.
* Очередь агентов на момент записи: `ci1` (стабилизация смоука, посев чата для скриншотов, сквозной тест с LLM), `integ3` (слияние wire1 + search1 и проверка CI). Ветки агентов не пушить в `master` зеркала; слияние и пуш мастера делает ведущий.
