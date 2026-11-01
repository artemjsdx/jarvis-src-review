package app.jarvis.ui.screens;

import android.Manifest;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.core.agent.SuggestionTap;
import app.jarvis.core.llm.Presets;
import app.jarvis.data.Settings;
import app.jarvis.media.MusicOut;
import app.jarvis.rt.AState;
import app.jarvis.rt.Jarvis;
import app.jarvis.rt.JarvisListener;
import app.jarvis.rt.Suggestions;
import app.jarvis.ui.Host;
import app.jarvis.ui.BrandSkin;
import app.jarvis.ui.OrbView;
import app.jarvis.ui.Screen;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import app.jarvis.ui.home.HomeStatus;
import app.jarvis.ui.home.MiniPlayerCard;
import app.jarvis.ui.home.SuggestionTray;
import java.util.List;

/**
 * Voice command centre: the orb with live assistant state, one big push-to-talk button, wake-word toggle, stop, last
 * action, mini-player, LLM status and suggestion chips. Header-less; fits 392x850dp without scrolling in the
 * normal state (a hidden ScrollView only opens up when banner + player + last action stack up, so nothing can clip).
 */
public class HomeScreen extends Screen {
    private ScrollView scroller;
    private OrbView orb;
    private TextView stateLabel, stateDetail;
    private FrameLayout micBtn;
    private ImageView micIcon;
    private View stopBtn;
    private Widgets.Toggle wakeToggle;
    private TextView wakeSub;
    private LinearLayout banner;
    private TextView bannerText;
    private View bannerAction;
    private LinearLayout lastActionCard;
    private TextView lastActionText;
    private TextView lastActionPill;
    private MiniPlayerCard player;
    private TextView llmChip;
    private SuggestionTray tray;
    private Suggestions suggestions;
    private final float[] lastLevel = {0f};

    private final JarvisListener listener = new JarvisListener() {
        @Override public void onState(AState s, String detail) { refreshState(); refreshBusy(); }
        @Override public void onLevel(float level) { lastLevel[0] = level; orb.setLevel(level); }
        @Override public void onMusic(MusicOut.Info info) { player.refresh(); }
        @Override public void onLastAction(String text, boolean ok) { refreshLastAction(); }
        @Override public void onRunChanged(boolean running) {
            refreshBusy();
            if (!running) suggestions.afterRun();
        }
        @Override public void onProvidersChanged() { refreshLlm(); }
        @Override public void onPermissionResult(String permission, boolean granted) { refreshMic(); }
    };

    public HomeScreen(Host host) { super(host); }

    @Override protected View build() {
        FrameLayout root = new FrameLayout(ctx);
        scroller = new ScrollView(ctx);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setFillViewport(true);
        root.addView(scroller, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = Ui.column(ctx);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int h = Ui.dp(18);
        col.setPadding(h, 0, h, Ui.dp(8));
        scroller.addView(col, new ScrollView.LayoutParams(-1, -1));

        // top status row: LLM chip on its own line
        LinearLayout top = Ui.row(ctx);
        llmChip = glassPill(ctx);
        llmChip.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { host.navigate("settings/llm"); } });
        top.addView(llmChip);
        col.addView(top, new LinearLayout.LayoutParams(-1, -2));

        col.addView(Ui.space(ctx, 1, 2));

        orb = new OrbView(ctx);
        orb.setSkin(BrandSkin.orb(ctx));
        col.addView(orb, Ui.lpm(Ui.dp(196), Ui.dp(196), 0, 0, 0, 0));

        stateLabel = Ui.text(ctx, "", Ui.BODY);
        stateLabel.setTypeface(Ui.medium);
        stateLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        stateLabel.setGravity(Gravity.CENTER);
        stateLabel.setSingleLine(true);
        stateLabel.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(stateLabel, Ui.lpm(-2, -2, 0, 8, 0, 0));
        stateDetail = Ui.text(ctx, "", Ui.CAPTION, Ui.P.text3);
        stateDetail.setGravity(Gravity.CENTER);
        stateDetail.setSingleLine(true);
        stateDetail.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(stateDetail, Ui.lpm(-1, -2, 8, 2, 8, 0));

