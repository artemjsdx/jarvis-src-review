package app.jarvis.core.chat;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SessionTextTest {
    private static String rep(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }

    @Test public void titleIsTheFirstFortyCodePointsOnOneLine() {
        assertEquals("Расскажи мне про погоду в Минске завтра", SessionText.title("Расскажи мне про погоду в Минске завтра\nи про пробки"));
        assertEquals(rep("б", 40), SessionText.title(rep("б", 90)));
    }

    @Test public void markdownMarkersAndSpacesAreStripped() {
        assertEquals("Заметка про хлеб", SessionText.title("  ## **Заметка про хлеб**"));
        assertEquals("Новый диалог", SessionText.title("   \n  "));
        assertEquals("Новый диалог", SessionText.title("***"));
        assertEquals("Новый диалог", SessionText.title(null));
    }

    @Test public void surrogatePairsAreNotSplit() {
        String emoji = "😀";
        String title = SessionText.title(rep(emoji, 41));
        assertEquals(40, title.codePointCount(0, title.length()));
        assertEquals(rep(emoji, 40), title);
    }
}
