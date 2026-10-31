# NOTES — факты, решения, блокеры

Дата начала: 2026-10-27. Документ хранит только проверенное; непроверенное помечено.

## Окружение (проверено)

| | Хост сборки (где идёт разработка) | Целевой телефон (по ТЗ) |
|---|---|---|
| Модель | vivo I2407 | Realme Note 50 |
| Android | 16 (SDK 36), патч 2026-08-01 | не подключён, не проверен |
| ABI | только arm64-v8a (`abilist32` пуст) | не проверен |
| RAM | 11 ГБ + 12 ГБ swap, 8 ядер | 3 ГБ (со слов заказчика) |
| Свободно | 30 ГБ | 64 ГБ накопитель |

**Расхождение с ТЗ:** Termux, в котором идёт разработка, работает на vivo, а не на Realme. Realme по `adb devices` недоступен
(беспроводная отладка выключена, `service.adb.tls.port` пуст). Все замеры производительности из этого окружения
помечаются «стенд (vivo, 11 ГБ)» и **не** выдаются за замеры на Realme. Замеры на Realme: не выполнены, причина — устройство недоступно.

Тулчейн в Termux (рабочий): OpenJDK 21.0.12, aapt2 16.0.0.4, d8 37.0.0, apksigner 37.0.0, zipalign, ecj, android-tools (adb 35.0.2),
python3 (PIL, numpy, requests, websockets, lxml, bs4), ffmpeg, clang, cmake, node. Gradle и kotlinc не установлены и не нужны.

## Стек и сборка (решение)

- **Java 8 (source/target 8)**, нативный Android UI без AndroidX/WebView. Kotlin не используется: kotlinc в Termux нет,
  `javac` собирает быстрее, а d8 десугарит лямбды.
- Сборка без Gradle: `build.sh` = `aapt2 compile/link` → `javac` → `d8` → `mkapk.py` → `zipalign -p` → `apksigner`.
- `android.jar` из Termux — API 28 (2023), поэтому берётся платформа **API 36** из репозитория Google (`tools/setup.sh`).
- `javac` с `-bootclasspath android.jar` не умеет лямбды: в android.jar нет `LambdaMetafactory.metafactory`. Решение —
  собственный stub (`tools/stubs`), аналог `core-lambda-stubs.jar` из build-tools. Java 17+ с bootclasspath запрещён javac.
- **minSdk 26, targetSdk 28** (осознанный выбор). Причины: (1) при targetSdk ≥ 29 Android запрещает `exec()` файлов из каталога
  приложения, а терминал и опциональные пакеты инструментов должны запускать скачанные бинарники (так же устроен Termux);
  (2) нет принудительных ограничений FGS-типов/edge-to-edge из новых API. Следствие: `usesCleartextTraffic=true`
  (по умолчанию для target ≥ 28 http заблокирован). Проверка exec из каталога данных на целевых устройствах — в разделе проверок.
- Подпись: `~/.jarvis-keys/jarvis-release.p12` (alias `jarvis`, RSA-4096, до 2126). Пароль — `~/.jarvis-keys/keystore.pass` (600).
  Копия: `~/jarvis-backups/keys/` (отдельно от архивов исходников). SHA-256 сертификата:
  `15:26:9E:BC:C3:D5:79:F3:9B:4A:BF:37:39:09:6A:20:13:D7:37:C9:C4:DA:B0:67:B9:2D:0E:3D:3D:38:F9:CF`.
  **Потеря ключа = невозможность обновлять установленное приложение.** Все сборки (debug и release) подписаны одним ключом.

## Предыдущие наработки

В `~/` лежат handoff-пакеты «JarvisDanika» — это другой проект (серверный Python/Telegram-агент), не Android-приложение.
Не используются. Каталога `~/jarvis` до начала работы не существовало; ничего не перезаписано.

## Inception (основной LLM-провайдер)

**БЛОКЕР (проверен):** `api.inceptionlabs.ai`, `docs.inceptionlabs.ai`, `platform.inceptionlabs.ai` отвечают
`403 The Amazon CloudFront distribution is configured to block access from your country` с текущего IP (страна по Cloudflare trace: **BY**).
Маркетинговый сайт `www.inceptionlabs.ai` открывается. Следствия:
1. Реальный запрос к Inception из этой сети проверить нельзя. Ключа Inception в окружении тоже нет.
2. В приложении: понятная диагностика гео-блока, настраиваемый HTTP/SOCKS-прокси для LLM-запросов и настраиваемый base URL
   (Inception API OpenAI-совместим, поэтому любой совместимый шлюз подходит как запасной).
