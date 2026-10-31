package app.jarvis.mcp;

import android.app.Activity;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.FrameMetrics;
import android.view.Window;
import app.jarvis.core.mcp.FrameStats;
import app.jarvis.core.mcp.McpRegistry;
import app.jarvis.core.mcp.McpResult;
import app.jarvis.core.mcp.McpTools;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONObject;

/** perf_frames: frame times of the visible window over a period (FrameMetrics, API 24+). */
final class PerfTools {
    private PerfTools() { }


    static void register(McpRegistry r, McpBridge b) {
        r.register(McpTools.tool("perf_frames", "Плавность кадров окна за период: число кадров, доля рывков (дольше 16,7 мс), p50/p90/p99 и 5 самых долгих кадров. Нужно видимое окно.",
            McpTools.Schema.obj().integer("durationMs", "Сколько ждать кадров, мс (200..20000, по умолчанию 3000)", false), (a, c) -> {
                if (Build.VERSION.SDK_INT < 24) return Tl.err("NOT_AVAILABLE", "FrameMetrics требует Android 7.0+", "sdk " + Build.VERSION.SDK_INT);
                Activity act = LiveUi.activity();
                if (act == null || !LiveUi.visible()) return Tl.err("NOT_VISIBLE", "Окно Jarvis не на экране: откройте приложение на телефоне.", "no visible window");
                return McpResult.json(measure(act.getWindow(), Tl.clamp(Tl.intArg(a, "durationMs", 3000), 200, 20000)));
            }).timeout(30_000).readOnly());
    }

    static JSONObject measure(Window w, int durationMs) throws InterruptedException {
        final List<Double> totals = new CopyOnWriteArrayList<>();
        Window.OnFrameMetricsAvailableListener l = new Window.OnFrameMetricsAvailableListener() {
            @Override public void onFrameMetricsAvailable(Window window, FrameMetrics fm, int dropped) {
                totals.add(fm.getMetric(FrameMetrics.TOTAL_DURATION) / 1e6);
            }
        };
        w.addOnFrameMetricsAvailableListener(l, new Handler(Looper.getMainLooper()));
        try {
            Thread.sleep(durationMs);
        } finally {
            w.removeOnFrameMetricsAvailableListener(l);
        }
        return FrameStats.summarize(new ArrayList<>(totals), durationMs);
    }

}
