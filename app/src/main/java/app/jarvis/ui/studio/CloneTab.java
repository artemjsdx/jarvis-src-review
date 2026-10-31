package app.jarvis.ui.studio;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.voice.ProviderInfo;
import app.jarvis.core.voice.TtsProvider;
import app.jarvis.core.voice.TtsRequest;
import app.jarvis.data.Settings;
import app.jarvis.rt.ListenApi;
import app.jarvis.rt.SpeechApi;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Host;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Вкладка «Клонирование»: образец (запись или файл) → точный текст образца → провайдер с клонированием
 * → честное уведомление, куда уйдёт образец, согласие → makeClone с прогрессом и отменой.
 * Ниже — профили голосов: сделать активным, прослушать, переименовать, удалить.
 */
public class CloneTab {
    public static final int REQ_IMPORT_SAMPLE = 6201;

    private final Host host;
    private final app.jarvis.ui.Screen screen;
    private final Context ctx;
    private final app.jarvis.rt.Jarvis j;

    private LinearLayout col;
    private LinearLayout sampleHost, recordPanel, cloneProgressHost, profilesHost, providerHost;
    private TextView sampleCaption, providerValue;
    private EditText transcript, nameField;
    private Widgets.Toggle consentToggle;
    private View cloneBtn;

    private String samplePath = "", sampleDesc = "";
    private String providerId = "";
    private boolean recording;
    private LevelBar levelBar;
    private TextView recordTime;
    private CancelToken cloneToken;
    private boolean cloning;

    public CloneTab(Host host, app.jarvis.ui.Screen screen) {
        this.host = host;
        this.screen = screen;
        this.ctx = host.context();
        this.j = host.jarvis();
    }

    public View view() {
        ScrollView sv = Ui.scroll(ctx);
        sv.setFillViewport(true);
        col = Ui.column(ctx);
        col.setPadding(0, 0, 0, Ui.dp(24));
        sv.addView(col);
        rebuild();
        return sv;
    }

    public void onHide() {
        if (recording) stopRecording();
    }

    /** Rebuilds the whole tab; keeps the chosen sample/provider. */
    public void rebuild() {
        col.removeAllViews();
        if (j.speech == null || j.listen == null) {
            col.addView(Widgets.empty(ctx, R.drawable.ic_voice, "Голосовой модуль не подключён",
                "Запись образца и клонирование появятся после подключения речевого движка."));
            return;
        }
        col.addView(Widgets.sectionTitle(ctx, "1. Образец голоса"));
        sampleHost = Ui.column(ctx);
        col.addView(sampleHost);
        renderSampleCard();

        col.addView(Widgets.sectionTitle(ctx, "2. Текст образца"));
        LinearLayout textCard = Widgets.card(ctx);
        textCard.addView(caption(ctx, "Введите дословно то, что произнесено в образце. Клонирование учится на паре «звук ↔ текст»: чем точнее расшифровка, тем похоже получится голос."),
            Ui.lpm(-2, -2, Ui.dp(16), Ui.dp(12), Ui.dp(16), 0));
        transcript = Widgets.field(ctx, "Например: Привет, это мой голос…", "", true, false);
        textCard.addView(Widgets.fieldBox(ctx, transcript), Ui.lpm(-1, -2, Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(14)));
        col.addView(textCard);

        col.addView(Widgets.sectionTitle(ctx, "3. Провайдер и согласие"));
        providerHost = Ui.column(ctx);
        col.addView(providerHost);
        renderProviderCard();

        col.addView(Widgets.sectionTitle(ctx, "4. Имя и запуск"));
        LinearLayout goCard = Widgets.card(ctx);
        goCard.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(14));
        nameField = Widgets.field(ctx, "Например: Мой голос", "", false, false);
        goCard.addView(Widgets.fieldBox(ctx, nameField), Ui.lpm(-1, -2, 0, 0, 0, 0));
        cloneProgressHost = Ui.column(ctx);
        goCard.addView(cloneProgressHost, Ui.lpm(-2, -2, 0, Ui.dp(10), 0, 0));
        cloneBtn = Ui.button(ctx, "Создать клон", R.drawable.ic_wand, Ui.BTN_PRIMARY, new Runnable() { @Override public void run() { startClone(); } });
        goCard.addView(cloneBtn, Ui.lpm(-1, -2, 0, Ui.dp(10), 0, 0));
        goCard.addView(caption(ctx, "Готовый клон появится в списке профилей ниже, но не станет голосом ассистента сам."),
            Ui.lpm(-2, -2, 0, Ui.dp(8), 0, 0));
        col.addView(goCard);

