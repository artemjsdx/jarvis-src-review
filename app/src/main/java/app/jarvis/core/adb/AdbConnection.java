package app.jarvis.core.adb;

import app.jarvis.core.util.CancelToken;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.net.ssl.SSLSocket;

/**
 * Client side of the adb connect protocol (AOSP adb.cpp): CNXN → STLS → TLS 1.3 with our client
 * certificate (paired key) → device CNXN banner; classic AUTH TOKEN/SIGNATURE/RSAPUBLICKEY flow
 * for non-TLS adbd (30 s window for the on-device confirmation). After the handshake: one
 * synchronous local-stream at a time (OPEN/OKAY/WRTE/CLSE) with shell v2 or legacy parsing.
 */
final class AdbConnection implements Closeable {
    static final String HOST_BANNER =
        "host::features=shell_v2,cmd,stat_v2,ls_v2,apex,sendrecv_v2";
    static final int AUTH_USER_CONFIRM_TIMEOUT_MS = 30_000;
    static final int CAP_PER_STREAM = 256 * 1024;

    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private final CancelToken cancel;
    private int maxPayload = AdbProtocol.OUR_MAX_PAYLOAD;
    private String banner = "";
    private String deviceModel = "";
    private Set<String> features = Collections.emptySet();
    private int localId = 1;
    private int remoteId = -1;
    private boolean streamOpen;

    private AdbConnection(CancelToken cancel) {
        this.cancel = cancel;
    }

    /** Token of the shell call in progress (null otherwise); cancelling it closes the socket. */
    private volatile CancelToken callCancel;

    private boolean isCancelled() {
        CancelToken c = callCancel;
        return cancel.isCancelled() || (c != null && c.isCancelled());
    }

    String banner() { return banner; }
    String deviceModel() { return deviceModel; }
    Set<String> features() { return features; }
    boolean shellV2() { return features.contains("shell_v2"); }

    static AdbConnection open(String host, int port, AdbKeys keys, long connectTimeoutMs,
                              CancelToken cancel) throws AdbException {
        long deadline = System.currentTimeMillis() + Math.max(connectTimeoutMs, 1000);
        AdbConnection c = new AdbConnection(cancel);
        try {
            c.socket = new Socket();
            Runnable unhook = cancel.onCancel(() -> {
                try {
                    c.socket.close();
                } catch (IOException ignored) { }
            });
            try {
                c.socket.connect(new InetSocketAddress(host, port),
                    (int) Math.min(deadline - System.currentTimeMillis(), Integer.MAX_VALUE));
                c.socket.setTcpNoDelay(true);
                c.handshake(host, keys, deadline);
            } finally {
                unhook.run();
            }
            return c;
        } catch (AdbException e) {
            c.closeQuietly();
            throw e;
        } catch (Exception e) {
            c.closeQuietly();
            throw AdbException.network(e, "connect " + host + ":" + port + ": " + e);
        }
    }

