package app.jarvis.core.util;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class Mime {
    private Mime() { }

    private static final Map<String, String> M = new HashMap<>();
    static {
        String[][] t = {
            {"mp3", "audio/mpeg"}, {"m4a", "audio/mp4"}, {"aac", "audio/aac"}, {"wav", "audio/wav"}, {"ogg", "audio/ogg"},
            {"oga", "audio/ogg"}, {"opus", "audio/ogg"}, {"flac", "audio/flac"}, {"amr", "audio/amr"}, {"mid", "audio/midi"},
            {"mp4", "video/mp4"}, {"mkv", "video/x-matroska"}, {"webm", "video/webm"}, {"3gp", "video/3gpp"},
            {"jpg", "image/jpeg"}, {"jpeg", "image/jpeg"}, {"png", "image/png"}, {"gif", "image/gif"}, {"webp", "image/webp"},
            {"txt", "text/plain"}, {"md", "text/markdown"}, {"json", "application/json"}, {"xml", "application/xml"},
            {"html", "text/html"}, {"csv", "text/csv"}, {"log", "text/plain"}, {"sh", "text/x-shellscript"},
            {"py", "text/x-python"}, {"java", "text/x-java"}, {"js", "text/javascript"}, {"zip", "application/zip"},
            {"pdf", "application/pdf"}, {"apk", "application/vnd.android.package-archive"}
        };
        for (String[] e : t) M.put(e[0], e[1]);
    }

    public static String guess(String name) {
        int d = name.lastIndexOf('.');
        String ext = d < 0 ? "" : name.substring(d + 1).toLowerCase(Locale.ROOT);
        String m = M.get(ext);
        return m == null ? "application/octet-stream" : m;
    }

    public static boolean isAudio(String name) { return guess(name).startsWith("audio/"); }
    public static boolean isText(String name) {
        String m = guess(name);
        return m.startsWith("text/") || m.equals("application/json") || m.equals("application/xml");
    }
}
