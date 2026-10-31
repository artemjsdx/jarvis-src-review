package app.jarvis.core.adb;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Builds the self-signed X.509 v3 certificate adb uses for its TLS identity (same fields as AOSP
 * x509_generator.cpp: C=US, O=Android, CN=Adb, CA:TRUE, keyUsage; SHA256withRSA; 10-year lifetime).
 */
final class SelfSignedCert {
    private SelfSignedCert() { }

    static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'");

    /** DER of a self-signed certificate for {@code key}. */
    static byte[] buildDer(KeyPair key, long notBeforeEpochSeconds) {
        try {
            long end = notBeforeEpochSeconds + 10L * 365 * 24 * 60 * 60;
            String notBefore = UTC.format(ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochSecond(notBeforeEpochSeconds), ZoneOffset.UTC));
            String notAfter = UTC.format(ZonedDateTime.ofInstant(
                java.time.Instant.ofEpochSecond(end), ZoneOffset.UTC));

            DerWriter name = name();
            DerWriter tbs = new DerWriter().seq(
                new DerWriter().explicit(0, new DerWriter().integer(2)),
                new DerWriter().integer(1),
                new DerWriter().seq(new DerWriter().oid("1.2.840.113549.1.1.11"), new DerWriter().nul()),
                name,
                new DerWriter().seq(new DerWriter().utcTime(notBefore), new DerWriter().utcTime(notAfter)),
                name(),
                spki(key),
                new DerWriter().explicit(3, new DerWriter().seq(
                    new DerWriter().seq(new DerWriter().oid("2.5.29.19"), new DerWriter().bool(true),
                        new DerWriter().octetString(new DerWriter().seq(new DerWriter().bool(true)).encoded())),
                    new DerWriter().seq(new DerWriter().oid("2.5.29.15"), new DerWriter().bool(true),
                        new DerWriter().octetString(new DerWriter().bitString(new byte[]{(byte) 0x86}, 1).encoded())))));

            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(key.getPrivate());
            sig.update(tbs.encoded());
            byte[] signature = sig.sign();

            return new DerWriter().seq(tbs,
                new DerWriter().seq(new DerWriter().oid("1.2.840.113549.1.1.11"), new DerWriter().nul()),
                new DerWriter().bitString(signature, 0)).encoded();
        } catch (Exception e) {
            throw new IllegalStateException("cert build failed", e);
        }
    }

    static X509Certificate parse(byte[] der) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(der));
    }

    static String toPem(byte[] der) {
        String b64 = java.util.Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN CERTIFICATE-----\n" + b64 + "\n-----END CERTIFICATE-----\n";
    }

    private static DerWriter name() {
        return new DerWriter().seq(
            new DerWriter().set(new DerWriter().seq(new DerWriter().oid("2.5.4.6"), new DerWriter().printableString("US"))),
            new DerWriter().set(new DerWriter().seq(new DerWriter().oid("2.5.4.10"), new DerWriter().printableString("Android"))),
            new DerWriter().set(new DerWriter().seq(new DerWriter().oid("2.5.4.3"), new DerWriter().printableString("Adb"))));
    }

    private static DerWriter spki(KeyPair key) {
        BigInteger n = ((java.security.interfaces.RSAPublicKey) key.getPublic()).getModulus();
        BigInteger e = ((java.security.interfaces.RSAPublicKey) key.getPublic()).getPublicExponent();
        byte[] modulus = trim(n.toByteArray());
        return new DerWriter().seq(
            new DerWriter().seq(new DerWriter().oid("1.2.840.113549.1.1.1"), new DerWriter().nul()),
            new DerWriter().bitString(new DerWriter().seq(
                new DerWriter().integerUnsigned(modulus),
                new DerWriter().integer(e)).encoded(), 0));
    }

    private static byte[] trim(byte[] be) {
        int i = 0;
        while (i < be.length - 1 && be[i] == 0) i++;
        byte[] out = new byte[be.length - i];
        System.arraycopy(be, i, out, 0, out.length);
        return out;
    }
}
