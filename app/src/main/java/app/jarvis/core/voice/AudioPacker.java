package app.jarvis.core.voice;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns 16 kHz mono PCM into the container an STT provider accepts. The core never encodes anything itself:
 * the Android build plugs in a MediaCodec-backed packer, the default packer only writes WAV.
 */
public interface AudioPacker {
    String WAV = "wav", FLAC = "flac", OPUS = "opus";
    /** Order used by the plain {@link #choose} (no timing information): smallest upload first. {@link #chooseFor} replaces it for real uploads. */
    List<String> PRIORITY = Collections.unmodifiableList(Arrays.asList(OPUS, FLAC, WAV));

    /** Formats this packer can produce right now. Always contains {@link #WAV}. */
    Set<String> available();

    /**
     * Encodes into {@code format}. Never throws: on any encoder failure the WAV container is returned and
     * {@link Packed#fellBackFrom} names the format that was requested.
     */
    Packed pack(PcmAudio audio, String format);

    /** Chooses the format for this phrase and the current uplink (see {@link #chooseFor}) and packs it. */
    default Packed packFor(PcmAudio audio, String preference, List<String> accepted) {
        return pack(audio, chooseFor(preference, accepted, available(), audio.durationMs(), UplinkMeter.bytesPerSec()));
    }

    AudioPacker WAV_ONLY = new AudioPacker() {
        @Override public Set<String> available() { return Collections.singleton(WAV); }
        @Override public Packed pack(PcmAudio audio, String format) { return Packed.wav(audio, ""); }
    };

    /**
     * The format to send. An explicit {@code preference} wins when the provider accepts it and it is available here;
     * otherwise the first of {@link #PRIORITY} that is both accepted and available; WAV as the last resort.
     */
    static String choose(String preference, List<String> accepted, Set<String> available) {
        String pref = preference == null ? "auto" : preference.trim().toLowerCase(Locale.ROOT);
        if (!pref.equals("auto") && accepted.contains(pref) && available.contains(pref)) return pref;
        for (String f : PRIORITY) if (accepted.contains(f) && available.contains(f)) return f;
        return WAV;
    }

    /** Assumed upload speed (bytes/s, about 1.6 Mbit/s) until the first upload has been measured. */
    double DEFAULT_UPLINK_BPS = 200_000;

    /**
     * Time in ms to get {@code durationMs} of speech to the provider in {@code format}: encoding plus transfer at {@code bytesPerSec}.
     * Encoding costs are measured on the owner's phone (docs/SPEED.md 9a: FLAC about 0.11 s and Opus 0.45-1.2 s for 8 s of speech, most of
     * Opus being fixed codec start-up); sizes: WAV 32 KB/s, FLAC about 40 % of that, Opus 16 kbit/s.
     */
    static double costMs(String format, long durationMs, double bytesPerSec) {
        double sec = durationMs / 1000.0, encodeMs, bytes;
        if (FLAC.equals(format)) { encodeMs = 90 + 4 * sec; bytes = 0.40 * (32_000 * sec + 44); }
        else if (OPUS.equals(format)) { encodeMs = 280 + 45 * sec; bytes = 2_000 * sec + 1_200; }
        else { encodeMs = 0; bytes = 32_000 * sec + 44; }
        return encodeMs + bytes * 1000.0 / Math.max(1_000, bytesPerSec);
    }

    /**
     * Format for "auto": the one with the least estimated time to deliver (encode plus upload). A fast link gets WAV, a middling one FLAC, a slow
     * one Opus; before anything is measured the default speed applies (FLAC for a typical phrase). An explicit preference that the provider
     * accepts and this device can produce still wins. Ties go to the simpler container.
     */
    static String chooseFor(String preference, List<String> accepted, Set<String> available, long durationMs, double uplinkBytesPerSec) {
        String pref = preference == null ? "auto" : preference.trim().toLowerCase(Locale.ROOT);
        if (!pref.equals("auto") && accepted.contains(pref) && available.contains(pref)) return pref;
        double bps = uplinkBytesPerSec > 0 ? uplinkBytesPerSec : DEFAULT_UPLINK_BPS;
        String best = WAV;
        double bestCost = Double.MAX_VALUE;
        for (String f : Arrays.asList(WAV, FLAC, OPUS)) {
            if (!accepted.contains(f) || !available.contains(f)) continue;
            double c = costMs(f, durationMs, bps);
            if (c < bestCost) { bestCost = c; best = f; }
        }
        return best;
    }

    /** An encoded upload body plus what the provider needs to name it. */
    final class Packed {
        public final String format;
        public final String mime;
        public final String fileName;
        public final byte[] bytes;
        /** Requested format when encoding failed and WAV was sent instead; "" otherwise. */
        public final String fellBackFrom;

        public Packed(String format, String mime, String fileName, byte[] bytes, String fellBackFrom) {
            this.format = format; this.mime = mime; this.fileName = fileName; this.bytes = bytes; this.fellBackFrom = fellBackFrom;
        }

        public static Packed wav(PcmAudio audio, String fellBackFrom) {
            return new Packed(WAV, "audio/wav", "audio.wav", audio.toWav(), fellBackFrom);
        }

        /** Short human label for logs: "wav 237 КБ", "ogg/opus 11 КБ". */
        public String label() {
            String name = OPUS.equals(format) ? "ogg/opus" : format;
            return name + " " + Math.max(1, bytes.length / 1024) + " КБ";
        }
    }
}
