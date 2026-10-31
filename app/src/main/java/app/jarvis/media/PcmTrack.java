package app.jarvis.media;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import java.io.IOException;

/**
 * One AudioTrack in MODE_STREAM for a single utterance: USAGE_ASSISTANT/CONTENT_TYPE_SPEECH (USAGE_MEDIA
 * fallback), buffer = 2×getMinBufferSize, ~120 ms pre-buffered before play() to avoid an underrun click.
 * Writes are non-blocking and polled in ~10 ms steps, so a stop request is honoured within one step even
 * mid-write; back-pressure kicks in above ~3 s buffered (in practice the track capacity, a few hundred ms,
 * is the real ceiling). {@link #stopNow()} pauses+flushes from any thread and cuts audible audio within ms.
 */
final class PcmTrack {

    /** Metering hook: called with every chunk of PCM actually handed to the track. */
    interface Meter {
        void onPcm(byte[] b, int off, int len);
    }

    private final Meter meter;
    private AudioTrack track;
    private int rate, channels;
    private long writtenFrames;
    private float volume = 1f, duckMul = 1f;
    private boolean playing;
    private volatile boolean stopped;

    PcmTrack(Meter meter) { this.meter = meter; }

    static AudioAttributes assistantAttrs() {
        try {
            return new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
        } catch (Throwable t) {
            return new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
        }
    }

    void start(int rate, int channels) throws IOException {
        if (track != null && this.rate == rate && this.channels == channels) return;
        releaseTrack();
        this.rate = rate;
        this.channels = channels;
        int chMask = channels >= 2 ? AudioFormat.CHANNEL_OUT_STEREO : AudioFormat.CHANNEL_OUT_MONO;
        int min;
        try {
            min = AudioTrack.getMinBufferSize(rate, chMask, AudioFormat.ENCODING_PCM_16BIT);
        } catch (Throwable e) {
            min = -1;
        }
        if (min <= 0) throw new IOException("Система не поддерживает вывод " + rate + " Гц/" + channels + " кан. (minBufferSize=" + min + ")");
        AudioTrack t;
        try {
            t = new AudioTrack(assistantAttrs(), new AudioFormat.Builder()
                            .setSampleRate(rate).setChannelMask(chMask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                    min * 2, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
        } catch (Throwable e) {
            throw new IOException("Не удалось открыть аудиовыход " + rate + " Гц: " + e, e);
        }
        if (t.getState() != AudioTrack.STATE_INITIALIZED) {
            int st = t.getState();
            t.release();
            throw new IOException("Аудиовыход не инициализировался (" + rate + " Гц, state=" + st + ")");
        }
        track = t;
        writtenFrames = 0;
        playing = false;
        applyVolume();
    }

    void write(byte[] b, int off, int len) throws IOException {
        AudioTrack t = track;
        if (t == null) throw new IOException("stopped");
        int frameBytes = Math.max(2, channels * 2);
        while (len > 0) {
            if (stopped) throw new IOException("stopped");
            if (!playing && bufferedMs() >= startThresholdMs()) play();
            while (bufferedMs() > 3000 && !stopped) sleep(15); // back-pressure ceiling (~3 s); capacity is far below it
            if (stopped) throw new IOException("stopped");
            int n = t.write(b, off, len, AudioTrack.WRITE_NON_BLOCKING);
            if (n < 0) throw new IOException(stopped ? "stopped" : "AudioTrack.write вернул " + n);
            if (n == 0) { // buffer full; if the pre-buffer target is unreachable, start playing anyway
                if (!playing && bufferedMs() > 0) play();
                sleep(10);
                continue;
            }
            if (meter != null) meter.onPcm(b, off, n);
            off += n;
            len -= n;
            writtenFrames += n / frameBytes;
        }
    }

    /** Waits until everything written has actually been played. IOException("stopped") if stopped meanwhile. */
    void drain() throws IOException {
        play(); // even a tiny stream must reach the speaker
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!stopped) {
            AudioTrack t = track;
            if (t == null) throw new IOException("stopped");
            if (writtenFrames - (t.getPlaybackHeadPosition() & 0xFFFFFFFFL) <= 0) return;
            if (System.nanoTime() > deadline) throw new IOException("Аудиовыход перестал продвигаться");
            sleep(15);
        }
        throw new IOException("stopped");
    }

    /** Any thread: cut audible audio immediately (pause + flush). Blocked write loops exit within ~10 ms. */
    void stopNow() {
        stopped = true;
        AudioTrack t = track;
        if (t != null) {
            try { t.pause(); } catch (Throwable ignored) { }
            try { t.flush(); } catch (Throwable ignored) { }
        }
    }

    void setVolume(float v) {
        volume = clamp(v);
        applyVolume();
    }

    void setDucked(boolean on) {
        duckMul = on ? 0.35f : 1f;
        applyVolume();
    }

    void release() {
        stopped = true;
        releaseTrack();
    }

    private void play() {
        AudioTrack t = track;
        if (t == null || playing) return;
        try {
            t.play();
            playing = true;
        } catch (Throwable ignored) { /* release race: the next stopped check exits */ }
    }

    private int startThresholdMs() {
        AudioTrack t = track;
        if (t == null) return 120;
        int capMs = t.getBufferSizeInFrames() * 1000 / Math.max(1, rate);
        return Math.min(120, Math.max(20, capMs / 2));
    }

    private long bufferedMs() {
        AudioTrack t = track;
        if (t == null) return 0;
        long bufferedFrames = Math.max(0, writtenFrames - (t.getPlaybackHeadPosition() & 0xFFFFFFFFL));
        return bufferedFrames * 1000 / Math.max(1, rate);
    }

    private void applyVolume() {
        AudioTrack t = track;
        if (t != null) {
            try { t.setVolume(clamp(volume * duckMul)); } catch (Throwable ignored) { }
        }
    }

    private void releaseTrack() {
        AudioTrack t = track;
        track = null;
        if (t == null) return;
        try { t.pause(); } catch (Throwable ignored) { }
        try { t.flush(); } catch (Throwable ignored) { }
        try { t.release(); } catch (Throwable ignored) { }
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