3. Протокол стриминга/инструментов проверяется на другом OpenAI-совместимом провайдере; Inception-специфика — по документации.

**Источник документации:** первоисточник недоступен из BY; прочитан снимок Wayback Machine от 2026-10-21 (`docs.inceptionlabs.ai`),
выжимка ниже. Каталог моделей в приложении всегда запрашивается живым `GET /v1/models`; имена моделей ниже — из документации, не угаданы.

Факты из документации (снимок 2026-10-08…21):
- Base: `https://api.inceptionlabs.ai/v1`, `POST /chat/completions`, `Authorization: Bearer <key>`. Ключ — в Platform Dashboard.
- Модели чата: `mercury-3` (упомянут в Quick Start/Prompt Guide, у OpenRouter 1M ctx), `mercury-2.5` (260K ctx, макс. вывод 65 536),
  `mercury-2` (128K, 50 000). `mercury-edit-2` — FIM/edit, не чат. `Mercury Voice` — только enterprise.
- Параметры: `messages`, `model`, `max_tokens` (1…50000, default 16384) / в примерах `max_completion_tokens`, `temperature` ∈ [0.5, 1]
  (вне диапазона сбрасывается на 0.75 с полем `warning`), `stop`, `tools`, `tool_choice` (auto|none|required), `stream`,
  `stream_options`, `response_format`, `reasoning_effort` ∈ {instant, low, medium, high}
  (умолчание: mercury-3 → low; mercury-2.5/2 → medium), `reasoning_summary`, `realtime` (меньше TTFT для голоса), `diffusing`
  (**не включать**: каждый чанк тогда содержит весь текст целиком).
- Tools: OpenAI-формат, параллельные tool calls поддерживаются, рекомендуется `strict: true` + `additionalProperties: false`.
  Результат — сообщение `role: "tool"` с `tool_call_id`.
- Стрим: SSE `data: {...}` по «блокам» (не по токену), завершение `data: [DONE]`.
- Рекомендации для голоса: системный промпт без markdown/списков и кратко; `temperature=0.75`; `max_completion_tokens ≥ 3000` для reasoning medium
  (reasoning-токены входят в лимит); `reasoning_effort=instant|low` + `realtime=true` для минимальной задержки.
- Ошибки: `{"error":{"message","type","param","code"}}`; 400 (`context_length_exceeded`), 401, 402 (биллинг), 404 (модель), 429 (backoff), 500, 503.
- Лимиты Free: 1000 запросов/мин, 1 000 000 входных и 100 000 выходных токенов/мин.

## Известные ограничения и непроверенное (актуальный список — в конце README)

- Беспроводной ADB на хосте выключен → автоматическая установка/скриншоты невозможны; установка через системный установщик (`termux-open`).
  Проверка приложения на устройстве идёт через встроенную самодиагностику с экспортом отчёта в `/sdcard/Jarvis/`.


## Проверенные факты по сервисам (стенд: Termux/vivo, egress BY, 2026-10-27/28)

**LLM.** Реально работают потоковые ответы, tool calls, отмена и повтор/адаптация параметров на: OpenRouter (`inception/mercury-3`:
первый фрагмент 0.9–1.5 с, агентский цикл из 3 шагов с файловым инструментом, shell и памятью — 3.8 с), Z.ai GLM (`glm-5.3-flash`,
`glm-5.2` через `https://api.z.ai/api/coding/paas/v4`, thinking выключается параметром `thinking:{type:disabled}`), ikhdev
(`glm-5.3`, `deepseek-v4.1-flash`; позже отвечал 503 «migration»). DeepSeek (`api.deepseek.com/v1`): ключ найден, но **баланс исчерпан**
(ошибка корректно классифицируется как «нет средств»). Inception напрямую: **403 CloudFront гео-блок для BY** (для любого ключа);
приложение автоматически переходит на OpenRouter (`inception/<модель>`), если он в `llm.fallbacks` и есть ключ.
Те же проверки выполнены на ART (Android HttpURLConnection): `bash tools/art-live.sh openrouter inception/mercury-3 all openrouter`.

