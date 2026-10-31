package app.jarvis.core.adb;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;
import javax.net.ssl.SSLSocket;

/**
 * One adb pairing exchange (AOSP pairing_connection.cpp) over an established TCP socket:
 * TLS 1.3 both-certificate handshake, exporter label "adb-label\0" (64 bytes) appended to the
 * pairing code as the SPAKE2 password, SPAKE2 M/N exchange, then the 8192-byte PeerInfo
 * (our adb public key) encrypted with the derived AES-128-GCM key.
 * Used as client by {@link AdbClient} and as server by the test FakeAdbd.
 */
final class PairingPeer {
    static final String EKM_LABEL = "adb-label\0";
    static final int EKM_LEN = 64;
    static final int PEER_INFO_SIZE = 8192;
    // PeerInfo.type (pairing_connection.h): the host sends its RSA key, the device answers with its GUID.
    static final int TYPE_RSA_PUB_KEY = 0;
    static final byte TYPE_DEVICE_GUID = 1;
    static final byte PACKET_VERSION = 1;
    // AOSP pairing_connection.cpp: SPAKE2_MSG = 0, PEER_INFO = 1 (real adb logs "expected=0 actual=1" if these are shifted)
    static final byte TYPE_SPAKE2_MSG = 0;
    static final byte TYPE_PEER_INFO = 1;
    static final int MAX_PAYLOAD = 16384;

    private final SSLSocket tls;
    private final Spake2 spake;
    private final byte[] peerInfo;
    private final long deadlineMs;

    private PairingPeer(SSLSocket tls, Spake2 spake, byte[] peerInfo, long deadlineMs) {
        this.tls = tls;
        this.spake = spake;
        this.peerInfo = peerInfo;
        this.deadlineMs = deadlineMs;
    }

    /** Server role (adbd side / FakeAdbd). */
    static PairingPeer server(Socket raw, KeyPair key, String code, byte[] peerInfo,
                              long deadlineMs) throws Exception {
        return start(raw, key, code, peerInfo, deadlineMs, true);
    }

    /** Client role (adb host side). */
    static PairingPeer client(Socket raw, KeyPair key, String code, byte[] peerInfo,
                              long deadlineMs) throws Exception {
        return start(raw, key, code, peerInfo, deadlineMs, false);
    }

    private static PairingPeer start(Socket raw, KeyPair key, String code, byte[] peerInfo,
                                     long deadlineMs, boolean server) throws Exception {
        javax.net.ssl.SSLContext ctx = TlsContexts.forKey(key);
        SSLSocket tls = server ? TlsContexts.wrapAsServer(ctx, raw)
            : TlsContexts.wrapAsClient(ctx, raw, raw.getInetAddress().getHostAddress(),
                raw.getPort());
        tls.startHandshake();
        byte[] ekm = TlsSupport.exportKeyingMaterial(tls, EKM_LABEL, EKM_LEN);
        byte[] codeBytes = code.getBytes(StandardCharsets.UTF_8);
        byte[] password = new byte[codeBytes.length + ekm.length];
        System.arraycopy(codeBytes, 0, password, 0, codeBytes.length);
        System.arraycopy(ekm, 0, password, codeBytes.length, ekm.length);
        Arrays.fill(codeBytes, (byte) 0);
        Spake2 spake = server ? Spake2.server(password) : Spake2.client(password);
        Arrays.fill(password, (byte) 0);
        return new PairingPeer(tls, spake, peerInfo, deadlineMs);
    }

    public static final class Result {
        public final int peerType;
        public final byte[] peerData;
        Result(int t, byte[] d) { peerType = t; peerData = d; }
    }

    /** Runs the SPAKE2 + PeerInfo exchange; closes the socket in every case. */
    Result run() throws AdbException {
        try {
            InputStream in = tls.getInputStream();
            OutputStream out = tls.getOutputStream();
            return runExchange(in, out, spake, peerInfo);
        } catch (AdbException e) {
            throw e;
        } catch (IOException e) {
            throw AdbException.network(e, "pairing io: " + e);
        } finally {
            closeQuietly();
        }
    }

