package app.jarvis.core.adb;

import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-128-GCM with adb pairing_auth's nonce scheme: 12-byte zero nonce whose first 4 bytes hold
 * a message counter (little-endian), separate counters per direction, empty AAD, 16-byte tag.
 */
final class AesGcm {
    private static final int KEY_LEN = 16;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private long encSequence;
    private long decSequence;

    private AesGcm(byte[] keyMaterial) {
        byte[] k = Hkdf.hkdfSha256(keyMaterial, null,
            "adb pairing_auth aes-128-gcm key".getBytes(java.nio.charset.StandardCharsets.UTF_8),
            KEY_LEN);
        this.key = new SecretKeySpec(k, "AES");
    }

    static AesGcm fromSpakeKey(byte[] spakeKeyMaterial) {
        return new AesGcm(spakeKeyMaterial);
    }

    byte[] encrypt(byte[] plain) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce(encSequence++)));
        return c.doFinal(plain);
    }

    /** Returns null on authentication failure (i.e. wrong pairing code). */
    byte[] decrypt(byte[] cipher) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce(decSequence++)));
            return c.doFinal(cipher);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] nonce(long seq) {
        byte[] n = new byte[12];
        for (int i = 0; i < 4; i++) n[i] = (byte) (seq >> (8 * i));
        return n;
    }

    /** Random 16-byte AES-128 key (helper for tests/tools). */
    static byte[] randomKey() {
        byte[] k = new byte[KEY_LEN];
        new SecureRandom().nextBytes(k);
        return k;
    }
}
