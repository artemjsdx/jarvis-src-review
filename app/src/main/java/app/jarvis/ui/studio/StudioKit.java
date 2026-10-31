package app.jarvis.ui.studio;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.core.voice.ProviderInfo;
import app.jarvis.core.voice.ProviderStatus;
import app.jarvis.core.voice.TtsProvider;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import java.util.Locale;

/** Small builders shared by the studio tabs: status pills, latency/time formatting, meta rows. */
final class StudioKit {
    private StudioKit() { }

    static String ms(long v) {
        if (v < 0) return "";
        if (v < 10_000) return v >= 1000 ? String.format(Locale.US, "%.1f с", v / 1000.0).replace(".", ",") : v + " мс";
        return String.format(Locale.US, "%.0f с", v / 1000.0);
    }

    /** "только что", "12 мин назад", "3 ч назад", otherwise a short date/time. */
    static String ago(Context c, long atMs) {
        if (atMs <= 0) return "";
        long d = System.currentTimeMillis() - atMs;
        if (d < 60_000L) return "только что";
        if (d < 3600_000L) return (d / 60_000L) + " мин назад";
        if (d < 86_400_000L) return (d / 3600_000L) + " ч назад";
        try { return android.text.format.DateUtils.formatDateTime(c, atMs, android.text.format.DateUtils.FORMAT_SHOW_DATE | android.text.format.DateUtils.FORMAT_ABBREV_MONTH); }
        catch (Throwable t) { return ""; }
    }

    static TextView pillForStatus(Context c, ProviderStatus s) {
        if (s == null || s == ProviderStatus.NEVER) return Widgets.pill(c, "не проверялся", Widgets.MUTED);
        if (s.ok) return Widgets.pill(c, "работает", Widgets.OK);
        return Widgets.pill(c, "ошибка", Widgets.ERR);
    }

    /** One-line description of the last check (time + latency), or "" when never checked. */
    static String statusLine(Context c, ProviderStatus s) {
        if (s == null || s == ProviderStatus.NEVER) return "";
        StringBuilder sb = new StringBuilder("проверено: ").append(ago(c, s.checkedAtMs));
        if (s.ok) {
            if (s.firstAudioMs > 0) sb.append(" · до первого звука ").append(ms(s.firstAudioMs));
            if (s.totalMs > 0) sb.append(" · всего ").append(ms(s.totalMs));
        } else if (!s.lastError.isEmpty()) {
            sb.append(" · ").append(s.lastError);
        }
        return sb.toString();
    }

    /** "проверено: только что" alone, so the status block can put it on its own line next to the badge. */
    static String checkedAgo(Context c, ProviderStatus s) {
        if (s == null || s == ProviderStatus.NEVER || s.checkedAtMs <= 0) return "";
        return "проверено: " + ago(c, s.checkedAtMs);
    }

    /** Latency after a successful check, or the reason of the failure: the second line of the status block. */
    static String statusDetail(Context c, ProviderStatus s) {
        if (s == null || s == ProviderStatus.NEVER) return "";
        if (!s.ok) return s.lastError.isEmpty() ? "" : s.lastError;
        StringBuilder sb = new StringBuilder();
        if (s.firstAudioMs > 0) sb.append("до первого звука ").append(ms(s.firstAudioMs));
        if (s.totalMs > 0) { if (sb.length() > 0) sb.append(" · "); sb.append("всего ").append(ms(s.totalMs)); }
        return sb.toString();
    }

    /** A hint line WITHOUT its own filled panel: used inside a card, where a nested card looked like a separate block. */
    static LinearLayout noteLine(Context c, int icon, String text) {
        LinearLayout r = Ui.row(c);
        r.setGravity(android.view.Gravity.TOP);
        if (icon != 0) r.addView(Ui.icon(c, icon, 14, Ui.P.text3), Ui.lpm(-2, -2, 0, 2, Ui.dp(7), 0));
        TextView t = Ui.text(c, text, Ui.CAPTION, Ui.P.text3);
        t.setMaxLines(3);
        t.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(t, Ui.lpw(1f));
        return r;
    }

    static String officialLabel(ProviderInfo i) { return i.official ? "официальный" : "неофициальный"; }

    /** Why a provider cannot join the reply chain right now ("", when it can). */
    static String inactiveReason(TtsProvider p, boolean inChain) {
        if (!p.isConfigured()) {
            ProviderInfo i = p.info();
            if (i.needsKey) return "нет ключа API — задайте его в настройках голоса";
            return "не настроен";
        }
        if (p instanceof app.jarvis.core.voice.SlowProvider) return "студийный режим: слишком медленный для ответов";
        return inChain ? "" : "выключен — включите переключателем";
    }

    /** A "label: value" caption pair used inside provider cards. */
    static LinearLayout metaRow(Context c, String label, String value) {
        LinearLayout r = Ui.rowTop(c);
        TextView l = Ui.text(c, label, Ui.CAPTION, Ui.P.text3);
        // Fixed 94dp was too narrow for «для провайдера» and clipped the label.
        // Long values (quotas, cloning limitations) must remain fully readable.
        l.setSingleLine(false);
        l.setMaxLines(3);
        r.addView(l, Ui.lpm(124, -2, 0, 0, 8, 0));
        TextView v = Ui.text(c, value == null ? "—" : value, Ui.CAPTION, Ui.P.text2);
        v.setSingleLine(false);
        r.addView(v, Ui.lpw(1f));
        return r;
    }

    /** A bordered notes block ("where data goes", consent, explanations). */
    static LinearLayout noteCard(Context c, int icon, String title, String body) {
        LinearLayout card = Ui.column(c);
        card.setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12));
        card.setBackground(Ui.glassTinted(14, Ui.alpha(Ui.P.accent, 0.08f)));
        LinearLayout head = Ui.row(c);
        if (icon != 0) head.addView(Ui.icon(c, icon, 16, Ui.P.accent), Ui.lpm(-2, -2, 0, 0, 8, 0));
        TextView t = Ui.text(c, title, Ui.BODY);
        t.setTypeface(Ui.medium);
        head.addView(t, Ui.lpw(1f));
        card.addView(head);
        TextView b = Ui.text(c, body, Ui.CAPTION, Ui.P.text2);
        card.addView(b, Ui.lpm(-2, -2, 0, 6, 0, 0));
        return card;
    }

    static void setGone(View v, boolean gone) { v.setVisibility(gone ? View.GONE : View.VISIBLE); }
}
