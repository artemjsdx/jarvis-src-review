package app.jarvis.core.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.llm.Cap;
import app.jarvis.core.llm.ChatMessage;
import app.jarvis.core.llm.ModelCaps;
import app.jarvis.core.llm.ToolCall;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class ContextFitTest {
    private static ModelCaps caps(String id, int window, Cap tools, Cap vision, Cap reasoning) {
        ModelCaps c = new ModelCaps(id);
        c.contextTokens = window;
        c.tools = tools;
        c.vision = vision;
        c.reasoning = reasoning;
        return c;
    }

    private static List<ChatMessage> chars(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append('а');
        return Collections.singletonList(ChatMessage.user(sb.toString()));
    }

    @Test public void smallHistoryFitsAndSwitchIsSilent() {
        ContextFit.Result r = ContextFit.check(chars(300), false, caps("big", 128_000, Cap.YES, Cap.NO, Cap.NO), null);
        assertTrue(r.fits);
        assertFalse(r.needsDialog());
        assertFalse(r.refused());
        assertEquals(94, r.estimatedTokens);
    }

    @Test public void historyBiggerThanWindowNeedsDialog() {
        ContextFit.Result r = ContextFit.check(chars(500_000), false, caps("small", 32_000, Cap.YES, Cap.NO, Cap.NO), null);
        assertFalse(r.fits);
        assertTrue(r.needsDialog());
        assertEquals(156_250, r.estimatedTokens);
    }

    @Test public void windowExactlyAtBudgetStillFitsWithReserve() {
        int window = 10_000;
        int chars = (int) ((window - ContextFit.RESERVE_TOKENS) * ContextFit.CHARS_PER_TOKEN);
        assertTrue(ContextFit.check(chars(chars), false, caps("m", window, Cap.YES, Cap.NO, Cap.NO), null).fits);
        assertFalse(ContextFit.check(chars(chars + 200), false, caps("m", window, Cap.YES, Cap.NO, Cap.NO), null).fits);
    }

    @Test public void windowBeyondTwoHundredThousandIsCappedAtBudget() {
        int chars = (int) ((200_000 - ContextFit.RESERVE_TOKENS + 10) * ContextFit.CHARS_PER_TOKEN);
        assertFalse(ContextFit.check(chars(chars), false, caps("huge", 1_048_576, Cap.YES, Cap.NO, Cap.NO), null).fits);
    }

    @Test public void unknownWindowIsNotCheckedAndNotAskedAbout() {
        ContextFit.Result r = ContextFit.check(chars(500_000), false, caps("unknown", 0, Cap.UNKNOWN, Cap.UNKNOWN, Cap.UNKNOWN), null);
        assertTrue(r.fits);
        assertFalse(r.needsDialog());
    }

    @Test public void toolHistoryToModelWithoutToolsLosesTools() {
        List<ChatMessage> h = new ArrayList<>();
        h.add(ChatMessage.user("открой файл"));
        h.add(ChatMessage.assistant("", Collections.singletonList(new ToolCall("c1", "fs_read", "{}"))));
        h.add(ChatMessage.tool("c1", "fs_read", "{\"ok\":true}"));
        ContextFit.Result no = ContextFit.check(h, false, caps("plain", 128_000, Cap.NO, Cap.NO, Cap.NO), null);
        assertTrue(no.toolsLost);
        assertTrue(no.needsDialog());
        ContextFit.Result unknown = ContextFit.check(h, false, caps("maybe", 128_000, Cap.UNKNOWN, Cap.NO, Cap.NO), null);
        assertFalse(unknown.toolsLost);
        assertTrue(unknown.toolsUnknown);
        assertFalse(unknown.needsDialog());
    }

    @Test public void chatWithoutToolsDoesNotLoseToolsOnAnyModel() {
        ContextFit.Result r = ContextFit.check(chars(100), false, caps("plain", 128_000, Cap.NO, Cap.NO, Cap.NO), null);
        assertFalse(r.toolsLost);
        assertFalse(r.needsDialog());
    }

    @Test public void imagesInHistoryWithoutVisionNeedDialog() {
        ContextFit.Result r = ContextFit.check(chars(10), true, caps("text", 128_000, Cap.YES, Cap.NO, Cap.NO), null);
        assertTrue(r.imagesLost);
        assertTrue(r.needsDialog());
        assertFalse(ContextFit.check(chars(10), true, caps("vision", 128_000, Cap.YES, Cap.YES, Cap.NO), null).imagesLost);
    }

    @Test public void reasoningModeLostWhenTargetCannotReason() {
        ModelCaps from = caps("reasoner", 128_000, Cap.YES, Cap.NO, Cap.YES);
        ContextFit.Result r = ContextFit.check(chars(10), false, caps("plain", 128_000, Cap.YES, Cap.NO, Cap.NO), from);
        assertTrue(r.reasoningLost);
        assertTrue(r.needsDialog());
        assertFalse(ContextFit.check(chars(10), false, caps("plain", 128_000, Cap.YES, Cap.NO, Cap.NO), caps("x", 1, Cap.YES, Cap.NO, Cap.NO)).reasoningLost);
    }

    @Test public void realtimeAndSpeechModelsAreRefused() {
        for (String id : new String[]{"gpt-4o-realtime-preview", "gpt-4o-audio-preview", "voice-model", "tts-1", "whisper-stt"}) {
            ContextFit.Result r = ContextFit.check(chars(10), false, caps(id, 128_000, Cap.YES, Cap.NO, Cap.NO), null);
            assertTrue(id, r.refused());
            assertFalse(id, r.needsDialog());
        }
        assertFalse(ContextFit.check(chars(10), false, caps("mercury-3", 128_000, Cap.YES, Cap.NO, Cap.NO), null).refused());
    }

    @Test public void autoContextUsesWindowCappedAtTwoHundredThousandMinusAnswer() {
        assertEquals((int) ((200_000 - 4_000) * 3.2), ContextFit.autoContextChars(1_048_576, 4_000));
        assertEquals((int) ((128_000 - 4_000) * 3.2), ContextFit.autoContextChars(128_000, 4_000));
        assertEquals(ContextFit.DEFAULT_CHARS, ContextFit.autoContextChars(0, 4_000));
        assertEquals(8_000, ContextFit.autoContextChars(1_000, 4_000));
    }
}
