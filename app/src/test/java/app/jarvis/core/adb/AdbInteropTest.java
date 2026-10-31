package app.jarvis.core.adb;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The strongest check available on this bench: the REAL Termux adb client (BoringSSL, v35.0.2)
 * against FakeAdbd on 127.0.0.1 — pairing (right and wrong code), TLS connect with client-cert
 * trust based on the paired key, and shell. Proves the SPAKE2/exporter/PeerInfo halves match.
 */
public class AdbInteropTest {
    private static String adbPath;
    /** Private adb server port: the global one (5037) may be wedged by someone else's session, and a test must not depend on it. */
    private static String serverPort;
    static EkmPairingHarness harness;
    /** Foreground private adb server (its log is attached to failures); null if it could not be started. */
    private static Process serverProc;
    private static File serverLog;

    @BeforeClass
    public static void boot() throws Exception {
        harness = EkmPairingHarness.shared();
        for (String p : new String[]{"/data/data/com.termux/files/usr/bin/adb", "/usr/bin/adb"}) {
            if (new File(p).canExecute()) {
                adbPath = p;
                try (java.net.ServerSocket ss = new java.net.ServerSocket(0)) {
                    serverPort = String.valueOf(ss.getLocalPort());
                }
                startPrivateServer();
                return;
            }
        }
        org.junit.Assume.assumeTrue("adb binary not found", false);
    }

