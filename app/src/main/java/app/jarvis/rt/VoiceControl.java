package app.jarvis.rt;

/** Microphone-side control surface used by the UI, the agent tools and the service. Implemented by VoiceController. */
public interface VoiceControl {
    /** Push-to-talk: capture one command now (works with or without wake mode). Returns false if it could not start (reason via toast/permission event). */
    boolean startListening();

    /** Ends push-to-talk capture (what was recorded so far is recognized). */
    void stopListening();

    /** Drops everything: capture, recognition in flight. */
    void cancel();

    void setWakeEnabled(boolean enabled);

    boolean isWakeEnabled();

    boolean isMicActive();

    String micProblem();

    VoiceControl NONE = new VoiceControl() {
        @Override public boolean startListening() { return false; }
        @Override public void stopListening() { }
        @Override public void cancel() { }
        @Override public void setWakeEnabled(boolean enabled) { }
        @Override public boolean isWakeEnabled() { return false; }
        @Override public boolean isMicActive() { return false; }
        @Override public String micProblem() { return "Голосовой модуль не подключён"; }
    };
}
