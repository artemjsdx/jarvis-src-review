package app.jarvis.core.adb;

import java.lang.reflect.Method;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Isolates the TLS provider choice and the TLS 1.3 keying-material exporter:
 * <ul>
 *   <li>Android API 31+: {@code android.net.ssl.SSLSockets.exportKeyingMaterial} (platform Conscrypt).</li>
 *   <li>bundled {@code org.conscrypt.Conscrypt} (app/libs jar + jniLibs): works on API 26+ and gives TLS 1.3.</li>
 *   <li>JVM tests: the same org.conscrypt API from conscrypt-openjdk-uber, injected via {@link #setHooks}.</li>
 * </ul>
 */
final class TlsSupport {
    private TlsSupport() { }

    /** Reflection-free seam for test environments where Conscrypt lives on a dynamic classloader. */
    interface Exporter {
        byte[] export(SSLSocket socket, String label, byte[] context, int length) throws Exception;
    }

    private static volatile java.security.Provider customProvider;
    private static volatile Exporter customExporter;
    private static volatile Runnable nativePreload;
    private static volatile boolean preloadDone;

    /** Test/App bootstrap: inject a Conscrypt provider built on a non-default classloader. */
    static void setHooks(java.security.Provider provider, Exporter exporter, Runnable preload) {
        customProvider = provider;
        customExporter = exporter;
        nativePreload = preload;
    }

    static synchronized SSLSocketFactory socketFactory() throws Exception {
        SSLContext ctx = tlsContext();
        ctx.init(null, null, null);
        return ctx.getSocketFactory();
    }

    /** Fresh SSLContext on the best available provider (Conscrypt when present, else platform). */
    static SSLContext tlsContext() throws Exception {
        runPreload();
        java.security.Provider p = customProvider != null ? customProvider : conscryptProvider();
        return p != null ? SSLContext.getInstance("TLS", p) : SSLContext.getInstance("TLS");
    }

    /** TLS keying material (RFC 5705/8446) with no context; label should include any trailing NUL. */
    static byte[] exportKeyingMaterial(SSLSocket socket, String label, int length) throws Exception {
        runPreload();
        Exporter e = customExporter;
        if (e == null) e = platformExporter();
        if (e == null) {
            throw new AdbException(AdbException.Kind.UNSUPPORTED,
                "Экспорт ключевого материала TLS недоступен в этой системе",
                "no TLS exporter provider (need android.net.ssl.SSLSockets API 31+ or Conscrypt)");
        }
        return e.export(socket, label, null, length);
    }

    private static void runPreload() {
        if (preloadDone) return;
        synchronized (TlsSupport.class) {
            if (preloadDone) return;
            Runnable r = nativePreload;
            if (r != null) r.run();
            preloadDone = true;
        }
    }

    private static java.security.Provider conscryptProvider() {
        try {
            Class<?> cls = Class.forName("org.conscrypt.Conscrypt");
            Method m = cls.getMethod("newProvider");
            return (java.security.Provider) m.invoke(null);
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Exception e) {
            throw new IllegalStateException("Conscrypt present but broken", e);
        }
    }

    private static Exporter platformExporter() {
        final Class<?> sslSockets;
        try {
            sslSockets = Class.forName("android.net.ssl.SSLSockets");
        } catch (ClassNotFoundException e) {
            return conscryptExporter();
        }
        return (socket, label, context, length) -> {
            Method supported = sslSockets.getMethod("isSupportedSocket", SSLSocket.class);
            if (!(Boolean) supported.invoke(null, socket)) {
                throw new IllegalStateException("socket does not support EKM");
            }
            Method m = sslSockets.getMethod("exportKeyingMaterial", SSLSocket.class,
                String.class, byte[].class, int.class);
            return (byte[]) m.invoke(null, socket, label, context, length);
        };
    }

    private static Exporter conscryptExporter() {
        final Class<?> cls;
        try {
            cls = Class.forName("org.conscrypt.Conscrypt");
        } catch (ClassNotFoundException e) {
            return null;
        }
        return (socket, label, context, length) -> {
            Method m = cls.getMethod("exportKeyingMaterial", SSLSocket.class, String.class,
                byte[].class, int.class);
            return (byte[]) m.invoke(null, socket, label, context, length);
        };
    }
}
