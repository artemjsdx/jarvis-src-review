package app.jarvis.core.adb;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Our adb RSA-2048 identity: private key (PKCS#8, file mode 600), adb-style public key line,
 * self-signed TLS certificate, classic-AUTH token signing. Persisted in the state dir.
 */
final class AdbKeys {
    static final String KEY_FILE = "adbkey";
    static final String LOGIN_AT_HOST = "jarvis@localhost";

    final KeyPair keyPair;
    final String publicKeyLine;
    final String fingerprint;
    final byte[] certDer;

    private AdbKeys(KeyPair keyPair, String publicKeyLine, String fingerprint, byte[] certDer) {
        this.keyPair = keyPair;
        this.publicKeyLine = publicKeyLine;
        this.fingerprint = fingerprint;
        this.certDer = certDer;
    }

    RSAPublicKey publicKey() {
        return (RSAPublicKey) keyPair.getPublic();
    }

    /** Loads adbkey from {@code dir} or generates a new one; writes mode-600 files. */
    static AdbKeys loadOrCreate(File dir) throws IOException {
        File keyFile = new File(dir, KEY_FILE);
        File pubFile = new File(dir, KEY_FILE + ".pub");
        KeyPair kp;
        if (keyFile.isFile()) {
            kp = readPem(keyFile);
        } else {
            kp = generate();
            writePem(keyFile, kp);
            writePublic(pubFile, kp);
        }
        return build(kp);
    }

    /** In-memory identity (tests, checks). */
    static AdbKeys ephemeral() {
        return build(generate());
    }

    static AdbKeys build(KeyPair kp) {
        String line = AndroidPubKey.toAdbLine((RSAPublicKey) kp.getPublic(), LOGIN_AT_HOST);
        String fingerprint = fingerprint(kp);
        byte[] cert = SelfSignedCert.buildDer(kp, System.currentTimeMillis() / 1000 - 86400);
        return new AdbKeys(kp, line, fingerprint, cert);
    }

    /** SHA-256 over the SPKI DER of the public key, adb-style colon-separated hex. */
    static String fingerprint(KeyPair kp) {
        try {
            byte[] spki = kp.getPublic().getEncoded();
            byte[] h = MessageDigest.getInstance("SHA-256").digest(spki);
            StringBuilder sb = new StringBuilder();
            for (byte b : h) {
                if (sb.length() > 0) sb.append(':');
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** PKCS#1 v1.5 SHA-1 signature of a 20-byte adbd auth token (adb's RSA_sign(NID_sha1, ...)). */
    byte[] signToken(byte[] token) throws Exception {
        Signature sig = Signature.getInstance("SHA1withRSA");
        sig.initSign(keyPair.getPrivate());
        sig.update(token);
        return sig.sign();
    }

    String certificatePem() {
        return SelfSignedCert.toPem(certDer);
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048, new SecureRandom());
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("RSA keygen failed", e);
        }
    }

    private static KeyPair readPem(File f) throws IOException {
        try {
            String pem = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            String b64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(b64);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(der));
            java.security.spec.RSAPrivateCrtKeySpec crt = kf.getKeySpec(priv,
                java.security.spec.RSAPrivateCrtKeySpec.class);
            RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(
                new java.security.spec.RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
            return new KeyPair(pub, priv);
        } catch (Exception e) {
            throw new IOException("bad adb key file: " + f, e);
        }
    }

    private static void writePem(File f, KeyPair kp) throws IOException {
        String pem = "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(kp.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
        Path p = f.toPath();
        Files.write(p, pem.getBytes(StandardCharsets.UTF_8));
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX filesystem: best effort
        }
    }

    private static void writePublic(File f, KeyPair kp) throws IOException {
        Files.write(f.toPath(), (AndroidPubKey.toAdbLine((RSAPublicKey) kp.getPublic(), LOGIN_AT_HOST)
            + "\n").getBytes(StandardCharsets.UTF_8));
    }
}
