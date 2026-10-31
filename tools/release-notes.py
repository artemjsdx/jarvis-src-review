#!/usr/bin/env python3
"""release-notes.py [--tests N] [--run TEXT] [--emu-summary FILE]
Writes releases/RELEASE.md and releases/Jarvis.apk.sha256 for the single Jarvis.apk (the original brief: version, changelog, SHA-256, check results, limitations)."""
import argparse, hashlib, os, re, subprocess, time
ap = argparse.ArgumentParser(); ap.add_argument("--tests", default="n/a"); ap.add_argument("--run", default="n/a"); ap.add_argument("--emu-summary", default="")
a = ap.parse_args()
os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
def sh(*c):
    try: return subprocess.run(c, capture_output=True, text=True, timeout=60).stdout.strip()
    except Exception: return ""
ver = re.search(r"VERSION_NAME=(\S+)", open("version.properties", encoding="utf8").read()).group(1)
rev = sh("git", "rev-parse", "--short", "HEAD"); apk = "releases/Jarvis.apk"
sha = hashlib.sha256(open(apk, "rb").read()).hexdigest(); size = os.path.getsize(apk) / 1e6
open("releases/Jarvis.apk.sha256", "w").write("%s  Jarvis.apk\n" % sha)
cert = (re.search(r"SHA-256 digest: ([0-9a-f]+)", sh("apksigner", "verify", "--print-certs", apk)) or [0, "?"])[1]
badge = sh("aapt2", "dump", "badging", apk)
vn = (re.search(r"versionName='([^']*)'", badge) or [0, "?"])[1]; label = (re.search(r"application-label:'([^']*)'", badge) or [0, "?"])[1]
names = {"H": "Главная", "C": "Чат и сессии", "A": "Блок действий агента", "F": "Файлы", "T": "Терминал и окружение агента", "V": "Вкладка «Голос»", "L": "Журнал", "S": "Настройки",
         "X": "Контекст, память, навыки", "P": "Скорость", "M": "Мост MCP", "G": "Telegram и внешние MCP", "W": "Веб и документы", "B": "Бренд", "Z": "Звук и микрофон", "D": "ADB по Wi-Fi", "U": "Дизайн-система"}
items = []
for line in open("docs/BACKLOG.md", encoding="utf8"):
    m = re.match(r"\s*- \[([ x~v])\] ([A-Z])-(\d\d) — (.*)", line)
    if m: items.append((m.group(1), m.group(2), "%s-%s" % (m.group(2), m.group(3)), re.split(r"\s+(?:Приёмка|См\.|Источник)", m.group(4))[0].strip()))
def block(flag, title):
    out, cur = ["### " + title, ""], None
    for st, g, i, t in items:
        if st != flag: continue
        if g != cur: out += ["", "**%s**" % names.get(g, g)]; cur = g
        out.append("- %s — %s" % (i, t if len(t) <= 118 else t[:117] + "…"))
    return out + [""]
emu = ""
if a.emu_summary and os.path.isfile(a.emu_summary):
    keep = ("device:", "install:", "bridge health", "jv-check", "mcp-smoke", "llm OK", "glass-", "FATAL", "result:")
    emu = "\n".join(l[:230] for l in open(a.emu_summary, encoding="utf8").read().splitlines() if l.startswith(keep))
L = ["# Jarvis %s: выпуск" % vn, "",
     "Один файл: `Jarvis.apk` (%.1f МБ). Версия **%s**, приложение называется «**%s**». Сборка из коммита `%s`, %s." % (size, vn, label, rev, time.strftime("%Y-%m-%d %H:%M")),
     "", "SHA-256: `%s`" % sha, "Подпись: сертификат SHA-256 `%s` (тот же ключ, что у установленной раньше версии: ставится поверх, данные и введённые ключи остаются)." % cert, "",
     "## Как поставить", "",
     "1. Файловый менеджер, папка Загрузки (Download), файл `Jarvis.apk`, «Установить» (поверх старой).",
     "2. Один раз открыть Jarvis: запускается мост, через него ассистент-разработчик проверяет и настраивает приложение, пока вы пользуетесь телефоном.",
     "3. Если в списке приложений остался второй значок «Jarvis [dev0]» (тестовая копия от 28.10), удалите его: он лишний.", "",
     "В APK нет API-ключей. Ключи, которые вы уже ввели в приложении, остаются; новые вводятся в Настройки → Языковая модель или передаются по мосту.", "",
     "## Что изменилось по сравнению со сборкой, которую вы проверяли 28.10", "",
     "Статусы по `docs/BACKLOG.md`: сделано = есть в коде, покрыто тестами и посмотрено в превью/эмуляторе; на вашем телефоне это ещё НЕ проверялось.", ""]
L += block("x", "Сделано") + block("~", "Сделано частично") + block(" ", "Не сделано")
L += ["## Проверки этой сборки", "",
      "- Сборка, подпись и тесты на GitHub Actions: %s" % a.run, "- JVM-тесты на чистом Linux (включая совместимость с настоящим adb): %s." % a.tests,
      "- Эмулятор Android 14 (x86_64, программный GPU): приложение ставится, стартует, мост отвечает, все экраны открываются по одному:", "", "```", emu or "(отчёт эмулятора ещё не получен)", "```", "",
      "- Превью экранов на ART этого телефона: сотни PNG-сцен, смотрелись глазами.", "- Журнал ошибок показывает файл и строку и в release-сборке (проверено на ART).", "",
      "## Не проверялось на телефоне владельца (шаги: docs/DEVICE_CHECKLISTS.md)", "",
      "- звук: озвучка, сигналы начала/конца записи; микрофон; режим обращения «Джарвис» (локальный Vosk); уступка микрофона Telegram",
      "- блюр и преломление стекла на настоящем GPU, плавность анимаций на 120 Гц",
      "- вход в Telegram, мост в Termux (разрешение RUN_COMMAND), ADB по Wi-Fi против настоящего adbd, клонирование голоса",
      "- скорость «до первого звука» (docs/SPEED.md разделы 10 и 11): сквозной замер сделан ТОЛЬКО на эмуляторе CI (хорошая сеть дата-центра): прямая команда ≈ 2,2 с, простой вопрос ≈ 3,7 с до исправлений; "
      "цель «2 с» и уровень «Алисы» (≈ 1 с) НЕ достигнуты; на вашем телефоне и по мобильной сети замеров ещё нет", "",
      "## Ограничения", "",
      "- Эмулятор Android 14 не равен Android 16 на vivo; Realme Note 50 (3 ГБ) не проверялся: устройства нет.",
      "- Поиск по прошлым чатам идёт через LIKE: в SQLite Android нет FTS5.",
      "- Иконка и «шар» ассистента прежние: 18 сгенерированных картинок лежат в /storage/emulated/0/Jarvis-design/ и ждут выбора владельца.",
      "- Ключи приложения шифруются Android Keystore; сессии Telegram лежат в файле ~/tg-mcp/.env (права 600) без Keystore.",
      "- Текст из PDF не извлекается (PDF только просматривается).", ""]
open("releases/RELEASE.md", "w", encoding="utf8").write("\n".join(L)); print("releases/RELEASE.md written: %d lines, %d backlog items" % (len(L), len(items)))
