package app.jarvis.core.audio;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class UnfinishedSpeechTest {
    @Test public void table() {
        String[] unfinished = {"включи музыку и", "открой настройки,", "поставь будильник на", "создай заметку про", "найди погоду в", "включи а", "покажи файлы но",
            "напомни мне что", "запиши в", "включи мою", "расскажи про погоду и", "позвони маме и скажи что"};
        String[] finished = {"пауза", "включи музыку", "какая погода в Гомеле", "что ты делаешь", "останови", "открой файлы.", "сколько времени?", "создай заметку купить хлеб",
            "следующий трек", "я проснулся", "и", "выключи свет на кухне", "громче", "включи музыку!"};
        for (String s : unfinished) assertEquals(s, true, UnfinishedSpeech.isUnfinished(s));
        for (String s : finished) assertEquals(s, false, UnfinishedSpeech.isUnfinished(s));
        assertEquals(false, UnfinishedSpeech.isUnfinished(null));
        assertEquals(false, UnfinishedSpeech.isUnfinished("  "));
    }
}
