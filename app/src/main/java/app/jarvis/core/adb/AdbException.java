package app.jarvis.core.adb;

/** Error of the Wi-Fi ADB client. {@link #userMessage} is Russian and safe to show; {@link #technical} is for logs. */
public class AdbException extends Exception {
    public enum Kind {
        BAD_INPUT, NETWORK, TIMEOUT, PAIRING_REJECTED, WRONG_CODE, NOT_PAIRED, AUTH_DENIED, TLS,
        PROTOCOL, UNSUPPORTED, CANCELLED, REMOTE
    }

    public final Kind kind;
    public final String userMessage;
    public final String technical;

    public AdbException(Kind kind, String userMessage, String technical) {
        super(technical);
        this.kind = kind;
        this.userMessage = userMessage == null ? "" : userMessage;
        this.technical = technical == null ? "" : technical;
    }

    public AdbException(Kind kind, String userMessage, String technical, Throwable cause) {
        super(technical, cause);
        this.kind = kind;
        this.userMessage = userMessage == null ? "" : userMessage;
        this.technical = technical == null ? "" : technical;
    }

    /** Wraps an {@link java.io.IOException} from the network layer as NETWORK (or CANCELLED if the token fired). */
    static AdbException network(Throwable cause, String technical) {
        return new AdbException(Kind.NETWORK, "Не удалось связаться с устройством", technical, cause);
    }
}
