package app.jarvis.dev.scenes;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.dev.PreviewHost;
import app.jarvis.ui.Backdrop;
import app.jarvis.ui.GlassDrawable;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Design-system gallery: every token-level component in dark and light, plus all icons with their names. */
public final class UiKitGallery {
    private UiKitGallery() { }

    public static void render() throws Exception {
        for (boolean dark : new boolean[]{true, false}) {
            String tag = dark ? "dark" : "light";
            Ui.setDark(dark);
            Ui.refreshMotion(PreviewHost.ctx, "full");
            renderPart(page(dark, 1), "kit_gallery_" + tag + "_1");
            renderPart(page(dark, 2), "kit_gallery_" + tag + "_2");
        }
    }

    private static void renderPart(View root, String name) throws Exception {
        int w = PreviewHost.px(392);
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int hDp = Math.round(root.getMeasuredHeight() / PreviewHost.density);
        PreviewHost.render(root, name, 392, hDp);
    }

    private static FrameLayout page(boolean dark, int part) {
        Context c = PreviewHost.ctx;
        FrameLayout root = new FrameLayout(c);
        root.addView(new Backdrop(c), new FrameLayout.LayoutParams(-1, -1));
        LinearLayout col = Ui.column(c);
        col.setPadding(Ui.dp(Ui.S.LG), Ui.dp(Ui.S.XL), Ui.dp(Ui.S.LG), Ui.dp(Ui.S.XL));
        if (part == 1) {
            col.addView(Ui.text(c, (dark ? "Тёмная" : "Светлая") + " тема · 1 / 2", Ui.TITLE));
            section(col, "Типографика");
            col.addView(Ui.text(c, "Заголовок экрана", Ui.TITLE));
            col.addView(Ui.text(c, "Основной текст: ассистент отвечает и выполняет команды.", Ui.BODY), Ui.lpm(-1, -2, 0, Ui.S.XS, 0, 0));
            col.addView(Ui.text(c, "Подпись: вторичная информация, время, статус", Ui.CAPTION), Ui.lpm(-1, -2, 0, Ui.S.XS, 0, 0));
            col.addView(Ui.text(c, "mono 12.5 · code_block()", Ui.MONO), Ui.lpm(-1, -2, 0, Ui.S.XS, 0, 0));

            section(col, "Кнопки");
            LinearLayout r1 = Ui.row(c);
            r1.addView(Ui.button(c, "Основная", R.drawable.ic_send, Ui.BTN_PRIMARY, null), Ui.lpm(-2, -2, 0, 0, Ui.S.SM, 0));
            r1.addView(Ui.button(c, "Стекло", R.drawable.ic_sliders, Ui.BTN_GLASS, null));
            col.addView(r1);
            LinearLayout r2 = Ui.row(c);
            r2.addView(Ui.button(c, "Текст", 0, Ui.BTN_GHOST, null), Ui.lpm(-2, -2, 0, 0, Ui.S.SM, 0));
            r2.addView(Ui.button(c, "Удалить", R.drawable.ic_trash, Ui.BTN_DANGER, null), Ui.lpm(-2, -2, 0, 0, Ui.S.SM, 0));
            View off = Ui.button(c, "Недоступна", 0, Ui.BTN_GLASS, null);
            off.setEnabled(false);
            off.setAlpha(0.4f);
            r2.addView(off);
            col.addView(r2, Ui.lpm(-1, -2, 0, Ui.S.SM, 0, 0));

            section(col, "Поля");
            col.addView(caption(c, "Обычное"));
            col.addView(Widgets.fieldBox(c, Widgets.field(c, "Адрес API", "https://api.example.com/v1", false, false)));
            col.addView(caption(c, "Фокус (акцентная рамка)"));
            col.addView(focused(c, "Введите запрос"));
            col.addView(caption(c, "Ошибка"));
            col.addView(error(c, "sk-…", "Ключ не принят провайдером"));

            section(col, "Переключатели и сегменты");
            LinearLayout sw = Ui.row(c);
            sw.addView(Widgets.toggle(c, true, null), Ui.lpm(-2, -2, 0, 0, Ui.S.MD, 0));
            sw.addView(Widgets.toggle(c, false, null));
            sw.addView(Ui.text(c, "вкл / выкл", Ui.CAPTION, Ui.P.text2), Ui.lpm(-2, -2, Ui.S.MD, 0, 0, 0));
            col.addView(sw);
            col.addView(Widgets.segmented(c, new String[]{"Полная", "Сниженная", "Выкл"}, 0, null), Ui.lpm(-1, -2, 0, Ui.S.SM, 0, 0));
        } else {
            section(col, "Карточки и строки");
            LinearLayout card = Widgets.card(c);
            card.addView(Widgets.row(c, R.drawable.ic_key, "API-ключ", "sk_f…9b4 · 35 симв.", null, null));
            Widgets.divide(card);
            card.addView(Widgets.row(c, R.drawable.ic_globe, "Прокси", "не используется", Widgets.pill(c, "выкл", Widgets.MUTED), null));
            Widgets.divide(card);
            card.addView(Widgets.row(c, R.drawable.ic_brain, "Что ассистент запомнил", "записей: 1", null, null));
            col.addView(card);
            LinearLayout card2 = Widgets.card(c);
            card2.addView(Widgets.row(c, R.drawable.ic_palette, "Тема", "как в системе", null, new Runnable() { @Override public void run() { } }));
            col.addView(card2);

            section(col, "Чипы и статусы");
            LinearLayout chips = Ui.row(c);
            chips.addView(Widgets.pill(c, "работает", Widgets.OK), Ui.lpm(-2, -2, 0, 0, Ui.S.XS, 0));
            chips.addView(Widgets.pill(c, "лимит", Widgets.WARN), Ui.lpm(-2, -2, 0, 0, Ui.S.XS, 0));
            chips.addView(Widgets.pill(c, "ошибка", Widgets.ERR), Ui.lpm(-2, -2, 0, 0, Ui.S.XS, 0));
            chips.addView(Widgets.pill(c, "инфо", Widgets.INFO));
            col.addView(chips);

            section(col, "Пустое состояние");
            col.addView(Widgets.empty(c, R.drawable.ic_folder_open, "Здесь пока пусто", "Пришлите файл в чат или скачайте командой"));

            section(col, "Диалог, шторка, уведомление");
            col.addView(panel(dark, Ui.S.RADIUS_CARD * 1f, dialogContent(c)), Ui.lpm(-1, -2, 0, 0, 0, Ui.S.MD));
            col.addView(panel(dark, 26f, sheetContent(c)), Ui.lpm(-1, -2, 0, 0, 0, Ui.S.MD));
            col.addView(toast(c, dark), Ui.lpm(-2, -2, 0, 0, 0, 0));
        }
        if (part == 2) iconGrid(col, c);
        root.addView(col, new FrameLayout.LayoutParams(-1, -2));
        return root;
    }

