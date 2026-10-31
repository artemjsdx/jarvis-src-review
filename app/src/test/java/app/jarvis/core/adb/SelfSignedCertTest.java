package app.jarvis.core.adb;

import static org.junit.Assert.*;

import java.security.KeyPair;
import java.security.cert.X509Certificate;
import org.junit.Test;

public class SelfSignedCertTest {

    @Test
    public void parseBackAndVerify() throws Exception {
        KeyPair kp = AndroidPubKeyTest.newKey();
        byte[] der = SelfSignedCert.buildDer(kp, 1_700_000_000);
        X509Certificate cert = SelfSignedCert.parse(der);
        assertTrue(cert.getSubjectX500Principal().getName(), cert.getSubjectX500Principal()
            .getName().contains("CN=Adb"));
        assertTrue(cert.getIssuerX500Principal().getName().contains("CN=Adb"));
        assertEquals(3, cert.getVersion()); // v3
        cert.verify(kp.getPublic()); // throws if the self-signature is bad
        assertEquals(kp.getPublic(), cert.getPublicKey());
        // 10-year lifetime
        long days = (cert.getNotAfter().getTime() - cert.getNotBefore().getTime()) / 86400_000L;
        assertTrue("lifetime days=" + days, days >= 3640 && days <= 3660);
        cert.checkValidity();
    }

    @Test
    public void pemEncodeParses() throws Exception {
        KeyPair kp = AndroidPubKeyTest.newKey();
        byte[] der = SelfSignedCert.buildDer(kp, 1_700_000_000);
        String pem = SelfSignedCert.toPem(der);
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----"));
        X509Certificate cert = SelfSignedCert.parse(
            java.util.Base64.getMimeDecoder().decode(
                pem.replace("-----BEGIN CERTIFICATE-----", "")
                    .replace("-----END CERTIFICATE-----", "")));
        assertTrue(cert.getSubjectX500Principal().getName().contains("CN=Adb"));
    }
}
