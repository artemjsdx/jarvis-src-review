package app.jarvis.core.adb;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Minimal Ed25519 group arithmetic on BigInteger for the SPAKE2 exchange used by adb pairing.
 * Extended twisted Edwards coordinates (a = -1); mirrors BoringSSL's curve25519 semantics
 * (compressed little-endian point encoding, SHA-512-based scalar reduction).
 */
public final class Ed25519 {
    private Ed25519() { }

    public static final BigInteger P = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19));
    /** 2^252 + 27742317777372353535851937790883648493 — order of the prime subgroup. */
    public static final BigInteger L =
        BigInteger.valueOf(2).pow(252).add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger MASK256 = BigInteger.valueOf(2).pow(256).subtract(BigInteger.ONE);
    private static final BigInteger D =
        new BigInteger("37095705934669439343138083508754565189542113879843219016388785533085940283555");
    private static final BigInteger SQRT_M1 =
        new BigInteger("19681161376707505956807079304988542015446066515923890162744021073123829784752");
    private static final BigInteger P_MINUS_5_DIV_8 =
        P.subtract(BigInteger.valueOf(5)).divide(BigInteger.valueOf(8));
    private static final BigInteger THREE = BigInteger.valueOf(3);
    private static final BigInteger SEVEN = BigInteger.valueOf(7);

    /** A curve point in extended coordinates (x = X/Z, y = Y/Z, x*y = T/Z). */
    public static final class Point {
        public final BigInteger x, y, z, t;
        Point(BigInteger x, BigInteger y, BigInteger z, BigInteger t) {
            this.x = x; this.y = y; this.z = z; this.t = t;
        }
    }

    public static final Point BASE = new Point(
        new BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202"),
        new BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960"),
        BigInteger.ONE,
        new BigInteger("46827403850823179245072216630277197565144205554125654976674165829533817101731"));

    public static Point neutral() {
        return new Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO);
    }

    /** EFD add-2008-hwcd-3 (twisted Edwards, a = -1). */
    public static Point add(Point p, Point q) {
        BigInteger a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(P);
        BigInteger b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(P);
        BigInteger c = p.t.multiply(BigInteger.valueOf(2)).multiply(D).multiply(q.t).mod(P);
        BigInteger d = p.z.multiply(BigInteger.valueOf(2)).multiply(q.z).mod(P);
        BigInteger e = b.subtract(a);
        BigInteger f = d.subtract(c);
        BigInteger g = d.add(c);
        BigInteger h = b.add(a);
        return new Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P),
            e.multiply(h).mod(P));
    }

    public static Point negate(Point p) {
        return new Point(p.x.negate().mod(P), p.y, p.z, p.t.negate().mod(P));
    }

    public static Point sub(Point p, Point q) {
        return add(p, negate(q));
    }

    /** Doubling = add(p, p) with the unified hwcd-3 formulas specialized in place. */
    public static Point dbl(Point p) {
        BigInteger b = p.y.add(p.x).mod(P).modPow(BigInteger.valueOf(2), P);   // (Y+X)^2
        BigInteger a = p.y.subtract(p.x).mod(P).modPow(BigInteger.valueOf(2), P); // (Y-X)^2
        BigInteger c = BigInteger.valueOf(2).multiply(D).multiply(p.t).multiply(p.t).mod(P); // 2d T^2
        BigInteger d2 = BigInteger.valueOf(2).multiply(p.z).multiply(p.z).mod(P);            // 2Z^2
        BigInteger e = b.subtract(a);
        BigInteger f = d2.subtract(c);
        BigInteger g = d2.add(c);
        BigInteger h = b.add(a);
        return new Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P),
            e.multiply(h).mod(P));
    }

    /** Double-and-add scalar multiplication over the full 256-bit scalar (like BoringSSL's
     * ge_scalarmult which takes a raw little-endian 32-byte scalar, not a value mod L). */
    public static Point scalarMult(BigInteger k, Point p) {
        Point r = neutral();
        k = k.and(BigInteger.valueOf(2).pow(256).subtract(BigInteger.ONE));
        for (int i = k.bitLength() - 1; i >= 0; i--) {
            r = dbl(r);
            if (k.testBit(i)) r = add(r, p);
        }
        return r;
    }

    /** Compressed 32-byte encoding: little-endian y with the sign of x in the top bit. */
    public static byte[] encode(Point p) {
        BigInteger zinv = p.z.modPow(P.subtract(BigInteger.valueOf(2)), P);
        BigInteger x = p.x.multiply(zinv).mod(P);
        BigInteger y = p.y.multiply(zinv).mod(P);
        byte[] out = toBytesLe(y);
        if (x.testBit(0)) out[31] = (byte) (out[31] | 0x80);
        return out;
    }

    /** Decodes a compressed point; returns null when invalid (non-canonical, bad sign, not on curve). */
    public static Point decode(byte[] in) {
        if (in == null || in.length != 32) return null;
        byte[] copy = in.clone();
        boolean sign = (copy[31] & 0x80) != 0;
        copy[31] &= 0x7f;
        BigInteger y = fromBytesLe(copy);
        if (y.compareTo(P) >= 0) return null;
        BigInteger yy = y.multiply(y).mod(P);
        BigInteger u = yy.subtract(BigInteger.ONE).mod(P);
        BigInteger v = D.multiply(yy).add(BigInteger.ONE).mod(P);
        // x = sqrt(u/v): candidate u/v ^ ((p+3)/8), corrected with sqrt(-1) when the check fails.
        BigInteger x2 = u.multiply(v.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P);
        BigInteger x = x2.modPow(P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)), P);
        if (!x.multiply(x).mod(P).equals(x2)) {
            x = x.multiply(SQRT_M1).mod(P);
            if (!x.multiply(x).mod(P).equals(x2)) return null;
        }
        if (x.testBit(0) != sign) x = P.subtract(x).mod(P);
        // On-curve check: -x^2 + y^2 == 1 + d*x^2*y^2
        BigInteger xx = x.multiply(x).mod(P);
        if (!xx.negate().add(yy).mod(P)
            .equals(BigInteger.ONE.add(D.multiply(xx).multiply(yy)).mod(P))) return null;
        if (x.signum() == 0 && sign) return null;
        return new Point(x, y, BigInteger.ONE, x.multiply(y).mod(P));
    }

    /** BoringSSL x25519_sc_reduce: interprets 64 little-endian bytes as an integer mod L. */
    public static BigInteger scalarFrom64Le(byte[] h64) {
        return fromBytesLe(h64).mod(L);
    }

    /** Multiplies a scalar by 8 in the 256-bit little-endian representation (BoringSSL left_shift_3). */
    public static BigInteger clearCofactor(BigInteger scalar) {
        return scalar.shiftLeft(3).and(MASK256);
    }

    /** Reduces a scalar mod L, then rounds it up to the next multiple of 8 like BoringSSL's
     * pairing password-scalar fixup (adding L, 2L, 4L per low bits 0,1,2; no wrap below 2^256). */
    public static BigInteger spakePasswordScalar(BigInteger reducedModL) {
        BigInteger w = reducedModL;
        BigInteger order = L;
        for (int pos = 0; pos <= 2; pos++) {
            if (w.testBit(pos)) w = w.add(order).and(MASK256);
            order = order.shiftLeft(1).and(MASK256);
        }
        return w;
    }

    public static byte[] sha512(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 unavailable", e);
        }
    }

    /** Fixed-width 32-byte little-endian encoding of a scalar. */
    public static byte[] toBytesLe(BigInteger v) {
        byte[] src = v.and(MASK256).toByteArray();
        byte[] out = new byte[32];
        for (int i = 0; i < src.length; i++) {
            if (i >= 32) break;
            out[i] = src[src.length - 1 - i];
        }
        return out;
    }

    public static BigInteger fromBytesLe(byte[] le) {
        byte[] be = new byte[le.length];
        for (int i = 0; i < le.length; i++) be[i] = le[le.length - 1 - i];
        return new BigInteger(1, be);
    }

    public static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
