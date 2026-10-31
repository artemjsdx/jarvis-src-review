package app.jarvis.core.adb;

import static org.junit.Assert.*;

import org.junit.Test;

public class AdbInputTest {
    @Test
    public void russianPaste() {
        AdbInput in = AdbInput.parse("192.168.1.5:37123 код 123456");
        assertNotNull(in);
        assertEquals("192.168.1.5", in.host);
        assertEquals(37123, in.port);
        assertEquals("123456", in.code);
    }

    @Test
    public void pairCommand() {
        AdbInput in = AdbInput.parse("pair 10.0.0.2:41234 654321");
        assertNotNull(in);
        assertEquals("10.0.0.2", in.host);
        assertEquals(41234, in.port);
        assertEquals("654321", in.code);
    }

    @Test
    public void adbPrefix() {
        AdbInput in = AdbInput.parse("adb pair 10.0.0.2:41234 654321");
        assertNotNull(in);
        assertEquals(41234, in.port);
        assertEquals("654321", in.code);
    }

    @Test
    public void connectWithoutCode() {
        AdbInput in = AdbInput.parse("10.0.0.2:39555");
        assertNotNull(in);
        assertEquals(39555, in.port);
        assertEquals("", in.code);
    }

    @Test
    public void labelVariant() {
        AdbInput in = AdbInput.parse("адрес 192.168.0.44:40123 code: 777888");
        assertNotNull(in);
        assertEquals("192.168.0.44", in.host);
        assertEquals(40123, in.port);
        assertEquals("777888", in.code);
    }

    @Test
    public void garbage() {
        assertNull(AdbInput.parse(null));
        assertNull(AdbInput.parse(""));
        assertNull(AdbInput.parse("привет как дела"));
        assertNull(AdbInput.parse("192.168.1.5:99999")); // bad port
    }
}
