package app.jarvis.media;

import app.jarvis.core.voice.AudioSink;

/**
 * Plays synthesized speech. Utterances are played strictly in the order they were queued; each one is fed by a
 * producer (a TTS provider call or a cached-file reader) running on a worker thread of the implementation.
 */
public interface SpeechOut {
    interface Producer {
        /** Write the audio into the sink (begin / write... / end). Throw to signal failure; the sink is aborted for you. */
        void produce(AudioSink sink) throws Exception;
    }

    interface Listener {
        void onUtteranceStart(String id);
        /** Milliseconds from the moment the utterance started producing until the first audible sample was handed to the audio device. */
        void onFirstAudio(String id, long msSinceStart);
        /** completed=false when stopped/aborted/failed. */
        void onUtteranceEnd(String id, boolean completed);
        void onError(String id, String message);
        /** Playback loudness 0..1 at ~30 Hz while speaking (drives the orb). May be called from any thread. */
        void onLevel(float level);
        /** True while something is queued or playing; false when the queue became empty. */
        void onSpeakingChanged(boolean speaking);
    }

    void setListener(Listener l);

    void speak(String id, Producer producer);

    /** Drops the queue and cuts the current utterance (audio stops within ~100 ms). */
    void stopAll();

    boolean isSpeaking();

    /** 0..1. */
    void setVolume(float v);

    /** Temporarily lowers the volume (e.g. while the microphone is capturing a command); duck(false) restores it. */
    void duck(boolean on);

    void release();
}