    private static void startPrivateServer() throws Exception {
        serverLog = File.createTempFile("adb-interop-server", ".log");
        ProcessBuilder pb = new ProcessBuilder(adbPath, "-L", "tcp:" + serverPort, "server", "nodaemon")
            .redirectErrorStream(true).redirectOutput(serverLog);
        pb.environment().put("ANDROID_ADB_SERVER_PORT", serverPort);
        pb.environment().put("ADB_TRACE", "transport,auth");
        serverProc = pb.start();
        long until = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < until) {
            try (java.net.Socket s = new java.net.Socket("127.0.0.1", Integer.parseInt(serverPort))) {
                return;
            } catch (java.io.IOException notYet) {
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("private adb server did not start: " + serverLog);
    }

    /** Tail of the private server log for failure messages. */
    private static String serverLogTail() {
        try {
            String all = new String(java.nio.file.Files.readAllBytes(serverLog.toPath()), StandardCharsets.UTF_8);
            return all.length() > 3500 ? all.substring(all.length() - 3500) : all;
        } catch (Exception e) {
            return "(no server log: " + e + ")";
        }
    }

    @AfterClass
    public static void stopPrivateServer() {
        if (serverProc != null) serverProc.destroy();
        if (serverLog != null) serverLog.delete();
        if (adbPath == null || serverPort == null) return;
        try {
            ProcessBuilder pb = new ProcessBuilder(adbPath, "kill-server").redirectErrorStream(true);
            pb.environment().put("ANDROID_ADB_SERVER_PORT", serverPort);
            Process p = pb.start();
            if (!p.waitFor(15, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (Exception ignored) {
        }
    }

    private static ProcessResult run(long timeoutSec, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = adbPath;
        System.arraycopy(args, 0, cmd, 1, args.length);
        // Output goes to a file, not a pipe: the adb client forks the server, which inherits the pipe and
        // would keep readAll() blocked forever, so the timeout below would never be reached.
        File outFile = File.createTempFile("adb-interop", ".out");
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(outFile);
            pb.environment().put("ANDROID_ADB_SERVER_PORT", serverPort);
            Process p = pb.start();
            boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            String out = new String(java.nio.file.Files.readAllBytes(outFile.toPath()), StandardCharsets.UTF_8);
            if (!done) {
                p.destroyForcibly();
                fail("adb timed out: " + java.util.Arrays.toString(args) + ": " + out);
            }
            return new ProcessResult(p.exitValue(), out);
        } finally {
            outFile.delete();
        }
    }

    @Test
    public void realAdbPairConnectShell() throws Exception {
        AdbKeys serverKeys = AdbKeys.ephemeral();
        RSAPub realClientKey = readHostAdbKey();

        // pairing server = shared EKM harness (Node/OpenSSL TLS 1.3 + Java pairing core)
        harness.relay().pairingCode = "246810";
        harness.relay().lastError = null;
        harness.relay().lastPeerInfo = null;
        FakeAdbd connect = new FakeAdbd(FakeAdbd.Mode.CONNECT_TLS, null, serverKeys,
            realClientKey.key, false);
        try {
            // 1. pair with the right code
            ProcessResult ok = run(60, "pair", "127.0.0.1:" + harness.pairingPort(), "246810");
            assertEquals("exit=" + ok.exit + " out=" + ok.out + " relayError=" + harness.relay().lastError,
                0, ok.exit);
            assertTrue("pair output: " + ok.out, ok.out.contains("Successfully paired"));
            assertTrue("pair output: " + ok.out, ok.out.contains("[guid=adb-FAKE0001-abcdef]"));
            // the host reports success as soon as it has OUR PeerInfo; the Java side may still be decrypting its copy
            long until = System.currentTimeMillis() + 10_000;
            while (harness.relay().lastPeerInfo == null && System.currentTimeMillis() < until) {
                Thread.sleep(50);
            }
            assertNotNull("server did not receive peer info: " + harness.relay().lastError,
                harness.relay().lastPeerInfo);
            String adbKeyLine = new String(harness.relay().lastPeerInfo,
                0, nulLen(harness.relay().lastPeerInfo), StandardCharsets.UTF_8);
            assertTrue("peer info should contain adb pubkey: " + adbKeyLine,
                adbKeyLine.startsWith(realClientKey.base64));

            // 2. connect with the paired key (server enforces the client cert)
            ProcessResult conn = run(60, "connect", "127.0.0.1:" + connect.getPort());
            assertEquals("exit=" + conn.exit + " out=" + conn.out, 0, conn.exit);
            assertTrue("adb connect output: " + conn.out, conn.out.contains("connected to "));

            // 3. shell over the TLS transport
            String serial = "127.0.0.1:" + connect.getPort();
            // `adb connect` returns before the TLS transport is online
            ProcessResult wait;
            try {
                wait = run(60, "-s", serial, "wait-for-device");
            } catch (AssertionError e) {
                throw new AssertionError(e.getMessage() + " | server log: " + serverLogTail() + " | devices: " + run(20, "devices", "-l").out
                    + " | fake.lastError=" + connect.lastError + " | connect out: " + conn.out, e);
            }
            assertEquals("exit=" + wait.exit + " out=" + wait.out, 0, wait.exit);
            ProcessResult sh = run(60, "-s", serial, "shell", "echo", "hi-interop");
            assertEquals("exit=" + sh.exit + " out=" + sh.out, 0, sh.exit);
            assertEquals("hi-interop", sh.out.trim());
            ProcessResult sh2 = run(60, "-s", serial, "shell", "echo second; exit 7");
            assertEquals("second", sh2.out.trim());
            assertEquals("exit code of the device shell must reach the host", 7, sh2.exit);

            run(30, "disconnect", serial);
        } finally {
            connect.close();
        }
    }

    @Test
    public void realAdbPairWrongCodeFails() throws Exception {
        harness.relay().pairingCode = "111111";
        harness.relay().lastPeerInfo = null;
        harness.relay().lastError = null;
        try {
            ProcessResult bad = run(60, "pair", "127.0.0.1:" + harness.pairingPort(), "999999");
            assertNotEquals("wrong code must fail: " + bad.out, 0, bad.exit);
            assertFalse(bad.out, bad.out.contains("Successfully paired"));
            // the adb CLI only prints a generic "protocol fault" for FAIL replies; the server side
            // must have rejected it as a wrong code (PeerInfo did not authenticate)
            long until = System.currentTimeMillis() + 10_000;
            while (harness.relay().lastError == null && System.currentTimeMillis() < until) {
                Thread.sleep(50);
            }
            assertTrue("relay error: " + harness.relay().lastError,
                harness.relay().lastError instanceof AdbException
                    && ((AdbException) harness.relay().lastError).kind == AdbException.Kind.WRONG_CODE);
            assertNull("no PeerInfo may be accepted on a wrong code", harness.relay().lastPeerInfo);
        } finally {
            // restore the shared relay code for other tests
            harness.relay().pairingCode = "000000";
        }
    }

    @Test
    public void realAdbConnectWithUnknownKeyRejected() throws Exception {
        AdbKeys serverKeys = AdbKeys.ephemeral();
        // strict server trusts a DIFFERENT key than the real client's
        FakeAdbd connect = new FakeAdbd(FakeAdbd.Mode.CONNECT_TLS, null, serverKeys,
            (java.security.interfaces.RSAPublicKey) serverKeys.keyPair.getPublic(), false);
        try {
            ProcessResult conn = run(60, "connect", "127.0.0.1:" + connect.getPort());
            // adb 35 prints "failed to connect to ..." but may still exit 0: judge by output + server side
            assertFalse("unknown key must be rejected: " + conn.out, conn.out.contains("connected to "));
            assertTrue("expected a failure message: " + conn.out,
                conn.exit != 0 || conn.out.contains("failed to connect"));
            // `adb connect` returns before the transport's TLS handshake reaches the server
            long until = System.currentTimeMillis() + 15_000;
            while (!connect.clientKeyRejected && System.currentTimeMillis() < until) Thread.sleep(100);
            assertTrue("server never saw the unknown client key; adb server log: " + serverLogTail(),
                connect.clientKeyRejected);
            ProcessResult devs = run(30, "devices");
            assertFalse("rejected device must not be online: " + devs.out,
                devs.out.matches("(?s).*127\\.0\\.0\\.1:" + connect.getPort() + "\\s+device.*"));
        } finally {
            connect.close();
        }
    }

    // ------------------------------------------------------------------ helpers

    private static int nulLen(byte[] b) {
        int i = 0;
        while (i < b.length && b[i] != 0) i++;
        return i;
    }

    private static RSAPub readHostAdbKey() throws Exception {
        File home = new File(System.getProperty("user.home"));
        RSAPub r = new RSAPub();
        r.key = AndroidPubKeyTest.readAdbKey(new File(home, ".android/adbkey"));
        r.base64 = java.util.Base64.getEncoder().encodeToString(
            AndroidPubKey.encode(r.key));
        return r;
    }

    private static final class RSAPub {
        java.security.interfaces.RSAPublicKey key;
        String base64;
    }

    private static final class ProcessResult {
        final int exit;
        final String out;
        ProcessResult(int exit, String out) { this.exit = exit; this.out = out; }
    }
}
