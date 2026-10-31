package app.jarvis.core.adb;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class Spake2Test {
    @Test
    public void roundTrip_samePassword() {
        byte[] pwd = "123456".getBytes(StandardCharsets.UTF_8);
        Spake2 alice = Spake2.client(pwd);
        Spake2 bob = Spake2.server(pwd);
        byte[] ka = alice.processMessage(bob.message());
        byte[] kb = bob.processMessage(alice.message());
        assertNotNull(ka);
        assertNotNull(kb);
        assertEquals(64, ka.length);
        assertArrayEquals(ka, kb);
    }

    @Test
    public void wrongPassword_givesDifferentKeys() {
        Spake2 alice = Spake2.client("111111".getBytes(StandardCharsets.UTF_8));
        Spake2 bob = Spake2.server("222222".getBytes(StandardCharsets.UTF_8));
        byte[] ka = alice.processMessage(bob.message());
        byte[] kb = bob.processMessage(alice.message());
        assertNotNull(ka);
        assertNotNull(kb);
        // Different passwords → different keys; with adb's AES-GCM this surfaces as WRONG_CODE.
        assertFalse(java.util.Arrays.equals(ka, kb));
    }

    @Test
    public void rejectsBadPeerMessage() {
        Spake2 alice = Spake2.client("123456".getBytes(StandardCharsets.UTF_8));
        assertNull(alice.processMessage(new byte[31]));
        // y=1 with the sign bit set: x recovers to 0 with wrong sign → rejected
        byte[] bad = new byte[32];
        bad[0] = 1;
        bad[31] = (byte) 0x80;
        assertNull(alice.processMessage(bad));
        // some encodings are not on the curve at all
        boolean rejectedOnce = false;
        for (int i = 1; i < 32 && !rejectedOnce; i++) {
            byte[] t = new byte[32];
            t[i] = (byte) 0xa5;
            rejectedOnce = alice.processMessage(t) == null;
        }
        assertTrue(rejectedOnce);
    }

    @Test
    public void keysVaryByPassword() {
        byte[] pwd = "654321".getBytes(StandardCharsets.UTF_8);
        byte[] k1 = Spake2.client(pwd).processMessage(Spake2.server(pwd).message());
        byte[] k2 = Spake2.client(pwd).processMessage(Spake2.server(pwd).message());
        assertNotNull(k1);
        assertNotNull(k2);
        assertFalse(java.util.Arrays.equals(k1, k2)); // fresh randomness per session
    }
}
