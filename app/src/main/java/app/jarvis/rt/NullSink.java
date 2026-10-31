package app.jarvis.rt;

import app.jarvis.core.voice.AudioSink;
import app.jarvis.core.voice.AudioSpec;

/** Discards audio but counts it (cache pre-warming, provider checks without playback). */
public final class NullSink implements AudioSink {
    public long bytes;
    public AudioSpec spec;
    @Override public void begin(AudioSpec s) { spec = s; }
    @Override public void write(byte[] d, int o, int l) { bytes += l; }
    @Override public void end() { }
    @Override public void abort() { }
}
