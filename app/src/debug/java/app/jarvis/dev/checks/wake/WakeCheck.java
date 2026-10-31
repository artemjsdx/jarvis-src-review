package app.jarvis.dev.checks.wake;

import app.jarvis.core.voice.PcmAudio;
import app.jarvis.core.voice.ProviderConfig;
import app.jarvis.core.voice.ProviderStatus;
import app.jarvis.core.voice.SttOptions;
import app.jarvis.core.voice.SttResult;
import app.jarvis.wake.LocalStt;
import app.jarvis.wake.LocalWake;
import app.jarvis.wake.WakePack;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * On-device verification of the offline wake gate (run via tools/art-apk.sh or plain app_process):
 * installs/uses the vosk model pack, measures model load time / RSS / CPU time, screens positive and
 * negative utterances through {@link LocalWake} and transcribes commands through {@link LocalStt}.
 *
 * <p>Usage: WakeCheck &lt;nativeLibDir&gt; &lt;packDir&gt; &lt;file.pcm&gt;... Files named pos_*.pcm must
 * contain the wake word, neg_*.pcm must not. nativeLibDir holds libjnidispatch.so/libvosk.so outside an
 * APK classloader (app_process); a normal APK install needs no such argument.</p>
 */
public final class WakeCheck {
    private static final int RATE = 16000;

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Throwable t) {
            System.out.println("WAKE CHECK FAILED");
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: WakeCheck <nativeLibDir> <packDir> <pos_*.pcm|neg_*.pcm>...");
            System.exit(2);
        }
        File nativeDir = new File(args[0]);
        File packDir = new File(args[1]);
        List<File> samples = new ArrayList<>();
        for (int i = 2; i < args.length; i++) samples.add(new File(args[i]));

        System.out.println("== WakeCheck ==");
        System.out.println("android os: " + android.os.Build.VERSION.SDK_INT + ", abi: " + android.os.Build.SUPPORTED_ABIS[0]);
        System.out.println("props jna.boot.library.path=" + System.getProperty("jna.boot.library.path")
            + " jna.library.path=" + System.getProperty("jna.library.path"));
        verifyClasspathArtifacts();

        // 1. model pack --------------------------------------------------------------------------------------------
        WakePack pack = new WakePack(packDir, new File(packDir.getParentFile(), "downloads"),
            app.jarvis.core.log.Journal.STDOUT);
        if (!pack.isInstalled()) {
            // reuse a previously downloaded zip from $TMPDIR when present (the ART bench may be offline)
            File seed = new File(System.getenv("TMPDIR") != null ? System.getenv("TMPDIR") : "/tmp",
                WakePack.ZIP_NAME);
            if (seed.isFile()) {
                File dst = new File(packDir.getParentFile(), "downloads/" + WakePack.ZIP_NAME);
                dst.getParentFile().mkdirs();
                if (!dst.isFile() || dst.length() != seed.length()) copy(seed, dst);
                System.out.println("seeded zip cache from " + seed);
            }
            long t0 = System.nanoTime();
            pack.install(s -> {
                if (s.state == WakePack.PackState.DOWNLOADING && s.bytesTotal > 0)
                    System.out.printf("  download %d/%d kB%n", s.bytesDone / 1024, s.bytesTotal / 1024);
            }, new app.jarvis.core.util.CancelToken());
            System.out.println("pack install ms: " + (System.nanoTime() - t0) / 1_000_000);
        }
        System.out.println("pack installed: " + pack.isInstalled() + ", size " + mib(pack.sizeBytes())
            + " MiB, version " + pack.status().modelVersion);

        // 2. load, measure RSS before/after -------------------------------------------------------------------------
        LocalWake.ensureNativeLibrary(nativeDir);
        long rssBefore = rssKb();
        long cpuBefore = cpuTicks();
        LocalWake wake = new LocalWake(packDir, app.jarvis.core.audio.WakeMatcher.defaultRu().phraseList(),
            app.jarvis.core.log.Journal.STDOUT);
        String problem = wake.availabilityProblem();
        if (!problem.isEmpty()) System.out.println("availability: " + problem);
        long loadMs = firstLoadMs(wake, samples);
        long rssAfterLoad = rssKb();
        System.out.println("model load ms: " + loadMs);
        System.out.println("rss kb before/after load: " + rssBefore + " / " + rssAfterLoad
            + " (delta " + (rssAfterLoad - rssBefore) + ")");

        // 3. screening -----------------------------------------------------------------------------------------------
        int posHit = 0, posAll = 0, negHit = 0, negAll = 0, errors = 0;
        long decodeTotal = 0;
        double audioSec = 0;
        System.out.println("-- screening --");
        for (File f : samples) {
            PcmAudio a = readPcm16k(f);
            if (a == null) { System.out.println(f.getName() + ": SKIP (not 16 kHz s16le)"); continue; }
            boolean positive = f.getName().startsWith("pos_");
            LocalWake.Detection d = wake.detect(a);
            decodeTotal += d.decodeMs;
            audioSec += a.durationMs() / 1000.0;
            String verdict = !d.ok() ? "ERROR" : (d.found ? "FOUND" : "miss");
            boolean correct = !d.ok() ? false : (positive ? d.found : !d.found);
            if (!d.ok()) errors++;
            else if (positive) { posAll++; if (d.found) posHit++; }
            else { negAll++; if (d.found) negHit++; }
            System.out.printf("%-28s %5d ms  %-5s pos=%-5d score=%.2f  %s%s%n", f.getName(), a.durationMs(),
                verdict, d.positionMs, d.score, d.localText,
                correct || !d.ok() ? "" : "  <-- WRONG");
        }
        System.out.printf("-- results: positives %d/%d (%.0f%%), false triggers %d/%d, detection errors %d%n",
            posHit, posAll, posAll == 0 ? 0 : 100.0 * posHit / posAll, negHit, negAll, errors);
        System.out.printf("-- decode: %d ms for %.2f s audio (%.0f ms per s of audio)%n",
            decodeTotal, audioSec, audioSec == 0 ? 0 : decodeTotal / audioSec);

        // 4. A/B: minimal grammar ("джарвис" + [unk] only) vs the full variant grammar — false-trigger comparison
        System.out.println("-- screening with minimal grammar (single token) --");
        LocalWake minimal = new LocalWake(packDir, java.util.Collections.singletonList("джарвис"),
            app.jarvis.core.log.Journal.NOP);
        int minPosHit = 0, minPosAll = 0, minNegHit = 0;
        for (File f : samples) {
            PcmAudio a = readPcm16k(f);
            if (a == null) continue;
            LocalWake.Detection d = minimal.detect(a);
            if (f.getName().startsWith("pos_")) { minPosAll++; if (d.found) minPosHit++; }
            else if (d.found) minNegHit++;
        }
        System.out.printf("-- minimal grammar: positives %d/%d, false triggers %d/%d%n",
            minPosHit, minPosAll, minNegHit, samples.size() - minPosAll);
        minimal.unload();

        // 5. local STT (full vocabulary) --------------------------------------------------------------------------------
        System.out.println("-- local stt (vosk-local, full vocabulary) --");
        LocalStt stt = new LocalStt();
        ProviderConfig cfg = new ProviderConfig();
        cfg.put("modelDir", packDir.getPath());
        cfg.journal = app.jarvis.core.log.Journal.STDOUT;
        stt.configure(cfg);
        System.out.println("isConfigured: " + stt.isConfigured());
        boolean sttOkShown = false;
        for (File f : samples) {
            PcmAudio a = readPcm16k(f);
            if (a == null) continue;
            try {
                SttResult r = stt.transcribe(a, new SttOptions(), new app.jarvis.core.util.CancelToken());
                System.out.printf("%-28s %5d ms audio -> %6d ms : %s (conf %.2f)%n", f.getName(), a.durationMs(),
                    r.latencyMs, r.text, r.confidence);
                sttOkShown = true;
                break; // one real transcription is enough here; screening above already exercised decoding
            } catch (Exception e) {
                System.out.println(f.getName() + " STT ERROR: " + e.getMessage());
                break;
            }
        }
        // check() compares against the bundled intro sample; feed it a positive intro recording when available
        PcmAudio checkSample = new PcmAudio(new byte[1600], RATE);
        for (File f : samples)
            if (f.getName().startsWith("pos_00") || f.getName().startsWith("pos_06")) { PcmAudio s = readPcm16k(f); if (s != null) checkSample = s; break; }
        ProviderStatus check = stt.check(checkSample, new app.jarvis.core.util.CancelToken());
        System.out.println("check(): ok=" + check.ok + " detail=" + check.detail);

        // 5b. idle release: LocalStt must unload itself after IDLE_MS without work
        String idleWait = System.getenv("JV_WAKE_IDLE_WAIT");
        if (idleWait != null) {
            long deadline = System.currentTimeMillis() + Long.parseLong(idleWait) * 1000L;
            System.out.println("idle-release poll (stt.isLoaded=" + stt.isLoaded() + "), waiting up to " + idleWait + " s...");
            while (stt.isLoaded() && System.currentTimeMillis() < deadline) Thread.sleep(2000);
            System.out.println("idle release after wait: stt.isLoaded=" + stt.isLoaded()
                + " (LocalStt.IDLE_MS=" + LocalStt.IDLE_MS + ")");
        }

        // 6. totals + unload ------------------------------------------------------------------------------------------
        long cpuAfter = cpuTicks();
        stt.unload();
        wake.unload();
        app.jarvis.wake.VoskModel.get(packDir).unload();   // force-close the shared model now (idle timer would wait 120 s)
        Thread.sleep(500);
        System.out.println("cpu ticks used (self+children, 10 ms units): " + (cpuAfter - cpuBefore));
        System.out.println("rss kb after unload: " + rssKb());
        System.out.println("isLoaded after unload: " + wake.isLoaded());
        System.out.println("== done ==");
    }

    /** Time of the very first detect (includes lazy model load); audio without a wake word would do, errors fine. */
    private static long firstLoadMs(LocalWake wake, List<File> samples) {
        for (File f : samples) {
            PcmAudio a = readPcm16k(f);
            if (a == null) continue;
            long t0 = System.nanoTime();
            wake.detect(a);
            return (System.nanoTime() - t0) / 1_000_000;
        }
        // no samples given: load through an empty utterance
        long t0 = System.nanoTime();
        LocalWake.Detection d = wake.detect(new PcmAudio(new byte[3200], RATE));
        System.out.println("warmup detection ok=" + d.ok() + (d.ok() ? "" : " error=" + d.error));
        return (System.nanoTime() - t0) / 1_000_000;
    }

    /** When CLASSPATH is an APK, list the native libs and dex entries (deliverable: vosk/jna dex + lib/arm64-v8a). */
    private static void verifyClasspathArtifacts() throws Exception {
        String cp = System.getenv("CLASSPATH");
        if (cp == null || !cp.endsWith(".apk")) {
            System.out.println("classpath: " + cp + " (not an apk, apk content check skipped)");
            return;
        }
        try (ZipFile z = new ZipFile(cp.split(":")[0])) {
            int dex = 0;
            List<String> libs = new ArrayList<>();
            java.util.Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (n.startsWith("lib/")) libs.add(n + " " + (e.getMethod() == ZipEntry.STORED ? "STORED" : "DEFLATED"));
                if (n.endsWith(".dex")) dex++;
            }
            System.out.println("apk dex entries: " + dex);
            for (String l : libs) System.out.println("apk lib: " + l);
        }
    }

    private static PcmAudio readPcm16k(File f) {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            byte[] all = bo.toByteArray();
            if (f.getName().endsWith(".wav")) {
                PcmAudio w = PcmAudio.fromWav(all);
                return w != null && w.sampleRate == RATE ? w : null;
            }
            return all.length > 0 ? new PcmAudio(all, RATE) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void copy(File from, File to) throws Exception {
        try (InputStream in = new FileInputStream(from)) {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(to)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
    }

    private static String mib(long bytes) { return bytes < 0 ? "?" : String.format("%.1f", bytes / 1048576.0); }

    private static long rssKb() {
        try {
            for (String line : Files.readAllLines(java.nio.file.Paths.get("/proc/self/status")))
                if (line.startsWith("VmRSS")) return Long.parseLong(line.replaceAll("[^0-9]", ""));
        } catch (Exception ignored) { }
        return -1;
    }

    private static long cpuTicks() {
        try {
            String[] f = Files.readAllLines(java.nio.file.Paths.get("/proc/self/stat")).get(0).split(" ");
            return Long.parseLong(f[13]) + Long.parseLong(f[14]);   // utime + stime
        } catch (Exception ignored) { }
        return -1;
    }
}
