package app.jarvis.ui.home;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import app.jarvis.core.agent.SuggestionTap;
import app.jarvis.ui.Ui;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Row of up to three suggestion chips above the dock. A tap is handed to the sink as an ordinary message. Empty
 * means "nothing yet": a shimmer line shows instead. New chips replace old ones with a 160 ms fade.
 */
public final class SuggestionTray extends FrameLayout {
    static final int MAX_CHIPS = 3;
    private static final long HALF_FADE_MS = 80;

    private final SuggestionTap.Sink sink;
    private final FlowLayout chips;
    private final ShimmerLine shimmer;
    private List<String> shown = Collections.emptyList();

    public SuggestionTray(Context c, SuggestionTap.Sink sink) {
        super(c);
        this.sink = sink;
        chips = new FlowLayout(c);
        addView(chips, new LayoutParams(-1, -2, Gravity.CENTER));
        shimmer = new ShimmerLine(c);
        addView(shimmer, new LayoutParams(-1, Ui.dp(38), Gravity.CENTER));
        apply(Collections.<String>emptyList());
    }

    public void show(List<String> items) {
        List<String> next = new ArrayList<>(items.subList(0, Math.min(MAX_CHIPS, items.size())));
        if (next.equals(shown)) return;
        boolean wasEmpty = shown.isEmpty();
        shown = next;
        if (wasEmpty) {
            apply(next);
            setAlpha(0f);
            animate().alpha(1f).setDuration(Ui.ms(2 * HALF_FADE_MS)).start();
            return;
        }
        animate().alpha(0f).setDuration(Ui.ms(HALF_FADE_MS)).withEndAction(new Runnable() {
            @Override public void run() {
                apply(next);
                animate().alpha(1f).setDuration(Ui.ms(HALF_FADE_MS)).start();
            }
        }).start();
    }

    @Override protected void onDetachedFromWindow() {
        shimmer.stop();
        super.onDetachedFromWindow();
    }

    private void apply(List<String> items) {
        chips.removeAllViews();
        for (String text : items) chips.addView(chip(text));
        boolean empty = items.isEmpty();
        chips.setVisibility(empty ? GONE : VISIBLE);
        shimmer.setVisibility(empty ? VISIBLE : GONE);
        if (empty) shimmer.start(); else shimmer.stop();
    }

    private View chip(final String text) {
        TextView chip = Ui.text(getContext(), text, Ui.CAPTION, Ui.P.text);
        chip.setTypeface(Ui.medium);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        chip.setMaxLines(2);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(14), Ui.dp(8));
        GradientDrawable base = Ui.rect(Ui.alpha(Ui.P.text, Ui.P.dark ? 0.06f : 0.04f), 16);
        base.setStroke(Math.max(1, Ui.dp(1)), Ui.P.strokeHi);
        chip.setBackground(Ui.rippleOn(base, 16));
        chip.setClickable(true);
        chip.setFocusable(true);
        chip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { SuggestionTap.tap(text, sink); }
        });
        Ui.pressScale(chip);
        return chip;
    }
}
