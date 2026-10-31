package app.jarvis.core.voice;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * multipart/form-data writer (RFC 7578) for audio uploads (ElevenLabs STT/STS, Groq Whisper).
 * Streams part-by-part into the connection's output stream, so the caller never holds two copies of
 * the payload. Bytes are supplied as byte[]; order of parts is preserved.
 */
public final class HttpMultipart {
    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final SecureRandom RND = new SecureRandom();

    private HttpMultipart() { }

    /** One form field or one file. */
    public static final class Part {
        public final String name, filename, contentType;
        public final byte[] data;

        /** Plain text field. */
        public Part(String name, String value) {
            this(name, null, null, value.getBytes(StandardCharsets.UTF_8));
        }

        /** File part; filename and contentType are placed in the headers (may be null). */
        public Part(String name, String filename, String contentType, byte[] data) {
            this.name = name;
            this.filename = filename;
            this.contentType = contentType;
            this.data = data;
        }
    }

    /** Random boundary (24 hex chars); quoted by the caller in Content-Type. */
    public static String newBoundary() {
        byte[] b = new byte[12];
        RND.nextBytes(b);
        StringBuilder sb = new StringBuilder(24);
        for (byte x : b) { sb.append(HEX[(x >> 4) & 0xf]).append(HEX[x & 0xf]); }
        return sb.toString();
    }

    public static String contentType(String boundary) { return "multipart/form-data; boundary=" + boundary; }

    public static long length(String boundary, Part... parts) {
        long n = 0;
        for (Part p : parts) n += headersLength(boundary, p) + p.data.length + 2;
        return n + boundary.length() + 6;
    }

    /** Writes all parts including the final closing boundary; does not close the stream. */
    public static void write(OutputStream out, String boundary, Part... parts) throws IOException {
        for (Part p : parts) {
            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(headers(boundary, p));
            out.write(p.data);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
    }

    /** Convenience for small bodies (tests): builds the whole body in memory. */
    public static byte[] build(Part... parts) {
        String boundary = newBoundary();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(Integer.MAX_VALUE, length(boundary, parts)));
            write(out, boundary, parts);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e); // impossible for ByteArrayOutputStream
        }
    }

    /** Builds with a fixed boundary (tests assert on it). */
    public static byte[] build(String boundary, Part... parts) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(Integer.MAX_VALUE, length(boundary, parts)));
            write(out, boundary, parts);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] headers(String boundary, Part p) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("Content-Disposition: form-data; name=\"").append(p.name).append("\"");
        if (p.filename != null) sb.append("; filename=\"").append(p.filename).append("\"");
        sb.append("\r\n");
        if (p.contentType != null) sb.append("Content-Type: ").append(p.contentType).append("\r\n");
        sb.append("\r\n");
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static int headersLength(String boundary, Part p) {
        return headers(boundary, p).length + ("--" + boundary + "\r\n").length();
    }
}
