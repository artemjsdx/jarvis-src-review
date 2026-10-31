package app.jarvis.media;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Local audio-file player with queue, audio focus and a media notification/session. */
public interface MusicOut {
    enum State { IDLE, PLAYING, PAUSED, STOPPED, ERROR }

    final class Info {
        public State state = State.IDLE;
        public String title = "", path = "", error = "";
        public long positionMs, durationMs;
        public int index = -1, queueSize;
        public float volume = 1f;
    }

    interface Listener {
        /** Any change of state/track; position updates are NOT reported here (poll info()). Main or any thread. */
        void onMusicChanged(Info info);
    }

    void setListener(Listener l);

    /**
     * Starts playback and returns only after the player really started (prepared, focus granted, start() called) or
     * throws IOException with the reason (file missing, unsupported format, focus denied...). Never lies about success.
     */
    void play(File file, String title) throws IOException;

    void playQueue(List<File> files, int startIndex) throws IOException;

    void pause();

    void resume();

    void stop();

    void next();

    void prev();

    void seekTo(long ms);

    void setVolume(float v);

    Info info();

    void release();
}
