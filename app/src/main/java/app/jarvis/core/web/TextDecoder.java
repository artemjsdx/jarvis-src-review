package app.jarvis.core.web;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Decides the charset of a fetched body: BOM, then the HTTP header, then a meta tag, then UTF-8 if valid, else windows-1251. */
public final class TextDecoder {
    private TextDecoder() { }

    private static final Pattern HEADER_CHARSET = Pattern.compile("charset\\s*=\\s*\"?([A-Za-z0-9_\\-:.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_CHARSET = Pattern.compile("<meta[^>]+charset\\s*=\\s*[\"']?\\s*([A-Za-z0-9_\\-:.]+)", Pattern.CASE_INSENSITIVE);
    private static final int META_SCAN = 4096;

    public static Charset detect(byte[] b, String contentType) {
        if (b.length >= 3 && (b[0] & 0xff) == 0xEF && (b[1] & 0xff) == 0xBB && (b[2] & 0xff) == 0xBF) return StandardCharsets.UTF_8;
        if (b.length >= 2 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xFE) return StandardCharsets.UTF_16LE;
        if (b.length >= 2 && (b[0] & 0xff) == 0xFE && (b[1] & 0xff) == 0xFF) return StandardCharsets.UTF_16BE;
        Charset fromHeader = contentType == null ? null : charsetNamed(HEADER_CHARSET, contentType);
        Charset fromMeta = charsetNamed(META_CHARSET, new String(b, 0, Math.min(b.length, META_SCAN), StandardCharsets.ISO_8859_1));
        // servers default to ISO-8859-1 when they say nothing; a page meta tag is the better signal then
        if (fromHeader != null && !(fromHeader.name().equals("ISO-8859-1") && fromMeta != null)) return fromHeader;
        if (fromMeta != null) return fromMeta;
        if (isValidUtf8(b)) return StandardCharsets.UTF_8;
        return Charset.forName("windows-1251");
    }

    public static String decode(byte[] b, String contentType) {
        return new String(b, detect(b, contentType));
    }

    private static Charset charsetNamed(Pattern p, String s) {
        Matcher m = p.matcher(s);
        if (!m.find()) return null;
        String name = m.group(1).toLowerCase(Locale.ROOT);
        if (name.equals("utf8")) name = "utf-8";
        if (name.equals("cp1251") || name.equals("win-1251")) name = "windows-1251";
        try {
            return Charset.isSupported(name) ? Charset.forName(name) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static boolean isValidUtf8(byte[] b) {
        int limit = b.length;
        int p = b.length - 1, k = 0;
        while (p >= 0 && k < 3 && (b[p] & 0xC0) == 0x80) { p--; k++; }
        if (p >= 0) {
            int lead = b[p] & 0xff;
            int need = lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1;
            if (need > k + 1) limit = p;   // the body was cut inside a multi-byte sequence
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b, 0, limit));
            return true;
        } catch (CharacterCodingException e) {
            return false;
        }
    }
}
