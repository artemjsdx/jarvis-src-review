package app.jarvis.core.tools;

import app.jarvis.core.agent.ToolRegistry;
import app.jarvis.core.agent.ToolResult;
import app.jarvis.core.llm.ToolSpec;
import app.jarvis.core.llm.ToolSpec.Param;
import app.jarvis.core.util.J;
import org.json.JSONObject;

public final class ShellTools {
    private ShellTools() { }

    public static void register(ToolRegistry r, final ShellSession session) {
        r.add(ToolSpec.of("shell_exec",
                "Команда в терминале Android shell (mksh, toybox, без root). Возвращает stdout, stderr, exit_code; ok=true только при exit_code 0.",
                Param.str("command", "Команда shell.", true),
                Param.str("cwd", "Рабочий каталог (по умолчанию текущий).", false),
                Param.integer("timeout_seconds", "Таймаут, сек (30 по умолчанию, макс. 600).", false)),
            "terminal", 11 * 60_000L, true, (a, ctx) -> {
                String cmd = J.str(a, "command").trim();
                if (cmd.isEmpty()) return ToolResult.fail("Пустая команда");
                long to = Math.max(1, Math.min(600, J.num(a, "timeout_seconds", 30))) * 1000L;
                ShellExec.Result x = session.run(cmd, J.str(a, "cwd"), to, ctx.cancel, new ShellExec.Sink() {
                    @Override public void chunk(String text, boolean stderr) { ctx.progress.progress(text); }
                });
                JSONObject o = new JSONObject();
                J.put(o, "exit_code", x.exitCode);
                J.put(o, "stdout", x.stdout);
                J.put(o, "stderr", x.stderr);
                J.put(o, "cwd", x.cwd);
                J.put(o, "duration_ms", x.durationMs);
                if (x.timedOut) J.put(o, "timed_out", true);
                if (x.truncated) J.put(o, "output_truncated", true);
                if (x.cancelled) return ToolResult.fail("Команда отменена", o);
                if (x.timedOut) return ToolResult.fail("Таймаут " + (to / 1000) + " с, процесс завершён принудительно", o);
                if (x.exitCode != 0) return ToolResult.fail("Команда завершилась с кодом " + x.exitCode, o);
                return ToolResult.ok(o, cmd.length() > 60 ? cmd.substring(0, 60) + "…" : cmd);
            });
    }
}
