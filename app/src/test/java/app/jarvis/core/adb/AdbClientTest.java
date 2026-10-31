package app.jarvis.core.adb;

import static org.junit.Assert.*;

import app.jarvis.core.log.Journal;
import app.jarvis.core.util.CancelToken;
import java.io.File;
import java.nio.file.Files;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Our own client against our own server-half on loopback. Pairing goes through the EKM harness
 * (Node/OpenSSL TLS 1.3 terminator + Java pairing core); connect/shell through FakeAdbd's TLS
 * server (plain JDK TLS 1.3). Happy paths + errors.
 */
public class AdbClientTest {
    static EkmPairingHarness harness;

    @BeforeClass
    public static void boot() throws Exception {
        harness = EkmPairingHarness.shared();
        PairingPeer.clientRelayPort = harness.startClientRelay(harness.pairingPort());
    }

    private static File tempDir() throws Exception {
        return Files.createTempDirectory("jarvis-adb-test").toFile();
    }

    private static void setRelayCode(String code) {
        harness.relay().pairingCode = code;
    }

    @Test
    public void pairConnectShellV2_fullFlow() throws Exception {
        AdbKeys serverKeys = AdbKeys.ephemeral();
        FakeAdbd connect = new FakeAdbd(FakeAdbd.Mode.CONNECT_TLS, null, serverKeys, null, false);
        try {
            setRelayCode("456789");
            AdbClient client = new AdbClient(tempDir(), Journal.NOP);
            assertEquals(AdbStatus.State.NOT_PAIRED, client.status().state);

            client.pair("127.0.0.1", harness.pairingPort(), "456789", new CancelToken());
            assertEquals(AdbStatus.State.PAIRED, client.status().state);

            client.connect("127.0.0.1", connect.getPort(), new CancelToken());
            assertEquals(AdbStatus.State.CONNECTED, client.status().state);
            assertEquals("FakeAdbd", client.status().device);

            AdbShellResult r = client.shell("echo hi", 10_000, new CancelToken());
            assertEquals(0, r.exitCode);
            assertEquals("hi", r.stdout.trim());
            assertEquals("", r.stderr.trim());
            assertFalse(r.timedOut);
            assertFalse(r.truncated);

            AdbShellResult e = client.shell("echo boom >&2; exit 3", 10_000, new CancelToken());
            assertEquals(3, e.exitCode);
            assertEquals("boom", e.stderr.trim());

            client.disconnect();
            assertEquals(AdbStatus.State.PAIRED, client.status().state);
        } finally {
            connect.close();
        }
    }

    @Test
    public void persistence_reconnectWithoutRepairing() throws Exception {
        AdbKeys serverKeys = AdbKeys.ephemeral();
        FakeAdbd connect = new FakeAdbd(FakeAdbd.Mode.CONNECT_TLS, null, serverKeys, null, false);
        File dir = tempDir();
        try {
            setRelayCode("111222");
            AdbClient first = new AdbClient(dir, Journal.NOP);
            first.pair("127.0.0.1", harness.pairingPort(), "111222", new CancelToken());
            first.connect("127.0.0.1", connect.getPort(), new CancelToken());
            first.disconnect();

            AdbClient second = new AdbClient(dir, Journal.NOP);
            assertEquals(AdbStatus.State.PAIRED, second.status().state);
            assertTrue(second.reconnect(new CancelToken()));
            AdbShellResult r = second.shell("echo again", 10_000, new CancelToken());
            assertEquals("again", r.stdout.trim());
        } finally {
            connect.close();
        }
    }

    @Test
    public void wrongCodeRejected() throws Exception {
        setRelayCode("123456");
        AdbClient client = new AdbClient(tempDir(), Journal.NOP);
        try {
            client.pair("127.0.0.1", harness.pairingPort(), "654321", new CancelToken());
            fail("expected WRONG_CODE");
        } catch (AdbException e) {
            assertEquals(AdbException.Kind.WRONG_CODE, e.kind);
        }
    }

    @Test
    public void badInputRejected() throws Exception {
        AdbClient client = new AdbClient(tempDir(), Journal.NOP);
        try {
            client.pair("127.0.0.1", 1, "12a456", new CancelToken());
            fail("expected BAD_INPUT");
        } catch (AdbException e) {
            assertEquals(AdbException.Kind.BAD_INPUT, e.kind);
        }
        try {
            client.shell("", 1000, new CancelToken());
            fail("expected BAD_INPUT");
        } catch (AdbException e) {
            assertEquals(AdbException.Kind.BAD_INPUT, e.kind);
        }
    }

    @Test
    public void unpairedClientRejectedByTlsConnect() throws Exception {
        // server trusts only its own key; our client has a different one
        AdbKeys serverKeys = AdbKeys.ephemeral();
        FakeAdbd connect = new FakeAdbd(FakeAdbd.Mode.CONNECT_TLS, null, serverKeys,
            (java.security.interfaces.RSAPublicKey) serverKeys.keyPair.getPublic(), false);
        try {
            AdbClient client = new AdbClient(tempDir(), Journal.NOP);
            try {
                client.connect("127.0.0.1", connect.getPort(), new CancelToken());
                fail("expected NOT_PAIRED");
            } catch (AdbException e) {
                assertEquals(AdbException.Kind.NOT_PAIRED, e.kind);
            }
            assertTrue(connect.clientKeyRejected);
        } finally {
            connect.close();
        }
    }

