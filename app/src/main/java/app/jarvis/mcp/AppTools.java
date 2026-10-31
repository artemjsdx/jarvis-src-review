package app.jarvis.mcp;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Debug;
import android.os.Process;
import app.jarvis.BuildConfig;
import app.jarvis.core.mcp.Capabilities;
import app.jarvis.core.mcp.McpRegistry;
import app.jarvis.core.mcp.McpResource;
import app.jarvis.core.mcp.McpResult;
import app.jarvis.core.mcp.McpTools;
import app.jarvis.core.mcp.ToolCatalog;
import app.jarvis.core.util.J;
import app.jarvis.rt.Jarvis;
import org.json.JSONArray;
import org.json.JSONObject;

/** app_info, app_health, app_quit and the jarvis://state resource. */
final class AppTools {
    private AppTools() { }

    static void register(McpRegistry r, final McpBridge b) {
        final Jarvis j = b.j;
        final Context ctx = b.ctx;

        r.register(McpTools.tool("app_info", "Версия, флейвор, SDK, uptime, процесс, разрешения, состояние служб и моста.", (a, c) -> McpResult.json(info(b))).readOnly());

        r.register(McpTools.tool("app_health", "Память, потоки, CPU, батарея, сеть.", (a, c) -> McpResult.json(health(b))).readOnly());

        r.register(McpTools.tool("app_capabilities", "Реестр функций приложения и покрытие инструментами моста (covered / planned / broken).", (a, c) -> {
            java.util.Set<String> have = new java.util.HashSet<>(b.registry.names());
            JSONObject o = Capabilities.report(have);
            J.put(o, "tools", have.size());
            JSONArray stubs = new JSONArray();
            for (String t : ToolCatalog.STUBS) stubs.put(t);
            J.put(o, "stubTools", stubs);
            return McpResult.json(o);
        }).readOnly());

        r.register(McpTools.tool("app_quit", "Завершить процесс приложения (мост остановится; прокладка запустит приложение заново).", (a, c) -> {
            j.journal.log("mcp", app.jarvis.core.log.Journal.Level.INFO, "app_quit по запросу моста", null, null, -1);
            j.main.postDelayed(new Runnable() { @Override public void run() { System.exit(0); } }, 400);
            return McpResult.json(J.put(new JSONObject(), "quitting", true));
        }));

        r.registerResource(new McpResource() {
            @Override public String uri() { return "jarvis://capabilities"; }
            @Override public String name() { return "capabilities"; }
            @Override public String description() { return "Реестр функций и покрытие инструментами"; }
            @Override public String mimeType() { return "application/json"; }
            @Override public String read() { return Capabilities.report(new java.util.HashSet<>(b.registry.names())).toString(); }
        });

        r.registerResource(new McpResource() {
            @Override public String uri() { return "jarvis://state"; }
            @Override public String name() { return "state"; }
            @Override public String description() { return "Состояние приложения"; }
            @Override public String mimeType() { return "application/json"; }
            @Override public String read() { return info(b).toString(); }
        });
    }

    static JSONObject info(McpBridge b) {
        Jarvis j = b.j;
        Context ctx = b.ctx;
        JSONObject o = new JSONObject();
        J.put(o, "package", ctx.getPackageName());
        J.put(o, "version", BuildConfig.VERSION_NAME);
        J.put(o, "rev", BuildConfig.GIT_REV);
        J.put(o, "flavor", BuildConfig.DEBUG ? "debug" : "release");
        J.put(o, "buildTime", BuildConfig.BUILD_TIME);
        J.put(o, "sdk", Build.VERSION.SDK_INT);
        J.put(o, "android", Build.VERSION.RELEASE);
        J.put(o, "device", Build.MANUFACTURER + " " + Build.MODEL);
        J.put(o, "pid", Process.myPid());
        J.put(o, "uptimeMs", System.currentTimeMillis() - j.startedAtMs);
        J.put(o, "assistantState", j.state().name());
        J.put(o, "stateDetail", j.stateDetail());
        J.put(o, "agentRunning", j.agent.isRunning());
        J.put(o, "wakeEnabled", j.voice.isWakeEnabled());
        J.put(o, "micActive", j.voice.isMicActive());
        J.put(o, "speaking", j.speaker.isSpeaking());
        JSONObject mcp = new JSONObject();
        J.put(mcp, "bind", b.bindAddress());
        J.put(mcp, "running", b.running());
        J.put(mcp, "tools", b.registry.tools().size());
        J.put(mcp, "allowAudio", b.gate().allowAudio());
        J.put(mcp, "allowDestructive", b.gate().allowDestructive());
        J.put(mcp, "calls", b.callCount());
        J.put(mcp, "lastTool", b.lastTool());
        J.put(mcp, "lastCallMs", b.lastCallMs());
        J.put(o, "mcp", mcp);
        JSONObject perms = new JSONObject();
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), PackageManager.GET_PERMISSIONS);
            if (pi.requestedPermissions != null) {
                for (String p : pi.requestedPermissions) {
                    String shortName = p.startsWith("android.permission.") ? p.substring(19) : p;
                    J.put(perms, shortName, ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED);
                }
            }
        } catch (Throwable ignored) { }
        J.put(o, "permissions", perms);
        JSONArray svc = new JSONArray();
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            for (ActivityManager.RunningServiceInfo s : am.getRunningServices(100)) {
                if (s.service.getPackageName().equals(ctx.getPackageName())) svc.put(J.put(J.put(new JSONObject(), "name", s.service.getShortClassName()), "foreground", s.foreground));
            }
        } catch (Throwable ignored) { }
        J.put(o, "services", svc);
        J.put(o, "ui", UiTools.liveAvailable() ? "live" : "none");
        return o;
    }

    static JSONObject health(McpBridge b) {
        Context ctx = b.ctx;
        JSONObject o = new JSONObject();
        Runtime rt = Runtime.getRuntime();
        JSONObject mem = new JSONObject();
        J.put(mem, "javaUsedMb", (rt.totalMemory() - rt.freeMemory()) / 1048576);
        J.put(mem, "javaMaxMb", rt.maxMemory() / 1048576);
        J.put(mem, "nativeAllocMb", Debug.getNativeHeapAllocatedSize() / 1048576);
        Debug.MemoryInfo mi = new Debug.MemoryInfo();
        Debug.getMemoryInfo(mi);
        J.put(mem, "pssMb", mi.getTotalPss() / 1024);
        J.put(o, "memory", mem);
        J.put(o, "threads", Thread.getAllStackTraces().size());
        J.put(o, "cpuTimeMs", Process.getElapsedCpuTime());
        J.put(o, "processUptimeMs", android.os.SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime());
        try {
            Intent bi = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bi != null) {
                int level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                JSONObject bat = new JSONObject();
                J.put(bat, "percent", scale > 0 ? level * 100 / scale : -1);
                J.put(bat, "plugged", bi.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0);
                J.put(bat, "tempC", bi.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0);
                J.put(o, "battery", bat);
            }
        } catch (Throwable ignored) { }
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            JSONObject net = new JSONObject();
            J.put(net, "connected", nc != null);
            if (nc != null) {
                J.put(net, "wifi", nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
                J.put(net, "cellular", nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
                J.put(net, "vpn", nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
                J.put(net, "validated", nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
                J.put(net, "downKbps", nc.getLinkDownstreamBandwidthKbps());
                J.put(net, "upKbps", nc.getLinkUpstreamBandwidthKbps());
            }
            J.put(o, "network", net);
        } catch (Throwable ignored) { }
        return o;
    }
}
