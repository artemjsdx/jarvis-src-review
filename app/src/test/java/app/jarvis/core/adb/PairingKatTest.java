package app.jarvis.core.adb;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Known-answer tests that need neither Node nor adb. The "bob_*" vectors were captured from a real
 * `adb pair` run (see the header of adb_pairing_kat.properties), so a regression in SPAKE2, the
 * password/transcript hashing, HKDF or the GCM nonce layout fails here first.
 */
public class PairingKatTest {
    private static Properties kat;

    @BeforeClass
    public static void load() throws Exception {
        File dir = new File("").getAbsoluteFile();
        while (dir != null && !new File(dir, "app/src/test/resources/adb_pairing_kat.properties").isFile()) {
            dir = dir.getParentFile();
        }
        assertNotNull("adb_pairing_kat.properties not found upward from cwd", dir);
        kat = new Properties();
        kat.load(Files.newBufferedReader(
            new File(dir, "app/src/test/resources/adb_pairing_kat.properties").toPath(),
            StandardCharsets.ISO_8859_1));
    }

    private static byte[] hex(String key) {
        String s = kat.getProperty(key);
        assertNotNull(key, s);
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    @Test
    public void bobAgainstRealAdbCapture() throws Exception {
        byte[] password = hex("password");
        Spake2 bob = Spake2.withSeed(Spake2.Role.BOB, password, hex("bob_seed"));
        assertArrayEquals("Bob SPAKE2 message", hex("bob_msg"), bob.message());
        byte[] key = bob.processMessage(hex("adb_alice_msg"));
        assertArrayEquals("SPAKE2 shared key", hex("bob_key"), key);

        // The packet the REAL adb client encrypted must open with the key we derived.
        byte[] ct = java.util.Base64.getDecoder().decode(kat.getProperty("adb_peerinfo_ct"));
        byte[] plain = AesGcm.fromSpakeKey(key).decrypt(ct);
        assertNotNull("real adb PeerInfo did not authenticate", plain);
        assertEquals(PairingPeer.PEER_INFO_SIZE, plain.length);
        assertEquals("PeerInfo type = ADB_RSA_PUB_KEY", PairingPeer.TYPE_RSA_PUB_KEY, plain[0]);
        String line = new String(plain, 1, 40, StandardCharsets.US_ASCII);
        assertTrue("adb public key line expected: " + line, line.startsWith("QAAAA"));
    }

    @Test
    public void aliceAgainstPythonReference() {
        byte[] password = hex("password");
        Spake2 alice = Spake2.withSeed(Spake2.Role.ALICE, password, hex("alice_seed"));
        assertArrayEquals("Alice SPAKE2 message", hex("alice_msg"), alice.message());
        assertArrayEquals("Alice key from the captured Bob message",
            hex("alice_key_with_bob_msg"), alice.processMessage(hex("bob_msg")));
    }

    @Test
    public void wrongPasswordDoesNotOpenRealCapture() {
        byte[] password = hex("password");
        password[0] ^= 1;
        Spake2 bob = Spake2.withSeed(Spake2.Role.BOB, password, hex("bob_seed"));
        byte[] key = bob.processMessage(hex("adb_alice_msg"));
        byte[] ct = java.util.Base64.getDecoder().decode(kat.getProperty("adb_peerinfo_ct"));
        assertNull(AesGcm.fromSpakeKey(key).decrypt(ct));
    }

    @Test
    public void gcmNonceCountersAreIndependentPerDirection() throws Exception {
        byte[] key = new byte[64];
        AesGcm a = AesGcm.fromSpakeKey(key);
        AesGcm b = AesGcm.fromSpakeKey(key);
        byte[] m1 = a.encrypt(new byte[]{1});
        byte[] m2 = a.encrypt(new byte[]{2});
        assertFalse(java.util.Arrays.equals(m1, m2));
        assertArrayEquals(new byte[]{1}, b.decrypt(m1));
        assertArrayEquals(new byte[]{2}, b.decrypt(m2));
        // b's encrypt counter is still 0, so it matches a's first message nonce
        assertArrayEquals(m1, AesGcm.fromSpakeKey(key).encrypt(new byte[]{1}));
    }

    /** Whole client/server exchange over loopback: wire header bytes, PeerInfo types both ways. */
    @Test
    public void exchangeWireFormatAndPeerTypes() throws Exception {
        byte[] ekm = new byte[64];
        for (int i = 0; i < ekm.length; i++) ekm[i] = (byte) (i * 7);
        byte[] clientInfo = AndroidPubKey.peerInfoRsaPubKey(AdbKeys.ephemeral().publicKey(), "t@h");
        byte[] serverInfo = FakeAdbd.deviceGuidPeerInfo();
        ExecutorService ex = Executors.newFixedThreadPool(2);
        try (ServerSocket ss = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            Future<PairingPeer.Result> srv = ex.submit(new Callable<PairingPeer.Result>() {
                @Override public PairingPeer.Result call() throws Exception {
                    try (Socket s = ss.accept()) {
                        s.setSoTimeout(20_000);
                        return PairingPeer.relayServer(s.getInputStream(), s.getOutputStream(),
                            "135790", ekm, serverInfo);
                    }
                }
            });
            try (Socket c = new Socket(ss.getInetAddress(), ss.getLocalPort())) {
                c.setSoTimeout(20_000);
                PairingPeer.Result got = PairingPeer.relayClient(c.getInputStream(), c.getOutputStream(),
                    "135790", ekm, clientInfo);
                assertEquals("client receives the device GUID", PairingPeer.TYPE_DEVICE_GUID, got.peerType);
                String guid = new String(got.peerData, 0, 19, StandardCharsets.UTF_8);
                assertEquals("adb-FAKE0001-abcdef", guid);
            }
            PairingPeer.Result atServer = srv.get(20, TimeUnit.SECONDS);
            assertEquals("server receives the host RSA key", PairingPeer.TYPE_RSA_PUB_KEY, atServer.peerType);
            assertEquals(clientInfo[1], atServer.peerData[0]);
        } finally {
            ex.shutdownNow();
        }
    }

    @Test
    public void firstPacketHeaderIsVersion1Spake2Msg0Len32() throws Exception {
        try (ServerSocket ss = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            ExecutorService ex = Executors.newSingleThreadExecutor();
            try {
                Future<byte[]> hdr = ex.submit(new Callable<byte[]>() {
                    @Override public byte[] call() throws Exception {
                        try (Socket s = ss.accept()) {
                            s.setSoTimeout(20_000);
                            byte[] h = new byte[6 + 32];
                            new DataInputStream(s.getInputStream()).readFully(h);
                            return h;
                        }
                    }
                });
                try (Socket c = new Socket(ss.getInetAddress(), ss.getLocalPort())) {
                    c.setSoTimeout(3_000);
                    try {
                        PairingPeer.relayClient(c.getInputStream(), c.getOutputStream(), "000000",
                            new byte[64], new byte[PairingPeer.PEER_INFO_SIZE]);
                        fail("peer never answers");
                    } catch (AdbException expected) {
                        // peer closed / timed out: only the first packet matters here
                    }
                }
                byte[] h = hdr.get(20, TimeUnit.SECONDS);
                assertArrayEquals(new byte[]{1, 0, 0, 0, 0, 32}, java.util.Arrays.copyOf(h, 6));
            } finally {
                ex.shutdownNow();
            }
        }
    }
}
