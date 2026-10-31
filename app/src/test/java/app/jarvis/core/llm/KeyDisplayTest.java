package app.jarvis.core.llm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.TimeZone;
import org.junit.Test;

public class KeyDisplayTest {
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    @Test public void maskShowsOnlyLastFourOfLongKeys() {
        assertEquals("••••1234", KeyDisplay.mask("sk-or-v1-abcdef1234"));
        assertEquals("••••", KeyDisplay.mask("short"));
        assertEquals("не задан", KeyDisplay.mask(""));
        assertEquals("не задан", KeyDisplay.mask(null));
    }

    @Test public void statusLineHasMaskAndSaveTimeInZone() {
        String s = KeyDisplay.statusLine("sk-or-v1-abcdef1234", "1793243520000", UTC);
        assertEquals("сохранён: ••••1234 · 29.10 03:12", s);
    }

    @Test public void statusLineWithoutTimeOrKey() {
        assertEquals("сохранён: ••••1234 · время неизвестно", KeyDisplay.statusLine("sk-or-v1-abcdef1234", "", UTC));
        assertEquals("ключ не задан", KeyDisplay.statusLine("", "123", UTC));
        assertEquals("ключ не задан", KeyDisplay.statusLine(null, null, UTC));
    }

    @Test public void storageTextStatesTheRealWay() {
        assertEquals("Хранится только на этом устройстве, зашифрован ключом Android Keystore", KeyDisplay.storageText(true));
        assertTrue(KeyDisplay.storageText(false).contains("без шифрования"));
    }

    @Test public void savedAtKeyNamesOnlyTheProvider() {
        assertEquals("key.openrouter.savedAt", KeyDisplay.savedAtKey("openrouter"));
    }

    @Test public void failureKindsHaveShortRussianLabels() {
        assertEquals("неверный ключ", KeyDisplay.label(LlmException.Kind.AUTH));
        assertEquals("нет средств", KeyDisplay.label(LlmException.Kind.PAYMENT));
        assertEquals("гео-блок", KeyDisplay.label(LlmException.Kind.GEO_BLOCK));
        assertEquals("нет сети", KeyDisplay.label(LlmException.Kind.NETWORK));
        assertEquals("ошибка", KeyDisplay.label(LlmException.Kind.PROTOCOL));
    }
}
