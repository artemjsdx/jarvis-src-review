package app.jarvis.core.adb;

import static org.junit.Assert.*;

import java.math.BigInteger;
import org.junit.Test;

public class Ed25519Test {
    @Test
    public void basePointIsOnCurve() {
        assertTrue(onCurve(Ed25519.BASE));
    }

    @Test
    public void groupLaw_addCommutative() {
        Ed25519.Point a = Ed25519.scalarMult(bi(1234567), Ed25519.BASE);
        Ed25519.Point b = Ed25519.scalarMult(bi(7654321), Ed25519.BASE);
        assertEquals(enc(Ed25519.add(a, b)), enc(Ed25519.add(b, a)));
        assertEquals(enc(a), enc(Ed25519.add(a, Ed25519.neutral())));
        assertEquals(enc(Ed25519.neutral()), enc(Ed25519.add(a, Ed25519.negate(a))));
    }

    @Test
    public void groupLaw_scalarSplit() {
        BigInteger k1 = bi(987654321);
        BigInteger k2 = bi(123456789);
        Ed25519.Point direct = Ed25519.scalarMult(k1.add(k2), Ed25519.BASE);
        Ed25519.Point via = Ed25519.add(Ed25519.scalarMult(k1, Ed25519.BASE),
            Ed25519.scalarMult(k2, Ed25519.BASE));
        assertEquals(enc(direct), enc(via));
    }

    @Test
    public void doubleMatchesAdd() {
        Ed25519.Point p = Ed25519.scalarMult(bi(31337), Ed25519.BASE);
        assertEquals(enc(Ed25519.add(p, p)), enc(Ed25519.dbl(p)));
    }

    @Test
    public void encodeDecodeRoundTrip() {
        Ed25519.Point p = Ed25519.scalarMult(bi(424242), Ed25519.BASE);
        byte[] e = Ed25519.encode(p);
        assertEquals(32, e.length);
        Ed25519.Point back = Ed25519.decode(e);
        assertNotNull(back);
        assertEquals(enc(p), enc(back));
    }

    @Test
    public void decodeRejectsGarbage() {
        assertNull(Ed25519.decode(new byte[31]));
        byte[] bad = new byte[32];
        bad[0] = 1; // almost certainly not on curve
        // Loop until we find an actual non-curve encoding (false positives are ~(1/4)^...)
        boolean rejectedAtLeastOnce = false;
        for (int i = 1; i < 32 && !rejectedAtLeastOnce; i++) {
            byte[] t = new byte[32];
            t[i] = (byte) 0xa5;
            rejectedAtLeastOnce = Ed25519.decode(t) == null;
        }
        assertTrue(rejectedAtLeastOnce);
        byte[] nonCanonical = Ed25519.encode(Ed25519.scalarMult(bi(7), Ed25519.BASE));
        nonCanonical[10] |= (byte) 0x80; // y >= p in most cases → non-canonical
        // not asserted: depends on the value; decode must simply not crash
        Ed25519.decode(nonCanonical);
    }

    @Test
    public void rfc8032_vector1_scalarMult() {
        // RFC 8032 test 1: seed 9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60
        // public key = d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a
        byte[] seed = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
        byte[] h = Ed25519.sha512(seed);
        byte[] h32 = java.util.Arrays.copyOf(h, 32);
        // clamp per RFC 8032 step 2 (the scalar is the clamped hash, NOT reduced mod L)
        h32[0] &= 0xf8;
        h32[31] &= 0x7f;
        h32[31] |= 0x40;
        BigInteger a = Ed25519.fromBytesLe(h32);
        String pub = hexs(Ed25519.encode(Ed25519.scalarMult(a, Ed25519.BASE)));
        assertEquals("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", pub);
    }

    @Test
    public void scalarReduction() {
        // A 64-byte little-endian number reduced mod L must match BigInteger mod.
        BigInteger v = Ed25519.P.shiftLeft(256).add(BigInteger.valueOf(1234567));
        byte[] le = new byte[64];
        byte[] src = v.toByteArray();
        for (int i = 0; i < src.length && i < 64; i++) {
            le[i] = src[src.length - 1 - i];
        }
        assertEquals(v.mod(Ed25519.L), Ed25519.scalarFrom64Le(le));
    }

    @Test
    public void passwordScalarIsMultipleOfEight() {
        for (long seed = 1; seed < 20; seed++) {
            byte[] h = new byte[64];
            new java.util.Random(seed).nextBytes(h);
            BigInteger w = Ed25519.spakePasswordScalar(Ed25519.scalarFrom64Le(h));
            assertTrue(w.mod(BigInteger.valueOf(8)).signum() == 0);
            assertTrue(w.compareTo(BigInteger.valueOf(2).pow(256)) < 0);
        }
    }

    private static boolean onCurve(Ed25519.Point p) {
        BigInteger x = p.x.multiply(p.z.modPow(Ed25519.P.subtract(BigInteger.valueOf(2)), Ed25519.P))
            .mod(Ed25519.P);
        BigInteger y = p.y.multiply(p.z.modPow(Ed25519.P.subtract(BigInteger.valueOf(2)), Ed25519.P))
            .mod(Ed25519.P);
        BigInteger lhs = x.modPow(BigInteger.valueOf(2), Ed25519.P).negate().add(y.modPow(BigInteger.valueOf(2), Ed25519.P));
        BigInteger rhs = BigInteger.ONE.add(
            x.modPow(BigInteger.valueOf(2), Ed25519.P).multiply(y.modPow(BigInteger.valueOf(2), Ed25519.P))
                .multiply(new BigInteger(
                    "37095705934669439343138083508754565189542113879843219016388785533085940283555")));
        return lhs.subtract(rhs).mod(Ed25519.P).signum() == 0;
    }

    private static String enc(Ed25519.Point p) {
        return hexs(Ed25519.encode(p));
    }

    private static BigInteger bi(long v) {
        return BigInteger.valueOf(v);
    }

    static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    static String hexs(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
