package app.jarvis.ui.voiceui;

import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.core.voice.ProviderInfo;
import app.jarvis.core.voice.TtsProvider;
import app.jarvis.data.Settings;
import app.jarvis.rt.SpeechApi;
import app.jarvis.ui.Host;
import app.jarvis.ui.Ui;
import app.jarvis.ui.WrapButton;
import app.jarvis.ui.Widgets;
import app.jarvis.ui.settings.SettingsPage;
import java.util.ArrayList;
import java.util.List;

/**
 * «Голос и озвучка»: когда ассистент говорит (tts.auto), порядок TTS-провайдеров в цепочке (tts.chain),
 * ключи ElevenLabs / OpenRouter / Groq (j.secrets, затем j.speech.reconfigure()), скорость по умолчанию,
 * переход в Студию голоса.
 */
public class VoiceSettingsPage extends SettingsPage {
    private static final String[] AUTO_VALUES = {"never", "voice", "always"};
    private static final String[] AUTO_LABELS = {"Никогда", "На голосовые", "Всегда"};
    private static final String[][] KEYS = {
        {"elevenlabs", "Ключ ElevenLabs", "квота 10 000 символов в месяц на free; один ключ на озвучку и распознавание Scribe"},
        {"openrouter", "Ключ OpenRouter", "тот же ключ, что и для языковой модели; есть бесплатные TTS/STT и бесплатное клонирование"},
        {"groq", "Ключ Groq", "тот же ключ, что и для LLM; используется Whisper-распознаванием"},
    };

    private LinearLayout content;
    private LinearLayout chainHost;

    public VoiceSettingsPage(Host host) { super(host); }

    @Override public String title() { return "Голос и озвучка"; }

