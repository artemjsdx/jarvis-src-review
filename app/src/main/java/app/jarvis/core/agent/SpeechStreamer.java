package app.jarvis.core.agent;

/**
 * Cuts a growing text stream into speakable pieces at sentence ends so speech can start while the model still writes.
 * The first piece is allowed to be short (time to first audio), later ones aim for natural phrases. The first piece may also end at a
 * comma or dash once it is long enough ({@link #CLAUSE_MIN}): waiting for the end of a long first sentence delays the first sound by the
 * time the model needs to write it, and synthesis of a short clause starts sooner and returns sooner (measured: TTS to first sound 0.8 s).
 * Fenced code blocks (```...```) are never spoken: text after an opening fence is held back until the closing
 * fence arrives, then the whole block is dropped. Pieces without a single letter or digit ("...", "—") are dropped too.
 */
public final class SpeechStreamer {
    public interface Out { void piece(String text); }

    private static final String FENCE = "```";
    /** The first piece may end at a comma or dash after this many characters. Shorter fragments ("Да,") sound broken when cut off. */
    public static final int CLAUSE_MIN = 16;

    private final StringBuilder buf = new StringBuilder();
    private final Out out;
    private boolean first = true;
    private boolean firstClauseCut = true;
    private final int firstMin, nextMin;

    public SpeechStreamer(Out out) { this(out, 10, 40); }

    public SpeechStreamer(Out out, int firstMin, int nextMin) { this.out = out; this.firstMin = firstMin; this.nextMin = nextMin; }

    /** Whether the first piece may end at a comma/dash (default on; setting tts.firstClause). */
    public SpeechStreamer firstClauseCut(boolean on) { this.firstClauseCut = on; return this; }

    public void feed(String delta) {
        buf.append(delta);
        dropClosedFences();
        int min = first ? firstMin : nextMin;
        while (true) {
            int cut = findCut(min, speakableLimit(), first && firstClauseCut);
            if (cut < 0) return;
            String piece = buf.substring(0, cut).trim();
            buf.delete(0, cut);
            if (emit(piece)) min = nextMin;
        }
    }

    public void flush() {
        dropClosedFences();
        String rest = buf.substring(0, speakableLimit()).trim();   // an unclosed code block is dropped
        buf.setLength(0);
        emit(rest);
    }

    public void reset() { buf.setLength(0); first = true; }

    private boolean emit(String piece) {
        if (!hasLetterOrDigit(piece)) return false;
        out.piece(piece);
        first = false;
        return true;
    }

    private static boolean hasLetterOrDigit(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isLetterOrDigit(s.charAt(i))) return true;
        return false;
    }

    private void dropClosedFences() {
        int open;
        while ((open = buf.indexOf(FENCE)) >= 0) {
            int close = buf.indexOf(FENCE, open + FENCE.length());
            if (close < 0) return;
            buf.delete(open, close + FENCE.length());
        }
    }

    /** Text before an unclosed opening fence is speakable; everything after it waits. */
    private int speakableLimit() {
        int open = buf.indexOf(FENCE);
        return open < 0 ? buf.length() : open;
    }

    /** Index just after the first sentence end at or beyond min characters (before limit), or -1. */
    private int findCut(int min, int limit, boolean clauseCut) {
        for (int i = 0; i < limit; i++) {
            char c = buf.charAt(i);
            boolean end = c == '.' || c == '!' || c == '?' || c == '…' || c == '\n' || c == ';' || (c == ':' && i > 25);
            boolean clause = clauseCut && i + 1 >= CLAUSE_MIN && (c == ',' || c == '—' || c == '–');
            if (!(end || clause) || i + 1 < min) continue;
            if (clause && c == ',' && i > 0 && Character.isDigit(buf.charAt(i - 1))) {
                // "1,5" is a number, not a clause end: look at what follows before deciding
                if (i + 1 >= limit) return -1;
                if (Character.isDigit(buf.charAt(i + 1))) continue;
            }
            if (i + 1 < limit) {
                char n = buf.charAt(i + 1);
                if (c == '.' && Character.isDigit(n)) continue;           // 3.5
                if (c == '.' && Character.isLowerCase(n)) continue;       // abbreviations like "т.е."
                int j = i + 1;
                while (j < limit && (buf.charAt(j) == '"' || buf.charAt(j) == '»' || buf.charAt(j) == ')' || buf.charAt(j) == '.')) j++;
                if (j < limit && !Character.isWhitespace(buf.charAt(j))) continue;
                return j;
            }
            if (limit < buf.length()) return i + 1;   // the sentence ends right before a code fence: nothing more can follow it
            return -1;  // sentence end is the last char: wait for the next delta to know it is not "3." of "3.5"
        }
        return -1;
    }
}
