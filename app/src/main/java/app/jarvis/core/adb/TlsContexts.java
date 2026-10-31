package app.jarvis.core.adb;

import java.net.Socket;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * TLS contexts shared by the adb pairing and connect flows: always TLS 1.3, our self-signed
 * certificate from the adb key, peer certificate accepted unconditionally (like adbd).
 */
final class TlsContexts {
    private static final X509TrustManager TRUST_ANY = new X509TrustManager() {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
            throws CertificateException { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
            throws CertificateException { }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    };

    private TlsContexts() { }

    /** SSLContext presenting {@code key}'s certificate and trusting any peer. */
    static SSLContext forKey(KeyPair key) throws Exception {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        char[] pass = "jarvis".toCharArray();
        java.security.KeyStore.ProtectionParameter prot =
            new KeyStore.PasswordProtection(pass);
        X509Certificate cert = SelfSignedCert.parse(
            SelfSignedCert.buildDer(key, System.currentTimeMillis() / 1000 - 86400));
        ks.setKeyEntry("adb", key.getPrivate(), pass, new X509Certificate[]{cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pass);
        SSLContext ctx = TlsSupport.tlsContext();
        ctx.init(kmf.getKeyManagers(), new TrustManager[]{TRUST_ANY}, new SecureRandom());
        return ctx;
    }

    /** Wraps an established TCP socket as the TLS 1.3 client side (no handshake yet). */
    static SSLSocket wrapAsClient(SSLContext ctx, Socket raw, String host, int port) throws Exception {
        SSLSocket s = (SSLSocket) ctx.getSocketFactory()
            .createSocket(raw, host, port, true);
        s.setUseClientMode(true);
        restrictTls13(s);
        return s;
    }

    /** Wraps an established TCP socket as the TLS 1.3 server side (no handshake yet). */
    static SSLSocket wrapAsServer(SSLContext ctx, Socket raw) throws Exception {
        SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, null, 0, true);
        s.setUseClientMode(false);
        s.setNeedClientAuth(true);
        restrictTls13(s);
        return s;
    }

    static void restrictTls13(SSLSocket s) {
        try {
            s.setEnabledProtocols(new String[]{"TLSv1.3"});
        } catch (IllegalArgumentException e) {
            // stack without TLS 1.3: keep defaults; handshake will fail with a clear error
        }
    }
}
