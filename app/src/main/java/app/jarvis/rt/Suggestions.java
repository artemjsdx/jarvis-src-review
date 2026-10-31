package app.jarvis.rt;

import app.jarvis.core.agent.CallBudget;
import app.jarvis.core.agent.SuggestionCache;
import app.jarvis.core.agent.SuggestionPolicy;
import app.jarvis.core.agent.SuggestionPrompt;
import app.jarvis.core.tools.MemoryStore;
import app.jarvis.core.llm.ChatMessage;
import app.jarvis.core.llm.LlmException;
import app.jarvis.core.llm.LlmListener;
import app.jarvis.core.llm.LlmRequest;
import app.jarvis.core.llm.LlmResult;
import app.jarvis.core.log.Journal;
import app.jarvis.core.util.CancelToken;
import app.jarvis.data.ChatItem;
import app.jarvis.data.ChatStore;
import app.jarvis.media.MusicOut;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Home-screen suggestion chips: reads the latest user messages, asks the fast model for three chips, caches them and
 * reports to the listener on the main thread. All reading and network work runs on one background thread, and at most
 * one refresh is in flight. Without a key, with the model switched off, or past the hourly budget the offline chips are used.
 */
public final class Suggestions {
    public static final String ENABLED = "home.suggestions", MODE = "home.suggestions.mode", CALLS = "home.suggestions.calls";
    public static final String MODE_LLM = "llm", MODE_STATIC = "static";

    public interface Listener { void onSuggestions(List<String> items, boolean fromModel); }

    private static final int RECENT_CHATS = 3, RECENT_LINES = 6;

