package app.jarvis.core.web;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class HtmlToTextTest {

    private static HtmlToText.Result md(String html) {
        return HtmlToText.convert(html, "https://example.org/docs/page.html", true);
    }

    @Test
    public void dropsScriptNavFooterHiddenAndCookieBanners() {
        String html = "<html><head><title>Заголовок &amp; тест</title><style>p{color:red}</style></head><body>"
            + "<nav><a href='/a'>меню</a></nav><div class='cookie-banner'>Мы используем cookie</div>"
            + "<div style='display: none'>скрыто</div><div hidden>тоже скрыто</div><script>var x = '<p>bad</p>';</script>"
            + "<article><h1>Большой текст</h1><p>Первый абзац.</p><p>Второй абзац.</p></article>"
            + "<footer>подвал</footer></body></html>";
        HtmlToText.Result r = md(html);
        assertEquals("Заголовок & тест", r.title);
        assertTrue(r.text, r.text.contains("# Большой текст"));
        assertTrue(r.text, r.text.contains("Первый абзац."));
        assertFalse(r.text, r.text.contains("меню"));
        assertFalse(r.text, r.text.contains("cookie"));
        assertFalse(r.text, r.text.contains("скрыто"));
        assertFalse(r.text, r.text.contains("bad"));
        assertFalse(r.text, r.text.contains("подвал"));
    }

    @Test
    public void keepsLinksAbsoluteAndSkipsAnchorsAndJavascript() {
        String html = "<body><p>See <a href='../guide/intro.html?x=1#top'>the intro</a>, <a href='#x'>anchor</a> and "
            + "<a href='javascript:void(0)'>js</a>.</p></body>";
        HtmlToText.Result r = md(html);
        assertTrue(r.text, r.text.contains("[the intro](https://example.org/guide/intro.html?x=1#top)"));
        assertTrue(r.text, r.text.contains("anchor"));
        assertFalse(r.text, r.text.contains("](#x)"));
        assertEquals(1, r.links.size());
        assertEquals("https://example.org/guide/intro.html?x=1#top", r.links.get(0)[1]);
    }

    @Test
    public void rendersCodeBlocksPreservingWhitespace() {
        String html = "<body><p>Пример:</p><pre><code>def f():\n    return 1\n</code></pre><p>Используйте <code>f()</code>.</p></body>";
        HtmlToText.Result r = md(html);
        assertTrue(r.text, r.text.contains("```\ndef f():"));
        assertTrue(r.text, r.text.contains("    return 1"));
        assertTrue(r.text, r.text.contains("`f()`"));
    }

    @Test
    public void rendersListsWithNumbersAndNesting() {
        String html = "<body><ol><li>один<li>два<ul><li>вложен</ul><li>три</ol></body>";
        HtmlToText.Result r = md(html);
        assertTrue(r.text, r.text.contains("1. один"));
        assertTrue(r.text, r.text.contains("2. два"));
        assertTrue(r.text, r.text.contains("  - вложен"));
        assertTrue(r.text, r.text.contains("3. три"));
    }

    @Test
    public void rendersDataTableAsAlignedGrid() {
        String html = "<body><table><thead><tr><th>Имя<th>Значение</thead>"
            + "<tbody><tr><td>a<td>1<tr><td>длинное имя<td>22</tbody></table></body>";
        HtmlToText.Result r = md(html);
        String[] lines = r.text.split("\n");
        int w1 = "длинное имя".length(), w2 = "Значение".length();
        assertEquals(r.text, "| " + pad("Имя", w1) + " | " + pad("Значение", w2) + " |", lines[0]);
        assertEquals(r.text, "|" + dashes(w1 + 2) + "|" + dashes(w2 + 2) + "|", lines[1]);
        assertEquals(r.text, "| " + pad("a", w1) + " | " + pad("1", w2) + " |", lines[2]);
        assertEquals(r.text, "| " + pad("длинное имя", w1) + " | " + pad("22", w2) + " |", lines[3]);
    }

    private static String pad(String s, int w) {
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < w) sb.append(' ');
        return sb.toString();
    }

    private static String dashes(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append('-');
        return sb.toString();
    }

    @Test
    public void flattensNestedTablesAndTreatsLayoutTablesAsBlocks() {
        String html = "<body><table><tr><td><table><tr><td>внутри</td><td>ячейка</td></tr></table></td>"
            + "<td>снаружи</td></tr></table>"
            + "<table><tr><td><p>Только блок</p></td></tr></table></body>";
        HtmlToText.Result r = md(html);
        assertTrue(r.text, r.text.contains("внутри"));
        assertTrue(r.text, r.text.contains("ячейка"));
        assertTrue(r.text, r.text.contains("Только блок"));
        assertFalse(r.text, r.text.contains("<"));
    }

    @Test
    public void survivesBrokenMarkupAndUnterminatedTags() {
        String html = "<div><p>first<p>second <b>bold <i>both</b> tail<div>unclosed <a href='/x'>link";
        HtmlToText.Result r = md(html);
        assertTrue(r.text, r.text.contains("first"));
        assertTrue(r.text, r.text.contains("second"));
        assertTrue(r.text, r.text.contains("unclosed"));
        assertTrue(r.text, r.text.contains("[link](https://example.org/x)"));
        HtmlToText.Result cut = md("<p>text then <a href=\"/unterminated");
        assertTrue(cut.text, cut.text.contains("text then"));
    }

    @Test
    public void decodesEntitiesAndNumericReferences() {
        assertEquals("a & b < c \" d ' e — f é €", Entities.decode("a &amp; b &lt; c &quot; d &apos; e &mdash; f &#233; &#x20AC;"));
        assertEquals("&unknown; &#xZZ;", Entities.decode("&unknown; &#xZZ;"));
        HtmlToText.Result r = md("<p>5&nbsp;&times;&nbsp;5 = 25</p>");
        assertTrue(r.text, r.text.contains("5 × 5 = 25"));
    }

    @Test
    public void parsesDeeplyNestedMarkupWithoutOverflow() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) sb.append("<div>");
        sb.append("глубоко");
        HtmlToText.Result r = md(sb.toString());
        assertTrue(r.text.contains("глубоко"));
    }

    @Test
    public void plainModeHasNoMarkup() {
        HtmlToText.Result r = HtmlToText.convert("<h2>Title</h2><p><b>bold</b> and <a href='/y'>link</a></p>", "https://e.org/", false);
        assertTrue(r.text, r.text.contains("Title"));
        assertTrue(r.text, r.text.contains("bold and link"));
        assertFalse(r.text, r.text.contains("#"));
        assertFalse(r.text, r.text.contains("**"));
        assertFalse(r.text, r.text.contains("]("));
        assertEquals(1, r.links.size());
    }

    @Test
    public void decodesWindows1251PageFromMetaCharset() throws Exception {
        String body = "<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=windows-1251\"></head>"
            + "<body><h1>Погода в Гомеле</h1><p>Солнечно, +12 °C</p></body></html>";
        byte[] bytes = body.getBytes(Charset.forName("windows-1251"));
        assertFalse(TextDecoder.isValidUtf8(bytes));
        HtmlToText.Result r = HtmlToText.convert(TextDecoder.decode(bytes, "text/html"), "", true);
        assertTrue(r.text, r.text.contains("# Погода в Гомеле"));
        assertTrue(r.text, r.text.contains("Солнечно"));
    }

    @Test
    public void charsetPrecedenceBomHeaderMetaAndFallback() throws Exception {
        byte[] utf8 = "<p>привет</p>".getBytes(StandardCharsets.UTF_8);
        assertEquals(StandardCharsets.UTF_8, TextDecoder.detect(utf8, null));
        byte[] withBom = new byte[utf8.length + 3];
        withBom[0] = (byte) 0xEF; withBom[1] = (byte) 0xBB; withBom[2] = (byte) 0xBF;
        System.arraycopy(utf8, 0, withBom, 3, utf8.length);
        assertEquals(StandardCharsets.UTF_8, TextDecoder.detect(withBom, "text/html; charset=windows-1251"));
        byte[] cp1251 = "<p>привет</p>".getBytes(Charset.forName("windows-1251"));
        assertEquals(Charset.forName("windows-1251"), TextDecoder.detect(cp1251, "text/html; charset=windows-1251"));
        assertEquals(Charset.forName("windows-1251"), TextDecoder.detect(cp1251, null));
        assertEquals("<p>привет</p>", TextDecoder.decode(cp1251, "text/html; charset=cp1251"));
        byte[] latinHeaderMetaRu = ("<meta charset=\"windows-1251\"><p>" + "привет").getBytes(Charset.forName("windows-1251"));
        assertEquals(Charset.forName("windows-1251"), TextDecoder.detect(latinHeaderMetaRu, "text/html"));
        assertEquals(Charset.forName("windows-1251"), TextDecoder.detect(latinHeaderMetaRu, "text/html; charset=ISO-8859-1"));
    }

    @Test
    public void utf8BodyCutInsideMultibyteSequenceStillDetectedAsUtf8() {
        byte[] full = "привет мир привет мир".getBytes(StandardCharsets.UTF_8);
        byte[] cut = java.util.Arrays.copyOf(full, full.length - 1);
        assertTrue(TextDecoder.isValidUtf8(cut));
        assertEquals(StandardCharsets.UTF_8, TextDecoder.detect(cut, null));
    }
}
