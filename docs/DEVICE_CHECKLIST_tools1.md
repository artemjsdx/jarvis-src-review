# Проверка на устройстве — веб-инструменты (ветка tools1)

Не выполнено: телефон недоступен по ADB (владелец в поездке, Wave 3). Выполнять, когда adb снова увидит устройство.
Всё действие только в пакете `app.jarvis.tools1` (не `app.jarvis`, не `ai.jarvis.app`).

Предусловия
1. `export ANDROID_ADB_SERVER_PORT=5038; D=10.61.19.159:38569; adb devices` — устройство в списке. Если нет: подождать 30 с, повторить; не делать pair/connect.
2. Сборка: `cd ~/jarvis-wt/tools1 && JV_APP_ID=app.jarvis.tools1 JV_APK_OUT=$PWD/build/outputs/tools1.apk bash build.sh debug`
   (на момент отчёта собран и проверен на ART `build/outputs/tools1.apk`, rev f8205b7; перед проверкой пересобрать на HEAD).
3. Установка: `adb -s $D install -r build/outputs/tools1.apk`. Приложение не открывать вручную; дальше MCP-мост с `JV_PKG=app.jarvis.tools1`.
4. Ключи: в варианте `app.jarvis.tools1` свой Secrets, поэтому `key.openrouter` там пуст. Ввести ключ OpenRouter в
   настройках LLM этого варианта (или через MCP) — иначе ожидается путь через DuckDuckGo.

Сценарии (шаги → ожидаемый результат)
1. MCP `agent_tool_call` name=`web_search`, args `{"queries":["погода Гомель","asyncio event loop"],"limit":3}`.
   Ожидание: `ok:true`, `results` длиной 2, в каждом до 3 `hits` с `title/url/snippet/source`, `backend` = `openrouter`
   (если ключ введён) или `ddg`; `ms` ≤ 20000. Ссылки не повторяются между запросами.
2. Тот же вызов без ключа OpenRouter и без сети (отключить Wi-Fi/моб. данные на минуту, если владелец разрешит): ожидание
   `ok:false`, русская ошибка «Поиск не дал результатов: …», в журнале запись категории агента с техническим текстом.
3. `web_search` с 7 запросами → `ok:false`, текст «не больше 6».
4. `web_search` с `"recency":"decade"` → `ok:false`, текст про допустимые значения.
5. `web_search` с `"site":"https://www.wikipedia.org/x"` → в журнале/ответе запрос содержит `site:wikipedia.org`.
6. MCP `agent_tool_call` name=`web_fetch`, args `{"url":"https://ru.wikipedia.org/wiki/Гомель","max_chars":3000}`.
   Ожидание: `ok:true`, `title` = «Гомель — Википедия», `text` начинается с «# Гомель» или «**Го́мель**», `truncated:true`,
   `nextOffset` = 3000, `links` до 30 элементов, `totalChars` ≈ 200 000.
7. Тот же URL с `"offset":3000` → текст начинается сразу после конца предыдущего (проверить глазами стык), `nextOffset` = 6000.
8. `web_fetch` с `"format":"raw"` на https://docs.python.org/3/library/asyncio-eventloop.html → текст содержит `<html`.
9. `web_fetch` на PDF, например https://www.python.org/ftp/python/doc/3.12.0/python-3.12.0-docs-pdf-a4.pdf (только если
   не жалко трафика; иначе любой .pdf на сайте): `ok:true`, `binary:true`, `hint` содержит «download».
10. `web_fetch` на несуществующую страницу https://ru.wikipedia.org/wiki/zzz-нет-такой: `ok:false`, `error` = «HTTP 404».
11. `web_fetch` `url` = `http://` адрес, который переводит на `https://` и обратно (если найдётся): редирект https→http
    отклоняется с текстом «Переход с https на http запрещён». Если такой адрес не находится — пункт пропустить.
12. Документы: MCP `agent_tool_call` name=`download`, args `{"url":"https://docs.python.org/3/library/asyncio-eventloop.html","path":"downloads/asyncio.html"}`;
    затем `docs_search` query `event loop` → первый hit `downloads/asyncio.html`, `snippet` содержит «event loop».
13. Повторный `docs_search` после `download` того же файла другой версии (или правки файла) → индекс обновляется (новый фрагмент).
14. Голос/чат (владелец не в сети — пропустить, если нет согласия на расход): в чате «найди погоду в Гомеле» → модель вызывает
    `web_search`, ответ содержит ссылки из результата, без выдуманных URL. Проверить в журнале, что вызовы шли одновременно.
15. Отмена: запустить `web_search` с 6 запросами и остановить ход кнопкой отмены сразу. Ожидание: ход завершается ≤ 3 с,
    в журнале «Отменено», соединения не висят (через MCP `agent_tool_call name=web_search` с долгим запросом — не нужно, достаточно кнопки).
16. Регрессии: вкладки «Главная», «Чат», «Файлы», «Настройки» открываются без ошибок; `http_fetch` и `download` работают как раньше.
17. Уборка: `adb -s $D uninstall app.jarvis.tools1`.

Не проверено на устройстве (записано явно):
* пункты 1–17 выше;
* поведение сети на мобильных данных и на телефоне владельца (замеры в `docs/WEB_TOOLS.md` — с сети стенда);
* Brave/Tavily/Exa/SearXNG — нет ключей и инстанса.