    @Test
    public void legacyShellWithoutV2() throws Exception {
        AdbKeys serverKeys = AdbKeys.ephemeral();
        FakeAdbd connect = new FakeAdbd(FakeAdbd.Mode.CONNECT_TLS, null, serverKeys, null, true);
        try {
            setRelayCode("222333");
            AdbClient client = new AdbClient(tempDir(), Journal.NOP);
            client.pair("127.0.0.1", harness.pairingPort(), "222333", new CancelToken());
            client.connect("127.0.0.1", connect.getPort(), new CancelToken());
            AdbShellResult r = client.shell("echo legacy", 10_000, new CancelToken());
            assertEquals(0, r.exitCode);
            assertEquals("legacy", r.stdout.trim());
        } finally {
            connect.close();
        }
    }

    @Test
    public void shellTimeout() throws Exception {
        AdbKeys serverKeys = AdbKeys.ephemeral();
        FakeAdbd connect = new FakeAdbd(FakeAdbd.Mode.CONNECT_TLS, null, serverKeys, null, false);
        try {
            setRelayCode("333444");
            AdbClient client = new AdbClient(tempDir(), Journal.NOP);
            client.pair("127.0.0.1", harness.pairingPort(), "333444", new CancelToken());
            client.connect("127.0.0.1", connect.getPort(), new CancelToken());
            long t0 = System.currentTimeMillis();
            AdbShellResult r = client.shell("sleep 30", 900, new CancelToken());
            assertTrue(r.timedOut);
            assertTrue("took " + (System.currentTimeMillis() - t0) + "ms",
                System.currentTimeMillis() - t0 < 10_000);
        } finally {
            connect.close();
        }
    }

    @Test
    public void shellCancel() throws Exception {
        AdbKeys serverKeys = AdbKeys.ephemeral();
        FakeAdbd connect = new FakeAdbd(FakeAdbd.Mode.CONNECT_TLS, null, serverKeys, null, false);
        try {
            setRelayCode("444555");
            AdbClient client = new AdbClient(tempDir(), Journal.NOP);
            client.pair("127.0.0.1", harness.pairingPort(), "444555", new CancelToken());
            client.connect("127.0.0.1", connect.getPort(), new CancelToken());
            CancelToken cancel = new CancelToken();
            Thread canceller = new Thread(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) { }
                cancel.cancel();
            });
            canceller.start();
            long t0 = System.currentTimeMillis();
            try {
                client.shell("sleep 30", 60_000, cancel);
                fail("expected CANCELLED");
            } catch (AdbException e) {
                assertEquals(AdbException.Kind.CANCELLED, e.kind);
            }
            assertTrue("took " + (System.currentTimeMillis() - t0) + "ms",
                System.currentTimeMillis() - t0 < 5_000);
        } finally {
            connect.close();
        }
    }

    @Test
    public void largeOutputAndStderr() throws Exception {
        AdbKeys serverKeys = AdbKeys.ephemeral();
        FakeAdbd connect = new FakeAdbd(FakeAdbd.Mode.CONNECT_TLS, null, serverKeys, null, false);
        try {
            setRelayCode("555666");
            AdbClient client = new AdbClient(tempDir(), Journal.NOP);
            client.pair("127.0.0.1", harness.pairingPort(), "555666", new CancelToken());
            client.connect("127.0.0.1", connect.getPort(), new CancelToken());
            AdbShellResult big = client.shell("seq 1 20000", 30_000, new CancelToken());
            assertEquals(0, big.exitCode);
            assertEquals(20000, big.stdout.split("\n").length);
            assertFalse(big.truncated);

            AdbShellResult huge = client.shell("seq 1 200000", 60_000, new CancelToken());
            assertTrue(huge.truncated);
            assertTrue(huge.stdout.length() >= 256 * 1024 - 8);
            assertTrue(huge.stdout.length() < 256 * 1024 + 8);
        } finally {
            connect.close();
        }
    }

    @Test
    public void classicAuthFlow() throws Exception {
        AdbKeys serverKeys = AdbKeys.ephemeral();
        FakeAdbd connect = new FakeAdbd(FakeAdbd.Mode.CONNECT_CLASSIC, null, serverKeys, null, false);
        try {
            AdbClient client = new AdbClient(tempDir(), Journal.NOP);
            client.connect("127.0.0.1", connect.getPort(), new CancelToken());
            assertEquals(AdbStatus.State.CONNECTED, client.status().state);
            AdbShellResult r = client.shell("echo classic", 10_000, new CancelToken());
            assertEquals("classic", r.stdout.trim());
            // the client sent its public key during the flow
            assertNotNull(connect.lastPeerInfo);
        } finally {
            connect.close();
        }
    }
}