        col.addView(Widgets.sectionTitle(ctx, "Что важно знать"));
        col.addView(StudioKit.noteCard(ctx, R.drawable.ic_info, "Честные ограничения",
            "· Изменение скорости или высоты голоса — это НЕ клонирование, сходство не появится.\n"
            + "· Насколько клон похож — оценивается только на слух: сравните «Проверить» на вкладке «Озвучка».\n"
            + "· Медленные провайдеры (F5-TTS, минуты на фразу) никогда не включаются в цепочку ответов автоматически."));
        col.addView(Ui.space(ctx, 1, 8));

        col.addView(Widgets.sectionTitle(ctx, "Профили голосов"));
        profilesHost = Ui.column(ctx);
        col.addView(profilesHost);
        renderProfiles();
    }

    private static TextView caption(Context c, String s) { return Ui.text(c, s, Ui.CAPTION, Ui.P.text2); }

    // -------------------------------------------------------------------------------------------- sample card
    private void renderSampleCard() {
        sampleHost.removeAllViews();
        LinearLayout card = Widgets.card(ctx);
        card.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(14));
        recordPanel = Ui.column(ctx);
        card.addView(recordPanel);
        if (recording) renderRecording();
        else {
            LinearLayout btns = Ui.row(ctx);
            View rec = Ui.button(ctx, "Записать с микрофона", R.drawable.ic_mic, Ui.BTN_GLASS, new Runnable() { @Override public void run() { startRecording(); } });
            btns.addView(rec, Ui.lpw(1f));
            btns.addView(Ui.space(ctx, 6, 1));
            View imp = Ui.button(ctx, "Выбрать файл", R.drawable.ic_upload, Ui.BTN_GHOST, new Runnable() { @Override public void run() { pickFile(); } });
            btns.addView(imp, Ui.lpw(1f));
            card.addView(btns);
        }
        if (!samplePath.isEmpty()) {
            Widgets.divide(card);
            LinearLayout row = Ui.row(ctx);
            row.setPadding(Ui.dp(16), Ui.dp(10), Ui.dp(14), Ui.dp(10));
            row.addView(Ui.icon(ctx, R.drawable.ic_file_audio, 20, Ui.P.accent), Ui.lpm(-2, -2, 0, 0, Ui.dp(10), 0));
            TextView t = Ui.text(ctx, samplePath.substring(samplePath.lastIndexOf('/') + 1), Ui.BODY);
            t.setTypeface(Ui.medium);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            row.addView(t, Ui.lpw(1f));
            TextView d = Ui.text(ctx, sampleDesc, Ui.CAPTION, Ui.P.text3);
            d.setSingleLine(true);
            row.addView(d, Ui.lpm(-2, -2, Ui.dp(8), 0, 0, 0));
            row.addView(Ui.iconButton(ctx, R.drawable.ic_close, 36, Ui.P.text3, new Runnable() { @Override public void run() {
                samplePath = ""; sampleDesc = "";
                renderSampleCard();
                refreshCloneButton();
            } }));
            card.addView(row);
        }
        sampleHost.addView(card);
    }

    private void renderRecording() {
        recordPanel.removeAllViews();
        LinearLayout head = Ui.row(ctx);
        head.addView(Widgets.spinner(ctx, 16));
        recordTime = Ui.text(ctx, " 0:00 / 0:30", Ui.BODY, Ui.P.text);
        recordTime.setTypeface(Ui.mono);
        head.addView(recordTime);
        head.addView(Ui.space(ctx, 8, 1));
        head.addView(Ui.button(ctx, "Стоп", R.drawable.ic_stop, Ui.BTN_DANGER, new Runnable() { @Override public void run() { stopRecording(); } }));
        recordPanel.addView(head);
        levelBar = new LevelBar(ctx);
        recordPanel.addView(levelBar, Ui.lpm(-1, Ui.dp(10), 0, Ui.dp(10), 0, 0));
        recordPanel.addView(caption(ctx, "Говорите естественно 5–30 секунд, без фонового шума."), Ui.lpm(-2, -2, 0, Ui.dp(8), 0, 0));
    }

    public void startRecording() {
        if (j.listen == null || recording) return;
        recording = true;
        renderSampleCard();
        j.listen.recordSample(30, new ListenApi.RecordCallback() {
            @Override public void onProgress(final float seconds, final float level) {
                j.main.post(new Runnable() { @Override public void run() {
                    if (!recording || recordTime == null) return;
                    int s = (int) seconds;
                    recordTime.setText(String.format(Locale.US, " 0:%02d / 0:30", s));
                    if (levelBar != null) levelBar.setLevel(level);
                } });
            }
            @Override public void onDone(final String sandboxPath, final float seconds) {
                j.main.post(new Runnable() { @Override public void run() {
                    recording = false;
                    if (sandboxPath == null || sandboxPath.isEmpty()) {
                        sampleDesc = "";
                    } else {
                        samplePath = sandboxPath;
                        sampleDesc = String.format(Locale.US, "%.1f с", seconds);
                        if (transcript != null && transcript.getText().length() == 0)
                            transcript.setHint("То, что вы произнесли в записи…");
                    }
                    renderSampleCard();
                    refreshCloneButton();
                } });
            }
            @Override public void onError(final String userMessage) {
                j.main.post(new Runnable() { @Override public void run() {
                    recording = false;
                    renderSampleCard();
                    refreshCloneButton();
                    host.overlays().toast(userMessage, 2);
                } });
            }
        });
    }

    public void stopRecording() {
        if (j.listen != null) j.listen.stopRecording();
        recording = false;
        renderSampleCard();
        refreshCloneButton();
    }

    private void pickFile() {
        try {
            host.pickDocument(screen, REQ_IMPORT_SAMPLE, new String[]{"audio/*", "application/ogg", "video/mp4"}, false);
        } catch (ActivityNotFoundException e) {
            host.overlays().toast("Системный выбор файлов недоступен", 2);
        }
    }

    /** Streams a picked audio file into the sandbox (work/voice-samples/). Called on the UI thread, copies in background. */
    public void onSamplePicked(final Uri uri) {
        if (uri == null || j.sandbox == null) return;
        final Dialogs.ProgressController progress = Dialogs.progress(host, "Импорт образца", "Копирую в песочницу…", null);
        new Thread(new Runnable() { @Override public void run() {
            try {
                File dir = new File(j.sandbox.work, "voice-samples");
                dir.mkdirs();
                String name = "import-" + new SimpleDateFormat("MMdd-HHmmss", Locale.US).format(new Date()) + guessExt(uri);
                File dst = new File(dir, name);
                InputStream in = ctx.getContentResolver().openInputStream(uri);
                OutputStream out = new FileOutputStream(dst);
                byte[] buf = new byte[8192];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); total += n; }
                in.close();
                out.close();
                final String rel = j.sandbox.rel(dst);
                final String desc = PageHuman.size(total);
                j.main.post(new Runnable() { @Override public void run() {
                    progress.close();
                    samplePath = rel;
                    sampleDesc = desc;
                    renderSampleCard();
                    refreshCloneButton();
                } });
            } catch (final Exception e) {
                j.main.post(new Runnable() { @Override public void run() {
                    progress.close();
                    host.overlays().toast("Не удалось прочитать файл: " + e.getClass().getSimpleName(), 2);
                } });
            }
        } }, "jv-import-sample").start();
    }

    private String guessExt(Uri u) {
        String s = u.toString().toLowerCase(Locale.ROOT);
        if (s.endsWith(".wav")) return ".wav";
        if (s.endsWith(".mp3")) return ".mp3";
        if (s.endsWith(".m4a")) return ".m4a";
        if (s.endsWith(".flac")) return ".flac";
        if (s.endsWith(".ogg") || s.endsWith(".oga")) return ".ogg";
        return ".audio";
    }

    // -------------------------------------------------------------------------------------------- provider + consent
    private void renderProviderCard() {
        providerHost.removeAllViews();
        LinearLayout card = Widgets.card(ctx);
        card.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(14));

        final List<TtsProvider> cloners = cloners();
        if (cloners.isEmpty()) {
            card.addView(caption(ctx, "Ни один подключённый провайдер не поддерживает клонирование."));
            providerHost.addView(card);
            return;
        }
        if (providerId.isEmpty()) providerId = cloners.get(0).info().id;

        LinearLayout row = Ui.row(ctx);
        TextView t = Ui.text(ctx, "Сервис клонирования", Ui.BODY);
        t.setTypeface(Ui.medium);
        row.addView(t, Ui.lpw(1f));
        providerValue = Ui.text(ctx, providerName(), Ui.CAPTION, Ui.P.accent);
        row.addView(providerValue, Ui.lpm(-2, -2, Ui.dp(8), 0, 0, 0));
        row.addView(Ui.icon(ctx, R.drawable.ic_chevron_right, 18, Ui.P.text3));
        row.setBackground(Ui.rippleOn(new android.graphics.drawable.ColorDrawable(0), 14));
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickProvider(); } });
        card.addView(row);

        final TtsProvider p = byId(providerId);
        if (p != null) {
            Widgets.divide(card);
            LinearLayout notice = StudioKit.noteCard(ctx, R.drawable.ic_shield,
                "Образец будет отправлен в " + p.info().sendsDataTo,
            "Аудиозапись и её текст уйдут на этот сервис для создания клона. Ничего не отправляется, пока вы не нажали «Создать клон».");
            card.addView(notice, Ui.lpm(-1, -2, 0, Ui.dp(10), 0, 0));

            LinearLayout consent = Ui.row(ctx);
            TextView ct = Ui.text(ctx, "Я согласен отправить образец в " + p.info().sendsDataTo, Ui.CAPTION, Ui.P.text2);
            consent.addView(ct, Ui.lpw(1f));
            consentToggle = Widgets.toggle(ctx, consentToggle != null && consentToggle.isChecked(), new Widgets.Toggle.Listener() { @Override public void onChanged(boolean on) { refreshCloneButton(); } });
            consent.addView(consentToggle, Ui.lpm(-2, -2, Ui.dp(8), 0, 0, 0));
            card.addView(consent, Ui.lpm(-2, -2, 0, Ui.dp(4), 0, 0));

            ProviderInfo info = p.info();
            card.addView(StudioKit.metaRow(ctx, "как клонирует:", info.cloningNote), Ui.lpm(-2, -2, 0, Ui.dp(6), 0, 0));
        }
        providerHost.addView(card);
        refreshCloneButton();
    }

    private List<TtsProvider> cloners() {
        List<TtsProvider> out = new ArrayList<>();
        if (j.speech == null) return out;
        for (TtsProvider p : j.speech.ttsProviders()) if (p.info().cloning) out.add(p);
        return out;
    }

    private TtsProvider byId(String id) {
        for (TtsProvider p : cloners()) if (p.info().id.equals(id)) return p;
        return null;
    }

    private String providerName() {
        TtsProvider p = byId(providerId);
        return p == null ? "—" : p.info().name;
    }

    private void pickProvider() {
        final List<TtsProvider> cloners = cloners();
        String[] items = new String[cloners.size()];
        int sel = 0;
        for (int i = 0; i < cloners.size(); i++) {
            TtsProvider p = cloners.get(i);
            String slow = p instanceof app.jarvis.core.voice.SlowProvider ? "\nмедленно: минуты на фразу" : "";
            items[i] = p.info().name + "\n" + p.info().sendsDataTo + slow;
            if (p.info().id.equals(providerId)) sel = i;
        }
        Dialogs.choose(host, "Где клонировать", items, sel, new Dialogs.IndexListener() { @Override public void on(int index) {
            if (index >= 0 && index < cloners.size()) {
                providerId = cloners.get(index).info().id;
                renderProviderCard();
            }
        } });
    }

    // -------------------------------------------------------------------------------------------- clone run
    private boolean canClone() {
        return !samplePath.isEmpty() && transcript != null && transcript.getText().toString().trim().length() > 0
            && consentToggle != null && consentToggle.isChecked() && !cloning && j.speech != null && j.listen != null;
    }

    private void refreshCloneButton() {
        if (cloneBtn != null) {
            cloneBtn.setEnabled(canClone());
            cloneBtn.setAlpha(canClone() ? 1f : 0.45f);
        }
    }

    private void startClone() {
        if (!canClone()) return;
        final String pid = providerId;
        final String name = nameField.getText().toString().trim();
        final String finalName = name.isEmpty() ? "Мой голос" : name;
        final String refText = transcript.getText().toString().trim();
        cloning = true;
        renderCloneProgress("Создаю клон…");
        final SpeechApi.Test cb = new SpeechApi.Test() {
            @Override public void onStarted() { }
            @Override public void onFirstAudio(long ms) { }
            @Override public void onDone(long totalMs) {
                j.main.post(new Runnable() { @Override public void run() {
                    cloning = false;
                    renderCloneDone("Клон создан (" + StudioKit.ms(totalMs) + "). Профиль «" + finalName + "» в списке ниже.");
                    renderProfiles();
                } });
            }
            @Override public void onError(final String userMessage, final String technical) {
                j.main.post(new Runnable() { @Override public void run() {
                    cloning = false;
                    renderCloneError(userMessage, technical);
                } });
            }
        };
        cloneToken = j.speech.makeClone(pid, finalName, samplePath, refText, "", cb);
    }

    private void renderCloneProgress(String s) {
        cloneProgressHost.removeAllViews();
        LinearLayout row = Ui.row(ctx);
        row.addView(Widgets.spinner(ctx, 16));
        TextView t = Ui.text(ctx, "  " + s + " Это может занять несколько минут.", Ui.CAPTION, Ui.P.text2);
        row.addView(t, Ui.lpw(1f));
        cloneProgressHost.addView(row);
        cloneProgressHost.addView(Ui.button(ctx, "Отменить", 0, Ui.BTN_GHOST, new Runnable() { @Override public void run() {
            if (cloneToken != null) cloneToken.cancel();
            cloning = false;
            renderCloneIdle();
        } }), Ui.lpm(-2, -2, 0, Ui.dp(8), 0, 0));
        cloneBtn.setVisibility(View.GONE);
    }

    private void renderCloneDone(String s) {
        cloneProgressHost.removeAllViews();
        LinearLayout row = Ui.row(ctx);
        row.addView(Widgets.pill(ctx, "готово", Widgets.OK));
        row.addView(Ui.text(ctx, "  " + s, Ui.CAPTION, Ui.P.text2));
        cloneProgressHost.addView(row);
        cloneBtn.setVisibility(View.VISIBLE);
        renderCloneIdleSoon();
    }

    private void renderCloneIdleSoon() { refreshCloneButton(); }

    private void renderCloneError(String userMessage, final String technical) {
        cloneProgressHost.removeAllViews();
        LinearLayout row = Ui.row(ctx);
        row.addView(Widgets.pill(ctx, "не удалось", Widgets.ERR));
        TextView t = Ui.text(ctx, "  " + userMessage, Ui.CAPTION, Ui.P.err);
        t.setMaxLines(3);
        row.addView(t, Ui.lpw(1f));
        if (technical != null && !technical.isEmpty()) {
            row.addView(Ui.iconButton(ctx, R.drawable.ic_info, 34, Ui.P.text3, new Runnable() { @Override public void run() {
                Dialogs.info(host, "Подробности", technical);
            } }));
        }
        cloneProgressHost.addView(row);
        cloneBtn.setVisibility(View.VISIBLE);
        refreshCloneButton();
    }

    private void renderCloneIdle() {
        cloneProgressHost.removeAllViews();
        cloneBtn.setVisibility(View.VISIBLE);
        refreshCloneButton();
    }

    // -------------------------------------------------------------------------------------------- profiles
    private void renderProfiles() {
        if (profilesHost == null) return;
        profilesHost.removeAllViews();
        if (j.speech == null) return;
        List<SpeechApi.Profile> profiles = j.speech.profiles();
        SpeechApi.Profile active = j.speech.activeProfile();
        LinearLayout card = Widgets.card(ctx);
        if (profiles.isEmpty()) {
            card.addView(caption(ctx, "Пока нет ни одного профиля. Создайте клон выше — или используйте обычные голоса провайдеров."),
                Ui.lpm(-2, -2, Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12)));
            profilesHost.addView(card);
            return;
        }
        int i = 0;
        for (final SpeechApi.Profile p : profiles) {
            if (i > 0) Widgets.divide(card);
            LinearLayout row = Ui.row(ctx);
            row.setPadding(Ui.dp(16), Ui.dp(11), Ui.dp(10), Ui.dp(11));
            LinearLayout tx = Ui.column(ctx);
            LinearLayout nameRow = Ui.row(ctx);
            TextView name = Ui.text(ctx, p.name, Ui.BODY);
            name.setTypeface(Ui.medium);
            nameRow.addView(name);
            if (active != null && active.id.equals(p.id)) {
                nameRow.addView(Ui.space(ctx, 6, 1));
                nameRow.addView(Widgets.pill(ctx, "голос ассистента", Widgets.OK));
            } else if (p.cloned) {
                nameRow.addView(Ui.space(ctx, 6, 1));
                nameRow.addView(Widgets.pill(ctx, "клон", Widgets.INFO));
            }
            tx.addView(nameRow);
            StringBuilder sub = new StringBuilder();
            TtsProvider prov = j.speech.tts(p.providerId);
            sub.append(prov == null ? p.providerId : prov.info().name);
            if (p.created > 0) sub.append(" · создан ").append(StudioKit.ago(ctx, p.created));
            if (!p.refAudioPath.isEmpty()) sub.append(" · из образца");
            tx.addView(Ui.text(ctx, sub.toString(), Ui.CAPTION, Ui.P.text3), Ui.lpm(-2, -2, 0, Ui.dp(2), 0, 0));
            row.addView(tx, Ui.lpw(1f));
            row.addView(Ui.iconButton(ctx, R.drawable.ic_play, 38, Ui.P.accent, new Runnable() { @Override public void run() { playProfile(p); } }));
            row.addView(Ui.iconButton(ctx, active != null && active.id.equals(p.id) ? R.drawable.ic_volume_off : R.drawable.ic_volume, 38,
                active != null && active.id.equals(p.id) ? Ui.P.ok : Ui.P.text2, new Runnable() { @Override public void run() { toggleActive(p); } }));
            row.addView(Ui.iconButton(ctx, R.drawable.ic_edit, 38, Ui.P.text2, new Runnable() { @Override public void run() { renameProfile(p); } }));
            row.addView(Ui.iconButton(ctx, R.drawable.ic_trash, 38, Ui.P.text3, new Runnable() { @Override public void run() { deleteProfile(p); } }));
            card.addView(row);
            i++;
        }
        profilesHost.addView(card);
    }

    private void playProfile(SpeechApi.Profile p) {
        if (j.speech == null || j.speech.tts(p.providerId) == null) {
            host.overlays().toast("Провайдер профиля не подключён", 2);
            return;
        }
        TtsRequest req = new TtsRequest();
        req.text = "Привет! Это мой новый голос, проверка звучания.";
        req.voiceId = p.voiceId;
        req.speed = p.speed > 0 ? p.speed : 1f;
        req.options.putAll(p.options);
        req.allowCache = false;
        if (!p.refAudioPath.isEmpty()) {
            try { req.refAudioPath = j.sandbox.resolve(p.refAudioPath).getPath(); } catch (Exception ignored) { }
            req.refText = p.refText;
        }
        j.speech.speakWith(p.providerId, req, new SpeechApi.Test() {
            @Override public void onStarted() { }
            @Override public void onFirstAudio(long ms) { }
            @Override public void onDone(long totalMs) { }
            @Override public void onError(String userMessage, String technical) { host.overlays().toast(userMessage, 2); }
        });
    }

    private void toggleActive(SpeechApi.Profile p) {
        SpeechApi.Profile active = j.speech.activeProfile();
        boolean isActive = active != null && active.id.equals(p.id);
        j.speech.setActiveProfile(isActive ? null : p.id);
        renderProfiles();
        host.overlays().toast(isActive ? "Голос ассистента сброшен" : "Голос ассистента: " + p.name, 0);
    }

    private void renameProfile(final SpeechApi.Profile p) {
        Dialogs.prompt(host, "Переименовать голос", "Название", p.name, false, false, "Сохранить", new Dialogs.StringListener() { @Override public void on(String s) {
            String v = s.trim();
            if (v.isEmpty() || v.equals(p.name)) return;
            p.name = v;
            j.speech.saveProfile(p);
            renderProfiles();
        } });
    }

    private void deleteProfile(final SpeechApi.Profile p) {
        Dialogs.confirm(host, "Удалить голос?", "Профиль «" + p.name + "» будет удалён. Образец в песочнице останется.", "Удалить", true,
            new Runnable() { @Override public void run() {
                if (j.speech != null) j.speech.deleteProfile(p.id);
                renderProfiles();
            } });
    }

    // -------------------------------------------------------------------------------------------- level meter
    /** Horizontal loudness bar for the recording state (updated ~20 Hz by the record callback). */
    private static final class LevelBar extends View {
        private float level;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        LevelBar(Context c) { super(c); }

        void setLevel(float v) {
            level = Math.max(0f, Math.min(1f, v));
            invalidate();
        }

        @Override protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), Ui.dp(10)); }

        @Override protected void onDraw(Canvas c) {
            float h = getHeight();
            r.set(0, 0, getWidth(), h);
            p.setColor(Ui.P.field);
            c.drawRoundRect(r, h / 2, h / 2, p);
            float f = 0.04f + 0.96f * level;
            r.set(0, 0, Math.max(h, getWidth() * f), h);
            p.setColor(level > 0.85f ? Ui.P.warn : Ui.P.accent);
            c.drawRoundRect(r, h / 2, h / 2, p);
        }
    }
}

/** Tiny formatting helpers shared inside the studio package. */
final class PageHuman {
    private PageHuman() { }
    static String size(long b) {
        if (b < 1024) return b + " Б";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.0f КБ", b / 1024.0);
        return String.format(Locale.US, "%.1f МБ", b / 1024.0 / 1024.0);
    }
}
