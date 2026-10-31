package app.jarvis.core.adb;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * SPAKE2 as implemented by BoringSSL (crypto/curve25519/spake25519.cc) — the variant used by
 * Android's adb pairing (client = Alice "adb pair client\0", server = Bob "adb pair server\0").
 * The shared secret is SHA-512 over the length-prefixed transcript.
 */
public final class Spake2 {
    /** Encoded M and N points from BoringSSL spake25519.cc. */
    static final byte[] M = hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e");
    static final byte[] N = hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778");

    // AOSP passes sizeof("adb pair client") = 16 bytes, i.e. including the trailing NUL.
    public static final byte[] CLIENT_NAME = "adb pair client\0".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    public static final byte[] SERVER_NAME = "adb pair server\0".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    public enum Role { ALICE, BOB }

    private final Role role;
    private final byte[] myName;
    private final byte[] theirName;
    private BigInteger privateScalar;
    private BigInteger passwordScalar;
    private byte[] passwordHash;
    private byte[] myMsg;

    private Spake2(Role role, byte[] myName, byte[] theirName) {
        this.role = role;
        this.myName = myName;
        this.theirName = theirName;
    }

    public static Spake2 client(byte[] password) {
        return start(Role.ALICE, CLIENT_NAME, SERVER_NAME, password);
    }

    public static Spake2 server(byte[] password) {
        return start(Role.BOB, SERVER_NAME, CLIENT_NAME, password);
    }

    private static Spake2 start(Role role, byte[] myName, byte[] theirName, byte[] password) {
        byte[] rnd = new byte[64];
        new SecureRandom().nextBytes(rnd);
        Spake2 s = start(role, myName, theirName, password, rnd);
        Arrays.fill(rnd, (byte) 0);
        return s;
    }

    /** Known-answer tests only: runs a role with a fixed 64-byte ephemeral seed (BoringSSL's RAND_bytes input). */
    static Spake2 withSeed(Role role, byte[] password, byte[] seed64) {
        return role == Role.ALICE
            ? start(Role.ALICE, CLIENT_NAME, SERVER_NAME, password, seed64)
            : start(Role.BOB, SERVER_NAME, CLIENT_NAME, password, seed64);
    }

    private static Spake2 start(Role role, byte[] myName, byte[] theirName, byte[] password,
                                byte[] rnd) {
        Spake2 s = new Spake2(role, myName, theirName);
        byte[] h = Ed25519.sha512(password);
        s.passwordHash = h;
        BigInteger w = Ed25519.spakePasswordScalar(Ed25519.scalarFrom64Le(h));

        BigInteger x = Ed25519.clearCofactor(Ed25519.scalarFrom64Le(rnd));
        s.privateScalar = x;

        Ed25519.Point mask = Ed25519.decode(role == Role.ALICE ? M : N);
        if (mask == null) throw new IllegalStateException("bad built-in SPAKE2 point");
        Ed25519.Point msg = Ed25519.add(Ed25519.scalarMult(x, Ed25519.BASE),
            Ed25519.scalarMult(w, mask));
        s.myMsg = Ed25519.encode(msg);
        s.passwordScalar = w;
        return s;
    }

    /** 32-byte SPAKE2 share to send to the peer. */
    public byte[] message() {
        return myMsg.clone();
    }

    /** Processes the peer's share and returns the 64-byte shared key; null if the peer data is invalid. */
    public byte[] processMessage(byte[] theirMsg) {
        if (theirMsg == null || theirMsg.length != 32) return null;
        Ed25519.Point qStar = Ed25519.decode(theirMsg);
        if (qStar == null) return null;
        Ed25519.Point peersMask = Ed25519.scalarMult(passwordScalar,
            Ed25519.decode(role == Role.ALICE ? N : M));
        Ed25519.Point q = Ed25519.sub(qStar, peersMask);
        byte[] dh = Ed25519.encode(Ed25519.scalarMult(privateScalar, q));
        // BoringSSL transcript order: Alice hashes (my_name, their_name, my_msg, their_msg),
        // Bob hashes (their_name, my_name, their_msg, my_msg); then dh and password hash.
        byte[] firstName = role == Role.ALICE ? myName : theirName;
        byte[] secondName = role == Role.ALICE ? theirName : myName;
        byte[] firstMsg = role == Role.ALICE ? myMsg : theirMsg;
        byte[] secondMsg = role == Role.ALICE ? theirMsg : myMsg;
        return Ed25519.sha512(
            prefix(firstName), firstName,
            prefix(secondName), secondName,
            prefix(firstMsg), firstMsg,
            prefix(secondMsg), secondMsg,
            prefix(dh), dh,
            prefix(passwordHash), passwordHash);
    }

    private static byte[] prefix(byte[] b) {
        long l = b.length;
        byte[] out = new byte[8];
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) l;
            l >>>= 8;
        }
        return out;
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
