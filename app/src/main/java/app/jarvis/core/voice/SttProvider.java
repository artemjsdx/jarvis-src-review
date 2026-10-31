package app.jarvis.core.voice;

import app.jarvis.core.util.CancelToken;

public interface SttProvider {
    ProviderInfo info();

    void configure(ProviderConfig cfg);

    boolean isConfigured();

    /** Blocking. Typical audio: one utterance of 1–15 s at 16 kHz. */
    SttResult transcribe(PcmAudio audio, SttOptions opts, CancelToken cancel) throws VoiceException;

    ProviderStatus lastStatus();

    /** Transcribes the bundled Russian sample ("Привет, я Джарвис ...") for real and compares with the expected words. Never throws. */
    ProviderStatus check(PcmAudio sample, CancelToken cancel);
}
