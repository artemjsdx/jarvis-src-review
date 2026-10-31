package app.jarvis.core.voice;

/** Describes the byte stream a provider pushes into an {@link AudioSink}. */
public final class AudioSpec {
    public enum Codec { PCM_S16LE, MP3, WAV, OGG_OPUS, AAC_ADTS }

    public final Codec codec;
    /** Meaningful for PCM_S16LE (raw). For container/compressed codecs the decoder learns it from the stream (0 = unknown). */
    public final int sampleRate;
    public final int channels;

    public AudioSpec(Codec codec, int sampleRate, int channels) {
        this.codec = codec;
        this.sampleRate = sampleRate;
        this.channels = channels;
    }

    public static AudioSpec pcm(int rate) { return new AudioSpec(Codec.PCM_S16LE, rate, 1); }
    public static AudioSpec mp3() { return new AudioSpec(Codec.MP3, 0, 0); }
    public static AudioSpec wav() { return new AudioSpec(Codec.WAV, 0, 0); }

    @Override public String toString() { return codec + (sampleRate > 0 ? "@" + sampleRate : ""); }
}