    private void handshake(String host, AdbKeys keys, long deadline) throws Exception {
        in = socket.getInputStream();
        out = socket.getOutputStream();
        socket.setSoTimeout(soTimeoutLeft(deadline));
        write(AdbProtocol.Message.of(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION,
            AdbProtocol.OUR_MAX_PAYLOAD, HOST_BANNER.getBytes(StandardCharsets.UTF_8)));

        AdbProtocol.Message m = read(deadline);
        if (m.command == AdbProtocol.A_STLS) {
            write(AdbProtocol.Message.of(AdbProtocol.A_STLS, AdbProtocol.A_STLS_VERSION, 0, null));
            javax.net.ssl.SSLContext ctx = TlsContexts.forKey(keys.keyPair);
            SSLSocket tls = TlsContexts.wrapAsClient(ctx, socket, host, socket.getPort());
            tls.setSoTimeout(soTimeoutLeft(deadline));
            try {
                tls.startHandshake();
            } catch (Exception e) {
                try {
                    tls.close();
                } catch (IOException ignored) { }
                throw new AdbException(AdbException.Kind.NOT_PAIRED,
                    "Устройство не доверяет этому ключу — выполните сопряжение заново",
                    "tls handshake after STLS failed: " + e, e);
            }
            in = tls.getInputStream();
            out = tls.getOutputStream();
            socket = tls; // closing tls closes the underlying socket
            try {
                m = read(deadline);
            } catch (javax.net.ssl.SSLException | EOFException | java.net.SocketException e) {
                // TLS 1.3: the client finishes its handshake BEFORE the server has judged its
                // certificate, so an unknown adb key shows up here as an alert or an immediate close.
                if (isCancelled()) throw cancelled();
                throw new AdbException(AdbException.Kind.NOT_PAIRED,
                    "Устройство не доверяет этому ключу — выполните сопряжение заново",
                    "device dropped the TLS session right after the handshake: " + e, e);
            }
        }

        long authDeadline = System.currentTimeMillis() + AUTH_USER_CONFIRM_TIMEOUT_MS;
        boolean sentPublicKey = false;
        while (m.command != AdbProtocol.A_CNXN) {
            if (m.command == AdbProtocol.A_STLS) {
                // device asks again for TLS (should not happen after upgrade)
                write(AdbProtocol.Message.of(AdbProtocol.A_STLS, AdbProtocol.A_STLS_VERSION, 0, null));
            } else if (m.command == AdbProtocol.A_AUTH && m.arg0 == AdbProtocol.ADB_AUTH_TOKEN) {
                if (m.payload.length != 20) {
                    throw new AdbException(AdbException.Kind.PROTOCOL,
                        "Устройство передало некорректный запрос авторизации",
                        "AUTH TOKEN size=" + m.payload.length);
                }
                write(AdbProtocol.Message.of(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_SIGNATURE, 0,
                    keys.signToken(m.payload)));
                if (!sentPublicKey) {
                    sentPublicKey = true;
                    write(AdbProtocol.Message.of(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_RSAPUBLICKEY,
                        0, (keys.publicKeyLine + "\0").getBytes(StandardCharsets.UTF_8)));
                }
                if (System.currentTimeMillis() > authDeadline) {
                    throw new AdbException(AdbException.Kind.AUTH_DENIED,
                        "Устройство не подтвердило доступ — подтвердите запрос на экране телефона",
                        "classic AUTH timed out waiting for user confirmation");
                }
            } else {
                throw new AdbException(AdbException.Kind.PROTOCOL,
                    "Устройство ответило неожиданным сообщением",
                    "handshake: unexpected " + m.commandName());
            }
            socket.setSoTimeout(soTimeoutLeft(Math.min(deadline, authDeadline)));
            m = read(Math.min(deadline, authDeadline));
        }

        maxPayload = (int) Math.min(Math.max(m.arg1, 1024), AdbProtocol.OUR_MAX_PAYLOAD);
        banner = new String(m.payload, StandardCharsets.UTF_8);
        features = parseFeatures(banner);
        deviceModel = parseModel(banner);
    }

    static Set<String> parseFeatures(String banner) {
        for (String part : banner.split(";")) {
            if (part.startsWith("features=")) {
                Set<String> out = new LinkedHashSet<>();
                Collections.addAll(out, part.substring("features=".length()).split(","));
                return out;
            }
        }
        return Collections.emptySet();
    }

