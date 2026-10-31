package app.jarvis.dev.scenes;

import android.view.View;
import android.widget.FrameLayout;
import app.jarvis.core.telegram.TgAccount;
import app.jarvis.core.telegram.TgRights;
import app.jarvis.dev.PreviewHost;
import app.jarvis.dev.PreviewKit;
import app.jarvis.ui.Ui;
import app.jarvis.ui.settings.TelegramPage;

/**
 * "Telegram-аккаунты" page in dark and light: helper not running, helper running without accounts, two accounts with
 * the server running, and the add-account sheet at its three steps. Every state is drawn from a hand-built snapshot.
 */
public final class TelegramScenes {
    public static void render() throws Exception {
        PreviewKit.jarvis();
        PreviewKit.shell("settings");
        for (boolean dark : new boolean[]{true, false}) {
            String tag = dark ? "dark" : "light";
            Ui.init(PreviewHost.ctx, dark);

            TelegramPage.Snapshot down = new TelegramPage.Snapshot();
            down.helperProblem = "Помощник входа не отвечает. В Termux выполните: bash ~/tg-mcp/bin/run.sh helper-start";
            TelegramPage empty = new TelegramPage(PreviewKit.host());
            empty.showSnapshot(down);
            PreviewHost.render(frame(empty), "telegram_empty_" + tag, 392, 820);

            TelegramPage.Snapshot none = new TelegramPage.Snapshot();
            none.helperUp = true;
            none.tokenSaved = true;
            TelegramPage noAccounts = new TelegramPage(PreviewKit.host());
            noAccounts.showSnapshot(none);
            PreviewHost.render(frame(noAccounts), "telegram_noaccounts_" + tag, 392, 820);

            TelegramPage.Snapshot two = new TelegramPage.Snapshot();
            two.helperUp = true;
            two.tokenSaved = true;
            two.serverRunning = true;
            two.portOpen = true;
            two.mcpOk = true;
            two.entry = true;
            two.rights = TgRights.SEND;
            two.accounts.add(new TgAccount("owner", "Иван Петров", "ivan", "4567", "2026-10-30T10:00:00Z"));
            two.accounts.add(new TgAccount("work", "Рабочий", "", "1180", "2026-10-30T10:05:00Z"));
            TelegramPage list = new TelegramPage(PreviewKit.host());
            list.showSnapshot(two);
            PreviewHost.render(frame(list), "telegram_two_" + tag, 392, 820);

            for (int step = 0; step < 3; step++) {
                PreviewKit.host().overlays().dismissAll();
                TelegramPage add = new TelegramPage(PreviewKit.host());
                add.showSnapshot(two);
                add.previewAddStep(step);
                PreviewHost.pump(1500);
                PreviewHost.render(PreviewKit.host().overlays(), "telegram_add" + step + "_" + tag, 392, 820);
                PreviewKit.host().overlays().dismissAll();
            }
        }
    }

    private static View frame(TelegramPage p) {
        FrameLayout f = new FrameLayout(PreviewHost.ctx);
        f.setBackgroundColor(Ui.P.bg0);
        f.addView(p.view(), new FrameLayout.LayoutParams(-1, -1));
        p.onShow();
        PreviewHost.pump(300);
        return f;
    }
}
