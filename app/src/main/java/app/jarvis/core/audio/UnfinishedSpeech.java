package app.jarvis.core.audio;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Heuristic: does a recognised phrase end in the middle of a thought (conjunction, preposition, comma)? Then the speaker probably paused, not finished. */
public final class UnfinishedSpeech {
    private UnfinishedSpeech() { }

    private static final Set<String> TAIL = new HashSet<>(Arrays.asList(
        "и", "а", "но", "или", "да", "что", "чтобы", "если", "когда", "потому", "как", "ещё", "еще", "также", "тоже",
        "в", "на", "с", "со", "к", "ко", "по", "из", "за", "для", "от", "до", "про", "о", "об", "у", "при", "над", "под", "без", "через",
        "мой", "моя", "моё", "мою", "мои", "эту", "этот", "эта", "это", "какую", "какой", "какая", "который", "которая"));

    public static boolean isUnfinished(String text) {
        if (text == null) return false;
        String t = text.trim();
        if (t.isEmpty()) return false;
        char last = t.charAt(t.length() - 1);
        if (last == ',' || last == '—' || last == '-' || last == ':' || last == ';') return true;
        if (last == '.' || last == '!' || last == '?' || last == '…') return false;
        String[] w = t.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}«»“”…—–]+", " ").trim().split("\\s+");
        if (w.length < 2) return false;      // a one-word command ("пауза") is complete
        return TAIL.contains(w[w.length - 1]);
    }
}
