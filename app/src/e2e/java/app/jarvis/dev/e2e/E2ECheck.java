package app.jarvis.dev.e2e;

import app.jarvis.dev.PreviewHost;
import app.jarvis.dev.PreviewKit;
import app.jarvis.data.ChatItem;
import app.jarvis.media.MusicOut;
import app.jarvis.rt.AState;
import app.jarvis.rt.Jarvis;
import app.jarvis.rt.JarvisListener;
import app.jarvis.rt.VoiceController;
import app.jarvis.rt.VoiceHub;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * End-to-end scenarios on the phone's real Android runtime with REAL network services and REAL audio output, except that the
 * microphone is replaced by audio files (Termux has no RECORD_AUDIO): file → Segmenter → cloud STT → wake word → agent/direct
 * command → tools → player / speech synthesis → speaker. Prints a pass/fail table with timings.
 *   args: <apk> <outDir>    env: JV_KEY_OPENROUTER, JV_KEY_GROQ, JV_KEY_ELEVEN (optional), JV_E2E_AUDIO (dir with g_*.pcm), JV_E2E_MUSIC
 */
public final class E2ECheck {
    static int fails;
    static final List<String> rows = new ArrayList<>();

    static void check(boolean ok, String what, String detail) {
        String line = (ok ? "  PASS " : "  FAIL ") + what + (detail == null || detail.isEmpty() ? "" : "  —  " + detail);
        System.out.println(line);
        System.out.flush();
        rows.add(line);
        if (!ok) fails++;
    }

    public static void main(String[] a) {
        try { run(a); } catch (Throwable t) { System.out.println("E2E CRASH: " + t); t.printStackTrace(System.out); fails++; }
        System.out.println(fails == 0 ? "E2E OK" : "E2E FAILURES: " + fails);
        System.out.flush();
        System.exit(fails == 0 ? 0 : 1);
    }

    static String env(String k) { String v = System.getenv(k); return v == null ? "" : v; }

