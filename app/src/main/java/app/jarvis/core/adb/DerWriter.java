package app.jarvis.core.adb;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;

/** Tiny DER writer — enough for a self-signed RSA X.509 certificate (no external jars). */
final class DerWriter {
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

    DerWriter tagged(int tag, byte[] content) {
        buf.write(tag);
        writeLength(content.length);
        write(content);
        return this;
    }

    DerWriter seq(DerWriter... children) {
        return tagged(0x30, concat(children));
    }

    DerWriter set(DerWriter child) {
        return tagged(0x31, child.encoded());
    }

    DerWriter integer(long v) {
        return integer(BigInteger.valueOf(v));
    }

    DerWriter integer(BigInteger v) {
        byte[] be = v.toByteArray(); // already minimal + sign byte
        return tagged(0x02, be);
    }

    DerWriter integerUnsigned(byte[] magnitude) {
        byte[] out = magnitude;
        if ((out[0] & 0x80) != 0) {
            out = new byte[out.length + 1];
            System.arraycopy(magnitude, 0, out, 1, magnitude.length);
        }
        return tagged(0x02, out);
    }

    DerWriter bitString(byte[] content, int unusedBits) {
        byte[] out = new byte[content.length + 1];
        out[0] = (byte) unusedBits;
        System.arraycopy(content, 0, out, 1, content.length);
        return tagged(0x03, out);
    }

    DerWriter octetString(byte[] content) {
        return tagged(0x04, content);
    }

    DerWriter nul() {
        return tagged(0x05, new byte[0]);
    }

    DerWriter oid(String dotted) {
        String[] parts = dotted.split("\\.");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(Integer.parseInt(parts[0]) * 40 + Integer.parseInt(parts[1]));
        for (int i = 2; i < parts.length; i++) {
            long v = Long.parseLong(parts[i]);
            byte[] tmp = new byte[8];
            int n = 0;
            tmp[n++] = (byte) (v & 0x7f);
            v >>= 7;
            while (v > 0) {
                tmp[n++] = (byte) ((v & 0x7f) | 0x80);
                v >>= 7;
            }
            for (int j = n - 1; j >= 0; j--) b.write(tmp[j]);
        }
        return tagged(0x06, b.toByteArray());
    }

    DerWriter utcTime(String yyMmDdHhMmSsZ) {
        return tagged(0x17, ascii(yyMmDdHhMmSsZ));
    }

    DerWriter printableString(String s) {
        return tagged(0x13, ascii(s));
    }

    DerWriter bool(boolean v) {
        return tagged(0x01, new byte[]{(byte) (v ? 0xff : 0x00)});
    }

    DerWriter explicit(int tagNo, DerWriter child) {
        return tagged(0xa0 | tagNo, child.encoded());
    }

    byte[] encoded() {
        return buf.toByteArray();
    }

    private void writeLength(int len) {
        if (len < 128) {
            buf.write(len);
        } else if (len < 256) {
            buf.write(0x81);
            buf.write(len);
        } else {
            buf.write(0x82);
            buf.write(len >> 8);
            buf.write(len & 0xff);
        }
    }

    private void write(byte[] b) {
        buf.write(b, 0, b.length);
    }

    private static byte[] ascii(String s) {
        byte[] out = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) out[i] = (byte) s.charAt(i);
        return out;
    }

    private static byte[] concat(DerWriter... children) {
        int n = 0;
        for (DerWriter c : children) n += c.encoded().length;
        byte[] out = new byte[n];
        int o = 0;
        for (DerWriter c : children) {
            byte[] e = c.encoded();
            System.arraycopy(e, 0, out, o, e.length);
            o += e.length;
        }
        return out;
    }
}