    /**
     * One ASCII line read byte by byte. A BufferedReader must not be used on a stream that carries
     * the pairing protocol afterwards: it reads ahead and swallows the first protocol message.
     */
    static String readAsciiLine(InputStream in, int max) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b < 0) return sb.length() == 0 ? null : sb.toString();
            if (b == '\n') return sb.toString();
            if (sb.length() >= max) throw new java.io.IOException("relay header too long");
            sb.append((char) (b & 0x7F));
        }
    }

    // Test-only hook: when > 0, AdbClient.pair dials this local plain port instead of doing
    // its own TLS — a Node terminator there provides TLS 1.3 + the exported keying material
    // (the JVM has no TLS stack that can export it). Production code never sets it.
    static volatile int clientRelayPort;

    /** Pairing-side core over already-decrypted streams; password = code + externally
     * exported TLS keying material (used behind the Node TLS terminator in tests). */
    static Result relayServer(InputStream in, OutputStream out, String code, byte[] ekm,
                              byte[] peerInfo) throws AdbException {
        return relayRun(in, out, code, ekm, peerInfo, false);
    }

    static Result relayClient(InputStream in, OutputStream out, String code, byte[] ekm,
                              byte[] peerInfo) throws AdbException {
        return relayRun(in, out, code, ekm, peerInfo, true);
    }

    private static Result relayRun(InputStream in, OutputStream out, String code, byte[] ekm,
                                   byte[] peerInfo, boolean client) throws AdbException {
        byte[] codeBytes = code.getBytes(StandardCharsets.UTF_8);
        byte[] password = new byte[codeBytes.length + ekm.length];
        System.arraycopy(codeBytes, 0, password, 0, codeBytes.length);
        System.arraycopy(ekm, 0, password, codeBytes.length, ekm.length);
        Arrays.fill(codeBytes, (byte) 0);
        Spake2 spake = client ? Spake2.client(password) : Spake2.server(password);
        Arrays.fill(password, (byte) 0);
        return runExchange(in, out, spake, peerInfo);
    }

    private static Result runExchange(InputStream in, OutputStream out, Spake2 spake,
                                      byte[] peerInfo) throws AdbException {
        try {
            byte[] myMsg = spake.message();
            writePacket(out, TYPE_SPAKE2_MSG, myMsg);
            Packet p = readPacket(in);
            if (p.type != TYPE_SPAKE2_MSG || p.payload.length != 32) {
                throw new AdbException(AdbException.Kind.PROTOCOL,
                    "Устройство ответило неожиданным сообщением при сопряжении",
                    "pairing: expected SPAKE2_MSG(32B), got type=" + p.type + " len=" + p.payload.length);
            }
            byte[] key = spake.processMessage(p.payload);
            if (key == null) {
                throw new AdbException(AdbException.Kind.PROTOCOL,
                    "Устройство передало некорректные данные сопряжения",
                    "pairing: SPAKE2 rejected peer message (invalid point?)");
            }
            AesGcm gcm = AesGcm.fromSpakeKey(key);

            byte[] enc = gcm.encrypt(peerInfo);
            writePacket(out, TYPE_PEER_INFO, enc);
            Packet q = readPacket(in);
            if (q.type != TYPE_PEER_INFO) {
                throw new AdbException(AdbException.Kind.PROTOCOL,
                    "Устройство ответило неожиданным сообщением при сопряжении",
                    "pairing: expected PEER_INFO, got type=" + q.type);
            }
            byte[] dec = gcm.decrypt(q.payload);
            if (dec == null || dec.length != PEER_INFO_SIZE) {
                throw new AdbException(AdbException.Kind.WRONG_CODE,
                    "Неверный код сопряжения",
                    "pairing: PeerInfo decrypt failed (len=" + (dec == null ? -1 : dec.length)
                        + ") — wrong code");
            }
            int peerType = dec[0] & 0xff;
            return new Result(peerType, Arrays.copyOfRange(dec, 1, dec.length));
        } catch (AdbException e) {
            throw e;
        } catch (EOFException | javax.net.ssl.SSLException e) {
            throw new AdbException(AdbException.Kind.NETWORK,
                "Соединение с устройством прервано", "pairing io: " + e, e);
        } catch (IOException e) {
            throw AdbException.network(e, "pairing io: " + e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new AdbException(AdbException.Kind.TLS, "Ошибка TLS при сопряжении",
                "pairing: " + e, e);
        }
    }

    private static void writePacket(OutputStream out, byte type, byte[] payload) throws IOException {
        byte[] h = new byte[6];
        h[0] = PACKET_VERSION;
        h[1] = type;
        h[2] = (byte) (payload.length >> 24);
        h[3] = (byte) (payload.length >> 16);
        h[4] = (byte) (payload.length >> 8);
        h[5] = (byte) payload.length;
        out.write(h);
        out.write(payload);
        out.flush();
    }

    private static Packet readPacket(InputStream in) throws IOException {
        byte[] h = readFully(in, 6);
        Packet p = new Packet();
        p.version = h[0];
        p.type = h[1];
        p.payloadLen = ((h[2] & 0xff) << 24) | ((h[3] & 0xff) << 16) | ((h[4] & 0xff) << 8)
            | (h[5] & 0xff);
        if (p.version != PACKET_VERSION || p.payloadLen <= 0 || p.payloadLen > MAX_PAYLOAD) {
            throw new IOException("pairing: bad packet header v=" + p.version + " len=" + p.payloadLen);
        }
        p.payload = readFully(in, p.payloadLen);
        return p;
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new EOFException("pairing: stream closed (" + off + "/" + n + ")");
            off += r;
        }
        return buf;
    }

    void closeQuietly() {
        try {
            tls.close();
        } catch (IOException ignored) { }
    }

    private static final class Packet {
        byte version;
        byte type;
        int payloadLen;
        byte[] payload;
    }
}
