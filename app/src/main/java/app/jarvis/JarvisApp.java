package app.jarvis;

import android.app.Application;
import app.jarvis.core.log.Journal;
import app.jarvis.rt.Jarvis;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class JarvisApp extends Application {
    public static volatile Throwable startupFailure;

    @Override public void onCreate() {
        super.onCreate();
        installCrashHandler();
        try {
            Jarvis j = Jarvis.init(this);
            Modules.attach(this, j);
            try { app.jarvis.mcp.McpBridge.installIfEnabled(this, j); } catch (Throwable t) { j.journal.e("mcp", "Мост MCP не запущен", t); }
        } catch (Throwable t) {
            startupFailure = t;
            writeCrash("startup", t);
        }
    }

    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                writeCrash(t.getName(), e);
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    private void writeCrash(String thread, Throwable e) {
        try {
            File dir = new File(getFilesDir(), "crash");
            dir.mkdirs();
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String text = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + " thread=" + thread + " v" + BuildConfig.VERSION_NAME + " " + BuildConfig.GIT_REV + "\n" + sw;
            try (FileWriter w = new FileWriter(new File(dir, "last.txt"))) { w.write(app.jarvis.core.util.Redactor.redact(text)); }
            Jarvis j = Jarvis.get();
            if (j != null) j.journal.logNow(Journal.Cat.SYSTEM, Journal.Level.ERROR,
                "Необработанное исключение в потоке " + thread + ": " + e, Journal.errorDetails(e, thread));
        } catch (Throwable ignored) { }
    }
}
