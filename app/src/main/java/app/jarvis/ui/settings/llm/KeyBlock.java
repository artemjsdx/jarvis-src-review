package app.jarvis.ui.settings.llm;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.core.llm.KeyCheck;
import app.jarvis.core.llm.KeyDisplay;
import app.jarvis.core.llm.LlmConfig;
import app.jarvis.core.llm.LlmException;
import app.jarvis.core.llm.ProviderPreset;
import app.jarvis.core.util.CancelToken;
import app.jarvis.rt.Jarvis;
import app.jarvis.ui.Dialogs;
import app.jarvis.ui.HelpPopup;
import app.jarvis.ui.Host;
import app.jarvis.ui.OverlayHost;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import app.jarvis.ui.motion.Motion;
import java.util.TimeZone;

/**
 * The API-key block of one provider: the key field (with a show/hide eye and paste), where the key is stored, its status,
 * Save and Delete, and "Проверить ключ" (one cheap authenticated request, see {@link KeyCheck}).
 */
public final class KeyBlock {
    private static final int BUTTON_H_DP = 46;
    private static final int GAP_DP = 12;
    private static final String[] HELP = {
        "Ключ нужен, чтобы Джарвис мог обращаться к провайдеру от вашего имени. Без ключа модель не ответит.",
        "Ключ хранится только на этом телефоне, в файле приложения. Он не попадает в журнал, настройки и отчёты.",
        "Ключ шифруется AES-256 ключом из Android Keystore; сам ключ шифрования нельзя выгрузить из телефона. Если Keystore недоступен, строка «Хранится…» скажет об этом.",
        "«Проверить ключ» делает один запрос (список моделей или данные ключа). Токены модели не тратятся, результат пишется в журнал.",
    };

    private final Host host;
    private final Context c;
    private final Jarvis j;
    private final ProviderPreset p;
    private final Runnable onChanged;
    private final LinearLayout root;
    private EditText field;
    private FrameLayout eyeButton;
    private ImageView eyeHidden, eyeShown;
    private TextView storage, status;
    private View saveButton;
    private LinearLayout checkHost;
    private boolean shown, checking;

