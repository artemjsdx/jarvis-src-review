package app.jarvis.core.telegram;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class TelegramCoreTest {
    @Test public void okReplyCarriesLabelAndState() {
        TgReply r = TgReply.parse("{\"ok\":true,\"state\":\"code_sent\",\"label\":\"work\"}");
        assertTrue(r.ok);
        assertEquals("code_sent", r.state);
        assertEquals("work", r.label);
    }

    @Test public void floodWaitKeepsSecondsAndRussianMessage() {
        TgReply r = TgReply.parse("{\"ok\":false,\"code\":\"flood_wait\",\"seconds\":321,"
            + "\"message\":\"Telegram просит подождать 321 с. Повторите позже.\",\"technical\":\"FloodWaitError\"}");
        assertFalse(r.ok);
        assertEquals("flood_wait", r.code);
        assertEquals(321, r.seconds);
        assertTrue(r.userMessage().contains("321"));
    }

    @Test public void brokenBodyBecomesFailureNotException() {
        TgReply r = TgReply.parse("<html>502</html>");
        assertFalse(r.ok);
        assertEquals("bad_reply", r.code);
        assertFalse(TgReply.parse("").ok);
        assertEquals("helper_down", TgReply.unavailable("ConnectException").code);
    }

    @Test public void unavailableTellsTheOwnerWhatToRun() {
        assertTrue(TgReply.unavailable("x").message.contains("run.sh helper-start"));
    }

    @Test public void rightsFromServerValue() {
        assertEquals(TgRights.READ, TgRights.fromExposed("read-only"));
        assertEquals(TgRights.SEND, TgRights.fromExposed("read-only+send_message,reply_to_message"));
        assertEquals(TgRights.ALL, TgRights.fromExposed("all"));
        assertEquals(TgRights.ALL, TgRights.fromExposed(""));
        assertEquals(TgRights.ALL, TgRights.fromExposed(null));
        assertEquals("send", TgRights.SEND.apiValue);
    }

    @Test public void accountsParseWithoutSecrets() throws Exception {
        JSONObject reply = new JSONObject("{\"ok\":true,\"accounts\":["
            + "{\"label\":\"work\",\"name\":\"Иван\",\"username\":\"ivan\",\"phone_tail\":\"4567\",\"added_at\":\"2026-10-30T10:00:00Z\"},"
            + "{\"label\":\"\",\"name\":\"skipped\"},"
            + "{\"label\":\"second\"}]}");
        List<TgAccount> list = TgAccount.parseList(reply);
        assertEquals(2, list.size());
        assertEquals("Иван", list.get(0).title());
        assertEquals("work · @ivan · …4567", list.get(0).subtitle());
        assertEquals("second", list.get(1).title());
        assertEquals(0, TgAccount.parseList(new JSONObject("{}")).size());
    }

    @Test public void limiterAllowsTwentyPerMinuteThenRecovers() {
        SendLimiter limiter = SendLimiter.perMinute();
        for (int i = 0; i < 20; i++) assertTrue(limiter.tryAcquire(1_000L + i));
        assertFalse(limiter.tryAcquire(1_500L));
        assertEquals(60_000L - 500L, limiter.retryAfterMs(1_500L));
        assertTrue(limiter.tryAcquire(61_000L));
    }

    @Test public void limiterWindowIsSliding() {
        SendLimiter limiter = new SendLimiter(2, 10_000L);
        assertTrue(limiter.tryAcquire(0L));
        assertTrue(limiter.tryAcquire(5_000L));
        assertFalse(limiter.tryAcquire(9_999L));
        assertTrue(limiter.tryAcquire(10_000L));
        assertEquals(0L, new SendLimiter(1, 1_000L).retryAfterMs(0L));
    }
}
