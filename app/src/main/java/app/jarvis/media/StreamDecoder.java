package app.jarvis.media;

import android.media.MediaCodec;
import android.media.MediaFormat;
import app.jarvis.core.audio.Mp3FrameSplitter;
import app.jarvis.core.audio.WavStream;
import app.jarvis.core.voice.AudioSpec;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Streaming decoder from arbitrary byte chunks to 16-bit little-endian PCM, driven by {@link AudioSpec}.
 * Used by {@link SpeechPlayer} (PCM goes into the AudioTrack) and by DecodeCheck (PCM goes into a counting
 * sink), so both exercise literally the same decode path.
 *
 * MP3: core.audio.Mp3FrameSplitter finds frames in the raw byte stream (ID3v2/garbage tolerant) and each frame
 * is fed to a MediaCodec "audio/mpeg" decoder. AAC_ADTS: an ADTS header splitter feeds raw frames to MediaCodec
 * "audio/mp4a-latm" with a csd-0 AudioSpecificConfig built from the first ADTS header. OGG_OPUS is not
 * implemented (clear error). WAV: core.audio.WavStream (streaming headers, data size 0/0xFFFFFFFF tolerated)
 * passes PCM straight through. Output rate/channels come from INFO_OUTPUT_FORMAT_CHANGED (or the stream
 * header) and reach the caller via {@link PcmOut#format}.
 *
 * All methods run on the producer's thread; {@link #abort()} may be called from any thread.
 */
public final class StreamDecoder {

    /** Receiver of the decoded PCM. format() is called once (again only on a mid-stream format change) before the first pcm(). */
    public interface PcmOut {
        void format(int sampleRate, int channels) throws IOException;

        void pcm(byte[] b, int off, int len) throws IOException;
    }

    private static final int[] AAC_RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};

    private final PcmOut out;
    private final byte[] scratch = new byte[8192];
    private MediaCodec codec;
    private MediaCodec.BufferInfo info;
    private WavStream wav;
    private Mp3FrameSplitter mp3;
    private AdtsSplitter adts;
    private long ptsUs;
    private int sentRate = -1, sentCh = -1;
    private boolean eosQueued, eosSeen;
    private volatile boolean aborted;

    public StreamDecoder(PcmOut out) { this.out = out; }

    public void begin(AudioSpec spec) throws IOException {
        try {
            if (spec == null) throw new IOException("Не задан формат аудиопотока (AudioSpec = null)");
            switch (spec.codec) {
                case PCM_S16LE:
                    sendFormat(spec.sampleRate > 0 ? spec.sampleRate : 24_000, spec.channels > 0 ? spec.channels : 1);
                    break;
                case WAV:
                    wav = new WavStream(new WavStream.Listener() {
                        @Override public void onFormat(int sampleRate, int channels, long dataSizeBytes) throws IOException {
                            sendFormat(sampleRate, channels);
                        }

                        @Override public void onPcm(byte[] b, int off, int len) throws IOException { out.pcm(b, off, len); }
                    });
                    break;
                case MP3:
                    mp3 = new Mp3FrameSplitter(new Mp3FrameSplitter.Listener() {
                        @Override public void onFrame(byte[] b, int off, int len, int sampleRate, int channels) {
                            try {
                                decodeFrame(b, off, len, sampleRate, channels, 1152);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                        }
                    });
                    break;
                case AAC_ADTS:
                    adts = new AdtsSplitter();
                    break;
                default:
                    throw new IOException("Формат " + spec.codec + " пока не поддерживается плеером речи");
            }
        } catch (RuntimeException e) {
            throw unwrap(e);
        }
    }

    public void feed(byte[] b, int off, int len) throws IOException {
        try {
            if (wav != null) wav.feed(b, off, len);
            else if (mp3 != null) mp3.feed(b, off, len);
            else if (adts != null) adts.feed(b, off, len);
            else if (sentRate > 0) out.pcm(b, off, len); // raw PCM passthrough
            else throw new IOException("Аудиопоток не инициализирован: begin() не вызывался");
        } catch (RuntimeException e) {
            throw unwrap(e);
        }
    }

    /** End of stream: flushes the last codec buffers. A decoder that produced no audio at all is an error. */
    public void end() throws IOException {
        try {
            if (wav != null) {
                wav.end();
            } else if (mp3 != null) {
                mp3.end();
                if (codec == null) throw new IOException("MP3-поток пуст или не содержит аудиокадров");
            } else if (adts != null) {
                adts.end();
                if (codec == null) throw new IOException("AAC-поток пуст или не содержит аудиокадров");
            }
            if (codec != null) finishCodec();
        } catch (RuntimeException e) {
            throw unwrap(e);
        }
    }

    /** Drop everything and free the codec. Safe to call at any time, from any thread, repeatedly. */
    public void abort() {
        aborted = true;
        releaseCodec();
    }

    private void decodeFrame(byte[] b, int off, int len, int sampleRate, int channels, int samplesHint) throws IOException {
        if (aborted) throw new IOException("stopped");
        ensureCodec("audio/mpeg", sampleRate, channels, null);
        queueFrame(b, off, len, samplesHint);
        drainOutputs(false);
    }

    private void ensureCodec(String mime, int rate, int channels, byte[] csd) throws IOException {
        if (codec != null) return;
        try {
            codec = MediaCodec.createDecoderByType(mime);
            MediaFormat f = MediaFormat.createAudioFormat(mime, Math.max(rate, 8000), Math.max(channels, 1));
            if (csd != null) f.setByteBuffer("csd-0", ByteBuffer.wrap(csd));
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384);
            codec.configure(f, null, null, 0);
            codec.start();
        } catch (Exception e) {
            releaseCodec();
            throw new IOException("Не удалось создать декодер " + friendly(mime) + " на этом устройстве (MediaCodec " + mime + "): " + e, e);
        }
    }

    private void queueFrame(byte[] b, int off, int len, int samplesHint) throws IOException {
        int idx = codec.dequeueInputBuffer(25_000);
        if (idx < 0) idx = codec.dequeueInputBuffer(100_000);
        if (idx < 0) return; // decoder backlog: drop this frame rather than grow latency
        ByteBuffer ib = codec.getInputBuffer(idx);
        ib.clear();
        int n = Math.min(len, ib.remaining());
        ib.put(b, off, n);
        codec.queueInputBuffer(idx, 0, n, ptsUs, 0);
        ptsUs += samplesHint * 1_000_000L / Math.max(1, sentRate); // monotonic PTS; the AudioTrack path ignores it
    }

    /** @return true if at least one buffer was processed (used to avoid a busy spin during the final drain). */
    private boolean drainOutputs(boolean finalDrain) throws IOException {
        if (codec == null) return false;
        if (info == null) info = new MediaCodec.BufferInfo();
        boolean any = false;
        while (true) {
            int oi;
            try {
                oi = codec.dequeueOutputBuffer(info, finalDrain && !any ? 50_000 : 0);
            } catch (MediaCodec.CodecException e) {
                throw new IOException("Ошибка декодера звука: " + e.getClass().getSimpleName(), e);
            }
            if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) break;
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = codec.getOutputFormat();
                sendFormat(of.getInteger(MediaFormat.KEY_SAMPLE_RATE), of.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                continue;
            }
            if (oi < 0) break;
            boolean last = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            if (last) eosSeen = true;
            if (info.size > 0) {
                if (sentRate <= 0) { // some decoders emit PCM without a FORMAT_CHANGED event
                    MediaFormat of = codec.getOutputFormat();
                    sendFormat(of.getInteger(MediaFormat.KEY_SAMPLE_RATE), of.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                }
                ByteBuffer ob = codec.getOutputBuffer(oi);
                ob.position(info.offset).limit(info.offset + info.size);
                while (ob.remaining() > 0) {
                    int n = Math.min(ob.remaining(), scratch.length);
                    ob.get(scratch, 0, n);
                    out.pcm(scratch, 0, n);
                }
            }
            codec.releaseOutputBuffer(oi, false);
            any = true;
            if (eosSeen) break;
        }
        return any;
    }

    private void finishCodec() throws IOException {
        long deadline = System.nanoTime() + 1_500_000_000L; // wedged decoder: give up instead of hanging forever
        while (!eosSeen && !aborted && System.nanoTime() < deadline) {
            if (!eosQueued) {
                try {
                    int idx = codec.dequeueInputBuffer(50_000);
                    if (idx >= 0) {
                        codec.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        eosQueued = true;
                    }
                } catch (Throwable t) {
                    eosQueued = true;
                }
            }
            drainOutputs(true);
            if (!eosSeen && eosQueued) {
                try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
        releaseCodec();
    }

    private void sendFormat(int rate, int channels) throws IOException {
        if (rate <= 0 || channels <= 0) return;
        if (rate == sentRate && channels == sentCh) return;
        sentRate = rate;
        sentCh = channels;
        out.format(rate, channels);
    }

    private void releaseCodec() {
        MediaCodec c = codec;
        codec = null;
        eosQueued = false;
        eosSeen = false;
        if (c == null) return;
        try { c.stop(); } catch (Throwable ignored) { }
        try { c.release(); } catch (Throwable ignored) { }
    }

    private static IOException unwrap(RuntimeException e) {
        Throwable c = e;
        while (c != null) {
            if (c instanceof IOException) return (IOException) c;
            c = c.getCause();
        }
        throw e;
    }

    private static String friendly(String mime) {
        return "audio/mpeg".equals(mime) ? "MP3" : "audio/mp4a-latm".equals(mime) ? "AAC" : mime;
    }

    /** Splits an ADTS stream (sync 0xFFF, 7- or 9-byte headers, garbage tolerant) into raw AAC frames. */
    private final class AdtsSplitter {
        private byte[] buf = new byte[8192];
        private int start, end;
        private boolean started;

        void feed(byte[] b, int off, int len) throws IOException {
            if (end + len > buf.length) {
                System.arraycopy(buf, start, buf, 0, end - start);
                end -= start;
                start = 0;
                if (end + len > buf.length) {
                    byte[] nb = new byte[Math.max(buf.length * 2, end + len)];
                    System.arraycopy(buf, 0, nb, 0, end);
                    buf = nb;
                }
            }
            System.arraycopy(b, off, buf, end, len);
            end += len;
            process();
        }

        void end() { /* a trailing partial frame is dropped */ }

        private void process() throws IOException {
            while (end - start >= 6) {
                if ((buf[start] & 0xFF) != 0xFF || (buf[start + 1] & 0xF0) != 0xF0) { start++; continue; } // resync
                int sfIdx = (buf[start + 2] >> 2) & 0x0F;
                if (sfIdx >= 13) { start++; continue; }
                int hdrlen = (buf[start + 1] & 0x01) != 0 ? 7 : 9;
                if (end - start < hdrlen + 1) return;
                int frameLen = ((buf[start + 3] & 0x03) << 11) | ((buf[start + 4] & 0xFF) << 3) | ((buf[start + 5] >> 5) & 0x07);
                if (frameLen < hdrlen + 2 || frameLen > 8192) { start++; continue; }
                if (end - start < frameLen) return; // wait for the whole frame
                if (!started) {
                    started = true;
                    int profile = ((buf[start + 2] >> 6) & 0x03) + 1; // ADTS profile + 1 = audio object type
                    int ch = ((buf[start + 2] & 0x01) << 2) | ((buf[start + 3] >> 6) & 0x03);
                    byte[] csd = {(byte) ((profile << 3) | (sfIdx >> 1)), (byte) (((sfIdx & 1) << 7) | (ch << 3))};
                    ensureCodec("audio/mp4a-latm", AAC_RATES[sfIdx], Math.max(1, ch), csd);
                }
                queueFrame(buf, start + hdrlen, frameLen - hdrlen, 1024);
                drainOutputs(false);
                start += frameLen;
            }
        }
    }
}