    static boolean waitFor(long ms, java.util.concurrent.Callable<Boolean> c) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { if (c.call()) return true; Thread.sleep(120); }
        return c.call();
    }

    static List<ChatItem> chat(Jarvis j) { return j.chat.items(j.chat.currentConversation(), 0, 200); }

    static String lastAssistant(Jarvis j) {
        List<ChatItem> it = chat(j);
        for (int i = it.size() - 1; i >= 0; i--) if (it.get(i).kind == ChatItem.Kind.ASSISTANT && !it.get(i).text.isEmpty()) return it.get(i).text;
        return "";
    }

    static boolean hasTool(Jarvis j, String name, boolean ok) {
        for (ChatItem it : chat(j)) if (it.kind == ChatItem.Kind.TOOL && it.toolName.equals(name) && it.ok == ok && it.toolStatus != ChatItem.ToolStatus.RUNNING) return true;
        return false;
    }

    static int userCount(Jarvis j) { int n = 0; for (ChatItem it : chat(j)) if (it.kind == ChatItem.Kind.USER) n++; return n; }

    static void run(String[] a) throws Exception {
        PreviewHost.init(a[0], new File(a[1]));
        final Jarvis j = PreviewKit.jarvis();
        String audio = env("JV_E2E_AUDIO");
        System.out.println("== setup");
        if (!env("JV_KEY_OPENROUTER").isEmpty()) j.secrets.set("key.openrouter", env("JV_KEY_OPENROUTER"));
        if (!env("JV_KEY_GROQ").isEmpty()) j.secrets.set("key.groq", env("JV_KEY_GROQ"));
        if (!env("JV_KEY_ELEVEN").isEmpty()) j.secrets.set("key.elevenlabs", env("JV_KEY_ELEVEN"));
        j.settings.set("llm.provider", "openrouter");
        j.settings.set("llm.model.openrouter", "inception/mercury-3");
        j.settings.set("llm.fallbacks", "");
        j.settings.set("llm.reasoning", "off");
        j.settings.set("tts.chain", env("JV_E2E_TTS").isEmpty() ? "edge,gtts" : env("JV_E2E_TTS"));
        j.settings.set("tts.auto", "always");
        j.settings.set("stt.chain", "groq-stt,google-stt");
        j.settings.set("wake.stt", "google-stt");
        j.settings.set("user.call", "папочка");
        File music = new File(j.sandbox.music, "morning.mp3");
        java.nio.file.Files.copy(new File(env("JV_E2E_MUSIC")).toPath(), music.toPath());
        j.settings.set("music.favorite", "music/morning.mp3");
        j.shell.cwd();

        final List<String> states = new ArrayList<>();
        j.addListener(new JarvisListener() {
            @Override public void onState(AState s, String d) { if (states.isEmpty() || !states.get(states.size() - 1).equals(s.name())) states.add(s.name()); }
            @Override public void onToast(String m, int k) { System.out.println("   [toast " + k + "] " + m); }
        });
        check(j.speaker != app.jarvis.rt.Speaker.NONE, "SpeechService attached", "");
        check(j.music != null, "MusicPlayer attached", "");

        FakeMic mic = new FakeMic();
        VoiceHub hub = new VoiceHub(j);
        VoiceController vc = new VoiceController(j, mic, hub);
        vc.assumePermission(true);
        j.voice = vc;
        j.listen = vc;
        vc.setWakeEnabled(true);
        check(vc.isWakeEnabled() && mic.isRunning(), "wake mode armed on the (fake) microphone", vc.micProblem());

        // ---- 1. morning scenario ------------------------------------------------------------------------------------
        System.out.println("== 1. «Джарвис, папочка проснулся»");
        int users0 = userCount(j);
        long t0 = System.currentTimeMillis();
        mic.play(FakeMic.read(audio + "/g_cmd_morning.pcm"), 700, 1600, 8);
        long fedMs = System.currentTimeMillis() - t0;
        boolean sub = waitFor(30000, new java.util.concurrent.Callable<Boolean>() { public Boolean call() { return userCount(j) > users0; } });
        long sttMs = System.currentTimeMillis() - t0;
        check(sub, "wake word heard, command recognised and submitted", "feed " + fedMs + " ms, submitted after " + sttMs + " ms");
        String cmdText = "";
        for (ChatItem it : chat(j)) if (it.kind == ChatItem.Kind.USER) cmdText = it.text;
        check(cmdText.toLowerCase().contains("проснул"), "command text keeps the start (wake word stripped)", "«" + cmdText + "»");
        boolean played = waitFor(25000, new java.util.concurrent.Callable<Boolean>() { public Boolean call() { return hasTool(j, "music_play", true); } });
        check(played, "music_play executed by a real tool call", "");
        MusicOut.Info mi = j.music.info();
        check(mi.state == MusicOut.State.PLAYING, "player is really PLAYING", mi.state + " " + mi.title + " pos=" + mi.positionMs + "/" + mi.durationMs);
        Thread.sleep(1500);
        check(j.music.info().positionMs > 500, "playback position advances", "pos=" + j.music.info().positionMs);
        check(waitFor(20000, new java.util.concurrent.Callable<Boolean>() { public Boolean call() { return !lastAssistant(j).isEmpty(); } }), "assistant reply text", "«" + lastAssistant(j) + "»");
        check(waitFor(30000, new java.util.concurrent.Callable<Boolean>() { public Boolean call() { return !j.speaker.isSpeaking() && j.state() != AState.THINKING && j.state() != AState.TOOL; } }), "spoken reply finished, assistant idle", "states " + states);

        // ---- 2. status ----------------------------------------------------------------------------------------------
        System.out.println("== 2. «Джарвис, что ты сейчас делаешь?»");
        Thread.sleep(900);
        int u2 = userCount(j);
        mic.play(FakeMic.read(audio + "/g_cmd_status.pcm"), 500, 1600, 8);
        check(waitFor(30000, new java.util.concurrent.Callable<Boolean>() { public Boolean call() { return userCount(j) > u2; } }), "status command recognised", "");
        check(waitFor(8000, new java.util.concurrent.Callable<Boolean>() { public Boolean call() { return lastAssistant(j).startsWith("Сейчас"); } }), "answer built from real state", "«" + lastAssistant(j) + "»");
        waitFor(20000, new java.util.concurrent.Callable<Boolean>() { public Boolean call() { return !j.speaker.isSpeaking(); } });

        // ---- 3. stop music ------------------------------------------------------------------------------------------
        System.out.println("== 3. «Джарвис, останови музыку»");
        Thread.sleep(900);
        int u3 = userCount(j);
        mic.play(FakeMic.read(audio + "/g_cmd_stopmusic.pcm"), 500, 1600, 8);
        check(waitFor(30000, new java.util.concurrent.Callable<Boolean>() { public Boolean call() { return userCount(j) > u3; } }), "stop command recognised", "");
        check(waitFor(10000, new java.util.concurrent.Callable<Boolean>() { public Boolean call() { return hasTool(j, "music_stop", true); } }), "music_stop tool ok", "");
        check(j.music.info().state != MusicOut.State.PLAYING, "player really stopped", String.valueOf(j.music.info().state));
        waitFor(20000, new java.util.concurrent.Callable<Boolean>() { public Boolean call() { return !j.speaker.isSpeaking(); } });

        // ---- 4. negatives -------------------------------------------------------------------------------------------
        System.out.println("== 4. фразы без обращения");
        Thread.sleep(900);
        int u4 = userCount(j);
        mic.play(FakeMic.read(audio + "/g_neg_weather.pcm"), 500, 1600, 8);
        mic.play(FakeMic.read(audio + "/g_neg_service.pcm"), 500, 1600, 8);
        Thread.sleep(9000);
        check(userCount(j) == u4, "speech without the wake word is ignored", "users " + u4 + " -> " + userCount(j));

        // ---- 5. LLM + real tool ----------------------------------------------------------------------------------------
        System.out.println("== 5. «Джарвис, создай заметку…» (модель + инструмент)");
        int u5 = userCount(j);
        long t5 = System.currentTimeMillis();
        mic.play(FakeMic.read(audio + "/g_cmd_note.pcm"), 500, 1600, 8);
        check(waitFor(30000, new java.util.concurrent.Callable<Boolean>() { public Boolean call() { return userCount(j) > u5; } }), "note command recognised", "");
        check(waitFor(60000, new java.util.concurrent.Callable<Boolean>() { public Boolean call() { return hasTool(j, "note_create", true); } }), "note_create tool executed", (System.currentTimeMillis() - t5) + " ms from feed start");
        File notes = new File(j.sandbox.docs, "notes");
        String[] fs = notes.list();
        boolean exists = fs != null && fs.length > 0;
        String body = "";
        if (exists) body = new String(java.nio.file.Files.readAllBytes(new File(notes, fs[0]).toPath()), "UTF-8");
        check(exists && body.toLowerCase().contains("хлеб"), "the note file really exists with the content", exists ? fs[0] + " «" + body.replace('\n', ' ').trim() + "»" : "no file");
        waitFor(30000, new java.util.concurrent.Callable<Boolean>() { public Boolean call() { return !j.speaker.isSpeaking() && !j.agent.isRunning(); } });

        System.out.println("== summary");
        System.out.println("states seen: " + states);
        vc.setWakeEnabled(false);
        j.music.stop();
    }
}
