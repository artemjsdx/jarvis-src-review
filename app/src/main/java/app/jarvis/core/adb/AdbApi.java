package app.jarvis.core.adb;

import app.jarvis.core.util.CancelToken;

/** Wi-Fi ADB (Wireless debugging) client: one-time pairing, connect, shell. */
public interface AdbApi {
    AdbStatus status();

    /** One-time pairing with host:pairingPort using the 6-digit code from the device screen. Stores the key + peer. */
    void pair(String host, int pairingPort, String pairingCode, CancelToken cancel) throws AdbException;

    /** Connects to host:port (the wireless-debugging connect port shown next to the pairing one). */
    void connect(String host, int port, CancelToken cancel) throws AdbException;

    /** Reconnects to the last saved host/port. Returns false (no exception) when the last endpoint is unknown or unreachable. */
    boolean reconnect(CancelToken cancel);

    /** Runs one shell command. On {@code timeoutMs} expiry returns a result with {@code timedOut=true} instead of throwing. */
    AdbShellResult shell(String command, long timeoutMs, CancelToken cancel) throws AdbException;

    /** Closes the current connection (the pairing and the key stay usable). */
    void disconnect();
}
