package app.jarvis.core.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public class RedactorTest {
    @After public void tearDown() { Redactor.clearSecrets(); }

    @Test public void masksKnownAndPatternSecrets() {
        Redactor.addSecret("supersecretvalue123");
        String r = Redactor.redact("key=supersecretvalue123 and Authorization: Bearer abcdef1234567890XYZ and sk-abcdefghijklmnop1234");
        assertFalse(r, r.contains("supersecretvalue123"));
        assertFalse(r, r.contains("abcdef1234567890XYZ"));
        assertFalse(r, r.contains("abcdefghijklmnop1234"));
    }

    @Test public void masksJsonApiKeyAndPairingCode() {
        String r = Redactor.redact("{\"api_key\":\"qwertyuiop12345\"} pairing code 482913 for 192.168.1.5");
        assertFalse(r, r.contains("qwertyuiop12345"));
        assertFalse(r, r.contains("482913"));
        assertTrue(r, r.contains("192.168.1.5"));
    }

    @Test public void keepsChecksumsAndOrdinaryText() {
        String sha = "15269ebcc3d579f39b4abf3739096a2013d737c9c4dab067b92d0e3d3d38f9cf";
        String r = Redactor.redact("sha256 " + sha + " файл song.mp3 скачан");
        assertTrue(r, r.contains(sha));
        assertTrue(r, r.contains("song.mp3"));
    }
}
