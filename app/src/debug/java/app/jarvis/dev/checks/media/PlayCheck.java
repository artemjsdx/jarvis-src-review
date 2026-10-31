package app.jarvis.dev.checks.media;

import android.content.Context;
import app.jarvis.core.voice.AudioSink;
import app.jarvis.core.voice.AudioSpec;
import app.jarvis.media.SpeechOut;
import app.jarvis.media.SpeechPlayer;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * ART check with the real audio output: SpeechPlayer + AudioTrack on the actual device (sound is audible — fine).
 * Reports onFirstAudio, normal completion, queue order of 3 utterances, stopAll() latency (call duration and the
 * delay until onUtteranceEnd), and back-pressure: a producer faster than realtime must be throttled by playback.
 */
public final class PlayCheck {
    static int fails;

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) { System.out.println("CRASH " + t); t.printStackTrace(System.out); fails++; }
        System.out.println(fails == 0 ? "PLAYCHECK OK" : "PLAYCHECK FAILURES: " + fails);
        System.out.flush();
        System.exit(fails == 0 ? 0 : 1);
    }

    static final class Rec implements SpeechOut.Listener {
        final List<String> events = new ArrayList<>();
        volatile long firstAudioMs = -1;
        volatile int levelCalls;
        volatile float maxLevel;
        volatile boolean speaking;

        @Override public void onUtteranceStart(String id) { rec("start:" + id); }

        @Override public void onFirstAudio(String id, long ms) { rec("first:" + id + "@" + ms + "ms"); firstAudioMs = ms; }

        @Override public void onUtteranceEnd(String id, boolean completed) { rec("end:" + id + (completed ? "(ok)" : "(cut)")); }

        @Override public void onError(String id, String message) { rec("error:" + id + ": " + message); }

        @Override public void onLevel(float level) { levelCalls++; if (level > maxLevel) maxLevel = level; }

        @Override public void onSpeakingChanged(boolean s) { rec("speaking=" + s); speaking = s; }

        synchronized void rec(String e) { events.add(e); System.out.println("    [" + e + "]"); System.out.flush(); }

        synchronized boolean has(String prefix) {
            for (String e : events) if (e.startsWith(prefix)) return true;
            return false;
        }

        synchronized List<String> snapshot() { return new ArrayList<>(events); }

        synchronized void clear() { events.clear(); firstAudioMs = -1; levelCalls = 0; maxLevel = 0; }
    }

    /** Streams a whole file/byte array like a TTS provider would: chunked, with pauses between chunks. */
    static SpeechOut.Producer bytesProducer(final byte[] data, final AudioSpec spec, final int chunk, final long delayMs) {
        return new SpeechOut.Producer() {
            @Override public void produce(AudioSink sink) throws Exception {
                sink.begin(spec);
                int off = 0;
                while (off < data.length) {
                    int n = Math.min(chunk, data.length - off);
                    sink.write(data, off, n);
                    off += n;
                    if (delayMs > 0) Thread.sleep(delayMs);
                }
                sink.end();
            }
        };
    }

    /** A slice of a raw PCM file (mono s16le). */
    static SpeechOut.Producer pcmSliceProducer(final byte[] data, final int rate, final int from, final int len) {
        return new SpeechOut.Producer() {
            @Override public void produce(AudioSink sink) throws Exception {
                sink.begin(new AudioSpec(AudioSpec.Codec.PCM_S16LE, rate, 1));
                sink.write(data, from, Math.min(len, data.length - from));
                sink.end();
            }
        };
    }

    /** Writes `seconds` of a 440 Hz sine at `rate` in 100 ms chunks, `pace` ms between chunks (pace < 100 = faster than realtime). */
    static SpeechOut.Producer sineProducer(final int rate, final double seconds, final long pace) {
        return new SpeechOut.Producer() {
            @Override public void produce(AudioSink sink) throws Exception {
                sink.begin(new AudioSpec(AudioSpec.Codec.PCM_S16LE, rate, 1));
                byte[] b = new byte[rate / 10 * 2];
                long toWrite = (long) (rate * seconds) * 2;
                long written = 0;
                int t = 0;
                while (written < toWrite) {
                    for (int i = 0; i < b.length; i += 2, t++) {
                        short s = (short) (Math.sin(2 * Math.PI * 440 * t / rate) * 12000);
                        b[i] = (byte) s;
                        b[i + 1] = (byte) (s >> 8);
                    }
                    sink.write(b, 0, b.length);
                    written += b.length;
                    if (pace > 0) Thread.sleep(pace);
                }
                sink.end();
            }
        };
    }

    static byte[] read(String path) throws Exception {
        File f = new File(path);
        byte[] b = new byte[(int) f.length()];
        FileInputStream in = new FileInputStream(f);
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) break;
            off += n;
        }
        in.close();
        return b;
    }

    static void waitEvent(Rec r, String prefix, long timeoutMs) throws InterruptedException {
        long dl = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < dl && !r.has(prefix)) Thread.sleep(25);
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) fails++;
        System.out.flush();
    }

    static void run() throws Exception {
        String tmp = System.getenv("TMPDIR");
        if (tmp == null || tmp.isEmpty()) tmp = "/data/data/com.termux/files/usr/tmp"; // app_process may lack TMPDIR
        byte[] shortMp3 = read(tmp + "/el_eleven_flash_v2_5_EXAV_mp3_22050_32.mp3"); // 1.4 s, 22.05 kHz mono
        byte[] longMp3 = read(tmp + "/edge_ru-RU-SvetlanaNeural.mp3");               // 7.4 s, 24 kHz mono
        byte[] svetPcm = read(tmp + "/svet16k.pcm");                                 // 7.4 s, 16 kHz mono
        Context ctx = trySystemContext();
        System.out.println("  context for focus: " + (ctx == null ? "нет (headless) — фокус не запрашивается" : "system context"));

        SpeechPlayer p = new SpeechPlayer(ctx);
        Rec r = new Rec();
        p.setListener(r);

        System.out.println("== 1) one utterance through the real AudioTrack (MP3 22.05k, streamed 2KB/25ms)");
        r.clear();
        long t0 = System.nanoTime();
        p.speak("one", bytesProducer(shortMp3, AudioSpec.mp3(), 2048, 25));
        waitEvent(r, "end:one", 20_000);
        long wall = (System.nanoTime() - t0) / 1_000_000;
        check(r.firstAudioMs >= 0 && r.firstAudioMs < 3000, "onFirstAudio = " + r.firstAudioMs + " ms (<3000)");
        check(r.has("end:one(ok)"), "utterance completed normally");
        check(r.levelCalls > 20, "onLevel fired " + r.levelCalls + " times (expect ~30 Hz over ~1.4 s => 40+)");
        check(r.maxLevel > 0.01f, "max level " + r.maxLevel + " (audible signal)");
        check(!r.speaking, "onSpeakingChanged(false) after the queue emptied");

        System.out.println("== 2) queue order of 3 utterances (0.5 s PCM each)");
        r.clear();
        p.speak("q1", pcmSliceProducer(svetPcm, 16_000, 0, 16_000));
        p.speak("q2", pcmSliceProducer(svetPcm, 16_000, 32_000, 16_000));
        p.speak("q3", pcmSliceProducer(svetPcm, 16_000, 64_000, 16_000));
        waitEvent(r, "end:q3", 20_000);
        List<String> order = new ArrayList<>();
        for (String e : r.snapshot()) if (e.startsWith("start:")) order.add(e);
        check(order.size() == 3 && order.get(0).endsWith("q1") && order.get(1).endsWith("q2") && order.get(2).endsWith("q3"),
                "starts strictly in queue order: " + order);
        check(r.has("end:q1(ok)") && r.has("end:q2(ok)") && r.has("end:q3(ok)"), "all three completed");

        System.out.println("== 3) stopAll() latency mid-utterance (7.4 s MP3, cut at ~0.7 s of playing)");
        r.clear();
        p.speak("cut", bytesProducer(longMp3, AudioSpec.mp3(), 4096, 5));
        waitEvent(r, "first:cut", 5000);
        check(r.firstAudioMs >= 0, "first audio arrived (" + r.firstAudioMs + " ms)");
        Thread.sleep(700);
        long c0 = System.nanoTime();
        p.stopAll();
        long stopCallMs = (System.nanoTime() - c0) / 1_000_000;
        long c1 = System.nanoTime();
        waitEvent(r, "end:cut", 3000);
        long endLatencyMs = (System.nanoTime() - c1) / 1_000_000;
        check(stopCallMs < 100, "stopAll() call took " + stopCallMs + " ms (<100)");
        check(endLatencyMs < 500, "onUtteranceEnd(cut) " + endLatencyMs + " ms after stopAll (target ~100, slack for the worker unwind)");
        check(r.has("end:cut(cut)") && !r.has("end:cut(ok)"), "completed=false reported");
        check(!p.isSpeaking(), "isSpeaking()=false right after stopAll");
        Thread.sleep(300);

        System.out.println("== 4) back-pressure: producer at 5x realtime, 6 s of audio must take ~6 s wall");
        r.clear();
        t0 = System.nanoTime();
        p.speak("bp", sineProducer(24_000, 6.0, 20));
        waitEvent(r, "end:bp", 30_000);
        wall = (System.nanoTime() - t0) / 1_000_000;
        check(r.has("end:bp(ok)") && wall >= 4500, "6 s audio produced+played in " + wall + " ms (write() throttled by playback)");
        check(r.firstAudioMs >= 0 && r.firstAudioMs < 2000, "first audio " + r.firstAudioMs + " ms (pre-buffer ~120 ms path)");

        System.out.println("== 5) volume / duck smoke");
        p.setVolume(0.5f);
        p.duck(true);
        p.duck(false);
        check(true, "setVolume/duck applied without errors");

        long r0 = System.nanoTime();
        p.release();
        check(true, "release() done in " + (System.nanoTime() - r0) / 1_000_000 + " ms");
    }

    static Context trySystemContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            return (Context) at.getMethod("getSystemContext").invoke(thread);
        } catch (Throwable t) {
            System.out.println("  system context unavailable: " + t);
            return null;
        }
    }
}
