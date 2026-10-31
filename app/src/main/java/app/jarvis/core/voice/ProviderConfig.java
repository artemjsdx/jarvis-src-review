package app.jarvis.core.voice;

import app.jarvis.core.log.Journal;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** Settings handed to a provider. Keys per provider are documented in that provider's class (apiKey, proxy, model, ...). */
public final class ProviderConfig {
    public final Map<String, String> values = new HashMap<>();
    public Journal journal = Journal.NOP;
    /** Scratch directory the provider may use (downloads, temp files). */
    public File tmpDir;

    public String get(String key) { String v = values.get(key); return v == null ? "" : v.trim(); }
    public String get(String key, String def) { String v = get(key); return v.isEmpty() ? def : v; }
    public boolean has(String key) { return !get(key).isEmpty(); }
    public ProviderConfig put(String key, String value) { values.put(key, value); return this; }
}