    /** @param onChanged runs after the key was saved or deleted (the page refreshes the provider row). */
    public KeyBlock(Host host, ProviderPreset p, Runnable onChanged) {
        this.host = host;
        this.c = host.context();
        this.j = host.jarvis();
        this.p = p;
        this.onChanged = onChanged;
        this.root = Ui.column(c);
        root.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12));
        build();
        refresh();
        renderIdle();
    }

    public View view() { return root; }

    /** Writes the key and the time of saving; the key itself is never written anywhere else. */
    public static void store(Jarvis j, String providerId, String key) {
        j.secrets.set("key." + providerId, key);
        j.settings.set(KeyDisplay.savedAtKey(providerId), String.valueOf(System.currentTimeMillis()));
    }

    public static void forget(Jarvis j, String providerId) {
        j.secrets.remove("key." + providerId);
        j.settings.remove(KeyDisplay.savedAtKey(providerId));
    }

    private void build() {
        LinearLayout head = Ui.row(c);
        TextView title = Ui.text(c, "API-ключ", Ui.BODY);
        title.setTypeface(Ui.medium);
        head.addView(title, Ui.lpw(1f));
        head.addView(HelpPopup.button(c, host, "API-ключ", HELP));
        root.addView(head);

        field = Widgets.field(c, "Новый ключ…", "", false, true);
        field.setHintTextColor(Ui.P.text3);
        LinearLayout fieldRow = Ui.row(c);
        fieldRow.setBackground(Ui.glass(14));
        fieldRow.addView(field, Ui.lpw(1f));
        fieldRow.addView(eyeButton(), new LinearLayout.LayoutParams(Ui.dp(40), Ui.dp(40)));
        fieldRow.addView(Ui.iconButton(c, R.drawable.ic_paperclip, 40, Ui.P.text2, new Runnable() { @Override public void run() {
            String v = clipboard();
            if (v.isEmpty()) host.overlays().toast("Буфер обмена пуст", OverlayHost.INFO);
            else { field.setText(v.trim()); field.setSelection(field.getText().length()); }
        } }));
        root.addView(fieldRow, Ui.lpm(-1, -2, 0, 8, 0, 0));

        storage = Ui.text(c, "", Ui.CAPTION, Ui.P.text3);
        root.addView(storage, Ui.lpm(-1, -2, 0, 8, 0, 0));
        status = Ui.text(c, "", Ui.CAPTION, Ui.P.text2);
        root.addView(status, Ui.lpm(-1, -2, 0, 3, 0, 0));

        LinearLayout btns = Ui.row(c);
        saveButton = Ui.button(c, "Сохранить", R.drawable.ic_key, Ui.BTN_PRIMARY, new Runnable() { @Override public void run() { save(); } });
        View deleteButton = Ui.button(c, "Удалить", R.drawable.ic_trash, Ui.BTN_DANGER, new Runnable() { @Override public void run() { delete(); } });
        btns.addView(saveButton, new LinearLayout.LayoutParams(0, Ui.dp(BUTTON_H_DP), 1f));
        btns.addView(new Space(c), new LinearLayout.LayoutParams(Ui.dp(GAP_DP), Ui.dp(BUTTON_H_DP), 0f));
        btns.addView(deleteButton, new LinearLayout.LayoutParams(0, Ui.dp(BUTTON_H_DP), 1f));
        root.addView(btns, Ui.lpm(-1, -2, 0, 10, 0, 0));

        root.addView(Ui.button(c, "Проверить ключ", R.drawable.ic_check, Ui.BTN_GLASS, new Runnable() { @Override public void run() { check(); } }),
            Ui.lpm(-1, Ui.dp(BUTTON_H_DP), 0, 10, 0, 0));
        checkHost = Ui.column(c);
        root.addView(checkHost, Ui.lpm(-1, -2, 0, 8, 0, 0));
    }

    private View eyeButton() {
        eyeButton = new FrameLayout(c);
        eyeHidden = Ui.icon(c, R.drawable.ic_eye, 20, Ui.P.text2);
        eyeShown = Ui.icon(c, R.drawable.ic_eye_off, 20, Ui.P.text2);
        eyeButton.addView(eyeHidden, new FrameLayout.LayoutParams(Ui.dp(20), Ui.dp(20), Gravity.CENTER));
        eyeButton.addView(eyeShown, new FrameLayout.LayoutParams(Ui.dp(20), Ui.dp(20), Gravity.CENTER));
        eyeButton.setBackground(Ui.rippleOn(new ColorDrawable(0), 20));
        eyeButton.setClickable(true);
        eyeButton.setFocusable(true);
        eyeButton.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            shown = !shown;
            applyEye(true);
        } });
        applyEye(false);
        return eyeButton;
    }

    /** Shows the eye that matches the state (ic_eye while hidden) and cross-fades between the two over the fade duration. */
    private void applyEye(boolean animate) {
        float hiddenAlpha = shown ? 0f : 1f, shownAlpha = shown ? 1f : 0f;
        long d = Motion.fade(Motion.D_FAST);
        eyeHidden.animate().cancel();
        eyeShown.animate().cancel();
        if (!animate || d <= 0) {
            eyeHidden.setAlpha(hiddenAlpha);
            eyeShown.setAlpha(shownAlpha);
        } else {
            eyeHidden.animate().alpha(hiddenAlpha).setDuration(d).start();
            eyeShown.animate().alpha(shownAlpha).setDuration(d).start();
        }
        eyeButton.setContentDescription(shown ? "Скрыть ключ" : "Показать ключ");
        int sel = field.getSelectionEnd();
        field.setInputType(InputType.TYPE_CLASS_TEXT | (shown ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
        field.setTypeface(Ui.regular);
        if (sel >= 0 && sel <= field.getText().length()) field.setSelection(sel);
    }

    private void refresh() {
        String key = j.secrets.get("key." + p.id);
        status.setText(KeyDisplay.statusLine(key, j.settings.get(KeyDisplay.savedAtKey(p.id)), TimeZone.getDefault()));
        status.setTextColor(key.isEmpty() ? Ui.P.text3 : Ui.P.text2);
        storage.setText(KeyDisplay.storageText(j.secrets.isEncrypted()));
    }

    private void save() {
        String v = field.getText().toString().trim();
        if (v.isEmpty()) { host.overlays().toast("Введите ключ", OverlayHost.ERR); return; }
        store(j, p.id, v);
        field.setText("");
        field.clearFocus();
        host.hideKeyboard();
        saveButton.requestFocus();
        host.overlays().toast("Ключ сохранён", OverlayHost.OK);
        renderIdle();
        refresh();
        if (onChanged != null) onChanged.run();
    }

    private void delete() {
        if (j.secrets.get("key." + p.id).isEmpty()) { host.overlays().toast("Ключа нет", OverlayHost.INFO); return; }
        forget(j, p.id);
        host.overlays().toast("Ключ удалён", OverlayHost.INFO);
        renderIdle();
        refresh();
        if (onChanged != null) onChanged.run();
    }

    private void check() {
        if (checking) return;
        if (j.secrets.get("key." + p.id).isEmpty()) { host.overlays().toast("Сначала сохраните ключ", OverlayHost.ERR); return; }
        final LlmConfig cfg = j.choiceFor(p.id, null).config;
        checking = true;
        renderChecking();
        Thread th = new Thread(new Runnable() { @Override public void run() {
            final KeyCheck.Result r = KeyCheck.run(j.llm, cfg, p.id, j.journal, new CancelToken());
            j.main.post(new Runnable() { @Override public void run() {
                checking = false;
                showCheck(r);
            } });
        } }, "jv-key-check");
        th.setDaemon(true);
        th.start();
    }

    /** Renders a check outcome: OK with latency, or the classified failure with its Russian text. */
    public void showCheck(KeyCheck.Result r) {
        checkHost.removeAllViews();
        LinearLayout row = Ui.row(c);
        if (r.ok) {
            row.addView(Widgets.pill(c, KeyDisplay.label(null), Widgets.OK));
            checkHost.addView(row);
            checkHost.addView(caption("ответ за " + r.latencyMs + " мс · GET " + r.path), Ui.lpm(-2, -2, 0, 6, 0, 0));
        } else {
            row.addView(Widgets.pill(c, KeyDisplay.label(r.kind), r.kind == LlmException.Kind.GEO_BLOCK ? Widgets.WARN : Widgets.ERR));
            checkHost.addView(row);
            checkHost.addView(caption(r.userMessage), Ui.lpm(-2, -2, 0, 6, 0, 0));
            if (!r.technical.isEmpty()) checkHost.addView(Ui.button(c, "Подробности", R.drawable.ic_info, Ui.BTN_GHOST, new Runnable() { @Override public void run() {
                Dialogs.info(host, "Технические подробности", "HTTP " + r.status + "\n" + r.technical);
            } }), Ui.lpm(-2, -2, 0, 6, 0, 0));
        }
        checkHost.addView(note("Один запрос к " + p.name + " (GET " + r.path + "). Токены модели не тратятся; результат записан в журнал."),
            Ui.lpm(-2, -2, 0, 8, 0, 0));
    }

    private void renderIdle() {
        checkHost.removeAllViews();
        checkHost.addView(note("Проверит, что сохранённый ключ принимает провайдер: один короткий запрос, без токенов модели."));
    }

    private void renderChecking() {
        checkHost.removeAllViews();
        LinearLayout row = Ui.row(c);
        row.addView(Widgets.spinner(c, 18));
        row.addView(Ui.text(c, " Проверяю ключ…", Ui.BODY, Ui.P.text2));
        checkHost.addView(row);
    }

    private TextView caption(String s) { return Ui.text(c, s, Ui.CAPTION, Ui.P.text2); }

    private TextView note(String s) { return Ui.text(c, s, Ui.CAPTION, Ui.P.text3); }

    private String clipboard() {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            android.content.ClipData d = cm == null ? null : cm.getPrimaryClip();
            return d == null || d.getItemCount() == 0 ? "" : String.valueOf(d.getItemAt(0).getText());
        } catch (Throwable t) {
            return "";
        }
    }
}
