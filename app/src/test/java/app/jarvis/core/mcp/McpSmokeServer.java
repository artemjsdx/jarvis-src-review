package app.jarvis.core.mcp;

import app.jarvis.core.agent.Tool;
import app.jarvis.core.agent.ToolRegistry;
import app.jarvis.core.audio.WavIo;
import app.jarvis.core.agent.ToolResult;
import app.jarvis.core.llm.ToolCall;
import app.jarvis.core.llm.ToolSpec;
import app.jarvis.core.log.Journal;
import app.jarvis.core.trace.TraceStore;
import app.jarvis.core.trace.TurnTrace;


import app.jarvis.core.tools.DownloadTool;
import app.jarvis.core.tools.FsTools;
import app.jarvis.core.tools.HttpFetchTool;
import app.jarvis.core.tools.MemoryStore;
import app.jarvis.core.tools.Sandbox;
import app.jarvis.core.tools.ShellExec;
import app.jarvis.core.tools.ShellSession;
import app.jarvis.core.tools.ShellTools;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.util.J;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A complete MCP server on loopback with an in-memory fake runtime, used by `tools/mcp-smoke.py --jvm` and the shim tests.
 * It uses the REAL core transport (McpServer/McpDispatcher/McpRegistry gating and audit), the real core agent tools (fs, shell, memory,
 * http, download on a temp sandbox) and fakes only what needs Android: journal, settings, chat with an echo "model", a tiny UI tree.
 * So it proves protocol, gating, scenarios and the runner; it does NOT prove the Android tool implementations (see docs/MCP.md section 8).
 * Args: [port [token]] [--audio] [--destructive] (gates default to off). Prints "READY {json}" on stdout and exits when stdin closes.
 */
public final class McpSmokeServer {
    private static final String PNG_1X1 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==";

    private final McpRegistry reg = new McpRegistry();
    private final List<JSONObject> journal = new CopyOnWriteArrayList<>();
    private final AtomicLong jid = new AtomicLong();
    private final Map<String, String> settings = new TreeMap<>();
    private final Map<String, String> secrets = new TreeMap<>();
    private final Map<Long, String> convTitle = new LinkedHashMap<>();
    private final Map<Long, List<JSONObject>> convItems = new LinkedHashMap<>();
    private final AtomicLong convN = new AtomicLong(), seqN = new AtomicLong();
    private volatile boolean allowAudio, allowDestructive;
    private volatile String screen = "home";
    private final long started = System.currentTimeMillis();
    private McpServer server;
    private int calls;

    public static void main(String[] args) throws Exception {
        List<String> pos = new ArrayList<>();
        McpSmokeServer s = new McpSmokeServer();
        for (String a : args) {
            if (a.equals("--audio")) s.allowAudio = true;
            else if (a.equals("--destructive")) s.allowDestructive = true;
            else pos.add(a);
        }
        int port = pos.size() > 0 ? Integer.parseInt(pos.get(0)) : 0;
        String token = pos.size() > 1 ? pos.get(1) : null;
        String tok = token != null ? token : s.randomToken();
        int p = s.start(port, tok);
        System.out.println("READY " + new JSONObject().put("port", p).put("token", tok));
        System.out.flush();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        while (in.readLine() != null) { /* keep running until stdin closes */ }
        s.close();
    }

