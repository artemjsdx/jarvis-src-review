package app.jarvis.dev.checks;

import android.os.Looper;
import app.jarvis.core.llm.ChatMessage;
import app.jarvis.core.log.Journal;
import app.jarvis.core.util.Redactor;
import app.jarvis.data.ChatItem;
import app.jarvis.data.ChatStore;
import app.jarvis.data.Db;
import app.jarvis.data.Secrets;
import app.jarvis.data.Settings;
import app.jarvis.data.SqlJournal;
import java.io.File;
import java.io.FileInputStream;
import java.util.List;

/** Real-runtime check of the persistence layer: settings, encrypted secrets, chat store incl. crash recovery, journal. */
public final class DataCheck {
    static int fails;

    static void check(boolean ok, String what) { System.out.println((ok ? "  ok   " : "  FAIL ") + what); if (!ok) fails++; System.out.flush(); }

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) { System.out.println("CRASH " + t); t.printStackTrace(System.out); fails++; }
        System.out.println(fails == 0 ? "DATACHECK OK" : "DATACHECK FAILURES: " + fails);
        System.out.flush();
        System.exit(fails == 0 ? 0 : 1);
    }

    static void run() throws Exception {
        Looper.prepareMainLooper();
        File dir = new File(System.getenv("TMPDIR"), "datacheck-" + System.nanoTime());
        dir.mkdirs();

        System.out.println("== Settings");
        Settings s = new Settings(new File(dir, "settings.json"));
        s.set("a.b", "x"); s.setBool("flag", true); s.set("n", "42"); s.set("f", "0,75"); s.setList("l", java.util.Arrays.asList("a", "b", "c"));
        Settings s2 = new Settings(new File(dir, "settings.json"));
        check("x".equals(s2.get("a.b")) && s2.getBool("flag", false) && s2.getInt("n", 0) == 42 && Math.abs(s2.getFloat("f", 0) - 0.75f) < 1e-6 && s2.getList("l").size() == 3, "persist + typed getters (comma decimal)");
        check(s2.getInt("missing", 7) == 7 && !s2.has("missing"), "defaults");

        System.out.println("== Secrets (Android Keystore)");
        try { // zygote registers the provider in real app processes; a bare app_process does not
            Class.forName("android.security.keystore2.AndroidKeyStoreProvider").getMethod("install").invoke(null);
            System.out.println("  keystore provider installed manually");
        } catch (Throwable t) { System.out.println("  keystore provider install failed: " + t + " / " + t.getCause()); }
        Secrets sec = new Secrets(new File(dir, "secrets.json"));
        System.out.println("  keystore encrypted=" + sec.isEncrypted() + (sec.isEncrypted() ? "" : " problem=" + sec.keystoreProblem()));
        sec.set("key.test", "sk-SECRET-VALUE-1234567890");
        Secrets sec2 = new Secrets(new File(dir, "secrets.json"));
        check("sk-SECRET-VALUE-1234567890".equals(sec2.get("key.test")), "round trip through a new instance");
        String raw = new String(java.nio.file.Files.readAllBytes(new File(dir, "secrets.json").toPath()), "UTF-8");
        check(!raw.contains("SECRET-VALUE"), "plaintext not present in the file");
        check(Redactor.redact("x sk-SECRET-VALUE-1234567890 y").indexOf("SECRET") < 0, "registered secret is redacted from log text");
        check(sec2.importJson("{\"key.imp\":\"v1234567\",\"_comment\":\"skip\"}") == 1 && sec2.has("key.imp"), "import json");

        System.out.println("== ChatStore");
        Db db = Db.open(new File(dir, "j.db"));
        ChatStore cs = new ChatStore(db);
        long conv = cs.currentConversation();
        ChatItem u = new ChatItem(); u.convId = conv; u.kind = ChatItem.Kind.USER; u.text = "создай заметку"; u.runId = "r1"; cs.add(u);
        ChatItem as = new ChatItem(); as.convId = conv; as.kind = ChatItem.Kind.ASSISTANT; as.runId = "r1";
        as.toolCallsJson = "[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"fs_write\",\"arguments\":\"{}\"}}]"; cs.add(as);
        ChatItem t = new ChatItem(); t.convId = conv; t.kind = ChatItem.Kind.TOOL; t.runId = "r1"; t.callId = "c1"; t.toolName = "fs_write"; t.toolStatus = ChatItem.ToolStatus.RUNNING; cs.add(t);
        List<ChatMessage> h1 = cs.history(conv, 100000);
        check(h1.size() == 3 && h1.get(1).hasToolCalls() && h1.get(2).role.equals("tool") && h1.get(2).content.contains("результат неизвестен"), "RUNNING tool: the model sees a paired 'result unknown', never a dangling call");
        int n = cs.recoverInterrupted();
        check(n == 1, "recoverInterrupted closed 1 stuck tool call");
        ChatItem back = cs.find(t.seq);
        check(back.toolStatus == ChatItem.ToolStatus.INTERRUPTED && !back.ok && back.text.contains("Результат неизвестен"), "stuck tool becomes INTERRUPTED with an honest result");
        List<ChatMessage> h2 = cs.history(conv, 100000);
        check(h2.size() == 3 && h2.get(1).hasToolCalls() && h2.get(2).role.equals("tool") && h2.get(2).toolCallId.equals("c1"), "history is a consistent assistant(tool_calls)+tool pair after recovery");
        long t0 = System.nanoTime();
        for (int i = 0; i < 300; i++) { ChatItem x = new ChatItem(); x.convId = conv; x.kind = ChatItem.Kind.USER; x.text = "сообщение " + i; cs.add(x); }
        long ms = (System.nanoTime() - t0) / 1000000;
        check(cs.items(conv, 0, 50).size() == 50 && ms < 3000, "300 inserts in " + ms + " ms; paging works");
        check(cs.history(conv, 2000).size() < 303, "history respects the size budget");

        System.out.println("== SqlJournal");
        SqlJournal j = new SqlJournal(db);
        Redactor.addSecret("sk-SECRET-VALUE-1234567890");
        for (int i = 0; i < 400; i++) j.log(i % 2 == 0 ? Journal.Cat.TTS : Journal.Cat.LLM, i % 50 == 0 ? Journal.Level.ERROR : Journal.Level.INFO, "событие " + i + (i == 7 ? " ключ sk-SECRET-VALUE-1234567890" : ""), "run1", null, i);
        Thread.sleep(1500);
        check(j.count() >= 400, "rows persisted: " + j.count());
        check(j.query("tts", null, null, 0, 1000).size() == 200, "filter by category");
        check(j.query(null, Journal.Level.ERROR, null, 0, 1000).size() == 8, "filter by level");
        List<SqlJournal.Row> found = j.query(null, null, "событие 7", 0, 10);
        check(!found.isEmpty() && !found.get(0).message.contains("SECRET-VALUE"), "secret redacted before storage");
        File ex = new File(dir, "export.txt");
        int rows = j.export(ex, null, null, 10000);
        check(rows >= 400 && ex.length() > 5000, "export wrote " + rows + " rows, " + ex.length() + " bytes");
        j.setMaxRows(1000);
        j.close();
        System.out.println("== done in " + dir);
    }
}
