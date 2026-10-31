package app.jarvis.core.audio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Detects the Russian wake word ("джарвис" and its realistic ASR mishearings) in a speech-recognition transcript
 * and splits off the command that follows it.
 *
 * Matching pipeline: normalize (lowercase, ё→е, punctuation→spaces, Latin homoglyphs folded to Cyrillic, "дж"→"ж"),
 * then per-word weighted alignment of transcript words against every phrase word n-gram. Onset substitutions (first
 * letter of a word, phrase word ≥ 4 chars) are heavily penalized — "гарвис"/"карвис"/"дарвин" must not trigger —
 * while onset deletions ("ярвис") and mid-word confusions (unstressed а/о/я, и/е/э/ы, с/з, б/п, д/т, ф/в, г/к) cost
 * little. The wake phrase must start within the first {@value #POSITION_WORDS} words of the transcript, so a
 * "…и потом сказал, что джарвис ему помог" deep inside a story does not match.
 */
public final class WakeMatcher {

    public static final class Match {
        public static final Match NONE = new Match(false, "", "", 0f, false);
        public final boolean found;
        /** The matched span of the original transcript (the wake phrase as heard). */
        public final String heard;
        /** Everything after the wake phrase (normalized, punctuation stripped); may be empty. */
        public final String command;
        public final float score;
        public final boolean positionOk;

        Match(boolean found, String heard, String command, float score, boolean positionOk) {
            this.found = found; this.heard = heard; this.command = command; this.score = score; this.positionOk = positionOk;
        }
    }

    /** Wake phrase must start at word index 0..POSITION_WORDS-1 ("at the beginning or within the first 3 words"). */
    private static final int POSITION_WORDS = 3;
    // substitutions inside these classes cost 0 (careless ASR merges unstressed vowels and voiced/voiceless pairs)
    private static final String[] CONFUSION = {"аояеиэы", "сз", "бп", "дт", "фв", "гк"};
    private static final float COST_MAX_SHORT = 1f; // single-word phrases of length ≤ 4
    private static final float COST_MAX = 2f;

    private final List<String[]> phrases = new ArrayList<>();
    private final List<String> phrasesRaw = new ArrayList<>();

    public WakeMatcher(List<String> phrases) {
        Set<String> uniq = new LinkedHashSet<>();
        for (String p : phrases) {
            String n = normalize(p).trim();
            if (n.length() > 0 && uniq.add(n)) {
                this.phrases.add(n.split(" +"));
                this.phrasesRaw.add(p.trim());
            }
        }
    }

    public static WakeMatcher defaultRu() {
        return new WakeMatcher(Arrays.asList(
            "джарвис", "джарвиз", "жарвис", "джервис", "джарвес", "ярвис", "эй джарвис",
            "привет джарвис", "хей джарвис", "jarvis"));
    }

    public Match match(String transcript) {
        if (transcript == null) return Match.NONE;
        String rawTrim = transcript.trim();
        if (rawTrim.isEmpty()) return Match.NONE;
        // normalize per token so indices stay aligned with the raw words (pure-punctuation tokens vanish in both)
        List<String> words = new ArrayList<>(), raws = new ArrayList<>();
        for (String t : rawTrim.split("\\s+")) {
            String n = normalize(t).trim();
            if (!n.isEmpty()) { words.add(n); raws.add(clean(t)); }
        }
        int bestStart = -1, bestPhrase = -1;
        float bestCost = Float.MAX_VALUE;
        for (int p = 0; p < phrases.size(); p++) {
            String[] ph = phrases.get(p);
            for (int s = 0; s + ph.length <= words.size(); s++) { // scan ALL positions; the position rule gates `found`
                float cost = 0;
                for (int i = 0; i < ph.length; i++) cost += wordCost(words.get(s + i), ph[i]);
                if (cost < bestCost || (cost == bestCost && bestPhrase >= 0 && ph.length > phrases.get(bestPhrase).length)) {
                    bestCost = cost; bestStart = s; bestPhrase = p;
                }
            }
        }
        if (bestStart < 0) return Match.NONE;
        String[] ph = phrases.get(bestPhrase);
        float denom = 0;
        for (String w : ph) denom += w.length();
        float maxCost = ph.length == 1 && ph[0].length() <= 4 ? COST_MAX_SHORT : COST_MAX;
        float score = Math.max(0f, 1f - bestCost / denom);
        boolean positionOk = bestStart < POSITION_WORDS;
        if (bestCost > maxCost || !positionOk) {
            return new Match(false, span(raws, bestStart, bestStart + ph.length), "", score, positionOk);
        }
        return new Match(true, span(raws, bestStart, bestStart + ph.length),
            span(raws, bestStart + ph.length, raws.size()), score, true);
    }

    /** Strips punctuation but keeps letters/digits and the original case. */
    private static String clean(String token) {
        StringBuilder sb = new StringBuilder(token.length());
        for (int i = 0; i < token.length(); i++) if (Character.isLetterOrDigit(token.charAt(i))) sb.append(token.charAt(i));
        return sb.toString();
    }

    private static String span(List<String> raw, int from, int to) {
        if (from < 0 || to > raw.size() || from >= to) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) { if (sb.length() > 0) sb.append(' '); sb.append(raw.get(i)); }
        return sb.toString();
    }

    /** Normalization shared by phrases and transcripts: lower, ё→е, punctuation→spaces, homoglyph fold, дж→ж. */
    public static String normalize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean space = true;
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if (c == 'ё') c = 'е';
            if (Character.isLetterOrDigit(c)) {
                if (space && sb.length() > 0) sb.append(' ');
                space = false;
                sb.append(fold(c));
            } else if (!space) {
                space = true;
            }
        }
        // phonetic collapse: Russian "дж" is one sound; folding it makes джарвис == жарвис
        for (int i = sb.length() - 2; i >= 0; i--)
            if (sb.charAt(i) == 'д' && sb.charAt(i + 1) == 'ж') sb.deleteCharAt(i);
        return sb.toString();
    }

    /** Latin→Cyrillic homoglyph fold, only where unambiguous (used for ASR transcripts that mix scripts). */
    private static char fold(char c) {
        switch (c) {
            case 'a': return 'а'; case 'c': return 'с'; case 'e': return 'е'; case 'o': return 'о';
            case 'p': return 'р'; case 's': return 'с'; case 'x': return 'х'; case 'y': return 'у'; case 'k': return 'к';
            default: return c;
        }
    }

    private static boolean sameClass(char a, char b) {
        if (a == b) return true;
        for (String cl : CONFUSION) if (cl.indexOf(a) >= 0 && cl.indexOf(b) >= 0) return true;
        return false;
    }

    private static final float COST_INF = 99f;

    /**
     * Alignment cost between one normalized transcript word and one normalized phrase word. When the onsets (first
     * letters) are in the same confusion class a weighted Levenshtein applies: insert/delete cost 1, substitution 0
     * within a confusion class, 1 otherwise. When the onsets differ, only a dropped onset ("ярвис" ← жарвис; the
     * remainder must still start with a same-class onset) is tolerated at cost 1; everything else does not match.
     * This is what rejects "гарвис"/"карвис"/"дарвин": each is one onset substitution away, so plain
     * Levenshtein ≤ 2 would wrongly accept them.
     */
    static float wordCost(String w, String p) {
        int n = w.length(), m = p.length();
        if (n == 0 || m == 0) return Math.max(n, m);
        float cost;
        if (m >= 4) {
            if (sameClass(w.charAt(0), p.charAt(0))) cost = lev(w, p);
            else if (sameClass(w.charAt(0), p.charAt(1))) cost = 1 + lev(w, p.substring(1)); // phrase onset dropped
            else return COST_INF;
        } else {
            cost = lev(w, p);
        }
        // a candidate 2+ letters shorter/longer with 2+ cost edits ("жара" ≈ "жарвес") is a different word
        if (Math.abs(n - m) >= 2 && cost > 1) return COST_INF;
        return cost;
    }

    private static float lev(String w, String p) {
        int n = w.length(), m = p.length();
        float[] prev = new float[m + 1], cur = new float[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            for (int j = 1; j <= m; j++) {
                float sub = sameClass(w.charAt(i - 1), p.charAt(j - 1)) ? 0 : 1;
                cur[j] = Math.min(prev[j - 1] + sub, Math.min(prev[j] + 1, cur[j - 1] + 1));
            }
            float[] t = prev; prev = cur; cur = t;
        }
        return prev[m];
    }

    /** For tests/diagnostics. */
    public List<String> phraseList() { return phrasesRaw; }
}
