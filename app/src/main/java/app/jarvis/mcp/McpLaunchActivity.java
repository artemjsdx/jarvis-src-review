package app.jarvis.mcp;

import android.app.Activity;
import android.os.Bundle;
import app.jarvis.rt.Jarvis;

/** Invisible entry point for `adb shell am start`: starts the bridge and finishes at once without showing any window. */
public class McpLaunchActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Jarvis j = Jarvis.get();
        if (j != null && McpBridge.enabled(j)) {
            McpBridge br = McpBridge.ensure(this, j);
            if (br != null) { try { McpBridge.startService(this); } catch (Throwable ignored) { } }
        }
        finish();
    }
}
