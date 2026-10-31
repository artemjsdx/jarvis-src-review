package app.jarvis.mcp;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Base64;
import app.jarvis.core.audio.PcmConvert;
import app.jarvis.core.mcp.McpRegistry;
import app.jarvis.core.mcp.McpResult;
import app.jarvis.core.mcp.McpTools;
import app.jarvis.core.trace.TraceStore;
import app.jarvis.core.util.CancelToken;
import app.jarvis.core.util.J;
import app.jarvis.core.voice.PcmAudio;
import app.jarvis.core.voice.ProviderStatus;
import app.jarvis.core.voice.SttOptions;
import app.jarvis.core.voice.SttProvider;
import app.jarvis.core.voice.SttResult;
import app.jarvis.core.voice.TtsCache;
import app.jarvis.core.voice.TtsProvider;
import app.jarvis.core.voice.TtsRequest;
import app.jarvis.core.voice.VoiceException;
import app.jarvis.data.ChatItem;
import app.jarvis.data.Settings;
import app.jarvis.rt.SpeechApi;
import app.jarvis.rt.SpeechService;
import app.jarvis.rt.VoiceController;
import app.jarvis.rt.Jarvis;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** voice_state, voice_speak, voice_stt_file, voice_inject, tts_cache, wake_state, wake_arm, mic_level. */
final class VoiceTools {
    private VoiceTools() { }

    static final long MAX_INPUT_BYTES = 25L << 20;
    private static final long TURN_WAIT_MS = 90_000;

    /** Early exit from a helper with a ready MCP error. */
    static final class Fail extends RuntimeException {
        final McpResult res;
        Fail(McpResult res) { super("tool error"); this.res = res; }
    }

    static Fail fail(String code, String ru, String technical) { return new Fail(Tl.err(code, ru, technical)); }

    /** Mono 16 kHz PCM ready for STT or injection, and what it came from. */
    static final class Clip {
        final short[] pcm;
        final String format;
        final int sourceRate, sourceChannels;
        Clip(short[] pcm, String format, int sourceRate, int sourceChannels) { this.pcm = pcm; this.format = format; this.sourceRate = sourceRate; this.sourceChannels = sourceChannels; }
    }

