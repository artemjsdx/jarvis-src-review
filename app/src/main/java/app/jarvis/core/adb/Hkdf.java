package app.jarvis.core.adb;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 5869 HKDF with SHA-256 (used to derive the adb pairing AES-128-GCM key). */
public final class Hkdf {
    private Hkdf() { }

    public static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            if (salt == null || salt.length == 0) salt = new byte[32];
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);
            byte[] t = new byte[0];
            byte[] out = new byte[length];
            int done = 0;
            for (byte i = 1; done < length; i++) {
                mac.init(new SecretKeySpec(prk, "HmacSHA256"));
                mac.update(t);
                mac.update(info);
                mac.update(i);
                t = mac.doFinal();
                int n = Math.min(t.length, length - done);
                System.arraycopy(t, 0, out, done, n);
                done += n;
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("HKDF failed", e);
        }
    }
}
