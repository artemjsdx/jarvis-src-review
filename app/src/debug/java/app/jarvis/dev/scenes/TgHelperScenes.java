package app.jarvis.dev.scenes;

import app.jarvis.core.telegram.TgReply;
import app.jarvis.dev.PreviewKit;
import app.jarvis.rt.Jarvis;
import app.jarvis.rt.TgHelper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Live check of the Telegram helper client against the running login helper on this phone. Prints states only; the
 * token is read from ~/tg-mcp/helper.token and is never printed. Read-only calls plus calls that fail before Telegram.
 */
public final class TgHelperScenes {
    public static void render() throws Exception {
        Jarvis j = PreviewKit.jarvis();
        TgHelper h = new TgHelper(j);
        System.out.println("[tg] health ok=" + h.health().ok);
        System.out.println("[tg] token saved before=" + h.hasToken());
        String token = new String(Files.readAllBytes(Paths.get("/data/data/com.termux/files/home/tg-mcp/helper.token")),
            StandardCharsets.UTF_8).trim();
        h.saveToken(token);
        System.out.println("[tg] token saved after=" + h.hasToken());
        TgReply st = h.status();
        System.out.println("[tg] status ok=" + st.ok + " helper_port_open=" + st.json.optBoolean("helper_port_open", false)
            + " version=" + st.json.optString("pinned_version", "?") + " env_mode=" + st.json.optString("env_mode", "?"));
        TgReply acc = h.accounts();
        System.out.println("[tg] accounts ok=" + acc.ok + " rights=" + acc.json.optString("rights", "?")
            + " count=" + acc.json.optJSONArray("accounts").length());
        TgReply bad = h.importAccount("probe", 1, "00000000000000000000000000000000",
            new org.json.JSONObject().put("string_session", "nope"));
        System.out.println("[tg] import bad code=" + bad.code);
        System.out.println("[tg] check missing code=" + h.check("no_such_label").code);
        System.out.println("[tg] qr image null=" + (h.qrImage() == null));
        h.saveToken("");
        System.out.println("[tg] token cleared=" + !h.hasToken());
        System.out.println("[tg] no token code=" + h.status().code);
    }
}