    static String parseModel(String banner) {
        int prefix = banner.indexOf("::");
        for (String part : (prefix >= 0 ? banner.substring(prefix + 2) : banner).split(";")) {
            if (part.startsWith("ro.product.model=")) {
                return part.substring("ro.product.model=".length());
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ shell

    /** Runs one command; returns parsed v2 output or legacy combined output. */
    ShellOutcome shell(String command, long timeoutMs, CancelToken callToken) throws AdbException {
        callCancel = callToken;
        Runnable unhook = callToken.onCancel(this::closeQuietly);
        try {
            return shellInternal(command, timeoutMs);
        } finally {
            unhook.run();
            callCancel = null;
        }
    }

    private ShellOutcome shellInternal(String command, long timeoutMs) throws AdbException {
        long deadline = System.currentTimeMillis() + Math.max(timeoutMs, 100);
        String legacyMarker = null;
        String dest;
        if (shellV2()) {
            dest = "shell,v2,raw:" + command;
        } else {
            legacyMarker = "__JV_EXIT";
            dest = "shell:" + command + "; echo " + legacyMarker + ":$?";
        }
        openStream(dest, deadline);
        try {
            ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
            ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
            boolean[] truncated = {false};
            int[] exit = {-1};
            ByteQueue q = new ByteQueue();

            while (true) {
                checkCancel();
                if (System.currentTimeMillis() >= deadline) {
                    closeStream();
                    return ShellOutcome.timedOut(toStr(outBuf), toStr(errBuf), truncated[0]);
                }
                socket.setSoTimeout(soTimeoutLeft(deadline));
                AdbProtocol.Message m;
                try {
                    m = read(deadline);
                } catch (SocketTimeoutException e) {
                    closeStream();
                    return ShellOutcome.timedOut(toStr(outBuf), toStr(errBuf), truncated[0]);
                } catch (EOFException | java.net.SocketException e) {
                    if (isCancelled()) throw cancelled();
                    // device closed without CLSE — treat as end of stream
                    break;
                }
                if (m.command == AdbProtocol.A_WRTE) {
                    q.push(m.payload);
                    parseShellPackets(q, outBuf, errBuf, exit, truncated);
                    write(AdbProtocol.Message.of(AdbProtocol.A_OKAY, localId, remoteId, null));
                } else if (m.command == AdbProtocol.A_CLSE) {
                    parseShellPackets(q, outBuf, errBuf, exit, truncated);
                    streamOpen = false;
                    break;
                } else if (m.command == AdbProtocol.A_OKAY) {
                    // ack for our previous WRTE — nothing buffered, continue
                } else if (m.command == AdbProtocol.A_CNXN || m.command == AdbProtocol.A_AUTH) {
                    // ignore late connect/auth noise
                }
            }

            if (exit[0] >= 0) {
                return new ShellOutcome(exit[0], toStr(outBuf), toStr(errBuf), false, truncated[0]);
            }
            // legacy: parse the trailing __JV_EXIT marker the appended echo produced
            String combined = toStr(outBuf);
            if (legacyMarker == null) {
                return new ShellOutcome(-1, combined, toStr(errBuf), false, truncated[0]);
            }
            int idx = combined.lastIndexOf(legacyMarker + ":");
            if (idx >= 0) {
                String tail = combined.substring(idx + legacyMarker.length() + 1).trim();
                String head = combined.substring(0, idx);
                int code = -1;
                try {
                    code = Integer.parseInt(tail);
                } catch (NumberFormatException ignored) { }
                return new ShellOutcome(code, stripMarkerLine(head),
                    toStr(errBuf), false, truncated[0]);
            }
            return new ShellOutcome(-1, combined, toStr(errBuf), false, truncated[0]);
        } catch (IOException e) {
            if (isCancelled()) throw cancelled();
            throw AdbException.network(e, "shell io: " + e);
        }
    }

    private static String stripMarkerLine(String s) {
        int nl = s.lastIndexOf('\n');
        return (nl >= 0 ? s.substring(0, nl) : "").trim() + (nl >= 0 ? "\n" : "");
    }

    private void parseShellPackets(ByteQueue q, ByteArrayOutputStream outBuf,
                                   ByteArrayOutputStream errBuf, int[] exit, boolean[] truncated) {
        while (true) {
            if (q.size() < 5) return;
            int id = q.peek(0) & 0xff;
            long len = AdbProtocol.getU32(q.peekBytes(1, 4), 0) & 0xffffffffL;
            if (q.size() < 5 + len) return;
            q.drop(5);
            byte[] data = q.take((int) len);
            if (id == 1) append(outBuf, data, CAP_PER_STREAM, truncated);
            else if (id == 2) append(errBuf, data, CAP_PER_STREAM, truncated);
            else if (id == 3 && data.length >= 1) exit[0] = data[0] & 0xff;
            // 0 stdin, 4 close-stdin, 5 winsize: not produced by the device on this stream
        }
    }

    private static void append(ByteArrayOutputStream buf, byte[] data, int cap, boolean[] truncated) {
        if (buf.size() >= cap) {
            truncated[0] = true;
            return;
        }
        int room = cap - buf.size();
        if (data.length > room) {
            buf.write(data, 0, room);
            truncated[0] = true;
        } else {
            buf.write(data, 0, data.length);
        }
    }

    private static String toStr(ByteArrayOutputStream b) {
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ stream plumbing

    private void openStream(String destination, long deadline) throws AdbException {
        try {
            localId++;
            write(AdbProtocol.Message.of(AdbProtocol.A_OPEN, localId, 0,
                (destination + "\0").getBytes(StandardCharsets.UTF_8)));
            while (true) {
                checkCancel();
                socket.setSoTimeout(soTimeoutLeft(deadline));
                AdbProtocol.Message m = read(deadline);
                if (m.command == AdbProtocol.A_OKAY && m.arg1 == localId) {
                    remoteId = m.arg0;
                    streamOpen = true;
                    return;
                }
                if (m.command == AdbProtocol.A_CLSE && m.arg1 == localId) {
                    throw new AdbException(AdbException.Kind.REMOTE,
                        "Устройство отвергло команду", "OPEN refused for '" + destination + "'");
                }
            }
        } catch (AdbException e) {
            throw e;
        } catch (IOException e) {
            if (isCancelled()) throw cancelled();
            throw AdbException.network(e, "OPEN io: " + e);
        }
    }

    private void closeStream() {
        if (streamOpen && remoteId > 0) {
            try {
                write(AdbProtocol.Message.of(AdbProtocol.A_CLSE, localId, remoteId, null));
            } catch (IOException ignored) { }
            streamOpen = false;
        }
    }

    private void checkCancel() throws AdbException {
        if (isCancelled()) throw cancelled();
    }

    private static AdbException cancelled() {
        return new AdbException(AdbException.Kind.CANCELLED, "Операция отменена", "cancelled by token");
    }

    private void write(AdbProtocol.Message m) throws IOException {
        AdbProtocol.write(out, m);
    }

    private AdbProtocol.Message read(long deadline) throws IOException {
        return AdbProtocol.read(in, Math.max(AdbProtocol.OUR_MAX_PAYLOAD, maxPayload));
    }

    private static int soTimeoutLeft(long deadline) {
        long left = deadline - System.currentTimeMillis();
        return (int) Math.max(Math.min(left, 60_000), 1);
    }

    @Override
    public void close() {
        try {
            closeStream();
        } finally {
            closeQuietly();
        }
    }

    private void closeQuietly() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) { }
    }

    /** Parsed result of one shell command (pre-AdbShellResult, strings already decoded). */
    static final class ShellOutcome {
        final int exitCode;
        final String stdout;
        final String stderr;
        final boolean timedOut;
        final boolean truncated;

        ShellOutcome(int exitCode, String stdout, String stderr, boolean timedOut, boolean truncated) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.timedOut = timedOut;
            this.truncated = truncated;
        }

        static ShellOutcome timedOut(String stdout, String stderr, boolean truncated) {
            return new ShellOutcome(-1, stdout, stderr, true, truncated);
        }
    }

    /** Growable FIFO of bytes with prefix peeking (for shell v2 packet reassembly). */
    private static final class ByteQueue {
        private final List<byte[]> chunks = new ArrayList<>();
        private int size;

        synchronized void push(byte[] b) {
            if (b.length == 0) return;
            chunks.add(b);
            size += b.length;
        }

        synchronized int size() {
            return size;
        }

        synchronized int peek(int off) {
            int i = 0;
            for (byte[] c : chunks) {
                if (off < c.length) return c[off] & 0xff;
                off -= c.length;
                i++;
            }
            throw new IndexOutOfBoundsException();
        }

        synchronized byte[] peekBytes(int off, int n) {
            byte[] out = new byte[n];
            int done = 0, ci = 0;
            while (done < n) {
                if (ci >= chunks.size()) throw new IndexOutOfBoundsException();
                byte[] c = chunks.get(ci++);
                if (off >= c.length) { off -= c.length; continue; }
                int take = Math.min(c.length - off, n - done);
                System.arraycopy(c, off, out, done, take);
                done += take;
                off = 0;
            }
            return out;
        }

        synchronized void drop(int n) {
            while (n > 0 && !chunks.isEmpty()) {
                byte[] c = chunks.get(0);
                if (n >= c.length) {
                    n -= c.length;
                    size -= c.length;
                    chunks.remove(0);
                } else {
                    byte[] rest = Arrays.copyOfRange(c, n, c.length);
                    chunks.set(0, rest);
                    size -= n;
                    n = 0;
                }
            }
        }

        synchronized byte[] take(int n) {
            byte[] out = new byte[n];
            int done = 0;
            while (done < n) {
                byte[] c = chunks.get(0);
                int take = Math.min(c.length, n - done);
                System.arraycopy(c, 0, out, done, take);
                done += take;
                if (take == c.length) chunks.remove(0);
                else chunks.set(0, Arrays.copyOfRange(c, take, c.length));
                size -= take;
            }
            return out;
        }
    }
}
