package app.jarvis.core.agent;

import app.jarvis.core.util.J;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * Simple, well-known voice commands that are executed directly (no LLM round trip, hence fast and offline-capable).
 * The reply is always built from the REAL tool result: a failed tool never produces a success sentence.
 * Anything that is not clearly one of these goes to the model.
 */
public final class DirectCommands {
    public enum SessionCommand { NEW_CHAT, CLEAR_CONTEXT }

    public interface Env {
        /** Runs a registered tool (recorded in the chat/journal like any other tool call). */
        ToolResult tool(String name, JSONObject args);
        String userCall();                 // "папочка"
        String favoritePath();             // "" when not chosen
        void navigate(String target);
        String statusText();               // what the assistant is doing right now, from real state
        /** True if a track is currently paused (so "продолжи" means music, not something else). */
        boolean musicPaused();
        boolean musicActive();
        /** Performs a chat-session action and returns the Russian reply to show. */
        String session(SessionCommand c);
        /** The phrase trigger that matches the raw utterance, or null. DIRECT triggers are run here; LLM ones are left to the model. */
        default Triggers.Trigger trigger(String raw) { return null; }
    }

    public static final class Outcome {
        public final boolean handled, ok;
        /** Full reply built from the real tool result (may name the track, the reason of a failure, ...). */
        public final String reply;
        /**
         * Short phrase from {@link FastPhrases} that is a complete, truthful confirmation of a SUCCESSFUL action (it is in the audio cache).
         * Empty when the outcome is a failure or carries information the user must hear (status, errors): then {@link #reply} is spoken.
         */
        public final String fast;
        Outcome(boolean handled, boolean ok, String reply, String fast) { this.handled = handled; this.ok = ok; this.reply = reply; this.fast = fast == null ? "" : fast; }
        static final Outcome NO = new Outcome(false, false, "", "");
        static Outcome done(boolean ok, String reply) { return new Outcome(true, ok, reply, ""); }
        static Outcome done(boolean ok, String reply, FastPhrases.Cat cat) { return new Outcome(true, ok, reply, ok ? FastPhrases.global().pick(cat) : ""); }
    }

    private static final Pattern TOOL_ACTION = Pattern.compile("^tools\\.call:([a-z_][a-z0-9_]*):(\\{.*\\})$", Pattern.DOTALL);
    private static final Pattern STOP_MUSIC = Pattern.compile("^(?:останови|выключи|выруби|заглуши|хватит|стоп|прекрати|отключи)\\s*(?:уже\\s+)?(?:(?:эту\\s+|мою\\s+)?(?:музыку|музыка|песню|песня|трек|плеер|воспроизведение)|играть)?$|^(?:стоп|остановись)$");
    private static final Pattern PAUSE = Pattern.compile("^(?:пауза|поставь\\s+(?:на\\s+)?паузу|приостанови(?:\\s+(?:музыку|песню|трек))?)$");
    private static final Pattern RESUME = Pattern.compile("^(?:продолжи|продолжай|возобнови|играй\\s+дальше|сними\\s+с\\s+паузы)(?:\\s+(?:музыку|песню|трек|воспроизведение))?$");
    private static final Pattern NEXT = Pattern.compile("^(?:следующ(?:ая|ий|ую|ее)(?:\\s+(?:песня|песню|трек|композиция))?|дальше|пропусти(?:\\s+(?:песню|трек))?|переключи(?:\\s+(?:песню|трек))?)$");
    private static final Pattern PREV = Pattern.compile("^(?:предыдущ(?:ая|ий|ую|ее)(?:\\s+(?:песня|песню|трек|композиция))?|назад|верни\\s+(?:песню|трек))$");
    private static final Pattern PLAY_ANY = Pattern.compile("^(?:включи|поставь|запусти|сыграй|проиграй|играй)\\s+(?:мне\\s+)?(?:какую[-\\s]нибудь\\s+|любую\\s+|мою\\s+|нашу\\s+)?(?:музыку|песню|песни|трек|что[-\\s]нибудь)(?:\\s+(?:из\\s+песочницы|из\\s+памяти|из\\s+моих\\s+файлов|из\\s+папки\\s+музыка))?$");
    private static final Pattern PLAY_FAV = Pattern.compile("^(?:включи|поставь|запусти|сыграй|проиграй)\\s+(?:мою\\s+|нашу\\s+)?(?:любимую|избранную)(?:\\s+(?:музыку|песню|песни|трек))?$");
    private static final Pattern PLAY_SENT = Pattern.compile("^(?:включи|поставь|запусти|проиграй|сыграй|воспроизведи)\\s+(?:тот\\s+|этот\\s+|последний\\s+)?(?:файл|трек|аудио|песню|запись)(?:\\s*(?:,|который|что)?\\s*(?:я\\s+)?(?:тебе\\s+)?(?:только\\s+что\\s+)?(?:прислал|скинул|отправил|загрузил|отправила|скинула|прислала))?(?:\\s+(?:тебе|в\\s+чат))?$|^(?:проиграй|включи|поставь)\\s+присланн(?:ый|ую|ое)(?:\\s+(?:файл|трек|песню|аудио))?$");
    private static final Pattern WHAT_DOING = Pattern.compile("^(?:что\\s+ты\\s+(?:сейчас\\s+)?делаешь|чем\\s+(?:ты\\s+)?(?:сейчас\\s+)?занят(?:а)?|что\\s+происходит|ты\\s+(?:тут|здесь|на\\s+связи)|статус)$");
    private static final Pattern SHOW_FILES = Pattern.compile("^(?:покажи|открой|давай)\\s+(?:мои\\s+|все\\s+|загруженные\\s+|скачанные\\s+|присланные\\s+)*(?:файлы|загрузки|файловый\\s+менеджер)$");
    private static final Pattern NEW_CHAT = Pattern.compile("^(?:(?:начни|начать|открой|создай|давай)\\s+)?(?:новый|новую|новое)\\s+(?:голосовой\\s+|текстовый\\s+)?(?:чат|диалог|сессию|сессия)(?:\\s+без\\s+контекста)?$");
    private static final Pattern CLEAR_CONTEXT = Pattern.compile("^(?:очисти|очистить|сбрось|сбросить|обнули|обнулить)\\s+(?:весь\\s+|этот\\s+|мой\\s+)?контекст(?:\\s+(?:чата|диалога|сессии))?$");
    private static final Pattern OPEN =Pattern.compile("^(?:открой|покажи|перейди\\s+(?:в|на|к)|зайди\\s+в)\\s+(?:раздел\\s+|вкладку\\s+|экран\\s+)?(.+)$");

