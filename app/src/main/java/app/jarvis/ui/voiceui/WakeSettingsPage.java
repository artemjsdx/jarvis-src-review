package app.jarvis.ui.voiceui;

import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.voice.ProviderInfo;
import app.jarvis.core.voice.ProviderStatus;
import app.jarvis.core.voice.SttProvider;
import app.jarvis.data.Settings;
import app.jarvis.wake.WakeEngine;
import app.jarvis.wake.WakePack;
import java.io.File;
import app.jarvis.rt.ListenApi;
import app.jarvis.ui.Host;
import app.jarvis.ui.Ui;
import app.jarvis.ui.WrapButton;
import app.jarvis.ui.Widgets;
import app.jarvis.ui.settings.SettingsPage;
import java.util.ArrayList;
import java.util.List;

/**
 * «Обращение и распознавание»: тумблер режима обращения с честным micProblem, фразы обращения,
 * эхоподавление, цепочка STT и провайдер фильтра «Джарвис» с реальными проверками (образец и микрофон),
 * язык распознавания и простое объяснение, что режим делает с микрофоном.
 */
public class WakeSettingsPage extends SettingsPage {
    /** Shown when the user has no custom list; must match WakeMatcher.defaultRu(). */
    static final String DEFAULT_PHRASES = "джарвис, джарвиз, жарвис, джервис, джарвес, ярвис, эй джарвис, привет джарвис, хей джарвис, jarvis";

    public enum Panel { BOTH, RECOGNITION, WAKE }
    private final Panel panel;
    private LinearLayout content, sttHost, checkHost;
    private TextView modelStatus;
    private volatile boolean modelInstalling;
    private TextView micNote;
    private final CancelToken checkToken = new CancelToken();
    private String testingPid = "";

    public WakeSettingsPage(Host host) { this(host, Panel.BOTH); }
    public WakeSettingsPage(Host host, Panel panel) {
        super(host);
        this.panel = panel == null ? Panel.BOTH : panel;
    }

    @Override public String title() { return "Обращение и распознавание"; }