    private final Jarvis j;
    private final Listener listener;
    private final File file;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "suggestions");
            t.setDaemon(true);
            return t;
        }
    });
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile CancelToken cancel;
    private volatile boolean visible;

    public Suggestions(Jarvis j, Listener listener) {
        this.j = j;
        this.listener = listener;
        this.file = new File(j.home, "suggestions.json");
    }

    /** The home screen came on screen: refresh when the cache is stale or the context changed. Main thread. */
    public void onShown() {
        visible = true;
        refresh();
    }

    /** The home screen went away: cancel a request that is still running. Main thread. */
    public void onHidden() {
        visible = false;
        CancelToken c = cancel;
        if (c != null) c.cancel("home hidden");
    }

    /** An agent run just finished: the conversation changed, so refresh a few seconds later. Main thread. */
    public void afterRun() {
        j.main.postDelayed(new Runnable() {
            @Override public void run() { if (visible) refresh(); }
        }, SuggestionPolicy.AFTER_RUN_MS);
    }

    private void refresh() {
        if (!busy.compareAndSet(false, true)) return;
        final CancelToken token = new CancelToken();
        cancel = token;
        worker.execute(new Runnable() {
            @Override public void run() {
                try {
                    work(token);
                } catch (RuntimeException e) {
                    j.journal.e(Journal.Cat.LLM, "Подсказки: ошибка обновления", e);
                } finally {
                    busy.set(false);
                }
            }
        });
    }

    private void work(CancelToken token) {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (!useModel()) { publish(SuggestionPolicy.offline(hour), false, token); return; }
        long now = System.currentTimeMillis();
        SuggestionPrompt.Input in = collect(hour);
        String hash = SuggestionPrompt.contextHash(in);
        SuggestionCache.Entry cached = readCache();
        if (cached != null) publish(cached.items, true, token);
        if (!SuggestionPolicy.needRefresh(now, cached == null ? 0 : cached.at, cached == null ? null : cached.hash, hash)) return;
        Jarvis.LlmChoice choice = readyChoice();
        if (choice == null) { if (cached == null) publish(SuggestionPolicy.offline(hour), false, token); return; }
        CallBudget budget = new CallBudget(SuggestionPolicy.MAX_CALLS_PER_HOUR, SuggestionPolicy.HOUR_MS).decode(j.settings.get(CALLS));
        if (!budget.tryAcquire(now)) {
            j.journal.log(Journal.Cat.LLM, Journal.Level.DEBUG, "Подсказки: лимит запросов в час исчерпан, показаны прежние", null, null, -1);
            if (cached == null) publish(SuggestionPolicy.offline(hour), false, token);
            return;
        }
        j.settings.set(CALLS, budget.encode());
        List<String> items = ask(choice, in, token);
        if (token.isCancelled()) return;
        if (items.isEmpty()) { if (cached == null) publish(SuggestionPolicy.offline(hour), false, token); return; }
        writeCache(SuggestionCache.encode(items, now, hash));
        publish(items, true, token);
    }

    private boolean useModel() {
        return j.settings.getBool(ENABLED, true) && MODE_LLM.equals(j.settings.get(MODE, MODE_LLM));
    }

    private Jarvis.LlmChoice readyChoice() {
        List<Jarvis.LlmChoice> choices = j.llmChoices();
        if (choices.isEmpty()) return null;
        Jarvis.LlmChoice c = choices.get(0);
        return c.hasKey && !c.model.isEmpty() ? c : null;
    }

    private List<String> ask(Jarvis.LlmChoice choice, SuggestionPrompt.Input in, CancelToken token) {
        LlmRequest req = new LlmRequest();
        req.model = choice.model;
        req.stream = false;
        req.maxTokens = 120;
        req.temperature = 0.8;
        req.reasoning = "off";
        req.realtime = true;
        req.messages.add(ChatMessage.system(SuggestionPrompt.SYSTEM));
        req.messages.add(ChatMessage.user(SuggestionPrompt.user(in)));
        try {
            LlmResult r = j.llm.chat(choice.config, req, LlmListener.NOP, token);
            String raw = r.content == null ? "" : r.content;
            List<String> items = SuggestionPrompt.parse(raw);
            if (items.isEmpty()) {
                j.journal.log(Journal.Cat.LLM, Journal.Level.WARN, "Подсказки: модель не дала пригодных вариантов (ответ " + raw.length() + " симв.)", null, null, -1);
            } else {
                j.journal.log(Journal.Cat.LLM, Journal.Level.INFO, "Подсказки обновлены: " + items.size() + ", " + r.totalMs + " мс, модель " + choice.model, null, null, -1);
            }
            return items;
        } catch (LlmException e) {
            if (!token.isCancelled()) j.journal.log(Journal.Cat.LLM, Journal.Level.WARN, "Подсказки не обновлены: " + e.userMessage, null, null, -1);
            return Collections.emptyList();
        }
    }

    /** Recent user messages of the latest chats, oldest first; assistant and tool output never leave the device this way. */
    private SuggestionPrompt.Input collect(int hour) {
        SuggestionPrompt.Input in = new SuggestionPrompt.Input();
        in.hour = hour;
        in.weekday = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7;
        MemoryStore.PromptParts memParts = j.memory.promptParts(SuggestionPrompt.MEMORY_CHARS);
        in.memory = memParts.facts;     // style notes are instructions for the answering model, not context for suggestions
        MusicOut m = j.music;
        if (m != null) {
            MusicOut.Info info = m.info();
            if (info.state == MusicOut.State.PLAYING) in.nowPlaying = info.title;
        }
        List<String> newest = new ArrayList<>();
        List<ChatStore.Conv> convs = j.chat.conversations("", false, false);
        for (int c = 0; c < Math.min(RECENT_CHATS, convs.size()) && newest.size() < RECENT_LINES; c++) {
            ChatStore.Conv conv = convs.get(c);
            List<ChatItem> items = j.chat.items(conv.id, 0, 40);
            for (int i = items.size() - 1; i >= 0 && newest.size() < RECENT_LINES; i--) {
                ChatItem it = items.get(i);
                if (it.kind != ChatItem.Kind.USER) continue;
                String first = it.text.trim().split("\n")[0].trim();
                if (first.isEmpty()) continue;
                String title = conv.title.trim();
                newest.add(title.isEmpty() ? first : title + ": " + first);
            }
        }
        for (int i = newest.size() - 1; i >= 0; i--) in.recentUserLines.add(SuggestionPrompt.clip(newest.get(i), SuggestionPrompt.LINE_CHARS));
        return in;
    }

    private void publish(final List<String> items, final boolean fromModel, final CancelToken token) {
        final List<String> copy = new ArrayList<>(items);
        j.main.post(new Runnable() {
            @Override public void run() { if (!token.isCancelled()) listener.onSuggestions(copy, fromModel); }
        });
    }

    private SuggestionCache.Entry readCache() {
        if (!file.exists()) return null;
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return SuggestionCache.decode(new String(bo.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return null;
        }
    }

    private void writeCache(String json) {
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            j.journal.log(Journal.Cat.LLM, Journal.Level.WARN, "Подсказки: кэш не записан", null, null, -1);
            return;
        }
        if (!tmp.renameTo(file)) j.journal.log(Journal.Cat.LLM, Journal.Level.WARN, "Подсказки: кэш не записан", null, null, -1);
    }
}
