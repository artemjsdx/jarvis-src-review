package app.jarvis.rt;

import app.jarvis.core.mcp.client.McpHub;
import app.jarvis.core.mcp.client.ServerConfig;
import app.jarvis.core.telegram.TgReply;
import app.jarvis.core.telegram.TgRights;
import app.jarvis.core.util.Redactor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Client of the local Telegram login helper (tools/tg/login.py on 127.0.0.1:8766) and owner of the McpServers entry
 * that points at the Telegram MCP server. Blocking calls: run them off the UI thread. Secrets go in the request body
 * only; they are registered with {@link Redactor} first and never returned by the helper.
 */
public final class TgHelper {
    /** Id of the McpServers entry for the Telegram server. */
    public static final String SERVER_ID = "telegram";
    public static final String MCP_URL = "http://127.0.0.1:8765/mcp";
    private static final String BASE = "http://127.0.0.1:8766";
    static final String TOKEN_KEY = "tg.helper.token";
    private static final int CONNECT_MS = 3_000, READ_MS = 60_000, MAX_BYTES = 4 << 20;

    private final Jarvis j;

    public TgHelper(Jarvis j) { this.j = j; }

    public boolean hasToken() { return j.secrets.has(TOKEN_KEY); }

    /** Stores the helper token (from ~/tg-mcp/helper.token) encrypted with the other secrets; empty clears it. */
    public void saveToken(String raw) {
        String t = raw == null ? "" : raw.trim();
        if (t.isEmpty()) {
            j.secrets.remove(TOKEN_KEY);
            return;
        }
        Redactor.addSecret(t);
        j.secrets.set(TOKEN_KEY, t);
    }

    public TgReply health() { return call("GET", "/health", null, false); }

    public TgReply status() { return call("GET", "/status", null, true); }

    public TgReply accounts() { return call("GET", "/accounts", null, true); }

    /** Starts or stops the MCP server through the helper ("start" or "stop"). */
    public TgReply server(String action) { return call("POST", "/server", obj("action", action), true); }

    /** Step 1 of the code login: asks Telegram to send the code. */
    public TgReply startCode(int apiId, String apiHash, String phone, String label) {
        Redactor.addSecret(apiHash);
        return call("POST", "/start", obj("api_id", apiId, "api_hash", apiHash, "phone", phone, "label", label), true);
    }

    public TgReply submitCode(String code) {
        Redactor.addSecret(code);
        return call("POST", "/code", obj("code", code), true);
    }

    public TgReply submitPassword(String password) {
        Redactor.addSecret(password);
        return call("POST", "/password", obj("password", password), true);
    }

    public TgReply startQr(int apiId, String apiHash, String label) {
        Redactor.addSecret(apiHash);
        return call("POST", "/qr/start", obj("api_id", apiId, "api_hash", apiHash, "label", label), true);
    }

    public TgReply qrStatus() { return call("GET", "/qr/status", null, true); }

    /** PNG of the current QR link, or null when the helper has none or is unreachable. */
    public byte[] qrImage() {
        HttpURLConnection c = null;
        try {
            c = open("GET", "/qr/image", true);
            if (c.getResponseCode() != 200) return null;
            return readBytes(c.getInputStream());
        } catch (IOException e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * Imports an existing session. {@code extra} carries exactly one of string_session, session_json or session_file_b64
     * (the helper validates it against Telegram before storing).
     */
    public TgReply importAccount(String label, int apiId, String apiHash, JSONObject extra) {
        Redactor.addSecret(apiHash);
        String session = extra.optString("string_session", "");
        if (!session.isEmpty()) Redactor.addSecret(session);
        JSONObject body = obj("label", label, "api_id", apiId, "api_hash", apiHash);
        copy(extra, body);
        return call("POST", "/import", body, true);
    }

    public TgReply check(String label) { return call("POST", "/check", obj("label", label), true); }

    public TgReply remove(String label) { return call("POST", "/remove", obj("label", label), true); }

    public TgReply setRights(TgRights rights) { return call("POST", "/rights", obj("rights", rights.apiValue), true); }

    /** Creates or refreshes the McpServers entry; enabled exactly while the helper reports accounts. */
    public void syncMcpEntry(boolean hasAccounts) throws IOException {
        McpHub hub = j.mcp.hub();
        ServerConfig cfg = entry();
        cfg.enabled = hasAccounts;
        hub.save(cfg, null);
    }

    /** Current Telegram entry, or a fresh one with the owner's default: no confirmation before writes. */
    public ServerConfig entry() {
        ServerConfig cfg = j.mcp.hub().config(SERVER_ID);
        if (cfg == null) {
            cfg = new ServerConfig();
            cfg.confirmWrites = false;
        }
        cfg.id = SERVER_ID;
        cfg.name = "Telegram";
        cfg.url = MCP_URL;
        cfg.transport = ServerConfig.Transport.HTTP;
        return cfg;
    }

    public boolean hasEntry() { return j.mcp.hub().config(SERVER_ID) != null; }

    public void setConfirmWrites(boolean on) throws IOException {
        ServerConfig cfg = entry();
        cfg.confirmWrites = on;
        j.mcp.hub().save(cfg, null);
    }

    public void removeEntry() throws IOException { j.mcp.hub().remove(SERVER_ID); }

    private TgReply call(String method, String path, JSONObject body, boolean auth) {
        if (auth && !hasToken()) {
            return TgReply.fail("no_token", "Нет токена помощника. Вставьте его в этом экране.", "no_token");
        }
        HttpURLConnection c = null;
        try {
            c = open(method, path, auth);
            if (body != null) {
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setFixedLengthStreamingMode(data.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(data);
                }
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            return TgReply.parse(in == null ? "" : new String(readBytes(in), StandardCharsets.UTF_8));
        } catch (IOException e) {
            j.journal.w("system", "Помощник входа недоступен: " + e.getClass().getSimpleName());
            return TgReply.unavailable(e.getClass().getSimpleName());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private HttpURLConnection open(String method, String path, boolean auth) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(CONNECT_MS);
        c.setReadTimeout(READ_MS);
        c.setUseCaches(false);
        c.setRequestProperty("Accept", "application/json, image/png");
        if (auth) c.setRequestProperty("Authorization", "Bearer " + j.secrets.get(TOKEN_KEY));
        return c;
    }

    private static byte[] readBytes(InputStream in) throws IOException {
        if (in == null) return new byte[0];
        try (InputStream src = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = src.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) throw new IOException("ответ помощника слишком большой");
            }
            return out.toByteArray();
        }
    }

    private static JSONObject obj(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        } catch (JSONException ignored) {
            // keys are constants and values are plain strings or numbers
        }
        return o;
    }

    private static void copy(JSONObject from, JSONObject to) {
        java.util.Iterator<String> keys = from.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            try {
                to.put(k, from.get(k));
            } catch (JSONException ignored) {
                // value came from the same object, cannot be invalid
            }
        }
    }
}
