package app.jarvis.ui.settings;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.PowerManager;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.core.llm.LlmException;
import app.jarvis.core.net.Http;
import app.jarvis.rt.Jarvis;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Host;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Diagnostics: nine real self-checks run on background threads with honest per-row results (spinner → OK/WARN/FAIL
 * plus advice). Internet, selected LLM endpoint, microphone, notifications, battery, storage, Keystore, audio output
 * and API-level compatibility. The report can be copied or written to the journal.
 */
public class DiagnosticsPage extends SettingsPage {
    private static final int IDLE = 0, RUNNING = 1, OK = 2, WARN = 3, FAIL = 4;

    private static final class Check {
        final String id, name;
        int state = IDLE;
        String status = "", advice = "", tech = "";
        Check(String id, String name) { this.id = id; this.name = name; }
    }

    private final List<Check> checks = new ArrayList<>();
    private LinearLayout rowsHost;
    private volatile boolean running;
    private final app.jarvis.core.util.CancelToken cancel = new app.jarvis.core.util.CancelToken();

    public DiagnosticsPage(Host host) { super(host); }

    @Override public String title() { return "Диагностика"; }

    @Override protected View build() {
        checks.clear();
        checks.add(new Check("net", "Интернет"));
        checks.add(new Check("llm", "Сервер языковой модели"));
        checks.add(new Check("mic", "Микрофон"));
        checks.add(new Check("notif", "Уведомления"));
        checks.add(new Check("battery", "Оптимизация батареи"));
        checks.add(new Check("storage", "Свободное место"));
        checks.add(new Check("keystore", "Шифрование ключей"));
        checks.add(new Check("audio", "Вывод звука"));
        checks.add(new Check("api", "Совместимость Android"));

        ScrollView sv = Ui.scroll(ctx);
        sv.setFillViewport(true);
        LinearLayout content = Ui.column(ctx);
        content.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(24));
        content.addView(Widgets.sectionTitle(ctx, "Проверки"));
        LinearLayout card = Widgets.card(ctx);
        LinearLayout runPad = Ui.column(ctx);
        runPad.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12));
        runPad.addView(Ui.button(ctx, "Проверить всё", R.drawable.ic_activity, Ui.BTN_PRIMARY, new Runnable() { @Override public void run() { runAll(); } }));
        card.addView(runPad);
        Widgets.divide(card);
        rowsHost = Ui.column(ctx);
        card.addView(rowsHost);
        content.addView(card);
        content.addView(Widgets.sectionTitle(ctx, "Отчёт"));
        content.addView(reportCard());
        sv.addView(content);
        buildRows();
        return sv;
    }

    private View reportCard() {
        LinearLayout card = Widgets.card(ctx);
        LinearLayout pad = Ui.column(ctx);
        pad.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12));
        pad.addView(Ui.button(ctx, "Скопировать отчёт", R.drawable.ic_copy, Ui.BTN_GLASS, new Runnable() { @Override public void run() {
            String r = buildReport();
            if (r == null) { host.overlays().toast("Сначала запустите проверки", 2); return; }
            host.copyToClipboard("Jarvis diagnostics", r);
            host.overlays().toast("Отчёт в буфере обмена", 1);
        } }));
        pad.addView(Ui.space(ctx, 1, Ui.dp(8)));
        pad.addView(Ui.button(ctx, "Записать в журнал", R.drawable.ic_log, Ui.BTN_GLASS, new Runnable() { @Override public void run() {
            String r = buildReport();
            if (r == null) { host.overlays().toast("Сначала запустите проверки", 2); return; }
            JSONObject details = new JSONObject();
            app.jarvis.core.util.J.put(details, "report", r);
            j.journal.log(app.jarvis.core.log.Journal.Cat.SYSTEM, app.jarvis.core.log.Journal.Level.INFO, "Самодиагностика выполнена", null, details, -1);
            host.overlays().toast("Записано в журнал", 1);
        } }));
        card.addView(pad);
        return card;
    }

    private void buildRows() {
        rowsHost.removeAllViews();
        int i = 0;
        for (final Check c : checks) {
            if (i > 0) Widgets.divide(rowsHost);
            final LinearLayout row = Widgets.row(ctx, icon(c.id), c.name, "", null, null);
            row.setTag("chk." + c.id);
            // child 0 is the icon badge (a FrameLayout) when the row has an icon: the text column is the first LinearLayout child
            LinearLayout tx = null;
            for (int k = 0; k < row.getChildCount() && tx == null; k++) if (row.getChildAt(k) instanceof LinearLayout) tx = (LinearLayout) row.getChildAt(k);
            if (tx == null) continue;
            TextView advice = PageUi.note(ctx, "");
            advice.setTag("advice");
            tx.addView(advice, Ui.lpm(-2, -2, 0, Ui.dp(3), 0, 0));
            LinearLayout trail = Ui.row(ctx);
            trail.setTag("trail");
            row.addView(trail, Ui.lpm(-2, -2, Ui.dp(8), 0, 0, 0));
            row.setBackground(Ui.rippleOn(new android.graphics.drawable.ColorDrawable(0), 14));
            row.setClickable(true);
            row.setFocusable(true);
            row.setContentDescription("Проверить: " + c.name);
            row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { runOne(c); } });
            rowsHost.addView(row);
            paint(c);
            i++;
        }
    }

    private static int icon(String id) {
        switch (id) {
            case "net": return R.drawable.ic_globe;
            case "llm": return R.drawable.ic_server;
            case "mic": return R.drawable.ic_mic;
            case "notif": return R.drawable.ic_bell;
            case "battery": return R.drawable.ic_battery;
            case "storage": return R.drawable.ic_drive;
            case "keystore": return R.drawable.ic_lock;
            case "audio": return R.drawable.ic_volume;
            default: return R.drawable.ic_info;
        }
    }

    private void paint(Check c) {
        View row = rowsHost == null ? null : rowsHost.findViewWithTag("chk." + c.id);
        if (!(row instanceof LinearLayout)) return;
        LinearLayout rl = (LinearLayout) row;
        TextView sub = rl.findViewWithTag("advice") instanceof TextView ? (TextView) rl.findViewWithTag("advice") : null;
        if (sub != null) {
            sub.setText(c.advice == null || c.advice.isEmpty() ? defaultAdvice(c) : c.advice);
            sub.setVisibility(View.VISIBLE);
        }
        paintTrail(rl, c);
    }

    private void paintTrail(LinearLayout rl, Check c) {
        LinearLayout trail = rl.findViewWithTag("trail") instanceof LinearLayout ? (LinearLayout) rl.findViewWithTag("trail") : null;
        if (trail == null) return;
        trail.removeAllViews();
        if (c.state == RUNNING) {
            trail.addView(Widgets.spinner(ctx, 20));
        } else if (c.state == OK) {
            trail.addView(Widgets.pill(ctx, c.status.isEmpty() ? "ок" : c.status, Widgets.OK));
        } else if (c.state == WARN) {
            trail.addView(Widgets.pill(ctx, c.status.isEmpty() ? "внимание" : c.status, Widgets.WARN));
        } else if (c.state == FAIL) {
            trail.addView(Widgets.pill(ctx, c.status.isEmpty() ? "ошибка" : c.status, Widgets.ERR));
        } else {
            trail.addView(Ui.icon(ctx, R.drawable.ic_chevron_right, 16, Ui.P.text3), Ui.lpm(-2, -2, 0, 0, 0, 0));
        }
    }

    private static String defaultAdvice(Check c) {
        return "нажмите, чтобы проверить";
    }

    @Override public void onShow() { }

    @Override public void onHide() { cancel.cancel(); }

    private void runOne(final Check c) {
        if (running) { host.overlays().toast("Дождитесь окончания проверок", 2); return; }
        Thread th = new Thread(new Runnable() { @Override public void run() { perform(c); } }, "jv-diag-" + c.id);
        th.setDaemon(true);
        th.start();
    }

    /** Runs every check sequentially on a background thread; safe to call from previews. */
    public void runAll() {
        if (running) { host.overlays().toast("Проверки уже идут", 2); return; }
        Thread th = new Thread(new Runnable() { @Override public void run() {
            running = true;
            try {
                for (Check c : checks) perform(c);
            } finally {
                running = false;
            }
        } }, "jv-diag-all");
        th.setDaemon(true);
        th.start();
    }

    /** Runs a single check to completion; called on background threads only. */
    void perform(final Check c) {
        if (c.state == RUNNING) return;
        c.state = RUNNING;
        c.advice = "проверяю…";
        j.main.post(new Runnable() { @Override public void run() { paint(c); } });
        try {
            switch (c.id) {
                case "net": checkNet(c); break;
                case "llm": checkLlm(c); break;
                case "mic": checkMic(c); break;
                case "notif": checkNotif(c); break;
                case "battery": checkBattery(c); break;
                case "storage": checkStorage(c); break;
                case "keystore": checkKeystore(c); break;
                case "audio": checkAudio(c); break;
                case "api": checkApi(c); break;
                default: c.state = FAIL; c.status = "?"; break;
            }
        } catch (Throwable t) {
            c.state = FAIL;
            c.status = "сбой";
            c.advice = "Неожиданная ошибка проверки";
            c.tech = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        j.main.post(new Runnable() { @Override public void run() { paint(c); } });
    }

    // --------------------------------------------------------------------------- individual checks
    private void checkNet(Check c) throws Exception {
        long t0 = System.nanoTime();
        HttpURLConnection conn = Http.open("https://www.google.com/generate_204", "GET", null, 8000, 8000);
        try {
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in != null) in.read(new byte[512]);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            if (code == 204) {
                c.state = OK;
                c.status = ms + " мс";
                c.advice = "Интернет есть. Это время отклика до Google (стенд может отличаться от вашего оператора).";
            } else {
                c.state = WARN;
                c.status = "HTTP " + code;
                c.advice = "Сеть отвечает нестандартно: возможен портал авторизации или блокировка.";
                c.tech = "generate_204 -> " + code;
            }
        } finally {
            conn.disconnect();
        }
    }

    private void checkLlm(Check c) throws Exception {
        Jarvis.LlmChoice ch = j.choiceFor(j.providerId(), null);
        if (ch == null) { c.state = FAIL; c.status = "нет"; c.advice = "Провайдер не выбран."; return; }
        String modelNote = ch.model.isEmpty() ? " (модель не выбрана)" : " · " + ch.model;
        try {
            int n = j.llm.listModels(ch.config, cancel).size();
            c.state = OK;
            c.status = PageUi.group(n);
            c.advice = ch.preset.name + modelNote + ": каталог моделей получен.";
        } catch (LlmException e) {
            c.tech = "HTTP " + e.status + ": " + e.technical;
            c.status = e.kind == LlmException.Kind.GEO_BLOCK ? "гео-блок" : "HTTP " + (e.status > 0 ? e.status : "—");
            switch (e.kind) {
                case GEO_BLOCK:
                    c.state = FAIL;
                    c.advice = ch.preset.name + " закрыл доступ из вашей страны. Настройте прокси или перейдите на OpenRouter — подробнее на странице LLM.";
                    break;
                case AUTH:
                    c.state = FAIL;
                    c.advice = "Ключ отсутствует или не принят (" + ch.preset.name + "). Задайте ключ на странице LLM.";
                    break;
                case NETWORK: case TIMEOUT: case TLS:
                    c.state = FAIL;
                    c.advice = "Сервер " + ch.preset.name + " не отвечает: " + e.userMessage;
                    break;
                default:
                    c.state = WARN;
                    c.advice = ch.preset.name + ": " + e.userMessage;
                    break;
            }
        }
    }

    private void checkMic(Check c) {
        boolean ok = host.hasPermission(android.Manifest.permission.RECORD_AUDIO);
        c.state = ok ? OK : WARN;
        c.status = ok ? "есть" : "нет";
        c.advice = ok ? "Голосовой ввод возможен." : "Разрешите микрофон: без него не работает ни распознавание, ни обращение «Джарвис».";
    }

    private void checkNotif(Check c) {
        if (Build.VERSION.SDK_INT < 33) {
            c.state = OK;
            c.status = "не нужно";
            c.advice = "Android до 13 не спрашивает разрешение на уведомления.";
            return;
        }
        boolean ok = host.hasPermission("android.permission.POST_NOTIFICATIONS");
        c.state = ok ? OK : WARN;
        c.status = ok ? "есть" : "нет";
        c.advice = ok ? "Напоминания и медиауправление будут видны." : "Без уведомлений Jarvis не напомнит о себе после перезагрузки.";
    }

    private void checkBattery(Check c) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(android.content.Context.POWER_SERVICE);
            boolean ignored = pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
            if (ignored) {
                c.state = OK;
                c.status = "исключён";
                c.advice = "Система не будет усыплять Jarvis в фоне.";
            } else {
                c.state = WARN;
                c.status = "активна";
                c.advice = "Android может останавливать фоновую работу. Исключите Jarvis: страница «Фон и батарея» → Настройки.";
            }
        } catch (Throwable t) {
            c.state = WARN;
            c.status = "?";
            c.advice = "Не удалось узнать статус оптимизации батареи.";
            c.tech = t.getClass().getSimpleName();
        }
    }

    private void checkStorage(Check c) {
        long free;
        try { free = j.sandbox.root.getUsableSpace(); } catch (Throwable t) { free = -1; }
        if (free < 0) { c.state = WARN; c.status = "?"; c.advice = "Не удалось определить свободное место."; return; }
        c.status = PageUi.humanSize(free);
        if (free > 1024L * 1024 * 1024) {
            c.state = OK;
            c.advice = "Место есть: " + PageUi.humanSize(free) + " свободно.";
        } else if (free > 200L * 1024 * 1024) {
            c.state = WARN;
            c.advice = "Осталось " + PageUi.humanSize(free) + ": пора освободить место, чат и журнал растут.";
        } else {
            c.state = FAIL;
            c.advice = "Критически мало места (" + PageUi.humanSize(free) + "): приложение может перестать сохранять данные.";
        }
    }

    private void checkKeystore(Check c) {
        if (j.secrets.isEncrypted()) {
            c.state = OK;
            c.status = "шифрует";
            c.advice = "Ключи шифруются AES-256-GCM ключом из Android Keystore.";
        } else {
            c.state = WARN;
            c.status = "в открытом виде";
            c.advice = "Keystore недоступен: ключи хранятся в файле без шифрования. " + j.secrets.keystoreProblem();
        }
    }

    private void checkAudio(Check c) throws Exception {
        int sampleRate = 48000;
        int frames = sampleRate * 300 / 1000;     // 300 ms
        short[] samples = new short[frames];
        for (int i = 0; i < frames; i++) samples[i] = (short) (Math.sin(2 * Math.PI * 440 * i / sampleRate) * 12000);
        AudioTrack track = null;
        try {
            track = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, frames * 2, AudioTrack.MODE_STATIC);
            int written = track.write(samples, 0, frames);
            if (written <= 0) {
                c.state = FAIL;
                c.status = "не пишется";
                c.advice = "AudioTrack не принял данные (write=" + written + "). Звук на устройстве может не работать.";
                return;
            }
            track.play();
            Thread.sleep(360);
            c.state = OK;
            c.status = "440 Гц";
            c.advice = "Короткий тон проигран через AudioTrack без ошибок.";
        } catch (Throwable t) {
            c.state = FAIL;
            c.status = "недоступен";
            c.advice = "Не удалось проиграть тон: звуковой выход сообщил об ошибке.";
            c.tech = t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (track != null) {
                try { track.stop(); } catch (Throwable ignored) { }
                try { track.release(); } catch (Throwable ignored) { }
            }
        }
    }

    private void checkApi(Check c) {
        c.state = OK;
        c.status = "SDK " + Build.VERSION.SDK_INT;
        c.advice = "Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + "), " + Build.MANUFACTURER + " " + Build.MODEL
            + ". Приложение рассчитано на Android 8.0 (SDK 26) и новее — совместимо.";
    }

    // --------------------------------------------------------------------------- report
    private String buildReport() {
        boolean any = false;
        StringBuilder sb = new StringBuilder();
        sb.append("Jarvis ").append(app.jarvis.BuildConfig.VERSION_NAME).append(" (").append(app.jarvis.BuildConfig.GIT_REV).append("), Android ")
            .append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append("), ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        JSONArray arr = new JSONArray();
        for (Check c : checks) {
            if (c.state == IDLE || c.state == RUNNING) continue;
            any = true;
            sb.append(c.name).append(": ").append(stateWord(c.state));
            if (!c.status.isEmpty()) sb.append(" (").append(c.status).append(')');
            if (!c.advice.isEmpty()) sb.append(" — ").append(c.advice);
            if (!c.tech.isEmpty()) sb.append(" [").append(c.tech).append(']');
            sb.append('\n');
            JSONObject o = new JSONObject();
            app.jarvis.core.util.J.put(o, "id", c.id);
            app.jarvis.core.util.J.put(o, "state", stateWord(c.state));
            app.jarvis.core.util.J.put(o, "status", c.status);
            app.jarvis.core.util.J.put(o, "advice", c.advice);
            app.jarvis.core.util.J.put(o, "tech", c.tech);
            arr.put(o);
        }
        return any ? sb.toString() : null;
    }

    private static String stateWord(int state) {
        switch (state) {
            case OK: return "OK";
            case WARN: return "ВНИМАНИЕ";
            case FAIL: return "ОШИБКА";
            default: return "?";
        }
    }
}
