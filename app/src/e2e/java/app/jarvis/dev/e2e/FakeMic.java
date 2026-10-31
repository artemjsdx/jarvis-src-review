package app.jarvis.dev.e2e;

import app.jarvis.media.MicIn;
import java.io.File;
import java.io.FileInputStream;

/** A microphone that "hears" audio files: 16 kHz mono s16le frames pushed on a thread, faster than real time if asked. */
final class FakeMic implements MicIn {
    private volatile FrameListener l;
    private volatile boolean running;

    @Override public boolean start(int sampleRate, boolean aec, FrameListener listener) { l = listener; running = true; return true; }
    @Override public void stop() { running = false; }
    @Override public boolean isRunning() { return running; }
    @Override public String lastError() { return ""; }

    static byte[] read(String path) throws Exception {
        File f = new File(path);
        byte[] b = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) { int o = 0; while (o < b.length) { int n = in.read(b, o, b.length - o); if (n < 0) break; o += n; } }
        return b;
    }

    /** Pushes lead silence, the speech and tail silence; pacingMs = real sleep per 30 ms frame (30 = real time). Blocks until done. */
    void play(byte[] pcm, int leadMs, int tailMs, int pacingMs) throws InterruptedException {
        FrameListener fl = l;
        if (fl == null || !running) throw new IllegalStateException("microphone is not started");
        int frame = 480;                                         // 30 ms @ 16 kHz
        byte[] all = new byte[leadMs * 32 + pcm.length + tailMs * 32];
        System.arraycopy(pcm, 0, all, leadMs * 32, pcm.length);
        short[] buf = new short[frame];
        for (int off = 0; off + frame * 2 <= all.length && running; off += frame * 2) {
            for (int i = 0; i < frame; i++) buf[i] = (short) ((all[off + 2 * i] & 0xff) | (all[off + 2 * i + 1] << 8));
            fl.onFrame(buf, frame);
            if (pacingMs > 0) Thread.sleep(pacingMs);
        }
    }
}
