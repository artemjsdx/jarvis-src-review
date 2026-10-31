package app.jarvis.core.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PronunciationDictTest {

    @Test
    public void literalCaseInsensitive() {
        PronunciationDict d = new PronunciationDict().add("джарвис", "ДЖАРВИС");
        assertEquals("Привет, ДЖАРВИС! Ты тут, ДЖАРВИС?", d.apply("Привет, Джарвис! Ты тут, ДЖАРВИС?"));
    }

    @Test
    public void literalCaseSensitive() {
        PronunciationDict d = new PronunciationDict().add("Java", "Джава", false, false);
        assertEquals("Люблю Джава, но не java.", d.apply("Люблю Java, но не java."));
    }

    @Test
    public void regexRule() {
        PronunciationDict d = new PronunciationDict().add(":\\)", "улыбка", true, false);
        assertEquals("Отправляю улыбка", d.apply("Отправляю :)")); // literal replacement, no back-references
        assertEquals(1, d.size());
    }

    @Test
    public void rulesAppliedInOrder() {
        PronunciationDict d = new PronunciationDict()
                .add("дом", "квартира")
                .add("квартира", "подъезд");
        assertEquals("подъезд", d.apply("дом")); // second rule sees the output of the first
    }

    @Test
    public void jsonRoundTrip() {
        PronunciationDict d = new PronunciationDict()
                .add("джарвис", "ДЖАРВИС")
                .add("\\b\\d{4}\\b", "год", true, false)
                .add("Йо", "ё", false, true);
        PronunciationDict back = PronunciationDict.fromJson(d.toJson());
        assertEquals(3, back.size());
        assertEquals(d.apply("Джарвис, год 2024"), back.apply("Джарвис, год 2024"));
    }

    @Test
    public void brokenJsonOrRulesAreSafe() {
        assertEquals(0, PronunciationDict.fromJson("not json").size());
        assertEquals(0, PronunciationDict.fromJson(null).size());
        PronunciationDict d = PronunciationDict.fromJson("{\"rules\":[{\"pattern\":\"[\",\"replacement\":\"x\",\"regex\":true},{\"pattern\":\"ок\",\"replacement\":\"ОК\",\"regex\":false,\"ci\":true}]}");
        assertEquals(2, d.size()); // both rules are stored; the broken one is skipped at apply time
        assertEquals("ОК", d.apply("ок"));
    }

    @Test
    public void emptyPatternsIgnored() {
        assertEquals(0, new PronunciationDict().add("", "x").size());
        assertEquals(0, new PronunciationDict().add(null, "x").size());
        assertTrue(new PronunciationDict().apply("текст").equals("текст"));
    }
}
