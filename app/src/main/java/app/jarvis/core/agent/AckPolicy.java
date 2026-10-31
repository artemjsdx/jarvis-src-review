package app.jarvis.core.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Quick acknowledgement ("Секунду.") for turns where the model is slow to start answering. One instance per app; {@link #arm} per turn.
 * Rules: at most one ack per turn; never the same phrase twice in a row; never when real text started first; when the real answer arrives
 * while the ack is audible it is cut, unless less than {@link #LET_FINISH_MS} of it is left.
 */
public final class AckPolicy {
    public enum Mode {
        OFF, VOICE, ALWAYS;
        public static Mode parse(String s) {
            if ("off".equalsIgnoreCase(s)) return OFF;
            if ("always".equalsIgnoreCase(s)) return ALWAYS;
            return VOICE;
        }
    }

    public interface Player {
        /** Starts the phrase (from the audio cache, no synthesis on the critical path). False when it cannot start now. */
        boolean play(String phrase);
        /** Cuts the ack that is playing or queued. */
        void stop();
        /** Milliseconds of the ack still to be heard; 0 when finished or unknown, so an unknown ack is never cut in the middle by mistake. */
        long remainingMs();
    }

    public enum TextEffect { NOTHING_PENDING, TIMER_CANCELLED, STOPPED, LEFT_TO_FINISH }

    public static final long LET_FINISH_MS = 300;

    /** The setting {@code ack.phrases}: phrases separated by '|' (they contain commas); empty = the built-in list. */
    public static List<String> parsePhrases(String setting) {
        List<String> out = new ArrayList<>();
        if (setting != null) for (String s : setting.split("\\|")) if (!s.trim().isEmpty()) out.add(s.trim());
        if (out.isEmpty()) for (String s : FastPhrases.ACK_DEFAULT) out.add(s);
        return out;
    }

    private final ScheduledExecutorService sch;
    private final Player player;
    private final Random rnd = new Random();
    private final Object lock = new Object();
    private List<String> phrases = new ArrayList<>();
    private String lastPhrase = "";
    private ScheduledFuture<?> timer;
    private int generation;
    private boolean playing, textSeen;

    public AckPolicy(ScheduledExecutorService sch, Player player, List<String> phrases) {
        this.sch = sch;
        this.player = player;
        setPhrases(phrases);
    }

    public void setPhrases(List<String> p) {
        synchronized (lock) { phrases = new ArrayList<>(); if (p != null) for (String s : p) if (s != null && !s.trim().isEmpty()) phrases.add(s.trim()); }
    }

    /** A new turn starts; if no real text arrived within delayMs, the ack is played. Returns the turn's token for {@link #onRealText(int)}/{@link #disarm(int)}. */
    public int arm(long delayMs) {
        synchronized (lock) {
            cancelTimerLocked();
            textSeen = false;
            playing = false;
            final int gen = ++generation;
            timer = sch.schedule(new Runnable() { @Override public void run() { fire(gen); } }, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
            return gen;
        }
    }

    private void fire(int gen) {
        String phrase;
        synchronized (lock) {
            if (gen != generation || textSeen || playing) return;
            phrase = pickLocked();
            if (phrase.isEmpty()) return;
            playing = true;
        }
        boolean started;
        try { started = player.play(phrase); } catch (RuntimeException e) { started = false; }
        synchronized (lock) {
            if (!started) { playing = false; return; }
            lastPhrase = phrase;
            if (gen != generation || textSeen) { /* the answer arrived while we were starting */ }
        }
        // text may have arrived between the decision and the start: cut it now (cheap, idempotent)
        boolean late;
        synchronized (lock) { late = gen != generation || textSeen; }
        if (late) cut();
    }

    /** The first real text of the turn {@code token} is about to be spoken. Call before handing it to the speaker; a stale token (older turn) does nothing. */
    public TextEffect onRealText(int token) {
        synchronized (lock) { if (token != generation) return TextEffect.NOTHING_PENDING; }
        return cut();
    }

    private TextEffect cut() {
        boolean wasPlaying;
        boolean hadTimer;
        synchronized (lock) {
            textSeen = true;
            hadTimer = timer != null && !timer.isDone();
            cancelTimerLocked();
            wasPlaying = playing;
            playing = false;
        }
        if (!wasPlaying) return hadTimer ? TextEffect.TIMER_CANCELLED : TextEffect.NOTHING_PENDING;
        long left;
        try { left = player.remainingMs(); } catch (RuntimeException e) { left = 0; }
        if (left > 0 && left < LET_FINISH_MS) return TextEffect.LEFT_TO_FINISH;
        if (left == 0) { try { player.stop(); } catch (RuntimeException ignored) { } return TextEffect.STOPPED; }
        try { player.stop(); } catch (RuntimeException ignored) { }
        return TextEffect.STOPPED;
    }

    /** The turn {@code token} is over (or cancelled): no timer may fire for it any more. A newer turn is left alone. */
    public void disarm(int token) {
        synchronized (lock) { if (token != generation) return; cancelTimerLocked(); generation++; playing = false; }
    }

    public String lastPhrase() { synchronized (lock) { return lastPhrase; } }

    private void cancelTimerLocked() {
        if (timer != null) { timer.cancel(false); timer = null; }
    }

    private String pickLocked() {
        List<String> c = new ArrayList<>();
        for (String s : phrases) if (!s.equals(lastPhrase)) c.add(s);
        if (c.isEmpty()) { lastPhrase = ""; return ""; }     // a single configured phrase: skip this turn, allow it again next turn
        return c.get(rnd.nextInt(c.size()));
    }
}
