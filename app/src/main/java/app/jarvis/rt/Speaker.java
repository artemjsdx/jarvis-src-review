package app.jarvis.rt;

/** What the agent needs from the speech output side (implemented by SpeechService). */
public interface Speaker {
    /** Queue a piece of text for speech (already visible to the user in chat). Non-blocking. */
    void speak(String text);

    /** Stop immediately and drop everything queued. */
    void stop();

    boolean isSpeaking();

    /** Stops current speech only if it is part of an older run (used when a new command arrives). */
    Speaker NONE = new Speaker() {
        @Override public void speak(String text) { }
        @Override public void stop() { }
        @Override public boolean isSpeaking() { return false; }
    };
}
