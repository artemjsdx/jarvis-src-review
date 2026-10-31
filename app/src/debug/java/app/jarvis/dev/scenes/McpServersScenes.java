package app.jarvis.dev.scenes;

import app.jarvis.core.mcp.client.ServerConfig;
import app.jarvis.dev.PreviewHost;
import app.jarvis.dev.PreviewKit;
import app.jarvis.rt.Jarvis;
import app.jarvis.ui.Ui;
import app.jarvis.ui.settings.McpServersPage;
import android.view.View;
import android.widget.FrameLayout;

/**
 * "Внешние MCP-серверы" page in dark and light: empty list, three servers with mixed health (cached tools, refused
 * connection, disabled), the server screen and the add sheet. Servers are written to the preview's own hub file.
 */
public final class McpServersScenes {
    public static void render() throws Exception {
        Jarvis j = PreviewKit.jarvis();
        PreviewKit.shell("settings");
        for (boolean dark : new boolean[]{true, false}) {
            String tag = dark ? "dark" : "light";
            Ui.init(PreviewHost.ctx, dark);
            McpServersPage empty = new McpServersPage(PreviewKit.host());
            PreviewHost.render(frame(empty), "mcpsrv_empty_" + tag, 392, 820);
        }
        seed(j);
        for (boolean dark : new boolean[]{true, false}) {
            String tag = dark ? "dark" : "light";
            Ui.init(PreviewHost.ctx, dark);
            McpServersPage list = new McpServersPage(PreviewKit.host());
            list.onShow();
            PreviewHost.pump(400);
            PreviewHost.render(frame(list), "mcpsrv_list_" + tag, 392, 820);
            list.onHide();
            McpServersPage detail = new McpServersPage(PreviewKit.host());
            detail.showDetail("docs-demo");
            detail.onShow();
            PreviewHost.pump(400);
            PreviewHost.render(frame(detail), "mcpsrv_detail_" + tag, 392, 820);
            detail.onHide();
            McpServersPage sheet = new McpServersPage(PreviewKit.host());
            sheet.onShow();
            sheet.openEditor(null, true);
            PreviewHost.pump(1500);
            PreviewHost.render(PreviewKit.host().overlays(), "mcpsrv_sheet_" + tag, 392, 820);
            sheet.onHide();
        }
    }

    private static View frame(McpServersPage p) {
        FrameLayout f = new FrameLayout(PreviewHost.ctx);
        f.setBackgroundColor(Ui.P.bg0);
        f.addView(p.view(), new FrameLayout.LayoutParams(-1, -1));
        p.onShow();
        PreviewHost.pump(300);
        return f;
    }

    /** Three servers: cached tools and idle, refused connection (error), and a disabled one. */
    private static void seed(Jarvis j) throws Exception {
        ServerConfig idle = new ServerConfig();
        idle.id = "docs-demo";
        idle.name = "Документы";
        idle.url = "http://127.0.0.1:9/mcp";
        idle.timeoutMs = 2000;
        idle.tools.add(new app.jarvis.core.mcp.client.RemoteTool("search_docs", "Ищет по документации проекта", null, true));
        idle.tools.add(new app.jarvis.core.mcp.client.RemoteTool("save_note", "Сохраняет заметку", null, false));
        j.mcp.hub().save(idle, null);

        ServerConfig failing = new ServerConfig();
        failing.id = "telegram";
        failing.name = "Telegram (через Termux)";
        failing.url = "http://127.0.0.1:8830/mcp";
        failing.timeoutMs = 2000;
        j.mcp.hub().save(failing, null);
        try {
            j.mcp.hub().testServer("telegram", new app.jarvis.core.util.CancelToken());
        } catch (app.jarvis.core.mcp.client.McpException expected) {
            // the error state is the point of this scene
        }

        ServerConfig off = new ServerConfig();
        off.id = "notes";
        off.name = "Заметки";
        off.url = "http://127.0.0.1:9/sse";
        off.enabled = false;
        j.mcp.hub().save(off, null);
    }
}
