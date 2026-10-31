package app.jarvis.core.voice;

import app.jarvis.core.util.CancelToken;
import java.util.List;

public interface TtsProvider {
    ProviderInfo info();

    /** Called at startup and whenever settings change; must be cheap and must not touch the network. */
    void configure(ProviderConfig cfg);

    /** False when a required key/setting is missing. */
    boolean isConfigured();

    /** Voices to offer in the UI. May use the network; implementations cache the result. */
    List<Voice> listVoices(CancelToken cancel) throws VoiceException;

    /** Blocking. Stream audio into sink as soon as it arrives; call sink.begin once, write..., sink.end (or sink.abort on failure). */
    void synthesize(TtsRequest req, AudioSink sink, CancelToken cancel) throws VoiceException;

    ProviderStatus lastStatus();

    /** A REAL synthesis of a short phrase into a throw-away sink; measures latency; updates lastStatus(). Never throws. */
    ProviderStatus check(String phrase, String voiceId, CancelToken cancel);

    /** Human-readable remaining quota ("осталось 9 120 из 10 000 символов") or null when unknown/not applicable. */
    String quotaText(CancelToken cancel);
}
