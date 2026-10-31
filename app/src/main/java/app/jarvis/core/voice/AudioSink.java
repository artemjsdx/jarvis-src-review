package app.jarvis.core.voice;

import java.io.IOException;

/**
 * Receives one utterance of audio from a TtsProvider. Implemented by the Android player (decodes + plays while bytes
 * still arrive) and by test/cache sinks (write to a file). Called on the provider's worker thread; write() may block
 * (back-pressure) and must return promptly after abort().
 */
public interface AudioSink {
    /** Once, before the first write. */
    void begin(AudioSpec spec) throws IOException;

    void write(byte[] data, int off, int len) throws IOException;

    /** Normal end of stream: everything already written should be played out. */
    void end() throws IOException;

    /** Error or cancellation: drop whatever is pending and release resources. Safe to call at any time, repeatedly. */
    void abort();
}
