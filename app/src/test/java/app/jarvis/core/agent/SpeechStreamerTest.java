package app.jarvis.core.agent;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class SpeechStreamerTest {
    private static List<String> run(String... deltas) {
        final List<String> out = new ArrayList<>();
        SpeechStreamer s = new SpeechStreamer(new SpeechStreamer.Out() { @Override public void piece(String t) { out.add(t); } });
        for (String d : deltas) s.feed(d);
        s.flush();
        return out;
    }

    @Test
    public void cutsAtSentenceEnds() {
        assertEquals(Arrays.asList("Привет, как дела?", "Всё хорошо, спасибо что спросил."),
            run("Привет, как ", "дела? Всё хорошо, спасибо ", "что спросил."));
    }

    @Test
    public void punctuationOnlyPiecesAreDropped() {
        assertEquals(Arrays.asList(), run("...", "!!!", " — "));
        for (String p : run("Хорошо, сейчас сделаю. ", "...", " ", "…\n", "Готово, всё сделано!")) {
            boolean letter = false;
            for (int i = 0; i < p.length(); i++) letter |= Character.isLetterOrDigit(p.charAt(i));
            if (!letter) throw new AssertionError("piece without letters: [" + p + "]");
        }
    }

    @Test
    public void codeBlockIsHeldBackAndDropped() {
        assertEquals(Arrays.asList("Вот пример кода.", "Теперь запусти его командой."),
            run("Вот пример кода.\n```py", "thon\nprint(1)\n", "x = 2\n```\nТеперь запусти ", "его командой."));
    }

    @Test
    public void unclosedCodeBlockIsDroppedAtFlush() {
        assertEquals(Arrays.asList("Вот код для тебя."), run("Вот код для тебя.\n```sh\nrm -rf /\n"));
    }

    @Test
    public void sentenceBeforeFenceIsSpokenImmediately() {
        final List<String> out = new ArrayList<>();
        SpeechStreamer s = new SpeechStreamer(new SpeechStreamer.Out() { @Override public void piece(String t) { out.add(t); } });
        s.feed("Смотри пример кода.\n```kotlin\nfun main() {");
        assertEquals(Arrays.asList("Смотри пример кода."), out);
    }

    @Test
    public void decimalsDoNotCut() {
        assertEquals(Arrays.asList("Курс 3.5 рубля сегодня."), run("Курс 3.5 рубля сегодня."));
    }

    // ---- first piece may end at a comma or dash (time to first sound) ---------------------------------------------------

    @Test
    public void firstPieceEndsAtACommaOnceItIsLongEnough() {
        assertEquals(Arrays.asList("Меня зовут Джарвис,", "я ваш голосовой помощник."),
            run("Меня зовут Джар", "вис, я ваш голосовой ", "помощник."));
    }

    @Test
    public void commaBeforeTheMinimumLengthDoesNotCut() {
        assertEquals(Arrays.asList("Привет, как дела у тебя сегодня?"), run("Привет, как дела у тебя сегодня?"));
    }

    @Test
    public void onlyTheFirstPieceIsCutAtCommas() {
        assertEquals(Arrays.asList("Сегодня в Минске примерно плюс двенадцать,", "облачно, возможен небольшой дождь ближе к вечеру, а ночью похолодает."),
            run("Сегодня в Минске примерно плюс двенадцать, облачно, возможен небольшой дождь ближе к вечеру, а ночью похолодает."));
    }

    @Test
    public void aSentenceEndBeforeTheCommaStillWins() {
        assertEquals(Arrays.asList("Хорошо, понял тебя.", "Сейчас всё сделаю."), run("Хорошо, понял тебя. Сейчас всё сделаю."));
    }

    @Test
    public void commaInsideANumberIsNotAClauseEnd() {
        assertEquals(Arrays.asList("Температура сейчас примерно 1,5 градуса."), run("Температура сейчас примерно 1,5 градуса."));
        assertEquals(Arrays.asList("Температура сегодня 21,5 градуса,", "и это нормально."), run("Температура сегодня 21", ",5 градуса, и это нормально."));
    }

    @Test
    public void aTrailingCommaWaitsForTheNextCharacterOnlyWhenItFollowsADigit() {
        final List<String> out = new ArrayList<>();
        SpeechStreamer s = new SpeechStreamer(new SpeechStreamer.Out() { @Override public void piece(String t) { out.add(t); } });
        s.feed("Температура сегодня около 21,");
        assertEquals("a comma after a digit may be a decimal separator: wait", Arrays.asList(), out);
        s.feed("5 градуса и всё.");
        s.flush();
        assertEquals(Arrays.asList("Температура сегодня около 21,5 градуса и всё."), out);
    }

    @Test
    public void clauseCutCanBeSwitchedOff() {
        final List<String> out = new ArrayList<>();
        SpeechStreamer s = new SpeechStreamer(new SpeechStreamer.Out() { @Override public void piece(String t) { out.add(t); } }).firstClauseCut(false);
        s.feed("Меня зовут Джарвис, я ваш голосовой помощник.");
        s.flush();
        assertEquals(Arrays.asList("Меня зовут Джарвис, я ваш голосовой помощник."), out);
    }

    @Test
    public void dashEndsTheFirstClause() {
        assertEquals(Arrays.asList("Это займёт немного времени —", "минуту или две."), run("Это займёт немного времени — минуту или две."));
    }
}
