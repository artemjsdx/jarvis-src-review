package app.jarvis.core.design;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * Source-level lint for layout bugs that already cost us a screen: a zero-width LayoutParams without weight (invisible
 * view) and a bare android.view.View added to a layout without an explicit size (measures as match-parent).
 */
public class UiSourceLintTest {
    private static final Pattern ZERO_WIDTH = Pattern.compile("lpm\\(\\s*0\\s*,");
    private static final Pattern BARE_VIEW = Pattern.compile("addView\\(\\s*new View\\(");

    private static File uiRoot() {
        File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (d != null) {
            File candidate = new File(d, "app/src/main/java/app/jarvis/ui");
            if (candidate.isDirectory()) return candidate;
            d = d.getParentFile();
        }
        throw new AssertionError("app/src/main/java/app/jarvis/ui not found above " + System.getProperty("user.dir"));
    }

    @Test public void noZeroWidthLayoutParamsOrBareSpacers() throws Exception {
        List<String> hits = new ArrayList<>();
        for (File f : sources(uiRoot())) {
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) continue;
                if (ZERO_WIDTH.matcher(line).find()) hits.add(f.getName() + ":" + (i + 1) + " zero-width lpm");
                if (BARE_VIEW.matcher(line).find()) hits.add(f.getName() + ":" + (i + 1) + " bare View added to a layout");
            }
        }
        assertTrue("layout lint: " + hits, hits.isEmpty());
    }

    private static List<File> sources(File root) {
        List<File> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.isDirectory()) collect(c, out);
            else if (c.getName().endsWith(".java")) out.add(c);
        }
    }
}
