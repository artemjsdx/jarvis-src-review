package app.jarvis.core.telegram;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** A Telegram account as the helper describes it: no session and no full phone number. */
public final class TgAccount {
    public final String label;
    public final String name;
    public final String username;
    public final String phoneTail;
    public final String addedAt;

    public TgAccount(String label, String name, String username, String phoneTail, String addedAt) {
        this.label = label;
        this.name = name;
        this.username = username;
        this.phoneTail = phoneTail;
        this.addedAt = addedAt;
    }

    /** Parses the "accounts" array of GET /accounts. Entries without a label are skipped. */
    public static List<TgAccount> parseList(JSONObject reply) {
        List<TgAccount> out = new ArrayList<>();
        JSONArray arr = reply == null ? null : reply.optJSONArray("accounts");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String label = o.optString("label", "");
            if (label.isEmpty()) continue;
            out.add(new TgAccount(label, o.optString("name", ""), o.optString("username", ""),
                o.optString("phone_tail", ""), o.optString("added_at", "")));
        }
        return out;
    }

    /** Title for a row: the name, else @username, else the label. */
    public String title() {
        if (!name.isEmpty()) return name;
        if (!username.isEmpty()) return "@" + username;
        return label;
    }

    /** Second line: the username and the last digits of the phone, never the full number. */
    public String subtitle() {
        StringBuilder sb = new StringBuilder(label);
        if (!username.isEmpty()) sb.append(" · @").append(username);
        if (!phoneTail.isEmpty()) sb.append(" · …").append(phoneTail);
        return sb.toString();
    }
}
