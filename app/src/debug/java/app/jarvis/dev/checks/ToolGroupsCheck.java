package app.jarvis.dev.checks;

import app.jarvis.core.agent.Tool;
import app.jarvis.core.agent.ToolGroups;
import app.jarvis.core.agent.ToolRouter;
import app.jarvis.core.agent.ToolSelection;
import app.jarvis.core.llm.ToolSpec;
import app.jarvis.data.ChatItem;
import app.jarvis.dev.PreviewHost;
import app.jarvis.dev.PreviewKit;
import app.jarvis.rt.Jarvis;
import java.io.File;
import java.util.List;
import org.json.JSONArray;

/**
 * Tool groups on the REAL registry of a real Jarvis instance (ART): which tools each sample request announces and how many schema
 * characters that is, against announcing everything. Also lists registered tools that nobody placed in a group or in the core.
 * Output lines: ALL / BLOCK all / UNDECIDED / ROW / BLOCK <row> / NEXT / CHECK DONE, tab separated; input for docs/SPEED.md 8a. args: <apk> <outDir>
 */
public final class ToolGroupsCheck {
    private static final String[] TEXTS = {
        "привет как дела", "расскажи анекдот", "что такое фотосинтез", "запомни что я люблю чай", "замолчи",
        "включи музыку", "поставь следующую песню", "что сейчас играет", "какая завтра погода в Гомеле", "найди в интернете рецепт борща",
        "прочитай статью по ссылке https://example.com/a", "создай папку проекты в work", "прочитай заметку про отпуск",
        "запусти python скрипт test.py", "открой ютуб", "сделай скриншот", "забудь что я люблю кофе", "создай навык для утренней рутины",
        "выключи режим обращения", "о чём мы говорили вчера",
    };

    private static int chars(List<ToolSpec> specs) {
        int n = 0;
        for (ToolSpec s : specs) n += s.toJson(false).toString().length();
        return n;
    }

    private static int mcp(Jarvis j, List<ToolSpec> specs) {
        int n = 0;
        for (ToolSpec s : specs) { Tool t = j.tools.get(s.name); if (t != null && "mcp".equals(t.category())) n++; }
        return n;
    }

    /**
     * The periodic full list on the REAL chat store and the real AgentRunner: after k user messages the NEXT request is number k+1 and
     * carries every tool when k+1 is a multiple of tools.fullEvery. Lines: NEXT k, user messages counted by the store, tools announced.
     */
    private static void fullListEveryN(Jarvis j) {
        long conv = j.sessions.newChat();
        j.settings.setBool("tools.groups", true);
        j.settings.set("tools.fullEvery", "5");
        for (int k = 0; k <= 10; k++) {
            if (k > 0) addUser(j, conv, "привет " + k);
            System.out.println("NEXT\tevery=5\t" + k + "\t" + j.chat.userTurns(conv) + "\t" + j.agent.nextRequestTools(conv).size());
        }
        j.settings.set("tools.fullEvery", "1");
        System.out.println("NEXT\tevery=1\t" + j.chat.userTurns(conv) + "\t" + j.chat.userTurns(conv) + "\t" + j.agent.nextRequestTools(conv).size());
        j.settings.set("tools.fullEvery", "0");
        System.out.println("NEXT\tevery=0\t" + j.chat.userTurns(conv) + "\t" + j.chat.userTurns(conv) + "\t" + j.agent.nextRequestTools(conv).size());
        j.settings.setBool("tools.groups", false);
        System.out.println("NEXT\tgroups=off\t" + j.chat.userTurns(conv) + "\t" + j.chat.userTurns(conv) + "\t" + j.agent.nextRequestTools(conv).size());
    }

    private static void addUser(Jarvis j, long conv, String text) {
        ChatItem u = new ChatItem();
        u.convId = conv; u.kind = ChatItem.Kind.USER; u.text = text; u.source = "text"; u.runId = "check";
        j.chat.add(u);
    }

    public static void main(String[] a) {
        try {
            PreviewHost.init(a[0], new File(a[1]));
            Jarvis j = PreviewKit.jarvis();
            List<ToolSpec> all = j.tools.specs();
            System.out.println("ALL\t" + all.size() + "\t" + chars(all) + "\tmcp=" + mcp(j, all));
            JSONArray everything = new JSONArray();
            for (ToolSpec s : all) everything.put(s.toJson(false));
            System.out.println("BLOCK\tall\t" + everything);
            for (String n : j.tools.names()) {
                Tool t = j.tools.get(n);
                if (!ToolGroups.isDecided(n) && !"mcp".equals(t.category())) System.out.println("UNDECIDED\t" + n + "\t" + t.category());
            }
            for (int i = 0; i < TEXTS.length; i++) {
                String text = TEXTS[i];
                ToolRouter.Result r = ToolRouter.route(text, null, null, false);
                List<ToolSpec> shown = new ToolSelection(j.tools, r.groups()).advertise();
                System.out.println("ROW\t" + shown.size() + "\t" + chars(shown) + "\tmcp=" + mcp(j, shown) + "\t" + r.groups() + "\t" + text);
                // the exact tools block the request would carry, for a live prompt_tokens measurement
                JSONArray block = new JSONArray();
                for (ToolSpec s : shown) block.put(s.toJson(false));
                System.out.println("BLOCK\t" + i + "\t" + block);
            }
            fullListEveryN(j);
            System.out.println("CHECK DONE");
        } catch (Throwable t) { System.out.println("CRASH " + t); t.printStackTrace(System.out); }
        System.out.flush();
        System.exit(0);
    }
}
