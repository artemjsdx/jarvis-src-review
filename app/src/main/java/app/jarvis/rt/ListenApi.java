package app.jarvis.rt;

/**
 * Listening-side extras for the UI (voice sample recording, STT tests). Implemented by VoiceController; reachable via
 * {@code Jarvis.get().listen} (null until the module is attached).
 */
public interface ListenApi extends VoiceControl {
    interface RecordCallback {
        /** ~20 Hz while recording; seconds elapsed and loudness 0..1. */
        void onProgress(float seconds, float level);
        /** sandbox-relative path of the saved 16 kHz mono WAV, or an error. */
        void onDone(String sandboxPath, float seconds);
        void onError(String userMessage);
    }

    /** Records up to maxSeconds from the microphone into the sandbox (work/voice-samples/). Call stopRecording() to finish early. */
    void recordSample(int maxSeconds, RecordCallback cb);

    void stopRecording();

    boolean isRecording();

    interface SttTest {
        void onStarted();
        void onLevel(float level);
        void onResult(String text, long latencyMs, String providerId);
        void onError(String userMessage, String technical);
    }

    /** Real recognition with exactly ONE provider: records one utterance (≤ 8 s, stops at silence) and transcribes it. */
    void testStt(String providerId, SttTest cb);

    /** Real check with the bundled Russian sample (no microphone needed); result also stored in the provider's lastStatus(). */
    void checkStt(String providerId, SttTest cb);
}
