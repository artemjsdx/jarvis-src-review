package app.jarvis.core.adb;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import org.junit.Test;

public class AndroidPubKeyTest {

    /** Byte-for-byte comparison against the real adb client's encoder. */
    @Test
    public void matchesRealAdbKeyPub() throws Exception {
        File home = new File(System.getProperty("user.home"));
        File priv = new File(home, ".android/adbkey");
        File pub = new File(home, ".android/adbkey.pub");
        if (!priv.isFile() || !pub.isFile()) {
            fail("~/.android/adbkey{,.pub} missing — run `adb start-server` first");
        }
        RSAPublicKey pubKey = readAdbKey(priv);
        String mineB64 = Base64.getEncoder().encodeToString(AndroidPubKey.encode(pubKey));
        String real = new String(Files.readAllBytes(pub.toPath()), StandardCharsets.UTF_8).trim();
        String realB64 = real.split(" ")[0];
        assertEquals(realB64, mineB64);
    }

    @Test
    public void generatedKeyStructureIsValid() throws Exception {
        RSAPublicKey pub = (RSAPublicKey) newKey().getPublic();
        byte[] enc = AndroidPubKey.encode(pub);
        assertEquals(AndroidPubKey.ENCODED_SIZE, enc.length);
        assertEquals(64, AdbProtocol.getU32(enc, 0)); // len in 32-bit words
        byte[] nLe = java.util.Arrays.copyOfRange(enc, 8, 8 + 256);
        byte[] nBe = new byte[256];
        for (int i = 0; i < 256; i++) nBe[i] = nLe[255 - i];
        byte[] withSign = new byte[257];
        System.arraycopy(nBe, 0, withSign, 1, 256);
        assertArrayEquals(pub.getModulus().toByteArray(), withSign);
        assertEquals(pub.getPublicExponent().longValue(), AdbProtocol.getU32(enc, 8 + 512));
        // rr = 2^4096 mod n, stored little-endian
        byte[] rrLe = java.util.Arrays.copyOfRange(enc, 8 + 256, 8 + 512);
        byte[] rrBe = new byte[256];
        for (int i = 0; i < 256; i++) rrBe[i] = rrLe[255 - i];
        java.math.BigInteger rr = new java.math.BigInteger(1, rrBe);
        assertEquals(java.math.BigInteger.valueOf(2).pow(4096).mod(pub.getModulus()), rr);
        // n0inv = (2^32 - n^-1) mod 2^32
        long n0 = AdbProtocol.getU32(enc, 4);
        java.math.BigInteger inv = pub.getModulus().mod(java.math.BigInteger.valueOf(2).pow(32))
            .modInverse(java.math.BigInteger.valueOf(2).pow(32));
        assertEquals(java.math.BigInteger.valueOf(2).pow(32).subtract(inv)
            .mod(java.math.BigInteger.valueOf(2).pow(32)).longValue(), n0);
    }

    @Test
    public void peerInfoLayout() {
        RSAPublicKey pub = (RSAPublicKey) newKey().getPublic();
        byte[] info = AndroidPubKey.peerInfoRsaPubKey(pub, "jarvis@localhost");
        assertEquals(8192, info.length);
        assertEquals(0, info[0]); // ADB_RSA_PUB_KEY
        int nul = 1;
        while (info[nul] != 0) nul++;
        String line = new String(java.util.Arrays.copyOfRange(info, 1, nul), StandardCharsets.UTF_8);
        assertTrue(line, line.startsWith(Base64.getEncoder().encodeToString(AndroidPubKey.encode(pub))));
        assertTrue(line.endsWith(" jarvis@localhost"));
    }

    // ------------------------------------------------------------------ helpers

    /** Reads either PKCS#8 ("BEGIN PRIVATE KEY") or PKCS#1 ("BEGIN RSA PRIVATE KEY") PEM. */
    static RSAPublicKey readAdbKey(File pemFile) throws Exception {
        String pem = new String(Files.readAllBytes(pemFile.toPath()), StandardCharsets.UTF_8);
        String b64 = pem.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(b64);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PrivateKey priv;
        try {
            priv = kf.generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            priv = kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs1ToPkcs8(der)));
        }
        RSAPrivateCrtKeySpec crt = kf.getKeySpec(priv, RSAPrivateCrtKeySpec.class);
        return (RSAPublicKey) kf.generatePublic(
            new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
    }

    /** Wraps a PKCS#1 RSAPrivateKey into an unencrypted PKCS#8 PrivateKeyInfo. */
    static byte[] pkcs1ToPkcs8(byte[] pkcs1) {
        DerWriter alg = new DerWriter().seq(new DerWriter().oid("1.2.840.113549.1.1.1"),
            new DerWriter().nul());
        return new DerWriter().seq(
            new DerWriter().integer(0),
            alg,
            new DerWriter().octetString(pkcs1)).encoded();
    }

    static java.security.KeyPair newKey() {
        try {
            java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
