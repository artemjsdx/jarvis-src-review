package app.jarvis.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.R;

/**
 * Tiny question mark opens an illustrated, paginated mini-guide instead of
 * a wall of repeated text. The diagrams use native Android views: crisp at
 * any density and no network, SVG parser, WebView, or image bundle required.
 */
public final class HelpPopup {
    private HelpPopup() { }

    public static View button(final Context c, final Host h, final String title, final String... pages) {
        return Ui.iconButton(c, R.drawable.ic_help, 34, Ui.P.text3, "Объяснение: " + title,
            () -> show(h, title, pages));
    }

    public static void show(final Host h, String title, String... lines) {
        Context c = h.context();
        final String[] steps = lines == null || lines.length == 0
            ? new String[]{"Это раздел «" + title + "». Нажимайте на элементы, чтобы изменить параметры."} : lines;
        final int[] current = {0};
        LinearLayout body = Ui.column(c);
        body.setPadding(Ui.dp(20), Ui.dp(6), Ui.dp(20), Ui.dp(10));

        // Visual legend: a compact miniature flow of the feature, not unrelated stock imagery.
        LinearLayout illustration = Ui.column(c);
        body.addView(illustration, Ui.lpm(-1, -2, 0, Ui.dp(4), 0, Ui.dp(14)));

        TextView progress = Ui.text(c, "", Ui.CAPTION, Ui.P.text3);
        progress.setGravity(Gravity.CENTER);
        body.addView(progress);

        LinearLayout progressDots = Ui.row(c);
        progressDots.setGravity(Gravity.CENTER);
        body.addView(progressDots, Ui.lpm(-1, -2, 0, Ui.dp(8), 0, Ui.dp(12)));

        TextView pageTitle = Ui.text(c, "", Ui.BODY);
        pageTitle.setTypeface(Ui.medium);
        body.addView(pageTitle);
        TextView explanation = Ui.text(c, "", Ui.BODY, Ui.P.text2);
        explanation.setSingleLine(false);
        explanation.setTextIsSelectable(true);
        body.addView(explanation, Ui.lpm(-1, -2, 0, Ui.dp(9), 0, 0));

        final OverlayHost.Layer[] layer = new OverlayHost.Layer[1];
        LinearLayout actions = Ui.row(c);
        final TextView prev = Ui.text(c, "Назад", Ui.BODY, Ui.P.accent);
        final TextView next = Ui.text(c, "Далее", Ui.BODY, Ui.P.accent);
        prev.setGravity(Gravity.CENTER);
        next.setGravity(Gravity.CENTER);
        prev.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(12), Ui.dp(12));
        next.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(12), Ui.dp(12));
        actions.addView(prev, new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(next, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(actions, Ui.lpm(-1, -2, 0, Ui.dp(18), 0, 0));

        final Runnable[] repaint = {null};
        repaint[0] = () -> {
            int n = current[0];
            progress.setText("ШАГ " + (n + 1) + " / " + steps.length);
            String line = steps[n] == null ? "" : steps[n].trim();
            String lower = line.toLowerCase(java.util.Locale.ROOT);
            String heading = lower.startsWith("1.") || lower.startsWith("2.") || lower.startsWith("3.")
                ? "Что происходит на этом шаге" : (n == 0 ? "Для чего это нужно" :
                    n == steps.length - 1 ? "Что важно запомнить" : "Как это использовать");
            pageTitle.setText(heading);
            explanation.setText(line);
            prev.setVisibility(n == 0 ? View.INVISIBLE : View.VISIBLE);
            next.setText(n == steps.length - 1 ? "Готово" : "Далее");
            illustration.removeAllViews();

            // A native mini-diagram with contextual labels and arrows.
            LinearLayout visual = Ui.row(c);
            visual.setGravity(Gravity.CENTER_VERTICAL);
            String[] labels = diagramLabels(title, lower, n);
            int[] icons = diagramIcons(title, lower);
            visual.addView(node(c, labels[0], icons[0]), new LinearLayout.LayoutParams(0, Ui.dp(78), 1));
            visual.addView(Ui.text(c, "→", Ui.BODY, Ui.P.accent), Ui.lpm(-2, -2, Ui.dp(6), 0, Ui.dp(6), 0));
            visual.addView(node(c, labels[1], icons[1]), new LinearLayout.LayoutParams(0, Ui.dp(78), 1));
            illustration.addView(visual);
            progressDots.removeAllViews();
            for (int i = 0; i < steps.length; i++) {
                View dot = new View(c);
                dot.setBackground(Ui.rect(i == n ? Ui.P.accent : Ui.P.strokeHi, 4));
                progressDots.addView(dot, Ui.lpm(Ui.dp(i == n ? 25 : 10), Ui.dp(4),
                    i == 0 ? 0 : Ui.dp(5), 0, 0, 0));
            }
        };
        prev.setOnClickListener(v -> { if (current[0] > 0) { current[0]--; repaint[0].run(); } });
        next.setOnClickListener(v -> {
            if (current[0] == steps.length - 1) {
                h.overlays().dismiss(layer[0]);
            } else {
                current[0]++;
                repaint[0].run();
            }
        });
        repaint[0].run();
        layer[0] = Dialogs.sheet(h, title, body, true);
    }

    private static View node(Context c, String text, int icon) {
        LinearLayout box = Ui.column(c);
        box.setGravity(Gravity.CENTER);
        box.setPadding(Ui.dp(8), Ui.dp(8), Ui.dp(8), Ui.dp(8));
        box.setBackground(Ui.glassTinted(14, Ui.alpha(Ui.P.accent, 0.06f)));
        box.addView(Ui.icon(c, icon, 20, Ui.P.accent));
        TextView label = Ui.text(c, text, Ui.CAPTION, Ui.P.text2);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        box.addView(label, Ui.lpm(-1, -2, 0, Ui.dp(6), 0, 0));
        return box;
    }

    /** Distinct, crisp Android vector art for every guide family rather than copied slider/check graphics. */
    private static int[] diagramIcons(String title, String line) {
        String s = (title + " " + line).toLowerCase(java.util.Locale.ROOT);
        if (s.contains("поиск") || s.contains("search") || s.contains("exa")
                || s.contains("tavily") || s.contains("serper"))
            return new int[]{R.drawable.ic_search, R.drawable.ic_globe};
        if (s.contains("mcp") || s.contains("сервер") || s.contains("мост"))
            return new int[]{R.drawable.ic_bot, R.drawable.ic_plug};
        if (s.contains("озвуч") || s.contains("tts") || s.contains("голос"))
            return new int[]{R.drawable.ic_file_text, R.drawable.ic_speaker};
        if (s.contains("микрофон") || s.contains("распозна") || s.contains("stt")
                || s.contains("джарвис"))
            return new int[]{R.drawable.ic_mic, R.drawable.ic_ear};
        if (s.contains("api") || s.contains("ключ") || s.contains("токен")
                || s.contains("авторизац"))
            return new int[]{R.drawable.ic_key, R.drawable.ic_shield};
        if (s.contains("тема") || s.contains("стекл") || s.contains("анимац"))
            return new int[]{R.drawable.ic_palette, R.drawable.ic_eye};
        if (s.contains("музык") || s.contains("песн"))
            return new int[]{R.drawable.ic_music, R.drawable.ic_play};
        if (s.contains("батаре") || s.contains("фон") || s.contains("автозапуск"))
            return new int[]{R.drawable.ic_battery, R.drawable.ic_check};
        return new int[]{R.drawable.ic_sliders, R.drawable.ic_check};
    }

    /** Each topic has a different two-node illustration: settings, search, speech, keys, devices, etc. */
    private static String[] diagramLabels(String title, String line, int page) {
        String s = (title + " " + line).toLowerCase(java.util.Locale.ROOT);
        if (s.contains("поиск") || s.contains("search") || s.contains("exa") || s.contains("tavily"))
            return new String[]{"Запрос", page == 0 ? "Источники" : "Ссылки"};
        if (s.contains("озвуч") || s.contains("голос") || s.contains("tts"))
            return new String[]{"Текст", "Звук"};
        if (s.contains("микрофон") || s.contains("распозна") || s.contains("stt"))
            return new String[]{"Речь", "Команда"};
        if (s.contains("api") || s.contains("ключ") || s.contains("токен"))
            return new String[]{"Ключ", "Проверка"};
        if (s.contains("mcp") || s.contains("сервер") || s.contains("мост"))
            return new String[]{"Агент", "Инструмент"};
        if (s.contains("анимац") || s.contains("стекл") || s.contains("тем"))
            return new String[]{"Настройка", "Вид"};
        return new String[]{"Выбор", "Результат"};
    }
}
