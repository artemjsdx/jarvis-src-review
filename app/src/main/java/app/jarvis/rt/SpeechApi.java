package app.jarvis.rt;

import app.jarvis.core.util.CancelToken;
import app.jarvis.core.voice.SttProvider;
import app.jarvis.core.voice.TtsProvider;
import app.jarvis.core.voice.TtsRequest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Speech output as the UI sees it: providers with their real status, ad-hoc tests, voice profiles (incl. cloned voices),
 * cache and pronunciation dictionary. Implemented by SpeechService; reachable as {@code Jarvis.get().speech}
 * (null until the voice module is attached).
 */
public interface SpeechApi extends Speaker {
    List<TtsProvider> ttsProviders();
    TtsProvider tts(String id);
    List<SttProvider> sttProviders();
    SttProvider stt(String id);

    /** Re-applies keys/settings (Secrets "key.<providerId>", Settings "tts.*") to every provider; call after the user changes them. */
    void reconfigure();

    /** Ordered provider ids used for normal replies (settings tts.chain), only those that are configured. */
    List<String> activeChain();

    interface Test {
        void onStarted();
        /** Real time to the first audible sample. */
        void onFirstAudio(long ms);
        void onDone(long totalMs);
        void onError(String userMessage, String technical);
    }

    /** Real synthesis with exactly ONE provider (no fallback chain, no cache) played through the speaker; callbacks arrive on the main thread. */
    CancelToken speakWith(String providerId, TtsRequest req, Test cb);

    /** The text exactly as it will be sent to a provider (normalisation, numbers, tags, pronunciation dictionary). */
    String prepareText(String text, String providerId);

    // ---- voice profiles (the voice the assistant uses; cloned voices are profiles too) --------------------------------
    final class Profile {
        public String id = "", name = "", providerId = "", voiceId = "";
        /** Cloning reference (sandbox-relative path of an audio file) and its transcript; empty for stock voices. */
        public String refAudioPath = "", refText = "";
        public float speed = 1f;
        public final Map<String, String> options = new HashMap<>();
        public boolean cloned;
        public long created;
        /** Where the reference sample was sent when this clone was made (shown to the user). */
        public String sentTo = "";
    }

    List<Profile> profiles();
    Profile activeProfile();
    /** null = no profile: the plain settings (tts.voice.<provider>) apply. */
    void setActiveProfile(String id);
    Profile saveProfile(Profile p);
    void deleteProfile(String id);

    /**
     * Creates a cloned-voice profile: sends the reference to {@code providerId}, synthesises a real test phrase with it and
     * only if that succeeds stores the profile. Slow providers (minutes) are allowed here; cancel via the token.
     */
    CancelToken makeClone(String providerId, String name, String refAudioPath, String refText, String testPhrase, Test cb);

    // ---- cache ----------------------------------------------------------------------------------------------------------
    long cacheBytes();
    void clearCache();

    // ---- pronunciation dictionary (JSON text, see core.voice.PronunciationDict) ----------------------------------------
    String pronunciationJson();
    void setPronunciationJson(String json);

    /** Convenience for UI lists. */
    static List<String> ids(List<? extends Object> l) { return new ArrayList<>(); }
}
