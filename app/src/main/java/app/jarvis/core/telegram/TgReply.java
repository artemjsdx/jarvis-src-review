package app.jarvis.core.telegram;

import org.json.JSONObject;

/**
 * One answer of the local Telegram login helper ({ok, code, message, technical, state, label, ...}).
 * Parsing never throws: a broken answer becomes a failed reply with a Russian message.
 */
public final class TgReply {
    public final boolean ok;
    public final String code;
    public final String message;
    public final String technical;
    public final String state;
    public final String label;
    public final String url;
    public final int seconds;
    public final JSONObject json;

    private TgReply(JSONObject o) {
        this.json = o;
        this.ok = o.optBoolean("ok", false);
        this.code = o.optString("code", "");
        this.message = o.optString("message", "");
        this.technical = o.optString("technical", "");
        this.state = o.optString("state", "");
        this.label = o.optString("label", "");
        this.url = o.optString("url", "");
        this.seconds = o.optInt("seconds", 0);
    }

    public static TgReply parse(String body) {
        if (body == null || body.isEmpty()) return fail("bad_reply", "Помощник вернул пустой ответ.", "empty");
        try {
            return new TgReply(new JSONObject(body));
        } catch (org.json.JSONException e) {
            return fail("bad_reply", "Помощник ответил непонятно.", "bad_json");
        }
    }

    public static TgReply fail(String code, String message, String technical) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false).put("code", code).put("message", message).put("technical", technical);
        } catch (org.json.JSONException ignored) {
            // constant keys with string values cannot fail
        }
        return new TgReply(o);
    }

    /** The helper is not running or not reachable: the owner has to start it in Termux. */
    public static TgReply unavailable(String technical) {
        return fail("helper_down", "Помощник входа не отвечает. В Termux выполните: bash ~/tg-mcp/bin/run.sh helper-start",
            technical);
    }

    /** Tells the owner what to do next, in Russian, for one of the helper's codes. */
    public String userMessage() {
        if (!message.isEmpty()) return message;
        return ok ? "Готово." : "Не удалось. Повторите позже.";
    }
}
