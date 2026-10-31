package app.jarvis.core.llm;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.json.JSONObject;

/**
 * Request parameters that one provider refused for one model. Learned from the provider's own error answers
 * ({@code OpenAiClient} drops the parameter and retries), kept in a small JSON file so the next session does not
 * send the same parameter again. Keys are "provider|model"; values are the parameter names as sent on the wire.
 */
public final class ParamMemory {
    private final File file;
    private final Map<String, Set<String>> rejected = new TreeMap<>();

    public ParamMemory(File file) {
        this.file = file;
        load();
    }

    private static String key(String provider, String model) { return provider + "|" + model; }

    public synchronized Set<String> rejected(String provider, String model) {
        Set<String> s = rejected.get(key(provider, model));
        return s == null ? new TreeSet<String>() : new TreeSet<>(s);
    }

    /** Records the refusal; returns true when it was not known before. A null file keeps the memory in RAM only. */
    public synchronized boolean learn(String provider, String model, String param) {
        Set<String> s = rejected.get(key(provider, model));
        if (s == null) { s = new TreeSet<>(); rejected.put(key(provider, model), s); }
        if (!s.add(param)) return false;
        save();
        return true;
    }

    private void load() {
        if (file == null || !file.isFile()) return;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] b = new byte[(int) file.length()];
            int n = 0;
            while (n < b.length) { int r = in.read(b, n, b.length - n); if (r < 0) break; n += r; }
            JSONObject o = new JSONObject(new String(b, 0, n, StandardCharsets.UTF_8));
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                Set<String> s = new TreeSet<>();
                org.json.JSONArray a = o.optJSONArray(k);
                if (a != null) for (int i = 0; i < a.length(); i++) s.add(a.optString(i));
                if (!s.isEmpty()) rejected.put(k, s);
            }
        } catch (Exception ignored) {
            // a damaged file starts an empty memory; the parameters are learned again on the next refusal
        }
    }

    private void save() {
        if (file == null) return;
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, Set<String>> e : rejected.entrySet()) o.put(e.getKey(), new org.json.JSONArray(e.getValue()));
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(o.toString().getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(file)) throw new IOException("rename failed");
        } catch (Exception e) {
            // the in-memory record still applies to this session; a write failure is not worth interrupting a request
        }
    }
}