    private static void section(LinearLayout col, String title) { col.addView(Widgets.sectionTitle(PreviewHost.ctx, title)); }

    private static TextView caption(Context c, String t) { return Ui.text(c, t, Ui.CAPTION, Ui.P.text2); }

    private static View focused(Context c, String hint) {
        FrameLayout box = new FrameLayout(c);
        box.setBackground(Ui.outlined(Ui.S.RADIUS_FIELD, Ui.P.field, Ui.P.accent));
        EditText e = Widgets.field(c, hint, "", false, false);
        box.addView(e, new FrameLayout.LayoutParams(-1, -2));
        return box;
    }

    private static View error(Context c, String value, String message) {
        LinearLayout col = Ui.column(c);
        FrameLayout box = new FrameLayout(c);
        box.setBackground(Ui.outlined(Ui.S.RADIUS_FIELD, Ui.P.field, Ui.P.err));
        box.addView(Widgets.field(c, "API-ключ", value, false, true), new FrameLayout.LayoutParams(-1, -2));
        col.addView(box);
        col.addView(Ui.text(c, message, Ui.CAPTION, Ui.P.err), Ui.lpm(-2, -2, Ui.S.XS, Ui.S.XS, 0, 0));
        return col;
    }

    private static View panel(boolean dark, float rDp, View content) {
        FrameLayout f = new FrameLayout(PreviewHost.ctx);
        Drawable base = Ui.rect(dark ? 0xF2111019 : 0xF5FBFAFF, rDp);
        f.setBackground(new LayerDrawable(new Drawable[]{base, new GlassDrawable(rDp, true)}));
        f.addView(content);
        return f;
    }

