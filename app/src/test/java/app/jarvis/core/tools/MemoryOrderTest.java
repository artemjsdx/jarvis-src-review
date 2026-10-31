package app.jarvis.core.tools;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;
import org.junit.Test;

public class MemoryOrderTest {
    private static MemoryStore.Entry e(String key, long updated) {
        return new MemoryStore.Entry(key, "v-" + key, updated, "fact", false);
    }

    @Test
    public void newestFirstSortsByUpdatedDescending() {
        List<MemoryStore.Entry> in = Arrays.asList(e("a", 100), e("b", 300), e("c", 200));
        List<MemoryStore.Entry> out = MemoryOrder.newestFirst(in);
        assertEquals("b", out.get(0).key);
        assertEquals("c", out.get(1).key);
        assertEquals("a", out.get(2).key);
    }

    @Test
    public void equalTimestampsFallBackToKeyOrder() {
        List<MemoryStore.Entry> out = MemoryOrder.newestFirst(Arrays.asList(e("zeta", 5), e("alpha", 5)));
        assertEquals("alpha", out.get(0).key);
        assertEquals("zeta", out.get(1).key);
    }

    @Test
    public void inputListIsNotMutated() {
        List<MemoryStore.Entry> in = Arrays.asList(e("a", 1), e("b", 2));
        MemoryOrder.newestFirst(in);
        assertEquals("a", in.get(0).key);
    }

    @Test
    public void stampFormatsDayMonthTimeInGivenZone() {
        // 2026-10-29 03:12 UTC
        long ms = 1793243520000L;
        assertEquals("29.10 03:12", MemoryOrder.stamp(ms, TimeZone.getTimeZone("UTC")));
    }

    @Test
    public void zeroTimestampReadsUnknown() {
        assertEquals("дата неизвестна", MemoryOrder.stamp(0, TimeZone.getTimeZone("UTC")));
    }
}
