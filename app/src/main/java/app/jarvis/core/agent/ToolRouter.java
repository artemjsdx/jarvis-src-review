package app.jarvis.core.agent;

import app.jarvis.core.llm.ChatMessage;
import app.jarvis.core.llm.ToolCall;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * Decides which {@link ToolGroups groups} to announce to the model for one request, BEFORE the first model call.
 * <p>
 * Three independent signals, any of them switches a group on:
 * <ol>
 * <li>words of the request (stems of Russian words, matched at the start of a word);</li>
 * <li>stickiness: tools used in the last {@link #STICKY_USER_TURNS} user turns of the conversation (and tools already run for
 * this request, see {@code prefill}), so a follow-up like «ещё раз» or «громче» keeps what the previous turn needed;</li>
 * <li>the player is playing or paused (any "стоп" or "хватит" may mean the music).</li>
 * </ol>
 * The router is deliberately generous: an unneeded group costs a few hundred input tokens, a missing one costs a wasted model
 * round trip (the model has to ask with {@link ToolGroups#META_TOOL}) or, worse, a wrong answer. Words that are common in a
 * group's requests but also elsewhere («включи», «открой», «запусти») are kept on purpose.
 */
public final class ToolRouter {
    private ToolRouter() { }

    /** How many previous user turns count for stickiness. */
    public static final int STICKY_USER_TURNS = 3;

    /** The outcome: groups to switch on and, for the journal, why each one. */
    public static final class Result {
        private final Map<String, String> why = new HashMap<>();
        private boolean full;

        void add(String group, String reason) {
            if (ToolGroups.isGroup(group) && !why.containsKey(group)) why.put(group, reason);
        }

        /** Groups in the fixed group order. */
        public List<String> groups() {
            List<String> r = new ArrayList<>();
            for (String g : ToolGroups.ids()) if (why.containsKey(g)) r.add(g);
            return r;
        }

        public String why(String group) { String w = why.get(group); return w == null ? "" : w; }
        public boolean isEmpty() { return why.isEmpty(); }
        /** True when this is the periodic "every tool" answer (see {@link #fullListDue}), not a choice made from the words of the request. */
        public boolean isFull() { return full; }

        @Override public String toString() {
            if (full) return "все (" + why(ToolGroups.ids().get(0)) + ")";
            if (why.isEmpty()) return "нет";
            StringBuilder sb = new StringBuilder();
            for (String g : groups()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(g).append(" (").append(why.get(g)).append(')');
            }
            return sb.toString();
        }
    }

    /** Matches the start of a word (a letter or digit may not precede) and the rest of that word. */
    private static Pattern words(String... alternatives) {
        StringBuilder sb = new StringBuilder("(?<![\\p{L}\\p{Nd}])(?:");
        for (int i = 0; i < alternatives.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(alternatives[i]);
        }
        return Pattern.compile(sb.append(")[\\p{L}\\p{Nd}]*").toString());
    }

    /** A whole short word (so «гит» does not match «гитара»). */
    private static String exact(String w) { return w + "(?![\\p{L}\\p{Nd}])"; }

    /** Group id -> pattern over text normalized by {@link DirectCommands#normalize}: lower case, ё→е, no punctuation. */
    private static final Map<String, Pattern> KEYWORDS = new LinkedHashMap<>();
    /**
     * Verbs that start a request for the player, for another app, for a setting: «включи X», «открой X». Alone they say little, so they
     * count only when no specific word was found (otherwise «включи музыку» would also announce all of ADB, «запусти скрипт» the player).
     */
    private static final Pattern GENERIC_VERBS = words("включи", "выключи", "поставь", "запусти", "открой", "открыть", "закрой");
    private static final String[] GENERIC_GROUPS = {ToolGroups.MUSIC, ToolGroups.ADB};
    /** Things only visible before normalization (links). */
    private static final Pattern URL = Pattern.compile("https?://|www\\.|\\b[a-z0-9-]+\\.(?:com|ru|by|org|net|io|dev|app|su|ua|kz|me)\\b");

    static {
        KEYWORDS.put(ToolGroups.MUSIC, words(
            "музык", "песн", "песен", "трек", "плейлист", "плеер", "мелоди", "альбом", "исполнител", "артист", "композици", "радио", "mp3", "audio", "аудио",
            "играет", "играй", "сыграй", "проиграй", "воспроизв", "пауз", "стоп", "останови", "продолжи", "возобнов", "следующ", "предыдущ",
            "перемот", "пропусти", "переключи", "громк", "громче", "погромче", "тише", "потише", "любим", "избранн"));
        KEYWORDS.put(ToolGroups.FILES, words(
            "файл", "папк", "каталог", "директори", "документ", "заметк", "загрузк", "загружен", "скачанн", "вложени", "inbox", "downloads", "work", "docs",
            "песочниц", "прочит", "прочти", "переимен", "перемест", "скопир", "содержим", "txt", "pdf", "csv", "json"));
        KEYWORDS.put(ToolGroups.WEB, words(
            "сайт", "страниц", "ссылк", "url", "http", "www", "скача", "загрузи", "документаци", "википеди", "стать", "прочит", "прочти",
            exact("api"), "апи", "гитхаб", "github", "хабр", "интернет", "онлайн"));
        KEYWORDS.put(ToolGroups.SHELL, words(
            "терминал", "консол", "шелл", "shell", "bash", "баш", "скрипт", "python", "питон", exact("pip"), exact("apt"), exact("pkg"), exact("git"), exact("гит"),
            exact("node"), "npm", "curl", exact("ping"), exact("пинг"), "ssh", "grep", "chmod", "sudo", "termux", "термукс", "выполни", "командн", "процесс",
            "компил", "скомпил", "docker", "докер", "установи",
            "свободн\\p{L}*\\s+(?:место|места|памят)", "места\\s+на\\s+(?:диск|телефон|устройств)"));
        KEYWORDS.put(ToolGroups.ADB, words(
            "adb", "адб", "отладк", "скриншот", "снимок", "сними", "нажми", exact("тап"), "тапни", "свайп", "смахни", "прокрути", "экран", "яркост", "фонарик",
            "вай ?фай", "wi ?fi", "wifi", "блютуз", "bluetooth", "авиарежим", "режим полета", "самолетн", "громк", "громче", "погромче", "тише", "потише", "звук",
            "не беспокоить", "будильник", "таймер", "позвони", "звонок", "набери", "смс", "приложени", "установи",
            "камер", "ютуб", "youtube", "телеграм", "telegram", "whatsapp", "ватсап", "браузер", "хром", "chrome", "навигатор", "калькулятор", "контакт", "галере",
            "перезагруз", "заряд", "батаре", "поверни", "ориентац", "заблокир", "разблокир", "блокиров"));
        KEYWORDS.put(ToolGroups.MEMORY, words(
            "забуд", "забы", "памят", "закреп", "открепи", "исправ", "замени", "обнови", "сотри", "стереть", "неправильно запомнил", "не так запомнил"));
        KEYWORDS.put(ToolGroups.SKILLS, words(
            "навык", "скилл", "skill", "научись", "триггер", "trigger", "когда я скажу", "когда я говорю", "когда скажу", "если я скажу", "если скажу",
            "по фразе", "по слову", "на фразу", "по команде", "сценар", "автоматиз", "рутин", "макрос"));
        KEYWORDS.put(ToolGroups.SYSTEM, words(
            "слуша", "прослуш", "микрофон", "обращени", "активаци", "ключев", "пробужд", "режим", "настро", "парамет", "голос", "озвучк", "скорост", "тембр",
            "темн\\p{L}*\\s+тем", "светл\\p{L}*\\s+тем", "wake"));
        KEYWORDS.put(ToolGroups.HISTORY, words(
            "вчера", "позавчера", "раньше", "ранее", "прошл", "помнишь", "вспомни", "вспомин", "говорили", "обсуждали", "обсудили", "разговор", "диалог",
            "переписк", "чат", "истори", "просил", "говорил", "сказал", "на днях", "недавно", "в тот раз"));
    }

    /**
     * True on every {@code every}-th user turn of a session (turns count from 1): that request carries EVERY tool, as a reminder of
     * everything the assistant can do. 0 = never, 1 = every request (the way it was before groups existed). The same counting as the
     * rules reminder, {@link Prompts#reminderDue}.
     */
    public static boolean fullListDue(int userTurn, int every) { return Prompts.reminderDue(userTurn, every); }

    /** Every group switched on: the request announces every tool. */
    public static Result all(String reason) {
        Result r = new Result();
        for (String g : ToolGroups.ids()) r.add(g, reason);
        r.full = true;
        return r;
    }

    /**
     * The groups for one request: all of them on every {@code fullEvery}-th user turn, otherwise what {@link #route} decides.
     *
     * @param userTurn  number of the user's message in the session, this one included (1 for the first)
     * @param fullEvery setting {@code tools.fullEvery}: 0 = never, N = every N-th request, 1 = every request
     */
    public static Result choose(String userText, List<ChatMessage> history, List<ChatMessage> prefill, boolean musicActive, int userTurn, int fullEvery) {
        if (fullListDue(userTurn, fullEvery)) return all("полный список: каждый " + fullEvery + "-й запрос, № " + userTurn);
        return route(userText, history, prefill, musicActive);
    }

    /**
     * @param userText    the user's message as sent to the model (attachment markers included)
     * @param history     the conversation before this message, oldest first; may be null
     * @param prefill     tool calls and results already run for this request (direct commands); may be null
     * @param musicActive true when the player is playing or paused
     */
    public static Result route(String userText, List<ChatMessage> history, List<ChatMessage> prefill, boolean musicActive) {
        Result r = new Result();
        String raw = userText == null ? "" : userText.toLowerCase(Locale.ROOT);
        String text = DirectCommands.normalize(raw);
        for (Map.Entry<String, Pattern> e : KEYWORDS.entrySet()) {
            Matcher m = e.getValue().matcher(text);
            if (m.find()) r.add(e.getKey(), "слово «" + m.group() + "»");
        }
        if (URL.matcher(raw).find()) r.add(ToolGroups.WEB, "ссылка в тексте");
        if (r.isEmpty()) {
            Matcher g = GENERIC_VERBS.matcher(text);
            if (g.find()) for (String grp : GENERIC_GROUPS) r.add(grp, "общий глагол «" + g.group() + "»");
        }
        if (musicActive) r.add(ToolGroups.MUSIC, "плеер занят");
        scanHistory(r, history, true);
        scanHistory(r, prefill, false);
        return r;
    }

    /**
     * Walks back from the newest message. A turn is a user message followed by the replies and tool calls it caused, so going backwards
     * the replies of a turn come BEFORE its user message: the window ends at the user message of the oldest turn that still counts.
     */
    private static void scanHistory(Result r, List<ChatMessage> msgs, boolean limited) {
        if (msgs == null) return;
        int users = 0;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            ChatMessage m = msgs.get(i);
            if (ChatMessage.USER.equals(m.role)) {
                if (limited && ++users >= STICKY_USER_TURNS) break;
            } else if (ChatMessage.ASSISTANT.equals(m.role)) {
                for (ToolCall c : m.toolCalls) used(r, c);
            } else if (ChatMessage.TOOL.equals(m.role) && m.name != null) {
                useTool(r, m.name);
            }
        }
    }

    private static void used(Result r, ToolCall c) {
        if (ToolGroups.META_TOOL.equals(c.name)) {
            // groups the model asked for earlier stay on for the next turns, even if it never got to use them
            JSONObject a = c.args();
            if (a == null) return;
            for (String g : ToolSelection.parseGroups(a)) {
                if (ToolGroups.ALL.equals(g)) for (String all : ToolGroups.ids()) r.add(all, "tools_enable(all) в истории");
                else r.add(g, "tools_enable в истории");
            }
        } else {
            useTool(r, c.name);
        }
    }

    private static void useTool(Result r, String tool) {
        for (String g : ToolGroups.groupsForUse(tool)) r.add(g, "в истории: " + tool);
    }
}
