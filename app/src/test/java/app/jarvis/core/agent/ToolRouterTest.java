package app.jarvis.core.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.jarvis.core.llm.ChatMessage;
import app.jarvis.core.llm.ToolCall;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * The acceptance table for tool groups (docs/TASK_SPEED2.md 1d): realistic Russian requests, and for each one the tools the model
 * MUST be shown without having to ask for them. A row fails when the router would hide a tool the request needs.
 */
public class ToolRouterTest {
    private static final List<String[]> TABLE = new ArrayList<>();

    private static void row(String text, String... needed) {
        List<String> r = new ArrayList<>();
        r.add(text);
        r.addAll(Arrays.asList(needed));
        TABLE.add(r.toArray(new String[0]));
    }

    static {
        // player
        row("включи музыку", "music_play");
        row("Джарвис, поставь следующую песню", "music_next");
        row("что сейчас играет", "music_status");
        row("поставь на паузу", "music_pause");
        row("продолжи играть", "music_resume");
        row("включи мою любимую песню", "music_play");
        row("запомни что моя любимая песня Imagine", "memory_save", "music_set_favorite");
        row("включи Queen", "music_list", "music_play");
        row("сделай потише", "adb_shell");
        // web
        row("какая завтра погода в Гомеле", "web_search");
        row("найди в интернете рецепт борща", "web_search", "web_fetch");
        row("прочитай статью по ссылке https://example.com/a", "web_fetch");
        row("открой сайт example.com и перескажи", "web_fetch");
        row("скачай файл https://example.com/a.zip", "download");
        row("найди в документации как работает socket", "docs_search");
        row("сделай запрос к api погоды", "http_fetch");
        // files and notes
        row("создай папку проекты в work", "fs_mkdir");
        row("покажи что лежит в загрузках", "fs_list");
        row("удали файл старый.txt", "fs_delete");
        row("прочитай заметку про отпуск", "fs_read");
        row("перемести документ в архив", "fs_move");
        row("запиши в файл список покупок", "fs_write");
        row("что это? [Вложение: inbox/report.pdf — application/pdf, 1.2 МБ]", "fs_read");
        row("запиши заметку купить молоко", "note_create");
        // terminal
        row("запусти python скрипт test.py", "shell_exec", "termux_exec");
        row("выполни команду ls в терминале", "shell_exec");
        row("установи пакет через pip install requests", "termux_exec");
        row("сколько свободного места на телефоне", "shell_exec");
        // phone control (ADB is the only way to touch other apps and system settings)
        row("подключись к телефону по adb", "adb_connect");
        row("сделай скриншот", "adb_shell");
        row("открой ютуб", "adb_shell");
        row("позвони маме", "adb_shell");
        row("включи фонарик", "adb_shell");
        row("включи вайфай", "adb_shell");
        row("увеличь яркость", "adb_shell");
        row("поставь будильник на семь утра", "adb_shell");
        row("поставь таймер на пять минут", "adb_shell");
        row("включи свет", "adb_shell");
        row("запусти игру", "adb_shell");
        row("открой файл отчёт.pdf", "fs_read");
        row("сколько осталось заряда", "adb_shell");
        // memory administration (saving and reading memory is core)
        row("забудь что я люблю кофе", "memory_delete");
        row("исправь в памяти мой город на Минск", "memory_replace");
        row("закрепи в памяти что у меня аллергия", "memory_pin");
        row("запомни что я люблю чай", "memory_save");
        // skills and triggers
        row("создай навык для утренней рутины", "skill_manage");
        row("когда я скажу спокойной ночи, выключай музыку", "trigger_save", "music_stop");
        row("какие у меня есть навыки", "skills_list");
        // assistant settings
        row("выключи режим обращения", "app_wake");
        row("слушай команду", "app_listen");
        row("измени скорость речи на быструю", "app_setting");
        row("сделай голос помягче", "app_setting");
        // past chats
        row("о чём мы говорили вчера", "session_search");
        row("помнишь что я просил в прошлый раз", "session_search");
        row("найди в переписке адрес склада", "session_search");
        // core
        row("замолчи", "speech_stop");
        row("что ты сейчас делаешь", "app_state");
        row("открой настройки голоса", "app_open");
    }

