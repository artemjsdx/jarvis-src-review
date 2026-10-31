package app.jarvis.core.adb;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import javax.crypto.AEADBadTagException;
import org.junit.Test;

public class HkdfAesGcmTest {
    @Test
    public void rfc5869_testCase1() {
        byte[] ikm = new byte[22]; java.util.Arrays.fill(ikm, (byte) 0x0b);
        byte[] salt = hex("000102030405060708090a0b0c");
        byte[] info = hex("f0f1f2f3f4f5f6f7f8f9");
        byte[] okm = Hkdf.hkdfSha256(ikm, salt, info, 42);
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            hex2(okm));
    }

    @Test
    public void hkdf_emptySaltMatchesZeroSalt() {
        byte[] a = Hkdf.hkdfSha256("ikm".getBytes(StandardCharsets.UTF_8), null, "info".getBytes(), 32);
        byte[] b = Hkdf.hkdfSha256("ikm".getBytes(StandardCharsets.UTF_8), new byte[0], "info".getBytes(), 32);
        assertArrayEquals(a, b);
    }

    @Test
    public void gcm_counterNonceRoundTrip() throws Exception {
        AesGcm enc = AesGcm.fromSpakeKey(new byte[64]);
        AesGcm dec = AesGcm.fromSpakeKey(new byte[64]);
        byte[] m1 = enc.encrypt("first".getBytes(StandardCharsets.UTF_8));
        byte[] m2 = enc.encrypt("second".getBytes(StandardCharsets.UTF_8));
        assertEquals("first", new String(dec.decrypt(m1), StandardCharsets.UTF_8));
        assertEquals("second", new String(dec.decrypt(m2), StandardCharsets.UTF_8));
    }

    @Test
    public void gcm_tamperFails() throws Exception {
        AesGcm g = AesGcm.fromSpakeKey(new byte[64]);
        byte[] c = g.encrypt("secret".getBytes(StandardCharsets.UTF_8));
        c[0] ^= 1;
        assertNull(g.decrypt(c));
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static String hex2(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
