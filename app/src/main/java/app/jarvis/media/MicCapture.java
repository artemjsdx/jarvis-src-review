package app.jarvis.media;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Process;

/**
 * Microphone capture (16-bit mono PCM) on a dedicated audio-priority thread. start() fails with a clear Russian reason
 * instead of throwing; stop() always releases the device. If Android hands out only digital silence for ~3 s (app in
 * background without a permitted foreground service, or the privacy switch is off) the listener is told, because that
 * looks exactly like "nobody is speaking" otherwise.
 */
public final class MicCapture implements MicIn {
    /** Reported when the microphone delivers only silence for about two seconds (Android withholds audio from background apps). */
    public static final String NO_AUDIO_MESSAGE = "Микрофон молчит уже около 2 с: его занимает другое приложение, либо Jarvis в фоне без разрешённой службы, либо микрофон выключен в настройках Android";

    private final Object lock = new Object();
    private AudioRecord rec;
    private AcousticEchoCanceler aec;
    private NoiseSuppressor ns;
    private Thread thread;
    private volatile boolean running;
    private volatile String lastError = "";

    @Override public boolean start(int sampleRate, boolean wantAec, FrameListener l) {
        synchronized (lock) {
            if (running) return true;
            lastError = "";
            int min = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) { lastError = "Устройство не поддерживает запись " + sampleRate + " Гц"; return false; }
            int buffer = Math.max(min * 4, sampleRate / 5 * 2);       // at least 200 ms
            int[] sources = wantAec ? new int[]{MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC}
                                    : new int[]{MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};
            AudioRecord r = null;
            for (int src : sources) {
                try {
                    AudioRecord t = new AudioRecord(src, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer);
                    if (t.getState() == AudioRecord.STATE_INITIALIZED) { r = t; break; }
                    t.release();
                } catch (SecurityException e) {
                    lastError = "Нет разрешения на микрофон";
                    return false;
                } catch (Throwable e) {
                    lastError = "Не удалось открыть микрофон: " + e.getClass().getSimpleName();
                }
            }
            if (r == null) { if (lastError.isEmpty()) lastError = "Микрофон недоступен: нет разрешения или его использует другое приложение"; return false; }
            try {
                if (wantAec && AcousticEchoCanceler.isAvailable()) { aec = AcousticEchoCanceler.create(r.getAudioSessionId()); if (aec != null) aec.setEnabled(true); }
                if (NoiseSuppressor.isAvailable()) { ns = NoiseSuppressor.create(r.getAudioSessionId()); if (ns != null) ns.setEnabled(true); }
            } catch (Throwable ignored) { /* effects are an optimisation, never a reason to fail */ }
            try {
                r.startRecording();
            } catch (Throwable e) {
                r.release();
                lastError = "Не удалось запустить запись: " + e.getClass().getSimpleName();
                return false;
            }
            if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                r.release();
                lastError = "Микрофон занят другим приложением";
                return false;
            }
            rec = r;
            running = true;
            final AudioRecord fr = r;
            final int rate = sampleRate;
            final FrameListener fl = l;
            thread = new Thread(new Runnable() { @Override public void run() { loop(fr, rate, fl); } }, "jv-mic");
            thread.start();
            return true;
        }
    }

    private void loop(AudioRecord r, int rate, FrameListener l) {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO); } catch (Throwable ignored) { }
        short[] frame = new short[rate * 30 / 1000];
        int silentFrames = 0;
        String failure = null;
        while (running) {
            int n = r.read(frame, 0, frame.length);
            if (n > 0) {
                boolean allZero = true;
                for (int i = 0; i < n; i++) if (frame[i] != 0) { allZero = false; break; }
                silentFrames = allZero ? silentFrames + 1 : 0;
                if (silentFrames == 100) { failure = NO_AUDIO_MESSAGE; break; }
                try { l.onFrame(frame, n); } catch (Throwable t) { /* a faulty consumer must not kill capture */ }
            } else if (n < 0) {
                failure = n == AudioRecord.ERROR_DEAD_OBJECT ? "Микрофон отключён системой" : "Ошибка чтения микрофона (" + n + ")";
                break;
            } else {
                try { Thread.sleep(5); } catch (InterruptedException e) { break; }
            }
        }
        boolean wasRunning;
        synchronized (lock) { wasRunning = running; running = false; releaseLocked(); }
        if (failure != null && wasRunning) { lastError = failure; try { l.onError(failure); } catch (Throwable ignored) { } }
    }

    private void releaseLocked() {
        try { if (aec != null) aec.release(); } catch (Throwable ignored) { }
        try { if (ns != null) ns.release(); } catch (Throwable ignored) { }
        aec = null; ns = null;
        AudioRecord r = rec;
        rec = null;
        if (r != null) {
            try { r.stop(); } catch (Throwable ignored) { }
            try { r.release(); } catch (Throwable ignored) { }
        }
    }

    @Override public void stop() {
        Thread t;
        synchronized (lock) { running = false; t = thread; thread = null; }
        if (t != null && t != Thread.currentThread()) { try { t.join(800); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
        synchronized (lock) { releaseLocked(); }
    }

    @Override public boolean isRunning() { return running; }

    @Override public int sessionId() {
        AudioRecord r = rec;
        return running && r != null ? r.getAudioSessionId() : -1;
    }

    @Override public String lastError() { return lastError; }
}
