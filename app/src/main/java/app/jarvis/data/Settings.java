package app.jarvis.data;

import android.os.Handler;
import android.os.Looper;
import app.jarvis.core.util.J;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONObject;

/** Plain (non-secret) settings: one small JSON file, written atomically; listeners are notified on the main thread. */
public final class Settings {
    public interface Listener { void onSettingChanged(String key); }

    // ---- keys -----------------------------------------------------------------------------------------------------
    public static final String LLM_PROVIDER = "llm.provider", LLM_MODEL = "llm.model", LLM_BASE_URL = "llm.baseUrl", LLM_PROXY = "llm.proxy",
        LLM_TEMPERATURE = "llm.temperature", LLM_MAX_TOKENS = "llm.maxTokens", LLM_REASONING = "llm.reasoning", LLM_MAX_STEPS = "llm.maxSteps",
        LLM_CONTEXT_CHARS = "llm.contextChars", LLM_FALLBACKS = "llm.fallbacks", LLM_CONTEXT_AUTO = "llm.contextAuto", LLM_RECENT = "llm.recent",
        TTS_AUTO = "tts.auto", TTS_CHAIN = "tts.chain", TTS_SPEED = "tts.speed", TTS_CACHE_MB = "tts.cacheMb", TTS_PRONUNCIATION = "tts.pronunciation",
        STT_CHAIN = "stt.chain", STT_LANGUAGE = "stt.language", STT_FORMAT = "stt.format", STT_HEDGE_MS = "stt.hedgeMs",
        WAKE_ENABLED = "wake.enabled", WAKE_PHRASES = "wake.phrases", WAKE_STT = "wake.stt", WAKE_AEC = "wake.aec", WAKE_ENGINE = "wake.engine",
        MUSIC_FAVORITE = "music.favorite", MUSIC_MORNING_GREETING = "music.morningGreeting", SANDBOX_EXTERNAL = "sandbox.external",
        UI_THEME = "ui.theme", UI_MOTION = "ui.motion", UI_ONBOARDED = "ui.onboarded",
        ADB_HOST = "adb.host", ADB_PAIR_PORT = "adb.pairPort", ADB_PORT = "adb.port",
        SVC_BOOT_NOTIFY = "svc.bootNotify", ASSISTANT_NAME = "assistant.name", USER_CALL = "user.call", LOG_MAX_ROWS = "log.maxRows",
        MCP_ENABLED = "mcp.enabled", MCP_ALLOW_AUDIO = "mcp.allowAudio", MCP_ALLOW_DESTRUCTIVE = "mcp.allowDestructive", MCP_SHOW_TEST_SESSIONS = "mcp.showTestSessions", MCP_MIRROR = "mcp.mirrorToSharedStorage";

    public static final String WEB_SEARCH_BACKEND = "web.search.backend", WEB_SEARCH_MAX = "web.search.maxResults",
        WEB_FETCH_MAX = "web.fetch.maxChars", WEB_SEARXNG_URL = "web.searxng.url", WEB_OPENROUTER_MODEL = "web.search.openrouterModel";

    private final File file;
    private final LinkedHashMap<String, String> map = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private Handler main;

    public Settings(File file) {
        this.file = file;
        try { main = new Handler(Looper.getMainLooper()); } catch (Throwable t) { main = null; }
        load();
    }

    private void load() {
        if (!file.isFile()) return;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] b = new byte[(int) file.length()];
            int off = 0;
            while (off < b.length) { int n = in.read(b, off, b.length - off); if (n < 0) break; off += n; }
            JSONObject o = J.parseOrNull(new String(b, 0, off, StandardCharsets.UTF_8));
            if (o != null) {
                Iterator<String> it = o.keys();
                while (it.hasNext()) { String k = it.next(); map.put(k, J.str(o, k)); }
            }
        } catch (IOException ignored) { }
    }

    private void persist() {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, String> e : map.entrySet()) J.put(o, e.getKey(), e.getValue());
            File dir = file.getAbsoluteFile().getParentFile();
            if (dir != null) dir.mkdirs();
            File tmp = new File(dir, file.getName() + ".tmp");
            try (FileOutputStream os = new FileOutputStream(tmp)) { os.write(o.toString().getBytes(StandardCharsets.UTF_8)); os.getFD().sync(); }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) { }
    }

    public void addListener(Listener l) { listeners.addIfAbsent(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    public synchronized String get(String key, String def) { String v = map.get(key); return v == null ? def : v; }
    public String get(String key) { return get(key, ""); }
    public synchronized boolean has(String key) { String v = map.get(key); return v != null && !v.isEmpty(); }

    public boolean getBool(String key, boolean def) { String v = get(key, null); return v == null || v.isEmpty() ? def : v.equals("true") || v.equals("1"); }

    public int getInt(String key, int def) {
        try { return Integer.parseInt(get(key, "").trim()); } catch (NumberFormatException e) { return def; }
    }

    public float getFloat(String key, float def) {
        try { return Float.parseFloat(get(key, "").trim().replace(',', '.')); } catch (NumberFormatException e) { return def; }
    }

    public List<String> getList(String key) {
        List<String> out = new ArrayList<>();
        for (String s : get(key).split(",")) { s = s.trim(); if (!s.isEmpty()) out.add(s); }
        return out;
    }

    public void set(final String key, String value) {
        if (value == null) value = "";
        synchronized (this) {
            String old = map.get(key);
            if (value.equals(old)) return;
            map.put(key, value);
            persist();
        }
        fire(key);
    }

    public void setBool(String key, boolean v) { set(key, v ? "true" : "false"); }
    public void setList(String key, List<String> v) { StringBuilder sb = new StringBuilder(); for (String s : v) { if (sb.length() > 0) sb.append(','); sb.append(s); } set(key, sb.toString()); }

    public void remove(String key) {
        synchronized (this) { if (map.remove(key) == null) return; persist(); }
        fire(key);
    }

    private void fire(final String key) {
        Runnable r = new Runnable() { @Override public void run() { for (Listener l : listeners) { try { l.onSettingChanged(key); } catch (Throwable ignored) { } } } };
        if (main != null && Looper.myLooper() != Looper.getMainLooper()) main.post(r); else r.run();
    }

    public synchronized Map<String, String> snapshot() { return new LinkedHashMap<>(map); }
}
