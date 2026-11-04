package app.jarvis.ui.settings;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.audio.CueImport;
import app.jarvis.audio.Cues;
import app.jarvis.core.audio.CueManifest;
import app.jarvis.core.audio.CuePackCatalog;
import app.jarvis.core.audio.CuePolicy;
import app.jarvis.core.audio.CueSettings;
import app.jarvis.core.audio.CueSummary;
import app.jarvis.core.net.Downloader;
import app.jarvis.core.util.CancelToken;
import app.jarvis.rt.Jarvis;
import app.jarvis.rt.VoiceControl;
import app.jarvis.ui.HelpPopup;
import app.jarvis.ui.Host;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Sounds and microphone: the start and end cues (each with its own set and a play button), volume and vibration, the
 * user's own files, the sound pack from the internet, and the state of the microphone yielding to other apps.
 * The download and the file import outlive the page, so their state is static and the visible page is updated on finish.
 */
public class SoundsPage extends SettingsPage {
    private static final int REQ_START_FILE = 61, REQ_STOP_FILE = 62;
    private static final long TICK_MS = 2000;

    private static SoundsPage visible;
    private static boolean downloading;
    private static int packPercent = -1;
    private static String packNote = "";

    private final Cues cues;
    private final boolean embedded;
    private LinearLayout column;
    private TextView micSub, packSub;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refreshMic();
            j.main.postDelayed(this, TICK_MS);
        }
    };

    public SoundsPage(Host host) { this(host, false); }

    public SoundsPage(Host host, boolean embedded) {
        super(host);
        this.embedded = embedded;
        cues = new Cues(ctx, j.settings, j.journal);
    }

    /** One-line state for the settings list, e.g. "Хрусталь · вкл". */
    public static String statusLine(Jarvis j) {
        Cues c = new Cues(j.ctx, j.settings, j.journal);
        CueSettings s = c.current();
        List<CueManifest.Set> sets = c.sets();
        return CueSummary.sounds(s.enabled, kindLabel(s, CuePolicy.Kind.START, sets), kindLabel(s, CuePolicy.Kind.STOP, sets));
    }

    private static String kindLabel(CueSettings s, CuePolicy.Kind kind, List<CueManifest.Set> sets) {
        String custom = kind == CuePolicy.Kind.START ? s.customStart : s.customStop;
        if (!custom.isEmpty()) return "свой файл";
        return setTitle(sets, kind == CuePolicy.Kind.START ? s.startSet : s.stopSet);
    }

    private static String setTitle(List<CueManifest.Set> sets, String id) {
        CueManifest.Set s = CueManifest.find(sets, id);
        return s == null ? "" : s.title;
    }

    @Override public String title() { return "Звук и микрофон"; }

    @Override protected View build() {
        ScrollView sv = Ui.scroll(ctx);
        sv.setFillViewport(true);
        column = Ui.column(ctx);
        int h = embedded ? 0 : Ui.dp(16);
        column.setPadding(h, Ui.dp(8), h, Ui.dp(24));
        sv.addView(column);
        fill();
        return sv;
    }

    private void rebuild() {
        column.removeAllViews();
        fill();
    }

    private void fill() {
        CueSettings s = cues.current();
        List<CueManifest.Set> sets = cues.sets();
        addSignals(s);
        addPicker(CuePolicy.Kind.START, s, sets);
        addPicker(CuePolicy.Kind.STOP, s, sets);
        addVolume(s);
        addPack();
        addMic();
    }

    private void addSignals(CueSettings s) {
        column.addView(header("Сигналы",
            "Короткий звук в момент, когда Джарвис начал слушать, закончил запись или не распознал команду.",
            "Главный выключатель гасит все звуки. Вибрация настраивается отдельно."));
        LinearLayout card = Widgets.card(ctx);
        card.addView(toggleRow(R.drawable.ic_volume, "Звуковые сигналы", "Главный выключатель", CueSettings.ENABLED, s.enabled));
        Widgets.divide(card);
        card.addView(toggleRow(R.drawable.ic_play, "Сигнал начала", "Звучит, когда Джарвис начал слушать", CueSettings.START_ON, s.startOn));
        Widgets.divide(card);
        card.addView(toggleRow(R.drawable.ic_stop, "Сигнал конца", "Звучит, когда запись закончилась или её остановили", CueSettings.STOP_ON, s.stopOn));
        Widgets.divide(card);
        card.addView(toggleRow(R.drawable.ic_warning, "Сигнал «не услышал»", "Команда не распознана или речи не было 7 секунд", CueSettings.ERROR_ON, s.errorOn));
        column.addView(card);
    }

    private void addPicker(final CuePolicy.Kind kind, CueSettings s, List<CueManifest.Set> sets) {
        final boolean start = kind == CuePolicy.Kind.START;
        final String setKey = start ? CueSettings.START_SET : CueSettings.STOP_SET;
        final String customKey = start ? CueSettings.CUSTOM_START : CueSettings.CUSTOM_STOP;
        String custom = start ? s.customStart : s.customStop;
        String chosen = effectiveId(sets, start ? s.startSet : s.stopSet);
        column.addView(header(start ? "Звук начала" : "Звук окончания",
            start ? "Звучит, когда Джарвис начал слушать: по слову «Джарвис» или по кнопке микрофона."
                  : "Звучит, когда запись закончилась сама (пауза в речи) или её остановили вручную.",
            "Выберите звук, а ▶ проигрывает его один раз. Сам по себе звук не звучит.",
            "Начало и конец можно выбрать разные."));
        LinearLayout card = Widgets.card(ctx);
        boolean first = true;
        for (CueManifest.Set set : sets) {
            if (!first) Widgets.divide(card);
            first = false;
            card.addView(setRow(kind, setKey, customKey, set.id, set.title, custom.isEmpty() && set.id.equals(chosen)));
        }
        Widgets.divide(card);
        card.addView(fileRow(kind, customKey, custom));
        column.addView(card);
    }

    /** The set that actually plays for this id: an unknown or missing one falls back to the default set. */
    private static String effectiveId(List<CueManifest.Set> sets, String id) {
        CueManifest.Set s = CueManifest.find(sets, id);
        return s == null ? "" : s.id;
    }

    private View setRow(final CuePolicy.Kind kind, final String setKey, final String customKey, final String setId, String title, boolean selected) {
        LinearLayout trailing = Ui.row(ctx);
        trailing.addView(check(selected));
        trailing.addView(playButton(new Runnable() { @Override public void run() { cues.previewSet(setId, kind); } }));
        return Widgets.row(ctx, 0, title, null, trailing, new Runnable() { @Override public void run() {
            j.settings.set(setKey, setId);
            j.settings.set(customKey, "");
            rebuild();
        } });
    }

    private View fileRow(final CuePolicy.Kind kind, final String customKey, final String fileName) {
        boolean has = !fileName.isEmpty();
        LinearLayout trailing = Ui.row(ctx);
        trailing.addView(check(has));
        if (has) {
            trailing.addView(playButton(new Runnable() { @Override public void run() { cues.previewFile(fileName); } }));
            trailing.addView(Ui.iconButton(ctx, R.drawable.ic_close, 36, Ui.P.text3, new Runnable() { @Override public void run() {
                j.settings.set(customKey, "");
                rebuild();
            } }), Ui.lpm(Ui.dp(36), Ui.dp(36), Ui.dp(4), 0, 0, 0));
        }
        String sub = has ? "Файл выбран. Нажмите, чтобы заменить" : "OGG, WAV или MP3, до 3 секунд и 300 КБ";
        return Widgets.row(ctx, R.drawable.ic_file_audio, "Свой файл…", sub, trailing, new Runnable() { @Override public void run() {
            host.pickDocument(screen(), kind == CuePolicy.Kind.START ? REQ_START_FILE : REQ_STOP_FILE, new String[]{"audio/*"}, false);
        } });
    }

    private ImageView check(boolean on) {
        ImageView v = Ui.icon(ctx, R.drawable.ic_check, 18, Ui.P.accent);
        v.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
        v.setLayoutParams(Ui.lpm(Ui.dp(18), Ui.dp(18), 0, 0, Ui.dp(8), 0));
        return v;
    }

    private View playButton(Runnable onPlay) {
        return Ui.iconButton(ctx, R.drawable.ic_play, 36, Ui.P.accent, onPlay);
    }

    private void addVolume(CueSettings s) {
        column.addView(header("Громкость и вибрация",
            "Громкость — множитель к громкости мультимедиа телефона. Вибрация: короткий тик при начале и двойной при конце.",
            "Вибрация не работает во время звонка."));
        LinearLayout card = Widgets.card(ctx);
        LinearLayout box = Ui.column(ctx);
        box.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(8));
        final TextView value = Ui.text(ctx, volumeLabel(s.volume), Ui.BODY, Ui.P.text);
        box.addView(value);
        box.addView(Widgets.slider(ctx, CueSettings.MIN_VOLUME, CueSettings.MAX_VOLUME, 0.05f, s.volume, new Widgets.SliderListener() {
            @Override public void onValue(float v, boolean fromUser) {
                j.settings.set(CueSettings.VOLUME, String.format(Locale.US, "%.2f", v));
                value.setText(volumeLabel(v));
            }
        }), Ui.lpm(-1, -2, 0, Ui.dp(4), 0, 0));
        card.addView(box);
        Widgets.divide(card);
        card.addView(toggleRow(R.drawable.ic_bolt, "Вибрация", "Тик при начале и двойной при конце", CueSettings.HAPTIC, s.haptic));
        column.addView(card);
        column.addView(PageUi.note(ctx, "Сигналы в работе учитывают тихий режим и «Не беспокоить». Предпрослушивание зависит от громкости мультимедиа. Во время звонка сигналы молчат."),
            Ui.lpm(-1, -2, Ui.dp(4), Ui.dp(8), Ui.dp(4), 0));
    }

    private static String volumeLabel(float v) {
        return "Громкость сигналов: " + CueSummary.percent(v);
    }

    private void addPack() {
        column.addView(header("Наборы из интернета",
            "Один набор звуков CC0 (около 230 КБ) с сайта OpenGameArt. Файл проверяется по контрольной сумме SHA-256; неверный файл не устанавливается.",
            "Лицензия CC0 разрешает использовать звуки без указания автора."));
        boolean installed = CuePackCatalog.installed(CueImport.packDir(ctx));
        LinearLayout trailing = Ui.row(ctx);
        if (installed) {
            trailing.addView(Widgets.pill(ctx, "установлен", Widgets.OK));
        } else if (downloading) {
            trailing.addView(Widgets.pill(ctx, "загрузка", Widgets.INFO));
        } else {
            trailing.addView(Ui.button(ctx, "Скачать", R.drawable.ic_download, Ui.BTN_GLASS, new Runnable() { @Override public void run() { downloadPack(); } }));
        }
        LinearLayout card = Widgets.card(ctx);
        LinearLayout pack = Widgets.row(ctx, R.drawable.ic_package, CuePackCatalog.sets().get(0).title, packStatusText(installed), trailing, null);
        packSub = (TextView) pack.findViewWithTag("subtitle");
        card.addView(pack);
        Widgets.divide(card);
        card.addView(Widgets.row(ctx, R.drawable.ic_link, "Страница набора и лицензия", "opengameart.org", null, new Runnable() { @Override public void run() {
            host.openUrl(CuePackCatalog.PAGE_URL);
        } }));
        column.addView(card);
    }

    private String packStatusText(boolean installed) {
        if (downloading) return packPercent < 0 ? "загрузка…" : "загрузка " + packPercent + " %";
        if (installed) return "установлен, контрольная сумма SHA-256 проверена при загрузке";
        if (!packNote.isEmpty()) return packNote;
        return "не установлен · " + PageUi.humanSize(CuePackCatalog.SIZE) + " · лицензия CC0";
    }

    private void downloadPack() {
        if (downloading) return;
        downloading = true;
        packNote = "";
        packPercent = -1;
        rebuild();
        new Thread(new Runnable() { @Override public void run() {
            String note = "";
            try {
                CueImport.installPack(ctx, j.journal, new Downloader.Progress() {
                    @Override public void onProgress(long done, long total) {
                        final int pct = total > 0 ? (int) (done * 100 / total) : -1;
                        j.main.post(new Runnable() { @Override public void run() {
                            packPercent = pct;
                            if (visible != null) visible.refreshPack();
                        } });
                    }
                }, new CancelToken());
            } catch (IOException e) {
                note = "Не удалось установить набор: " + e.getMessage();
            } catch (RuntimeException e) {
                note = "Не удалось установить набор: " + e.getClass().getSimpleName();
            }
            final String result = note;
            j.main.post(new Runnable() { @Override public void run() {
                downloading = false;
                packNote = result;
                if (visible != null) visible.rebuild();
                if (result.isEmpty()) j.toast("Набор установлен: контрольная сумма SHA-256 совпала", 1);
                else j.toast(result, 2);
            } });
        } }, "jv-cue-pack").start();
    }

    private void refreshPack() {
        if (packSub != null) packSub.setText(packStatusText(CuePackCatalog.installed(CueImport.packDir(ctx))));
    }

    private void addMic() {
        column.addView(header("Микрофон",
            "Если другое приложение начинает запись (например, голосовое сообщение в Telegram) или идёт звонок, Джарвис отпускает микрофон. Когда микрофон освободится, он снова слушает.",
            "Состояние видно здесь, в строке голоса и в журнале (категория «Обращение»)."));
        LinearLayout card = Widgets.card(ctx);
        card.addView(Widgets.row(ctx, R.drawable.ic_mic, "Уступать микрофон другим приложениям", "Telegram, звонки и другие записи",
            Widgets.pill(ctx, "всегда", Widgets.OK), null));
        Widgets.divide(card);
        LinearLayout state = Widgets.row(ctx, R.drawable.ic_activity, "Сейчас", micText(), null, null);
        micSub = (TextView) state.findViewWithTag("subtitle");
        card.addView(state);
        column.addView(card);
    }

    private String micText() {
        VoiceControl v = j.voice;
        return CueSummary.mic(v.isWakeEnabled() || v.isMicActive(), v.micProblem());
    }

    private void refreshMic() {
        if (micSub != null) micSub.setText(micText());
    }

    private View header(String title, String... help) {
        LinearLayout h = Ui.row(ctx);
        h.addView(Ui.section(ctx, title), Ui.lpw(1f));
        h.addView(HelpPopup.button(ctx, host, title, help));
        return h;
    }

    private View toggleRow(int icon, String title, String sub, final String key, boolean on) {
        LinearLayout r = Widgets.row(ctx, icon, title, sub, Widgets.toggle(ctx, on, new Widgets.Toggle.Listener() {
            @Override public void onChanged(boolean v) { j.settings.setBool(key, v); }
        }), null);
        r.setTag(key);
        return r;
    }

    @Override public void onShow() {
        visible = this;
        j.main.removeCallbacks(tick);
        j.main.post(tick);
    }

    @Override public void onHide() {
        if (visible == this) visible = null;
        j.main.removeCallbacks(tick);
        cues.release();
    }

    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode != REQ_START_FILE && requestCode != REQ_STOP_FILE) return;
        final boolean start = requestCode == REQ_START_FILE;
        final Uri uri = data.getData();
        new Thread(new Runnable() { @Override public void run() {
            String text;
            int kind = 1;
            try {
                String name = CueImport.importFile(ctx, uri, start ? "start" : "stop");
                j.settings.set(start ? CueSettings.CUSTOM_START : CueSettings.CUSTOM_STOP, name);
                text = "Свой сигнал " + (start ? "начала" : "конца") + " сохранён";
            } catch (IOException e) {
                text = e.getMessage();
                kind = 2;
            } catch (RuntimeException e) {
                text = "Не удалось прочитать файл";
                kind = 2;
            }
            final String msg = text;
            final int k = kind;
            j.main.post(new Runnable() { @Override public void run() {
                rebuild();
                j.toast(msg, k);
            } });
        } }, "jv-cue-import").start();
    }
}
