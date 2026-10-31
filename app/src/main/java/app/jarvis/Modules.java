package app.jarvis;

import android.content.Context;
import app.jarvis.core.adb.AdbClient;
import app.jarvis.core.log.Journal;
import app.jarvis.media.MediaNotifications;
import app.jarvis.media.MicCapture;
import app.jarvis.media.MusicOut;
import app.jarvis.media.SpeechOut;
import app.jarvis.rt.Jarvis;
import app.jarvis.rt.SpeechService;
import app.jarvis.rt.VoiceController;
import app.jarvis.rt.VoiceHub;
import app.jarvis.svc.JarvisService;

/**
 * Wires the platform-dependent modules (audio output/input, music, speech providers, ADB) into the runtime. Every module is
 * attached in its own try/catch: a module that fails to start disables one feature, with a journal entry, not the app.
 */
public final class Modules {
    private Modules() { }

    public static void attach(final Context app, final Jarvis j) {
        try {
            j.attachAdb(new AdbClient(new java.io.File(j.home, "adb"), j.journal));
        } catch (Throwable t) {
            j.journal.e(Journal.Cat.ADB, "ADB-модуль не подключён", t);
        }

        final VoiceHub hub;
        try {
            hub = new VoiceHub(j);
        } catch (Throwable t) {
            j.journal.e(Journal.Cat.TTS, "Голосовые провайдеры не подключены", t);
            return;
        }

        try {
            SpeechOut out = (SpeechOut) construct("app.jarvis.media.SpeechPlayer", app);
            SpeechService speech = new SpeechService(j, out, hub);
            j.speaker = speech;
            j.speech = speech;
            j.main.postDelayed(new Runnable() { @Override public void run() { ((SpeechService) j.speech).prewarm(); } }, 25_000);
        } catch (Throwable t) {
            j.journal.e(Journal.Cat.PLAYER, "Озвучка не подключена: " + t, t);
        }

        try {
            MusicOut music = (MusicOut) construct("app.jarvis.media.MusicPlayer", app);
            music.setListener(new MusicOut.Listener() {
                @Override public void onMusicChanged(MusicOut.Info info) {
                    j.musicChanged(info);
                    JarvisService.sync(app);
                }
            });
            j.music = music;
            MediaNotifications.setActionHandler(new MediaNotifications.ActionHandler() {
                @Override public void onAction(String action) {
                    MusicOut m = j.music;
                    if (m == null) return;
                    try {
                        switch (action) {
                            case MediaNotifications.ACTION_PLAY: m.resume(); break;
                            case MediaNotifications.ACTION_PAUSE: m.pause(); break;
                            case MediaNotifications.ACTION_NEXT: m.next(); break;
                            case MediaNotifications.ACTION_PREV: m.prev(); break;
                            case MediaNotifications.ACTION_STOP: m.stop(); break;
                            default: break;
                        }
                    } catch (Throwable t) { j.journal.e(Journal.Cat.PLAYER, "Команда плеера из уведомления: " + t, t); }
                }
            });
        } catch (Throwable t) {
            j.journal.e(Journal.Cat.PLAYER, "Плеер музыки не подключён: " + t, t);
        }

        try {
            VoiceController vc = new VoiceController(j, new MicCapture(), hub);
            j.voice = vc;
            j.listen = vc;
        } catch (Throwable t) {
            j.journal.e(Journal.Cat.WAKE, "Микрофон не подключён: " + t, t);
        }
    }

    /** Looks the class up by name so the app still builds (and degrades) while a module is being written. */
    private static Object construct(String cls, Context app) throws Exception {
        Class<?> c = Class.forName(cls);
        try { return c.getConstructor(Context.class).newInstance(app); }
        catch (NoSuchMethodException e) { return c.getConstructor().newInstance(); }
    }
}
