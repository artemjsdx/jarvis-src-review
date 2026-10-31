package app.jarvis.dev.scenes;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.core.log.Journal;
import app.jarvis.core.log.JournalAction;
import app.jarvis.core.util.CancelToken;
import app.jarvis.data.SqlJournal;
import app.jarvis.dev.PreviewHost;
import app.jarvis.dev.PreviewKit;
import app.jarvis.rt.Jarvis;
import app.jarvis.ui.Shell;
import app.jarvis.ui.Widgets;
import app.jarvis.ui.screens.LogScreen;
import org.json.JSONObject;

/** Journal rows with a real thrown error (file:line), the wake summary row, quiet debug rows and an action button. */
public final class LogErrorScenes {
    private LogErrorScenes() { }

    public static void render() throws Exception {
        Jarvis j = PreviewKit.jarvis();
        try {
            CancelToken token = new CancelToken();
            token.cancel("проверка журнала");
            token.throwIfCancelled();
        } catch (Throwable e) {
            j.journal.e(Journal.Cat.AGENT, "Сбой запуска агента", e);
        }
        JSONObject mic = new JSONObject();
        new JournalAction(JournalAction.BACKGROUND_SETTINGS, "Открыть настройки фона").putInto(mic);
        j.journal.log(Journal.Cat.WAKE, Journal.Level.ERROR,
            "Ошибка микрофона: Android не отдаёт звук микрофона: Jarvis в фоне без разрешённой службы или микрофон выключен в системе",
            null, mic, -1);
        for (int i = 0; i < 9; i++) j.journal.log(Journal.Cat.STT, Journal.Level.DEBUG, "Распознано (wake, Google Speech): 12 симв., 2300 мс звука", null, null, 480);
        j.journal.summary(Journal.Cat.WAKE, "wake-summary", "за 10 мин: 14 фрагментов, 1 обращение");
        j.journal.i(Journal.Cat.SYSTEM, "Журнал открыт в превью");
        PreviewHost.pump(900);
        j.journal.summary(Journal.Cat.WAKE, "wake-summary", "за 10 мин: 15 фрагментов, 1 обращение");
        PreviewHost.pump(900);
        int summaryRows = 0;
        String summaryText = "";
        for (SqlJournal.Row r : j.journal.query(Journal.Cat.WAKE, null, "", 0, 500)) {
            if ("wake-summary".equals(r.runId)) { summaryRows++; summaryText = r.message; }
        }
        System.out.println("summary rows=" + summaryRows + " text=" + summaryText);

        Shell s = PreviewKit.shell("log");
        PreviewHost.pump(900);
        PreviewHost.render(s, "20_log_errors_default");

        LogScreen log = (LogScreen) s.current();
        SqlJournal.Row err = null, micRow = null;
        for (SqlJournal.Row r : j.journal.query(null, Journal.Level.ERROR, "", 0, 20)) {
            if (err == null && r.site() != null) err = r;
            if (micRow == null && r.message.startsWith("Ошибка микрофона")) micRow = r;
        }
        if (err != null) {
            log.openDetail(err);
            PreviewHost.pump(500);
            settle(s.overlays());
            PreviewHost.render(s, "21_log_error_detail");
            s.overlays().dismissAll();
            PreviewHost.pump(200);
        }
        if (micRow != null) {
            log.openDetail(micRow);
            PreviewHost.pump(500);
            settle(s.overlays());
            PreviewHost.render(s, "22_log_action_detail");
            s.overlays().dismissAll();
            PreviewHost.pump(200);
        }
        LinearLayout seg = Widgets.segmented(PreviewKit.host().context(), new String[]{"Все", "Предупр.", "Ошибки", "Отладка"}, 0, null);
        PreviewHost.render(seg, "23_segmented_probe", 360, 56);
    }

    /**
     * Preview only: the headless render never attaches views to a window, so the sheet's post() that makes it visible and
     * the slide-in animation never run; put the sheet in its final state and open the technical block.
     */
    private static void settle(View v) {
        v.animate().cancel();
        v.setVisibility(View.VISIBLE);
        v.setTranslationY(0f);
        v.setAlpha(1f);
        v.setScaleX(1f);
        v.setScaleY(1f);
        if (v instanceof TextView && ((TextView) v).getText().toString().startsWith("Технические детали")) v.performClick();
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) settle(g.getChildAt(i));
        }
    }
}