    /** Plain talk: nothing but the core and the meta tool may be announced (this is where the saving is). */
    private static final String[] PLAIN = {
        "привет как дела", "расскажи анекдот", "сколько будет двадцать пять умножить на шесть", "переведи на английский доброе утро",
        "что такое фотосинтез", "спасибо", "объясни разницу между кошкой и собакой", "придумай название для кофейни",
    };

    private static Set<String> shown(String text) { return shown(text, null, null, false); }

    private static Set<String> shown(String text, List<ChatMessage> history, List<ChatMessage> prefill, boolean musicActive) {
        ToolRouter.Result r = ToolRouter.route(text, history, prefill, musicActive);
        return ToolFixtures.names(new ToolSelection(ToolFixtures.fullRegistry(), r.groups()).advertise());
    }

    private static Set<String> shownChosen(String text, int turn, int every) {
        ToolRouter.Result r = ToolRouter.choose(text, null, null, false, turn, every);
        return ToolFixtures.names(new ToolSelection(ToolFixtures.fullRegistry(), r.groups()).advertise());
    }

    private static ChatMessage calls(String name, String args) {
        return ChatMessage.assistant("", Collections.singletonList(new ToolCall("c-" + name, name, args)));
    }

    /** One finished turn of the conversation: the user's words, the tool calls that followed, the answer. */
    private static void turn(List<ChatMessage> h, String user, String tool) {
        h.add(ChatMessage.user(user));
        if (tool != null) {
            h.add(calls(tool, "{}"));
            h.add(ChatMessage.tool("c-" + tool, tool, "{\"ok\":true}"));
        }
        h.add(ChatMessage.assistant("Готово.", null));
    }

    @Test public void tableHasEnoughRealisticCommands() {
        assertTrue("the acceptance rule asks for at least 40 commands, have " + TABLE.size(), TABLE.size() >= 40);
    }

    @Test public void everyCommandSeesTheToolsItNeeds() {
        List<String> failures = new ArrayList<>();
        for (String[] r : TABLE) {
            Set<String> have = shown(r[0]);
            for (int i = 1; i < r.length; i++) if (!have.contains(r[i])) failures.add("«" + r[0] + "» не получила " + r[i]);
        }
        assertTrue("маршрутизация потеряла инструменты:\n" + String.join("\n", failures), failures.isEmpty());
    }

    @Test public void plainTalkAnnouncesOnlyTheCoreAndTheMetaTool() {
        for (String text : PLAIN) {
            Set<String> have = shown(text);
            assertEquals("«" + text + "»: " + have, ToolGroups.CORE_BUILTIN.size() + 1, have.size());
            assertTrue(have.containsAll(ToolGroups.CORE_BUILTIN));
            assertTrue(have.contains(ToolGroups.META_TOOL));
            assertTrue("«" + text + "»: " + ToolRouter.route(text, null, null, false), ToolRouter.route(text, null, null, false).isEmpty());
        }
    }

    @Test public void shortWordsMatchWholeWordsOnly() {
        assertFalse("«гитаре» is not git", shown("сыграй на гитаре").contains("shell_exec"));
        assertFalse("«пингвины» is not ping", shown("расскажи про пингвинов").contains("shell_exec"));
        assertTrue(shown("сделай git pull").contains("shell_exec"));
    }

    // ---- a generic verb is a second tier ----------------------------------------------------------------------------

    @Test public void aSpecificWordBeatsAGenericVerb() {
        assertEquals(Collections.singletonList("music"), ToolRouter.route("включи музыку", null, null, false).groups());
        assertEquals(Collections.singletonList("adb"), ToolRouter.route("включи фонарик", null, null, false).groups());
        assertEquals(Collections.singletonList("shell"), ToolRouter.route("запусти python скрипт test.py", null, null, false).groups());
        assertFalse("«включи музыку» must not drag all of ADB in", shown("включи музыку").contains("adb_shell"));
    }