**ElevenLabs free (ключ владельца, 10 000 симв./мес.)**: работают TTS всех моделей (`eleven_flash_v2_5`, `multilingual_v2`, `v3`, `v4`, `v4_turbo`),
PCM `pcm_24000/16000/22050`, `mp3_22050_32`, `mp3_44100_64`, `opus_48000_32` (первый байт 0.7–1.2 с на стенде); **`pcm_44100` — 403 (только Pro)**;
STT Scribe (`scribe_v1`, rus) 1.4 с на 3 с звука; speech-to-speech (`eleven_multilingual_sts_v2`) 200. **Недоступно на free (403):
Voice Design через API и клонирование (IVC)** — «только платный план». Клон на free есть через OpenRouter (ниже). Источник рекомендаций по
качеству озвучки (v3/v4: теги эмоций в `[...]` на английском, один на фразу; stability ≈ 0.5, style 0; числа словами) — заметка владельца.

**Edge Read Aloud**: работает (`ru-RU-SvetlanaNeural`, первое аудио 1.5–3.5 с), `ru-RU-DmitryNeural` — обрыв соединения. Токен `Sec-MS-GEC`
v2 — нестандартный SHA-256/CS64, порт из `edge-tts` с проверкой по эталонным векторам. **Google Translate TTS** работает (≤180 симв. за запрос).
**Silero demo Space** (Gradio 3.39) работает (predict 2.5–3.7 с + скачивание 3–15 с). **OpenRouter TTS** `fish-audio/s2.1-pro-free:free`
бесплатно (3.9 с), **клонирование по образцу** с `input_references` бесплатно (13 с; сходство на слух не оценивалось). **STT**: Google Speech v2
(ключ Chromium) работает; ElevenLabs Scribe, OpenRouter (`whisper-large-v3-turbo`, voxtral, qwen3-asr) работают; Groq Whisper: ключ жив, формат
AAC/ADTS отклоняется (нужен WAV/FLAC/MP3).

**Сеть стенда**: исходящая скорость ≈ 7 КБ/с (!): загрузка 100+ КБ аудио занимает 10–30 с. Все задержки STT со стенда
нерепрезентативны для телефона пользователя; замеры помечаются «стенд».

## Инструменты проверки без устройства (важно)

* **ART на телефоне**: `/system/bin/app_process` запускается из Termux с настоящим фреймворком Android (SDK 36). `tools/art-run.sh`, `art-apk.sh`,
  `art-live.sh`. Подводные камни, найденные опытом: необработанное исключение → тихий SIGKILL (смотреть `logcat -d`); dex/jar должны быть read-only
  (`chmod 0400`); `Typeface` не прединициализирован → шрифты строятся вручную из `/system/etc/fonts.xml`; vivo читает `Settings.Global` в `View.<init>` →
  подмена провайдера; `IWindowManager` подменён прокси. Всё это в `app/src/debug/java/app/jarvis/dev/PreviewHost.java`.
* **Предпросмотр экранов**: `bash tools/preview.sh [Scene]` рендерит настоящие экраны на настоящем рантайме (реальные SQLite/песочница/инструменты) в PNG.
* Не проверяемо здесь: реальная работа микрофона (у Termux нет RECORD_AUDIO), уведомления/фоновая служба, поведение на Realme Note 50,
  Bluetooth-кнопки, реальная беспроводная отладка.

## Решения, принятые автономно

* Единый `Jarvis`-рантайм + `Shell` (шапка/док/оверлеи) + экраны как независимые классы; UI без AndroidX и XML-раскладок.
* `ChatStore` — одна упорядоченная таблица `item` для UI и протокола модели; после аварии вызовы инструментов в статусе RUNNING закрываются как
  INTERRUPTED и **не повторяются**; модель получает «результат неизвестен».
* Секреты: AES-GCM в Android Keystore (`Secrets`), в журнал не попадают (`Redactor`); «личная» сборка (`build.sh personal`) встраивает ключи владельца
  из `~/.jarvis-keys/provisioned.json` (вне репозитория); публичная (`build.sh release`) — без ключей.
* Прямые команды (`DirectCommands`) выполняются без LLM: утренний сценарий, остановка/пауза/следующий трек, «что ты делаешь», открыть раздел; ответ всегда строится по
  реальному результату инструмента.
* Фолбэк LLM только до первого побочного эффекта (нет вызванных инструментов и не отдан ни один токен) — чтобы не дублировать действия.
