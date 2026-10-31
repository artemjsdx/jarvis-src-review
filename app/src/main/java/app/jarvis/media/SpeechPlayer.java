package app.jarvis.media;

import android.content.Context;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import app.jarvis.core.voice.AudioSink;
import app.jarvis.core.voice.AudioSpec;
import java.io.IOException;
import java.util.ArrayDeque;

/**
 * Speech playback per the frozen {@link SpeechOut} contract: a serial queue drained by one worker thread that also
 * runs the head utterance's producer. Each utterance gets a fresh {@link Sink} = {@link StreamDecoder} (decode by
 * AudioSpec) → {@link PcmTrack} (AudioTrack). Audio focus (AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) is requested when
 * speaking starts and abandoned when the queue empties; denial is reported but speech still plays. stopAll() cuts
 * the audio within ms (pause+flush) and makes the producer's sink calls fail fast with IOException("stopped").
 */
public final class SpeechPlayer implements SpeechOut {

    private static final class Job {
        final String id;
        final Producer producer;

        Job(String id, Producer producer) { this.id = id; this.producer = producer; }
    }

    /** One utterance: StreamDecoder (detect format, decode) → PcmTrack (AudioTrack) + RMS metering. Worker thread only. */
    private final class Sink implements AudioSink, StreamDecoder.PcmOut, PcmTrack.Meter {
        final String id;
        final long startNanos;
        StreamDecoder decoder;
        PcmTrack track;
        volatile boolean stopped;
        private boolean firstAudioFired;
        private float levelSmoothed;
        private long lastLevelEmit;

        Sink(String id) {
            this.id = id;
            this.startNanos = System.nanoTime();
        }

        // ---- AudioSink (called by the producer on the worker thread)
        @Override public void begin(AudioSpec spec) throws IOException {
            if (stopped) throw new IOException("stopped");
            track = new PcmTrack(this);
            decoder = new StreamDecoder(this);
            decoder.begin(spec);
        }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            if (stopped) throw new IOException("stopped");
            if (decoder == null) throw new IOException("sink.write() вызван раньше sink.begin()");
            decoder.feed(b, off, len);
        }

        @Override public void end() throws IOException {
            if (stopped) throw new IOException("stopped");
            if (decoder == null) throw new IOException("sink.end() вызван раньше sink.begin()");
            decoder.end();                  // flush codecs: the last PCM reaches the track
            if (track != null) track.drain(); // wait until everything written is actually played out
        }

        @Override public void abort() { stop(); }

        /** stopAll()/release()/normal completion: free codec and track. Idempotent, safe from any thread. */
        void stop() {
            stopped = true;
            if (decoder != null) decoder.abort();
            if (track != null) track.release();
        }

        // ---- StreamDecoder.PcmOut
        @Override public void format(int sampleRate, int channels) throws IOException {
            if (stopped) throw new IOException("stopped");
            track.start(sampleRate, channels);
            track.setVolume(volume);
            track.setDucked(ducked);
        }

        @Override public void pcm(byte[] b, int off, int len) throws IOException {
            if (stopped) throw new IOException("stopped");
            track.write(b, off, len);
        }

        // ---- PcmTrack.Meter
        @Override public void onPcm(byte[] b, int off, int len) {
            long acc = 0;
            int peak = 0;
            for (int i = off; i + 1 < off + len; i += 2) {
                int v = (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
                acc += (long) v * v;
                int a = v < 0 ? -v : v;
                if (a > peak) peak = a;
            }
            // onFirstAudio = onUtteranceStart → the first AudioTrack.write chunk of non-silent data, where
            // "non-silent" means peak amplitude above 1 % of full scale (codec priming silence doesn't count).
            if (!firstAudioFired && peak >= 328) {
                firstAudioFired = true;
                Listener l = listener;
                if (l != null) try { l.onFirstAudio(id, (System.nanoTime() - startNanos) / 1_000_000); } catch (Throwable ignored) { }
            }
            double rms = Math.sqrt(acc / (double) Math.max(1, len / 2)) / 32768.0;
            levelSmoothed = Math.max((float) rms, levelSmoothed * 0.80f); // fast attack, ~decay per chunk
            long now = System.nanoTime();
            if (now - lastLevelEmit >= 33_000_000) { // ~30 Hz
                lastLevelEmit = now;
                Listener l = listener;
                if (l != null) try { l.onLevel(clamp(levelSmoothed * 2.5f)); } catch (Throwable ignored) { }
            }
        }
    }

    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private final Object lock = new Object();
    private final Thread worker;
    private final HandlerThread focusThread;
    private final Context appContext;
    private volatile Listener listener;
    private volatile boolean released;
    private volatile float volume = 1f;
    private volatile boolean ducked;
    private Sink current;
    private boolean speakingState; // last value delivered to onSpeakingChanged
    private AudioManager am;
    private AudioFocusRequest focusRequest;