    /** Lower-case, ё→е, punctuation → spaces, collapsed. The wake word is removed by the caller (WakeMatcher). */
    public static String normalize(String s) {
        String t = s.toLowerCase(Locale.ROOT).replace('ё', 'е');
        t = t.replaceAll("[\\p{Punct}«»“”…—–]+", " ").replaceAll("\\s+", " ").trim();
        return t;
    }

    public Outcome handle(String raw, Env env) {
        Triggers.Trigger tr = env.trigger(raw);
        if (tr != null && tr.direct()) return runTrigger(tr.action, env);
        String t = normalize(raw);
        if (t.isEmpty() || t.split(" ").length > 9) return Outcome.NO;

        if (PLAY_FAV.matcher(t).matches()) {
            if (env.favoritePath().isEmpty()) { env.navigate("settings/music"); return Outcome.done(false, "Любимая песня не выбрана. Открыл настройки музыки, выберите файл."); }
            ToolResult r = env.tool("music_play", J.put(new JSONObject(), "path", env.favoritePath()));
            return r.ok ? Outcome.done(true, "Включаю " + title(r) + ".", FastPhrases.Cat.PLAY) : Outcome.done(false, "Не получилось включить: " + r.error);
        }
        if (PLAY_SENT.matcher(t).matches()) {
            ToolResult r = env.tool("music_play_latest_received", new JSONObject());
            return r.ok ? Outcome.done(true, "Играет " + title(r) + ".", FastPhrases.Cat.PLAY) : Outcome.done(false, r.error);
        }
        if (PLAY_ANY.matcher(t).matches()) {
            ToolResult r = env.tool("music_play", new JSONObject());
            return r.ok ? Outcome.done(true, "Включаю " + title(r) + ".", FastPhrases.Cat.PLAY) : Outcome.done(false, r.error);
        }
        if (STOP_MUSIC.matcher(t).matches()) {
            // plain "стоп" must not eat a command meant for the assistant's speech: only treat it as music when something plays
            boolean explicitMusic = t.contains("музык") || t.contains("песн") || t.contains("трек") || t.contains("плеер") || t.contains("воспроизвед");
            if (!explicitMusic && !env.musicActive()) return Outcome.NO;
            ToolResult r = env.tool("music_stop", new JSONObject());
            return r.ok ? Outcome.done(true, "Остановил.", FastPhrases.Cat.STOP) : Outcome.done(false, r.error);
        }
        if (PAUSE.matcher(t).matches()) {
            if (!env.musicActive()) return Outcome.NO;
            ToolResult r = env.tool("music_pause", new JSONObject());
            return r.ok ? Outcome.done(true, "Пауза.", FastPhrases.Cat.PAUSE) : Outcome.done(false, r.error);
        }
        if (RESUME.matcher(t).matches()) {
            if (!env.musicPaused()) return Outcome.NO;
            ToolResult r = env.tool("music_resume", new JSONObject());
            return r.ok ? Outcome.done(true, "Продолжаю.", FastPhrases.Cat.RESUME) : Outcome.done(false, r.error);
        }
        if (NEXT.matcher(t).matches()) {
            if (!env.musicActive()) return Outcome.NO;
            ToolResult r = env.tool("music_next", new JSONObject());
            return r.ok ? Outcome.done(true, "Следующий трек.", FastPhrases.Cat.NEXT) : Outcome.done(false, r.error);
        }
        if (PREV.matcher(t).matches()) {
            if (!env.musicActive()) return Outcome.NO;
            ToolResult r = env.tool("music_prev", new JSONObject());
            return r.ok ? Outcome.done(true, "Предыдущий трек.", FastPhrases.Cat.PREV) : Outcome.done(false, r.error);
        }
        if (WHAT_DOING.matcher(t).matches()) return Outcome.done(true, env.statusText());
        if (NEW_CHAT.matcher(t).matches()) return Outcome.done(true, env.session(SessionCommand.NEW_CHAT), FastPhrases.Cat.DONE);
        if (CLEAR_CONTEXT.matcher(t).matches()) return Outcome.done(true, env.session(SessionCommand.CLEAR_CONTEXT), FastPhrases.Cat.DONE);
        if (SHOW_FILES.matcher(t).matches()) { env.navigate("files"); return Outcome.done(true, "Открыл файлы.", FastPhrases.Cat.FILES); }
        Matcher m = OPEN.matcher(t);
        if (m.matches()) {
            String where = screenFor(m.group(1).trim());
            if (where != null) { env.navigate(where); return Outcome.done(true, "Открыл.", FastPhrases.Cat.OPEN); }
        }
        return Outcome.NO;
    }

