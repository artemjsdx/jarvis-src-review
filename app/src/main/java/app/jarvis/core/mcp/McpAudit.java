package app.jarvis.core.mcp;

import org.json.JSONObject;

/** Receives one record per tools/call. Implementations must not throw and must not block. */
public interface McpAudit {
    final class Entry {
        public String tool = "", session = "", errorCode = "", errorMessage = "";
        /** Arguments with sensitive values masked and long strings truncated. */
        public JSONObject args = new JSONObject();
        public long durationMs;
        public int flags;
        public boolean ok;
    }

    void record(Entry e);

    McpAudit NOP = new McpAudit() { @Override public void record(Entry e) { } };
}
