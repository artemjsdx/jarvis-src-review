package app.jarvis.core.adb;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Test-only adbd: the server half of adb pairing (TLS 1.3 + SPAKE2-as-Bob + PeerInfo) and of the
 * connect protocol (CNXN/STLS/TLS-with-client-cert or classic AUTH, then OPEN + shell v2/legacy
 * executed by /bin/sh). Used against the real `adb` client (interop) and our own AdbClient.
 */
public final class FakeAdbd implements Closeable {
    public enum Mode { PAIRING, PAIRING_RELAY, CONNECT_TLS, CONNECT_CLASSIC }

    public static final String DEVICE_BANNER =
        "device::ro.product.model=FakeAdbd;ro.product.device=fake;features=shell_v2,cmd,stat_v2";

    public final Mode mode;
    public volatile String pairingCode; // mutable for the shared relay harness
    public volatile byte[] lastPeerInfo; // captured client key (pairing) or RSAPUBLICKEY payload
    public volatile Throwable lastError;  // last failure inside a connection handler (otherwise swallowed)
    public volatile boolean clientKeyRejected;

    private final AdbKeys serverKeys;
    private final RSAPublicKey knownClientKey; // null = accept any TLS client cert
    private final boolean legacyShell; // advertise no shell_v2
    private final ServerSocket server;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final AtomicInteger streams = new AtomicInteger(100);
    private volatile boolean closed;

    public FakeAdbd(Mode mode, String pairingCode, AdbKeys serverKeys, RSAPublicKey knownClientKey,
                    boolean legacyShell) throws IOException {
        this(mode, pairingCode, serverKeys, knownClientKey, legacyShell, 0);
    }

