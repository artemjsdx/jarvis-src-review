package app.jarvis.core.audio;

import app.jarvis.core.voice.PcmAudio;

/**
 * Splits a microphone stream into utterances. Feed raw frames (any chunking); internally everything is analyzed in
 * {@link Config#frameMs} frames with {@link Vad}. Emits:
 * <ul>
 *   <li>{@link Listener#onSpeechStart} once {@link Config#minSpeechMs} of continuous speech is seen (delayed on
 *       purpose, so every start is followed by exactly one utterance; the beginning is never lost — the preroll ring
 *       buffer keeps {@link Config#prerollMs} of audio before the detected onset);</li>
 *   <li>{@link Listener#onLevel} (0..1) per analysis frame;</li>
 *   <li>{@link Listener#onUtterance} when {@link Config#endSilenceMs} of silence follows, or immediately (with
 *       truncated=true) when {@link Config#maxUtteranceMs} is reached. Blips shorter than minSpeechMs are dropped
 *       silently.</li>
 * </ul>
 * startMs/endMs in the utterance callback are stream time: milliseconds since construction (monotonic).
 * {@link #setEnabled(boolean)}=false pauses detection and clears all state (used while the assistant speaks, so it
 * does not hear itself); {@link #reset()} clears state too.
 */
public final class Segmenter {

    public static final class Config {
        public int sampleRate = 16000;
        public int frameMs = 30;
        public int prerollMs = 450;
        public int endSilenceMs = 550;
        public int maxUtteranceMs = 14000;
        public int minSpeechMs = 220;
    }

    public interface Listener {
        void onSpeechStart();

        void onLevel(float level);

        /** One complete utterance; pcm is mono 16-bit at the configured rate, ownership passes to the listener. */
        void onUtterance(PcmAudio audio, long startMs, long endMs, boolean truncated);
    }

    private final Config cfg;
    private final Listener listener;
    private final Vad vad = new Vad();
    private final int frameSamples;
    private final short[] pending; // accumulation buffer for a partial incoming frame
    private int pendingLen;
    private final short[] preroll; // ring buffer of prerollMs
    private int prerollLen, prerollPos;
    private short[] utter;         // grows; holds preroll + speech so far

    private boolean enabled = true;
    private boolean inSpeech, announced;
    private long streamMs;         // total stream time fed so far
    private long speechStartMs, lastVoiceMs;
    private int voiceFrames, silenceFrames;

    public Segmenter(Config cfg, Listener listener) {
        this.cfg = cfg;
        this.listener = listener;
        frameSamples = cfg.sampleRate * cfg.frameMs / 1000;
        pending = new short[frameSamples];
        preroll = new short[cfg.sampleRate * cfg.prerollMs / 1000];
        utter = new short[Math.max(preroll.length, cfg.sampleRate * cfg.maxUtteranceMs / 1000) + frameSamples * 2];
    }

    public void feed(short[] pcm, int n) {
        if (!enabled) return;
        int off = 0;
        while (off < n) {
            int take = Math.min(frameSamples - pendingLen, n - off);
            System.arraycopy(pcm, off, pending, pendingLen, take);
            pendingLen += take; off += take;
            if (pendingLen == frameSamples) {
                processFrame(pending, 0, frameSamples);
                pendingLen = 0;
            }
        }
    }

    public void setEnabled(boolean on) {
        if (enabled == on) return;
        enabled = on;
        if (!on) clear();
    }

    public boolean isEnabled() { return enabled; }

    public void reset() { clear(); vad.reset(); }

    private void clear() {
        inSpeech = announced = false;
        prerollLen = prerollPos = 0;
        pendingLen = 0;
        voiceFrames = silenceFrames = 0;
    }

    private void processFrame(short[] f, int off, int n) {
        long frameStartMs = streamMs;
        streamMs += cfg.frameMs;
        Vad.Result r = vad.process(f, off, n);
        float level = Math.min(1f, r.rms / 8000f);
        listener.onLevel(announced || inSpeech ? level : 0f);

        if (r.speech) {
            if (!inSpeech) { inSpeech = true; speechStartMs = frameStartMs; }
            voiceFrames++;
            silenceFrames = 0;
            lastVoiceMs = frameStartMs;
        } else if (inSpeech) {
            silenceFrames++;
        }

        if (inSpeech) {
            appendUtter(f, off, n);
            long speechMs = voiceFrames * (long) cfg.frameMs;
            if (!announced && speechMs >= cfg.minSpeechMs) {
                announced = true;
                listener.onSpeechStart();
            }
            long totalMs = (lastVoiceMs - speechStartMs) + cfg.frameMs + silenceFrames * (long) cfg.frameMs;
            boolean maxed = totalMs >= cfg.maxUtteranceMs;
            boolean ended = silenceFrames * (long) cfg.frameMs >= cfg.endSilenceMs;
            if (maxed || ended) finish(maxed);
        } else {
            preroll(f, off, n);
        }
    }

    private void preroll(short[] f, int off, int n) {
        for (int i = 0; i < n; i++) {
            preroll[prerollPos] = f[off + i];
            prerollPos = (prerollPos + 1) % preroll.length;
            if (prerollLen < preroll.length) prerollLen++;
        }
    }

    private void appendUtter(short[] f, int off, int n) {
        if (voiceFrames == 1) { // speech onset: seed with the preroll ring contents (oldest first)
            int begin = prerollLen < preroll.length ? 0 : prerollPos;
            for (int i = 0; i < prerollLen; i++) utter[i] = preroll[(begin + i) % preroll.length];
            utterLen = prerollLen;
        }
        if (utterLen + n > utter.length) { // grow (long speech beyond configured max is truncated anyway)
            short[] bigger = new short[Math.max(utter.length * 2, utterLen + n)];
            System.arraycopy(utter, 0, bigger, 0, utterLen);
            utter = bigger;
        }
        System.arraycopy(f, off, utter, utterLen, n);
        utterLen += n;
    }

    private int utterLen;

    private void finish(boolean truncated) {
        long speechSamples = voiceFrames * (long) frameSamples;
        long totalSamples = utterLen;
        boolean blip = speechSamples * 1000L / cfg.sampleRate < cfg.minSpeechMs;
        long endMs = streamMs - (truncated ? 0 : silenceFrames * (long) cfg.frameMs);
        if (!blip) {
            byte[] pcm = new byte[(int) (totalSamples * 2)];
            for (int i = 0; i < totalSamples; i++) {
                pcm[i * 2] = (byte) utter[i];
                pcm[i * 2 + 1] = (byte) (utter[i] >> 8);
            }
            listener.onUtterance(new PcmAudio(pcm, cfg.sampleRate), speechStartMs, endMs, truncated);
        }
        clear();
    }
}
