package app.jarvis.rt;

import app.jarvis.core.agent.ToolRegistry;
import app.jarvis.core.agent.ToolResult;
import app.jarvis.core.llm.ToolSpec;
import app.jarvis.core.llm.ToolSpec.Param;
import app.jarvis.core.util.J;
import app.jarvis.data.Settings;
import app.jarvis.media.MusicOut;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Tools that act on the app itself: player, navigation, voice, settings, notes. Every result reflects what really happened. */
final class SystemTools {
    private SystemTools() { }

    /** Settings the model may read/change through app_setting (nothing secret, nothing destructive). */
    private static final Set<String> SETTABLE = new HashSet<>(Arrays.asList(
        Settings.TTS_AUTO, Settings.TTS_SPEED, Settings.TTS_CHAIN, Settings.LLM_REASONING, Settings.LLM_MODEL, Settings.LLM_PROVIDER,
        Settings.WAKE_ENABLED, Settings.MUSIC_FAVORITE, Settings.USER_CALL, Settings.UI_THEME, Settings.STT_CHAIN, Settings.LLM_MAX_STEPS));

    static void register(final Jarvis j) {
        final ToolRegistry r = j.tools;

        // ---------------------------------------------------------------- music
        r.add(ToolSpec.of("music_list", "Аудиофайлы песочницы (music/, inbox/, downloads/): название, длительность, путь.",
                Param.integer("limit", "Сколько файлов вернуть (по умолчанию 50).", false)),
            "player", 30_000, false, (a, ctx) -> {
                List<MusicLibrary.Track> tr = MusicLibrary.scan(j.sandbox, true, Math.max(1, Math.min(200, J.num(a, "limit", 50))));
                JSONArray arr = new JSONArray();
                for (MusicLibrary.Track t : tr) arr.put(J.put(J.put(J.put(J.put(J.put(new JSONObject(), "path", t.path), "title", t.title), "artist", t.artist), "duration", t.durationText()), "size", t.size));
                return ToolResult.ok(J.put(new JSONObject(), "tracks", arr), tr.size() + " аудиофайлов");
            });

        r.add(ToolSpec.of("music_play", "Включить музыку во встроенном плеере. Без path: любимая песня, иначе первый трек music/, иначе последний присланный. ok=true только когда плеер реально играет.",
                Param.str("path", "Путь, например music/song.mp3 (необязательно).", false)),
            "player", 45_000, true, (a, ctx) -> {
                MusicOut m = j.music;
                if (m == null) return ToolResult.fail("Плеер недоступен");
                String p = J.str(a, "path").trim();
                File f;
                if (!p.isEmpty()) f = j.sandbox.resolve(p);
                else {
                    String fav = j.settings.get(Settings.MUSIC_FAVORITE);
                    if (!fav.isEmpty() && j.sandbox.resolve(fav).isFile()) f = j.sandbox.resolve(fav);
                    else {
                        List<MusicLibrary.Track> all = MusicLibrary.scan(j.sandbox, false, 1);
                        if (!all.isEmpty()) f = all.get(0).file;
                        else return ToolResult.fail("В песочнице нет аудиофайлов. Пришлите файл в чат (скрепка) или попросите скачать по ссылке.");
                    }
                }
                return startPlayback(j, m, f);
            });

        r.add(ToolSpec.of("music_play_latest_received", "Включить последний присланный/скачанный аудиофайл (inbox/, затем downloads/)."),
            "player", 45_000, true, (a, ctx) -> {
                MusicOut m = j.music;
                if (m == null) return ToolResult.fail("Плеер недоступен");
                File f = MusicLibrary.latestReceived(j.sandbox);
                if (f == null) return ToolResult.fail("Не нашёл присланных аудиофайлов во входящих и загрузках.");
                return startPlayback(j, m, f);
            });

        r.add(ToolSpec.of("music_pause", "Поставить музыку на паузу."), "player", 10_000, true, (a, ctx) -> control(j, "pause"));
        r.add(ToolSpec.of("music_resume", "Продолжить воспроизведение после паузы."), "player", 10_000, true, (a, ctx) -> control(j, "resume"));
        r.add(ToolSpec.of("music_stop", "Остановить музыку."), "player", 10_000, true, (a, ctx) -> control(j, "stop"));
        r.add(ToolSpec.of("music_next", "Следующий трек очереди."), "player", 15_000, true, (a, ctx) -> control(j, "next"));
        r.add(ToolSpec.of("music_prev", "Предыдущий трек очереди."), "player", 15_000, true, (a, ctx) -> control(j, "prev"));
        r.add(ToolSpec.of("music_status", "Состояние плеера: что играет, позиция, длительность."), "player", 10_000, false, (a, ctx) -> {
            MusicOut m = j.music;
            if (m == null) return ToolResult.fail("Плеер недоступен");
            MusicOut.Info i = m.info();
            return ToolResult.ok(infoJson(i), i.state.name().toLowerCase(Locale.ROOT));
        });

        r.add(ToolSpec.of("music_set_favorite", "Запомнить любимую песню (сценарий «Джарвис, я проснулся»); файл должен существовать.",
                Param.str("path", "Путь аудиофайла в песочнице.", true)),
            "player", 30_000, true, (a, ctx) -> {
                File f = j.sandbox.resolve(J.str(a, "path"));
                if (!MusicLibrary.isAudio(f)) return ToolResult.fail("Это не аудиофайл или файла нет: " + J.str(a, "path"));
                j.settings.set(Settings.MUSIC_FAVORITE, j.sandbox.rel(f));
                MusicLibrary.Track t = MusicLibrary.describe(j.sandbox, f, true);
                return ToolResult.ok(J.put(J.put(new JSONObject(), "path", t.path), "title", t.title), "любимая: " + t.title);
            });

        // ---------------------------------------------------------------- app control
        r.add(ToolSpec.of("app_state", "Текущее состояние приложения: что делает ассистент, режим обращения, провайдеры, плеер, микрофон."),
            "tools", 10_000, false, (a, ctx) -> {
                JSONObject o = new JSONObject();
                J.put(o, "assistant_state", j.state().label);
                J.put(o, "run_active", j.agent.isRunning());
                J.put(o, "wake_enabled", j.voice.isWakeEnabled());
                J.put(o, "mic_active", j.voice.isMicActive());
                J.put(o, "mic_problem", j.voice.micProblem());
                J.put(o, "speaking", j.speaker.isSpeaking());
                J.put(o, "llm", Jarvis.describeProvider(j.llmChoices().isEmpty() ? null : j.llmChoices().get(0)));
                J.put(o, "tts_chain", j.settings.get(Settings.TTS_CHAIN));
                J.put(o, "stt_chain", j.settings.get(Settings.STT_CHAIN));
                J.put(o, "favorite_song", j.settings.get(Settings.MUSIC_FAVORITE));
                MusicOut m = j.music;
                if (m != null) J.put(o, "player", infoJson(m.info()));
                return ToolResult.ok(o, j.state().label);
            });

        r.add(ToolSpec.of("app_open", "Открыть раздел приложения.",
                Param.str("screen", "Куда перейти.", true).oneOf("home", "agent", "files", "terminal", "studio", "log", "settings", "settings/llm", "settings/voice", "settings/wake", "settings/music", "settings/sandbox", "settings/adb", "settings/background", "settings/appearance", "settings/advanced", "settings/diagnostics")),
            "tools", 10_000, true, (a, ctx) -> {
                String s = J.str(a, "screen");
                j.navigate(s);
                return ToolResult.ok(J.put(new JSONObject(), "screen", s), "открыт раздел " + s);
            });

        r.add(ToolSpec.of("app_listen", "Слушать команду микрофоном: start или stop.",
                Param.str("action", "start или stop.", true).oneOf("start", "stop")),
            "tools", 15_000, true, (a, ctx) -> {
                if ("stop".equals(J.str(a, "action"))) { j.voice.cancel(); return ToolResult.ok("прослушивание остановлено"); }
                boolean ok = j.voice.startListening();
                return ok ? ToolResult.ok("слушаю") : ToolResult.fail(j.voice.micProblem().isEmpty() ? "Не удалось включить микрофон" : j.voice.micProblem());
            });

        r.add(ToolSpec.of("app_wake", "Режим ожидания обращения «Джарвис»: вкл/выкл.",
                Param.bool("enabled", "true — слушать, false — выключить.", true)),
            "tools", 15_000, true, (a, ctx) -> {
                boolean en = J.bool(a, "enabled", false);
                j.settings.setBool(Settings.WAKE_ENABLED, en);
                j.voice.setWakeEnabled(en);
                boolean actual = j.voice.isWakeEnabled();
                if (en && !actual) return ToolResult.fail(j.voice.micProblem().isEmpty() ? "Режим обращения не включился" : j.voice.micProblem());
                return ToolResult.ok(J.put(new JSONObject(), "wake_enabled", actual), actual ? "режим обращения включён" : "режим обращения выключен");
            });

        r.add(ToolSpec.of("speech_stop", "Немедленно прекратить озвучку и очистить очередь речи."), "tools", 10_000, true, (a, ctx) -> {
            j.speaker.stop();
            return ToolResult.ok("озвучка остановлена");
        });

        r.add(ToolSpec.of("app_setting", "Прочитать или изменить разрешённую настройку. Без value — текущее значение.",
                Param.str("key", "Ключ: " + SETTABLE, true), Param.str("value", "Новое значение (необязательно).", false)),
            "tools", 10_000, true, (a, ctx) -> {
                String k = J.str(a, "key");
                if (!SETTABLE.contains(k)) return ToolResult.fail("Эту настройку менять нельзя. Допустимые: " + SETTABLE);
                if (!a.has("value")) return ToolResult.ok(J.put(J.put(new JSONObject(), "key", k), "value", j.settings.get(k)), k + " = " + j.settings.get(k));
                j.settings.set(k, J.str(a, "value"));
                if (k.equals(Settings.WAKE_ENABLED)) j.voice.setWakeEnabled(j.settings.getBool(k, false));
                return ToolResult.ok(J.put(J.put(new JSONObject(), "key", k), "value", j.settings.get(k)), k + " = " + j.settings.get(k));
            });

        // ---------------------------------------------------------------- notes
        r.add(ToolSpec.of("note_create", "Создать заметку (markdown в docs/notes/). Возвращает путь и размер.",
                Param.str("title", "Заголовок.", true), Param.str("text", "Текст заметки.", true)),
            "files", 20_000, true, (a, ctx) -> {
                String title = J.str(a, "title").trim();
                if (title.isEmpty()) title = "Заметка";
                File dir = new File(j.sandbox.docs, "notes");
                if (!dir.isDirectory() && !dir.mkdirs()) return ToolResult.fail("Не удалось создать docs/notes");
                String slug = title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]+", "-").replaceAll("^-+|-+$", "");
                if (slug.isEmpty()) slug = "note";
                if (slug.length() > 40) slug = slug.substring(0, 40);
                File f = Attachments.uniqueFile(dir, new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + "-" + slug + ".md");
                byte[] body = ("# " + title + "\n\n" + J.str(a, "text") + "\n").getBytes(StandardCharsets.UTF_8);
                try (FileOutputStream os = new FileOutputStream(f)) { os.write(body); os.getFD().sync(); }
                if (!f.isFile() || f.length() != body.length) return ToolResult.fail("Заметка не записалась на диск");
                return ToolResult.ok(J.put(J.put(new JSONObject(), "path", j.sandbox.rel(f)), "size", f.length()), "заметка " + j.sandbox.rel(f));
            });
    }

    private static ToolResult startPlayback(Jarvis j, MusicOut m, File f) {
        if (!MusicLibrary.isAudio(f)) return ToolResult.fail("Файл не найден или это не аудио: " + j.sandbox.rel(f));
        MusicLibrary.Track t = MusicLibrary.describe(j.sandbox, f, true);
        try {
            List<File> queue = MusicLibrary.siblings(f);
            int idx = Math.max(0, queue.indexOf(f));
            j.speaker.stop();
            if (queue.size() > 1) m.playQueue(queue, idx); else m.play(f, t.title);
        } catch (IOException e) {
            return ToolResult.fail(e.getMessage() == null ? "Плеер не смог запустить файл" : e.getMessage());
        }
        MusicOut.Info i = m.info();
        if (i.state != MusicOut.State.PLAYING) return ToolResult.fail("Плеер не перешёл в состояние воспроизведения (" + i.state + (i.error.isEmpty() ? "" : ": " + i.error) + ")");
        JSONObject o = infoJson(i);
        J.put(o, "title", t.title);
        J.put(o, "path", t.path);
        return ToolResult.ok(o, "играет «" + t.title + "»");
    }

    private static ToolResult control(Jarvis j, String what) {
        MusicOut m = j.music;
        if (m == null) return ToolResult.fail("Плеер недоступен");
        MusicOut.Info before = m.info();
        if (before.state == MusicOut.State.IDLE && !what.equals("stop")) return ToolResult.fail("Сейчас ничего не играет");
        switch (what) {
            case "pause": m.pause(); break;
            case "resume": m.resume(); break;
            case "stop": m.stop(); break;
            case "next": m.next(); break;
            default: m.prev(); break;
        }
        MusicOut.Info after = m.info();
        boolean ok;
        switch (what) {
            case "pause": ok = after.state == MusicOut.State.PAUSED; break;
            case "resume": ok = after.state == MusicOut.State.PLAYING; break;
            case "stop": ok = after.state != MusicOut.State.PLAYING && after.state != MusicOut.State.PAUSED; break;
            default: ok = after.state == MusicOut.State.PLAYING; break;
        }
        return ok ? ToolResult.ok(infoJson(after), after.state.name().toLowerCase(Locale.ROOT)) : ToolResult.fail("Плеер не выполнил команду «" + what + "»: состояние " + after.state);
    }

    static JSONObject infoJson(MusicOut.Info i) {
        JSONObject o = new JSONObject();
        J.put(o, "state", i.state.name().toLowerCase(Locale.ROOT));
        J.put(o, "title", i.title);
        J.put(o, "path", i.path);
        J.put(o, "position_ms", i.positionMs);
        J.put(o, "duration_ms", i.durationMs);
        J.put(o, "track", (i.index + 1) + "/" + i.queueSize);
        if (!i.error.isEmpty()) J.put(o, "error", i.error);
        return o;
    }
}
