package app.jarvis.core.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class DirectCommandsTest {
    static final class FakeEnv implements DirectCommands.Env {
        final List<String> calls = new ArrayList<>();
        final List<String> nav = new ArrayList<>();
        String fav = "music/morning.mp3";
        boolean toolOk = true, paused, active;
        Triggers triggers = new Triggers(Collections.singletonList(Triggers.morningPreset()));
        @Override public Triggers.Trigger trigger(String raw) { return triggers.match(raw); }
        public ToolResult tool(String name, JSONObject args) {
            calls.add(name + args);
            return toolOk ? ToolResult.ok(new JSONObject().put("title", "Утренняя").put("path", args.optString("path", "music/x.mp3")), "ok") : ToolResult.fail("Файл не найден: music/morning.mp3");
        }
        public String userCall() { return "папочка"; }
        public String favoritePath() { return fav; }
        public void navigate(String t) { nav.add(t); }
        public String statusText() { return "Сейчас жду команду."; }
        public boolean musicPaused() { return paused; }
        public boolean musicActive() { return active; }
        final List<DirectCommands.SessionCommand> sessions = new ArrayList<>();
        public String session(DirectCommands.SessionCommand c) {
            sessions.add(c);
            return c == DirectCommands.SessionCommand.NEW_CHAT ? "Открыл новый чат." : "Контекст очищен.";
        }
    }

    private final DirectCommands dc = new DirectCommands();

    @Test public void morningScenarioPlaysFavoriteAndReportsRealResult() {
        FakeEnv e = new FakeEnv();
        DirectCommands.Outcome o = dc.handle("папочка проснулся", e);
        assertTrue(o.handled);
        assertTrue(o.ok);
        assertEquals(1, e.calls.size());
        assertTrue(e.calls.get(0), e.calls.get(0).startsWith("music_play") && e.calls.get(0).contains("music/morning.mp3"));
        assertTrue(o.reply, o.reply.contains("папочка") && o.reply.contains("Утренняя"));
    }

    @Test public void morningScenarioNeverClaimsSuccessWhenToolFails() {
        FakeEnv e = new FakeEnv();
        e.toolOk = false;
        DirectCommands.Outcome o = dc.handle("Папочка проснулся!", e);
        assertTrue(o.handled);
        assertFalse(o.ok);
        assertFalse(o.reply, o.reply.contains("Включаю"));
        assertTrue(o.reply, o.reply.contains("не удалось") && o.reply.contains("Файл не найден"));
    }

    @Test public void noFavoriteGivesAGuidedScenarioNotACrash() {
        FakeEnv e = new FakeEnv();
        e.fav = "";
        DirectCommands.Outcome o = dc.handle("доброе утро", e);
        assertTrue(o.handled);
        assertFalse(o.ok);
        assertTrue(e.calls.isEmpty());
        assertEquals("settings/music", e.nav.get(0));
        assertTrue(o.reply.contains("не выбрана"));
    }

    @Test public void directTriggerRunsItsToolAndReportsItsResult() {
        FakeEnv e = new FakeEnv();
        e.triggers = new Triggers(Collections.singletonList(new Triggers.Trigger("r", Collections.singletonList("включи радио"),
            Triggers.Mode.DIRECT, "tools.call:music_play:{\"path\":\"music/radio.mp3\"}", "", true)));
        DirectCommands.Outcome o = dc.handle("Джарвис, включи радио", e);
        assertTrue(o.handled);
        assertTrue(o.ok);
        assertEquals(1, e.calls.size());
        assertTrue(e.calls.get(0), e.calls.get(0).startsWith("music_play") && e.calls.get(0).contains("music/radio.mp3"));
    }

    @Test public void appOpenTriggerNavigatesToTheSection() {
        FakeEnv e = new FakeEnv();
        e.triggers = new Triggers(Collections.singletonList(new Triggers.Trigger("o", Collections.singletonList("журнал"),
            Triggers.Mode.DIRECT, "app.open:log", "", true)));
        DirectCommands.Outcome o = dc.handle("журнал", e);
        assertTrue(o.ok);
        assertEquals(Arrays.asList("log"), e.nav);
        assertTrue(e.calls.isEmpty());
    }

    @Test public void llmTriggerIsLeftToTheModel() {
        FakeEnv e = new FakeEnv();
        e.triggers = new Triggers(Collections.singletonList(new Triggers.Trigger("l", Collections.singletonList("устал"),
            Triggers.Mode.LLM, "", "Спроси, нужен ли перерыв", true)));
        DirectCommands.Outcome o = dc.handle("устал", e);
        assertFalse(o.handled);
        assertTrue(e.calls.isEmpty());
        assertTrue(e.nav.isEmpty());
    }

    @Test public void failingTriggerToolNeverClaimsSuccess() {
        FakeEnv e = new FakeEnv();
        e.toolOk = false;
        e.triggers = new Triggers(Collections.singletonList(new Triggers.Trigger("r", Collections.singletonList("включи радио"),
            Triggers.Mode.DIRECT, "tools.call:music_play:{}", "", true)));
        DirectCommands.Outcome o = dc.handle("включи радио", e);
        assertTrue(o.handled);
        assertFalse(o.ok);
        assertTrue(o.reply, o.reply.contains("Не получилось") && o.reply.contains("Файл не найден"));
    }

    @Test public void stopMusicVariants() {
        for (String s : new String[]{"останови музыку", "Выключи музыку.", "стоп музыка", "хватит", "заткни музыку".replace("заткни", "выруби")}) {
            FakeEnv e = new FakeEnv();
            e.active = true;
            DirectCommands.Outcome o = dc.handle(s, e);
            assertTrue(s, o.handled && o.ok);
            assertEquals(s, "music_stop{}", e.calls.get(0));
        }
    }

    @Test public void bareStopIsLeftAloneWhenNothingPlays() {
        FakeEnv e = new FakeEnv();
        assertFalse(dc.handle("стоп", e).handled);
        assertTrue(e.calls.isEmpty());
    }

    @Test public void resumeOnlyWhenPaused() {
        FakeEnv e = new FakeEnv();
        assertFalse(dc.handle("продолжи", e).handled);
        e.paused = true;
        assertTrue(dc.handle("продолжи", e).handled);
        assertEquals("music_resume{}", e.calls.get(0));
    }

    @Test public void playSentFileAndPlayAnything() {
        FakeEnv e = new FakeEnv();
        assertTrue(dc.handle("проиграй файл, который я прислал", e).handled);
        assertEquals("music_play_latest_received{}", e.calls.get(0));
        assertTrue(dc.handle("включи музыку из песочницы", e).handled);
        assertEquals("music_play{}", e.calls.get(1));
    }

    @Test public void navigationPhrases() {
        FakeEnv e = new FakeEnv();
        assertTrue(dc.handle("открой настройки голоса", e).handled);
        assertTrue(dc.handle("покажи загруженные файлы", e).handled);
        assertTrue(dc.handle("открой журнал", e).handled);
        assertTrue(dc.handle("открой терминал", e).handled);
        assertEquals("[settings/voice, files, log, terminal]", e.nav.toString());
        assertFalse("unknown screen goes to the LLM", dc.handle("открой холодильник", e).handled);
    }

    @Test public void everythingElseGoesToTheModel() {
        FakeEnv e = new FakeEnv();
        for (String s : new String[]{"запомни что утром я предпочитаю спокойную музыку", "скачай файл по этой ссылке", "создай заметку", "выполни команду ls",
                "подключись по adb вот адрес и код", "расскажи анекдот про музыку и стоп", "один два три четыре пять шесть семь восемь девять десять"}) {
            assertFalse(s, dc.handle(s, e).handled);
        }
        assertTrue(e.calls.isEmpty());
    }

    @Test public void newChatPhrasesStartASessionAndNavigateNowhere() {
        for (String s : new String[]{"новый чат", "Новый голосовой чат", "начни новый диалог без контекста", "создай новый чат", "новую сессию"}) {
            FakeEnv e = new FakeEnv();
            DirectCommands.Outcome o = dc.handle(s, e);
            assertTrue(s, o.handled && o.ok);
            assertEquals(s, "Открыл новый чат.", o.reply);
            assertEquals(s, DirectCommands.SessionCommand.NEW_CHAT, e.sessions.get(0));
            assertTrue(s, e.nav.isEmpty() && e.calls.isEmpty());
        }
    }

    @Test public void clearContextPhrases() {
        for (String s : new String[]{"очисти контекст", "Сбрось контекст.", "очистить контекст чата", "очисти весь контекст"}) {
            FakeEnv e = new FakeEnv();
            DirectCommands.Outcome o = dc.handle(s, e);
            assertTrue(s, o.handled && o.ok);
            assertEquals(s, DirectCommands.SessionCommand.CLEAR_CONTEXT, e.sessions.get(0));
        }
    }

    @Test public void chatWordsThatAreNotSessionCommandsStayAsTheyWere() {
        FakeEnv e = new FakeEnv();
        assertTrue(dc.handle("открой чат", e).handled);
        assertEquals("agent", e.nav.get(0));
        assertFalse(dc.handle("очисти чат", e).handled);
        assertFalse(dc.handle("новый файл", e).handled);
        assertTrue(e.sessions.isEmpty());
    }

    @Test public void whatAreYouDoingReadsRealState() {
        FakeEnv e = new FakeEnv();
        DirectCommands.Outcome o = dc.handle("Что ты сейчас делаешь?", e);
        assertTrue(o.handled);
        assertEquals("Сейчас жду команду.", o.reply);
    }

    @Test public void successCarriesACacheableFastPhraseFailureDoesNot() {
        FakeEnv e = new FakeEnv();
        e.active = true;
        for (String cmd : new String[]{"пауза", "следующий трек", "предыдущий трек", "останови музыку", "включи музыку", "покажи файлы", "открой журнал", "папочка проснулся"}) {
            DirectCommands.Outcome o = dc.handle(cmd, e);
            assertTrue(cmd, o.handled && o.ok);
            assertTrue(cmd + " -> " + o.fast, FastPhrases.contains(o.fast));
        }
        e.toolOk = false;
        DirectCommands.Outcome bad = dc.handle("включи музыку", e);
        assertTrue(bad.handled);
        assertEquals("", bad.fast);
        assertEquals("", dc.handle("что ты делаешь", e).fast);          // information the user must hear stays the full reply
    }
}
