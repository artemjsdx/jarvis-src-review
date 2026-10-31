package app.jarvis.audio;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import app.jarvis.core.voice.AudioPacker;
import app.jarvis.core.voice.PcmAudio;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Device encoder for speech uploads: OGG/Opus (16 kbit/s mono, MediaCodec + MediaMuxer, API 29+) and FLAC (MediaCodec,
 * API 27+). Anything else, and every failure, is answered with WAV so an utterance is never lost; the reason of the last
 * failure is kept in {@link #lastError}. The capability probe runs once and is cached. Encoding blocks: call it off the
 * UI thread.
 */
public final class AudioEncoder implements AudioPacker {
    private static final int RATE = 16000;
    private static final int OPUS_BITRATE = 16000;
    private static final long TIMEOUT_MS = 4000;
    private static final long IDLE_WAIT_US = 2000;

    /** Receives the codec output: the output format once, then every encoded buffer. */
    private interface Sink {
        void format(MediaFormat f) throws IOException;
        void data(ByteBuffer buf, MediaCodec.BufferInfo info) throws IOException;
    }

    private final File tmpDir;
    private volatile Set<String> available;
    /** Why the last encode fell back to WAV ("" when none happened). For traces and the on-device check. */
    public volatile String lastError = "";

    public AudioEncoder(File tmpDir) {
        this.tmpDir = tmpDir;
    }

    @Override public Set<String> available() {
        Set<String> s = available;
        if (s == null) {
            synchronized (this) {
                if (available == null) available = probe();
                s = available;
            }
        }
        return s;
    }

    static Set<String> probe() {
        Set<String> out = new HashSet<>();
        out.add(WAV);
        try {
            if (Build.VERSION.SDK_INT >= 29 && hasEncoder("audio/opus")) out.add(OPUS);
            if (Build.VERSION.SDK_INT >= 27 && hasEncoder("audio/flac")) out.add(FLAC);
        } catch (RuntimeException ignored) {
            // a broken codec list leaves WAV only
        }
        return Collections.unmodifiableSet(out);
    }

    private static boolean hasEncoder(String mime) {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) if (type.equalsIgnoreCase(mime)) return true;
        }
        return false;
    }

    @Override public Packed pack(PcmAudio audio, String format) {
        if (OPUS.equals(format) && available().contains(OPUS)) {
            try {
                return new Packed(OPUS, "audio/ogg", "audio.ogg", encodeOpus(audio), "");
            } catch (IOException | RuntimeException | LinkageError e) {
                return fallback(audio, format, e);
            }
        }
        if (FLAC.equals(format) && available().contains(FLAC)) {
            try {
                return new Packed(FLAC, "audio/flac", "audio.flac", encodeFlac(audio), "");
            } catch (IOException | RuntimeException | LinkageError e) {
                return fallback(audio, format, e);
            }
        }
        return Packed.wav(audio, "");
    }

    private Packed fallback(PcmAudio audio, String format, Throwable e) {
        lastError = format + ": " + e;
        return Packed.wav(audio, format);
    }

    private byte[] encodeOpus(final PcmAudio audio) throws IOException {
        MediaFormat format = MediaFormat.createAudioFormat("audio/opus", RATE, 1);
        format.setInteger(MediaFormat.KEY_BIT_RATE, OPUS_BITRATE);
        final File out = File.createTempFile("stt-", ".ogg", tmpDir);
        MediaCodec codec = MediaCodec.createEncoderByType("audio/opus");
        MediaMuxer muxer = null;
        try {
            muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG);
            final MediaMuxer mux = muxer;
            final int[] track = {-1};
            runCodec(codec, format, audio.pcm, new Sink() {
                @Override public void format(MediaFormat f) {
                    track[0] = mux.addTrack(f);
                    mux.start();
                }
                @Override public void data(ByteBuffer buf, MediaCodec.BufferInfo info) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return;
                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);
                    mux.writeSampleData(track[0], buf, info);
                }
            });
            muxer.stop();
            return readFile(out);
        } finally {
            codec.release();
            if (muxer != null) muxer.release();
            out.delete();
        }
    }

    private byte[] encodeFlac(final PcmAudio audio) throws IOException {
        MediaFormat format = MediaFormat.createAudioFormat("audio/flac", RATE, 1);
        format.setInteger("flac-compression-level", 0);
        MediaCodec codec = MediaCodec.createEncoderByType("audio/flac");
        final ByteArrayOutputStream out = new ByteArrayOutputStream(audio.pcm.length / 2);
        try {
            runCodec(codec, format, audio.pcm, new Sink() {
                @Override public void format(MediaFormat f) { }
                @Override public void data(ByteBuffer buf, MediaCodec.BufferInfo info) {
                    append(out, buf, info.offset, info.size);
                }
            });
            return out.toByteArray();
        } finally {
            codec.release();
        }
    }

    /**
     * Feeds the PCM in codec-sized chunks and drains the output until end-of-stream. The loop never sleeps while it makes
     * progress; it blocks for at most {@link #IDLE_WAIT_US} when the codec has nothing to give.
     */
    private static void runCodec(MediaCodec codec, MediaFormat format, byte[] pcm, Sink sink) throws IOException {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start();
        try {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int offset = 0;
            boolean inputDone = false, outputDone = false, formatSeen = false;
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (!outputDone) {
                if (System.currentTimeMillis() > deadline) throw new IOException("encoder timeout");
                boolean progress = false;
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(0);
                    if (in >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(in);
                        long ptsUs = offset / 2 * 1_000_000L / RATE;
                        if (offset < pcm.length) {
                            int n = Math.min(buf.capacity(), pcm.length - offset);
                            buf.clear();
                            buf.put(pcm, offset, n);
                            codec.queueInputBuffer(in, 0, n, ptsUs, 0);
                            offset += n;
                        } else {
                            codec.queueInputBuffer(in, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        }
                        progress = true;
                    }
                }
                int o = codec.dequeueOutputBuffer(info, progress ? 0 : IDLE_WAIT_US);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!formatSeen) { sink.format(codec.getOutputFormat()); formatSeen = true; }
                    progress = true;
                } else if (o >= 0) {
                    ByteBuffer buf = codec.getOutputBuffer(o);
                    if (info.size > 0) {
                        if (!formatSeen) throw new IOException("encoder produced data before its format");
                        sink.data(buf, info);
                    }
                    codec.releaseOutputBuffer(o, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                    progress = true;
                }
            }
            if (!formatSeen) throw new IOException("encoder produced no output");
            codec.stop();
        } catch (IllegalStateException e) {
            throw new IOException("encoder state: " + e.getMessage(), e);
        }
    }

    private static void append(ByteArrayOutputStream out, ByteBuffer buf, int offset, int size) {
        byte[] bytes = new byte[size];
        ByteBuffer dup = buf.duplicate();
        dup.position(offset);
        dup.get(bytes);
        out.write(bytes, 0, bytes.length);
    }

    private static byte[] readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(1024, f.length()));
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) bos.write(b, 0, n);
            return bos.toByteArray();
        }
    }
}
