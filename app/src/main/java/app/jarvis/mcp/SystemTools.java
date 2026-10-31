package app.jarvis.mcp;

import app.jarvis.core.agent.ToolResult;
import app.jarvis.core.llm.ToolCall;
import app.jarvis.core.mcp.McpRegistry;
import app.jarvis.core.mcp.McpResult;
import app.jarvis.core.mcp.McpTools;
import app.jarvis.core.tools.ShellExec;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.util.J;
import app.jarvis.core.util.Redactor;
import app.jarvis.core.trace.TraceStore;
import app.jarvis.core.mcp.TraceTools;
import app.jarvis.rt.Jarvis;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

/** files_*, shell_exec, logcat_tail, perf_snapshot and the in-app adb_* wrappers. */
final class SystemTools {
    private SystemTools() { }

    static void register(McpRegistry r, final McpBridge b) {
        final Jarvis j = b.j;

        r.register(McpTools.tool("files_list", "Содержимое каталога песочницы (area=app — служебные файлы приложения, только чтение).",
            McpTools.Schema.obj().str("path", "Путь (по умолчанию корень песочницы)", false).oneOf("area", "sandbox (по умолчанию) или app", false, "sandbox", "app"), (a, c) -> {
                File dir = resolve(j, J.str(a, "area"), J.str(a, "path"));
                File[] fs = dir.listFiles();
                if (fs == null) return Tl.err("NOT_FOUND", "Это не каталог или он недоступен.", dir.getPath());
                java.util.Arrays.sort(fs, (x, y) -> x.getName().compareToIgnoreCase(y.getName()));
                JSONArray arr = new JSONArray();
                for (File f : fs) {
                    if (arr.length() >= 500) break;
                    JSONObject o = new JSONObject();
                    J.put(o, "name", f.getName());
                    J.put(o, "dir", f.isDirectory());
                    J.put(o, "size", f.length());
                    J.put(o, "modified", f.lastModified());
                    arr.put(o);
                }
                return McpResult.json(J.put(J.put(J.put(new JSONObject(), "path", display(j, dir)), "count", fs.length), "entries", arr));
            }).readOnly());

        r.register(McpTools.tool("files_read", "Прочитать файл как текст (или base64). Секреты в тексте маскируются.",
            McpTools.Schema.obj().str("path", "Путь", true).oneOf("area", "sandbox или app", false, "sandbox", "app").integer("max", "Максимум символов/байт (по умолчанию 20000, до 1000000)", false)
                .oneOf("encoding", "text (по умолчанию) или base64", false, "text", "base64").integer("tail", "Читать последние max (1) вместо первых", false), (a, c) -> {
                File f = resolve(j, J.str(a, "area"), J.str(a, "path"));
                if (!f.isFile()) return Tl.err("NOT_FOUND", "Файл не найден.", f.getPath());
                int max = Tl.clamp(Tl.intArg(a, "max", 20000), 1, 1_000_000);
                boolean tail = Tl.intArg(a, "tail", 0) == 1;
                byte[] data = readBytes(f, max, tail);
                JSONObject o = new JSONObject();
                J.put(o, "path", display(j, f));
                J.put(o, "size", f.length());
                J.put(o, "truncated", f.length() > data.length);
                if ("base64".equals(J.str(a, "encoding"))) J.put(o, "base64", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP));
                else J.put(o, "text", Redactor.redact(new String(data, StandardCharsets.UTF_8)));
                return McpResult.json(o);
            }).readOnly());

        r.register(McpTools.tool("files_write", "Записать файл в песочницу (создаёт каталоги, перезаписывает).",
            McpTools.Schema.obj().str("path", "Путь в песочнице", true).str("text", "Текст", false).str("base64", "Содержимое в base64 вместо text", false).bool("append", "Дописать в конец", false), (a, c) -> {
                File f = j.sandbox.resolve(J.str(a, "path"));
                if (j.sandbox.isCore(f)) return Tl.err("FORBIDDEN", "Корневые каталоги песочницы перезаписывать нельзя.", f.getPath());
                byte[] data = a.has("base64") ? android.util.Base64.decode(J.str(a, "base64"), android.util.Base64.DEFAULT) : J.str(a, "text").getBytes(StandardCharsets.UTF_8);
                File parent = f.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return Tl.err("IO", "Не удалось создать каталог.", parent.getPath());
                try (FileOutputStream os = new FileOutputStream(f, Tl.boolArg(a, "append", false))) { os.write(data); os.getFD().sync(); }
                return McpResult.json(J.put(J.put(new JSONObject(), "path", display(j, f)), "bytes", data.length));
            }));

        r.register(McpTools.tool("shell_exec", "Выполнить команду в оболочке песочницы приложения (тот же терминал, что у агента).",
            McpTools.Schema.obj().str("cmd", "Команда", true).str("cwd", "Рабочий каталог в песочнице", false).integer("timeoutMs", "Таймаут, мс (по умолчанию 30000, до 120000)", false), (a, c) -> {
                long to = Tl.clamp(Tl.intArg(a, "timeoutMs", 30000), 500, 120000);
                CancelToken ct = new CancelToken();
                ShellExec.Result res = j.shell.run(J.str(a, "cmd"), J.str(a, "cwd"), to, ct, null);
                JSONObject o = new JSONObject();
                J.put(o, "exitCode", res.exitCode);
                J.put(o, "ok", res.ok());
                J.put(o, "stdout", Redactor.redact(Tl.clip(res.stdout, 20000)));
                J.put(o, "stderr", Redactor.redact(Tl.clip(res.stderr, 8000)));
                J.put(o, "cwd", res.cwd);
                J.put(o, "ms", res.durationMs);
                J.put(o, "timedOut", res.timedOut);
                return McpResult.json(o);
            }).timeout(125_000));