    /** Port 0 binds an ephemeral loopback port. */
    public FakeAdbd(Mode mode, String pairingCode, AdbKeys serverKeys, RSAPublicKey knownClientKey,
                    boolean legacyShell, int port) throws IOException {
        this.mode = mode;
        this.pairingCode = pairingCode;
        this.serverKeys = serverKeys;
        this.knownClientKey = knownClientKey;
        this.legacyShell = legacyShell;
        this.server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress("127.0.0.1", port));
        Thread t = new Thread(this::acceptLoop, "FakeAdbd-" + mode);
        t.setDaemon(true);
        t.start();
    }

    public int getPort() {
        return server.getLocalPort();
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) { }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket s = server.accept();
                pool.submit(() -> {
                    try {
                        handle(s);
                    } catch (Exception e) {
                        lastError = e;
                        if (Boolean.getBoolean("fakeadbd.debug")) e.printStackTrace();
                    } finally {
                        try {
                            s.close();
                        } catch (IOException ignored) { }
                    }
                });
            } catch (IOException e) {
                if (closed) return;
            }
        }
    }

    /**
     * What real adbd answers with during pairing: PeerInfo type ADB_DEVICE_GUID (1) and the device
     * GUID string. The host adb prints "Failed: Successfully paired but server returned unknown
     * response=" for any other type, so the type byte is part of the interop contract.
     */
    static byte[] deviceGuidPeerInfo() {
        byte[] guid = "adb-FAKE0001-abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] info = new byte[PairingPeer.PEER_INFO_SIZE];
        info[0] = PairingPeer.TYPE_DEVICE_GUID;
        System.arraycopy(guid, 0, info, 1, guid.length);
        return info;
    }

    private void handle(Socket raw) throws Exception {
        raw.setSoTimeout(30_000);
        switch (mode) {
            case PAIRING: {
                byte[] info = deviceGuidPeerInfo();
                PairingPeer.Result r = PairingPeer.server(raw, serverKeys.keyPair,
                    pairingCode, info, System.currentTimeMillis() + 30_000).run();
                lastPeerInfo = r.peerData;
                break;
            }
            case PAIRING_RELAY: {
                // Behind the Node TLS terminator: read "EKMPROXY1 <base64>\n", then the
                // plaintext pairing protocol runs over this socket.
                String line = PairingPeer.readAsciiLine(raw.getInputStream(), 512);
                if (line == null || !line.startsWith("EKMPROXY1 ")) {
                    throw new IOException("bad relay header: " + line);
                }
                byte[] ekm = java.util.Base64.getDecoder().decode(line.substring("EKMPROXY1 ".length()));
                byte[] info = deviceGuidPeerInfo();
                PairingPeer.Result r = PairingPeer.relayServer(raw.getInputStream(),
                    raw.getOutputStream(), pairingCode, ekm, info);
                lastPeerInfo = r.peerData;
                break;
            }
            case CONNECT_TLS: {
                AdbProtocol.read(raw.getInputStream(), AdbProtocol.DEVICE_MAX_PAYLOAD); // CNXN
                AdbProtocol.write(raw.getOutputStream(),
                    AdbProtocol.Message.of(AdbProtocol.A_STLS, AdbProtocol.A_STLS_VERSION, 0, null));
                AdbProtocol.Message m = AdbProtocol.read(raw.getInputStream(),
                    AdbProtocol.DEVICE_MAX_PAYLOAD);
                if (m.command != AdbProtocol.A_STLS) {
                    throw new IOException("expected STLS, got " + m.commandName());
                }
                SSLContext ctx = TlsContexts.forKey(serverKeys.keyPair);
                SSLSocket tls = tlsServerWithCertCheck(ctx, raw);
                tls.startHandshake();
                AdbProtocol.write(tls.getOutputStream(), AdbProtocol.Message.of(AdbProtocol.A_CNXN,
                    AdbProtocol.A_VERSION, AdbProtocol.DEVICE_MAX_PAYLOAD,
                    (legacyShell ? DEVICE_BANNER.replace(",shell_v2", "")
                        : DEVICE_BANNER).getBytes(StandardCharsets.UTF_8)));
                serveAdb(tls);
                break;
            }
            case CONNECT_CLASSIC: {
                AdbProtocol.read(raw.getInputStream(), AdbProtocol.DEVICE_MAX_PAYLOAD); // CNXN
                serveClassicAuth(raw);
                break;
            }
        }
    }

    private SSLSocket tlsServerWithCertCheck(SSLContext ctx, Socket raw) throws Exception {
        SSLSocket tls = TlsContexts.wrapAsServer(ctx, raw);
        if (knownClientKey != null) {
            TrustManager check = new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                    java.security.PublicKey pk = chain[0].getPublicKey();
                    if (!(pk instanceof RSAPublicKey)
                        || !((RSAPublicKey) pk).getModulus().equals(knownClientKey.getModulus())) {
                        clientKeyRejected = true;
                        throw new CertificateException("unknown client adb key");
                    }
                }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            };
            SSLContext strict = SSLContext.getInstance("TLS");
            javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance(
                javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            java.security.KeyStore ks = java.security.KeyStore.getInstance(
                java.security.KeyStore.getDefaultType());
            ks.load(null, null);
            char[] pass = "fake".toCharArray();
            ks.setKeyEntry("fake", serverKeys.keyPair.getPrivate(), pass,
                new X509Certificate[]{app.jarvis.core.adb.SelfSignedCert.parse(serverKeys.certDer)});
            kmf.init(ks, pass);
            strict.init(kmf.getKeyManagers(), new TrustManager[]{check}, new SecureRandom());
            tls = TlsContexts.wrapAsServer(strict, raw);
        }
        return tls;
    }

    // adb connect without TLS: TOKEN -> SIGNATURE (verify) -> CNXN; RSAPUBLICKEY -> auto-enroll.
    private void serveClassicAuth(Socket s) throws Exception {
        OutputStream out = s.getOutputStream();
        InputStream in = s.getInputStream();
        RSAPublicKey enrolled = knownClientKey;
        byte[] token = new byte[20];
        new SecureRandom().nextBytes(token);
        AdbProtocol.write(out, AdbProtocol.Message.of(AdbProtocol.A_AUTH,
            AdbProtocol.ADB_AUTH_TOKEN, 0, token));
        for (int round = 0; round < 10; round++) {
            AdbProtocol.Message m = AdbProtocol.read(in, AdbProtocol.DEVICE_MAX_PAYLOAD);
            if (m.command == AdbProtocol.A_AUTH && m.arg0 == AdbProtocol.ADB_AUTH_RSAPUBLICKEY) {
                lastPeerInfo = m.payload;
                enrolled = decodeAdbPubKey(m.payload);
                continue; // user "tapped allow": the next signature verifies against this key
            }
            if (m.command == AdbProtocol.A_AUTH && m.arg0 == AdbProtocol.ADB_AUTH_SIGNATURE) {
                boolean ok = false;
                if (enrolled != null) {
                    java.security.Signature sig = java.security.Signature.getInstance("SHA1withRSA");
                    sig.initVerify(enrolled);
                    sig.update(token);
                    ok = sig.verify(m.payload);
                }
                if (ok) {
                    AdbProtocol.write(out, AdbProtocol.Message.of(AdbProtocol.A_CNXN,
                        AdbProtocol.A_VERSION, AdbProtocol.DEVICE_MAX_PAYLOAD,
                        DEVICE_BANNER.getBytes(StandardCharsets.UTF_8)));
                    serveAdb(s);
                    return;
                }
                token = new byte[20]; // like adbd's send_auth_request on failure
                new SecureRandom().nextBytes(token);
                AdbProtocol.write(out, AdbProtocol.Message.of(AdbProtocol.A_AUTH,
                    AdbProtocol.ADB_AUTH_TOKEN, 0, token));
            }
        }
        throw new IOException("classic auth failed");
    }

    /** Writes cert.pem + key.pem for the Node TLS terminator; returns {cert, key} files. */
    public static File[] writeIdentityPem(File dir, AdbKeys keys) throws IOException {
        File cert = new File(dir, "fakeadbd-cert.pem");
        File key = new File(dir, "fakeadbd-key.pem");
        Files.write(cert.toPath(), keys.certificatePem().getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder("-----BEGIN PRIVATE KEY-----\n");
        String b64 = java.util.Base64.getMimeEncoder(64, "\n".getBytes())
            .encodeToString(keys.keyPair.getPrivate().getEncoded());
        sb.append(b64).append("\n-----END PRIVATE KEY-----\n");
        Files.write(key.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        return new File[]{cert, key};
    }

    /** Decodes an adbkey.pub line ("base64 user@host\0...") into an RSA public key. */
    static RSAPublicKey decodeAdbPubKey(byte[] payload) throws Exception {
        int nul = 0;
        while (nul < payload.length && payload[nul] != 0) nul++;
        String line = new String(payload, 0, nul, StandardCharsets.UTF_8).trim();
        String b64 = line.split(" ")[0];
        byte[] raw = java.util.Base64.getDecoder().decode(b64);
        byte[] nLe = java.util.Arrays.copyOfRange(raw, 8, 8 + 256);
        long e = AdbProtocol.getU32(raw, 8 + 512);
        byte[] nBe = new byte[256];
        for (int i = 0; i < 256; i++) nBe[i] = nLe[255 - i];
        java.math.BigInteger modulus = new java.math.BigInteger(1, nBe);
        java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
        return (RSAPublicKey) kf.generatePublic(
            new java.security.spec.RSAPublicKeySpec(modulus, java.math.BigInteger.valueOf(e)));
    }

    // ---------------------------------------------------------------- shell serving

    private void serveAdb(Socket s) throws Exception {
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();
        while (true) {
            AdbProtocol.Message m;
            try {
                m = AdbProtocol.read(in, AdbProtocol.DEVICE_MAX_PAYLOAD);
            } catch (SocketTimeoutException e) {
                return;
            } catch (IOException e) {
                return;
            }
            if (m.command == AdbProtocol.A_OPEN) {
                String dest = new String(m.payload, StandardCharsets.UTF_8);
                if (dest.endsWith("\0")) dest = dest.substring(0, dest.length() - 1);
                int colon = dest.indexOf(':');
                String args = colon >= 0 ? dest.substring(0, colon) : dest;
                String command = colon >= 0 ? dest.substring(colon + 1) : "";
                boolean v2 = false;
                for (String a : args.split(",")) {
                    if (a.equals("v2")) v2 = true;
                }
                int remote = streams.incrementAndGet();
                AdbProtocol.write(out, AdbProtocol.Message.of(AdbProtocol.A_OKAY, remote, m.arg0, null));
                runShell(out, in, m.arg0, remote, v2, command);
                AdbProtocol.write(out, AdbProtocol.Message.of(AdbProtocol.A_CLSE, remote, m.arg0, null));
            } else if (m.command == AdbProtocol.A_WRTE) {
                AdbProtocol.write(out, AdbProtocol.Message.of(AdbProtocol.A_OKAY, m.arg1, m.arg0, null));
            }
            // A_CLSE for a stream we already closed is just an acknowledgement: the transport stays up
            // (real adb reuses one transport for many `adb shell` calls).
        }
    }

    private static File shellBinary() {
        File sys = new File("/system/bin/sh");
        return sys.canExecute() ? sys : new File("/bin/sh");
    }

    private void runShell(OutputStream out, InputStream in, int clientLocal, int remote, boolean v2,
                          String command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(shellBinary().getAbsolutePath(), "-c", command);
        pb.redirectErrorStream(!v2);
        Process p = pb.start();
        p.getOutputStream().close(); // no stdin for tests

        LinkedBlockingQueue<Integer> eof = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
        pump(p.getInputStream(), v2 ? 1 : -1, chunks, eof);
        if (v2) pump(p.getErrorStream(), 2, chunks, eof);

        // Each pump queues its data BEFORE its eof token, so "all eofs seen and chunks empty" is final.
        int eofs = v2 ? 2 : 1;
        long giveUp = System.currentTimeMillis() + 30_000;
        while (true) {
            byte[] data = chunks.poll(20, TimeUnit.MILLISECONDS);
            if (data == null) {
                while (eof.poll() != null) eofs--;
                if (eofs <= 0 && chunks.isEmpty()) break;
                if (System.currentTimeMillis() > giveUp) break;
                continue;
            }
            if (data.length == 1) continue; // end-of-stream marker
            byte id = data[0];
            byte[] payload = new byte[data.length - 1];
            System.arraycopy(data, 1, payload, 0, payload.length);
            if (v2) {
                byte[] packet = new byte[payload.length + 5];
                packet[0] = id;
                AdbProtocol.putU32(packet, 1, payload.length);
                System.arraycopy(payload, 0, packet, 5, payload.length);
                sendChunked(out, in, clientLocal, remote, packet);
            } else {
                sendChunked(out, in, clientLocal, remote, payload);
            }
        }
        if (v2) {
            int code = waitExit(p);
            byte[] packet = {3, 1, 0, 0, 0, (byte) code};
            sendChunked(out, in, clientLocal, remote, packet);
        } else {
            waitExit(p);
        }
    }

    private static int waitExit(Process p) {
        try {
            if (!p.waitFor(30, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return p.exitValue() & 0xff;
    }

    /** Sends one shell v2 packet (or legacy raw data) as WRTE messages ≤ maxdata, awaiting OKAY. */
    private void sendChunked(OutputStream out, InputStream in, int clientLocal, int remote,
                             byte[] data) throws Exception {
        int max = 4096;
        for (int off = 0; off < data.length; off += max) {
            int n = Math.min(max, data.length - off);
            AdbProtocol.write(out, AdbProtocol.Message.of(AdbProtocol.A_WRTE, remote, clientLocal,
                java.util.Arrays.copyOfRange(data, off, off + n)));
            AdbProtocol.Message ack = AdbProtocol.read(in, AdbProtocol.DEVICE_MAX_PAYLOAD);
            if (ack.command != AdbProtocol.A_OKAY) throw new IOException("expected OKAY ack");
        }
    }

    private void pump(InputStream src, int id, LinkedBlockingQueue<byte[]> chunks,
                      LinkedBlockingQueue<Integer> eof) {
        Thread t = new Thread(() -> {
            try {
                byte[] buf = new byte[8192];
                int r;
                while ((r = src.read(buf)) > 0) {
                    byte[] tagged = new byte[r + 1];
                    tagged[0] = (byte) id;
                    System.arraycopy(buf, 0, tagged, 1, r);
                    chunks.put(tagged);
                }
            } catch (Exception ignored) {
            } finally {
                try {
                    chunks.put(new byte[]{(byte) id}); // 1-byte marker; eof queue unused for ordering
                } catch (InterruptedException ignored) { }
                eof.offer(id);
            }
        });
        t.setDaemon(true);
        t.start();
    }
}
