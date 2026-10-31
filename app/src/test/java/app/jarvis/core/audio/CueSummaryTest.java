package app.jarvis.core.audio;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CueSummaryTest {
    @Test public void masterSwitchOffWins() {
        assertEquals("выключены", CueSummary.sounds(false, "Хрусталь", "Хрусталь"));
    }

    @Test public void sameSetForBothKindsIsShortForm() {
        assertEquals("Хрусталь · вкл", CueSummary.sounds(true, "Хрусталь", "Хрусталь"));
    }

    @Test public void differentSetsNameBothKinds() {
        assertEquals("начало: Капля · конец: Колокольчик", CueSummary.sounds(true, "Капля", "Колокольчик"));
    }

    @Test public void volumeAsPercent() {
        assertEquals("55 %", CueSummary.percent(0.55f));
        assertEquals("10 %", CueSummary.percent(0.1f));
        assertEquals("100 %", CueSummary.percent(1f));
    }

    @Test public void micProblemOverridesListeningState() {
        assertEquals("Микрофон занят другим приложением", CueSummary.mic(true, "Микрофон занят другим приложением"));
        assertEquals("Идёт звонок: Джарвис не слушает", CueSummary.mic(false, "Идёт звонок: Джарвис не слушает"));
    }

    @Test public void noProblemShowsListeningState() {
        assertEquals("Джарвис слушает, микрофон у него", CueSummary.mic(true, ""));
        assertEquals("Джарвис не слушает, микрофон свободен", CueSummary.mic(false, null));
    }
}
