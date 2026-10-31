package app.jarvis.media;

/** Microphone capture: 16-bit mono PCM frames. */
public interface MicIn {
    interface FrameListener {
        /** n samples in pcm[0..n). Called on the capture thread; do not block. */
        void onFrame(short[] pcm, int n);
        void onError(String message);
    }

    /**
     * @param aec prefer the platform echo canceller/noise suppressor (VOICE_COMMUNICATION) so the assistant does not hear itself.
     * @return false if capture could not start (no permission, device busy, unsupported rate); see lastError().
     */
    boolean start(int sampleRate, boolean aec, FrameListener l);

    void stop();

    /** Audio session id of the running capture, or -1 when not capturing. Lets the arbiter tell our recording from others. */
    default int sessionId() { return -1; }

    boolean isRunning();

    String lastError();
}
