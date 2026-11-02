package app.jarvis;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.core.log.Journal;
import app.jarvis.data.ChatItem;
import app.jarvis.rt.AState;
import app.jarvis.rt.Jarvis;
import app.jarvis.rt.JarvisListener;
import app.jarvis.ui.Backdrop;
import app.jarvis.ui.GlassDrawable;
import app.jarvis.ui.Host;
import app.jarvis.ui.OverlayHost;
import app.jarvis.ui.Screen;
import app.jarvis.ui.Screens;
import app.jarvis.ui.Shell;
import app.jarvis.ui.Ui;
import app.jarvis.ui.theme.Theme;
import app.jarvis.svc.JarvisService;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The single activity: backdrop, slim header (title + live assistant state + settings gear), the current screen, a floating
 * glass dock and the overlay layer. Screens are plain objects (see Screen); navigation is by string targets.
 */
public class MainActivity extends Activity implements Host {
    private static final int REQ_PERM = 71, REQ_PICK_BASE = 100;

    private Jarvis j;
    private Shell shell;
    private Screen pickRequester;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        // Allow screenshots of settings, including the LLM page. Never inherit a secure window flag.
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        j = Jarvis.get();
        if (j == null) { showFatal(JarvisApp.startupFailure); return; }
        Ui.init(this, Theme.resolveDark(j.settings.get("ui.theme", Theme.DARK), getResources().getConfiguration()));
        Ui.refreshMotion(this, j.settings.get("ui.motion", "full"));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        Theme.applyWindow(getWindow(), Ui.P.dark);
        shell = new Shell(this);
        setContentView(shell);
        installInsets(shell);
        shell.attach();
        String start = j.settings.get("ui.lastScreen", "home");
        shell.navigate(start.equals("settings") ? "home" : start);
        handleIntent(getIntent(), true);
        maybeShowCrash();
    }

    @Override protected void onResume() {
        super.onResume();
        // Some vendor transitions can recreate window attributes. Keep in-app screenshots enabled.
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    private void installInsets(final Shell s) {
        s.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @SuppressWarnings("deprecation")
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                int sys = in.getSystemWindowInsetBottom(), stable = in.getStableInsetBottom();
                s.setInsets(in.getSystemWindowInsetTop(), stable, Math.max(0, sys - stable));
                return in;
            }
        });
        s.requestApplyInsets();
    }

    /** Rebuilds the whole shell with the resolved theme; the visible screen keeps its field text and scroll offsets. */
    private void rebuildTheme() {
        if (shell == null || j == null) return;
        boolean dark = Theme.resolveDark(j.settings.get("ui.theme", Theme.DARK), getResources().getConfiguration());
        Ui.setDark(dark);
        Theme.applyWindow(getWindow(), dark);
        final Shell old = shell;
        String id = old.currentId().isEmpty() ? "home" : old.currentId(), arg = old.currentArg();
        old.overlays().dismissAll();
        old.destroy();
        Shell fresh = new Shell(this);
        setContentView(fresh);
        installInsets(fresh);
        shell = fresh;
        fresh.attach();
        fresh.navigate(arg.isEmpty() ? id : id + "/" + arg);
        fresh.copyStateFrom(old);
    }

    @Override public void applyTheme() {
        if (shell != null) shell.post(new Runnable() { @Override public void run() { rebuildTheme(); } });
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (j == null || shell == null || !Theme.SYSTEM.equals(j.settings.get("ui.theme", Theme.DARK))) return;
        if (Theme.resolveDark(Theme.SYSTEM, newConfig) != Ui.P.dark) rebuildTheme();
    }

    private void showFatal(Throwable t) {
        TextView tv = new TextView(this);
        tv.setPadding(48, 96, 48, 48);
        tv.setTextColor(0xFFFFFFFF);
        tv.setBackgroundColor(0xFF07070B);
        tv.setText("Jarvis не смог запуститься.\n\n" + (t == null ? "неизвестная ошибка" : t.toString()));
        setContentView(tv);
    }

    @Override public void navigate(String target) { if (shell != null) shell.navigate(target); }

    @Override public void onBackPressed() { if (shell != null && shell.onBack()) return; moveTaskToBack(true); }

    // ---- Host -----------------------------------------------------------------------------------------------------
    @Override public android.content.Context context() { return this; }
    @Override public Activity activity() { return this; }
    @Override public Jarvis jarvis() { return j; }
    @Override public OverlayHost overlays() { return shell.overlays(); }
    @Override public int bottomInset() { return shell.bottomInset(); }
    @Override public int topInset() { return shell.topInset(); }

    @Override public void pickDocument(Screen requester, int requestCode, String[] mimeTypes, boolean multiple) {
        pickRequester = requester;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mimeTypes != null && mimeTypes.length == 1 ? mimeTypes[0] : "*/*");
        if (mimeTypes != null && mimeTypes.length > 1) i.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
        try { startActivityForResult(i, REQ_PICK_BASE + requestCode); } catch (Throwable t) { shell.overlays().toast("Выбор файлов недоступен на этом устройстве", 2); }
    }

    @Override public void startActivityForResult(Screen requester, Intent intent, int requestCode) {
        pickRequester = requester;
        try { startActivityForResult(intent, REQ_PICK_BASE + requestCode); } catch (Throwable t) { shell.overlays().toast("Не удалось открыть: " + t.getMessage(), 2); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req >= REQ_PICK_BASE && pickRequester != null) pickRequester.onActivityResult(req - REQ_PICK_BASE, res, data);
    }

    @Override public boolean hasPermission(String p) { return Build.VERSION.SDK_INT < 23 || checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    @Override public void requestPermission(String p) {
        if (hasPermission(p)) { j.permissionResult(p, true); return; }
        if (p.equals(Manifest.permission.POST_NOTIFICATIONS) && Build.VERSION.SDK_INT < 33) { j.permissionResult(p, true); return; }
        try { requestPermissions(new String[]{p}, REQ_PERM); } catch (Throwable t) { j.permissionResult(p, false); }
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req != REQ_PERM) return;
        for (int i = 0; i < perms.length; i++) {
            boolean ok = i < results.length && results[i] == PackageManager.PERMISSION_GRANTED;
            j.journal.log(Journal.Cat.PERMISSIONS, ok ? Journal.Level.INFO : Journal.Level.WARN, "Разрешение " + perms[i] + ": " + (ok ? "выдано" : "отклонено"), null, null, -1);
            j.permissionResult(perms[i], ok);
            if (shell.current() != null) shell.current().onPermissionResult(perms[i], ok);
            if (!ok) shell.overlays().toast("Без этого разрешения функция недоступна. Его можно выдать в настройках Android.", 2);
        }
    }

    @Override public void hideKeyboard() {
        View f = getCurrentFocus();
        if (f != null) { InputMethodManager m = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE); if (m != null) m.hideSoftInputFromWindow(f.getWindowToken(), 0); }
    }

    @Override public void copyToClipboard(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) { cm.setPrimaryClip(ClipData.newPlainText(label, text)); shell.overlays().toast("Скопировано", 1); }
    }

    @Override public void shareFile(File f, String mime) {
        try {
            // Files live in app-private storage; hand out a copy through the shared cache is NOT possible without a provider.
            // Use ACTION_SEND with a content copy in external cache dir readable by the target (legacy storage model, target 28).
            File out = new File(getExternalCacheDir(), f.getName());
            try (java.io.FileInputStream in = new FileInputStream(f); java.io.FileOutputStream os = new java.io.FileOutputStream(out)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            }
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(mime == null ? "*/*" : mime);
            i.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(out));
            startActivity(Intent.createChooser(i, "Поделиться"));
        } catch (Throwable t) {
            shell.overlays().toast("Не удалось поделиться: " + t.getMessage(), 2);
        }
    }

    @Override public void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Throwable t) { shell.overlays().toast("Нет приложения для открытия ссылки", 2); }
    }

    @Override public void openBatterySettings() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (Build.VERSION.SDK_INT >= 23 && pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
            } else {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        } catch (Throwable t) {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Throwable ignored) { }
        }
    }

    // ---- intents (share target, assistant button) ------------------------------------------------------------------
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent, false); }

    private void handleIntent(Intent in, boolean first) {
        if (in == null || j == null) return;
        String a = in.getAction();
        if (a == null) return;
        if (Intent.ACTION_VOICE_COMMAND.equals(a) || Intent.ACTION_ASSIST.equals(a)) { navigate("home"); j.voice.startListening(); return; }
        final List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(a)) { Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM); if (u != null) uris.add(u); else if (in.getStringExtra(Intent.EXTRA_TEXT) != null) { navigate("agent"); j.submit(in.getStringExtra(Intent.EXTRA_TEXT), null, "text"); return; } }
        else if (Intent.ACTION_SEND_MULTIPLE.equals(a)) { ArrayList<Uri> l = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM); if (l != null) uris.addAll(l); }
        else if (Intent.ACTION_VIEW.equals(a) && in.getData() != null) uris.add(in.getData());
        if (uris.isEmpty()) return;
        setIntent(new Intent());
        shell.overlays().toast("Принимаю файлов: " + uris.size(), 0);
        new Thread(new Runnable() {
            @Override public void run() {
                final List<ChatItem.Attachment> got = new ArrayList<>();
                for (Uri u : uris) {
                    try { got.add(j.attach(u, null)); } catch (final Exception e) { j.journal.e(Journal.Cat.FILES, "Не удалось принять файл: " + e.getMessage(), e); j.toast("Не удалось принять файл: " + e.getMessage(), 2); }
                }
                if (got.isEmpty()) return;
                StringBuilder sb = new StringBuilder("Получено файлов: " + got.size() + " (" + got.get(0).path + (got.size() > 1 ? ", …" : "") + ")");
                j.journal.log(Journal.Cat.FILES, Journal.Level.INFO, sb.toString(), null, null, -1);
                j.toast(sb + ". Скажите «проиграй присланный файл» или откройте раздел «Файлы».", 1);
                j.lastAction("Получен файл " + got.get(0).name, true);
                j.navigate("files");
            }
        }, "jv-share").start();
    }

    private void maybeShowCrash() {
        final File f = new File(j.home, "crash/last.txt");
        if (!f.isFile()) return;
        final StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f))) { String l; int n = 0; while ((l = r.readLine()) != null && n++ < 40) sb.append(l).append('\n'); } catch (Throwable ignored) { }
        f.renameTo(new File(f.getParentFile(), "previous.txt"));
        LinearLayout box = Ui.column(this);
        box.setPadding(Ui.dp(18), Ui.dp(16), Ui.dp(18), Ui.dp(14));
        box.addView(Ui.text(this, "Прошлый запуск завершился с ошибкой", Ui.TITLE));
        box.addView(Ui.text(this, "Отчёт сохранён в журнале. Его можно скопировать и показать разработчику.", Ui.BODY, Ui.P.text2), Ui.lpm(-1, -2, 0, 6, 0, 8));
        TextView tv = Ui.text(this, sb.toString(), Ui.MONO, Ui.P.text2);
        tv.setMaxLines(9);
        box.addView(tv);
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.END);
        final OverlayHost.Layer[] layer = new OverlayHost.Layer[1];
        row.addView(Ui.button(this, "Копировать", app.jarvis.R.drawable.ic_copy, Ui.BTN_GLASS, new Runnable() { @Override public void run() { copyToClipboard("crash", sb.toString()); } }));
        row.addView(Ui.space(this, 8, 1));
        row.addView(Ui.button(this, "Закрыть", 0, Ui.BTN_PRIMARY, new Runnable() { @Override public void run() { shell.overlays().dismiss(layer[0]); } }));
        box.addView(row, Ui.lpm(-1, -2, 0, 12, 0, 0));
        layer[0] = shell.overlays().showDialog(box, true, null);
    }

    @Override protected void onResume() {
        super.onResume();
        if (j == null) return;
        // After a reboot/update/process death the microphone service can only be re-armed while the app is visible.
        if (j.settings.getBool("wake.enabled", false) && !j.voice.isWakeEnabled()) j.voice.setWakeEnabled(true);
        JarvisService.sync(this);
        app.jarvis.mcp.LiveUi.resumed(this, true);
        j.mcp.attachHost(this);
    }

    @Override protected void onPause() {
        app.jarvis.mcp.LiveUi.resumed(this, false);
        if (j != null) j.mcp.attachHost(null);
        super.onPause();
    }

    @Override protected void onDestroy() {
        app.jarvis.mcp.LiveUi.gone(this);
        if (shell != null) shell.destroy();
        super.onDestroy();
    }
}
