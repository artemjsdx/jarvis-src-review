package app.jarvis.core.agent;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The closed set of short confirmations that are spoken without asking the model. Because the set is small and fixed it can be
 * synthesised once (by a free TTS provider) and replayed from the audio cache in ~100-200 ms. Every phrase a tool or a direct command
 * may say in fast mode MUST come from here, otherwise it is not in the cache and costs a full synthesis.
 */
public final class FastPhrases {
    public enum Cat { PLAY, PAUSE, RESUME, STOP, NEXT, PREV, OPEN, FILES, DONE, SAVED, FOLDER, NOTE, MORNING }

    private static final Map<Cat, String[]> POOL = new EnumMap<>(Cat.class);
    static {
        POOL.put(Cat.PLAY, new String[]{"Включаю музыку.", "Сейчас включу.", "Хорошо, включаю."});
        POOL.put(Cat.PAUSE, new String[]{"Поставил на паузу.", "Пауза."});
        POOL.put(Cat.RESUME, new String[]{"Продолжаю.", "Включаю дальше."});
        POOL.put(Cat.STOP, new String[]{"Остановил.", "Выключил."});
        POOL.put(Cat.NEXT, new String[]{"Следующий трек.", "Переключаю."});
        POOL.put(Cat.PREV, new String[]{"Предыдущий трек.", "Возвращаю."});
        POOL.put(Cat.OPEN, new String[]{"Открываю.", "Открыл."});
        POOL.put(Cat.FILES, new String[]{"Открываю файлы.", "Открыл файлы."});
        POOL.put(Cat.DONE, new String[]{"Готово.", "Сделано.", "Хорошо."});
        POOL.put(Cat.SAVED, new String[]{"Записал.", "Сохранил."});
        POOL.put(Cat.FOLDER, new String[]{"Создал папку."});
        POOL.put(Cat.NOTE, new String[]{"Заметка создана.", "Записал заметку."});
        POOL.put(Cat.MORNING, new String[]{"Доброе утро! Включаю музыку."});
    }

    /** Default quick acknowledgements for slow turns (C-10); the user can replace the list with the setting {@code ack.phrases}. */
    public static final String[] ACK_DEFAULT = {"Секунду.", "Принял, смотрю.", "Сейчас гляну.", "Минуту.", "Понял, сейчас."};

    private final Map<Cat, Integer> last = new EnumMap<>(Cat.class);
    private final Random rnd = new Random();

    private static final FastPhrases GLOBAL = new FastPhrases();
    public static FastPhrases global() { return GLOBAL; }

    /** A phrase of the category, never the same one twice in a row (when the category has more than one). */
    public synchronized String pick(Cat c) {
        String[] p = POOL.get(c);
        if (p.length == 1) return p[0];
        Integer prev = last.get(c);
        int i = rnd.nextInt(p.length);
        if (prev != null && i == prev) i = (i + 1 + rnd.nextInt(p.length - 1)) % p.length;
        last.put(c, i);
        return p[i];
    }

    public static boolean contains(String phrase) {
        for (String[] a : POOL.values()) for (String s : a) if (s.equals(phrase)) return true;
        return false;
    }

    /** Every confirmation phrase (for cache pre-synthesis). */
    public static List<String> all() {
        List<String> out = new ArrayList<>();
        for (String[] a : POOL.values()) for (String s : a) out.add(s);
        return out;
    }
}
