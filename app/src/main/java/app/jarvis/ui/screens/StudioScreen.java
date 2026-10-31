package app.jarvis.ui.screens;

import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import app.jarvis.rt.SpeechApi;
import app.jarvis.ui.Host;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import app.jarvis.ui.studio.CloneTab;
import app.jarvis.ui.studio.DubTab;
import app.jarvis.ui.studio.PronTab;

/**
 * Студия голоса: рабочее место озвучки. Три вкладки: «Озвучка» (провайдеры, тесты, дубли),
 * «Клонирование» (образец → согласие → клон → профили голосов), «Произношение» (словарь замен, предпросмотр, кэш).
 * Если голосовой модуль не подключён (j.speech == null) — честное состояние без кнопок.
 */
public class StudioScreen extends app.jarvis.ui.Screen {
    private static final String[] TABS = {"Озвучка", "Клонирование", "Произношение"};

    private LinearLayout tabsBar;
    private FrameLayout content;
    private int current = 0;
    private DubTab dub;
    private CloneTab clone;
    private PronTab pron;

    public StudioScreen(Host host) { super(host); }

    @Override public String title() { return "Студия голоса"; }

    @Override protected View build() {
        LinearLayout root = Ui.column(ctx);
        root.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), 0);
        tabsBar = Widgets.segmented(ctx, TABS, 0, new Widgets.IntListener() {
            @Override public void on(int index) { if (index != current) { current = index; showTab(); } }
        });
        root.addView(tabsBar, Ui.lpm(-1, -2, 0, 0, 0, Ui.dp(10)));
        content = new FrameLayout(ctx);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        return root;
    }

    @Override public void onShow(String arg) {
        if ("clone".equals(arg)) current = 1;
        else if ("pron".equals(arg)) current = 2;
        styleTabs();
        showTab();
    }

    @Override public void onHide() {
        if (clone != null) clone.onHide();
    }

    @Override public void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        if (requestCode == CloneTab.REQ_IMPORT_SAMPLE && resultCode == android.app.Activity.RESULT_OK
            && data != null && data.getData() != null && clone != null) {
            clone.onSamplePicked(data.getData());
        }
    }

    @Override public boolean onBack() {
        // nothing nested yet; overlays are handled by the shell
        return false;
    }

    private void styleTabs() {
        // re-style the segmented bar by re-clicking semantics: rebuild labels' backgrounds
        for (int i = 0; i < TABS.length; i++) {
            View cell = tabsBar.getChildAt(i);
            boolean on = i == current;
            cell.setBackground(on ? Ui.rect(Ui.P.accent, 11) : null);
            ((android.widget.TextView) cell).setTextColor(on ? Ui.P.onAccent : Ui.P.text2);
        }
    }

    private void showTab() {
        content.removeAllViews();
        if (j.speech == null) {
            content.addView(noSpeech());
            return;
        }
        View v;
        if (current == 1) { if (clone == null) clone = new CloneTab(host, this); v = clone.view(); }
        else if (current == 2) { if (pron == null) pron = new PronTab(host); v = pron.view(); }
        else { if (dub == null) dub = new DubTab(host); v = dub.view(); }
        if (v.getParent() != null) ((android.view.ViewGroup) v.getParent()).removeView(v);
        content.addView(v, new FrameLayout.LayoutParams(-1, -1));
    }

    /** Honest disabled state: no fake buttons, no fake providers. */
    private View noSpeech() {
        android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
        LinearLayout col = Ui.column(ctx);
        col.setPadding(0, Ui.dp(24), 0, Ui.dp(24));
        col.addView(Widgets.empty(ctx, app.jarvis.R.drawable.ic_voice, "Голосовой модуль не подключён",
            "Озвучка и клонирование появятся, когда речевой движок запустится.\nПерезапустите приложение и проверьте журнал."));
        col.addView(Ui.text(ctx, "Модуль подключается при старте; причина неудачи пишется в журнал (раздел «Журнал»).",
            Ui.CAPTION, Ui.P.text3), Ui.lpm(-1, -2, 0, Ui.dp(16), Ui.dp(12), 0));
        sv.addView(col);
        return sv;
    }

    /** Convenience for tabs: the speech API may disappear between builds; tabs check it themselves too. */
    static SpeechApi speech(Host h) { return h.jarvis().speech; }
}
