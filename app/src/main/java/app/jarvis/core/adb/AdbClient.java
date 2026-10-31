package app.jarvis.core.adb;

import app.jarvis.core.log.Journal;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.util.Redactor;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;

/**
 * Wi-Fi ADB client: one-time pairing (Android 11+ wireless debugging), TLS connect, shell.
 * State (adb key + last endpoint) lives in {@code stateDir}; pairing codes and keys never reach
 * the journal. Not thread-safe beyond coarse synchronization on a single connection.
 */
public final class AdbClient implements AdbApi {
    private static final String STATE_FILE = "adb_state.json";
    private static final int PAIR_TIMEOUT_MS = 15_000;
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    private final File stateDir;
    private final Journal journal;
    private final AdbKeys keys;

    private String savedHost = "";
    private int savedPort = -1;
    private String savedDevice = "";
    private boolean paired;
    private String lastError = "";

    private AdbConnection conn;

    public AdbClient(File stateDir, Journal journal) throws IOException {
        this.stateDir = stateDir;
        this.journal = journal == null ? Journal.NOP : journal;
        if (!stateDir.isDirectory() && !stateDir.mkdirs()) {
            throw new IOException("cannot create state dir " + stateDir);
        }
        this.keys = AdbKeys.loadOrCreate(stateDir);
        readState();
    }

    @Override
    public synchronized AdbStatus status() {
        AdbStatus.State s;
        if (conn != null) s = AdbStatus.State.CONNECTED;
        else if (lastError != null && !lastError.isEmpty()) s = AdbStatus.State.ERROR;
        else if (paired) s = AdbStatus.State.PAIRED;
        else s = AdbStatus.State.NOT_PAIRED;
        String host = savedHost;
        int port = savedPort;
        String device = conn != null ? conn.deviceModel() : savedDevice;
        return new AdbStatus(s, host, port, device, keys.fingerprint, lastError);
    }

    @Override
    public synchronized void pair(String host, int pairingPort, String pairingCode,
                                  CancelToken cancel) throws AdbException {
        validateHostPort(host, pairingPort);
        if (pairingCode == null || !pairingCode.matches("\\d{6}")) {
            throw new AdbException(AdbException.Kind.BAD_INPUT,
                "Код сопряжения — 6 цифр с экрана телефона", "bad pairing code: "
                + Redactor.redact(String.valueOf(pairingCode)));
        }
        lastError = "";
        long t0 = System.currentTimeMillis();
        CancelToken ct = cancel == null ? new CancelToken() : cancel;
        try {
            java.net.Socket raw = new java.net.Socket();
            Runnable unhook = ct.onCancel(() -> {
                try {
                    raw.close();
                } catch (IOException ignored) { }
            });
            try {
                byte[] myPeerInfo = AndroidPubKey.peerInfoRsaPubKey(keys.publicKey(),
                    AdbKeys.LOGIN_AT_HOST);
                PairingPeer.Result r;
                if (PairingPeer.clientRelayPort > 0) {
                    // test hook: Node terminator owns the TLS 1.3 + EKM side
                    raw.connect(new java.net.InetSocketAddress("127.0.0.1",
                        PairingPeer.clientRelayPort), PAIR_TIMEOUT_MS);
                    raw.setTcpNoDelay(true);
                    raw.setSoTimeout(PAIR_TIMEOUT_MS);
                    byte[] ekm = readRelayHeader(raw);
                    r = PairingPeer.relayClient(raw.getInputStream(), raw.getOutputStream(),
                        pairingCode, ekm, myPeerInfo);
                } else {
                    raw.connect(new java.net.InetSocketAddress(host, pairingPort), PAIR_TIMEOUT_MS);
                    raw.setTcpNoDelay(true);
                    raw.setSoTimeout(PAIR_TIMEOUT_MS);
                    r = PairingPeer.client(raw, keys.keyPair, pairingCode,
                        myPeerInfo, System.currentTimeMillis() + PAIR_TIMEOUT_MS).run();
                }
                paired = true;
                savedHost = host;
                savedPort = -1; // connect port is different from the pairing port
                savedDevice = "";
                writeState();
                journal.i(Journal.Cat.ADB, "Сопряжение с " + host + ":" + pairingPort
                    + " выполнено", null, null, System.currentTimeMillis() - t0);
            } finally {
                unhook.run();
            }
        } catch (AdbException e) {
            lastError = e.userMessage;
            journal.e(Journal.Cat.ADB, "Сопряжение не удалось: " + e.userMessage, e);
            throw e;
        } catch (Exception e) {
            lastError = "Не удалось связаться с устройством";
            journal.e(Journal.Cat.ADB, "Сопряжение не удалось", e);
            throw AdbException.network(e, "pair " + host + ":" + pairingPort + ": " + e);
        }
    }

