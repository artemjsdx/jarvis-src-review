package app.jarvis.core.design;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/** Every interface icon is one stroke-based style: 24 viewBox, 1.75-2 dp stroke, round caps and joins, no fill. */
public class IconSetTest {
    private static final Pattern STROKE = Pattern.compile("android:strokeWidth=\"([0-9.]+)\"");
    private static final Pattern PATH_DATA = Pattern.compile("android:pathData=\"([^\"]*)\"");

    /** Android reads "1012" as one number, so an arc whose flags are not separate tokens silently disappears. */
    static String compactArcFlag(String d) {
        String spaced = d.replaceAll("([A-Za-z])", " $1 ").replaceAll("(?<=[0-9.])-", " -");
        String cmd = null;
        int n = 0;
        for (String t : spaced.trim().split("[\\s,]+")) {
            if (t.matches("[A-Za-z]")) { cmd = t; n = 0; continue; }
            if (cmd != null && cmd.equalsIgnoreCase("A") && (n % 7 == 3 || n % 7 == 4) && !t.equals("0") && !t.equals("1")) {
                return "arc flag '" + t + "' is not a separate 0/1 token in " + d;
            }
            n++;
        }
        return null;
    }

    @Test public void compactArcFlagsAreDetected() {
        assertTrue(compactArcFlag("M17.707 6.294A3 3 0 1012 5a3 3 0 10-5.707 1.294") != null);
        assertTrue(compactArcFlag("M6 18a4 4 0 01-2-7.465") != null);
        assertTrue(compactArcFlag("M18 18a4 4 0 1 0 2-7.465") == null);
    }

    private static File drawableDir() {
        File d = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (d != null) {
            File candidate = new File(d, "app/res/drawable");
            if (candidate.isDirectory()) return candidate;
            d = d.getParentFile();
        }
        throw new AssertionError("app/res/drawable not found above " + System.getProperty("user.dir"));
    }

    @Test public void interfaceIconsShareOneStyle() throws Exception {
        File[] files = drawableDir().listFiles();
        assertTrue("no drawables", files != null && files.length > 0);
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (File f : files) {
            String name = f.getName();
            // launcher artwork and the status-bar notification glyph (ic_notify, hand-drawn with a filled dot) are not interface icons
            if (!name.startsWith("ic_") || name.startsWith("ic_launcher") || name.equals("ic_notify.xml")) continue;
            checked++;
            String x = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            if (!x.contains("android:viewportWidth=\"24\"") || !x.contains("android:viewportHeight=\"24\"")) problems.add(name + ": viewport is not 24");
            if (!x.contains("android:strokeLineCap=\"round\"") || !x.contains("android:strokeLineJoin=\"round\"")) problems.add(name + ": caps/joins are not round");
            if (x.contains("android:fillColor=\"#FF") || x.contains("android:fillColor=\"#FFFFFFFF\"")) problems.add(name + ": filled shape in a stroke set");
            if (!x.contains("android:pathData=")) problems.add(name + ": no path data (empty icon)");
            Matcher m = STROKE.matcher(x);
            while (m.find()) {
                float w = Float.parseFloat(m.group(1));
                if (w < 1.75f || w > 2.0f) problems.add(name + ": stroke " + w + " outside 1.75-2");
            }
            Matcher pd = PATH_DATA.matcher(x);
            while (pd.find()) {
                String bad = compactArcFlag(pd.group(1));
                if (bad != null) problems.add(name + ": " + bad);
            }
        }
        assertTrue("checked " + checked + " icons; problems: " + problems, problems.isEmpty());
        assertTrue("expected the full set, found " + checked, checked >= 100);
    }
}
