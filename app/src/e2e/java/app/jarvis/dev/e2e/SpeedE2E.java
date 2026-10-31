package app.jarvis.dev.e2e;

import app.jarvis.core.trace.TraceStore;
import app.jarvis.dev.PreviewHost;
import app.jarvis.dev.PreviewKit;
import app.jarvis.rt.Jarvis;
import app.jarvis.rt.SpeechService;
import app.jarvis.rt.VoiceController;
import app.jarvis.rt.VoiceHub;
import java.io.File;
import org.json.JSONObject;

/**
 * Voice turns through the real chain with the microphone replaced by a recorded phrase and the speaker replaced by SilentOut:
 * Segmenter -> cloud STT -> wake word -> agent (real LLM, real tools) -> SpeechText -> Edge TTS -> MediaCodec decode.
 * Prints the TurnTrace of every turn. args: <apk> <outDir> <pcm>... ; env JV_KEY_OPENROUTER, JV_KEY_GROQ, JV_LLM_MODEL (optional)
 */
public final class SpeedE2E {
    static String env(String k) { String v = System.getenv(k); return v == null ? "" : v; }

    public static void main(String[] a) {
        try { run(a); } catch (Throwable t) { System.out.println("SPEEDE2E CRASH: " + t); t.printStackTrace(System.out); }
        System.out.flush();
        System.exit(0);
    }

    static void run(String[] a) throws Exception {
        PreviewHost.init(a[0], new File(a[1]));
        final Jarvis j = PreviewKit.jarvis();
        j.secrets.set("key.openrouter", env("JV_KEY_OPENROUTER"));
        if (!env("JV_KEY_GROQ").isEmpty()) j.secrets.set("key.groq", env("JV_KEY_GROQ"));
        j.settings.set("llm.provider", "openrouter");
        j.settings.set("llm.model.openrouter", env("JV_LLM_MODEL").isEmpty() ? "inception/mercury-3" : env("JV_LLM_MODEL"));
        j.settings.set("llm.fallbacks", "");
        j.settings.set("llm.reasoning", "off");
        j.settings.set("tts.chain", "edge,gtts");
        j.settings.set("tts.auto", "always");
        j.settings.set("stt.chain", env("JV_KEY_GROQ").isEmpty() ? "google-stt" : "groq-stt,google-stt");
        j.settings.set("wake.stt", "google-stt");
        j.settings.set("tts.cache.mb", "8");
        j.shell.cwd();

        VoiceHub hub = new VoiceHub(j);
        SpeechService speech = new SpeechService(j, new SilentOut(), hub);
        j.speaker = speech;
        j.speech = speech;
        FakeMic mic = new FakeMic();
        VoiceController vc = new VoiceController(j, mic, hub);
        vc.assumePermission(true);
        j.voice = vc;
        j.listen = vc;
        vc.setWakeEnabled(true);
        System.out.println("armed: " + vc.isWakeEnabled() + " mic running: " + mic.isRunning());

        TraceStore st = TraceStore.global();
        for (int i = 2; i < a.length; i++) {
            int before = st.size();
            long t0 = System.currentTimeMillis();
            mic.play(FakeMic.read(a[i]), 700, 1600, 8);
            long end = System.currentTimeMillis() + 90_000;
            while (System.currentTimeMillis() < end && st.size() == before) Thread.sleep(200);
            Thread.sleep(500);
            System.out.println("== " + new File(a[i]).getName() + ": finished after " + (System.currentTimeMillis() - t0) + " ms (incl. feeding the audio)");
            for (JSONObject o : st.recent(1)) {
                System.out.println("summary: " + o.optString("summary"));
                System.out.println("trace: " + o);
            }
            while (j.speaker.isSpeaking() || j.agent.isRunning()) Thread.sleep(200);
            Thread.sleep(1500);
        }
        vc.setWakeEnabled(false);
        System.out.println("turns recorded: " + st.size());
    }
}