    static void register(McpRegistry r, final McpBridge b) {
        final Jarvis j = b.j;

        r.register(McpTools.tool("voice_state", "Состояние голосового конвейера: состояние приложения, обращение и микрофон, цепочки озвучки и распознавания, кулдауны провайдеров, кэш.",
            McpTools.Schema.obj(), (a, c) -> {
                JSONObject o = new JSONObject();
                J.put(o, "state", j.state().name());
                J.put(o, "stateDetail", j.stateDetail());
                J.put(o, "micPermission", j.ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
                J.put(o, "allowAudio", b.gate().allowAudio());
                J.put(o, "wakeEnabled", j.voice.isWakeEnabled());
                J.put(o, "micActive", j.voice.isMicActive());
                J.put(o, "micProblem", j.voice.micProblem());
                SpeechApi sp = j.speech;
                if (sp != null) {
                    J.put(o, "ttsChain", Tl.toArray(sp.activeChain()));
                    JSONArray tts = new JSONArray();
                    for (TtsProvider p : sp.ttsProviders()) {
                        JSONObject po = new JSONObject();
                        J.put(po, "id", p.info().id);
                        J.put(po, "configured", p.isConfigured());
                        J.put(po, "lastStatus", statusJson(p.lastStatus()));
                        tts.put(po);
                    }
                    J.put(o, "ttsProviders", tts);
                    JSONObject cool = new JSONObject();
                    SpeechService ss = speech(j);
                    if (ss != null) for (Map.Entry<String, Long> e : ss.cooldownLeftMs().entrySet()) J.put(cool, e.getKey(), Math.round(e.getValue() / 1000.0));
                    J.put(o, "cooldownSec", cool);
                    J.put(o, "cacheBytes", sp.cacheBytes());
                    if (ss != null) J.put(o, "cacheEntries", ss.ttsCache().entryCount());
                    JSONArray stt = new JSONArray();
                    for (SttProvider p : sp.sttProviders()) {
                        JSONObject so = new JSONObject();
                        J.put(so, "id", p.info().id);
                        J.put(so, "configured", p.isConfigured());
                        J.put(so, "lastStatus", statusJson(p.lastStatus()));
                        stt.put(so);
                    }
                    J.put(o, "sttProviders", stt);
                }
                VoiceController vc = vc(j);
                if (vc != null) {
                    JSONArray chain = new JSONArray();
                    for (SttProvider p : vc.sttChain()) chain.put(p.info().id);
                    J.put(o, "sttChain", chain);
                    J.put(o, "wakePhrases", Tl.toArray(vc.wakePhrases()));
                }
                return McpResult.json(o);
            }).readOnly());

        r.register(McpTools.tool("voice_speak", "Озвучить текст через настоящую цепочку провайдеров: в WAV-файл (по умолчанию) или в динамик (нужен mcp.allowAudio). Возвращает тайминги этапов.",
            McpTools.Schema.obj().str("text", "Текст (до 2000 символов)", true).str("provider", "edge, elevenlabs или gtts (по умолчанию цепочка настроек)", false)
                .str("voice", "Голос (учитывается только вместе с provider)", false).number("speed", "Скорость 0.5–2 (по умолчанию из настроек)", false)
                .oneOf("output", "file (по умолчанию) или speaker", false, "file", "speaker"), (a, c) -> {
                String text = J.str(a, "text").trim();
                if (text.isEmpty()) return Tl.err("INVALID_ARGS", "Пустой текст.", "text");
                if (text.length() > 2000) return Tl.err("INVALID_ARGS", "Текст длиннее 2000 символов.", "text");
                float speed = a.has("speed") && !a.isNull("speed") ? (float) a.optDouble("speed", 0) : 0f;
                if (speed != 0 && (speed < 0.5f || speed > 2f)) return Tl.err("INVALID_ARGS", "Скорость от 0,5 до 2.", "speed=" + speed);
                String provider = J.str(a, "provider"), voice = J.str(a, "voice");
                SpeechService ss = speech(j);
                if (ss == null) return Tl.err("NOT_AVAILABLE", "Озвучка не подключена к приложению.", "speech service missing");
                try {
                    if ("speaker".equals(J.str(a, "output", "file"))) {
                        if (!b.gate().allowAudio()) return Tl.err("FORBIDDEN", "Воспроизведение в динамик: включите mcp.allowAudio.", "setting mcp.allowAudio=false");
                        return speakOut(j, ss, text, provider, voice, speed);
                    }
                    File out = new File(audioDir(j), "voice-" + System.currentTimeMillis() + "-" + (provider.isEmpty() ? "chain" : provider) + ".wav");
                    SpeechService.FileResult fr = ss.synthesizeToFile(text, provider, voice, speed, out);
                    JSONObject o = new JSONObject();
                    J.put(o, "output", "file");
                    J.put(o, "provider", fr.providerId);
                    J.put(o, "chars", fr.chars);
                    J.put(o, "bytes", fr.bytes);
                    J.put(o, "durationMs", fr.durationMs);
                    J.put(o, "firstByteMs", fr.firstByteMs);
                    J.put(o, "totalMs", fr.totalMs);
                    J.put(o, "cacheHit", fr.cacheHit);
                    J.put(o, "sampleRate", fr.sampleRate);
                    J.put(o, "channels", fr.channels);
                    J.put(o, "path", fr.path);
                    J.put(o, "cleanedText", fr.cleanedText);
                    J.put(o, "voiceRequested", voice);
                    J.put(o, "voiceApplied", !voice.isEmpty() && fr.providerId.equals(provider));
                    J.put(o, "speedApplied", speed > 0);
                    return McpResult.json(o);
                } catch (IOException e) {
                    return Tl.err("TTS_FAILED", e.getMessage() == null ? "Озвучка не удалась" : e.getMessage(), e.toString());
                }
            }).timeout(120_000));

        r.register(McpTools.tool("voice_stt_file", "Распознать речь из файла (путь в песочнице или в файлах приложения) или из base64. WAV разбирается сам; MP3/AAC/OGG/M4A декодирует Android. Один провайдер или цепочка распознавания.",
            McpTools.Schema.obj().str("path", "Путь к аудио", false).str("base64", "Аудио в base64", false).str("provider", "Провайдер STT (по умолчанию цепочка)", false), (a, c) -> {
                SpeechApi sp = j.speech;
                VoiceController vc = vc(j);
                if (sp == null || vc == null) return Tl.err("NOT_AVAILABLE", "Распознавание речи не подключено к приложению.", "speech/listen missing");
                try {
                    Clip clip = load(j, a);
                    PcmAudio audio = new PcmAudio(PcmConvert.toBytes(clip.pcm), PcmConvert.STT_RATE);
                    String provider = J.str(a, "provider");
                    List<SttProvider> chain;
                    if (provider.isEmpty()) chain = vc.sttChain();
                    else {
                        SttProvider p = sp.stt(provider);
                        if (p == null) return Tl.err("NOT_FOUND", "Провайдер распознавания «" + provider + "» не найден.", "stt " + provider);
                        chain = Collections.singletonList(p);
                    }
                    if (chain.isEmpty()) return Tl.err("NOT_CONFIGURED", "Распознавание речи не настроено (Настройки → Обращение и распознавание).", "stt chain empty");
                    SttOptions opts = new SttOptions();
                    opts.language = j.settings.get(Settings.STT_LANGUAGE, "ru");
                    long t0 = System.currentTimeMillis();
                    int attempts = 0;
                    VoiceException last = null;
                    for (SttProvider p : chain) {
                        attempts++;
                        try {
                            SttResult res = p.transcribe(audio, opts, new CancelToken());
                            JSONObject o = new JSONObject();
                            J.put(o, "text", res.text == null ? "" : res.text);
                            J.put(o, "provider", p.info().id);
                            J.put(o, "ms", System.currentTimeMillis() - t0);
                            J.put(o, "attempts", attempts);
                            J.put(o, "bytesSent", audio.pcm.length);
                            JSONObject f = new JSONObject();
                            J.put(f, "input", clip.format);
                            J.put(f, "sourceRate", clip.sourceRate);
                            J.put(f, "sourceChannels", clip.sourceChannels);
                            J.put(f, "sampleRate", PcmConvert.STT_RATE);
                            J.put(f, "channels", 1);
                            J.put(f, "durationMs", audio.durationMs());
                            J.put(o, "format", f);
                            return McpResult.json(o);
                        } catch (VoiceException e) {
                            last = e;
                        }
                    }
                    return Tl.err("STT_FAILED", last != null ? last.userMessage : "Распознавание не удалось", last != null ? last.technical : "");
                } catch (Fail f) {
                    return f.res;
                } catch (IOException e) {
                    return Tl.err("IO", e.getMessage() == null ? "Файл не прочитан" : e.getMessage(), e.toString());
                }
            }).timeout(125_000));

        r.register(McpTools.tool("voice_inject", "Подать готовый звук в конвейер вместо микрофона: сегментер, распознавание, обращение или команда, агент, ответ. Ждёт ход до 90 с. Микрофон не включается; ответ по умолчанию не озвучивается, а пишется в WAV.",
            McpTools.Schema.obj().str("path", "Путь к аудио", false).str("base64", "Аудио в base64", false)
                .oneOf("mode", "wake (ищет обращение «Джарвис») или command (сразу команда)", false, "wake", "command")
                .bool("speak", "Озвучить ответ в динамик (нужен mcp.allowAudio)", false).str("session", "Тестовая сессия (по умолчанию автосессия моста)", false), (a, c) -> {
                VoiceController vc = vc(j);
                if (vc == null) return Tl.err("NOT_AVAILABLE", "Микрофонный модуль не подключён.", "listen missing");
                boolean speak = Tl.boolArg(a, "speak", false);
                if (speak && !b.gate().allowAudio()) return Tl.err("FORBIDDEN", "Ответ в динамик: включите mcp.allowAudio.", "setting mcp.allowAudio=false");
                boolean command = "command".equals(J.str(a, "mode", "wake"));
                try {
                    Clip clip = load(j, a);
                    if (clip.pcm.length == 0) return Tl.err("INVALID_ARGS", "В аудио нет звука.", "0 samples");
                    ChatTools.Sess s = ChatTools.resolve(j, J.str(a, "session"), c, true);
                    if (s == null) return Tl.err("NOT_FOUND", "Сессия не найдена.", J.str(a, "session"));
                    if (speak) j.agent.silentConvs.remove(s.id); else j.agent.silentConvs.add(s.id);
                    long base = lastSeq(j, s.id);
                    long t0 = System.currentTimeMillis();
                    vc.injectPcm(clip.pcm, clip.pcm.length, command, s.id);
                    JSONObject turn = null;
                    while (turn == null && System.currentTimeMillis() - t0 < TURN_WAIT_MS && !c.isCancelled()) {
                        turn = finishedVoiceTurn(t0);
                        if (turn == null) Thread.sleep(150);
                    }
                    vc.clearInject();
                    if (turn == null) {
                        return Tl.err("NO_TURN", "Ход не завершился за 90 с: речь не распознана или обращение не найдено (журнал, категории stt и wake).",
                            "session " + s.id + ", mode " + (command ? "command" : "wake"));
                    }
                    String transcript = "", reply = "";
                    for (ChatItem it : j.chat.items(s.id, 0, 60)) {
                        if (it.seq <= base) continue;
                        if (it.kind == ChatItem.Kind.USER) transcript = it.text;
                        else if (it.kind == ChatItem.Kind.ASSISTANT) reply = it.text;
                    }
                    JSONObject o = new JSONObject();
                    J.put(o, "turn", turn);
                    J.put(o, "transcript", transcript);
                    J.put(o, "reply", reply);
                    J.put(o, "session", String.valueOf(s.id));
                    J.put(o, "speak", speak);
                    JSONArray bypassed = new JSONArray();
                    bypassed.put("microphone (кадры поданы напрямую)");
                    bypassed.put("speaker-busy гейт");
                    if (!speak) bypassed.put("динамик (ответ отключён, озвучен отдельно в файл)");
                    J.put(o, "bypassed", bypassed);
                    SpeechService ss = speech(j);
                    if (!speak && ss != null && !reply.trim().isEmpty()) {
                        try {
                            SpeechService.FileResult fr = ss.synthesizeToFile(reply, "", new File(audioDir(j), "inject-" + System.currentTimeMillis() + ".wav"));
                            J.put(o, "ttsPath", fr.path);
                            J.put(o, "ttsMs", fr.totalMs);
                        } catch (IOException e) {
                            J.put(o, "ttsError", e.getMessage());
                        }
                    }
                    return McpResult.json(o);
                } catch (Fail f) {
                    return f.res;
                } catch (IOException e) {
                    return Tl.err("IO", e.getMessage() == null ? "Файл не прочитан" : e.getMessage(), e.toString());
                }
            }).timeout(120_000));

        r.register(McpTools.tool("tts_cache", "Кэш озвучки: stats (размер и число записей), list (записи), clear (очистить кэш).",
            McpTools.Schema.obj().oneOf("action", "stats, list или clear", false, "stats", "list", "clear"), (a, c) -> {
                SpeechService ss = speech(j);
                if (ss == null) return Tl.err("NOT_AVAILABLE", "Озвучка не подключена к приложению.", "speech service missing");
                String action = J.str(a, "action", "stats");
                JSONObject o = new JSONObject();
                J.put(o, "action", action);
                if ("clear".equals(action)) {
                    long before = ss.cacheBytes();
                    ss.clearCache();
                    J.put(o, "clearedBytes", before);
                }
                TtsCache cache = ss.ttsCache();
                J.put(o, "bytes", cache.totalBytes());
                J.put(o, "entries", cache.entryCount());
                J.put(o, "maxBytes", TtsCache.DEFAULT_MAX_BYTES);
                if ("list".equals(action)) {
                    JSONArray arr = new JSONArray();
                    for (TtsCache.Entry e : cache.list()) {
                        JSONObject eo = new JSONObject();
                        String name = e.file.getName();
                        J.put(eo, "key", name.substring(0, Math.max(0, name.length() - 4)));
                        J.put(eo, "bytes", e.size);
                        J.put(eo, "modified", e.file.lastModified());
                        J.put(eo, "header", Tl.clip(e.headerJson, 200));
                        arr.put(eo);
                    }
                    J.put(o, "list", arr);
                }
                return McpResult.json(o);
            }));

        r.register(McpTools.tool("wake_state", "Обращение «Джарвис»: включено ли, микрофон, фразы обращения, права на микрофон.",
            McpTools.Schema.obj(), (a, c) -> {
                VoiceController vc = vc(j);
                if (vc == null) return Tl.err("NOT_AVAILABLE", "Микрофонный модуль не подключён.", "listen missing");
                JSONObject o = new JSONObject();
                J.put(o, "enabled", j.voice.isWakeEnabled());
                J.put(o, "micActive", j.voice.isMicActive());
                J.put(o, "micProblem", j.voice.micProblem());
                J.put(o, "micPermission", j.ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
                J.put(o, "phrases", Tl.toArray(vc.wakePhrases()));
                return McpResult.json(o);
            }).readOnly());

        r.register(McpTools.tool("wake_arm", "Включить или выключить обращение. Включение открывает микрофон и требует mcp.allowAudio; выключение разрешено всегда.",
            McpTools.Schema.obj().bool("on", "Включить", true), (a, c) -> {
                if (!a.has("on") || a.isNull("on")) return Tl.err("INVALID_ARGS", "Укажите on: true или false.", "on");
                boolean on = a.optBoolean("on", false);
                if (on && !b.gate().allowAudio()) return Tl.err("FORBIDDEN", "Обращение открывает микрофон: включите mcp.allowAudio.", "setting mcp.allowAudio=false");
                if (j.voice == null) return Tl.err("NOT_AVAILABLE", "Микрофонный модуль не подключён.", "voice missing");
                j.voice.setWakeEnabled(on);
                JSONObject o = new JSONObject();
                J.put(o, "enabled", j.voice.isWakeEnabled());
                J.put(o, "micActive", j.voice.isMicActive());
                J.put(o, "micProblem", j.voice.micProblem());
                return McpResult.json(o);
            }));

        r.register(McpTools.tool("mic_level", "Уровень сигнала микрофона сейчас (0–1). Нужен mcp.allowAudio и включённый микрофон (обращение).",
            McpTools.Schema.obj(), (a, c) -> {
                VoiceController vc = vc(j);
                if (vc == null) return Tl.err("NOT_AVAILABLE", "Микрофонный модуль не подключён.", "listen missing");
                if (!vc.isMicActive()) return Tl.err("NOT_AVAILABLE", "Микрофон выключен: включите обращение (wake_arm on).", "micActive=false");
                JSONObject o = new JSONObject();
                J.put(o, "level", vc.lastLevel());
                J.put(o, "micActive", true);
                return McpResult.json(o);
            }).readOnly().audio());
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    static SpeechService speech(Jarvis j) { return j.speech instanceof SpeechService ? (SpeechService) j.speech : null; }

    static VoiceController vc(Jarvis j) { return j.listen instanceof VoiceController ? (VoiceController) j.listen : null; }

    static File audioDir(Jarvis j) {
        File d = new File(j.home, "mcp/audio");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    static JSONObject statusJson(ProviderStatus s) {
        JSONObject o = new JSONObject();
        if (s == null) return o;
        J.put(o, "checkedAtMs", s.checkedAtMs);
        J.put(o, "ok", s.ok);
        J.put(o, "lastError", s.lastError);
        J.put(o, "firstAudioMs", s.firstAudioMs);
        J.put(o, "totalMs", s.totalMs);
        J.put(o, "detail", s.detail);
        return o;
    }

    /** Speaker output through the real provider, timed by the SpeechApi callbacks. */
    private static McpResult speakOut(Jarvis j, SpeechService ss, String text, String provider, String voice, float speed) {
        String pid = provider;
        if (pid.isEmpty()) {
            List<String> chain = ss.activeChain();
            if (chain.isEmpty()) return Tl.err("NOT_CONFIGURED", "Озвучка не настроена: включите провайдера в настройках голоса.", "tts chain empty");
            pid = chain.get(0);
        }
        if (ss.tts(pid) == null) return Tl.err("NOT_FOUND", "Провайдер озвучки «" + pid + "» не найден.", "tts " + pid);
        TtsRequest req = new TtsRequest();
        req.text = text;
        req.language = j.settings.get(Settings.STT_LANGUAGE, "ru");
        req.voiceId = voice.isEmpty() ? j.settings.get("tts.voice." + pid) : voice;
        req.speed = speed > 0 ? speed : j.settings.getFloat(Settings.TTS_SPEED, 1f);
        final CountDownLatch done = new CountDownLatch(1);
        final long[] first = {-1, -1};
        final String[] err = {null, null};
        ss.speakWith(pid, req, new SpeechApi.Test() {
            @Override public void onStarted() { }
            @Override public void onFirstAudio(long ms) { first[0] = ms; }
            @Override public void onDone(long totalMs) { first[1] = totalMs; done.countDown(); }
            @Override public void onError(String userMessage, String technical) { err[0] = userMessage; err[1] = technical; done.countDown(); }
        });
        try {
            if (!done.await(60, TimeUnit.SECONDS)) { ss.stop(); return Tl.err("TIMEOUT", "Озвучка в динамик не завершилась за 60 с.", pid); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Tl.err("CANCELLED", "Отменено.", "interrupted");
        }
        if (err[0] != null) return Tl.err("TTS_FAILED", err[0], err[1]);
        JSONObject o = new JSONObject();
        J.put(o, "output", "speaker");
        J.put(o, "provider", pid);
        J.put(o, "firstByteMs", first[0]);
        J.put(o, "totalMs", first[1]);
        J.put(o, "cleanedText", ss.prepareText(text, pid));
        J.put(o, "played", true);
        return McpResult.json(o);
    }

    private static long lastSeq(Jarvis j, long conv) {
        List<ChatItem> last = j.chat.items(conv, 0, 1);
        return last.isEmpty() ? 0 : last.get(0).seq;
    }

    /** The first finished voice turn that started after t0 (TraceStore keeps finished turns only), or null. */
    private static JSONObject finishedVoiceTurn(long t0) {
        for (JSONObject t : TraceStore.global().recent(30)) {
            if ("voice".equals(t.optString("source")) && t.optLong("start_ms") >= t0 - 1000) return t;
        }
        return null;
    }

    /** Input for STT or injection: a path inside the app's files or sandbox, or base64. Errors come back as {@link Fail}. */
    static Clip load(Jarvis j, JSONObject a) throws IOException {
        String path = J.str(a, "path"), b64 = J.str(a, "base64");
        if (path.isEmpty() == b64.isEmpty()) throw fail("INVALID_ARGS", "Нужен один из параметров: path или base64.", "path|base64");
        File file;
        boolean temp = false;
        if (!path.isEmpty()) {
            file = inAppFiles(j, path);
            if (!file.isFile()) throw fail("NOT_FOUND", "Файл не найден.", file.getPath());
        } else {
            byte[] data;
            try { data = Base64.decode(b64, Base64.DEFAULT); } catch (IllegalArgumentException e) { throw fail("INVALID_ARGS", "base64 не разобран.", e.getMessage()); }
            if (data.length > MAX_INPUT_BYTES) throw fail("TOO_LARGE", "Аудио больше 25 МБ.", "bytes=" + data.length);
            file = new File(audioDir(j), "in-" + System.nanoTime() + ".bin");
            try (FileOutputStream fo = new FileOutputStream(file)) { fo.write(data); }
            temp = true;
        }
        try {
            if (file.length() > MAX_INPUT_BYTES) throw fail("TOO_LARGE", "Аудио больше 25 МБ.", "bytes=" + file.length());
            byte[] all = readAll(file);
            boolean riff = all.length >= 12 && all[0] == 'R' && all[1] == 'I' && all[2] == 'F' && all[3] == 'F';
            if (riff) {
                PcmAudio w = PcmAudio.fromWav(all);
                if (w == null) throw fail("UNSUPPORTED", "WAV: поддерживается только PCM 16 бит.", "RIFF without PCM16 data");
                try {
                    return new Clip(PcmConvert.resample(PcmConvert.fromBytes(w.pcm), w.sampleRate, PcmConvert.STT_RATE), "wav", w.sampleRate, 1);
                } catch (IllegalArgumentException e) {
                    throw fail("UNSUPPORTED", "WAV с недопустимой частотой.", e.getMessage());
                }
            }
            Decoded d = decodePlatform(file);
            return new Clip(PcmConvert.to16kMono(d.pcm, d.rate, d.channels), "platform", d.rate, d.channels);
        } finally {
            if (temp) file.delete();
        }
    }

    /** Only files inside the app's files directory or the sandbox. */
    static File inAppFiles(Jarvis j, String path) {
        String p = path.trim();
        try {
            File home = j.home.getCanonicalFile();
            File abs = new File(p);
            if (abs.isAbsolute()) {
                File c = abs.getCanonicalFile();
                if (c.equals(home) || c.getPath().startsWith(home.getPath() + File.separator)) return c;
            }
            return j.sandbox.resolve(p);
        } catch (IOException e) {
            throw fail("FORBIDDEN", "Файл вне песочницы и файлов приложения.", path);
        }
    }

    private static byte[] readAll(File f) throws IOException {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream((int) Math.min(f.length(), Integer.MAX_VALUE));
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
            return o.toByteArray();
        } finally {
            in.close();
        }
    }

    private static final class Decoded {
        final short[] pcm;
        final int rate, channels;
        Decoded(short[] pcm, int rate, int channels) { this.pcm = pcm; this.rate = rate; this.channels = channels; }
    }

    /** Compressed audio through the platform: MediaExtractor finds the audio track, MediaCodec decodes it to PCM16. Device-only. */
    static Decoded decodePlatform(File f) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(f.getPath());
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat t = ex.getTrackFormat(i);
                String mime = t.containsKey(MediaFormat.KEY_MIME) ? t.getString(MediaFormat.KEY_MIME) : "";
                if (mime.startsWith("audio/")) { track = i; fmt = t; break; }
            }
            if (track < 0 || fmt == null) throw fail("UNSUPPORTED", "В файле нет звуковой дорожки, которую понимает Android.", f.getName());
            ex.selectTrack(track);
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();
            int rate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : PcmConvert.STT_RATE;
            int ch = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inEos = false, outEos = false;
            long deadline = System.currentTimeMillis() + 60_000;
            while (!outEos) {
                if (System.currentTimeMillis() > deadline || pcm.size() > 4 * MAX_INPUT_BYTES) throw new IOException("декодирование слишком долгое или слишком большое");
                if (!inEos) {
                    int ii = codec.dequeueInputBuffer(10_000);
                    if (ii >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(ii);
                        int n = buf == null ? -1 : ex.readSampleData(buf, 0);
                        if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true; }
                        else { codec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 10_000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                } else if (oi >= 0) {
                    if (info.size > 0) {
                        ByteBuffer ob = codec.getOutputBuffer(oi);
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        byte[] chunk = new byte[info.size];
                        ob.get(chunk);
                        pcm.write(chunk, 0, chunk.length);
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true;
                }
            }
            return new Decoded(PcmConvert.fromBytes(pcm.toByteArray()), rate, Math.max(1, ch));
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new IOException("декодер Android не справился: " + e.getMessage(), e);
        } finally {
            if (codec != null) { try { codec.stop(); } catch (RuntimeException ignored) { } codec.release(); }
            ex.release();
        }
    }
}
