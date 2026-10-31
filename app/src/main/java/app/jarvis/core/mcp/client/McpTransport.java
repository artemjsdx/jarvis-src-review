package app.jarvis.core.mcp.client;

import app.jarvis.core.util.CancelToken;
import org.json.JSONObject;

/**
 * One connection to an external MCP server. Incoming JSON-RPC messages (responses, notifications, server requests)
 * are delivered to the {@link Sink}; the client matches responses to requests by id. Implementations are not thread-safe
 * for concurrent {@link #open}; {@link #post} may be called from several threads.
 */
public interface McpTransport {
    interface Sink {
        void onMessage(JSONObject message);
        /** The server-side channel ended for good (after retries); pending requests will time out. */
        void onChannelLost(String technical);
    }

    /** Prepares the connection (legacy SSE: opens the event stream and waits for the endpoint). Must be called once before post. */
    void open(Sink sink) throws McpException;

    /** Sends one client message. Inline responses (JSON body or SSE body) are delivered to the sink before this returns. */
    void post(JSONObject message, CancelToken cancel) throws McpException;

    /** Forgets the session id so the next initialize starts a fresh one. */
    void resetSession();

    /** Negotiated protocol version, sent on every request after initialize (streamable HTTP). */
    void setProtocolVersion(String version);

    /** Ends the session (streamable HTTP: DELETE with the session id) and closes streams. Never throws. */
    void close();
}
