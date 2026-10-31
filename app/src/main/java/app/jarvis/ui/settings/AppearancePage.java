package app.jarvis.ui.settings;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.data.Settings;
import app.jarvis.ui.Host;
import app.jarvis.ui.HelpPopup;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import app.jarvis.ui.glass.GlassLevel;

/**
 * Appearance: theme (applied on restart — the shell is built once), motion scale (applied live through
 * Ui.refreshMotion), and how the assistant addresses the user with a live preview chip.
 */
public class AppearancePage extends SettingsPage {
    private LinearLayout content;

    public AppearancePage(Host host) { super(host); }

    @Override public String title() { return "Внешний вид"; }

    @Override protected View build() {
        ScrollView sv = Ui.scroll(ctx);
        sv.setFillViewport(true);
        content = Ui.column(ctx);
        content.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(24));

        content.addView(Widgets.sectionTitle(ctx, "Тема"));
        LinearLayout themeCard = Widgets.card(ctx);
        LinearLayout themePad = Ui.column(ctx);
        themePad.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12));
        String theme = j.settings.get(Settings.UI_THEME, "dark");
        themePad.addView(Widgets.segmented(ctx, new String[]{"Тёмная", "Светлая", "Системная"},
            theme.equals("light") ? 1 : theme.equals("system") ? 2 : 0, new Widgets.IntListener() { @Override public void on(int index) {
                j.settings.set(Settings.UI_THEME, index == 1 ? "light" : index == 2 ? "system" : "dark");
                host.applyTheme();
            } }));
        themeCard.addView(themePad);
        content.addView(themeCard);

        content.addView(Widgets.sectionTitle(ctx, "Анимация"));
        LinearLayout motionCard = Widgets.card(ctx);
        LinearLayout motionPad = Ui.column(ctx);
        motionPad.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12));
        String motion = j.settings.get(Settings.UI_MOTION, "full");
        motionPad.addView(Widgets.segmented(ctx, new String[]{"Полная", "Сниженная", "Выкл"},
            "reduced".equals(motion) ? 1 : "off".equals(motion) ? 2 : 0, new Widgets.IntListener() { @Override public void on(int index) {
                String mode = index == 1 ? "reduced" : index == 2 ? "off" : "full";
                j.settings.set(Settings.UI_MOTION, mode);
                Ui.refreshMotion(ctx, mode);
            } }));
        motionPad.addView(PageUi.note(ctx, "Сниженная: без движения, только короткие затухания. Выкл: всё мгновенно. "
            + "Если в Android отключены анимации, действует «Выкл»."), Ui.lpm(-2, -2, 0, Ui.dp(8), 0, 0));
        LinearLayout demo = Ui.row(ctx);
        demo.setPadding(0, Ui.dp(12), 0, 0);
        demo.addView(Ui.text(ctx, "Пример переключателя", Ui.BODY), Ui.lpwm(1f, 0, 0, 8, 0));
        demo.addView(Widgets.toggle(ctx, false, null));
        motionPad.addView(demo);
        LinearLayout samples = Ui.row(ctx);
        samples.setPadding(0, Ui.dp(10), 0, 0);
        samples.addView(Ui.button(ctx, "Окно", R.drawable.ic_info, Ui.BTN_GLASS, new Runnable() { @Override public void run() { showSampleDialog(); } }),
            Ui.lpm(-2, -2, 0, 0, 8, 0));
        samples.addView(Ui.button(ctx, "Шторка", R.drawable.ic_info, Ui.BTN_GLASS, new Runnable() { @Override public void run() { showSampleSheet(); } }));
        motionPad.addView(samples);
        motionCard.addView(motionPad);
        content.addView(motionCard);

        content.addView(Widgets.sectionTitle(ctx, "Стекло"));
        LinearLayout glassCard = Widgets.card(ctx);
        LinearLayout glassPad = Ui.column(ctx);
        glassPad.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12));
        LinearLayout glassHead = Ui.row(ctx);
        glassHead.addView(Ui.text(ctx, "Размытие стекла", Ui.BODY), Ui.lpw(1f));
        glassHead.addView(HelpPopup.button(ctx, host, "Размытие стекла",
            "Плавающие элементы (док, окна, кнопка «вниз») размывают то, что под ними.",
            "Авто включает полный эффект и снижает его, если кадры с эффектом тормозят.",
            "Упрощённый: размытие слабее. Выкл: только полупрозрачность, без размытия."));
        glassPad.addView(glassHead);
        final GlassLevel[] glassLevels = {GlassLevel.AUTO, GlassLevel.FULL, GlassLevel.REDUCED, GlassLevel.OFF};
        String glassNow = j.settings.get(GlassLevel.KEY, GlassLevel.AUTO.key);
        int glassSel = 0;
        for (int i = 0; i < glassLevels.length; i++) if (glassLevels[i].key.equals(glassNow)) glassSel = i;
        glassPad.addView(Widgets.segmented(ctx, new String[]{"Авто", "Полный", "Упрощ.", "Выкл"}, glassSel,
            new Widgets.IntListener() { @Override public void on(int index) {
                j.settings.set(GlassLevel.KEY, glassLevels[index].key);
            } }), Ui.lpm(-1, -2, 0, Ui.dp(8), 0, 0));
        glassCard.addView(glassPad);
        content.addView(glassCard);

        content.addView(Widgets.sectionTitle(ctx, "Обращение"));
        LinearLayout callCard = Widgets.card(ctx);
        LinearLayout callPad = Ui.column(ctx);
        callPad.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12));
        callPad.addView(PageUi.caption(ctx, "Как Jarvis будет обращаться к вам"));
        android.widget.EditText e = PageUi.saveField(ctx, "Имя или обращение", j.settings.get(Settings.USER_CALL), new PageUi.TextListener() { @Override public void on(String s) {
            j.settings.set(Settings.USER_CALL, s.trim());
            refreshPreview();
        } });
        callPad.addView(Widgets.fieldBox(ctx, e), Ui.lpm(-1, -2, 0, Ui.dp(8), 0, 0));
        callPad.addView(PageUi.note(ctx, "Например: Саша или шеф. Пусто — Jarvis обращается без имени."), Ui.lpm(-2, -2, 0, Ui.dp(6), 0, 0));
        TextView preview = Ui.chip(ctx, callPreviewText(), Ui.P.accent);
        preview.setTag("callPreview");
        callPad.addView(preview, Ui.lpm(-2, -2, 0, Ui.dp(10), 0, 0));
        callCard.addView(callPad);
        content.addView(callCard);

        sv.addView(content);
        return sv;
    }

    private String callPreviewText() {
        String call = j.settings.get(Settings.USER_CALL);
        return call.isEmpty() ? "«Джарвис!»" : "«Джарвис, " + call + "!»";
    }

    private void refreshPreview() {
        if (content == null) return;
        View chip = content.findViewWithTag("callPreview");
        if (chip instanceof TextView) ((TextView) chip).setText(callPreviewText());
    }

    private void showSampleDialog() {
        LinearLayout box = Ui.column(ctx);
        box.setPadding(Ui.dp(20), Ui.dp(20), Ui.dp(20), Ui.dp(16));
        box.addView(Ui.text(ctx, "Так открывается окно", Ui.TITLE));
        box.addView(Ui.text(ctx, "Появляется и закрывается с выбранной анимацией.", Ui.CAPTION, Ui.P.text2), Ui.lpm(-2, -2, 0, 8, 0, 0));
        box.addView(Ui.button(ctx, "Закрыть", 0, Ui.BTN_PRIMARY, new Runnable() { @Override public void run() { host.overlays().dismissAll(); } }),
            Ui.lpm(-1, -2, 0, 16, 0, 0));
        host.overlays().showDialog(box, true, null);
    }

    private void showSampleSheet() {
        LinearLayout box = Ui.column(ctx);
        box.setPadding(Ui.dp(20), Ui.dp(20), Ui.dp(20), Ui.dp(20));
        box.addView(Ui.text(ctx, "Так выезжает шторка", Ui.TITLE));
        box.addView(Ui.text(ctx, "Тянется снизу; её можно сдвинуть вниз, чтобы закрыть.", Ui.CAPTION, Ui.P.text2), Ui.lpm(-2, -2, 0, 8, 0, 0));
        box.addView(Ui.button(ctx, "Закрыть", 0, Ui.BTN_GLASS, new Runnable() { @Override public void run() { host.overlays().dismissAll(); } }),
            Ui.lpm(-1, -2, 0, 16, 0, 0));
        host.overlays().showSheet(box, true, null);
    }
}
