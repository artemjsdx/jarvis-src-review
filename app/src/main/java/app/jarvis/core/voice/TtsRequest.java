package app.jarvis.core.voice;

import java.util.HashMap;
import java.util.Map;

public final class TtsRequest {
    /** Plain text already prepared for speech (see SpeechText). May contain provider-specific tags only if the provider supports them. */
    public String text = "";
    /** Provider-specific voice id; "" = provider default. */
    public String voiceId = "";
    /** ISO language code, "ru" by default. */
    public String language = "ru";
    /** 1.0 = normal. Providers map it to what they support (rate, speed) or ignore it. */
    public float speed = 1f;
    /** Provider-specific knobs, e.g. elevenlabs: model, stability, similarity, style. Unknown keys are ignored. */
    public final Map<String, String> options = new HashMap<>();
    /** Voice cloning reference: absolute path of an audio file and its exact transcript (may be empty). */
    public String refAudioPath = "";
    public String refText = "";
    /** Providers that can may serve/store cached audio (the cache layer sits above them; this is only a hint). */
    public boolean allowCache = true;
}