    private static View dialogContent(Context c) {
        LinearLayout box = Ui.column(c);
        box.setPadding(Ui.dp(Ui.S.LG + Ui.S.XS), Ui.dp(Ui.S.LG + Ui.S.XS), Ui.dp(Ui.S.LG + Ui.S.XS), Ui.dp(Ui.S.LG));
        box.addView(Ui.text(c, "Удалить заметку?", Ui.BODY, Ui.P.text));
        box.addView(Ui.text(c, "Заметку нельзя будет восстановить.", Ui.CAPTION, Ui.P.text2), Ui.lpm(-2, -2, 0, Ui.S.SM, 0, 0));
        LinearLayout row = Ui.row(c);
        row.addView(Ui.button(c, "Отмена", 0, Ui.BTN_GHOST, null), Ui.lpm(-2, -2, 0, 0, Ui.S.SM, 0));
        row.addView(Ui.button(c, "Удалить", 0, Ui.BTN_DANGER, null));
        box.addView(row, Ui.lpm(-1, -2, 0, Ui.S.LG, 0, 0));
        return box;
    }

    private static View sheetContent(Context c) {
        LinearLayout box = Ui.column(c);
        box.setPadding(Ui.dp(Ui.S.LG + Ui.S.XS), Ui.dp(Ui.S.SM), Ui.dp(Ui.S.LG + Ui.S.XS), Ui.dp(Ui.S.LG));
        box.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        View handle = new View(c);
        handle.setBackground(Ui.rect(Ui.P.strokeHi, 3));
        box.addView(handle, Ui.lpm(Ui.dp(36), Ui.dp(4), 0, 0, 0, Ui.S.MD));
        box.addView(Widgets.row(c, R.drawable.ic_brain, "Модель", "openai/gpt-x · 128k", null, null));
        return box;
    }

    private static View toast(Context c, boolean dark) {
        LinearLayout row = Ui.row(c);
        row.setPadding(Ui.dp(14), Ui.dp(11), Ui.dp(16), Ui.dp(11));
        ImageView ic = Ui.icon(c, R.drawable.ic_ok, 18, Ui.P.ok);
        row.addView(ic);
        row.addView(Ui.text(c, "Настройки сохранены", Ui.BODY), Ui.lpm(-2, -2, 10, 0, 0, 0));
        row.setBackground(new LayerDrawable(new Drawable[]{Ui.rect(dark ? 0xF2111019 : 0xF5FBFAFF, 18), new GlassDrawable(18, true)}));
        return row;
    }

    private static void iconGrid(LinearLayout col, Context c) {
        col.addView(Widgets.sectionTitle(c, "Иконки (" + iconNames().size() + ")"));
        List<String> names = iconNames();
        int perRow = 5;
        LinearLayout row = null;
        for (int i = 0; i < names.size(); i++) {
            if (i % perRow == 0) {
                row = Ui.row(c);
                col.addView(row, Ui.lpm(-1, -2, 0, 0, 0, Ui.S.SM));
            }
            String n = names.get(i);
            LinearLayout cell = Ui.column(c);
            cell.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            cell.setPadding(0, Ui.dp(Ui.S.SM), 0, Ui.dp(Ui.S.SM));
            cell.setBackground(Ui.outlined(Ui.S.RADIUS_CHIP, Ui.P.field, Ui.P.strokeLo));
            int id = resId(n);
            cell.addView(Ui.icon(c, id, 24, Ui.P.text), Ui.lpm(Ui.dp(24), Ui.dp(24), 0, 0, 0, Ui.S.XS));
            TextView label = Ui.text(c, n.substring(3), Ui.CAPTION, Ui.P.text3);
            label.setTextSize(8.5f);
            label.setGravity(android.view.Gravity.CENTER);
            label.setSingleLine(true);
            cell.addView(label, Ui.lpm(-2, -2, 0, 0, 0, 0));
            row.addView(cell, Ui.lpwm(1f, 2, 0, 2, 0));
        }
        if (names.size() % perRow != 0) {
            for (int k = names.size() % perRow; k < perRow; k++) row.addView(Ui.space(c, 0, 0), Ui.lpwm(1f, 2, 0, 2, 0));
        }
    }

    private static int resId(String name) {
        try { return R.drawable.class.getField(name).getInt(null); }
        catch (Exception e) { throw new IllegalStateException("no drawable " + name, e); }
    }

    private static List<String> iconNames() {
        List<String> out = new ArrayList<>();
        for (Field f : R.drawable.class.getFields()) {
            String n = f.getName();
            if (n.startsWith("ic_") && !n.startsWith("ic_launcher") && !n.equals("ic_notify")) out.add(n);
        }
        java.util.Collections.sort(out);
        return out;
    }
}
