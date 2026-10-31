package app.jarvis.audio;

import android.content.Context;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.os.Build;
import android.os.Handler;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * The system's view of the microphone: another app recording (AudioManager recording callback), our own capture silenced
 * by the system (API 29+), and a phone or VoIP call (audio mode, polled while the assistant wants the microphone). Reports
 * changes on the handler's thread; the decisions are made by MicArbiterMachine.
 */
public final class MicArbiter {
    public interface Listener {
        void onBlockers(boolean otherAppRecording, boolean inCall);

        void onOwnSilenced();
    }

    private static final long CALL_POLL_MS = 1000;

    private final AudioManager am;
    private final Handler main;
    private final IntSupplier ownSession;
    private final Listener listener;
    private AudioManager.AudioRecordingCallback callback;
    private boolean watchingCall, lastOther, lastCall, lastSilenced;

    /** @param ownSession the audio session id of our running capture, or -1 when we hold no microphone. */
    public MicArbiter(Context ctx, Handler main, IntSupplier ownSession, Listener listener) {
        this.am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        this.main = main;
        this.ownSession = ownSession;
        this.listener = listener;
    }

    public void start() {
        if (am == null || callback != null) return;
        callback = new AudioManager.AudioRecordingCallback() {
            @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) { onConfigs(configs); }
        };
        try {
            am.registerAudioRecordingCallback(callback, main);
        } catch (RuntimeException e) {
            callback = null;
        }
    }

    public void stop() {
        setWatchingCall(false);
        if (callback != null) {
            try { am.unregisterAudioRecordingCallback(callback); } catch (RuntimeException ignored) { }
            callback = null;
        }
        lastOther = false;
        lastCall = false;
        lastSilenced = false;
    }

    /** Polls the audio mode for calls; on while the assistant wants the microphone. */
    public void setWatchingCall(boolean on) {
        if (on == watchingCall || am == null) return;
        watchingCall = on;
        main.removeCallbacks(poll);
        if (on) main.post(poll);
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!watchingCall) return;
            checkCall();
            main.postDelayed(this, CALL_POLL_MS);
        }
    };

    private void checkCall() {
        boolean call = false;
        try {
            int mode = am.getMode();
            call = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION;
        } catch (RuntimeException ignored) { }
        if (call != lastCall) {
            lastCall = call;
            listener.onBlockers(lastOther, lastCall);
        }
    }

    private void onConfigs(List<AudioRecordingConfiguration> configs) {
        int mine = ownSession.getAsInt();
        boolean other = false, silenced = false;
        if (Build.VERSION.SDK_INT >= 29) {
            for (AudioRecordingConfiguration c : configs) {
                if (mine >= 0 && c.getClientAudioSessionId() == mine) {
                    if (c.isClientSilenced()) silenced = true;
                } else {
                    other = true;
                }
            }
        } else {
            other = configs.size() > (mine >= 0 ? 1 : 0);
        }
        boolean changed = other != lastOther;
        lastOther = other;
        if (changed) listener.onBlockers(lastOther, lastCall);
        if (silenced && !lastSilenced) listener.onOwnSilenced();
        lastSilenced = silenced;
    }
}
