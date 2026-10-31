package app.jarvis.dev.e2e;

import app.jarvis.core.voice.AudioSink;
import app.jarvis.core.voice.AudioSpec;
import app.jarvis.media.SpeechOut;
import app.jarvis.media.StreamDecoder;
import java.io.IOException;
import java.util.ArrayDeque;

/**
 * A SpeechOut that never touches the speaker: it decodes the stream with the real MediaCodec path (StreamDecoder), fires the
 * same listener events as SpeechPlayer (first audible PCM, speaking on/off) and waits the duration of the audio so the timeline
 * of a spoken reply is realistic. For latency checks on a phone that must stay silent.
 */
final class SilentOut implements SpeechOut {
    private static final class Job { final String id; final Producer p; Job(String id, Producer p) { this.id = id; this.p = p; } }

    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private final Object lock = new Object();
    private volatile Listener listener;
    private volatile boolean stopped;
    private Job current;
    private boolean speakingState;

    SilentOut() {
        Thread t = new Thread(new Runnable() { @Override public void run() { loop(); } }, "silent-out");
        t.setDaemon(true);
        t.start();
    }

    @Override public void setListener(Listener l) { listener = l; }

    @Override public void speak(String id, Producer p) {
        synchronized (lock) { queue.addLast(new Job(id, p)); lock.notifyAll(); }
        update();
    }

    @Override public void stopAll() {
        synchronized (lock) { queue.clear(); stopped = true; }
        update();
    }

    @Override public boolean isSpeaking() { synchronized (lock) { return !queue.isEmpty() || current != null; } }

    @Override public void setVolume(float v) { }

    @Override public void duck(boolean on) { }

    @Override public void release() { stopAll(); }

    private void update() {
        boolean s = isSpeaking();
        Listener l = listener;
        synchronized (lock) { if (s == speakingState) return; speakingState = s; }
        if (l != null) l.onSpeakingChanged(s);
    }

    private void loop() {
        while (true) {
            Job j;
            synchronized (lock) {
                while (queue.isEmpty()) { try { lock.wait(); } catch (InterruptedException e) { return; } }
                j = queue.pollFirst();
                current = j;
                stopped = false;
            }
            Listener l = listener;
            if (l != null) l.onUtteranceStart(j.id);
            boolean ok = false;
            try { j.p.produce(new Sink(j.id)); ok = !stopped; }
            catch (Throwable t) { if (l != null && !stopped) l.onError(j.id, String.valueOf(t.getMessage() == null ? t : t.getMessage())); }
            synchronized (lock) { current = null; }
            if (l != null) l.onUtteranceEnd(j.id, ok);
            update();
        }
    }

    private final class Sink implements AudioSink, StreamDecoder.PcmOut {
        final String id;
        final long t0 = System.nanoTime();
        StreamDecoder dec;
        int rate = 24000, ch = 1;
        long pcmBytes;
        long firstPcmNs = -1;
        boolean fired;

        Sink(String id) { this.id = id; }

        @Override public void begin(AudioSpec s) throws IOException { dec = new StreamDecoder(this); dec.begin(s); }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            if (stopped) throw new IOException("stopped");
            dec.feed(b, off, len);
        }

        @Override public void end() throws IOException {
            dec.end();
            long durNs = pcmBytes * 1_000_000_000L / Math.max(1, rate * 2L * ch);
            long wait = firstPcmNs < 0 ? 0 : firstPcmNs + durNs - System.nanoTime();
            if (wait > 0) try { Thread.sleep(wait / 1_000_000L); } catch (InterruptedException ignored) { }
        }

        @Override public void abort() { if (dec != null) dec.abort(); }

        @Override public void format(int sampleRate, int channels) { rate = sampleRate; ch = channels; }

        @Override public void pcm(byte[] b, int off, int len) {
            pcmBytes += len;
            if (firstPcmNs < 0) firstPcmNs = System.nanoTime();
            if (fired) return;
            int peak = 0;
            for (int i = off; i + 1 < off + len; i += 2) { int v = (short) ((b[i] & 0xFF) | (b[i + 1] << 8)); peak = Math.max(peak, v < 0 ? -v : v); }
            if (peak >= 328) {
                fired = true;
                Listener l = listener;
                if (l != null) l.onFirstAudio(id, (System.nanoTime() - t0) / 1_000_000L);
            }
        }
    }
}
