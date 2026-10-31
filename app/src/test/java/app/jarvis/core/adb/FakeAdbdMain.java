package app.jarvis.core.adb;

import java.io.File;

/**
 * Entry point to run FakeAdbd as a separate JVM process (used by the ART check on this bench):
 * <pre>
 *   java app.jarvis.core.adb.FakeAdbdMain pairing  PORT CODE
 *   java app.jarvis.core.adb.FakeAdbdMain connect  PORT [lax|strict]
 *   java app.jarvis.core.adb.FakeAdbdMain classic  PORT
 * </pre>
 * Prints READY on stdout when listening; exits when killed.
 */
public final class FakeAdbdMain {
    private FakeAdbdMain() { }

    public static void main(String[] args) throws Exception {
        try {
            if (args.length < 2) {
                System.err.println("usage: FakeAdbdMain pairing|connect|classic PORT [CODE|lax|strict]");
                System.exit(2);
            }
            int port = Integer.parseInt(args[1]);
            FakeAdbd d;
            switch (args[0]) {
                case "pairing-relay": {
                    // Pairing core behind FakeAdbdEkmProxy.js (Node exports the TLS 1.3 EKM).
                    int relayPort = port; // relay listens on the given port
                    AdbKeys keys = AdbKeys.ephemeral();
                    File dir = java.nio.file.Files.createTempDirectory("fakeadbd-pem").toFile();
                    File[] pem = FakeAdbd.writeIdentityPem(dir, keys);
                    FakeAdbd relay = new FakeAdbd(FakeAdbd.Mode.PAIRING_RELAY,
                        args.length > 2 ? args[2] : "123456", keys, null, false, relayPort);
                    System.out.println("PEM " + pem[0].getAbsolutePath() + " " + pem[1].getAbsolutePath());
                    d = relay;
                    break;
                }
                case "connect":
                    // lax: any client certificate accepted (the ART check uses a fresh key)
                    d = new FakeAdbd(FakeAdbd.Mode.CONNECT_TLS, null, AdbKeys.ephemeral(), null,
                        false, port);
                    break;
                case "classic":
                    d = new FakeAdbd(FakeAdbd.Mode.CONNECT_CLASSIC, null, AdbKeys.ephemeral(), null,
                        false, port);
                    break;
                default:
                    System.err.println("unknown mode " + args[0]);
                    System.exit(2);
                    return;
            }
            System.out.println("READY " + d.getPort());
            System.out.flush();
            Thread.sleep(Long.MAX_VALUE);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }
}
