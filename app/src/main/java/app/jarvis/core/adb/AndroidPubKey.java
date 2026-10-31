package app.jarvis.core.adb;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;

/**
 * Encodes an RSA-2048 public key into adb's "AndroidPubKey" format — the 524-byte struct
 * {len; n0inv; n[256]; rr[256]; exponent} of little-endian words that adbkey.pub base64-wraps.
 * Verified byte-for-byte against the output of the real {@code adb} client.
 */
final class AndroidPubKey {
    private AndroidPubKey() { }

    static final int ENCODED_SIZE = 3 * 4 + 2 * 256;

    static byte[] encode(RSAPublicKey key) {
        BigInteger n = key.getModulus();
        BigInteger e = key.getPublicExponent();
        if (n.bitLength() != 2048) {
            throw new IllegalArgumentException("need a 2048-bit modulus, got " + n.bitLength());
        }
        byte[] nLe = unsigned256(n);                    // already little-endian
        byte[] rrLe = unsigned256(BigInteger.valueOf(2).pow(4096).mod(n));
        long n0inv = BigInteger.valueOf(2).pow(32).subtract(n.mod(BigInteger.valueOf(2).pow(32))
            .modInverse(BigInteger.valueOf(2).pow(32))).longValue();

        byte[] out = new byte[ENCODED_SIZE];
        putU32(out, 0, 64);            // len in 32-bit words
        putU32(out, 4, n0inv);
        System.arraycopy(nLe, 0, out, 8, 256);
        System.arraycopy(rrLe, 0, out, 8 + 256, 256);
        putU32(out, 8 + 512, e.longValue());
        return out;
    }

    /** adbkey.pub line: base64 of the struct + " " + user@host. */
    static String toAdbLine(RSAPublicKey key, String loginAtHost) {
        return java.util.Base64.getEncoder().encodeToString(encode(key)) + " " + loginAtHost;
    }

    /** PeerInfo payload: the adb line, NUL-terminated inside an 8192-byte buffer. */
    static byte[] peerInfoRsaPubKey(RSAPublicKey key, String loginAtHost) {
        byte[] line = toAdbLine(key, loginAtHost).getBytes(StandardCharsets.UTF_8);
        byte[] info = new byte[8192];
        info[0] = 0; // ADB_RSA_PUB_KEY
        System.arraycopy(line, 0, info, 1, Math.min(line.length, 8190));
        info[1 + Math.min(line.length, 8190)] = 0;
        return info;
    }

    private static byte[] unsigned256(BigInteger v) {
        byte[] be = v.toByteArray();
        byte[] out = new byte[256];
        for (int i = 0; i < be.length && i < 256; i++) {
            out[i] = be[be.length - 1 - i];
        }
        return out;
    }

    private static byte[] reverse(byte[] b) {
        byte[] out = new byte[b.length];
        for (int i = 0; i < b.length; i++) out[i] = b[b.length - 1 - i];
        return out;
    }

    private static void putU32(byte[] out, int off, long v) {
        out[off] = (byte) v;
        out[off + 1] = (byte) (v >> 8);
        out[off + 2] = (byte) (v >> 16);
        out[off + 3] = (byte) (v >> 24);
    }
}
