package app.jarvis.core.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** The 44-byte RIFF/WAVE header for 16-bit PCM, written before the data size is known (streamed files patch it later). */
public final class WavIo {
    public static final int HEADER_BYTES = 44;

    private WavIo() { }

    public static byte[] header(int sampleRate, int channels, long dataBytes) {
        ByteBuffer b = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        int ch = Math.max(1, channels);
        b.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt((int) Math.min(Integer.MAX_VALUE, 36 + dataBytes))
            .put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16)
            .putShort((short) 1).putShort((short) ch).putInt(sampleRate).putInt(sampleRate * ch * 2).putShort((short) (ch * 2)).putShort((short) 16)
            .put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt((int) Math.min(Integer.MAX_VALUE, dataBytes));
        return b.array();
    }
}