        micBtn = new FrameLayout(ctx);
        micIcon = Ui.icon(ctx, R.drawable.ic_mic, 30, Ui.P.text);
        micBtn.addView(micIcon, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        micBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { toggleMic(); } });
        Ui.pressScale(micBtn);
        col.addView(micBtn, Ui.lpm(Ui.dp(84), Ui.dp(84), 0, 14, 0, 0));

        stopBtn = Ui.button(ctx, "Остановить", R.drawable.ic_stop, Ui.BTN_GLASS, new Runnable() { @Override public void run() { j.cancelAll(); } });
        stopBtn.setVisibility(View.GONE);
        col.addView(stopBtn, Ui.lpm(-2, -2, 0, 10, 0, 0));

        col.addView(wakeRow(ctx), Ui.lpm(-1, -2, 0, 12, 0, 0));

        banner = Ui.row(ctx);
        banner.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(8), Ui.dp(8));
        GradientDrawable bg = Ui.rect(0x1AE8C77A, 14);
        bg.setStroke(Math.max(1, Ui.dp(1)), 0x4DE8C77A);
        banner.setBackground(bg);
        ImageView bico = Ui.icon(ctx, R.drawable.ic_mic_off, 16, Ui.P.warn);
        banner.addView(bico, Ui.lpm(-2, -2, 0, 0, 8, 0));
        bannerText = Ui.text(ctx, "", Ui.CAPTION, Ui.P.text2);
        bannerText.setSingleLine(false);
        bannerText.setMaxLines(2);
        banner.addView(bannerText, Ui.lpw(1f));
        bannerAction = Ui.button(ctx, "Разрешить", 0, Ui.BTN_GHOST, new Runnable() {
            @Override public void run() { host.requestPermission(Manifest.permission.RECORD_AUDIO); }
        });
        banner.addView(bannerAction, Ui.lpm(-2, -2, 8, 0, 0, 0));
        banner.setVisibility(View.GONE);
        col.addView(banner, Ui.lpm(-1, -2, 0, 8, 0, 0));

        lastActionCard = Ui.column(ctx);
        lastActionCard.setBackground(Ui.glass(18));
        lastActionCard.setPadding(Ui.dp(14), Ui.dp(10), Ui.dp(14), Ui.dp(10));
        LinearLayout la = Ui.row(ctx);
        lastActionPill = Widgets.pill(ctx, "ок", Widgets.OK);
        la.addView(lastActionPill);
        la.addView(Ui.space(ctx, 8, 1));
        lastActionText = Ui.text(ctx, "", Ui.CAPTION, Ui.P.text2);
        lastActionText.setSingleLine(true);
        lastActionText.setEllipsize(TextUtils.TruncateAt.END);
        la.addView(lastActionText, Ui.lpw(1f));
        lastActionCard.addView(la, new LinearLayout.LayoutParams(-1, -2));
        lastActionCard.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { host.navigate("agent"); } });
        lastActionCard.setVisibility(View.GONE);
        col.addView(lastActionCard, Ui.lpm(-1, -2, 0, 8, 0, 0));

        player = new MiniPlayerCard(ctx, j);
        col.addView(player, Ui.lpm(-1, -2, 0, 8, 0, 0));

        col.addView(Ui.space(ctx, 1, 0), new LinearLayout.LayoutParams(1, 0, 1f));

        tray = new SuggestionTray(ctx, new SuggestionTap.Sink() {
            @Override public void submit(String text, String source) { j.submit(text, null, source); }
        });
        col.addView(tray, Ui.lpm(-1, -2, 0, 4, 0, 0));
        suggestions = new Suggestions(j, new Suggestions.Listener() {
            @Override public void onSuggestions(List<String> items, boolean fromModel) { tray.show(items); }
        });

        refreshLlm();
        refreshState();
        refreshMic();
        refreshBusy();
        refreshLastAction();
        player.refresh();
        return root;
    }

    private LinearLayout wakeRow(Context c) {
        LinearLayout row = Ui.row(c);
        row.setPadding(Ui.dp(14), Ui.dp(9), Ui.dp(14), Ui.dp(9));
        row.setBackground(Ui.glassPressable(18));
        FrameLayout badge = new FrameLayout(c);
        badge.setBackground(Ui.rect(Ui.P.accentSoft, 10));
        badge.addView(Ui.icon(c, R.drawable.ic_ear, 18, Ui.P.accent), new FrameLayout.LayoutParams(Ui.dp(18), Ui.dp(18), Gravity.CENTER));
        row.addView(badge, Ui.lpm(Ui.dp(34), Ui.dp(34), 0, 0, 12, 0));
        LinearLayout tx = Ui.column(c);
        TextView t = Ui.text(c, "Обращение «Джарвис»", Ui.BODY);
        t.setTypeface(Ui.medium);
        tx.addView(t);
        wakeSub = Ui.text(c, "", Ui.CAPTION, Ui.P.text3);
        wakeSub.setSingleLine(true);
        wakeSub.setEllipsize(TextUtils.TruncateAt.END);
        wakeSub.setTag("sub");
        tx.addView(wakeSub, Ui.lpm(-2, -2, 0, 2, 0, 0));
        row.addView(tx, Ui.lpw(1f));
        wakeToggle = Widgets.toggle(c, false, new Widgets.Toggle.Listener() {
            @Override public void onChanged(boolean on) { applyWake(on); }
        });
        row.addView(wakeToggle, Ui.lpm(-2, -2, 10, 0, 0, 0));
        row.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { wakeToggle.performClick(); } });
        return row;
    }

    private TextView glassPill(Context c) {
        TextView v = Ui.text(c, "", Ui.CAPTION, Ui.P.text2);
        v.setTypeface(Ui.medium);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        v.setPadding(Ui.dp(12), Ui.dp(7), Ui.dp(12), Ui.dp(7));
        v.setBackground(Ui.glassPressable(16));
        v.setClickable(true);
        v.setFocusable(true);
        Ui.pressScale(v);
        return v;
    }

    // ---- refresh ---------------------------------------------------------------------------------------------------
    private static OrbView.Mode orbMode(AState s) {
        switch (s) {
            case LISTENING: return OrbView.Mode.LISTENING;
            case RECOGNIZING: case THINKING: return OrbView.Mode.THINKING;
            case TOOL: return OrbView.Mode.TOOL;
            case SPEAKING: return OrbView.Mode.SPEAKING;
            case ERROR: return OrbView.Mode.ERROR;
            default: return OrbView.Mode.IDLE;
        }
    }

    private void refreshState() {
        AState s = j.state();
        orb.setMode(orbMode(s));
        orb.setLevel(s == AState.LISTENING || s == AState.SPEAKING ? lastLevel[0] : 0f);
        stateLabel.setText(s.label);
        stateLabel.setTextColor(toneColor(HomeStatus.tone(s)));
        String sub = HomeStatus.subtitle(s, j.stateDetail());
        stateDetail.setText(sub);
        stateDetail.setVisibility(sub.isEmpty() ? View.GONE : View.VISIBLE);
        styleMic(s == AState.LISTENING);
    }

    private static int toneColor(HomeStatus.Tone t) {
        switch (t) {
            case ERROR: return Ui.P.err;
            case OK: return Ui.P.ok;
            case MUTED: return Ui.P.text2;
            default: return Ui.P.accent;
        }
    }

    private void styleMic(boolean listening) {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        if (listening) {
            circle.setColors(new int[]{Ui.P.accent, Ui.mix(Ui.P.accent, Ui.P.accent2, 0.6f)});
            circle.setOrientation(GradientDrawable.Orientation.TL_BR);
            circle.setStroke(Math.max(1, Ui.dp(1)), 0x66FFFFFF);
            micIcon.setColorFilter(Ui.P.onAccent);
        } else {
            circle.setOrientation(GradientDrawable.Orientation.TL_BR);
            circle.setColors(new int[]{Ui.alpha(Ui.P.accent, 0.16f), Ui.alpha(Ui.P.accent2, 0.10f)});
            circle.setStroke(Math.max(1, Ui.dp(1.2f)), Ui.mix(Ui.P.strokeHi, Ui.P.accent, 0.45f));
            micIcon.setColorFilter(Ui.P.text);
        }
        micBtn.setBackground(circle);
    }

    private void toggleMic() {
        if (!micUsable()) return;
        if (j.voice.isMicActive()) j.voice.stopListening(); else j.voice.startListening();
    }

    private void refreshBusy() {
        boolean busy = j.isBusy() || j.voice.isMicActive() || j.speaker.isSpeaking();
        stopBtn.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private boolean micUsable() {
        return host.hasPermission(Manifest.permission.RECORD_AUDIO) && j.voice.micProblem().isEmpty();
    }

    private void refreshMic() {
        boolean perm = host.hasPermission(Manifest.permission.RECORD_AUDIO);
        String problem = j.voice.micProblem();
        boolean usable = perm && problem.isEmpty();
        micBtn.setEnabled(usable);
        micBtn.setAlpha(usable ? 1f : 0.42f);
        banner.setVisibility(usable ? View.GONE : View.VISIBLE);
        if (perm) {
            bannerAction.setVisibility(View.GONE);
            bannerText.setText(problem);
        } else {
            bannerAction.setVisibility(View.VISIBLE);
            bannerText.setText("Нет доступа к микрофону");
        }
        refreshWakeSub();
    }

    private void refreshWakeSub() {
        boolean on = j.voice.isWakeEnabled() || (j.voice == app.jarvis.rt.VoiceControl.NONE && j.settings.getBool(Settings.WAKE_ENABLED, false));
        wakeToggle.setChecked(on, false);
        if (!j.voice.micProblem().isEmpty() || !host.hasPermission(Manifest.permission.RECORD_AUDIO)) {
            wakeSub.setText("Недоступно: микрофон");
        } else {
            wakeSub.setText(on ? "Слушаю в фоне" : "Выключено");
        }
    }

    private void applyWake(final boolean on) {
        j.settings.setBool(Settings.WAKE_ENABLED, on);
        j.voice.setWakeEnabled(on);
        refreshWakeSub();
        j.main.postDelayed(new Runnable() {
            @Override public void run() {
                boolean real = j.voice.isWakeEnabled();
                if (real == on) {
                    wakeSub.setText(on ? "Слушаю в фоне" : "Выключено");
                } else {
                    // could not start (or stop): roll back and show the honest reason
                    j.settings.setBool(Settings.WAKE_ENABLED, !on);
                    wakeToggle.setChecked(!on, false);
                    String p = j.voice.micProblem();
                    wakeSub.setText(p.isEmpty() ? "Не получилось изменить" : p);
                }
            }
        }, 350);
    }

    private void refreshLlm() {
        java.util.List<Jarvis.LlmChoice> choices = j.llmChoices();
        Jarvis.LlmChoice c = choices.isEmpty() ? null : choices.get(0);
        if (c != null && c.hasKey) {
            llmChip.setText(c.label());
            llmChip.setTextColor(Ui.P.text2);
        } else {
            String name = c != null ? c.preset.name : (j.providerId().isEmpty() ? "" : Presets.get(j.providerId()) != null ? Presets.get(j.providerId()).name : "");
            llmChip.setText(name.isEmpty() ? "Нет ключа — открыть настройки" : name + " · нет ключа — открыть настройки");
            llmChip.setTextColor(Ui.P.warn);
        }
    }

    private void refreshLastAction() {
        String t = j.lastActionText();
        lastActionCard.setVisibility(t.isEmpty() ? View.GONE : View.VISIBLE);
        lastActionText.setText(t);
        lastActionPill.setText(j.lastActionOk() ? "ок" : "сбой");
        lastActionPill.setTextColor(j.lastActionOk() ? Ui.P.ok : Ui.P.err);
        lastActionPill.setBackground(Ui.glassTinted(20, Ui.alpha(j.lastActionOk() ? Ui.P.ok : Ui.P.err, 0.14f)));
    }

    // ---- lifecycle -------------------------------------------------------------------------------------------------
    @Override public String title() { return null; }

    @Override public void onShow(String arg) {
        j.addListener(listener);
        player.startPolling();
        refreshLlm();
        refreshState();
        refreshMic();
        refreshBusy();
        refreshLastAction();
        suggestions.onShown();
    }

    @Override public void onHide() {
        j.removeListener(listener);
        player.stopPolling();
        suggestions.onHidden();
    }

    @Override public void onInsets(int top, int bottom) {
        scroller.setPadding(0, top + Ui.dp(6), 0, 0);
    }
}