    /** context may be null (headless ART checks): then audio focus is simply not requested. */
    public SpeechPlayer(Context context) {
        this.appContext = context == null ? null : context.getApplicationContext();
        focusThread = new HandlerThread("jv-speech-focus");
        focusThread.start();
        worker = new Thread(new Runnable() {
            @Override public void run() { workerLoop(); }
        }, "jv-speech");
        worker.start();
    }

    @Override public void setListener(Listener l) { listener = l; }

    @Override public void speak(String id, Producer producer) {
        if (producer == null || released) return;
        synchronized (lock) {
            queue.addLast(new Job(id == null ? "" : id, producer));
            lock.notifyAll();
        }
        updateSpeaking();
    }

    @Override public void stopAll() {
        Sink cur;
        synchronized (lock) {
            queue.clear();
            cur = current;
            lock.notifyAll();
        }
        if (cur != null) cur.stop(); // pause+flush the track (audible cut within ms); producer's writes fail fast
        updateSpeaking();
    }

    @Override public boolean isSpeaking() {
        synchronized (lock) { return !queue.isEmpty() || current != null; }
    }

    @Override public void setVolume(float v) {
        volume = clamp(v);
        Sink s = current;
        if (s != null && s.track != null) s.track.setVolume(volume);
    }

    @Override public void duck(boolean on) {
        ducked = on;
        Sink s = current;
        if (s != null && s.track != null) s.track.setDucked(on);
    }

    @Override public void release() {
        if (released) return;
        released = true;
        stopAll();
        synchronized (lock) { lock.notifyAll(); }
        try {
            worker.join(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Sink cur;
        synchronized (lock) { cur = current; }
        if (cur != null) cur.stop(); // the worker is stuck inside a producer: free the audio anyway
        abandonFocus();
        focusThread.quitSafely();
        updateSpeaking();
    }

    private void workerLoop() {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO); } catch (Throwable ignored) { }
        while (true) {
            Job job;
            synchronized (lock) {
                while (queue.isEmpty() && !released) {
                    try { lock.wait(); } catch (InterruptedException e) { /* re-check the predicates */ }
                }
                if (released) return;
                job = queue.pollFirst();
            }
            if (job != null) playJob(job);
        }
    }

    private void playJob(Job job) {
        Listener l = listener;
        Sink sink = new Sink(job.id);
        synchronized (lock) { current = sink; }
        updateSpeaking();
        acquireFocus(job.id);
        if (l != null) try { l.onUtteranceStart(job.id); } catch (Throwable ignored) { }
        boolean completed = false;
        try {
            job.producer.produce(sink); // begin / write... / end; end() drains the track
            completed = !sink.stopped;
        } catch (IOException e) {
            if (!sink.stopped && l != null) safeError(l, job.id, e.getMessage() == null ? e.toString() : e.getMessage());
        } catch (Throwable t) {
            if (!sink.stopped && l != null) safeError(l, job.id, "Ошибка воспроизведения: " + t);
        } finally {
            sink.stop(); // release codec/track either way (idempotent after end())
            synchronized (lock) { current = null; }
        }
        if (l != null) {
            try { l.onLevel(0); } catch (Throwable ignored) { }
            try { l.onUtteranceEnd(job.id, completed); } catch (Throwable ignored) { }
        }
        updateSpeaking();
    }

    private void updateSpeaking() {
        boolean speaking;
        synchronized (lock) { speaking = !queue.isEmpty() || current != null; }
        if (speaking == speakingState) return;
        speakingState = speaking;
        Listener l = listener;
        if (l != null) try { l.onSpeakingChanged(speaking); } catch (Throwable ignored) { }
        if (!speaking) abandonFocus();
    }

    private void acquireFocus(String id) {
        if (focusRequest != null) return;
        if (am == null && appContext != null) am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return; // headless environment: no focus, still speak
        try {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(PcmTrack.assistantAttrs())
                    .setOnAudioFocusChangeListener(focusListener, new Handler(focusThread.getLooper()))
                    .build();
            int res = am.requestAudioFocus(focusRequest);
            if (res != AudioManager.AUDIOFOCUS_REQUEST_GRANTED && listener != null)
                safeError(listener, id, "Не удалось получить аудиофокус: другое приложение использует звук");
        } catch (Throwable t) {
            focusRequest = null;
            if (listener != null) safeError(listener, id, "Аудиофокус недоступен: " + t);
        }
    }

    private void abandonFocus() {
        AudioFocusRequest r = focusRequest;
        focusRequest = null;
        if (am != null && r != null) try { am.abandonAudioFocusRequest(r); } catch (Throwable ignored) { }
    }

    private final AudioManager.OnAudioFocusChangeListener focusListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override public void onAudioFocusChange(int change) {
            // Someone else took the focus while we speak: stay quiet until it comes back (GAIN).
            duck(change != AudioManager.AUDIOFOCUS_GAIN);
        }
    };

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }

    private static void safeError(Listener l, String id, String msg) {
        try { l.onError(id, msg); } catch (Throwable ignored) { }
    }
}
