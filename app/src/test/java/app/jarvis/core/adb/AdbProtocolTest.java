package app.jarvis.core.adb;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class AdbProtocolTest {

    @Test
    public void messageRoundTrip() throws Exception {
        AdbProtocol.Message m = AdbProtocol.Message.of(AdbProtocol.A_WRTE, 7, 8,
            "hello adb".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AdbProtocol.write(out, m);
        byte[] bytes = out.toByteArray();
        assertEquals(24 + 9, bytes.length);
        // magic = cmd ^ 0xffffffff at offset 20
        assertEquals((~AdbProtocol.A_WRTE) & 0xffffffffL, AdbProtocol.getU32(bytes, 20));
        // checksum = sum of payload bytes
        long sum = 0;
        for (byte b : "hello adb".getBytes(StandardCharsets.UTF_8)) sum += b & 0xff;
        assertEquals(sum, AdbProtocol.getU32(bytes, 16));
        AdbProtocol.Message back = AdbProtocol.read(new ByteArrayInputStream(bytes), 1024);
        assertEquals(AdbProtocol.A_WRTE, back.command);
        assertEquals(7, back.arg0);
        assertEquals(8, back.arg1);
        assertArrayEquals(m.payload, back.payload);
    }

    @Test
    public void zeroChecksumAcceptedSkipChecksumPeers() throws Exception {
        AdbProtocol.Message m = AdbProtocol.Message.of(AdbProtocol.A_CNXN, 0x01000001, 262144,
            "banner".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AdbProtocol.write(out, m);
        byte[] bytes = out.toByteArray();
        java.util.Arrays.fill(bytes, 16, 20, (byte) 0); // sender skipped the checksum
        AdbProtocol.Message back = AdbProtocol.read(new ByteArrayInputStream(bytes), 1024 * 1024);
        assertEquals("banner", new String(back.payload, StandardCharsets.UTF_8));
    }

    @Test
    public void badMagicRejected() {
        byte[] h = new byte[24];
        AdbProtocol.putU32(h, 0, AdbProtocol.A_CNXN);
        AdbProtocol.putU32(h, 20, 0xdeadbeefL); // wrong magic
        try {
            AdbProtocol.read(new ByteArrayInputStream(h), 1024);
            fail("expected IOException");
        } catch (java.io.IOException expected) { }
    }

    @Test
    public void bannerParsing() {
        String banner = "device::ro.product.name=fake;ro.product.model=Pixel 9;ro.product.device=fake4;"
            + "features=shell_v2,cmd,stat_v2,sendrecv_v2";
        assertTrue(AdbConnection.parseFeatures(banner).contains("shell_v2"));
        assertFalse(AdbConnection.parseFeatures(banner).contains("delay_ack"));
        assertEquals("Pixel 9", AdbConnection.parseModel(banner));
        assertEquals("", AdbConnection.parseModel("host::features=cmd"));
        assertFalse(AdbConnection.parseFeatures("device::ro.product.model=X;features=cmd,stat_v2")
            .contains("shell_v2"));
    }
}
