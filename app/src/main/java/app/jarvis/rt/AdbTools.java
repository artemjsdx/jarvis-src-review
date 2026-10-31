package app.jarvis.rt;

import app.jarvis.core.adb.AdbApi;
import app.jarvis.core.adb.AdbException;
import app.jarvis.core.adb.AdbShellResult;
import app.jarvis.core.adb.AdbStatus;
import app.jarvis.core.agent.ToolRegistry;
import app.jarvis.core.agent.ToolResult;
import app.jarvis.core.llm.ToolSpec;
import app.jarvis.core.llm.ToolSpec.Param;
import app.jarvis.core.util.J;
import app.jarvis.data.Settings;
import org.json.JSONObject;

/**
 * Agent tools for Wi-Fi ADB. This is a SEPARATE mechanism from the in-app terminal: the terminal is the app's own shell
 * (no root, app permissions), ADB talks to the phone's adbd as the 'shell' user after the user paired it in
 * Settings → Developer options → Wireless debugging. Pairing codes are never stored or logged.
 */
final class AdbTools {
    private AdbTools() { }

    static void register(final Jarvis j, final AdbApi adb) {
        ToolRegistry r = j.tools;
        r.add(ToolSpec.of("adb_status", "Состояние ADB по Wi-Fi: сопряжено ли устройство, есть ли соединение, адрес."), "adb", 10_000, false, (a, ctx) -> {
            AdbStatus s = adb.status();
            JSONObject o = new JSONObject();
            J.put(o, "state", s.state.name().toLowerCase(java.util.Locale.ROOT));
            J.put(o, "host", s.host);
            J.put(o, "port", s.port);
            J.put(o, "device", s.device);
            J.put(o, "last_error", s.lastError);
            return ToolResult.ok(o, s.state.name().toLowerCase(java.util.Locale.ROOT));
        });

        r.add(ToolSpec.of("adb_pair",
                "ADB: сопряжение по Wi-Fi (код сопряжения). Нужны адрес, порт сопряжения и 6-значный код; порт подключения — в adb_connect.",
                Param.str("host", "IP Wi-Fi адрес телефона, например 192.168.1.5 (не 127.0.0.1).", true),
                Param.integer("pairing_port", "Порт сопряжения (окно «Сопряжение с устройством»).", true),
                Param.str("code", "6-значный код сопряжения.", true)),
            "adb", 60_000, true, (a, ctx) -> {
                try {
                    adb.pair(J.str(a, "host").trim(), J.num(a, "pairing_port", 0), J.str(a, "code").trim(), ctx.cancel);
                } catch (AdbException e) {
                    return ToolResult.fail(e.userMessage, J.put(new JSONObject(), "kind", e.kind.name()));
                }
                j.settings.set(Settings.ADB_HOST, J.str(a, "host").trim());
                j.settings.set(Settings.ADB_PAIR_PORT, String.valueOf(J.num(a, "pairing_port", 0)));
                return ToolResult.ok(J.put(new JSONObject(), "paired", true), "сопряжено с " + J.str(a, "host"));
            });

        r.add(ToolSpec.of("adb_connect", "ADB: подключение по Wi-Fi. Нужны адрес и порт подключения (не порт сопряжения).",
                Param.str("host", "IP-адрес.", true), Param.integer("port", "Порт подключения.", true)),
            "adb", 45_000, true, (a, ctx) -> {
                try {
                    adb.connect(J.str(a, "host").trim(), J.num(a, "port", 0), ctx.cancel);
                } catch (AdbException e) {
                    return ToolResult.fail(e.userMessage, J.put(new JSONObject(), "kind", e.kind.name()));
                }
                j.settings.set(Settings.ADB_HOST, J.str(a, "host").trim());
                j.settings.set(Settings.ADB_PORT, String.valueOf(J.num(a, "port", 0)));
                AdbStatus s = adb.status();
                return ToolResult.ok(J.put(J.put(new JSONObject(), "connected", true), "device", s.device), "подключено: " + s.device);
            });

        r.add(ToolSpec.of("adb_shell", "Команда через ADB (пользователь shell) при активном подключении. Возвращает stdout, stderr, код выхода.",
                Param.str("command", "Команда shell.", true), Param.integer("timeout_seconds", "Таймаут, сек (30 по умолчанию, макс. 300).", false)),
            "adb", 310_000, true, (a, ctx) -> {
                long to = Math.max(1, Math.min(300, J.num(a, "timeout_seconds", 30))) * 1000L;
                AdbShellResult x;
                try {
                    x = adb.shell(J.str(a, "command"), to, ctx.cancel);
                } catch (AdbException e) {
                    return ToolResult.fail(e.userMessage, J.put(new JSONObject(), "kind", e.kind.name()));
                }
                JSONObject o = new JSONObject();
                J.put(o, "exit_code", x.exitCode);
                J.put(o, "stdout", x.stdout);
                J.put(o, "stderr", x.stderr);
                J.put(o, "duration_ms", x.durationMs);
                if (x.timedOut) return ToolResult.fail("Таймаут команды ADB", o);
                if (x.exitCode != 0) return ToolResult.fail("Команда завершилась с кодом " + x.exitCode, o);
                return ToolResult.ok(o, J.str(a, "command"));
            });

        r.add(ToolSpec.of("adb_disconnect", "Закрыть соединение ADB (сопряжение сохраняется)."), "adb", 15_000, true, (a, ctx) -> {
            adb.disconnect();
            return ToolResult.ok("ADB отключён");
        });
    }

    /** What may be stored in the chat/journal: pairing codes are masked. */
    static String redactArgs(String tool, String argsJson) {
        if (!"adb_pair".equals(tool)) return argsJson;
        JSONObject o = J.parseOrNull(argsJson);
        if (o == null) return argsJson.replaceAll("\\d{6}", "******");
        if (o.has("code")) J.put(o, "code", "******");
        return o.toString();
    }
}