    @Test public void aGenericVerbAloneKeepsBothThePlayerAndPhoneControlOpen() {
        ToolRouter.Result r = ToolRouter.route("включи Queen", null, null, false);
        assertEquals(Arrays.asList("music", "adb"), r.groups());
        assertEquals("общий глагол «включи»", r.why("music"));
    }

    @Test public void aGenericVerbStillCountsWhenOnlyTheHistoryOrThePlayerKnowSomething() {
        // an active player is no reason to forget that «запусти игру» may mean another app
        assertTrue(shown("запусти игру", null, null, true).contains("adb_shell"));
        List<ChatMessage> h = new ArrayList<>();
        turn(h, "включи музыку", "music_play");
        assertTrue(shown("запусти игру", h, null, false).contains("adb_shell"));
    }

    // ---- the periodic full list (setting tools.fullEvery) -----------------------------------------------------------

    @Test public void everyNthRequestIsADueRequest() {
        List<Integer> due = new ArrayList<>();
        for (int turn = 1; turn <= 12; turn++) if (ToolRouter.fullListDue(turn, 5)) due.add(turn);
        assertEquals("the fifth request and the tenth", Arrays.asList(5, 10), due);
    }

    @Test public void zeroIsNeverAndOneIsEveryRequest() {
        for (int turn = 1; turn <= 8; turn++) {
            assertFalse("0 = never, turn " + turn, ToolRouter.fullListDue(turn, 0));
            assertTrue("1 = always, turn " + turn, ToolRouter.fullListDue(turn, 1));
        }
        assertFalse(ToolRouter.fullListDue(0, 5));
        assertFalse(ToolRouter.fullListDue(5, -3));
    }

    @Test public void aDueRequestAnnouncesEveryToolAndNoMetaTool() {
        ToolRouter.Result r = ToolRouter.choose("привет как дела", null, null, false, 5, 5);
        assertTrue(r.isFull());
        assertEquals(ToolGroups.ids(), r.groups());
        Set<String> have = shownChosen("привет как дела", 5, 5);
        assertEquals(ToolFixtures.names(ToolFixtures.fullRegistry().specs()), have);
        assertFalse("nothing is hidden, so there is nothing to ask for", have.contains(ToolGroups.META_TOOL));
        assertTrue(r.toString(), r.toString().contains("каждый 5-й запрос, № 5"));
    }

    @Test public void theRequestsBetweenAreGroupedAsUsual() {
        for (int turn : new int[] {1, 2, 3, 4, 6, 9}) {
            ToolRouter.Result r = ToolRouter.choose("привет как дела", null, null, false, turn, 5);
            assertFalse("turn " + turn, r.isFull());
            assertTrue("turn " + turn, r.isEmpty());
        }
        assertEquals(Collections.singletonList("music"), ToolRouter.choose("включи музыку", null, null, false, 4, 5).groups());
    }

    @Test public void withTheSettingAtOneEveryRequestGetsEverythingLikeBeforeGroupsExisted() {
        Set<String> everything = ToolFixtures.names(ToolFixtures.fullRegistry().specs());
        for (int turn = 1; turn <= 6; turn++) assertEquals("turn " + turn, everything, shownChosen("привет как дела", turn, 1));
    }

    @Test public void withTheSettingAtZeroNoRequestIsFull() {
        for (int turn = 1; turn <= 30; turn++) assertFalse("turn " + turn, ToolRouter.choose("привет", null, null, false, turn, 0).isFull());
    }

    @Test public void routerSaysWhyAGroupWasChosen() {
        ToolRouter.Result r = ToolRouter.route("Что сейчас играет, пожалуйста?", null, null, false);
        assertEquals(Collections.singletonList(ToolGroups.MUSIC), r.groups());
        assertEquals("the reason names the word that matched, whole", "слово «играет»", r.why(ToolGroups.MUSIC));
        assertEquals("music (слово «играет»)", r.toString());
        assertEquals("нет", ToolRouter.route("привет", null, null, false).toString());
    }

