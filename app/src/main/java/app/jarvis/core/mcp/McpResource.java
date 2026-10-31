package app.jarvis.core.mcp;

/** A readable resource (jarvis://state, ...). */
public interface McpResource {
    String uri();
    String name();
    String description();
    String mimeType();
    String read() throws Exception;
}
