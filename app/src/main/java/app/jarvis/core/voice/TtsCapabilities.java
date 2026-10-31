package app.jarvis.core.voice;

/** The speech-speed setting (tts.speed) and which providers act on it. Providers not listed ignore speed. */
public final class TtsCapabilities {
    public static final float MIN_SPEED = 0.7f, MAX_SPEED = 1.4f, DEFAULT_SPEED = 1f;

    private TtsCapabilities() { }

    public static float clampSpeed(float speed) {
        if (Float.isNaN(speed)) return DEFAULT_SPEED;
        return Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));
    }

    /**
     * ElevenLabs (speed voice setting, 0.7–1.2), Edge (SSML prosody rate), Google Translate (slow only, below 1) and F5
     * (the speed value is sent with the request). Silero and OpenRouter voices have no speed control.
     */
    public static boolean supportsSpeed(String providerId) {
        if (providerId == null) return false;
        switch (providerId) {
            case "elevenlabs":
            case "edge":
            case "gtts":
            case "f5-ru":
                return true;
            default:
                return false;
        }
    }
}
