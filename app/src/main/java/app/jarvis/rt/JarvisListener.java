package app.jarvis.rt;

import app.jarvis.media.MusicOut;

/** UI-facing events. Every callback is delivered on the main thread. Implement only what you need. */
public interface JarvisListener {
    default void onState(AState state, String detail) { }
    default void onChat(ChatEvent e) { }
    /** Microphone level while listening / speech level while speaking, 0..1 (~30 Hz). */
    default void onLevel(float level) { }
    /** Microphone side: listening started, the utterance ended, or capture failed. */
    default void onListening(ListeningIndicator s) { }
    default void onMusic(MusicOut.Info info) { }
    /** kind: 0 info, 1 ok, 2 error (see OverlayHost). */
    default void onToast(String message, int kind) { }
    /** "home","agent","files","terminal","studio","log","settings","settings/voice","settings/music"... */
    default void onNavigate(String target) { }
    default void onRunChanged(boolean running) { }
    /** The host activity should show the Android permission prompt for this permission. */
    default void onPermissionNeeded(String androidPermission) { }
    /** One-line description of the latest real action and its outcome (home screen). */
    default void onLastAction(String text, boolean ok) { }
    default void onProvidersChanged() { }
    /** Result of a permission prompt the activity showed for onPermissionNeeded. */
    default void onPermissionResult(String androidPermission, boolean granted) { }
}
