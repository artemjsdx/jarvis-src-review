package app.jarvis.dev.checks;

import app.jarvis.dev.PreviewHost;
import app.jarvis.dev.PreviewKit;
import app.jarvis.mcp.HeadlessUiHost;
import app.jarvis.rt.Jarvis;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Runs the headless UI host (the real Shell, no window) inside app_process on this phone and writes PNGs to the output directory.
 *   bash tools/art-apk.sh app.jarvis.dev.checks.HeadlessUiCheck <outDir>
 * The script runs on a worker thread while the main looper is pumped, the same way the MCP server thread drives the host in the app.
 */
public final class HeadlessUiCheck {
    private static volatile boolean finished;
    private static File out;

    public static void main(String[] a) {
        try {
            out = new File(a.length > 0 ? a[0] : "build/headless");
            out.mkdirs();
            PreviewHost.init(System.getProperty("java.class.path"), out);
            Jarvis j = PreviewKit.jarvis();
            PreviewKit.seedChat();
            final HeadlessUiHost h = new HeadlessUiHost(j, PreviewHost.ctx);
            Thread worker = new Thread(new Runnable() { @Override public void run() {
                try { script(h); } catch (Throwable t) { step("FAILED: " + t); t.printStackTrace(System.out); }
                finally { finished = true; }
            } }, "headless-check");
            worker.start();
            while (!finished) PreviewHost.pump(20);
            worker.join();
            h.close();
            step("DONE");
        } catch (Throwable t) {
            step("CHECK FAILED: " + t);
            t.printStackTrace(System.out);
        }
        System.out.flush();
    }

    private static void script(HeadlessUiHost h) throws Exception {
        long rss0 = rssKb();
        long t0 = System.nanoTime();
        JSONObject d = h.open(1080, 2340);
        step("open " + ms(t0) + " ms: " + d);
        step("rss before open " + rss0 + " kB, after open " + rssKb() + " kB");

        h.navigate("home");
        Thread.sleep(400);
        long t1 = System.nanoTime();
        JSONObject tree = h.tree(14, true);
        step("tree home " + ms(t1) + " ms, nodes " + tree.optInt("nodes"));
        shot(h, "01-home", 0.5f);

        JSONObject tapTarget = firstClickableText(tree.getJSONObject("root"));
        if (tapTarget != null) {
            String text = tapTarget.optString("text");
            String path = tapTarget.optString("path");
            step("tap home element \"" + text + "\" path " + path);
            h.tapBy("path", path);
            Thread.sleep(700);
            shot(h, "02-home-tap", 0.5f);
        } else step("no clickable text on home");

        h.navigate("agent");
        Thread.sleep(500);
        shot(h, "03-agent", 0.5f);

        h.navigate("terminal");
        Thread.sleep(500);
        JSONObject termTree = h.tree(20, true);
        String field = findPath(termTree.getJSONObject("root"), "EditText");
        step("terminal input path " + field);
        if (field != null) {
            h.typeBy("path", field, "pwd");
            h.key(66);
            Thread.sleep(1200);
            termTree = h.tree(20, true);
            step("terminal output lines containing '/': " + collectTexts(termTree.getJSONObject("root"), "/"));
        }
        shot(h, "04-terminal", 0.5f);

        String[] more = {"files", "studio", "log", "settings"};
        for (String s : more) {
            h.navigate(s);
            Thread.sleep(450);
            shot(h, "05-" + s.replace('/', '-'), 0.4f);
        }

        h.navigate("home");
        Thread.sleep(500);
        final JSONObject[] probe = new JSONObject[1];
        final HeadlessUiHost hh = h;
        Thread p = new Thread(new Runnable() { @Override public void run() {
            try { probe[0] = hh.probeBy("tag", "files", "scaleX", 900); } catch (Throwable t) { step("probe failed: " + t); }
        } });
        p.start();
        Thread.sleep(150);
        h.tapBy("tag", "files");
        p.join();
        if (probe[0] != null) step("anim probe dock press scaleX: frames " + probe[0].optInt("frames") + ", changed " + probe[0].optInt("changedFrames")
            + ", min " + probe[0].optDouble("min") + ", max " + probe[0].optDouble("max") + ", avg frame " + probe[0].optDouble("avgFrameMs") + " ms");
        Thread.sleep(400);
        shot(h, "06-files-after-dock-tap", 0.4f);

        JSONObject end = h.describe();
        step("describe " + end);
        step("rss end " + rssKb() + " kB (before open " + rss0 + " kB)");
    }

    private static void shot(HeadlessUiHost h, String name, float scale) throws Exception {
        long t = System.nanoTime();
        byte[] png = h.screenshot(scale, "png");
        try (FileOutputStream os = new FileOutputStream(new File(out, name + ".png"))) { os.write(png); }
        step("shot " + name + " scale " + scale + " " + png.length + " bytes in " + ms(t) + " ms");
    }

    static JSONObject firstClickableText(JSONObject n) {
        if (n == null) return null;
        if (n.optBoolean("clickable") && n.has("text") && n.optBoolean("visible")) return n;
        JSONArray kids = n.optJSONArray("children");
        if (kids == null) return null;
        for (int i = 0; i < kids.length(); i++) {
            JSONObject f = firstClickableText(kids.optJSONObject(i));
            if (f != null) return f;
        }
        return null;
    }

    static String findPath(JSONObject n, String cls) {
        if (n == null) return null;
        if (cls.equals(n.optString("class")) && n.optBoolean("visible")) return n.optString("path");
        JSONArray kids = n.optJSONArray("children");
        if (kids == null) return null;
        for (int i = 0; i < kids.length(); i++) {
            String f = findPath(kids.optJSONObject(i), cls);
            if (f != null) return f;
        }
        return null;
    }

    static String collectTexts(JSONObject n, String needle) {
        StringBuilder sb = new StringBuilder();
        collect(n, needle, sb);
        return sb.length() > 400 ? sb.substring(0, 400) + "…" : sb.toString();
    }

    private static void collect(JSONObject n, String needle, StringBuilder sb) {
        if (n == null) return;
        String t = n.optString("text", "");
        if (t.contains(needle)) sb.append(t.replace('\n', ' ')).append(" | ");
        JSONArray kids = n.optJSONArray("children");
        if (kids != null) for (int i = 0; i < kids.length(); i++) collect(kids.optJSONObject(i), needle, sb);
    }

    private static long rssKb() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("VmRSS:")) return Long.parseLong(line.replaceAll("[^0-9]", ""));
            }
        } catch (Exception ignored) { }
        return -1;
    }

    private static String ms(long t0) { return String.format(java.util.Locale.ROOT, "%.1f", (System.nanoTime() - t0) / 1e6); }

    private static void step(String s) { System.out.println("[headless] " + s); System.out.flush(); }
}
