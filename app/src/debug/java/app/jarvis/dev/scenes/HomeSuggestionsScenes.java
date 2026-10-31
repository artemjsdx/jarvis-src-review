package app.jarvis.dev.scenes;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.core.agent.SuggestionPolicy;
import app.jarvis.core.agent.SuggestionTap;
import app.jarvis.dev.PreviewHost;
import app.jarvis.ui.Ui;
import app.jarvis.ui.home.SuggestionTray;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Home suggestion chips: shimmer, short and long chips, offline defaults, and the provider pill, dark and light. */
public final class HomeSuggestionsScenes {
    private HomeSuggestionsScenes() { }

    public static void render() throws Exception {
        Context c = PreviewHost.ctx;
        List<String> none = new ArrayList<>();
        List<String> shortChips = Arrays.asList("Что нового?", "Включи музыку", "Какие дела на завтра?");
        List<String> longChips = Arrays.asList("Напомни про созвон с командой в пятницу в 15:00",
            "Сравни мои запросы о кошке за прошлую неделю", "Открой журнал за вчера и покажи ошибки");
        try {
            for (boolean dark : new boolean[]{true, false}) {
                Ui.init(c, dark);
                String tag = dark ? "dark" : "light";
                tile("suggest_" + tag + "_shimmer", none, false);
                tile("suggest_" + tag + "_short", shortChips, false);
                tile("suggest_" + tag + "_long", longChips, false);
                tile("suggest_" + tag + "_offline", SuggestionPolicy.offline(9), true);
            }
        } finally {
            Ui.init(c, true);
        }
    }

    private static void tile(String name, List<String> items, boolean withPill) throws Exception {
        Context c = PreviewHost.ctx;
        FrameLayout root = new FrameLayout(c);
        root.setBackgroundColor(Ui.P.bg0);
        LinearLayout col = Ui.column(c);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(Ui.dp(18), Ui.dp(12), Ui.dp(18), Ui.dp(12));
        if (withPill) col.addView(pill(c), Ui.lpm(Ui.dp(240), -2, 0, 0, 0, 0));
        SuggestionTray tray = new SuggestionTray(c, new SuggestionTap.Sink() {
            @Override public void submit(String text, String source) { }
        });
        col.addView(tray, Ui.lpm(-1, -2, 0, 8, 0, 0));
        root.addView(col, new FrameLayout.LayoutParams(-1, -2));
        tray.show(items);
        PreviewHost.pump(420);
        PreviewHost.render(root, name, 392, 200);
    }

    private static TextView pill(Context c) {
        TextView v = Ui.text(c, "OpenRouter · deepseek/deepseek-v4.1-flash-1017", Ui.CAPTION, Ui.P.text2);
        v.setTypeface(Ui.medium);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        v.setPadding(Ui.dp(12), Ui.dp(7), Ui.dp(12), Ui.dp(7));
        v.setBackground(Ui.glass(16));
        return v;
    }
}
