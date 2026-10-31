package app.jarvis.core.tools;

import app.jarvis.core.agent.ToolRegistry;
import app.jarvis.core.agent.ToolResult;
import app.jarvis.core.llm.ToolSpec;
import app.jarvis.core.llm.ToolSpec.Param;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.util.J;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;

/** Agent tool `termux_exec`: runs a command in the user's Termux through a {@link Transport}. */
public final class TermuxTool {
    private TermuxTool() { }

    /** What Termux reported back for one request. */
    public static final class Reply {
        public final int exitCode;
        public final String stdout, stderr, errmsg;
        public final int err;

        public Reply(int exitCode, String stdout, String stderr, int err, String errmsg) {
            this.exitCode = exitCode;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.err = err;
            this.errmsg = errmsg == null ? "" : errmsg;
        }
    }

    /** Android side of the bridge (rt/TermuxBridge). Pure Java callers never see Android types. */
    public interface Transport {
        /** Whether the Termux app is installed and the bridge permission is granted. */
        boolean ready();

        /**
         * Sends the wrapper line and blocks until Termux answers, {@code waitMs} passes, or {@code cancel} fires.
         * Returns null when there is no answer. Throws IOException with a user-safe Russian message when the request
         * could not be sent at all.
         */
        Reply exec(String id, String wrapper, long waitMs, CancelToken cancel) throws IOException;

        /** Best-effort kill of everything started under {@code id}. */
        void kill(String id);
    }

    public static final long GRACE_MS = 20_000;
    public static final int OUTPUT_MAX = 48 * 1024;

    public static void register(ToolRegistry r, final Transport transport) {
        r.add(ToolSpec.of("termux_exec",
                "Команда в Termux (полноценный Linux-терминал пользователя: python3, git, node, curl, apt и т. д.). Возвращает stdout, stderr, exit_code; "
                    + "ok=true только при exit_code 0. Доступен, только если Termux установлен и выдано разрешение моста.",
                Param.str("command", "Команда bash (можно несколько строк).", true),
                Param.str("cwd", "Рабочий каталог внутри Termux (по умолчанию домашний каталог Termux).", false),
                Param.str("stdin", "Данные на stdin команды (необязательно).", false),
                Param.integer("timeout_s", "Таймаут, сек (60 по умолчанию, макс. 600).", false)),
            "terminal", 11 * 60_000L, true, (a, ctx) -> {
                String cmd = J.str(a, "command").trim();
                if (cmd.isEmpty()) return ToolResult.fail("Пустая команда");
                if (!transport.ready()) return ToolResult.fail("Termux недоступен: установите Termux и выдайте приложению разрешение моста (Настройки → Песочница и инструменты)");
                String stdin = J.str(a, "stdin");
                if (cmd.getBytes(StandardCharsets.UTF_8).length + stdin.getBytes(StandardCharsets.UTF_8).length > TermuxCommand.MAX_COMMAND_BYTES) {
                    return ToolResult.fail("Команда или stdin больше 64 КБ");
                }
                int timeoutS = (int) Math.max(1, Math.min(600, J.num(a, "timeout_s", 60)));
                String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                String wrapper = TermuxCommand.wrapper(id, cmd, stdin, J.str(a, "cwd"), timeoutS);
                long t0 = System.nanoTime();
                Reply rep;
                try {
                    rep = transport.exec(id, wrapper, timeoutS * 1000L + GRACE_MS, ctx.cancel);
                } catch (IOException e) {
                    return ToolResult.fail(e.getMessage() == null ? "Termux не принял команду" : e.getMessage());
                }
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                if (rep == null) {
                    transport.kill(id);
                    JSONObject o = new JSONObject();
                    J.put(o, "duration_ms", ms);
                    if (ctx.cancel.isCancelled()) return ToolResult.fail("Команда отменена", o);
                    return ToolResult.fail("Termux не ответил за " + timeoutS + " с. Команда остановлена; проверьте, что в Termux включено allow-external-apps", o);
                }
                return toResult(rep, ms, cmd);
            });
    }

    static ToolResult toResult(Reply rep, long ms, String cmd) {
        JSONObject o = new JSONObject();
        boolean truncated = rep.stdout.length() > OUTPUT_MAX || rep.stderr.length() > OUTPUT_MAX;
        J.put(o, "exit_code", rep.exitCode);
        J.put(o, "stdout", head(rep.stdout));
        J.put(o, "stderr", head(rep.stderr));
        J.put(o, "duration_ms", ms);
        if (truncated) J.put(o, "output_truncated", true);
        if (rep.err != 0) return ToolResult.fail("Termux отказал: " + (rep.errmsg.isEmpty() ? "код " + rep.err : rep.errmsg), o);
        if (rep.exitCode == 124) {
            J.put(o, "timed_out", true);
            return ToolResult.fail("Таймаут: команда остановлена по времени", o);
        }
        if (rep.exitCode != 0) return ToolResult.fail("Команда завершилась с кодом " + rep.exitCode, o);
        return ToolResult.ok(o, cmd.length() > 60 ? cmd.substring(0, 60) + "…" : cmd);
    }

    static String head(String s) {
        if (s.length() <= OUTPUT_MAX) return s;
        int half = OUTPUT_MAX / 2;
        return s.substring(0, half) + "\n…[пропущено " + (s.length() - 2 * half) + " симв.]…\n" + s.substring(s.length() - half);
    }
}