    /** Runs the action of a DIRECT trigger; the reply is built from the real result of the tool it calls. */
    private static Outcome runTrigger(String action, Env env) {
        String call = env.userCall() == null || env.userCall().isEmpty() ? "" : ", " + env.userCall();
        if (Triggers.MORNING.equals(action)) {
            String fav = env.favoritePath();
            if (fav.isEmpty()) {
                env.navigate("settings/music");
                return Outcome.done(false, "Доброе утро" + call + "! Любимая песня пока не выбрана. Я открыл настройки музыки: выберите файл, и в следующий раз он заиграет сам.");
            }
            ToolResult r = env.tool("music_play", J.put(new JSONObject(), "path", fav));
            if (!r.ok) return Outcome.done(false, "Доброе утро" + call + ". Но музыку включить не удалось: " + r.error);
            return Outcome.done(true, "Доброе утро" + call + "! Включаю " + title(r) + ".", FastPhrases.Cat.MORNING);
        }
        if (Triggers.PLAY_FAVORITE.equals(action)) {
            if (env.favoritePath().isEmpty()) { env.navigate("settings/music"); return Outcome.done(false, "Любимая песня не выбрана. Открыл настройки музыки, выберите файл."); }
            ToolResult r = env.tool("music_play", J.put(new JSONObject(), "path", env.favoritePath()));
            return r.ok ? Outcome.done(true, "Включаю " + title(r) + ".", FastPhrases.Cat.PLAY) : Outcome.done(false, "Не получилось включить: " + r.error);
        }
        if (action.startsWith("app.open:")) {
            env.navigate(action.substring("app.open:".length()));
            return Outcome.done(true, "Открыл.", FastPhrases.Cat.OPEN);
        }
        Matcher m = TOOL_ACTION.matcher(action);
        if (m.matches()) {
            JSONObject args;
            try { args = new JSONObject(m.group(2)); } catch (org.json.JSONException e) { return Outcome.done(false, "Действие триггера не настроено: аргументы не JSON."); }
            ToolResult r = env.tool(m.group(1), args);
            return r.ok ? Outcome.done(true, r.summary.isEmpty() ? "Готово." : r.summary, FastPhrases.Cat.DONE)
                : Outcome.done(false, "Не получилось: " + r.error);
        }
        return Outcome.NO;
    }

    /** Maps a spoken screen name to a navigation target; null if it is not one we know. */
    public static String screenFor(String s) {
        if (s.matches(".*настройк\\p{L}*\\s+голос\\p{L}*.*|.*голосов\\p{L}*\\s+настройк\\p{L}*.*")) return "settings/voice";
        if (s.matches(".*настройк\\p{L}*\\s+(?:музык\\p{L}*)|.*настройк\\p{L}*\\s+(?:плеер\\p{L}*)")) return "settings/music";
        if (s.matches(".*настройк\\p{L}*\\s+(?:адб|adb)\\p{L}*.*")) return "settings/adb";
        if (s.matches(".*настройк\\p{L}*\\s+(?:батаре\\p{L}*|фон\\p{L}*).*")) return "settings/background";
        if (s.matches(".*настройк\\p{L}*.*")) return "settings";
        if (s.matches(".*(?:студи\\p{L}*(?:\\s+голос\\p{L}*)?|голосов\\p{L}*\\s+студи\\p{L}*).*")) return "studio";
        if (s.matches(".*(?:журнал\\p{L}*|лог\\p{L}*).*")) return "log";
        if (s.matches(".*(?:терминал\\p{L}*|консол\\p{L}*).*")) return "terminal";
        if (s.matches(".*(?:файл\\p{L}*|загрузк\\p{L}*).*")) return "files";
        if (s.matches(".*(?:чат\\p{L}*|агент\\p{L}*|переписк\\p{L}*).*")) return "agent";
        if (s.matches(".*(?:главн\\p{L}*|домой|начал\\p{L}*).*")) return "home";
        return null;
    }

    private static String title(ToolResult r) {
        String t = r.data.optString("title", "");
        if (t == null || t.isEmpty() || t.equals("null")) t = r.data.optString("path", "музыку");
        return t;
    }
}
