package app.jarvis.core.voice;

/**
 * Marker for providers that take minutes per phrase (e.g. F5-TTS studio cloning).
 * {@link ProviderChain} never routes interactive requests to them — callers that
 * explicitly want slow studio synthesis use the provider directly.
 */
public interface SlowProvider extends TtsProvider {
    /** Always true; present so a wrapper/registry can re-flag a provider if needed. */
    default boolean slow() { return true; }
}