        r.register(McpTools.tool("logcat_tail", "Последние строки logcat только процесса приложения.",
            McpTools.Schema.obj().integer("n", "Сколько строк (по умолчанию 200, до 2000)", false).oneOf("level", "Минимальный уровень", false, "V", "D", "I", "W", "E"), (a, c) -> {
                int n = Tl.clamp(Tl.intArg(a, "n", 200), 1, 2000);
                String lv = J.str(a, "level", "I");
                Process p = new ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=" + android.os.Process.myPid(), "-t", String.valueOf(n), "*:" + lv).redirectErrorStream(true).start();
                StringBuilder sb = new StringBuilder();
                try (BufferedReader rd = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = rd.readLine()) != null && sb.length() < 400_000) sb.append(line).append('\n');
                } finally { p.destroy(); }
                return McpResult.json(J.put(J.put(new JSONObject(), "pid", android.os.Process.myPid()), "log", Redactor.redact(sb.toString())));
            }).readOnly().timeout(20_000));

        r.register(McpTools.tool("perf_snapshot", "Снимок производительности: память, потоки, процессорное время, размер базы, состояние агента, последний ход.", (a, c) -> {
            JSONObject o = AppTools.health(b);
            J.put(o, "agentRunning", j.agent.isRunning());
            J.put(o, "journalRows", j.journal.count());
            File db = new File(j.home, "jarvis.db");
            J.put(o, "dbBytes", db.length());
            J.put(o, "mcpTools", b.registry.tools().size());
            JSONObject tr = TraceTools.traces(TraceStore.global(), 1);
            J.put(o, "lastTurn", tr.optJSONArray("traces") != null && tr.optJSONArray("traces").length() > 0 ? tr.optJSONArray("traces").opt(0) : JSONObject.NULL);
            return McpResult.json(o);
        }).readOnly());

        delegate(r, b, "adb_status", "Состояние Wi-Fi ADB внутри приложения.", McpTools.Schema.obj(), "adb_status", false);
        delegate(r, b, "adb_pair", "Сопряжение с беспроводной отладкой (host, pairing_port, code).",
            McpTools.Schema.obj().str("host", "IP-адрес устройства", true).integer("pairing_port", "Порт сопряжения", true).str("code", "6-значный код", true), "adb_pair", false);
        delegate(r, b, "adb_connect", "Подключиться к беспроводной отладке (host, port).", McpTools.Schema.obj().str("host", "IP-адрес", true).integer("port", "Порт подключения", true), "adb_connect", false);
        delegate(r, b, "adb_shell", "Выполнить shell-команду через ADB внутри приложения (command, timeout_seconds).",
            McpTools.Schema.obj().str("command", "Команда shell", true).integer("timeout_seconds", "Таймаут, по умолчанию 30", false), "adb_shell", false);
    }

    /** Runs the agent tool of the same purpose and returns its data as the MCP result. */
    private static void delegate(McpRegistry r, final McpBridge b, final String name, String desc, McpTools.Schema schema, final String agentTool, boolean readOnly) {
        McpTools.Def d = McpTools.tool(name, desc, schema, (a, c) -> {
            Jarvis j = b.j;
            if (j.tools.get(agentTool) == null) return Tl.err("NOT_AVAILABLE", "Модуль ADB ещё не подключён к приложению.", "tool " + agentTool + " not registered");
            String runId = "mcp" + Long.toString(System.currentTimeMillis(), 36);
            ToolResult res = j.tools.execute(new ToolCall("mcp-" + runId, agentTool, a.toString()), runId, new CancelToken(), j.journal, null);
            JSONObject o = new JSONObject();
            J.put(o, "ok", res.ok);
            J.put(o, "summary", res.summary);
            if (!res.ok) J.put(o, "error", res.error);
            J.put(o, "data", res.data == null ? new JSONObject() : res.data);
            return McpResult.json(o);
        }).timeout(120_000).sensitive("code");
        if (readOnly) d.readOnly();
        r.register(d);
    }

    private static File resolve(Jarvis j, String area, String path) throws IOException {
        if (!"app".equals(area)) return j.sandbox.resolve(path);
        File root = j.home.getCanonicalFile();
        String p = path == null ? "" : path.trim().replace('\\', '/');
        File f = new File(root, p.startsWith("/") ? p.substring(1) : p).getCanonicalFile();
        if (!(f.equals(root) || f.getPath().startsWith(root.getPath() + File.separator))) throw new IOException("Путь вне каталога приложения: " + path);
        String n = f.getName();
        if (n.equals("secrets.json")) throw new IOException("Файл с секретами недоступен");
        return f;
    }

    private static String display(Jarvis j, File f) {
        String p = j.sandbox.rel(f);
        return p.equals(f.getPath()) ? f.getPath().replace(j.home.getPath(), "<app>") : p;
    }

    private static byte[] readBytes(File f, int max, boolean tail) throws IOException {
        long len = f.length();
        int n = (int) Math.min(len, max);
        byte[] buf = new byte[n];
        try (FileInputStream in = new FileInputStream(f)) {
            if (tail && len > n) in.skip(len - n);
            int off = 0;
            while (off < n) { int k = in.read(buf, off, n - off); if (k < 0) break; off += k; }
            if (off < n) return java.util.Arrays.copyOf(buf, off);
        }
        return buf;
    }
}
