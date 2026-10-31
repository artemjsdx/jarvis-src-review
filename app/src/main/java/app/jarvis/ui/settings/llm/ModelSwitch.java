package app.jarvis.ui.settings.llm;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import app.jarvis.R;
import app.jarvis.core.agent.ContextFit;
import app.jarvis.core.agent.ContextSummarizer;
import app.jarvis.core.llm.ChatMessage;
import app.jarvis.core.log.Journal;
import app.jarvis.core.llm.LlmException;
import app.jarvis.core.llm.LlmListener;
import app.jarvis.core.llm.LlmRequest;
import app.jarvis.core.llm.LlmResult;
import app.jarvis.core.util.CancelToken;
import app.jarvis.data.ChatItem;
import app.jarvis.data.ChatStore;
import app.jarvis.data.Settings;
import app.jarvis.rt.Jarvis;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.Host;
import app.jarvis.ui.OverlayHost;
import app.jarvis.ui.Ui;
import java.util.ArrayList;
import java.util.List;

/**
 * Every change of model or provider goes through here. The visible history of the active conversation is checked against the
 * new model first: a silent switch when it fits and nothing is lost, otherwise a plain question with three answers.
 * Weights and the model's internal state never move: the text history is resent, so the new model starts from it.
 */
public final class ModelSwitch {
    /** The newest messages are never summarised: they go to the new model as they are. */
    static final int KEEP_RECENT = 4;

    private ModelSwitch() { }

    /** An empty model only changes the provider (the model is chosen later). */
    public static void request(final Host host, final String providerId, final String model, final Runnable done) {
        final Jarvis j = host.jarvis();
        if (model == null || model.isEmpty()) {
            j.settings.set(Settings.LLM_PROVIDER, providerId);
            if (done != null) done.run();
            return;
        }
        if (j.catalog.caps(providerId, model).realtimeOnly()) {
            Dialogs.info(host, "Не для текстового чата", "Модель «" + model + "» работает по голосовому или realtime-протоколу. "
                + "Джарвис общается текстом и вызывает инструменты, поэтому такую модель выбрать нельзя. Выберите текстовую модель.");
            return;
        }
        final long conv = j.sessions.active();
        List<ChatMessage> history = j.chat.history(conv, Integer.MAX_VALUE);
        ContextFit.Result r = j.checkSwitch(providerId, model);
        if (!r.needsDialog()) {
            j.selectModel(providerId, model);
            String note = r.toolsUnknown ? " · инструменты: неизвестно" : "";
            host.overlays().toast("Модель: " + model + note, OverlayHost.INFO);
            if (done != null) done.run();
            return;
        }
        ask(host, providerId, model, history, r, conv, done);
    }

    private static void ask(final Host host, final String providerId, final String model, final List<ChatMessage> history,
                            ContextFit.Result r, final long conv, final Runnable done) {
        OverlayHost.Layer[] ly = new OverlayHost.Layer[1];
        ly[0] = host.overlays().showDialog(questionView(host, providerId, model, history, r, conv, done, ly), true, null);
    }

    /** The question with its three answers; {@code ly[0]} is the dialog it is shown in (filled by the caller). */
    public static View questionView(final Host host, final String providerId, final String model, final List<ChatMessage> history,
                             ContextFit.Result r, final long conv, final Runnable done, final OverlayHost.Layer[] ly) {
        final Context c = host.context();
        final Jarvis j = host.jarvis();
        LinearLayout body = Ui.column(c);
        body.setPadding(Ui.dp(20), Ui.dp(18), Ui.dp(20), Ui.dp(16));
        body.addView(Ui.text(c, "Сменить модель на «" + model + "»?", Ui.TITLE), Ui.lpm(-1, -2, 0, 0, 0, Ui.dp(10)));
        for (String line : ContextFit.describe(r, model)) {
            body.addView(Ui.text(c, "• " + line, Ui.CAPTION, Ui.P.text2), Ui.lpm(-1, -2, 0, 0, 0, Ui.dp(6)));
        }
        final boolean canSummarise = history.size() > KEEP_RECENT;
        body.addView(Ui.button(c, "Переключить и обрезать старое", R.drawable.ic_refresh, Ui.BTN_PRIMARY, new Runnable() { @Override public void run() {
            host.overlays().dismiss(ly[0]);
            j.selectModel(providerId, model);
            host.overlays().toast("Модель: " + model + ". Старое, что не влезает в окно, в запрос не попадает", OverlayHost.INFO);
            if (done != null) done.run();
        } }), Ui.lpm(-1, -2, 0, Ui.dp(12), 0, 0));
        if (canSummarise) body.addView(Ui.button(c, "Передать краткое резюме", R.drawable.ic_edit, Ui.BTN_GLASS, new Runnable() { @Override public void run() {
            host.overlays().dismiss(ly[0]);
            summarise(host, providerId, model, history, conv, done);
        } }), Ui.lpm(-1, -2, 0, Ui.dp(8), 0, 0));
        body.addView(Ui.button(c, "Отмена", R.drawable.ic_trash, Ui.BTN_GHOST, new Runnable() { @Override public void run() {
            host.overlays().dismiss(ly[0]);
        } }), Ui.lpm(-1, -2, 0, Ui.dp(8), 0, 0));
        return body;
    }

    /** Summarises the older part with the CURRENT model, then clears the context and stores the summary as the first item of the new one. */
    private static void summarise(final Host host, final String providerId, final String model, final List<ChatMessage> history,
                                  final long conv, final Runnable done) {
        final Jarvis j = host.jarvis();
        final Jarvis.LlmChoice ch = j.choiceFor(j.providerId(), null);
        if (ch == null || !ch.hasKey || ch.model.isEmpty()) {
            host.overlays().toast("Для резюме нужна текущая модель с ключом", OverlayHost.ERR);
            return;
        }
        final List<ChatMessage> older = new ArrayList<>(history.subList(0, history.size() - KEEP_RECENT));
        final Dialogs.ProgressController pc = Dialogs.progress(host, "Резюме переписки", "Сжимаю старую часть текущей моделью…", null);
        Thread th = new Thread(new Runnable() { @Override public void run() {
            try {
                LlmRequest req = ContextSummarizer.buildRequest(ch.model, older, j.contextChars(ch.preset.id, ch.model));
                LlmResult res = j.llm.chat(ch.config, req, LlmListener.NOP, new CancelToken());
                final String summary = ContextSummarizer.clip(res.content);
                j.main.post(new Runnable() { @Override public void run() {
                    pc.close();
                    if (summary.isEmpty()) { host.overlays().toast("Модель вернула пустое резюме", OverlayHost.ERR); return; }
                    j.selectModel(providerId, model);
                    j.sessions.clearContext(conv);
                    ChatItem it = new ChatItem();
                    it.convId = conv;
                    it.kind = ChatItem.Kind.SYSTEM;
                    it.text = ChatStore.SUMMARY_PREFIX + "\n" + summary;
                    j.chat.add(it);
                    j.sessions.select(conv);
                    host.overlays().toast("Модель: " + model + ". Резюме передано", OverlayHost.OK);
                    if (done != null) done.run();
                } });
            } catch (LlmException e) {
                j.journal.log(Journal.Cat.LLM, Journal.Level.ERROR, "Резюме переписки не получилось: " + e.userMessage, null, null, -1);
                j.main.post(new Runnable() { @Override public void run() {
                    pc.close();
                    host.overlays().toast("Резюме не получилось: " + e.userMessage, OverlayHost.ERR);
                } });
            }
        } }, "jv-summary");
        th.setDaemon(true);
        th.start();
    }
}
