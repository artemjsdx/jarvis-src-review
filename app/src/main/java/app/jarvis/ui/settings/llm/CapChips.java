package app.jarvis.ui.settings.llm;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import app.jarvis.core.llm.Cap;
import app.jarvis.core.llm.ModelCaps;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import java.util.Locale;

/** Capability chips and number formats for model rows. Unknown values are shown as unknown, never as "no". */
public final class CapChips {
    private CapChips() { }

    public static String tokens(long n) { return app.jarvis.core.agent.ContextFit.tokens(n); }

    /** USD per one million tokens, e.g. "$0,09"; null when the catalog does not report a price. */
    public static String price(Double perMillion) {
        if (perMillion == null) return null;
        if (perMillion == 0) return "бесплатно";
        String s = perMillion < 1 ? String.format(Locale.US, "%.3f", perMillion) : String.format(Locale.US, "%.2f", perMillion);
        return "$" + s.replace('.', ',');
    }

    /** "2 мин назад", "3 ч назад", "5 дн. назад". */
    public static String ago(long millis) {
        long m = Math.max(0, millis / 60_000L);
        if (m < 1) return "только что";
        if (m < 60) return m + " мин назад";
        if (m < 60 * 24) return (m / 60) + " ч назад";
        return (m / (60 * 24)) + " дн. назад";
    }

    /** Chips for context, output, tools, reasoning and images; they wrap to as many lines as the width needs. */
    public static View chips(Context c, ModelCaps m) {
        Flow f = new Flow(c);
        f.addView(chip(c, m.contextTokens > 0 ? "контекст " + tokens(m.contextTokens) : "контекст неизвестен", m.contextTokens > 0 ? Widgets.INFO : Widgets.MUTED));
        if (m.maxOutputTokens > 0) f.addView(chip(c, "вывод " + tokens(m.maxOutputTokens), Widgets.INFO));
        f.addView(capChip(c, m.tools, "инструменты", "без инструментов", "инструменты: неизвестно"));
        f.addView(capChip(c, m.reasoning, "рассуждение", "без рассуждения", "рассуждение: неизвестно"));
        f.addView(capChip(c, m.vision, "изображения", "без изображений", "изображения: неизвестно"));
        return f;
    }

    /** "Цена: $0,09 вход · $0,18 выход за 1 млн токенов", or null when the catalog reports no price. */
    public static TextView priceLine(Context c, ModelCaps m) {
        if (m.priceInPerM == null && m.priceOutPerM == null) return null;
        String in = m.priceInPerM == null ? "?" : price(m.priceInPerM);
        String out = m.priceOutPerM == null ? "?" : price(m.priceOutPerM);
        return Ui.text(c, "Цена: " + in + " вход · " + out + " выход за 1 млн токенов", Ui.CAPTION, Ui.P.text3);
    }

    public static View capChip(Context c, Cap cap, String yes, String no, String unknown) {
        switch (cap) {
            case YES: return chip(c, yes, Widgets.OK);
            case NO: return chip(c, no, Widgets.MUTED);
            default: return chip(c, unknown, Widgets.MUTED);
        }
    }

    private static View chip(Context c, String text, int kind) {
        android.widget.TextView t = Widgets.pill(c, text, kind);
        t.setTextSize(11);
        return t;
    }

    /** Lays out children left to right and wraps to a new line when the width runs out. */
    static final class Flow extends ViewGroup {
        private final int gapH = Ui.dp(6), gapV = Ui.dp(6);
        private int[] xs = new int[0], ys = new int[0];

        Flow(Context c) { super(c); }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            int avail = Math.max(0, width - getPaddingLeft() - getPaddingRight());
            int n = getChildCount();
            xs = new int[n];
            ys = new int[n];
            int x = 0, y = 0, line = 0;
            for (int i = 0; i < n; i++) {
                View v = getChildAt(i);
                v.measure(MeasureSpec.makeMeasureSpec(avail, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int w = v.getMeasuredWidth(), h = v.getMeasuredHeight();
                if (x > 0 && x + gapH + w > avail) { y += line + gapV; x = 0; line = 0; }
                if (x > 0) x += gapH;
                xs[i] = x;
                ys[i] = y;
                x += w;
                line = Math.max(line, h);
            }
            setMeasuredDimension(width, resolveSize(y + line + getPaddingTop() + getPaddingBottom(), heightSpec));
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                int x = getPaddingLeft() + xs[i], y = getPaddingTop() + ys[i];
                v.layout(x, y, x + v.getMeasuredWidth(), y + v.getMeasuredHeight());
            }
        }
    }
}
