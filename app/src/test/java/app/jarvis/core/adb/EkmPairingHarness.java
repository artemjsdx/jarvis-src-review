package app.jarvis.core.adb;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * JVM-test harness for pairing over real TLS 1.3 keying material: starts a FakeAdbd
 * PAIRING_RELAY (Java pairing core) plus Node TLS terminators (OpenSSL exports the EKM —
 * the JVM cannot). Node processes are spawned from FakeAdbdEkmProxy.js next to this class.
 */
public final class EkmPairingHarness {
    private static final Object LOCK = new Object();
    private static EkmPairingHarness instance;

    private FakeAdbd relay;
    private Process serverProxy;
    private int pairingPort = -1;
    private final List<Process> clientProxies = new ArrayList<>();
    private final List<Integer> clientPorts = new ArrayList<>();

    private EkmPairingHarness() throws Exception {
        // node children would otherwise outlive the JUnit JVM
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopAll));
        AdbKeys keys = AdbKeys.ephemeral();
        relay = new FakeAdbd(FakeAdbd.Mode.PAIRING_RELAY, "000000", keys, null, false);
        File pemDir = Files.createTempDirectory("fakeadbd-pem").toFile();
        File[] pem = FakeAdbd.writeIdentityPem(pemDir, keys);
        String js = new File("app/src/test/java/app/jarvis/core/adb/FakeAdbdEkmProxy.js")
            .getAbsolutePath();
        if (!new File(js).isFile()) {
            js = new File("../app/src/test/java/app/jarvis/core/adb/FakeAdbdEkmProxy.js")
                .getAbsolutePath();
        }
        pairingPort = freePort();
        serverProxy = node("server", String.valueOf(pairingPort),
            String.valueOf(relay.getPort()), pem[0].getAbsolutePath(), pem[1].getAbsolutePath(),
            js);
        // A stable code is needed per pairing server; the relay rejects nothing by itself,
        // so tests that need a specific code restart the harness with their own FakeAdbd.
    }

    /** Full harness with the shared pairing server (code 000000). */
    public static EkmPairingHarness shared() throws Exception {
        synchronized (LOCK) {
            if (instance == null) instance = new EkmPairingHarness();
            return instance;
        }
    }

    public int pairingPort() {
        return pairingPort;
    }

    public FakeAdbd relay() {
        return relay;
    }

    /** Starts a Node client terminator targeting the given TLS pairing port; returns the plain port. */
    public synchronized int startClientRelay(int targetPort) throws Exception {
        String js = new File("app/src/test/java/app/jarvis/core/adb/FakeAdbdEkmProxy.js")
            .getAbsolutePath();
        if (!new File(js).isFile()) {
            js = new File("../app/src/test/java/app/jarvis/core/adb/FakeAdbdEkmProxy.js")
                .getAbsolutePath();
        }
        File pemDir = Files.createTempDirectory("fakeadbd-pem").toFile();
        File[] pem = FakeAdbd.writeIdentityPem(pemDir, AdbKeys.ephemeral());
        int listen = freePort();
        clientProxies.add(node("client", String.valueOf(listen), String.valueOf(targetPort),
            pem[0].getAbsolutePath(), pem[1].getAbsolutePath(), js));
        clientPorts.add(listen);
        return listen;
    }

    public synchronized void stopAll() {
        for (Process p : clientProxies) p.destroy();
        clientProxies.clear();
        if (serverProxy != null) serverProxy.destroy();
        if (relay != null) relay.close();
    }

    private static int freePort() throws Exception {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static Process node(String role, String portA, String portB, String cert, String key,
                                String js) throws Exception {
        Process p = new ProcessBuilder("node", js, role, portA, portB, cert, key)
            .redirectErrorStream(true).start();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(),
            StandardCharsets.UTF_8));
        String line;
        long deadline = System.currentTimeMillis() + 20_000;
        while ((line = r.readLine()) != null) {
            if (line.startsWith("READY")) return p;
            if (System.currentTimeMillis() > deadline) break;
        }
        p.destroy();
        throw new IllegalStateException("node proxy did not become READY");
    }
}
