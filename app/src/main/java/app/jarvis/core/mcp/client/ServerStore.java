package app.jarvis.core.mcp.client;

import app.jarvis.core.util.J;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reads and writes the server list ({@code filesDir/mcp-servers.json}). Writes are atomic: temp file, then rename. */
public final class ServerStore {
    private ServerStore() { }

    public static List<ServerConfig> load(File f) {
        List<ServerConfig> out = new ArrayList<>();
        if (f == null || !f.isFile()) return out;
        try {
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(text);
            JSONArray arr = root.optJSONArray("servers");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                ServerConfig c = ServerConfig.fromJson(arr.optJSONObject(i));
                if (c != null && !containsId(out, c.id)) out.add(c);
            }
        } catch (Exception e) {
            // keep the damaged file for inspection and start with an empty list instead of crashing the app
            File bad = new File(f.getParentFile(), f.getName() + ".bad");
            if (!f.renameTo(bad)) f.delete();
        }
        return out;
    }

    public static void save(File f, List<ServerConfig> servers) throws IOException {
        JSONArray arr = new JSONArray();
        for (ServerConfig c : servers) arr.put(c.toJson());
        JSONObject root = new JSONObject();
        J.put(root, "version", 1);
        J.put(root, "servers", arr);
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (OutputStream os = new FileOutputStream(tmp)) {
            os.write(root.toString().getBytes(StandardCharsets.UTF_8));
            os.flush();
            try {
                ((FileOutputStream) os).getFD().sync();
            } catch (IOException ignored) {
                // some filesystems refuse fsync; the rename below is still atomic
            }
        }
        if (!tmp.renameTo(f)) throw new IOException("Не удалось сохранить список серверов: " + f.getName());
    }

    private static boolean containsId(List<ServerConfig> l, String id) {
        for (ServerConfig c : l) if (c.id.equals(id)) return true;
        return false;
    }
}
