package app.jarvis.core.adb;

/** Current state of the Wi-Fi ADB subsystem, for display in settings. */
public final class AdbStatus {
    public enum State { NOT_PAIRED, PAIRED, CONNECTING, CONNECTED, ERROR }

    public final State state;
    public final String host;
    public final int port;
    /** ro.product.model from the device banner, or a short banner-derived name; "" when unknown. */
    public final String device;
    /** Uppercase colon-separated SHA-256 fingerprint of our adb key's SPKI DER (adb-style). */
    public final String keyFingerprint;
    /** Last error in Russian (safe), "" when the state is not ERROR. */
    public final String lastError;

    public AdbStatus(State state, String host, int port, String device, String keyFingerprint,
                     String lastError) {
        this.state = state;
        this.host = host == null ? "" : host;
        this.port = port;
        this.device = device == null ? "" : device;
        this.keyFingerprint = keyFingerprint == null ? "" : keyFingerprint;
        this.lastError = lastError == null ? "" : lastError;
    }

    @Override
    public String toString() {
        return "AdbStatus{" + state + " " + host + ":" + port + " device=" + device + "}";
    }
}