    @Override
    public synchronized void connect(String host, int port, CancelToken cancel) throws AdbException {
        validateHostPort(host, port);
        lastError = "";
        CancelToken ct = cancel == null ? new CancelToken() : cancel;
        long t0 = System.currentTimeMillis();
        closeConn();
        try {
            conn = AdbConnection.open(host, port, keys, CONNECT_TIMEOUT_MS, ct);
            paired = true;
            savedHost = host;
            savedPort = port;
            savedDevice = conn.deviceModel();
            writeState();
            journal.i(Journal.Cat.ADB, "Подключено к " + host + ":" + port
                + (conn.deviceModel().isEmpty() ? "" : " (" + conn.deviceModel() + ")"),
                null, null, System.currentTimeMillis() - t0);
        } catch (AdbException e) {
            lastError = e.userMessage;
            journal.e(Journal.Cat.ADB, "Подключение не удалось: " + e.userMessage, e);
            throw e;
        }
    }

    @Override
    public synchronized boolean reconnect(CancelToken cancel) {
        if (savedHost.isEmpty() || savedPort <= 0) return false;
        try {
            connect(savedHost, savedPort, cancel);
            return true;
        } catch (AdbException e) {
            return false;
        }
    }

    @Override
    public synchronized AdbShellResult shell(String command, long timeoutMs, CancelToken cancel)
        throws AdbException {
        if (command == null || command.isEmpty()) {
            throw new AdbException(AdbException.Kind.BAD_INPUT, "Пустая команда", "empty shell command");
        }
        if (conn == null) {
            if (!reconnect(cancel)) {
                throw new AdbException(AdbException.Kind.NOT_PAIRED,
                    "Нет подключения к устройству — выполните сопряжение и подключение",
                    "shell: no connection");
            }
        }
        long t0 = System.currentTimeMillis();
        try {
            AdbConnection.ShellOutcome o = conn.shell(command, timeoutMs, cancel == null ? new CancelToken() : cancel);
            AdbShellResult res = new AdbShellResult(o.exitCode, o.stdout, o.stderr,
                System.currentTimeMillis() - t0, o.timedOut, o.truncated);
            journal.i(Journal.Cat.ADB, "shell (" + Redactor.redact(command) + "): exit="
                + res.exitCode + ", " + res.stdout.length() + "+" + res.stderr.length() + "B",
                null, null, res.durationMs);
            return res;
        } catch (AdbException e) {
            lastError = e.userMessage;
            if (e.kind != AdbException.Kind.CANCELLED) {
                journal.e(Journal.Cat.ADB, "Ошибка shell: " + e.userMessage, e);
            }
            if (e.kind != AdbException.Kind.TIMEOUT) {
                closeConn(); // connection is suspect after any IO error
            }
            throw e;
        }
    }

    @Override
    public synchronized void disconnect() {
        closeConn();
        lastError = "";
        journal.i(Journal.Cat.ADB, "Отключено от " + savedHost + ":" + savedPort);
    }

    private void closeConn() {
        if (conn != null) {
            try {
                conn.close();
            } finally {
                conn = null;
            }
        }
    }

    private static byte[] readRelayHeader(java.net.Socket raw) throws IOException {
        String line = PairingPeer.readAsciiLine(raw.getInputStream(), 512);
        if (line == null || !line.startsWith("EKMPROXY1 ")) {
            throw new IOException("bad relay header: " + line);
        }
        return java.util.Base64.getDecoder().decode(line.substring("EKMPROXY1 ".length()));
    }

    private static void validateHostPort(String host, int port) throws AdbException {
        if (host == null || host.isEmpty() || port < 1 || port > 65535) {
            throw new AdbException(AdbException.Kind.BAD_INPUT,
                "Укажите адрес и порт устройства", "bad host/port: " + host + ":" + port);
        }
    }

    private void readState() {
        try {
            File f = new File(stateDir, STATE_FILE);
            if (!f.isFile()) return;
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()),
                StandardCharsets.UTF_8));
            paired = o.optBoolean("paired", false);
            savedHost = o.optString("host", "");
            savedPort = o.optInt("port", -1);
            savedDevice = o.optString("device", "");
        } catch (Exception e) {
            journal.w(Journal.Cat.ADB, "Не удалось прочитать состояние ADB", e, null);
        }
    }

    private void writeState() {
        try {
            JSONObject o = new JSONObject();
            o.put("paired", paired);
            o.put("host", savedHost);
            o.put("port", savedPort);
            o.put("device", savedDevice);
            o.put("keyFingerprint", keys.fingerprint);
            Files.write(new File(stateDir, STATE_FILE).toPath(),
                o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            journal.w(Journal.Cat.ADB, "Не удалось сохранить состояние ADB", e, null);
        }
    }
}
