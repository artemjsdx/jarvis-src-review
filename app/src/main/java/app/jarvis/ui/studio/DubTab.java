package app.jarvis.ui.studio;

import android.content.Context;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.voice.ProviderInfo;
import app.jarvis.core.voice.ProviderStatus;
import app.jarvis.core.voice.TtsProvider;
import app.jarvis.core.voice.TtsRequest;
import app.jarvis.data.Settings;
import app.jarvis.data.Secrets;
import app.jarvis.rt.SpeechApi;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.HelpPopup;
import app.jarvis.ui.WrapButton;
import app.jarvis.ui.Host;
import app.jarvis.ui.ProviderGuides;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Вкладка «Озвучка»: карточки всех TTS-провайдеров с честным статусом и реальной проверкой, выбор голоса,
 * параметры провайдера (скорость, у ElevenLabs — модель и настройки голоса) и тестовая площадка:
 * фраза → «Озвучить», «Стоп», «Снять 3 дубля» с повтором каждого дубля.
 */
public class DubTab {
    static final String[] PRESETS = {
        "Привет! Я Джарвис, ваш голосовой помощник.",
        "На улице двадцать восемь градусов, дождя нет.",
        "Готово: заметка сохранена в документы.",
    };
    static final int MAX_TEST_CHARS = 300;

    private final Host host;
    private final Context ctx;
    private final app.jarvis.rt.Jarvis j;
    private LinearLayout col;
    private final Map<String, ProviderCard> cards = new LinkedHashMap<>();
    private final Map<String, Boolean> detailsExpanded = new LinkedHashMap<>();
    private TextView testProviderValue;
    private EditText testText;
    private TextView testCounter;
    private LinearLayout testResultHost;
    private LinearLayout takesHost;
    private CancelToken runToken;
    private boolean running;
    private String testPid = "";
    private final List<Take> takes = new ArrayList<>();
    private String chosenTake = "";

    private static final class Take {
        int n;
        String pid;
        TtsRequest req;
        long firstMs = -1, totalMs = -1;
        String error = "";
    }

