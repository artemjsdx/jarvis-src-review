#!/usr/bin/env python3
"""jv-check — одна команда «что с приложением сейчас»: ~15 безопасных (только чтение) инструментов моста, компактный отчёт OK/WARN/FAIL.

  python3 tools/jv-check.py                  # приложение на телефоне (то же, что tools/jv.py: --pkg, JV_PKG, токен из ~/.jarvis-keys)
  python3 tools/jv-check.py --pkg app.jarvis.mcp3
  python3 tools/jv-check.py --jvm            # поднимает McpSmokeServer (без телефона; часть инструментов будет SKIP)
  python3 tools/jv-check.py --no-llm         # не вызывать llm_ping (он тратит несколько токенов)
Выход: 0, если нет FAIL; 1 — если есть FAIL; 2 — если мост не отвечает.
"""
import importlib.util, json, os, subprocess, sys, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JV = os.path.join(ROOT, "tools", "jv.py")
HOSTS = ["openrouter.ai", "api.groq.com", "api.elevenlabs.io", "speech.platform.bing.com"]


def load_smoke():
    spec = importlib.util.spec_from_file_location("mcp_smoke", os.path.join(ROOT, "tools", "mcp-smoke.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class Bridge:
    """Calls tools through tools/jv.py (same discovery and token rules as the command line client)."""

    def __init__(self, opts):
        self.opts = opts
        self.names = None

    def _run(self, args):
        p = subprocess.run([sys.executable, JV] + self.opts + args, capture_output=True, text=True, timeout=200)
        return p.returncode, p.stdout.strip()

    def tools(self):
        if self.names is None:
            code, out = self._run(["tools"])
            if code != 0 and not out:
                raise RuntimeError("tools/list не ответил")
            self.names = {ln.split()[0] for ln in out.splitlines() if ln.strip()}
        return self.names

    def call(self, name, args=None):
        """Returns (ok, data_or_message)."""
        code, out = self._run(["call", name, json.dumps(args or {}, ensure_ascii=False)])
        try:
            data = json.loads(out)
        except ValueError:
            data = out
        return code == 0, data


def rows_add(rows, status, title, detail):
    rows.append((status, title, detail))


def check(bridge, rows, name, title, fn, args=None):
    if name not in bridge.tools():
        rows_add(rows, "SKIP", title, "нет инструмента %s в этом сервере" % name)
        return None
    t0 = time.time()
    ok, data = bridge.call(name, args)
    ms = int((time.time() - t0) * 1000)
    if not ok:
        rows_add(rows, "FAIL", title, "ошибка: %s" % (data if isinstance(data, str) else json.dumps(data, ensure_ascii=False))[:220])
        return None
    try:
        status, detail = fn(data)
    except Exception as e:  # a check must never hide the rest of the report
        status, detail = "WARN", "не разобран ответ: %s" % e
    rows_add(rows, status, title, "%s (%d мс)" % (detail, ms))
    return data


def main():
    args = sys.argv[1:]
    jvm = "--jvm" in args
    no_llm = "--no-llm" in args
    args = [a for a in args if a not in ("--jvm", "--no-llm")]
    proc = None
    if jvm:
        smoke = load_smoke()
        proc, port, token = smoke.start_jvm([])
        opts = ["--port", str(port), "--token", token]
    else:
        opts = args
    bridge = Bridge(opts)
    rows = []
    try:
        try:
            bridge.tools()
        except Exception as e:
            print("Jarvis не отвечает: %s" % e)
            print("Откройте приложение на телефоне (MCP включён в debug и personal) и повторите.")
            return 2

        check(bridge, rows, "app_info", "Приложение", lambda d: ("OK", "версия %s, %s" % (d.get("version", "?"), d.get("flavor", "?"))))
        check(bridge, rows, "app_health", "Здоровье процесса", lambda d: (
            ("WARN", "память/потоки: %s" % json.dumps(d.get("memory", {}), ensure_ascii=False)[:120]) if d.get("threads", 0) > 200 else ("OK", "потоков %s" % d.get("threads", "?"))))
        check(bridge, rows, "settings_list", "Настройки", lambda d: ("OK", "ключей: %s" % len(d.get("settings", d if isinstance(d, list) else []))), {"prefix": "tts."})
        check(bridge, rows, "llm_config", "Модель", lambda d: (
            ("OK", "%s / %s" % (d.get("provider", "?"), d.get("model", "?"))) if d.get("provider") else ("WARN", "провайдер не задан")))
        if not no_llm:
            check(bridge, rows, "llm_ping", "Пинг модели", lambda d: (
                ("OK", "первый токен %s мс" % d.get("firstTokenMs", "?")) if d.get("ok") else ("FAIL", "ответа нет: %s" % str(d.get("error", d))[:160])), {})
        check(bridge, rows, "voice_state", "Голос: микрофон и цепочки", lambda d: (
            ("WARN", "нет разрешения RECORD_AUDIO") if d.get("micPermission") is False else
            ("WARN", "микрофон: %s" % d.get("micProblem")) if d.get("micProblem") else
            ("OK", "TTS: %s; STT: %s" % (",".join(d.get("ttsChain", [])) or "—", ",".join(d.get("sttProviders", [])) or "—"))))
        check(bridge, rows, "tts_cache", "Кэш озвучки", lambda d: ("OK", "%s записей, %s байт" % (d.get("entries", "?"), d.get("bytes", "?"))), {"action": "stats"})
        check(bridge, rows, "trace_recent", "Последние ходы", lambda d: (
            ("OK", "трасс: %s" % d.get("count", 0)) if d.get("count", 0) else ("WARN", "ходов ещё не было")), {"n": 5})
        check(bridge, rows, "perf_summary", "Скорость ходов (медианы)", lambda d: (
            ("OK", "голос: до звука %s мс (n=%s), текст: %s мс" % (
                d.get("voice", {}).get("to_sound", {}).get("medianMs", "—"), d.get("voice", {}).get("to_sound", {}).get("n", 0),
                d.get("text", {}).get("to_sound", {}).get("medianMs", "—"))) if d.get("turns", 0) else ("WARN", "ходов ещё не было")), {"n": 20})
        check(bridge, rows, "journal_tail", "Журнал: ошибки", lambda d: (
            ("WARN", "ошибок в последних записях: %s" % len(d.get("rows", d.get("entries", [])))) if d.get("rows", d.get("entries")) else ("OK", "ошибок нет")),
            {"n": 50, "level": "ERROR"})
        check(bridge, rows, "db_schema", "База данных", lambda d: ("OK", "таблиц: %s" % len(d.get("tables", d if isinstance(d, list) else []))), {})
        check(bridge, rows, "sessions_list", "Сессии чата", lambda d: ("OK", "сессий: %s" % len(d.get("sessions", [])) if isinstance(d, dict) else "ok"), {})
        check(bridge, rows, "agent_tools_list", "Инструменты агента", lambda d: ("OK", "инструментов: %s" % d.get("count", "?")), {"schemas": False})
        check(bridge, rows, "app_capabilities", "Покрытие функций", lambda d: (
            (("WARN" if jvm else "FAIL"), "сломано функций: %s%s" % (d.get("broken"), " (учебный сервер: часть инструментов не зарегистрирована)" if jvm else "")) if d.get("broken") else
            ("OK", "покрыто %s, в плане %s" % (d.get("covered"), d.get("planned")))), {})
        check(bridge, rows, "net_probe", "Сеть: хосты голоса", lambda d: (
            ("FAIL", "ни один хост не отвечает") if d.get("okCount", 0) == 0 else
            ("WARN", "отвечают %s из %s" % (d.get("okCount"), d.get("count"))) if d.get("okCount", 0) < d.get("count", 0) else
            ("OK", "все %s отвечают, медиана TCP %s мс" % (d.get("count"), sorted(h.get("tcpMs", 0) for h in d.get("hosts", []))[len(d.get("hosts", [])) // 2] if d.get("hosts") else "—"))),
            {"hosts": HOSTS, "timeoutMs": 6000})
    finally:
        if proc is not None:
            try:
                proc.stdin.close()
            except Exception:
                pass
    width = max(len(t) for _, t, _ in rows) if rows else 10
    for status, title, detail in rows:
        print("[%-4s] %-*s  %s" % (status, width, title, detail))
    fails = sum(1 for r in rows if r[0] == "FAIL")
    warns = sum(1 for r in rows if r[0] == "WARN")
    print("Итог: FAIL %d, WARN %d, OK %d, SKIP %d" % (fails, warns, sum(1 for r in rows if r[0] == "OK"), sum(1 for r in rows if r[0] == "SKIP")))
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
