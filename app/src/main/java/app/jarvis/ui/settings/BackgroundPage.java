package app.jarvis.ui.settings;

import android.app.Activity;
import android.content.Intent;
import android.os.PowerManager;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.data.Settings;
import app.jarvis.ui.Host;
import app.jarvis.ui.Ui;
import app.jarvis.ui.Widgets;

/**
 * Background reliability: honest status of notification permission and battery optimization, boot reminder switch,
 * OEM autostart help (Realme/ColorOS/Oppo/vivo and friends) with best-effort deep links, and a plain explanation of
 * what Android does and does not allow after reboot.
 */
public class BackgroundPage extends SettingsPage {
    private LinearLayout content;
    private LinearLayout permCard;

    /** Known OEM autostart screens, tried in order (component may not exist on a given build). */
    private static final String[][] AUTOSTART_ACTIVITIES = {
        {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
        {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
        {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
        {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
        {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"},
        {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
        {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
        {"com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"},
    };

    public BackgroundPage(Host host) { super(host); }

    @Override public String title() { return "Фон и батарея"; }

    @Override protected View build() {
        ScrollView sv = Ui.scroll(ctx);
        sv.setFillViewport(true);
        content = Ui.column(ctx);
        content.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(24));

        content.addView(Widgets.sectionTitle(ctx, "Разрешения"));
        permCard = permissionsCard();
        content.addView(permCard);

        content.addView(Widgets.sectionTitle(ctx, "Автозапуск на Realme / ColorOS / Oppo / vivo"));
        content.addView(autostartCard());

        content.addView(Widgets.sectionTitle(ctx, "После перезагрузки телефона"));
        content.addView(rebootCard());

        sv.addView(content);
        return sv;
    }

    private LinearLayout permissionsCard() {
        LinearLayout card = Widgets.card(ctx);
        card.setTag("permCard");
        renderPermissions(card);
        return card;
    }

    /** Rebuilds the whole card: permission rows change after system dialogs. */
    private void renderPermissions(final LinearLayout card) {
        card.removeAllViews();
        boolean mic = host.hasPermission(android.Manifest.permission.RECORD_AUDIO);
        LinearLayout micRow = Widgets.row(ctx, R.drawable.ic_mic, "Микрофон", mic ? "разрешение выдано" : "без него не работает голос",
            Widgets.pill(ctx, mic ? "есть" : "нет", mic ? Widgets.OK : Widgets.WARN), mic ? null : new Runnable() { @Override public void run() {
                host.requestPermission(android.Manifest.permission.RECORD_AUDIO);
            } });
        card.addView(micRow);
        Widgets.divide(card);
        boolean notify = android.os.Build.VERSION.SDK_INT < 33 || host.hasPermission("android.permission.POST_NOTIFICATIONS");
        LinearLayout notifyRow = Widgets.row(ctx, R.drawable.ic_bell, "Уведомления",
            android.os.Build.VERSION.SDK_INT < 33 ? "Android до 13: отдельного разрешения нет" : notify ? "разрешение выдано" : "без него Jarvis не покажет напоминание",
            notify ? Widgets.pill(ctx, android.os.Build.VERSION.SDK_INT < 33 ? "не нужно" : "есть", Widgets.OK) : Ui.button(ctx, "Запросить", 0, Ui.BTN_GLASS, new Runnable() { @Override public void run() {
                host.requestPermission("android.permission.POST_NOTIFICATIONS");
            } }), null);
        card.addView(notifyRow);
        Widgets.divide(card);
        card.addView(Widgets.row(ctx, R.drawable.ic_battery, "Оптимизация батареи", batteryText(), Ui.button(ctx, "Настройки", R.drawable.ic_external, Ui.BTN_GHOST, new Runnable() { @Override public void run() {
            host.openBatterySettings();
        } }), null));
        Widgets.divide(card);
        card.addView(Widgets.row(ctx, R.drawable.ic_bell, "Напоминание после перезагрузки", "Показать уведомление, чтобы включить микрофон",
            Widgets.toggle(ctx, j.settings.getBool(Settings.SVC_BOOT_NOTIFY, true), new Widgets.Toggle.Listener() { @Override public void onChanged(boolean on) {
                j.settings.setBool(Settings.SVC_BOOT_NOTIFY, on);
            } }), null));
    }

    private String batteryText() {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Activity.POWER_SERVICE);
            boolean ignored = pm != null && pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
            return ignored ? "Jarvis исключён: система не будет усыплять фоновую работу" : "оптимизация активна: Android может останавливать Jarvis в фоне — исключите приложение";
        } catch (Throwable t) {
            return "статус неизвестен";
        }
    }

    private LinearLayout autostartCard() {
        LinearLayout card = Widgets.card(ctx);
        LinearLayout pad = Ui.column(ctx);
        pad.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12));
        TextView body = PageUi.caption(ctx,
            "Системы Realme/ColorOS/Oppo/vivo закрывают фоновые приложения агрессивнее обычного Android. Чтобы Jarvis переживал закрытие и работал в фоне:\n\n"
            + "1. Настройки → Приложения → Jarvis → Автозапуск — включить.\n"
            + "2. Разрешение «Фоновый запуск» (Background activity) — разрешить.\n"
            + "3. Недавние приложения: зафиксируйте Jarvis (замочек на карточке).\n"
            + "4. Батарея → Jarvis → «Не ограничивать».");
        pad.addView(body);
        pad.addView(Ui.button(ctx, "Открыть автозапуск (попытка)", R.drawable.ic_settings, Ui.BTN_GLASS, new Runnable() { @Override public void run() { tryAutostartScreen(); } }),
            Ui.lpm(-1, -2, 0, Ui.dp(10), 0, 0));
        pad.addView(PageUi.note(ctx, "Кнопка пробует известные экраны производителя. Если ни один не открылся — пройдите шаги вручную."), Ui.lpm(-2, -2, 0, Ui.dp(6), 0, 0));
        card.addView(pad);
        return card;
    }

    private void tryAutostartScreen() {
        Activity a = host.activity();
        if (a == null) { host.overlays().toast("Доступно только в приложении, не в предпросмотре", 2); return; }
        for (String[] comp : AUTOSTART_ACTIVITIES) {
            try {
                Intent i = new Intent();
                i.setClassName(comp[0], comp[1]);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                a.startActivity(i);
                host.overlays().toast("Открылось: " + comp[0], 0);
                return;
            } catch (Throwable ignored) { }
        }
        host.overlays().toast("Не удалось открыть настройки автозапуска — откройте их вручную по шагам выше", 2);
    }

    private LinearLayout rebootCard() {
        LinearLayout card = Widgets.card(ctx);
        LinearLayout pad = Ui.column(ctx);
        pad.setPadding(Ui.dp(16), Ui.dp(12), Ui.dp(16), Ui.dp(12));
        pad.addView(PageUi.caption(ctx,
            "Честно о возможностях Android:\n\n"
            + "• Микрофонное слушание после перезагрузки запустить нельзя: система не разрешает фоновым приложениям включать микрофон без открытого экрана. Это защита приватности, обходить её мы не будем.\n\n"
            + "• Что Jarvis может: показать уведомление «Я перезапустился» (переключатель выше). Откройте его — микрофон включится одним касанием.\n\n"
            + "• Озвучивание и музыка тоже стартуют только после открытия приложения."));
        card.addView(pad);
        return card;
    }

    @Override public void onShow() {
        if (permCard != null) renderPermissions(permCard);
    }

    @Override public void onPermissionResult(String permission, boolean granted) {
        if (permCard != null) renderPermissions(permCard);
        host.overlays().toast(granted ? "Разрешение выдано" : "Разрешение не выдано", granted ? 1 : 2);
    }
}