    @Override protected View build() {
        ScrollView sv = Ui.scroll(ctx);
        sv.setFillViewport(true);
        content = Ui.column(ctx);
        content.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(24));
        rebuild();
        sv.addView(content);
        return sv;
    }

    private void rebuild() {
        content.removeAllViews();
        content.addView(Widgets.sectionTitle(ctx, "Озвучка ответов"));
        content.addView(autoCard());
        // The provider chain is managed in the Studio's «Озвучка» tab, not duplicated here.
        content.addView(Widgets.sectionTitle(ctx, "Ключи сервисов речи"));
        content.addView(keysCard());
        content.addView(Widgets.sectionTitle(ctx, "Скорость речи"));
        content.addView(speedCard());
        content.addView(Widgets.sectionTitle(ctx, "Приглушение музыки"));
        content.addView(duckCard());
        content.addView(Widgets.sectionTitle(ctx, "Комментарии агента"));
        content.addView(commentsCard());
        content.addView(Widgets.sectionTitle(ctx, "Перед действием"));
        content.addView(preActionCard());
        content.addView(VoiceUiKit.note(ctx, "Провайдеры речи выбираются на вкладках «Озвучка» и «Распознавание». Здесь настраивается поведение ассистента."));
    }

    // -------------------------------------------------------------------------------------------- auto mode
    private View autoCard() {
        LinearLayout card = Widgets.card(ctx);
        card.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(14));
        String cur = j.settings.get(Settings.TTS_AUTO, "voice");
        int sel = 1;
        for (int i = 0; i < AUTO_VALUES.length; i++) if (AUTO_VALUES[i].equals(cur)) sel = i;
        card.addView(Widgets.segmented(ctx, AUTO_LABELS, sel, new Widgets.IntListener() { @Override public void on(int index) {
            j.settings.set(Settings.TTS_AUTO, AUTO_VALUES[index]);
        } }));
        card.addView(VoiceUiKit.caption(ctx, "Никогда — молчать. На голосовые — отвечать голосом только на речь. Всегда — озвучивать каждый ответ."),
            Ui.lpm(-2, -2, 0, 8, 0, 0));
        return card;
    }

    // -------------------------------------------------------------------------------------------- chain
    private void renderChain() {
        chainHost.removeAllViews();
        SpeechApi sp = j.speech;
        List<TtsProvider> providers = sp == null ? new ArrayList<TtsProvider>() : sp.ttsProviders();
        List<String> chain = j.settings.getList(Settings.TTS_CHAIN);
        if (chain.isEmpty() && !providers.isEmpty()) { chain.add("edge"); chain.add("gtts"); }

        LinearLayout card = Widgets.card(ctx);
        card.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(14));
        card.addView(VoiceUiKit.caption(ctx, "Порядок = приоритет: если первый не сработал, пробуется следующий (только по сетевым сбоям, не по отмене)."),
            Ui.lpm(-2, -2, 0, 0, 0, 8));

        // chain members first (in order), then the rest
        List<TtsProvider> ordered = new ArrayList<>();
        for (String id : chain) for (TtsProvider p : providers) if (p.info().id.equals(id)) { ordered.add(p); break; }
        for (TtsProvider p : providers) if (!ordered.contains(p)) ordered.add(p);

        int rowI = 0;
        for (final TtsProvider p : ordered) {
            final ProviderInfo info = p.info();
            final boolean inChain = chain.contains(info.id);
            final int pos = chain.indexOf(info.id);
            String sub;
            if (inChain) {
                sub = pos == 0 ? "первый в ответах" : (pos + 1) + "-й в цепочке";
                if (!p.isConfigured()) sub += " · не активен: " + (info.needsKey ? "нет ключа ниже" : "не настроен");
            } else {
                sub = info.cloning ? info.cloningNote : "выключен";
                if (p instanceof app.jarvis.core.voice.SlowProvider) sub = "студийный: минуты на фразу, в ответы не включается";
            }

            LinearLayout entry = Ui.column(ctx);
            entry.setPadding(0, Ui.dp(10), 0, Ui.dp(10));
            LinearLayout top = Ui.row(ctx);
            TextView name = Ui.text(ctx, info.name, Ui.BODY);
            name.setTypeface(Ui.medium);
            name.setMaxLines(2);
            name.setSingleLine(false);
            top.addView(name, Ui.lpw(1f));
            final boolean slow = p instanceof app.jarvis.core.voice.SlowProvider;
            if (!slow) top.addView(Widgets.toggle(ctx, inChain, new Widgets.Toggle.Listener() {
                @Override public void onChanged(boolean on) { toggle(info.id, on); }
            }), Ui.lpm(-2, -2, 8, 0, 0, 0));
            else top.addView(Widgets.pill(ctx, "студия", Widgets.MUTED), Ui.lpm(-2, -2, 8, 0, 0, 0));
            entry.addView(top);
            TextView subtitle = VoiceUiKit.note(ctx, sub);
            subtitle.setMaxLines(3);
            entry.addView(subtitle, Ui.lpm(-1, -2, 0, 4, 0, 0));
            LinearLayout actions = Ui.row(ctx);
            if (!info.official) actions.addView(Widgets.pill(ctx, "неофиц.", Widgets.WARN));
            actions.addView(Ui.space(ctx, 1, 1), Ui.lpw(1f));
            if (!slow && inChain && pos > 0) actions.addView(Ui.iconButton(ctx, R.drawable.ic_arrow_up, 36, Ui.P.text2,
                new Runnable() { @Override public void run() { move(info.id, -1); } }));
            if (!slow && inChain && pos < chain.size() - 1) actions.addView(Ui.iconButton(ctx, R.drawable.ic_arrow_down, 36, Ui.P.text2,
                new Runnable() { @Override public void run() { move(info.id, 1); } }));
            if (!info.official || (!slow && inChain && (pos > 0 || pos < chain.size() - 1))) entry.addView(actions, Ui.lpm(-1, -2, 0, 4, 0, 0));
            if (rowI > 0) Widgets.divide(card);
            card.addView(entry);
            rowI++;
        }
        chainHost.addView(card);
    }

    private void toggle(String id, boolean on) {
        List<String> chain = j.settings.getList(Settings.TTS_CHAIN);
        chain.remove(id);
        if (on) chain.add(id);
        j.settings.setList(Settings.TTS_CHAIN, chain);
        if (j.speech != null) j.speech.reconfigure();
        renderChain();
    }

    private void move(String id, int dir) {
        List<String> chain = j.settings.getList(Settings.TTS_CHAIN);
        int i = chain.indexOf(id);
        int n = i + dir;
        if (i < 0 || n < 0 || n >= chain.size()) return;
        String tmp = chain.set(n, chain.get(i));
        chain.set(i, tmp);
        j.settings.setList(Settings.TTS_CHAIN, chain);
        renderChain();
    }

    // -------------------------------------------------------------------------------------------- keys
    private View keysCard() {
        LinearLayout card = Widgets.card(ctx);
        for (int i = 0; i < KEYS.length; i++) {
            final String pid = KEYS[i][0];
            if (i > 0) Widgets.divide(card);
            card.addView(keyBlock(pid, KEYS[i][1], KEYS[i][2]));
        }
        return card;
    }

    private View keyBlock(final String pid, String title, String note) {
        LinearLayout block = Ui.column(ctx);
        block.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12));
        LinearLayout head = Ui.row(ctx);
        TextView t = Ui.text(ctx, title, Ui.BODY);
        t.setTypeface(Ui.medium);
        head.addView(t, Ui.lpw(1f));
        TextView mask = Ui.text(ctx, j.secrets.has("key." + pid) ? "задан · " + app.jarvis.data.Secrets.mask(j.secrets.get("key." + pid)) : "не задан",
            Ui.CAPTION, j.secrets.has("key." + pid) ? Ui.P.ok : Ui.P.warn);
        mask.setSingleLine(true);
        head.addView(mask, Ui.lpm(-2, -2, 8, 0, 0, 0));
        block.addView(head);
        block.addView(VoiceUiKit.note(ctx, note), Ui.lpm(-2, -2, 0, 2, 0, 0));

        final EditText field = Widgets.field(ctx, "Вставить новый ключ…", "", false, true);
        LinearLayout fieldRow = Ui.row(ctx);
        fieldRow.setBackground(Ui.glass(14));
        fieldRow.addView(field, Ui.lpw(1f));
        final EditText fref = field;
        fieldRow.addView(Ui.iconButton(ctx, R.drawable.ic_eye, 40, Ui.P.text2, new Runnable() { @Override public void run() {
            boolean hide = (fref.getInputType() & InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0;
            int sel = fref.getSelectionEnd();
            fref.setInputType(InputType.TYPE_CLASS_TEXT | (hide ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            fref.setTypeface(Ui.regular);
            if (sel >= 0 && sel <= fref.getText().length()) fref.setSelection(sel);
        } }));
        block.addView(fieldRow, Ui.lpm(-1, -2, 0, 8, 0, 0));

        LinearLayout btns = VoiceUiKit.buttonRow(ctx,
            WrapButton.create(ctx, "Сохранить", R.drawable.ic_key, Ui.BTN_PRIMARY, new Runnable() { @Override public void run() {
                String v = fref.getText().toString().trim();
                if (v.isEmpty()) { host.overlays().toast("Введите ключ", 2); return; }
                j.secrets.set("key." + pid, v);
                fref.setText("");
                mask.setText("задан · " + app.jarvis.data.Secrets.mask(v));
                mask.setTextColor(Ui.P.ok);
                if (j.speech != null) j.speech.reconfigure();
                host.overlays().toast("Ключ сохранён, провайдеры перенастроены", 1);
            } }),
            WrapButton.create(ctx, "Удалить", R.drawable.ic_trash, Ui.BTN_DANGER, new Runnable() { @Override public void run() {
                if (!j.secrets.has("key." + pid)) return;
                j.secrets.remove("key." + pid);
                mask.setText("не задан");
                mask.setTextColor(Ui.P.warn);
                if (j.speech != null) j.speech.reconfigure();
                host.overlays().toast("Ключ удалён", 0);
            } }));
        block.addView(btns, Ui.lpm(-1, -2, 0, 8, 0, 0));
        return block;
    }

    // -------------------------------------------------------------------------------------------- speed
    private View speedCard() {
        LinearLayout card = Widgets.card(ctx);
        card.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(14));
        final float cur = j.settings.getFloat(Settings.TTS_SPEED, 1f);
        final TextView val = VoiceUiKit.caption(ctx, (cur == 1f ? "Обычная (1×)" : String.format(java.util.Locale.US, "%.2f×", cur).replace(".", ",")));
        val.setTypeface(Ui.medium);
        card.addView(val, Ui.lpm(-2, -2, 0, 0, 0, 4));
        card.addView(Widgets.slider(ctx, 0.7f, 1.3f, 0.05f, cur, new Widgets.SliderListener() { @Override public void onValue(float v, boolean fromUser) {
            val.setText(v == 1f ? "Обычная (1×)" : String.format(java.util.Locale.US, "%.2f×", v).replace(".", ","));
            if (v == 1f) j.settings.remove(Settings.TTS_SPEED);
            else j.settings.set(Settings.TTS_SPEED, String.valueOf(v));
        } }), Ui.lpm(-1, 32, 0, 0, 0, 0));
        card.addView(VoiceUiKit.note(ctx, "Скорость ответов ассистента. Отдельная скорость для тестов — на карточке провайдера в студии."),
            Ui.lpm(-2, -2, 0, 6, 0, 0));
        return card;
    }


    private View duckCard() {
        LinearLayout card = Widgets.card(ctx);
        card.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(14));
        LinearLayout header = Ui.row(ctx);
        header.addView(Ui.text(ctx, "Тише, пока Джарвис говорит", Ui.BODY), Ui.lpw(1f));
        header.addView(Widgets.toggle(ctx, j.settings.getBool("tts.duck", true), new Widgets.Toggle.Listener() {
            @Override public void onChanged(boolean on) {
                j.settings.setBool("tts.duck", on);
                if (!on) j.duckMusic("speech", false);
            }
        }));
        card.addView(header);
        final int percent = Math.max(0, Math.min(100, j.settings.getInt("tts.duckPercent", 10)));
        final TextView label = VoiceUiKit.caption(ctx, "Громкость музыки во время ответа: " + percent + " %");
        card.addView(label, Ui.lpm(-1, -2, 0, 12, 0, 0));
        card.addView(Widgets.slider(ctx, 0f, 100f, 1f, percent, new Widgets.SliderListener() {
            @Override public void onValue(float value, boolean fromUser) {
                int n = Math.max(0, Math.min(100, Math.round(value)));
                label.setText("Громкость музыки во время ответа: " + n + " %");
                if (fromUser) j.settings.set("tts.duckPercent", String.valueOf(n));
            }
        }), Ui.lpm(-1, 32, 0, 6, 0, 0));
        card.addView(VoiceUiKit.note(ctx,
            "Плавное снижение и возврат за 200 мс. Пауза музыки не затрагивается; 10 % — исходное значение."),
            Ui.lpm(-1, -2, 0, 8, 0, 0));
        return card;
    }


    private View commentsCard() {
        LinearLayout card = Widgets.card(ctx);
        card.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(14));
        int n = Math.max(0, Math.min(20, j.settings.getInt("agent.commentEvery", 0)));
        final TextView frequency = VoiceUiKit.caption(ctx, n == 0 ? "Комментарии выключены" : "Комментировать каждые " + n + " действий");
        card.addView(frequency);
        card.addView(Widgets.slider(ctx, 0f, 20f, 1f, n, new Widgets.SliderListener() {
            @Override public void onValue(float value, boolean fromUser) {
                int step = Math.max(0, Math.min(20, Math.round(value)));
                frequency.setText(step == 0 ? "Комментарии выключены" : "Комментировать каждые " + step + " действий");
                if (fromUser) j.settings.set("agent.commentEvery", String.valueOf(step));
            }
        }), Ui.lpm(-1, 32, 0, 6, 0, 0));
        LinearLayout line = Ui.row(ctx);
        line.addView(Ui.text(ctx, "Озвучивать комментарии", Ui.BODY), Ui.lpw(1f));
        line.addView(Widgets.toggle(ctx, j.settings.getBool("agent.commentVoice", false), new Widgets.Toggle.Listener() {
            @Override public void onChanged(boolean enabled) { j.settings.setBool("agent.commentVoice", enabled); }
        }));
        card.addView(line, Ui.lpm(-1, -2, 0, 8, 0, 0));
        card.addView(VoiceUiKit.note(ctx,
            "Короткие сообщения появляются в ленте во время длинной цепочки инструментов. Финальный ответ остаётся отдельным."),
            Ui.lpm(-1, -2, 0, 8, 0, 0));
        return card;
    }

    private View preActionCard() {
        LinearLayout card = Widgets.card(ctx);
        card.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(14));
        LinearLayout enabled = Ui.row(ctx);
        enabled.addView(Ui.text(ctx, "Реплика перед первым действием", Ui.BODY), Ui.lpw(1f));
        enabled.addView(Widgets.toggle(ctx, j.settings.getBool("agent.preAction", true), new Widgets.Toggle.Listener() {
            @Override public void onChanged(boolean value) { j.settings.setBool("agent.preAction", value); }
        }));
        card.addView(enabled);
        LinearLayout voice = Ui.row(ctx);
        voice.addView(Ui.text(ctx, "Произносить реплику", Ui.BODY), Ui.lpw(1f));
        voice.addView(Widgets.toggle(ctx, j.settings.getBool("agent.preActionVoice", true), new Widgets.Toggle.Listener() {
            @Override public void onChanged(boolean value) { j.settings.setBool("agent.preActionVoice", value); }
        }));
        card.addView(voice, Ui.lpm(-1, -2, 0, 8, 0, 0));
        card.addView(VoiceUiKit.note(ctx,
            "Если модель сразу вызывает инструмент, ассистент скажет, что собирается сделать. Уже прозвучавшее подтверждение не повторяется."),
            Ui.lpm(-1, -2, 0, 8, 0, 0));
        final EditText phrases = Widgets.field(ctx, "Свои фразы через | (необязательно)",
            j.settings.get("agent.preActionPhrases", ""), true, false);
        card.addView(Widgets.fieldBox(ctx, phrases), Ui.lpm(-1, -2, 0, 10, 0, 0));
        card.addView(Ui.button(ctx, "Сохранить фразы", 0, Ui.BTN_GLASS, new Runnable() {
            @Override public void run() {
                j.settings.set("agent.preActionPhrases", phrases.getText().toString().trim());
                host.overlays().toast("Фразы сохранены", 1);
            }
        }), Ui.lpm(-1, -2, 0, 8, 0, 0));
        return card;
    }

    @Override public void onShow() {
        if (chainHost != null) renderChain();
    }
}
