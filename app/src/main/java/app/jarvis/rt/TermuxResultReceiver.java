package app.jarvis.rt;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Receives the RUN_COMMAND result bundle (extra "result") for requests started by {@link TermuxBridge}. Not exported. */
public final class TermuxResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String id = TermuxBridge.requestId(intent);
        if (id == null) return;
        Bundle b = intent.getBundleExtra("result");
        TermuxBridge.deliver(id, b);
    }
}