    String randomToken() {
        byte[] b = new byte[32];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    public int start(int port, final String token) throws Exception {
        File tmp = Files.createTempDirectory("mcp-smoke").toFile();
        Sandbox sb = new Sandbox(new File(tmp, "sandbox"));
        ShellSession shell = new ShellSession(sb);
        final ToolRegistry agentTools = new ToolRegistry();
        FsTools.register(agentTools, sb);
        ShellTools.register(agentTools, shell);
        HttpFetchTool.register(agentTools);
        DownloadTool.register(agentTools, sb, Journal.NOP, new String[]{""});
        MemoryStore.registerTools(agentTools, new MemoryStore(new File(tmp, "memory.json")));
        registerTools(sb, shell, agentTools);
        McpGate gate = new McpGate() {
            @Override public boolean allowAudio() { return allowAudio; }
            @Override public boolean allowDestructive() { return allowDestructive; }
        };
        McpDispatcher d = new McpDispatcher(reg, gate, new McpAudit() {
            @Override public void record(Entry e) {
                calls++;
                log(e.ok ? "DEBUG" : "WARN", "mcp", "MCP " + e.tool + (e.ok ? " ok" : " ошибка " + e.errorCode));
            }
        }, "jarvis-smoke", "0-jvm", "JVM smoke server with a fake runtime");
        server = new McpServer(d, new McpServer.TokenSource() { @Override public String token() { return token; } },
            new McpServer.HealthSource() { @Override public JSONObject health() { return new JSONObject().put("app", "app.jarvis").put("version", "0-jvm").put("flavor", "jvm-smoke").put("pid", 0); } });
        return server.start(port, port == 0 ? 1 : 20);
    }

    public void close() { if (server != null) server.close(); }

    private void log(String level, String cat, String msg) {
        JSONObject o = new JSONObject().put("id", jid.incrementAndGet()).put("ts", System.currentTimeMillis()).put("level", level).put("cat", cat).put("msg", msg);
        journal.add(o);
    }

    private static McpResult err(String code, String ru) { return McpResult.error(code, ru, ""); }

    /** Two finished turns (voice and text) with fixed marks, so perf_summary has real numbers to report. */
    private static void seedTraces(TraceStore ts) {
        for (String src : new String[] {"voice", "text"}) {
            TurnTrace t = ts.create(src);
            ts.publish(t);
            t.mark(TurnTrace.SUBMIT);
            t.mark(TurnTrace.LLM_REQUEST);
            t.mark(TurnTrace.LLM_FIRST_TOKEN);
            t.mark(TurnTrace.LLM_FIRST_TEXT);
            t.mark(TurnTrace.LLM_DONE);
            ts.agentDone(t);
        }
    }

    private void registerTools(final Sandbox sb, final ShellSession shell, final ToolRegistry agentTools) {
        TraceStore traces = new TraceStore(null, null, TurnTrace.SYSTEM);
        seedTraces(traces);
        TraceTools.register(reg, new java.util.function.Supplier<TraceStore>() { @Override public TraceStore get() { return traces; } });
        NetTools.register(reg);
        reg.register(McpTools.tool("app_info", "info", (a, c) -> McpResult.json(new JSONObject().put("package", "app.jarvis").put("flavor", "jvm-smoke").put("uptimeMs", System.currentTimeMillis() - started)
            .put("mcp", new JSONObject().put("tools", reg.tools().size()).put("allowAudio", allowAudio).put("allowDestructive", allowDestructive).put("calls", calls)))).readOnly());
        reg.register(McpTools.tool("app_health", "health", (a, c) -> McpResult.json(new JSONObject().put("memory", new JSONObject().put("javaUsedMb", 1)).put("threads", Thread.activeCount()))).readOnly());
        reg.register(McpTools.tool("perf_snapshot", "perf", (a, c) -> McpResult.json(new JSONObject().put("memory", new JSONObject().put("javaUsedMb", 1)).put("agentRunning", false))).readOnly());
        reg.register(McpTools.tool("app_capabilities", "caps", (a, c) -> McpResult.json(Capabilities.report(new java.util.HashSet<>(reg.names())))).readOnly());

        reg.register(McpTools.tool("journal_tail", "journal", McpTools.Schema.obj().integer("n", "n", false).str("text", "t", false).str("cat", "c", false).integer("afterId", "a", false).integer("since", "s", false), (a, c) -> {
            int n = a.optInt("n", 50);
            JSONArray out = new JSONArray();
            for (JSONObject o : journal) {
                if (o.getLong("id") <= a.optLong("afterId", 0) || o.getLong("ts") < a.optLong("since", 0)) continue;
                if (!a.optString("cat").isEmpty() && !a.optString("cat").equals(o.getString("cat"))) continue;
                if (!a.optString("text").isEmpty() && !o.getString("msg").contains(a.optString("text"))) continue;
                out.put(o);
            }
            JSONArray cut = new JSONArray();
            for (int i = Math.max(0, out.length() - n); i < out.length(); i++) cut.put(out.get(i));
            return McpResult.json(new JSONObject().put("count", cut.length()).put("rows", cut));
        }).readOnly());
        reg.register(McpTools.tool("journal_mark", "mark", McpTools.Schema.obj().str("label", "l", false), (a, c) -> {
            log("INFO", "mcp", "MARK " + a.optString("label", "mark"));
            return McpResult.json(new JSONObject().put("id", jid.get()).put("ts", System.currentTimeMillis()).put("label", a.optString("label", "mark")));
        }));
        reg.register(McpTools.tool("journal_clear", "clear", (a, c) -> { int n = journal.size(); journal.clear(); return McpResult.json(new JSONObject().put("deleted", n)); }).destructive());

        reg.register(McpTools.tool("settings_list", "list", McpTools.Schema.obj().str("prefix", "p", false), (a, c) -> {
            JSONObject v = new JSONObject();
            for (Map.Entry<String, String> e : settings.entrySet()) if (e.getKey().startsWith(a.optString("prefix", ""))) v.put(e.getKey(), e.getValue());
            return McpResult.json(new JSONObject().put("count", v.length()).put("settings", v));
        }).readOnly());
        reg.register(McpTools.tool("settings_get", "get", McpTools.Schema.obj().str("key", "k", true), (a, c) ->
            McpResult.json(new JSONObject().put("key", a.getString("key")).put("set", settings.containsKey(a.getString("key"))).put("value", settings.getOrDefault(a.getString("key"), "")))).readOnly());
        reg.register(McpTools.tool("settings_set", "set", McpTools.Schema.obj().str("key", "k", true).str("value", "v", true), (a, c) -> {
            String k = a.getString("key");
            if (k.equals("mcp.allowAudio") || k.equals("mcp.allowDestructive") || k.equals("mcp.enabled")) return err("FORBIDDEN", "Этот флаг безопасности меняется только вручную.");
            String old = settings.getOrDefault(k, "");
            settings.put(k, a.getString("value"));
            return McpResult.json(new JSONObject().put("key", k).put("old", old).put("value", a.getString("value")));
        }));
        reg.register(McpTools.tool("settings_reset", "reset", McpTools.Schema.obj().str("key", "k", true), (a, c) ->
            McpResult.json(new JSONObject().put("key", a.getString("key")).put("removed", settings.remove(a.getString("key")) != null))).destructive());
        reg.register(McpTools.tool("secrets_list", "list", (a, c) -> {
            JSONArray arr = new JSONArray();
            for (Map.Entry<String, String> e : secrets.entrySet()) arr.put(new JSONObject().put("name", e.getKey()).put("last4", e.getValue().length() >= 12 ? e.getValue().substring(e.getValue().length() - 4) : "").put("length", e.getValue().length()));
            return McpResult.json(new JSONObject().put("count", arr.length()).put("secrets", arr));
        }).readOnly());
        reg.register(McpTools.tool("secrets_set", "set", McpTools.Schema.obj().str("name", "n", true).str("value", "v", true), (a, c) -> {
            secrets.put(a.getString("name"), a.getString("value"));
            return McpResult.json(new JSONObject().put("name", a.getString("name")).put("stored", true));
        }).sensitive("value"));
        reg.register(McpTools.tool("secrets_get", "get", McpTools.Schema.obj().str("name", "n", true), (a, c) ->
            McpResult.json(new JSONObject().put("name", a.getString("name")).put("found", secrets.containsKey(a.getString("name"))).put("value", secrets.getOrDefault(a.getString("name"), "")))).destructive());

        // chat with an echo model
        reg.register(McpTools.tool("chat_new", "new", McpTools.Schema.obj().str("title", "t", false).bool("visible", "v", false), (a, c) -> {
            String t = a.optString("title", "тест");
            if (!a.optBoolean("visible", false) && !t.startsWith("mcp-")) t = "mcp-" + t;
            long id = convN.incrementAndGet();
            convTitle.put(id, t);
            convItems.put(id, new CopyOnWriteArrayList<JSONObject>());
            return McpResult.json(new JSONObject().put("session", String.valueOf(id)).put("title", t));
        }));
        reg.register(McpTools.tool("chat_sessions", "sessions", McpTools.Schema.obj().bool("includeTest", "i", false), (a, c) -> {
            JSONArray arr = new JSONArray();
            for (Map.Entry<Long, String> e : convTitle.entrySet())
                arr.put(new JSONObject().put("session", String.valueOf(e.getKey())).put("title", e.getValue()).put("items", convItems.get(e.getKey()).size()).put("test", e.getValue().startsWith("mcp-")));
            return McpResult.json(new JSONObject().put("count", arr.length()).put("sessions", arr));
        }).readOnly());
        reg.register(McpTools.tool("chat_send", "send", McpTools.Schema.obj().str("text", "t", true).str("session", "s", false).bool("wait", "w", false).integer("timeoutMs", "m", false).oneOf("source", "s", false, "text", "voice"), (a, c) -> {
            if ("voice".equals(a.optString("source")) && !allowAudio) return err("FORBIDDEN", "Источник voice озвучивает ответ: включите mcp.allowAudio.");
            long id = session(a.optString("session"), c);
            if (id < 0) return err("NOT_FOUND", "Сессия не найдена.");
            List<JSONObject> items = convItems.get(id);
            long before = items.isEmpty() ? 0 : items.get(items.size() - 1).getLong("seq");
            String text = a.getString("text");
            items.add(new JSONObject().put("seq", seqN.incrementAndGet()).put("kind", "user").put("text", text).put("ts", System.currentTimeMillis()));
            String reply = text.toLowerCase().contains("ок") ? "ок" : "Вы сказали: " + text;
            Thread.sleep(30);
            items.add(new JSONObject().put("seq", seqN.incrementAndGet()).put("kind", "assistant").put("text", reply).put("ts", System.currentTimeMillis()).put("model", "echo"));
            JSONArray ev = new JSONArray();
            for (JSONObject it : items) if (it.getLong("seq") > before) ev.put(it);
            return McpResult.json(new JSONObject().put("session", String.valueOf(id)).put("reply", reply).put("toolCalls", new JSONArray()).put("events", ev)
                .put("timings", new JSONObject().put("totalMs", 30).put("firstTextMs", 10)).put("run", new JSONObject().put("status", "DONE").put("model", "echo")));
        }));
        reg.register(McpTools.tool("chat_history", "history", McpTools.Schema.obj().str("session", "s", false).integer("n", "n", false), (a, c) -> {
            long id = session(a.optString("session"), c);
            if (id < 0) return err("NOT_FOUND", "Сессия не найдена.");
            List<JSONObject> items = convItems.get(id);
            JSONArray arr = new JSONArray();
            for (int i = Math.max(0, items.size() - a.optInt("n", 30)); i < items.size(); i++) arr.put(items.get(i));
            return McpResult.json(new JSONObject().put("session", String.valueOf(id)).put("items", arr));
        }).readOnly());
        reg.register(McpTools.tool("chat_clear_context", "clear", McpTools.Schema.obj().str("session", "s", false), (a, c) -> {
            long id = session(a.optString("session"), c);
            if (id < 0) return err("NOT_FOUND", "Сессия не найдена.");
            convItems.get(id).add(new JSONObject().put("seq", seqN.incrementAndGet()).put("kind", "system").put("text", "— контекст очищен —").put("ts", System.currentTimeMillis()));
            return McpResult.json(new JSONObject().put("session", String.valueOf(id)).put("cleared", true));
        }));
        reg.register(McpTools.tool("chat_cancel", "cancel", (a, c) -> McpResult.json(new JSONObject().put("wasRunning", false))));
        reg.register(McpTools.tool("chat_wait_idle", "idle", McpTools.Schema.obj().integer("timeoutMs", "t", false), (a, c) -> McpResult.json(new JSONObject().put("idle", true).put("waitedMs", 0))).readOnly());
        reg.register(McpTools.tool("chat_state", "state", (a, c) -> McpResult.json(new JSONObject().put("agentRunning", false).put("state", "IDLE").put("provider", "echo").put("model", "echo"))).readOnly());
        reg.register(McpTools.tool("chat_delete", "delete", McpTools.Schema.obj().str("session", "s", true), (a, c) -> {
            long id = session(a.getString("session"), c);
            if (id < 0) return err("NOT_FOUND", "Сессия не найдена.");
            if (!convTitle.get(id).startsWith("mcp-") && !allowDestructive) return err("FORBIDDEN", "Обычные сессии удаляются только при mcp.allowDestructive.");
            convTitle.remove(id);
            convItems.remove(id);
            return McpResult.json(new JSONObject().put("deleted", true).put("session", String.valueOf(id)));
        }));

        // real core agent tools
        reg.register(McpTools.tool("agent_tools_list", "tools", McpTools.Schema.obj().bool("schemas", "s", false), (a, c) -> {
            JSONArray arr = new JSONArray();
            for (ToolSpec s : agentTools.specs()) arr.put(new JSONObject().put("name", s.name).put("description", s.description).put("category", agentTools.get(s.name).category()));
            return McpResult.json(new JSONObject().put("count", arr.length()).put("tools", arr));
        }).readOnly());
        reg.register(McpTools.tool("agent_tool_call", "call", McpTools.Schema.obj().str("name", "n", true).object("args", "a", false), (a, c) -> {
            Tool t = agentTools.get(a.getString("name"));
            if (t == null) return err("NOT_FOUND", "Нет инструмента агента «" + a.getString("name") + "».");
            JSONObject args = a.optJSONObject("args");
            ToolResult r = agentTools.execute(new ToolCall("smoke", a.getString("name"), args == null ? "{}" : args.toString()), "smoke", new CancelToken(), Journal.NOP, null);
            return McpResult.json(new JSONObject().put("ok", r.ok).put("summary", r.summary).put("error", r.error == null ? "" : r.error).put("data", r.data == null ? new JSONObject() : r.data));
        }).timeout(60_000));
        reg.register(McpTools.tool("files_list", "ls", McpTools.Schema.obj().str("path", "p", false), (a, c) -> {
            File dir = sb.resolve(a.optString("path"));
            JSONArray arr = new JSONArray();
            File[] fs = dir.listFiles();
            if (fs == null) return err("NOT_FOUND", "Это не каталог.");
            for (File f : fs) arr.put(new JSONObject().put("name", f.getName()).put("dir", f.isDirectory()).put("size", f.length()));
            return McpResult.json(new JSONObject().put("count", fs.length).put("entries", arr));
        }).readOnly());
        reg.register(McpTools.tool("files_read", "read", McpTools.Schema.obj().str("path", "p", true), (a, c) -> {
            File f = sb.resolve(a.getString("path"));
            if (!f.isFile()) return err("NOT_FOUND", "Файл не найден.");
            return McpResult.json(new JSONObject().put("size", f.length()).put("text", new String(Files.readAllBytes(f.toPath()), "UTF-8")));
        }).readOnly());
        reg.register(McpTools.tool("files_write", "write", McpTools.Schema.obj().str("path", "p", true).str("text", "t", false), (a, c) -> {
            File f = sb.resolve(a.getString("path"));
            f.getParentFile().mkdirs();
            byte[] data = a.optString("text").getBytes("UTF-8");
            Files.write(f.toPath(), data);
            return McpResult.json(new JSONObject().put("path", a.getString("path")).put("bytes", data.length));
        }));
        reg.register(McpTools.tool("shell_exec", "sh", McpTools.Schema.obj().str("cmd", "c", true).integer("timeoutMs", "t", false), (a, c) -> {
            ShellExec.Result r = shell.run(a.getString("cmd"), "", Math.min(60000, a.optInt("timeoutMs", 20000)), new CancelToken(), null);
            return McpResult.json(new JSONObject().put("exitCode", r.exitCode).put("ok", r.ok()).put("stdout", r.stdout).put("stderr", r.stderr).put("ms", r.durationMs));
        }).timeout(65_000));

        // tiny UI
        reg.register(McpTools.tool("ui_hosts", "hosts", (a, c) -> McpResult.json(new JSONObject().put("hosts", new JSONArray()
            .put(new JSONObject().put("id", "live").put("available", true).put("visible", true))
            .put(new JSONObject().put("id", "headless").put("available", false).put("reason", "NOT_IMPLEMENTED: needs device spike S1"))))).readOnly());
        reg.register(McpTools.tool("ui_screens", "screens", (a, c) -> McpResult.json(new JSONObject().put("screens", new JSONArray(java.util.Arrays.asList("home", "agent", "files", "terminal", "studio", "log", "settings"))))).readOnly());
        reg.register(McpTools.tool("ui_navigate", "nav", McpTools.Schema.obj().str("screen", "s", true).oneOf("host", "h", false, "live", "headless"), (a, c) -> {
            if ("headless".equals(a.optString("host"))) return McpResult.error("NOT_IMPLEMENTED", "Невидимое окно ещё не готово.", "spike S1");
            screen = a.getString("screen");
            return McpResult.json(new JSONObject().put("screen", screen).put("requested", true));
        }));
        reg.register(McpTools.tool("ui_tree", "tree", McpTools.Schema.obj().oneOf("host", "h", false, "live", "headless").integer("maxDepth", "d", false), (a, c) -> {
            if ("headless".equals(a.optString("host"))) return McpResult.error("NOT_IMPLEMENTED", "Невидимое окно ещё не готово.", "spike S1");
            JSONObject title = new JSONObject().put("path", "0.0").put("class", "TextView").put("text", screen).put("visible", true);
            JSONObject root = new JSONObject().put("path", "0").put("class", "DecorView").put("visible", true).put("children", new JSONArray().put(title));
            return McpResult.json(new JSONObject().put("host", "live").put("nodes", 2).put("root", root));
        }).readOnly());
        reg.register(McpTools.tool("ui_screenshot", "shot", McpTools.Schema.obj().number("scale", "s", false).oneOf("host", "h", false, "live", "headless"), (a, c) -> {
            if ("headless".equals(a.optString("host"))) return McpResult.error("NOT_IMPLEMENTED", "Невидимое окно ещё не готово.", "spike S1");
            return McpResult.image(PNG_1X1, "image/png").addText("{\"bytes\":70}");
        }).readOnly());

        // voice tools: fakes that keep the contract, argument checks and audio gates; the real ones run on the phone (fake:true marks them)
        final boolean[] wakeArmed = {false};
        reg.register(McpTools.tool("voice_state", "voice state (fake)", (a, c) -> McpResult.json(new JSONObject().put("fake", true).put("state", "IDLE")
            .put("wakeEnabled", wakeArmed[0]).put("micActive", wakeArmed[0]).put("allowAudio", allowAudio).put("ttsChain", new JSONArray(java.util.Arrays.asList("fake"))))).readOnly());
        reg.register(McpTools.tool("voice_speak", "speak (fake)", McpTools.Schema.obj().str("text", "t", true).str("provider", "p", false).str("voice", "v", false)
            .number("speed", "s", false).oneOf("output", "o", false, "file", "speaker"), (a, c) -> {
            String text = a.optString("text", "").trim();
            if (text.isEmpty()) return err("INVALID_ARGS", "Пустой текст.");
            if (a.has("speed") && (a.optDouble("speed") < 0.5 || a.optDouble("speed") > 2)) return err("INVALID_ARGS", "Скорость от 0,5 до 2.");
            if ("speaker".equals(a.optString("output", "file"))) {
                if (!allowAudio) return err("FORBIDDEN", "Воспроизведение в динамик: включите mcp.allowAudio.");
                return McpResult.json(new JSONObject().put("fake", true).put("output", "speaker").put("played", false));
            }
            File out = new File(sb.tmp, "voice-fake.wav");
            byte[] pcm = new byte[6400];
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) { fo.write(WavIo.header(16000, 1, pcm.length)); fo.write(pcm); }
            return McpResult.json(new JSONObject().put("fake", true).put("output", "file").put("provider", a.optString("provider", "fake")).put("bytes", pcm.length)
                .put("durationMs", 200).put("firstByteMs", 1).put("totalMs", 2).put("cacheHit", false).put("path", out.getPath()).put("cleanedText", text));
        }).timeout(120_000));
        reg.register(McpTools.tool("voice_stt_file", "transcribe (fake)", McpTools.Schema.obj().str("path", "p", false).str("base64", "b", false).str("provider", "p", false), (a, c) -> {
            boolean hasPath = !a.optString("path", "").isEmpty();
            if (hasPath == !a.optString("base64", "").isEmpty()) return err("INVALID_ARGS", "Нужен один из параметров: path или base64.");
            JSONObject o = new JSONObject().put("fake", true).put("text", "фейк").put("provider", "fake").put("attempts", 1);
            if (!hasPath) return McpResult.json(o);
            File f;
            try { f = sb.resolve(a.getString("path")); } catch (java.io.IOException e) { return err("FORBIDDEN", "Файл вне песочницы."); }
            if (!f.isFile()) return err("NOT_FOUND", "Файл не найден.");
            return McpResult.json(o.put("bytesSent", f.length()));
        }).timeout(125_000));
        reg.register(McpTools.tool("voice_inject", "inject (fake)", McpTools.Schema.obj().str("path", "p", false).str("base64", "b", false)
            .oneOf("mode", "m", false, "wake", "command").bool("speak", "s", false), (a, c) -> {
            boolean hasPath = !a.optString("path", "").isEmpty();
            if (hasPath == !a.optString("base64", "").isEmpty()) return err("INVALID_ARGS", "Нужен один из параметров: path или base64.");
            if (a.optBoolean("speak", false) && !allowAudio) return err("FORBIDDEN", "Ответ в динамик: включите mcp.allowAudio.");
            if (hasPath) {
                File f;
                try { f = sb.resolve(a.getString("path")); } catch (java.io.IOException e) { return err("FORBIDDEN", "Файл вне песочницы."); }
                if (!f.isFile()) return err("NOT_FOUND", "Файл не найден.");
            }
            return McpResult.json(new JSONObject().put("fake", true).put("transcript", "фейк").put("reply", "фейковый ответ").put("speak", a.optBoolean("speak", false))
                .put("turn", new JSONObject().put("source", "voice").put("complete", true)));
        }).timeout(120_000));
        reg.register(McpTools.tool("tts_cache", "tts cache (fake)", McpTools.Schema.obj().oneOf("action", "a", false, "stats", "list", "clear"), (a, c) -> {
            String action = a.optString("action", "stats");
            if (!action.equals("stats") && !action.equals("list") && !action.equals("clear")) return err("INVALID_ARGS", "action: stats, list или clear.");
            JSONObject o = new JSONObject().put("fake", true).put("action", action).put("bytes", 0).put("entries", 0).put("maxBytes", 64 * 1024 * 1024);
            if (action.equals("list")) o.put("list", new JSONArray());
            return McpResult.json(o);
        }));
        reg.register(McpTools.tool("wake_state", "wake state (fake)", (a, c) -> McpResult.json(new JSONObject().put("fake", true).put("enabled", wakeArmed[0])
            .put("micActive", wakeArmed[0]).put("phrases", new JSONArray(java.util.Arrays.asList("Джарвис"))))).readOnly());
        reg.register(McpTools.tool("wake_arm", "wake on/off (fake)", McpTools.Schema.obj().bool("on", "on", true), (a, c) -> {
            if (!a.has("on")) return err("INVALID_ARGS", "Укажите on: true или false.");
            boolean on = a.optBoolean("on", false);
            if (on && !allowAudio) return err("FORBIDDEN", "Обращение открывает микрофон: включите mcp.allowAudio.");
            wakeArmed[0] = on;
            return McpResult.json(new JSONObject().put("fake", true).put("enabled", on));
        }));
        reg.register(McpTools.tool("mic_level", "mic level (fake)", (a, c) -> {
            if (!wakeArmed[0]) return err("NOT_AVAILABLE", "Микрофон выключен: включите обращение (wake_arm on).");
            return McpResult.json(new JSONObject().put("fake", true).put("level", 0.0).put("micActive", true));
        }).readOnly().audio());
    }

    private long session(String arg, McpCall call) {
        if (arg == null || arg.isEmpty()) {
            String title = "mcp-" + (call.session.isEmpty() ? "default" : call.session.substring(0, Math.min(8, call.session.length())));
            for (Map.Entry<Long, String> e : convTitle.entrySet()) if (e.getValue().equals(title)) return e.getKey();
            long id = convN.incrementAndGet();
            convTitle.put(id, title);
            convItems.put(id, new CopyOnWriteArrayList<JSONObject>());
            return id;
        }
        for (Map.Entry<Long, String> e : convTitle.entrySet()) if (String.valueOf(e.getKey()).equals(arg) || e.getValue().equals(arg)) return e.getKey();
        return -1;
    }
}
