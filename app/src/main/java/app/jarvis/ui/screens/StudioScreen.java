package app.jarvis.ui.screens;

import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import app.jarvis.rt.SpeechApi;
import app.jarvis.ui.Host;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import app.jarvis.ui.studio.CloneTab;
import app.jarvis.ui.studio.DubTab;
import app.jarvis.ui.studio.PronTab;
import app.jarvis.ui.voiceui.VoiceSettingsPage;
import app.jarvis.ui.voiceui.WakeSettingsPage;
import app.jarvis.ui.settings.SoundsPage;

/**
 * Студия голоса: рабочее место озвучки. Единый голосовой центр: «Озвучка» (провайдеры, тесты, дубли),
 * «Клонирование» (образец → согласие → клон → профили голосов), «Произношение» (словарь замен, предпросмотр, кэш).
 * Если голосовой модуль не подключён (j.speech == null) — честное состояние без кнопок.
 */
public class StudioScreen extends app.jarvis.ui.Screen {
    private static final String[] TABS = {"Озвучка", "Распознавание", "Обращение", "Поведение", "Клонирование", "Произношение", "Микрофон и звуки"};

    private LinearLayout tabsBar;
    private HorizontalScrollView tabsScroll;
    private WakeSettingsPage sttPage, wakePage;
    private VoiceSettingsPage behaviorPage;
    private SoundsPage soundsPage;
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
        // Scrollable chips preserve full labels instead of squeezing six controls into a narrow segmented bar.
        tabsScroll = new HorizontalScrollView(ctx);
        tabsScroll.setHorizontalScrollBarEnabled(false);
        tabsScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        tabsBar = Ui.row(ctx);
        tabsBar.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4));
        tabsBar.setBackground(Ui.glassTinted(14, Ui.P.field));
        for (int i = 0; i < TABS.length; i++) {
            final int index = i;
            TextView tab = Ui.text(ctx, TABS[i], Ui.CAPTION, Ui.P.text2);
            tab.setGravity(Gravity.CENTER);
            tab.setSingleLine(true);
            tab.setTypeface(Ui.medium);
            tab.setPadding(Ui.dp(12), Ui.dp(10), Ui.dp(12), Ui.dp(10));
            tab.setClickable(true);
            tab.setFocusable(true);
            tab.setContentDescription("Вкладка: " + TABS[i]);
            tab.setOnClickListener(v -> {
                if (index != current) {
                    hideCurrent();
                    current = index;
                    styleTabs();
                    showTab();
                }
            });
            tabsBar.addView(tab, new LinearLayout.LayoutParams(-2, -2));
        }
        tabsScroll.addView(tabsBar);
        root.addView(tabsScroll, Ui.lpm(-1, -2, 0, 0, 0, Ui.dp(10)));
        content = new FrameLayout(ctx);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1f));
        return root;
    }

    @Override public void onShow(String arg) {
        if ("stt".equals(arg) || "recognition".equals(arg)) current = 1;
        else if ("wake".equals(arg)) current = 2;
        else if ("behavior".equals(arg) || "settings".equals(arg)) current = 3;
        else if ("clone".equals(arg)) current = 4;
        else if ("pron".equals(arg)) current = 5;
        else if ("sounds".equals(arg) || "mic".equals(arg)) current = 6;
        else if ("tts".equals(arg)) current = 0;
        styleTabs();
        showTab();
    }

    @Override public void onHide() { hideCurrent(); }
    private void hideCurrent() {
        if (current == 1 && sttPage != null) sttPage.onHide();
        if (current == 2 && wakePage != null) wakePage.onHide();
        if (current == 4 && clone != null) clone.onHide();
        if (current == 6 && soundsPage != null) soundsPage.onHide();
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
        for (int i = 0; i < TABS.length; i++) {
            TextView cell = (TextView) tabsBar.getChildAt(i);
            boolean on = i == current;
            cell.setBackground(on ? Ui.rect(Ui.P.accent, 11) : null);
            cell.setTextColor(on ? Ui.P.onAccent : Ui.P.text2);
            cell.setSelected(on);
        }
        tabsScroll.post(() -> {
            View selected = tabsBar.getChildAt(current);
            if (selected != null) tabsScroll.smoothScrollTo(Math.max(0, selected.getLeft() - Ui.dp(24)), 0);
        });
    }

    private void showTab() {
        content.removeAllViews();
        if (j.speech == null && (current == 0 || current == 4 || current == 5)) {
            content.addView(noSpeech());
            return;
        }
        View v;
        switch (current) {
            case 1:
                if (sttPage == null) sttPage = new WakeSettingsPage(host, WakeSettingsPage.Panel.RECOGNITION);
                v = sttPage.view();
                sttPage.onShow();
                break;
            case 2:
                if (wakePage == null) wakePage = new WakeSettingsPage(host, WakeSettingsPage.Panel.WAKE);
                v = wakePage.view();
                wakePage.onShow();
                break;
            case 3:
                if (behaviorPage == null) behaviorPage = new VoiceSettingsPage(host);
                v = behaviorPage.view();
                behaviorPage.onShow();
                break;
            case 4:
                if (clone == null) clone = new CloneTab(host, this);
                v = clone.view();
                break;
            case 5:
                if (pron == null) pron = new PronTab(host);
                v = pron.view();
                break;
            case 6:
                if (soundsPage == null) soundsPage = new SoundsPage(host);
                v = soundsPage.view();
                soundsPage.onShow();
                break;
            default:
                if (dub == null) dub = new DubTab(host);
                v = dub.view();
        }
        if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
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
