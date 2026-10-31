package app.jarvis.ui.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import app.jarvis.rt.AState;
import org.junit.Test;

public class HomeStatusTest {
    @Test public void everyStateHasAVisibleLabelAndTone() {
        for (AState s : AState.values()) {
            assertNotNull(s.label);
            assertFalse(s.name(), s.label.isEmpty());
            assertNotNull(HomeStatus.tone(s));
        }
    }

    @Test public void idleAndWaitingAreMutedAndErrorIsError() {
        assertEquals(HomeStatus.Tone.MUTED, HomeStatus.tone(AState.IDLE));
        assertEquals(HomeStatus.Tone.MUTED, HomeStatus.tone(AState.WAITING));
        assertEquals(HomeStatus.Tone.ERROR, HomeStatus.tone(AState.ERROR));
        assertEquals(HomeStatus.Tone.OK, HomeStatus.tone(AState.DONE));
        assertEquals(HomeStatus.Tone.ACCENT, HomeStatus.tone(AState.SPEAKING));
    }

    @Test public void staleVoiceDetailNeverShowsUnderIdleOrWaiting() {
        assertEquals("Нажмите микрофон и скажите команду", HomeStatus.subtitle(AState.IDLE, "речь"));
        assertEquals("Скажите «Джарвис», чтобы начать", HomeStatus.subtitle(AState.WAITING, "речь"));
    }

    @Test public void listeningShowsTheHintNotTheRawDetail() {
        assertEquals("Говорите, я слушаю", HomeStatus.subtitle(AState.LISTENING, "речь"));
    }

    @Test public void toolAndErrorShowTheirOwnDetail() {
        assertEquals("fs_read", HomeStatus.subtitle(AState.TOOL, "fs_read"));
        assertEquals("файл не найден", HomeStatus.subtitle(AState.ERROR, "файл не найден"));
        assertEquals("", HomeStatus.subtitle(AState.ERROR, null));
    }

    @Test public void thinkingAndSpeakingHaveNoSubtitleOfTheirOwn() {
        assertTrue(HomeStatus.subtitle(AState.THINKING, "").isEmpty());
        assertTrue(HomeStatus.subtitle(AState.SPEAKING, "речь").isEmpty());
    }
}