    @Test public void linksSwitchTheWebGroupOn() {
        assertTrue(shown("глянь вот это https://example.org/x").contains("web_fetch"));
        assertTrue(shown("что на www.example.org").contains("web_fetch"));
    }

    // ---- stickiness -------------------------------------------------------------------------------------------------

    @Test public void aFollowUpWithoutKeywordsKeepsTheGroupOfThePreviousTurn() {
        List<ChatMessage> h = new ArrayList<>();
        turn(h, "включи музыку", "music_play");
        assertTrue(shown("ещё раз", h, null, false).contains("music_pause"));
        assertTrue(ToolRouter.route("ещё раз", h, null, false).why(ToolGroups.MUSIC).contains("music_play"));
    }

    @Test public void stickinessLastsThreeUserTurns() {
        List<ChatMessage> h = new ArrayList<>();
        turn(h, "включи музыку", "music_play");
        turn(h, "как дела", null);
        turn(h, "расскажи анекдот", null);
        assertTrue("two plain turns later it still counts", shown("ещё раз", h, null, false).contains("music_pause"));
        turn(h, "спасибо", null);
        assertFalse("three plain turns later it is over", shown("ещё раз", h, null, false).contains("music_pause"));
        assertEquals(ToolGroups.CORE_BUILTIN.size() + 1, shown("привет", h, null, false).size());
    }

    @Test public void aWebSearchInTheHistoryBringsTheWebGroupForReadingThePages() {
        List<ChatMessage> h = new ArrayList<>();
        turn(h, "какие новости про космос", "web_search");
        assertTrue(shown("открой первую", h, null, false).contains("web_fetch"));
    }

    @Test public void groupsTheModelAskedForStayOnForTheNextTurns() {
        List<ChatMessage> h = new ArrayList<>();
        h.add(ChatMessage.user("сделай что-нибудь с этим"));
        h.add(calls(ToolGroups.META_TOOL, "{\"groups\":[\"files\"]}"));
        h.add(ChatMessage.tool("c-tools_enable", ToolGroups.META_TOOL, "{\"ok\":true}"));
        h.add(ChatMessage.assistant("Подключил.", null));
        assertTrue(shown("продолжай", h, null, false).contains("fs_read"));
        h.set(1, calls(ToolGroups.META_TOOL, "{\"groups\":[\"all\"]}"));
        assertTrue(shown("продолжай", h, null, false).contains("adb_shell"));
    }

    @Test public void toolsAlreadyRunForThisRequestKeepTheirGroup() {
        List<ChatMessage> prefill = new ArrayList<>();
        prefill.add(calls("music_play", "{}"));
        prefill.add(ChatMessage.tool("c-music_play", "music_play", "{\"ok\":true}"));
        assertTrue(shown("Команда выполнена", null, prefill, false).contains("music_pause"));
    }

    @Test public void anActivePlayerAnnouncesTheMusicTools() {
        // no music word in the request: only the state of the player can tell that this means the music
        assertFalse(shown("ну всё надоело", null, null, false).contains("music_stop"));
        assertTrue(shown("ну всё надоело", null, null, true).contains("music_stop"));
        assertTrue(ToolRouter.route("ну всё надоело", null, null, true).why(ToolGroups.MUSIC).contains("плеер"));
    }

    @Test public void nullAndBrokenInputAreHarmless() {
        assertEquals(ToolGroups.CORE_BUILTIN.size() + 1, shown(null, null, null, false).size());
        List<ChatMessage> h = new ArrayList<>();
        h.add(ChatMessage.user("x"));
        h.add(calls(ToolGroups.META_TOOL, "это не json"));
        h.add(ChatMessage.tool("c", null, "{}"));
        assertEquals(ToolGroups.CORE_BUILTIN.size() + 1, shown("привет", h, null, false).size());
    }
}
