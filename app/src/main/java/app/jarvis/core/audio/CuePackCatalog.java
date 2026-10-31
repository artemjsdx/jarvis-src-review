package app.jarvis.core.audio;

import app.jarvis.core.net.Downloader;
import app.jarvis.core.net.ZipSafe;
import app.jarvis.core.util.CancelToken;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * The one downloadable cue pack: "UI Sound Effects (Button Clicks, User Feedback, Notifications)" by OpenGameArt, licence
 * CC0 (https://creativecommons.org/publicdomain/zero/1.0/). Only the short clips are used as sets: a start cue gates the
 * microphone for its length, so anything longer than about 0.45 s would eat the first words of a command.
 * Durations were measured with ffprobe on the pack as published (see docs/SOUNDS.md).
 */
public final class CuePackCatalog {
    public static final String PACK_ID = "oga-ui";
    public static final String URL = "https://opengameart.org/sites/default/files/ui_ogg.zip";
    public static final String PAGE_URL = "https://opengameart.org/content/ui-sound-effects-button-clicks-user-feedback-notifications";
    public static final String LICENSE_URL = "https://creativecommons.org/publicdomain/zero/1.0/";
    public static final String SHA256 = "9e24099c1625f34077d890ed09e5351afb8025e51d80871e3c40e991c5fd991b";
    public static final long SIZE = 231225;

    private CuePackCatalog() { }

    public static List<CueManifest.Set> sets() {
        return Collections.singletonList(new CueManifest.Set("oga_clicks", "Щелчки (OpenGameArt, CC0)",
            "click_2.ogg", "click_3.ogg", "dum.ogg", 222, 222, 374));
    }

    /** The pack set with this id, or null when the id is not a pack set. */
    public static CueManifest.Set setById(String id) {
        for (CueManifest.Set s : sets()) if (s.id.equals(id)) return s;
        return null;
    }

    /** True when every file of every set is present in packDir (the pack was installed and not deleted since). */
    public static boolean installed(File packDir) {
        for (CueManifest.Set s : sets()) {
            for (CuePolicy.Kind k : CuePolicy.Kind.values()) {
                if (!new File(packDir, s.fileName(k)).isFile()) return false;
            }
        }
        return true;
    }

    /**
     * Downloads the pack zip, checks its SHA-256 (the Downloader does it), extracts it with ZipSafe into packDir and
     * replaces any earlier copy. Nothing is left behind on failure except the temporary files, which are removed.
     */
    public static void install(Downloader dl, String url, String sha256, File packDir, Downloader.Progress progress,
                               CancelToken cancel) throws IOException {
        File parent = packDir.getAbsoluteFile().getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) throw new IOException("Не удалось создать папку сигналов");
        File zip = new File(parent, packDir.getName() + ".part.zip");
        File staging = new File(parent, packDir.getName() + ".staging");
        try {
            Downloader.Request rq = new Downloader.Request();
            rq.url = url;
            rq.dest = zip;
            rq.sha256 = sha256;
            rq.maxBytes = 8L << 20;
            dl.download(rq, progress, cancel);
            ZipSafe.deleteTree(staging);
            ZipSafe.extract(zip, staging, true, new ZipSafe.Limits());
            if (!installed(staging)) throw new IOException("В архиве нет нужных сигналов");
            ZipSafe.deleteTree(packDir);
            if (!staging.renameTo(packDir)) throw new IOException("Не удалось установить набор сигналов");
        } finally {
            zip.delete();
            ZipSafe.deleteTree(staging);
        }
    }
}