    @Override protected View build() {
        ScrollView sv = Ui.scroll(ctx);
        sv.setFillViewport(true);
        content = Ui.column(ctx);
        content.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(24));
        if (panel != Panel.RECOGNITION) {
            content.addView(Widgets.sectionTitle(ctx, "Слово «Джарвис»"));
            content.addView(wakeCard());
            content.addView(Widgets.sectionTitle(ctx, "Локальная модель и режим распознавания обращения"));
            content.addView(wakeEngineCard());
            content.addView(Widgets.sectionTitle(ctx, "Как это работает"));
            content.addView(explainCard());
        }
        if (panel != Panel.WAKE) {
            content.addView(Widgets.sectionTitle(ctx, "Кто превращает голос в текст (STT)"));
            content.addView(VoiceUiKit.note(ctx,
                "STT не зависит от DeepSeek и озвучки. Каждый сервис использует собственный ключ и лимиты. " +
                "OpenRouter здесь не является языковой моделью."));
            sttHost = Ui.column(ctx);
            content.addView(sttHost);
            renderStt();
            content.addView(Widgets.sectionTitle(ctx, "Язык команд"));
            content.addView(languageCard());
        }
        sv.addView(content);
        return sv;
    }

    // -------------------------------------------------------------------------------------------- wake
    private View wakeCard() {
        LinearLayout card = Widgets.card(ctx);
        card.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(14));

        LinearLayout row = Ui.row(ctx);
        LinearLayout tx = Ui.column(ctx);
        TextView t = Ui.text(ctx, "Слушать «Джарвис»", Ui.BODY);
        t.setTypeface(Ui.medium);
        tx.addView(t);
        micNote = Ui.text(ctx, micProblemText(), Ui.CAPTION, Ui.P.text3);
        micNote.setMaxLines(3);
        tx.addView(micNote, Ui.lpm(-2, -2, 0, Ui.dp(2), 0, 0));
        row.addView(tx, Ui.lpw(1f));
        row.addView(Widgets.toggle(ctx, j.settings.getBool(Settings.WAKE_ENABLED, false), new Widgets.Toggle.Listener() { @Override public void onChanged(boolean on) {
            j.settings.setBool(Settings.WAKE_ENABLED, on);
            if (j.voice != null) j.voice.setWakeEnabled(on);
            micNote.setText(micProblemText());
        } }), Ui.lpm(-2, -2, Ui.dp(8), 0, 0, 0));
        card.addView(row);

        Widgets.divide(card);
        card.addView(VoiceUiKit.caption(ctx, "Фразы обращения (через запятую)"), Ui.lpm(-2, -2, Ui.dp(16), Ui.dp(12), Ui.dp(16), 0));
        final EditText phrases = VoiceUiKit.saveField(ctx, DEFAULT_PHRASES, j.settings.get(Settings.WAKE_PHRASES), new VoiceUiKit.TextListener() { @Override public void on(String s) {
            String v = s.trim();
            if (v.isEmpty()) j.settings.remove(Settings.WAKE_PHRASES);
            else j.settings.set(Settings.WAKE_PHRASES, v);
        } });
        card.addView(Widgets.fieldBox(ctx, phrases), Ui.lpm(-1, -2, Ui.dp(16), Ui.dp(8), Ui.dp(16), 0));
        card.addView(VoiceUiKit.note(ctx, "Пусто = список по умолчанию (серый текст). Распознавание уже рассчитано на типовые ослышки вроде «жарвис»."),
            Ui.lpm(-2, -2, Ui.dp(16), Ui.dp(4), Ui.dp(16), Ui.dp(12)));

        Widgets.divide(card);
        LinearLayout aec = Ui.row(ctx);
        LinearLayout atx = Ui.column(ctx);
        TextView at = Ui.text(ctx, "Эхоподавление", Ui.BODY);
        at.setTypeface(Ui.medium);
        atx.addView(at);
        atx.addView(VoiceUiKit.note(ctx, "Не отдавать в распознавание звук из динамика: ассистент не услышит сам себя."), Ui.lpm(-2, -2, 0, Ui.dp(2), 0, 0));
        aec.addView(atx, Ui.lpw(1f));
        aec.addView(Widgets.toggle(ctx, j.settings.getBool(Settings.WAKE_AEC, true), new Widgets.Toggle.Listener() { @Override public void onChanged(boolean on) {
            j.settings.setBool(Settings.WAKE_AEC, on);
        } }), Ui.lpm(-2, -2, Ui.dp(8), 0, 0, 0));
        card.addView(aec, Ui.lpm(-2, -2, Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12)));
        return card;
    }

    private String micProblemText() {
        if (j.voice == null) return "Голосовой модуль не подключён";
        String p = j.voice.micProblem();
        if (p == null || p.isEmpty()) return j.settings.getBool(Settings.WAKE_ENABLED, false) ? "работает: микрофон активен" : "выключено — микрофон не используется";
        return "проблема: " + p;
    }

    // -------------------------------------------------------------------------------------------- stt
    private void renderStt() {
        sttHost.removeAllViews();
        checkHost = Ui.column(ctx);

        LinearLayout card = Widgets.card(ctx);
        card.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(14));
        card.addView(VoiceUiKit.caption(ctx,
            "Ниже — сервисы для распознавания команд. При облачном режиме обращения они также могут распознавать «Джарвис». " +
            "При установленной офлайн-модели обращение распознаётся локально."),
            Ui.lpm(-2, -2, 0, 0, 0, Ui.dp(8)));

        List<SttProvider> providers = j.listen == null ? new ArrayList<SttProvider>() : sttList();
        List<String> chain = j.settings.getList(Settings.STT_CHAIN);
        if (chain.isEmpty() && !providers.isEmpty()) { chain.add("google-stt"); chain.add("groq-stt"); chain.add("vosk-local"); }
        String wakeStt = j.settings.get(Settings.WAKE_STT, chain.isEmpty() ? "google-stt" : chain.get(0));

        List<SttProvider> ordered = new ArrayList<>();
        for (String id : chain) for (SttProvider p : providers) if (p.info().id.equals(id)) { ordered.add(p); break; }
        for (SttProvider p : providers) if (!ordered.contains(p)) ordered.add(p);

        int i = 0;
        for (final SttProvider p : ordered) {
            final String pid = p.info().id;
            if (i > 0) Widgets.divide(card);
            LinearLayout row = Ui.row(ctx);
            row.setPadding(0, Ui.dp(10), 0, Ui.dp(10));
            LinearLayout tx = Ui.column(ctx);
            LinearLayout head = Ui.row(ctx);
            TextView name = Ui.text(ctx, p.info().name, Ui.BODY);
            name.setTypeface(Ui.medium);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            head.addView(name, Ui.lpw(1f));
            if (pid.equals(wakeStt)) head.addView(Widgets.pill(ctx, "фильтр «Джарвис»", Widgets.OK), Ui.lpm(-2, -2, Ui.dp(6), 0, 0, 0));
            if (!p.info().official) head.addView(Widgets.pill(ctx, "неофиц.", Widgets.WARN), Ui.lpm(-2, -2, Ui.dp(6), 0, 0, 0));
            tx.addView(head);
            StringBuilder sub = new StringBuilder();
            if (!p.isConfigured()) sub.append("нет ключа — проверки не пройдут");
            else sub.append(p.info().sendsDataTo);
            ProviderStatus st = p.lastStatus();
            if (st != ProviderStatus.NEVER) sub.append(st.ok ? " · работает" : " · ошибка");
            TextView s = VoiceUiKit.note(ctx, sub.toString());
            s.setMaxLines(2);
            tx.addView(s, Ui.lpm(-2, -2, 0, Ui.dp(2), 0, 0));
            row.addView(tx, Ui.lpw(1f));

            row.addView(Ui.iconButton(ctx, R.drawable.ic_volume, 38, Ui.P.text2, new Runnable() { @Override public void run() { runMicTest(pid); } }));
            row.addView(Ui.iconButton(ctx, R.drawable.ic_check, 38, pid.equals(testingPid) ? Ui.P.accent : Ui.P.text2, new Runnable() { @Override public void run() { runSampleCheck(pid); } }));
            row.addView(Ui.iconButton(ctx, R.drawable.ic_star, 38, pid.equals(wakeStt) ? Ui.P.ok : Ui.P.text3, new Runnable() { @Override public void run() {
                j.settings.set(Settings.WAKE_STT, pid);
                renderStt();
            } }));
            card.addView(row);
            i++;
        }
        sttHost.addView(card);
        sttHost.addView(checkHost, Ui.lpm(-2, -2, 0, Ui.dp(10), 0, 0));
        sttHost.addView(VoiceUiKit.note(ctx, "Голубая галочка — проверка образцом без микрофона (то, что сервис распознал и за сколько). Динамик — сказать фразу в микрофон и увидеть распознанное."),
            Ui.lpm(-2, -2, 0, Ui.dp(6), 0, 0));
        sttHost.addView(WrapButton.create(ctx, "Порядок резервных — в поле ниже (stt.chain)", R.drawable.ic_list, Ui.BTN_GHOST, new Runnable() { @Override public void run() {
            editChain();
        } }), Ui.lpm(-2, -2, 0, Ui.dp(8), 0, 0));
    }

    private List<SttProvider> sttList() {
        try { return j.speech == null ? new ArrayList<SttProvider>() : j.speech.sttProviders(); }
        catch (Throwable t) { return new ArrayList<SttProvider>(); }
    }

    private void editChain() {
        List<String> chain = j.settings.getList(Settings.STT_CHAIN);
        String cur = j.settings.get(Settings.STT_CHAIN);
        app.jarvis.ui.Dialogs.prompt(host, "Цепочка распознавания", "id через запятую: google-stt, groq-stt, elevenlabs-stt, openrouter-stt, vosk-local",
            cur, false, false, "Сохранить", new app.jarvis.ui.Dialogs.StringListener() { @Override public void on(String s) {
                List<String> v = new ArrayList<>();
                for (String part : s.split(",")) { part = part.trim(); if (!part.isEmpty()) v.add(part); }
                j.settings.setList(Settings.STT_CHAIN, v);
                renderStt();
            } });
    }

    /** Real recognition of the bundled Russian sample through the chosen provider. */
    private void runSampleCheck(final String pid) {
        if (j.listen == null) return;
        testingPid = pid;
        renderCheckState("Распознаю образец…");
        final ListenApi.SttTest cb = new ListenApi.SttTest() {
            @Override public void onStarted() { }
            @Override public void onLevel(float level) { }
            @Override public void onResult(final String text, final long latencyMs, final String providerId) {
                j.main.post(new Runnable() { @Override public void run() { testingPid = ""; renderCheckResult(text, latencyMs, providerId, true); } });
            }
            @Override public void onError(final String userMessage, final String technical) {
                j.main.post(new Runnable() { @Override public void run() { testingPid = ""; renderCheckError(userMessage, technical); } });
            }
        };
        j.listen.checkStt(pid, cb);
    }

    /** Real microphone round: record one utterance, transcribe it, show text + latency. */
    private void runMicTest(final String pid) {
        if (j.listen == null) return;
        String problem = j.voice.micProblem();
        if (problem != null && !problem.isEmpty()) { host.overlays().toast("Микрофон недоступен: " + problem, 2); return; }
        testingPid = pid;
        renderCheckState("Скажите фразу… распознаю с микрофона");
        final ListenApi.SttTest cb = new ListenApi.SttTest() {
            @Override public void onStarted() { }
            @Override public void onLevel(final float level) {
                j.main.post(new Runnable() { @Override public void run() { if (checkHost != null && testingPid.equals(pid)) updateCheckLevel(level); } });
            }
            @Override public void onResult(final String text, final long latencyMs, final String providerId) {
                j.main.post(new Runnable() { @Override public void run() { testingPid = ""; renderCheckResult(text, latencyMs, providerId, false); } });
            }
            @Override public void onError(final String userMessage, final String technical) {
                j.main.post(new Runnable() { @Override public void run() { testingPid = ""; renderCheckError(userMessage, technical); } });
            }
        };
        j.listen.testStt(pid, cb);
    }

    private void renderCheckState(String s) {
        checkHost.removeAllViews();
        LinearLayout row = Ui.row(ctx);
        row.addView(Widgets.spinner(ctx, 16));
        TextView t = Ui.text(ctx, "  " + s, Ui.CAPTION, Ui.P.text2);
        t.setTag("check.text");
        row.addView(t, Ui.lpw(1f));
        row.addView(WrapButton.create(ctx, "Отмена", 0, Ui.BTN_GHOST, new Runnable() { @Override public void run() {
            checkToken.cancel();
            testingPid = "";
            checkHost.removeAllViews();
        } }));
        checkHost.addView(row);
    }

    private void updateCheckLevel(float level) {
        TextView t = (TextView) checkHost.findViewWithTag("check.text");
        if (t == null) return;
        int n = Math.round(level * 10);
        StringBuilder sb = new StringBuilder("  ");
        for (int i = 0; i < 10; i++) sb.append(i < n ? "●" : "○");
        sb.append("  говорите…");
        t.setText(sb.toString());
    }

    private void renderCheckResult(String text, long latencyMs, String providerId, boolean fromSample) {
        checkHost.removeAllViews();
        LinearLayout row = Ui.row(ctx);
        row.addView(Widgets.pill(ctx, fromSample ? "образец распознан" : "распознано", Widgets.OK));
        TextView t = Ui.text(ctx, "  " + (latencyMs > 0 ? VoiceUiKit.group(latencyMs) + " мс · " : "") + "«" + text + "»", Ui.CAPTION, Ui.P.text2);
        t.setMaxLines(3);
        row.addView(t, Ui.lpw(1f));
        checkHost.addView(row);
    }

    private void renderCheckError(final String userMessage, final String technical) {
        checkHost.removeAllViews();
        LinearLayout row = Ui.row(ctx);
        row.addView(Widgets.pill(ctx, "ошибка", Widgets.ERR));
        TextView t = Ui.text(ctx, "  " + userMessage, Ui.CAPTION, Ui.P.err);
        t.setMaxLines(3);
        row.addView(t, Ui.lpw(1f));
        if (technical != null && !technical.isEmpty()) {
            row.addView(Ui.iconButton(ctx, R.drawable.ic_info, 34, Ui.P.text3, new Runnable() { @Override public void run() {
                app.jarvis.ui.Dialogs.info(host, "Подробности", technical);
            } }));
        }
        checkHost.addView(row);
    }

    // -------------------------------------------------------------------------------------------- language
    private View languageCard() {
        LinearLayout card = Widgets.card(ctx);
        card.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(14));
        card.addView(VoiceUiKit.caption(ctx, "Язык распознавания (stt.language)"), Ui.lpm(-2, -2, 0, 0, 0, Ui.dp(8)));
        final EditText lang = VoiceUiKit.saveField(ctx, "ru", j.settings.get(Settings.STT_LANGUAGE, "ru"), new VoiceUiKit.TextListener() { @Override public void on(String s) {
            String v = s.trim().toLowerCase(java.util.Locale.ROOT);
            if (v.isEmpty()) { j.settings.remove(Settings.STT_LANGUAGE); return; }
            j.settings.set(Settings.STT_LANGUAGE, v);
            host.overlays().toast("Язык: " + v, 0);
        } });
        card.addView(Widgets.fieldBox(ctx, lang), Ui.lpm(-1, -2, 0, 0, 0, Ui.dp(8)));
        card.addView(VoiceUiKit.note(ctx, "Используется и обращением, и командами; провайдеры с фиксированным языком игнорируют это поле."));
        return card;
    }

    // -------------------------------------------------------------------------------------------- local wake engine
    private View wakeEngineCard() {
        LinearLayout card = Widgets.card(ctx);
        card.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(14));
        String[] values = {"auto", "local", "cloud"};
        int selected = 0;
        String mode = j.settings.get(Settings.WAKE_ENGINE, WakeEngine.AUTO);
        for (int i = 0; i < values.length; i++) if (values[i].equals(mode)) selected = i;
        card.addView(VoiceUiKit.caption(ctx, "Распознавание обращения"),
            Ui.lpm(-1, -2, 0, 0, 0, Ui.dp(8)));
        card.addView(Widgets.segmented(ctx,
            new String[]{"Авто", "Офлайн", "Облако"}, selected, index -> {
                j.settings.set(Settings.WAKE_ENGINE, values[index]);
                host.overlays().toast("Режим обращения: " + values[index], 1);
            }));
        card.addView(VoiceUiKit.note(ctx,
            "Авто использует Vosk, если модель установлена; иначе облако. «Офлайн» не использует облако для обнаружения обращения."),
            Ui.lpm(-1, -2, 0, Ui.dp(8), 0, Ui.dp(12)));

        File modelDir = new File(ctx.getFilesDir(), "models/" + WakePack.MODEL_NAME);
        modelStatus = Ui.text(ctx, WakePack.looksInstalled(modelDir)
            ? "Офлайн-модель установлена · 46 МБ исходный архив"
            : "Офлайн-модель не установлена. Без неё «Авто» обращается к платным/ограниченным STT.",
            Ui.CAPTION, Ui.P.text2);
        modelStatus.setSingleLine(false);
        card.addView(modelStatus, Ui.lpm(-1, -2, 0, 0, 0, Ui.dp(8)));
        card.addView(WrapButton.create(ctx, "Установить офлайн-модель (46 МБ)", R.drawable.ic_download,
            Ui.BTN_GLASS, () -> installModel()), Ui.lpm(-1, -2, 0, 0, 0, Ui.dp(12)));

        LinearLayout confirm = Ui.row(ctx);
        confirm.addView(Ui.text(ctx, "Подтверждать «Джарвис» через облако", Ui.CAPTION), Ui.lpw(1f));
        confirm.addView(Widgets.toggle(ctx, j.settings.getBool("wake.cloudConfirm", false),
            on -> j.settings.setBool("wake.cloudConfirm", on)));
        card.addView(confirm);
        card.addView(VoiceUiKit.note(ctx, "Выключено по умолчанию: обнаруженное Vosk обращение срабатывает даже без кредитов OpenRouter."),
            Ui.lpm(-1, -2, 0, Ui.dp(8), 0, 0));
        return card;
    }

    private void installModel() {
        if (modelInstalling) return;
        modelInstalling = true;
        if (modelStatus != null) modelStatus.setText("Подготовка загрузки офлайн-модели…");
        new Thread(() -> {
            File dir = new File(ctx.getFilesDir(), "models/" + WakePack.MODEL_NAME);
            File cache = new File(ctx.getCacheDir(), "wake-model");
            WakePack pack = new WakePack(dir, cache, j.journal);
            try {
                pack.install(status -> j.main.post(() -> {
                    if (modelStatus == null) return;
                    String msg = status.message;
                    if (status.bytesTotal > 0 && status.bytesDone >= 0)
                        msg += " · " + Math.round(100.0 * status.bytesDone / status.bytesTotal) + " %";
                    modelStatus.setText(msg.isEmpty() ? "Модель установлена" : msg);
                }), new CancelToken());
                j.main.post(() -> {
                    modelInstalling = false;
                    if (modelStatus != null) modelStatus.setText("Офлайн-модель установлена. «Джарвис» теперь можно обнаруживать без облака.");
                    host.overlays().toast("Офлайн-модель установлена", 1);
                });
            } catch (Exception e) {
                j.main.post(() -> {
                    modelInstalling = false;
                    if (modelStatus != null) modelStatus.setText("Ошибка установки: " + e.getMessage());
                });
            }
        }, "jv-wake-model-install").start();
    }

    // -------------------------------------------------------------------------------------------- explanation
    private View explainCard() {
        LinearLayout card = Widgets.card(ctx);
        card.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(14));
        card.addView(VoiceUiKit.caption(ctx,
            "· Локальный Vosk распознаёт «Джарвис» без OpenRouter и Google; офлайн-модель должна быть установлена.\n"
            + "· В автоматическом режиме без модели будет использована облачная цепочка STT, у которой могут быть лимиты и ошибки ключей.\n"
            + "· Подтверждение через облако — отдельная необязательная функция. Она может снизить ложные срабатывания, но требует работоспособного STT.\n"
            + "· Если ассистент не слышит команду после обращения, проверьте отдельную вкладку «Распознавание».\n"
            + "· В фоне Android может ограничить микрофон: настройте фоновую службу и батарею."));
        return card;
    }

    @Override public void onShow() {
        if (sttHost != null) renderStt();
    }

    @Override public void onHide() {
        checkToken.cancel();
        testingPid = "";
    }
}
