package app.jarvis.core.adb;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** ADB wire framing: the 24-byte little-endian message header from adb.h. */
final class AdbProtocol {
    static final int A_CNXN = 0x4e584e43;
    static final int A_OPEN = 0x4e45504f;
    static final int A_OKAY = 0x59414b4f;
    static final int A_CLSE = 0x45534c43;
    static final int A_WRTE = 0x45545257;
    static final int A_AUTH = 0x48545541;
    static final int A_STLS = 0x534c5453;

    static final int A_VERSION = 0x01000001;
    static final int A_STLS_VERSION = 0x01000000;

    static final int ADB_AUTH_TOKEN = 1;
    static final int ADB_AUTH_SIGNATURE = 2;
    static final int ADB_AUTH_RSAPUBLICKEY = 3;

    static final int HEADER_LEN = 24;
    /** adbd's limit; we advertise a smaller one. */
    static final int DEVICE_MAX_PAYLOAD = 1024 * 1024;
    static final int OUR_MAX_PAYLOAD = 256 * 1024;

    private AdbProtocol() { }

    static final class Message {
        int command;
        int arg0;
        int arg1;
        byte[] payload = new byte[0];

        static Message of(int command, int arg0, int arg1, byte[] payload) {
            Message m = new Message();
            m.command = command;
            m.arg0 = arg0;
            m.arg1 = arg1;
            m.payload = payload == null ? new byte[0] : payload;
            return m;
        }

        long checksum() {
            long sum = 0;
            for (byte b : payload) sum += b & 0xffL;
            return sum & 0xffffffffL;
        }

        String commandName() {
            String s = String.format("%c%c%c%c", (command) & 0xff, (command >> 8) & 0xff,
                (command >> 16) & 0xff, (command >> 24) & 0xff);
            return s;
        }
    }

    static void write(OutputStream out, Message m) throws IOException {
        byte[] h = new byte[HEADER_LEN];
        putU32(h, 0, m.command);
        putU32(h, 4, m.arg0);
        putU32(h, 8, m.arg1);
        putU32(h, 12, m.payload.length);
        putU32(h, 16, m.checksum());
        putU32(h, 20, m.command ^ 0xffffffffL);
        out.write(h);
        if (m.payload.length > 0) out.write(m.payload);
        out.flush();
    }

    /** Reads one message; honors {@code deadlineMs} (absolute) via the socket's SO_TIMEOUT. */
    static Message read(InputStream in, int maxPayload) throws IOException {
        byte[] h = readFully(in, HEADER_LEN);
        Message m = new Message();
        m.command = (int) getU32(h, 0);
        m.arg0 = (int) getU32(h, 4);
        m.arg1 = (int) getU32(h, 8);
        int len = (int) getU32(h, 12);
        long checksum = getU32(h, 16);
        long magic = getU32(h, 20);
        if ((m.command ^ 0xffffffffL) != magic) {
            throw new IOException("adb: bad message magic " + String.format("%08x", m.command));
        }
        if (len < 0 || len > maxPayload) {
            throw new IOException("adb: oversized payload " + len);
        }
        if (len > 0) {
            m.payload = readFully(in, len);
            long actual = m.checksum();
            // A_VERSION_SKIP_CHECKSUM (0x01000001) peers may send a zero/ignored checksum.
            if (checksum != 0 && checksum != actual) {
                throw new IOException("adb: checksum mismatch (hdr=" + checksum + " act=" + actual + ")");
            }
        }
        return m;
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new EOFException("adb: stream closed (" + off + "/" + n + " bytes)");
            off += r;
        }
        return buf;
    }

    static void putU32(byte[] b, int off, long v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    static long getU32(byte[] b, int off) {
        return (b[off] & 0xffL) | ((b[off + 1] & 0xffL) << 8) | ((b[off + 2] & 0xffL) << 16)
            | ((b[off + 3] & 0xffL) << 24);
    }
}