    public DubTab(Host host) {
        this.host = host;
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

    /** Rebuilds everything from the live runtime (providers, statuses, settings). */
    public void rebuild() {
        col.removeAllViews();
        cards.clear();
        SpeechApi sp = j.speech;
        if (sp == null) {
            col.addView(Widgets.empty(ctx, app.jarvis.R.drawable.ic_voice, "Голосовой модуль не подключён",
                "Провайдеры озвучки появятся после подключения речевого движка."));
            return;
        }
        col.addView(chainCard(sp), Ui.lpm(-1, -2, 0, 0, 0, Ui.dp(18)));
        for (TtsProvider p : sp.ttsProviders()) {
            ProviderCard card = new ProviderCard(p);
            cards.put(p.info().id, card);
            col.addView(card.build(), Ui.lpm(-1, -2, 0, 0, 0, Ui.dp(18)));
        }
        col.addView(Widgets.sectionTitle(ctx, "Проверка озвучки"));
        col.addView(testBox());
        renderTestIdle();
        renderTakes();
    }

    // ---------------------------------------------------------------------------------------------- chain summary
    private View chainCard(SpeechApi sp) {
        LinearLayout card = Widgets.card(ctx);
        card.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12));
        LinearLayout head = Ui.row(ctx);
        TextView t = Ui.text(ctx, "В ответах ассистента", Ui.BODY);
        t.setTypeface(Ui.medium);
        head.addView(t, Ui.lpw(1f));
        head.addView(Widgets.pill(ctx, sp.activeChain().isEmpty() ? "цепочка пуста" : "провайдеров: " + sp.activeChain().size(),
            sp.activeChain().isEmpty() ? Widgets.WARN : Widgets.OK));
        head.addView(HelpPopup.button(ctx, host, "В ответах ассистента",
            "Здесь видно, какие провайдеры озвучивают ответы и в каком порядке.",
            "Если первый провайдер недоступен, ассистент пробует следующего по списку.",
            "Включайте нужные голоса и меняйте их приоритет стрелками в карточках."), Ui.lpm(-2, -2, Ui.dp(6), 0, 0, 0));
        card.addView(head);
        List<String> chain = sp.activeChain();
        TextView line;
        if (chain.isEmpty()) {
            line = Ui.text(ctx, "Ни один провайдер не активен: включите хотя бы одного ниже или задайте ключ.",
                Ui.CAPTION, Ui.P.warn);
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < chain.size(); i++) {
                if (i > 0) sb.append(" → ");
                TtsProvider p = sp.tts(chain.get(i));
                sb.append(p == null ? chain.get(i) : p.info().name);
            }
            line = Ui.text(ctx, sb.toString(), Ui.CAPTION, Ui.P.text2);
            line.setMaxLines(2);
        }
        card.addView(line, Ui.lpm(-2, -2, 0, Ui.dp(6), 0, 0));
        card.addView(Ui.text(ctx,
            "Включение, голос и приоритет — ниже. Параметры ответа — на вкладке «Поведение».",
            Ui.CAPTION, Ui.P.text2), Ui.lpm(-1, -2, 0, Ui.dp(8), 0, 0));
        return card;
    }

    /** Buttons two per row, equal width. */
    private LinearLayout grid(View... buttons) {
        LinearLayout col = Ui.column(ctx);
        for (int i = 0; i < buttons.length; i += 2) {
            LinearLayout row = Ui.row(ctx);
            row.addView(buttons[i], Ui.lpw(1f));
            if (i + 1 < buttons.length) {
                row.addView(Ui.space(ctx, 6, 1));
                row.addView(buttons[i + 1], Ui.lpw(1f));
            }
            col.addView(row, Ui.lpm(-1, -2, 0, i == 0 ? 0 : Ui.dp(6), 0, 0));
        }
        return col;
    }

    // ---------------------------------------------------------------------------------------------- provider card
    private static String ttsSecretName(String providerId) {
        switch (providerId) {
            case "gemini-tts": case "gemini-lite-tts": return "key.gemini";
            case "elevenlabs": return "key.elevenlabs";
            case "openrouter-tts": return "key.openrouter";
            default: return "";
        }
    }

    private void editTtsApiKey(String key, String providerName) {
        Dialogs.prompt(host, "API-ключ · " + providerName,
            "Вставьте ключ провайдера", "", false, true, "Сохранить", raw -> {
                String value = raw.trim();
                if (value.isEmpty()) return;
                j.secrets.set(key, value);
                if (j.speech != null) j.speech.reconfigure();
                host.overlays().toast("Ключ сохранён", 1);
                rebuild();
            });
    }

    private final class ProviderCard {
        final TtsProvider p;
        final ProviderInfo info;
        LinearLayout statusHost, optionsHost, quotaHost;
        View checkBtn;

        ProviderCard(TtsProvider p) { this.p = p; this.info = p.info(); }

        View build() {
            LinearLayout card = Widgets.card(ctx);
            card.setPadding(Ui.dp(16), Ui.dp(16), Ui.dp(16), Ui.dp(18));

            LinearLayout head = Ui.row(ctx);
            TextView name = Ui.text(ctx, info.name, Ui.BODY);
            name.setTypeface(Ui.medium);
            head.addView(name, Ui.lpw(1f));
            head.addView(HelpPopup.button(ctx, host, info.name, ProviderGuides.tts(p)),
                Ui.lpm(-2, -2, Ui.dp(6), 0, 0, 0));
            card.addView(head);
            LinearLayout badges = Ui.row(ctx);
            badges.addView(Widgets.pill(ctx, StudioKit.officialLabel(info),
                info.official ? Widgets.MUTED : Widgets.WARN));
            card.addView(badges, Ui.lpm(-1, -2, 0, Ui.dp(8), 0, 0));

            String inChain = chainPosition();
            if (!inChain.isEmpty()) {
                TextView pos = Ui.text(ctx, inChain, Ui.CAPTION, Ui.P.accent);
                card.addView(pos, Ui.lpm(-2, -2, 0, Ui.dp(8), 0, 0));
            }
            // Full provider details are kept on demand: the default list shows
            // essentials rather than five large metadata rows on every card.
            final LinearLayout detailHost = Ui.column(ctx);
            detailHost.addView(StudioKit.metaRow(ctx, "голоса:", info.voices),
                Ui.lpm(-1, -2, 0, Ui.dp(10), 0, 0));
            detailHost.addView(StudioKit.metaRow(ctx, "доступ:",
                info.needsKey ? "нужен ключ API" : info.needsAccount ? "нужен аккаунт" : "без аккаунта и ключа"),
                Ui.lpm(-1, -2, 0, Ui.dp(6), 0, 0));
            detailHost.addView(StudioKit.metaRow(ctx, "квота:", info.freeQuota),
                Ui.lpm(-1, -2, 0, Ui.dp(6), 0, 0));
            detailHost.addView(StudioKit.metaRow(ctx, "клонирование:", info.cloning ? info.cloningNote : "нет"),
                Ui.lpm(-1, -2, 0, Ui.dp(6), 0, 0));
            detailHost.addView(StudioKit.metaRow(ctx, "получатель данных:", info.sendsDataTo),
                Ui.lpm(-1, -2, 0, Ui.dp(6), 0, 0));
            TextView lim = Ui.text(ctx, info.limitations, Ui.CAPTION, Ui.P.text2);
            lim.setSingleLine(false);
            detailHost.addView(lim, Ui.lpm(-2, -2, 0, Ui.dp(6), 0, 0));
            final boolean initiallyExpanded = Boolean.TRUE.equals(detailsExpanded.get(info.id));
            detailHost.setVisibility(initiallyExpanded ? View.VISIBLE : View.GONE);
            TextView detailToggle = Ui.text(ctx,
                initiallyExpanded ? "Скрыть характеристики" : "Характеристики и ограничения",
                Ui.CAPTION, Ui.P.accent);
            detailToggle.setPadding(0, Ui.dp(10), 0, Ui.dp(8));
            detailToggle.setClickable(true);
            detailToggle.setFocusable(true);
            detailToggle.setContentDescription("Подробнее о голосе " + info.name);
            detailToggle.setOnClickListener(v -> {
                boolean show = detailHost.getVisibility() != View.VISIBLE;
                detailsExpanded.put(info.id, show);
                detailHost.setVisibility(show ? View.VISIBLE : View.GONE);
                detailToggle.setText(show ? "Скрыть характеристики" : "Характеристики и ограничения");
            });
            card.addView(detailToggle);
            card.addView(detailHost);

            statusHost = Ui.column(ctx);
            renderStatus();
            card.addView(statusHost, Ui.lpm(-2, -2, 0, Ui.dp(8), 0, 0));
            final String credential = ttsSecretName(info.id);
            if (info.needsKey && !credential.isEmpty()) {
                String masked = Secrets.mask(j.secrets.get(credential));
                card.addView(WrapButton.create(ctx, "API: " + masked,
                    0, Ui.BTN_GLASS, () -> editTtsApiKey(credential, info.name)),
                    Ui.lpm(-1, -2, 0, Ui.dp(12), 0, 0));
            }

            checkBtn = WrapButton.create(ctx, "Проверить", R.drawable.ic_activity, Ui.BTN_GLASS, new Runnable() { @Override public void run() { runCheck(); } });
            View voicesBtn = WrapButton.create(ctx, "Выбрать голос", 0, Ui.BTN_GLASS, new Runnable() { @Override public void run() { openVoices(); } });
            // All provider cards have the same concise default layout. Less-used
            // controls are available under "Характеристики и ограничения".
            card.addView(grid(checkBtn, voicesBtn), Ui.lpm(-1, -2, 0, Ui.dp(12), 0, 0));
            View quotaBtn = WrapButton.create(ctx, "Проверить квоту", 0, Ui.BTN_GLASS, new Runnable() { @Override public void run() { runQuota(); } });
            View optBtn = WrapButton.create(ctx, "Параметры голоса", R.drawable.ic_sliders, Ui.BTN_GLASS, new Runnable() { @Override public void run() { toggleOptions(); } });
            detailHost.addView(grid(quotaBtn, optBtn), Ui.lpm(-1, -2, 0, Ui.dp(14), 0, 0));

            // Enable, disable and reorder here: the old duplicate settings list is gone.
            List<String> chain = new ArrayList<>(j.settings.getList(Settings.TTS_CHAIN));
            if (chain.isEmpty()) { chain.add("edge"); chain.add("gtts"); }
            final boolean selected = chain.contains(info.id);
            final boolean slow = p instanceof app.jarvis.core.voice.SlowProvider;
            LinearLayout control = Ui.row(ctx);
            control.addView(Ui.text(ctx, slow ? "Только студийный тест" :
                selected ? "Включён в озвучку ответов" : "Не участвует в ответах",
                Ui.CAPTION, Ui.P.text2), Ui.lpw(1f));
            if (!slow) {
                control.addView(Widgets.toggle(ctx, selected, on -> {
                    List<String> order = new ArrayList<>(j.settings.getList(Settings.TTS_CHAIN));
                    if (order.isEmpty()) { order.add("edge"); order.add("gtts"); }
                    order.remove(info.id);
                    if (on) order.add(info.id);
                    if (order.isEmpty()) {
                        host.overlays().toast("Оставьте хотя бы один голос для ответов", 1);
                        rebuild();
                        return;
                    }
                    j.settings.setList(Settings.TTS_CHAIN, order);
                    rebuild();
                }));
            }
            card.addView(control, Ui.lpm(-1, -2, 0, Ui.dp(9), 0, 0));
            if (selected && !slow) {
                LinearLayout priority = Ui.row(ctx);
                if (chain.indexOf(info.id) > 0) {
                    priority.addView(Ui.iconButton(ctx, R.drawable.ic_arrow_up, 38, Ui.P.text2,
                        "Повысить приоритет " + info.name, () -> moveChain(info.id, -1)));
                }
                if (chain.indexOf(info.id) < chain.size() - 1) {
                    priority.addView(Ui.iconButton(ctx, R.drawable.ic_arrow_down, 38, Ui.P.text2,
                        "Понизить приоритет " + info.name, () -> moveChain(info.id, 1)));
                }
                priority.addView(Ui.text(ctx, isFirst() ? "Первый в цепочке" : "Приоритет можно менять стрелками",
                    Ui.CAPTION, Ui.P.text2), Ui.lpw(1f));
                card.addView(priority, Ui.lpm(-1, -2, 0, Ui.dp(5), 0, 0));
            }

            quotaHost = Ui.column(ctx);
            card.addView(quotaHost, Ui.lpm(-2, -2, 0, Ui.dp(4), 0, 0));
            optionsHost = Ui.column(ctx);
            card.addView(optionsHost, Ui.lpm(-2, -2, 0, Ui.dp(4), 0, 0));
            return card;
        }

        String shortVoice() {
            String v = currentVoice(info.id);
            int s = v.lastIndexOf('.');
            if (s < 0) s = v.lastIndexOf('-');
            return s >= 0 && s + 1 < v.length() ? v.substring(s + 1) : v;
        }

        String chainPosition() {
            List<String> chain = j.settings.getList(Settings.TTS_CHAIN);
            int idx = chain.indexOf(info.id);
            if (idx < 0 && chain.isEmpty()) idx = "edge".equals(info.id) ? 0 : "gtts".equals(info.id) ? 1 : -1;
            if (idx < 0) return StudioKit.inactiveReason(p, false);
            String where = idx == 0 ? "основной в ответах" : "в цепочке ответов: " + (idx + 1) + "-й";
            if (!p.isConfigured()) return where + " · сейчас не активен: нет ключа";
            return where;
        }

        boolean isFirst() {
            List<String> chain = j.settings.getList(Settings.TTS_CHAIN);
            String first = chain.isEmpty() ? "edge" : chain.get(0);
            return first.equals(info.id);
        }

        void renderStatus() {
            statusHost.removeAllViews();
            ProviderStatus st = p.lastStatus();
            LinearLayout row = Ui.row(ctx);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(StudioKit.pillForStatus(ctx, st));
            String when = StudioKit.checkedAgo(ctx, st);
            if (!when.isEmpty()) row.addView(Ui.text(ctx, when, Ui.CAPTION, Ui.P.text3), Ui.lpm(-2, -2, Ui.dp(8), 0, 0, 0));
            statusHost.addView(row);
            String detail = StudioKit.statusDetail(ctx, st);
            if (!detail.isEmpty()) {
                LinearLayout second = Ui.row(ctx);
                TextView cap = Ui.text(ctx, detail, Ui.CAPTION, st.ok ? Ui.P.text2 : Ui.P.err);
                cap.setMaxLines(3);
                cap.setEllipsize(TextUtils.TruncateAt.END);
                second.addView(cap, Ui.lpw(1f));
                if (!st.ok && !st.detail.isEmpty()) {
                    second.addView(Ui.iconButton(ctx, R.drawable.ic_info, 30, Ui.P.text3, new Runnable() { @Override public void run() {
                        Dialogs.info(host, "Подробности последней ошибки", st.detail);
                    } }), Ui.lpm(-2, -2, Ui.dp(6), 0, 0, 0));
                }
                statusHost.addView(second, Ui.lpm(-1, -2, 0, Ui.dp(4), 0, 0));
            }
        }

        void setChecking(final boolean on) {
            statusHost.removeAllViews();
            if (on) {
                LinearLayout row = Ui.row(ctx);
                row.addView(Widgets.spinner(ctx, 18));
                TextView t = Ui.text(ctx, "  Проверяю: реальный короткий синтез…", Ui.CAPTION, Ui.P.text2);
                row.addView(t);
                statusHost.addView(row);
                checkBtn.setEnabled(false);
                checkBtn.setAlpha(0.5f);
            } else {
                renderStatus();
                checkBtn.setEnabled(true);
                checkBtn.setAlpha(1f);
            }
        }

        void runCheck() {
            if (!p.isConfigured()) { host.overlays().toast("Сначала задайте ключ провайдера", 2); return; }
            final CancelToken token = new CancelToken();
            setChecking(true);
            final Thread th = new Thread(new Runnable() { @Override public void run() {
                try {
                    final ProviderStatus st = p.check("Привет, это проверка озвучки.", currentVoice(info.id), token);
                    j.main.post(new Runnable() { @Override public void run() { setChecking(false); renderStatusFrom(st); } });
                } catch (Throwable t) {
                    j.main.post(new Runnable() { @Override public void run() { setChecking(false); renderStatus(); } });
                }
            } }, "jv-tts-check");
            th.setDaemon(true);
            th.start();
        }

        void renderStatusFrom(ProviderStatus st) {
            renderStatus();
            if (st != null && !st.ok && !st.lastError.isEmpty()) host.overlays().toast(st.lastError, 2);
        }

        void runQuota() {
            quotaHost.removeAllViews();
            LinearLayout row = Ui.row(ctx);
            row.addView(Widgets.spinner(ctx, 14));
            row.addView(Ui.text(ctx, "  Запрашиваю квоту…", Ui.CAPTION, Ui.P.text3));
            quotaHost.addView(row);
            final CancelToken token = new CancelToken();
            new Thread(new Runnable() { @Override public void run() {
                String qq;
                try { qq = p.quotaText(token); } catch (Throwable t) { qq = null; }
                final String q = qq;
                j.main.post(new Runnable() { @Override public void run() {
                    quotaHost.removeAllViews();
                    quotaHost.addView(StudioKit.metaRow(ctx, "квота сейчас:", q == null ? "сервис не сообщает" : q));
                } });
            } }, "jv-quota").start();
        }

        void openVoices() {
            VoicesSheet.open(host, p, currentVoice(info.id), new VoicesSheet.Listener() {
                @Override public void onPicked(String voiceId) {
                    if (voiceId.isEmpty()) j.settings.remove("tts.voice." + info.id);
                    else j.settings.set("tts.voice." + info.id, voiceId);
                    j.speech.reconfigure();
                    rebuild();
                }
            });
        }

        void toggleOptions() {
            if (optionsHost.getChildCount() > 0) { optionsHost.removeAllViews(); return; }
            optionsHost.addView(buildOptions());
        }

        View buildOptions() {
            LinearLayout box = Ui.column(ctx);
            box.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(12));
            box.setBackground(Ui.rect(Ui.P.field, 12));

            TextView speedHead = Ui.text(ctx, "Скорость теста в студии", Ui.CAPTION, Ui.P.text2);
            speedHead.setTypeface(Ui.medium);
            box.addView(speedHead);
            final float speed = perProviderSpeed(info.id);
            final TextView speedVal = Ui.text(ctx, fmtSpeed(speed), Ui.CAPTION, Ui.P.text);
            box.addView(speedVal, Ui.lpm(-2, -2, 0, Ui.dp(4), 0, 0));
            box.addView(Widgets.slider(ctx, 0.7f, 1.3f, 0.05f, speed, new Widgets.SliderListener() { @Override public void onValue(float v, boolean fromUser) {
                speedVal.setText(fmtSpeed(v));
                if (Math.abs(v - 1f) < 0.01f) j.settings.remove("tts." + info.id + ".speed");
                else j.settings.set("tts." + info.id + ".speed", String.valueOf(v));
            } }), Ui.lpm(-1, Ui.dp(32), 0, Ui.dp(2), 0, 0));

            if (info.id.equals("elevenlabs")) addElevenLabsOptions(box);
            return box;
        }

        void addElevenLabsOptions(LinearLayout box) {
            box.addView(Ui.space(ctx, 1, 10));
            LinearLayout modelRow = Ui.row(ctx);
            TextView mh = Ui.text(ctx, "Модель", Ui.CAPTION, Ui.P.text2);
            mh.setTypeface(Ui.medium);
            modelRow.addView(mh, Ui.lpw(1f));
            String model = j.settings.get("tts.elevenlabs.model", "eleven_flash_v2_5");
            TextView mv = Ui.text(ctx, model, Ui.CAPTION, Ui.P.text);
            mv.setTypeface(Ui.mono);
            mv.setSingleLine(true);
            mv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            modelRow.addView(mv, Ui.lpw(1f));
            modelRow.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickElevenModel(); } });
            box.addView(modelRow);

            sliderOption(box, "Stability (выше = монотоннее)", "tts.elevenlabs.stability", 0.5f);
            sliderOption(box, "Similarity (сходство с голосом)", "tts.elevenlabs.similarity", 0.75f);
            sliderOption(box, "Style (выразительность)", "tts.elevenlabs.style", 0f);

            LinearLayout boostRow = Ui.row(ctx);
            boostRow.addView(Ui.text(ctx, "Speaker boost", Ui.CAPTION, Ui.P.text2), Ui.lpw(1f));
            boostRow.addView(Widgets.toggle(ctx, j.settings.getBool("tts.elevenlabs.boost", true), new Widgets.Toggle.Listener() { @Override public void onChanged(boolean on) {
                j.settings.setBool("tts.elevenlabs.boost", on);
                j.speech.reconfigure();
            } }), Ui.lpm(-2, -2, Ui.dp(8), 0, 0, 0));
            box.addView(boostRow, Ui.lpm(-2, -2, 0, Ui.dp(8), 0, 0));
            box.addView(Ui.text(ctx, "Модели v3/v4 понимают теги эмоций [whispers] на английском — один тег на фразу.",
                Ui.CAPTION, Ui.P.text3), Ui.lpm(-2, -2, 0, Ui.dp(6), 0, 0));
        }

        private void sliderOption(LinearLayout box, String title, final String key, float def) {
            box.addView(Ui.space(ctx, 1, 8));
            TextView h = Ui.text(ctx, title + " — " + fmt2(j.settings.getFloat(key, def)), Ui.CAPTION, Ui.P.text2);
            box.addView(h);
            box.addView(Widgets.slider(ctx, 0f, 1f, 0.05f, j.settings.getFloat(key, def), new Widgets.SliderListener() { @Override public void onValue(float v, boolean fromUser) {
                h.setText(title + " — " + fmt2(v));
                j.settings.set(key, String.valueOf(v));
            } }), Ui.lpm(-1, Ui.dp(32), 0, 0, 0, 0));
        }

        void pickElevenModel() {
            final String[] ids = {"eleven_flash_v2_5", "eleven_multilingual_v2", "eleven_v3", "eleven_v4", "eleven_v4_turbo"};
            final String[] labels = {
                "eleven_flash_v2_5\nбыстрая, для диалогов",
                "eleven_multilingual_v2\nкачественная классика",
                "eleven_v3\nсамая выразительная, теги [..]",
                "eleven_v4\nновее, теги [..]",
                "eleven_v4_turbo\nбыстрее v4",
            };
            String cur = j.settings.get("tts.elevenlabs.model", "eleven_flash_v2_5");
            int sel = 0;
            for (int i = 0; i < ids.length; i++) if (ids[i].equals(cur)) sel = i;
            Dialogs.choose(host, "Модель ElevenLabs", labels, sel, new Dialogs.IndexListener() { @Override public void on(int index) {
                j.settings.set("tts.elevenlabs.model", ids[index]);
                j.speech.reconfigure();
                rebuild();
            } });
        }

        void makePrimary() {
            List<String> chain = new ArrayList<>(j.settings.getList(Settings.TTS_CHAIN));
            if (chain.isEmpty()) { chain.add("edge"); chain.add("gtts"); }
            chain.removeAll(java.util.Collections.singletonList(info.id));
            chain.add(0, info.id);
            j.settings.setList(Settings.TTS_CHAIN, chain);
            rebuild();
        }
    }

    private void moveChain(String id, int delta) {
        List<String> chain = new ArrayList<>(j.settings.getList(Settings.TTS_CHAIN));
        if (chain.isEmpty()) { chain.add("edge"); chain.add("gtts"); }
        int old = chain.indexOf(id), next = old + delta;
        if (old < 0 || next < 0 || next >= chain.size()) return;
        java.util.Collections.swap(chain, old, next);
        j.settings.setList(Settings.TTS_CHAIN, chain);
        rebuild();
    }

    static String fmtSpeed(float v) {
        String s = String.format(java.util.Locale.US, "%.2f×", v);
        return s.endsWith("0×") ? s.substring(0, s.length() - 3) + "×" : s.replace(".", ",");
    }

    static String fmt2(float v) {
        String s = String.format(java.util.Locale.US, "%.2f", v);
        if (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        return s.replace(".", ",");
    }

    private String currentVoice(String pid) { return j.settings.get("tts.voice." + pid); }
    private String currentVoice(TtsProvider p) { return currentVoice(p.info().id); }

    private float perProviderSpeed(String pid) {
        float v = j.settings.getFloat("tts." + pid + ".speed", 0f);
        if (v > 0) return v;
        return j.settings.getFloat(Settings.TTS_SPEED, 1f);
    }

    // ---------------------------------------------------------------------------------------------- test box
    private View testBox() {
        LinearLayout card = Widgets.card(ctx);
        card.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(14));

        LinearLayout head = Ui.row(ctx);
        TextView t = Ui.text(ctx, "Текст проверки", Ui.BODY);
        t.setTypeface(Ui.medium);
        head.addView(t, Ui.lpw(1f));
        head.addView(HelpPopup.button(ctx, host, "Текст проверки",
            "Фраза синтезируется выбранным провайдером напрямую, минуя цепочку и кэш.",
            "Варианты ниже подставляются в поле; можно ввести свой текст, до " + MAX_TEST_CHARS + " знаков.",
            "«Озвучить» — один синтез, «Стоп» — прервать, «Снять 3 дубля» — три записи с повтором."), Ui.lpm(-2, -2, Ui.dp(6), 0, 0, 0));
        card.addView(head);

        testProviderValue = Ui.text(ctx, "", Ui.CAPTION, Ui.P.accent);
        testProviderValue.setClickable(true);
        testProviderValue.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pickTestProvider(); } });
        card.addView(testProviderValue, Ui.lpm(-2, -2, 0, Ui.dp(4), 0, 0));

        LinearLayout presets = Ui.column(ctx);
        for (final String preset : PRESETS) {
            TextView chip = Ui.text(ctx, preset, Ui.CAPTION, Ui.P.text);
            chip.setPadding(Ui.dp(12), Ui.dp(9), Ui.dp(12), Ui.dp(9));
            chip.setBackground(Ui.glassPressable(12));
            chip.setClickable(true);
            chip.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { testText.setText(preset); } });
            presets.addView(chip, Ui.lpm(-1, -2, 0, Ui.dp(6), 0, 0));
        }
        card.addView(presets, Ui.lpm(-1, -2, 0, Ui.dp(8), 0, 0));

        testText = Widgets.field(ctx, "Свой текст…", PRESETS[0], true, false);
        testText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_TEST_CHARS)});
        card.addView(Widgets.fieldBox(ctx, testText), Ui.lpm(-1, -2, 0, Ui.dp(8), 0, 0));
        testCounter = Ui.text(ctx, "", Ui.CAPTION, Ui.P.text3);
        testCounter.setGravity(Gravity.END);
        testText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                testCounter.setText(s.length() + " / " + MAX_TEST_CHARS);
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        testCounter.setText(testText.getText().length() + " / " + MAX_TEST_CHARS);
        card.addView(testCounter, Ui.lpm(-1, -2, 0, Ui.dp(4), 0, 0));

        View speak = WrapButton.create(ctx, "Озвучить", R.drawable.ic_volume, Ui.BTN_PRIMARY, new Runnable() { @Override public void run() { runTest(); } });
        View stop = WrapButton.create(ctx, "Стоп", R.drawable.ic_stop, Ui.BTN_DANGER, new Runnable() { @Override public void run() { stopRun(); } });
        View takes = WrapButton.create(ctx, "Снять 3 дубля", R.drawable.ic_layers, Ui.BTN_GLASS, new Runnable() { @Override public void run() { runTakes(); } });
        card.addView(grid(speak, stop, takes), Ui.lpm(-1, -2, 0, Ui.dp(10), 0, 0));

        testResultHost = Ui.column(ctx);
        card.addView(testResultHost);
        card.addView(Ui.space(ctx, 1, 6));
        takesHost = Ui.column(ctx);
        card.addView(takesHost);
        return card;
    }

    private String testProviderId() {
        if (!testPid.isEmpty()) return testPid;
        List<String> chain = j.speech == null ? new ArrayList<String>() : j.speech.activeChain();
        return chain.isEmpty() ? "" : chain.get(0);
    }

    private void refreshTestProvider() {
        String pid = testProviderId();
        TtsProvider p = pid.isEmpty() || j.speech == null ? null : j.speech.tts(pid);
        testProviderValue.setText(p == null ? "провайдер не выбран" : p.info().name + " ▾");
    }

    private void pickTestProvider() {
        if (j.speech == null) return;
        List<TtsProvider> all = j.speech.ttsProviders();
        String cur = testProviderId();
        String[] items = new String[all.size()];
        int sel = 0;
        for (int i = 0; i < all.size(); i++) {
            TtsProvider p = all.get(i);
            items[i] = p.info().name + (p.isConfigured() ? "" : "\nнет ключа — тест не пройдёт");
            if (p.info().id.equals(cur)) sel = i;
        }
        Dialogs.choose(host, "Провайдер теста", items, sel, new Dialogs.IndexListener() { @Override public void on(int index) {
            List<TtsProvider> all = j.speech.ttsProviders();
            if (index >= 0 && index < all.size()) { testPid = all.get(index).info().id; refreshTestProvider(); }
        } });
    }

    private void renderTestIdle() {
        refreshTestProvider();
        testResultHost.removeAllViews();
        testResultHost.addView(Ui.text(ctx, "«Озвучить» синтезирует текст одним провайдером, без цепочки и кэша: честные задержки.",
            Ui.CAPTION, Ui.P.text3), Ui.lpm(-2, -2, 0, Ui.dp(2), 0, 0));
    }

    private TtsRequest buildRequest(String pid, String rawText) {
        TtsRequest req = new TtsRequest();
        req.text = rawText;
        req.voiceId = currentVoice(pid);
        req.language = j.settings.get(Settings.STT_LANGUAGE, "ru");
        req.speed = perProviderSpeed(pid);
        if (pid.equals("elevenlabs")) {
            req.options.put("model", j.settings.get("tts.elevenlabs.model", "eleven_flash_v2_5"));
            req.options.put("stability", j.settings.get("tts.elevenlabs.stability", "0.5"));
            req.options.put("similarity", j.settings.get("tts.elevenlabs.similarity", "0.75"));
            req.options.put("style", j.settings.get("tts.elevenlabs.style", "0"));
            req.options.put("speaker_boost", j.settings.getBool("tts.elevenlabs.boost", true) ? "true" : "false");
        }
        return req;
    }

    /** Real synthesis through exactly one provider with live latency readouts. */
    public void runTest() {
        startRun(1);
    }

    /** Three sequential real syntheses of the same phrase; each take is replayable. */
    public void runTakes() {
        String pid = testProviderId();
        if (pid.equals("elevenlabs")) {
            Dialogs.confirm(host, "Потратятся символы ElevenLabs",
                "Три дубля — это три реальные синтеза (~3 × длина фразы символов из 10 000 в месяц). Продолжить?",
                "Снять 3 дубля", false, new Runnable() { @Override public void run() { takes.clear(); chosenTake = ""; startRun(3); } });
            return;
        }
        takes.clear();
        chosenTake = "";
        startRun(3);
    }

    private void startRun(final int total) {
        if (running) return;
        if (j.speech == null) return;
        final String pid = testProviderId();
        if (pid.isEmpty()) { host.overlays().toast("Выберите провайдера теста", 2); return; }
        TtsProvider p = j.speech.tts(pid);
        if (p != null && !p.isConfigured()) { host.overlays().toast("Провайдер не настроен: задайте ключ", 2); return; }
        String text = testText.getText().toString().trim();
        if (text.isEmpty()) { host.overlays().toast("Введите текст", 2); return; }
        final TtsRequest req = buildRequest(pid, text);
        running = true;
        renderRunState("Синтез…", total);
        final SpeechApi.Test cb = new SpeechApi.Test() {
            @Override public void onStarted() { }
            @Override public void onFirstAudio(long ms) {
                renderRunState("Говорю · до первого звука " + StudioKit.ms(ms), total);
                if (total > 1 && !takes.isEmpty()) takes.get(takes.size() - 1).firstMs = ms;
            }
            @Override public void onDone(long totalMs) { finishRun(true, totalMs, total); }
            @Override public void onError(String userMessage, String technical) { failRun(userMessage, technical, total); }
        };
        if (total > 1) {
            Take tk = new Take();
            tk.n = takes.size() + 1;
            tk.pid = pid;
            tk.req = req;
            takes.add(tk);
            renderTakes();
        }
        runToken = j.speech.speakWith(pid, req, cb);
    }

    private void finishRun(boolean ok, long totalMs, int total) {
        running = false;
        runToken = null;
        if (total > 1 && !takes.isEmpty()) {
            Take tk = takes.get(takes.size() - 1);
            tk.totalMs = totalMs;
            if (tk.n < total) {
                startRun(total);   // next take
                return;
            }
            renderTakes();
        }
        renderRunDone("Готово · всего " + StudioKit.ms(totalMs));
    }

    private void failRun(String userMessage, String technical, int total) {
        running = false;
        runToken = null;
        if (total > 1 && !takes.isEmpty()) {
            Take tk = takes.get(takes.size() - 1);
            tk.error = userMessage;
            renderTakes();
        }
        if (userMessage != null && userMessage.contains("остановлен")) {
            renderRunIdleAfterStop();
            return;
        }
        renderRunFail(userMessage, technical);
    }

    public void stopRun() {
        if (runToken != null) runToken.cancel();
        running = false;
        renderRunIdleAfterStop();
    }

    private void renderRunState(String s, int total) {
        testResultHost.removeAllViews();
        LinearLayout row = Ui.row(ctx);
        row.addView(Widgets.spinner(ctx, 16));
        row.addView(Ui.text(ctx, "  " + (total > 1 ? "Дубль " + (takes.size()) + " из " + total + " · " : "") + s, Ui.CAPTION, Ui.P.text2));
        testResultHost.addView(row, Ui.lpm(-2, -2, 0, Ui.dp(4), 0, 0));
    }

    private void renderRunDone(String s) {
        testResultHost.removeAllViews();
        LinearLayout row = Ui.row(ctx);
        row.addView(Widgets.pill(ctx, "готово", Widgets.OK));
        row.addView(Ui.text(ctx, "  " + s, Ui.CAPTION, Ui.P.text2));
        testResultHost.addView(row, Ui.lpm(-2, -2, 0, Ui.dp(4), 0, 0));
    }

    private void renderRunFail(String userMessage, final String technical) {
        testResultHost.removeAllViews();
        LinearLayout row = Ui.row(ctx);
        row.addView(Widgets.pill(ctx, "ошибка", Widgets.ERR));
        TextView t = Ui.text(ctx, "  " + userMessage, Ui.CAPTION, Ui.P.err);
        t.setMaxLines(2);
        row.addView(t, Ui.lpw(1f));
        if (technical != null && !technical.isEmpty()) {
            row.addView(Ui.iconButton(ctx, R.drawable.ic_info, 34, Ui.P.text3, new Runnable() { @Override public void run() {
                Dialogs.info(host, "Подробности", technical);
            } }));
        }
        testResultHost.addView(row, Ui.lpm(-2, -2, 0, Ui.dp(4), 0, 0));
    }

    private void renderRunIdleAfterStop() {
        testResultHost.removeAllViews();
        LinearLayout row = Ui.row(ctx);
        row.addView(Widgets.pill(ctx, "остановлено", Widgets.MUTED));
        testResultHost.addView(row, Ui.lpm(-2, -2, 0, Ui.dp(4), 0, 0));
    }

    private void renderTakes() {
        takesHost.removeAllViews();
        if (takes.isEmpty()) return;
        takesHost.addView(Ui.text(ctx, "Дубли (повтор синтезирует заново: у ElevenLabs тратятся символы)", Ui.CAPTION, Ui.P.text3),
            Ui.lpm(-2, -2, 0, Ui.dp(4), 0, 0));
        for (final Take tk : takes) {
            LinearLayout row = Ui.row(ctx);
            row.setPadding(0, Ui.dp(6), 0, 0);
            if (chosenTake.equals(takeKey(tk))) row.setBackground(Ui.rect(Ui.P.accentSoft, 10));
            row.setPadding(Ui.dp(8), Ui.dp(6), Ui.dp(8), Ui.dp(6));
            TextView num = Ui.text(ctx, "Дубль " + tk.n, Ui.BODY);
            num.setTypeface(Ui.medium);
            row.addView(num);
            LinearLayout mid = Ui.column(ctx);
            String line = tk.error.isEmpty()
                ? "до звука " + (tk.firstMs > 0 ? StudioKit.ms(tk.firstMs) : "—") + " · всего " + (tk.totalMs > 0 ? StudioKit.ms(tk.totalMs) : "…")
                : tk.error;
            TextView sub = Ui.text(ctx, line, Ui.CAPTION, tk.error.isEmpty() ? Ui.P.text3 : Ui.P.err);
            sub.setMaxLines(2);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            mid.addView(sub, Ui.lpm(-2, -2, Ui.dp(10), 0, 0, 0));
            row.addView(mid, Ui.lpw(1f));
            row.addView(Ui.iconButton(ctx, R.drawable.ic_play, 38, Ui.P.accent, new Runnable() { @Override public void run() { replayTake(tk); } }));
            row.addView(Ui.iconButton(ctx, R.drawable.ic_check, 38, chosenTake.equals(takeKey(tk)) ? Ui.P.accent : Ui.P.text3,
                new Runnable() { @Override public void run() { chosenTake = chosenTake.equals(takeKey(tk)) ? "" : takeKey(tk); renderTakes(); } }));
            takesHost.addView(row);
        }
    }

    private String takeKey(Take tk) { return "take" + tk.n; }

    private void replayTake(final Take tk) {
        if (running) return;
        if (tk.pid.equals("elevenlabs")) {
            Dialogs.confirm(host, "Повтор тратит символы", "Дубль " + tk.n + " будет озвучен заново через ElevenLabs. Продолжить?", "Озвучить", false,
                new Runnable() { @Override public void run() { doReplay(tk); } });
            return;
        }
        doReplay(tk);
    }

    private void doReplay(Take tk) {
        if (j.speech == null) return;
        running = true;
        renderRunState("Повтор дубля " + tk.n + "…", 1);
        runToken = j.speech.speakWith(tk.pid, tk.req, new SpeechApi.Test() {
            @Override public void onStarted() { }
            @Override public void onFirstAudio(long ms) { renderRunState("Говорю…", 1); }
            @Override public void onDone(long totalMs) { renderRunDone("Повтор готов · всего " + StudioKit.ms(totalMs)); running = false; }
            @Override public void onError(String userMessage, String technical) { renderRunFail(userMessage, technical); running = false; }
        });
    }
}
