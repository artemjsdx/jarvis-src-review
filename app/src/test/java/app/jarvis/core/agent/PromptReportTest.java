package app.jarvis.core.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class PromptReportTest {
    @Test public void tokensUseThreeAndTwoTenthsForCyrillicAndFourForLatin() {
        assertEquals(0, PromptReport.tokens(""));
        assertEquals(10, PromptReport.tokens("abcdefghijklmnopqrstuvwxyzabcdefghijklmn"));
        assertEquals(10, PromptReport.tokens(rep("я", 32)));
        assertEquals(2, PromptReport.tokens("Привет"));
        assertEquals(1, PromptReport.tokens("ok"));
    }

    @Test public void totalsAreSumsOfRowsAndPercentOfWindow() {
        List<PromptReport.Row> rows = new ArrayList<>();
        rows.add(PromptReport.row("core", "Правила", rep("а", 1000), ""));
        rows.add(PromptReport.sized("tools", "Инструменты", 3000, 900, ""));
        PromptReport r = PromptReport.of(rows, 8000, 2, 5);
        assertEquals(4000, r.totalChars());
        assertEquals(rows.get(0).tokens + 900, r.totalTokens());
        assertEquals(50, r.percentOfWindow());
        assertEquals(0.375, r.fraction(rows.get(1)), 1e-9);
        assertEquals(2, r.historyKept);
        assertEquals(5, r.historyDropped);
    }

    @Test public void oversizedRequestReportsMoreThanOneHundredPercent() {
        PromptReport r = PromptReport.of(java.util.Collections.singletonList(PromptReport.sized("x", "x", 9000, 1, "")), 8000, 0, 0);
        assertEquals(113, r.percentOfWindow());
        assertEquals(1.0, r.fraction(r.rows.get(0)), 1e-9);
    }

    @Test public void systemRowsCoverTheWholeSystemPromptWithoutDuplicatingIt() {
        Prompts.Info i = new Prompts.Info();
        i.nowText = "пятница";
        i.memory = "пользователь предпочитает краткие ответы";
        i.memoryStyle = "меньше точек";
        i.skills = "НАВЫКИ: тест";
        i.extra = "Любимая песня пользователя: не выбрана.";
        List<PromptReport.Row> rows = PromptReport.systemRows(i);
        assertEquals(4, rows.size());
        assertEquals("core", rows.get(0).key);
        assertTrue(rows.get(0).chars > 1000);
        int sum = 0;
        for (PromptReport.Row r : rows) sum += r.chars;
        int whole = Prompts.system(i).length();
        assertTrue(sum + " vs " + whole, Math.abs(sum - whole) < 80);
        assertTrue(rows.get(1).note, rows.get(1).chars >= i.memory.length() + i.memoryStyle.length());
    }

    @Test public void jsonCarriesEveryRowAndTheTotals() {
        List<PromptReport.Row> rows = new ArrayList<>();
        rows.add(PromptReport.row("core", "Правила", "abcd", "n"));
        org.json.JSONObject o = PromptReport.of(rows, 100, 1, 0).toJson();
        assertEquals(4, o.getInt("totalChars"));
        assertEquals(1, o.getJSONArray("rows").length());
        assertEquals("core", o.getJSONArray("rows").getJSONObject(0).getString("key"));
        assertEquals(4, o.getInt("percentOfWindow"));
    }

    private static String rep(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }
}
