package app.jarvis.core.tools;

import app.jarvis.core.agent.ToolRegistry;
import app.jarvis.core.agent.ToolResult;
import app.jarvis.core.llm.ToolSpec;
import app.jarvis.core.llm.ToolSpec.Param;
import app.jarvis.core.util.J;
import app.jarvis.core.util.Mime;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

public final class FsTools {
    private FsTools() { }

    public static final int MAX_READ = 64 * 1024;
    public static final int MAX_WRITE = 4 * 1024 * 1024;
    public static final int MAX_LIST = 200;

    public static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }

    public static void register(ToolRegistry r, final Sandbox sb) {
        r.add(ToolSpec.of("fs_list", "Содержимое каталога песочницы: имя, тип, размер.",
                Param.str("path", "Каталог, например music или work. Пусто = корень песочницы.", false)),
            "files", 15_000, false, (a, ctx) -> {
                File d = sb.resolve(J.str(a, "path"));
                if (!d.exists()) return ToolResult.fail("Каталог не найден: " + sb.rel(d));
                if (!d.isDirectory()) return ToolResult.fail("Это файл, а не каталог: " + sb.rel(d));
                File[] list = d.listFiles();
                if (list == null) return ToolResult.fail("Нет доступа к каталогу: " + sb.rel(d));
                Arrays.sort(list, new Comparator<File>() {
                    @Override public int compare(File x, File y) {
                        if (x.isDirectory() != y.isDirectory()) return x.isDirectory() ? -1 : 1;
                        return x.getName().compareToIgnoreCase(y.getName());
                    }
                });
                JSONArray arr = new JSONArray();
                for (int i = 0; i < list.length && i < MAX_LIST; i++) {
                    File f = list[i];
                    JSONObject e = new JSONObject();
                    J.put(e, "name", f.getName());
                    J.put(e, "type", f.isDirectory() ? "dir" : "file");
                    if (f.isFile()) { J.put(e, "size", f.length()); J.put(e, "mime", Mime.guess(f.getName())); }
                    J.put(e, "modified", iso(f.lastModified()));
                    arr.put(e);
                }
                JSONObject o = new JSONObject();
                J.put(o, "path", sb.rel(d));
                J.put(o, "entries", arr);
                J.put(o, "total", list.length);
                J.put(o, "truncated", list.length > MAX_LIST);
                return ToolResult.ok(o, sb.rel(d) + ": " + list.length + " элем.");
            });

        r.add(ToolSpec.of("fs_stat", "Метаданные файла или каталога.",
                Param.str("path", "Путь от корня песочницы.", true)),
            "files", 10_000, false, (a, ctx) -> {
                File f = sb.resolve(J.str(a, "path"));
                if (!f.exists()) return ToolResult.fail("Не найдено: " + sb.rel(f));
                JSONObject o = new JSONObject();
                J.put(o, "path", sb.rel(f));
                J.put(o, "type", f.isDirectory() ? "dir" : "file");
                J.put(o, "size", f.isDirectory() ? -1 : f.length());
                J.put(o, "modified", iso(f.lastModified()));
                J.put(o, "mime", f.isDirectory() ? "inode/directory" : Mime.guess(f.getName()));
                return ToolResult.ok(o, sb.rel(f));
            });

        r.add(ToolSpec.of("fs_read", "Прочитать текстовый файл частями (до 64 КБ; длинные — через offset).",
                Param.str("path", "Путь от корня песочницы.", true),
                Param.integer("offset", "Смещение в байтах, по умолчанию 0.", false),
                Param.integer("max_bytes", "Сколько байт прочитать (до 65536).", false)),
            "files", 20_000, false, (a, ctx) -> {
                File f = sb.resolve(J.str(a, "path"));
                if (!f.isFile()) return ToolResult.fail(f.exists() ? "Это каталог: " + sb.rel(f) : "Файл не найден: " + sb.rel(f));
                long size = f.length();
                long off = Math.max(0, J.lng(a, "offset", 0));
                int max = Math.max(1, Math.min(MAX_READ, J.num(a, "max_bytes", MAX_READ)));
                JSONObject o = new JSONObject();
                J.put(o, "path", sb.rel(f));
                J.put(o, "size", size);
                try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                    raf.seek(Math.min(off, size));
                    byte[] buf = new byte[(int) Math.min(max, Math.max(0, size - off))];
                    raf.readFully(buf);
                    if (looksBinary(buf)) {
                        J.put(o, "binary", true);
                        J.put(o, "mime", Mime.guess(f.getName()));
                        return ToolResult.ok(o, "бинарный файл " + sb.rel(f) + " (" + size + " Б)");
                    }
                    int len = buf.length;
                    if (off + len < size) len = utf8SafeLength(buf, len); // do not cut a multi-byte character
                    J.put(o, "content", new String(buf, 0, len, StandardCharsets.UTF_8));
                    J.put(o, "offset", off);
                    J.put(o, "next_offset", off + len);
                    J.put(o, "eof", off + len >= size);
                    return ToolResult.ok(o, "прочитано " + len + " Б из " + sb.rel(f));
                }
            });

        r.add(ToolSpec.of("fs_write", "Записать текстовый файл (перезапись или append). Успех — по размеру на диске.",
                Param.str("path", "Путь, например docs/note.txt.", true),
                Param.str("content", "Текст (UTF-8).", true),
                Param.bool("append", "true — дописать.", false)),
            "files", 20_000, true, (a, ctx) -> {
                File f = sb.resolve(J.str(a, "path"));
                if (sb.isCore(f) || f.isDirectory()) return ToolResult.fail("Нельзя записать в каталог: " + sb.rel(f));
                byte[] bytes = J.str(a, "content").getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_WRITE) return ToolResult.fail("Слишком большой файл (" + bytes.length + " Б, максимум " + MAX_WRITE + ")");
                File parent = f.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return ToolResult.fail("Не удалось создать каталог " + sb.rel(parent));
                boolean append = J.bool(a, "append", false);
                long before = f.exists() ? f.length() : 0;
                if (append) {
                    try (FileOutputStream os = new FileOutputStream(f, true)) { os.write(bytes); os.getFD().sync(); }
                } else {
                    File t = new File(parent, "." + f.getName() + ".tmp");
                    try (FileOutputStream os = new FileOutputStream(t)) { os.write(bytes); os.getFD().sync(); }
                    Files.move(t.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                long expect = append ? before + bytes.length : bytes.length;
                if (!f.isFile() || f.length() != expect)
                    return ToolResult.fail("Запись не подтверждена: ожидалось " + expect + " Б, на диске " + (f.exists() ? f.length() : 0));
                JSONObject o = new JSONObject();
                J.put(o, "path", sb.rel(f));
                J.put(o, "bytes_written", bytes.length);
                J.put(o, "size_on_disk", f.length());
                return ToolResult.ok(o, (append ? "дописано " : "записано ") + bytes.length + " Б в " + sb.rel(f));
            });

        r.add(ToolSpec.of("fs_mkdir", "Создать каталог (с родителями).", Param.str("path", "Путь каталога.", true)),
            "files", 10_000, true, (a, ctx) -> {
                File d = sb.resolve(J.str(a, "path"));
                if (d.isFile()) return ToolResult.fail("Уже существует файл: " + sb.rel(d));
                if (!d.isDirectory() && !d.mkdirs()) return ToolResult.fail("Не удалось создать " + sb.rel(d));
                return ToolResult.ok(J.put(new JSONObject(), "path", sb.rel(d)), "каталог " + sb.rel(d));
            });

        r.add(ToolSpec.of("fs_delete", "Удалить файл или каталог (базовые каталоги песочницы нельзя).",
                Param.str("path", "Путь.", true), Param.bool("recursive", "true — с содержимым.", false)),
            "files", 30_000, true, (a, ctx) -> {
                File f = sb.resolve(J.str(a, "path"));
                if (sb.isCore(f)) return ToolResult.fail("Основной каталог песочницы удалять нельзя: " + sb.rel(f));
                if (!f.exists()) return ToolResult.fail("Не найдено: " + sb.rel(f));
                if (f.isDirectory() && !J.bool(a, "recursive", false)) {
                    String[] ch = f.list();
                    if (ch != null && ch.length > 0) return ToolResult.fail("Каталог не пуст: укажите recursive=true");
                }
                int n = deleteTree(f);
                if (f.exists()) return ToolResult.fail("Не удалось удалить " + sb.rel(f));
                return ToolResult.ok(J.put(new JSONObject(), "deleted_entries", n), "удалено " + n + " элем.");
            });

        r.add(ToolSpec.of("fs_move", "Переместить или переименовать в песочнице.",
                Param.str("from", "Откуда.", true), Param.str("to", "Куда (новый путь).", true)),
            "files", 30_000, true, (a, ctx) -> {
                File from = sb.resolve(J.str(a, "from")), to = sb.resolve(J.str(a, "to"));
                if (sb.isCore(from)) return ToolResult.fail("Основной каталог песочницы перемещать нельзя");
                if (!from.exists()) return ToolResult.fail("Не найдено: " + sb.rel(from));
                if (to.isDirectory()) to = new File(to, from.getName());
                if (to.exists()) return ToolResult.fail("Назначение уже существует: " + sb.rel(to));
                File pp = to.getParentFile();
                if (pp != null && !pp.isDirectory()) pp.mkdirs();
                Files.move(from.toPath(), to.toPath());
                if (!to.exists() || from.exists()) return ToolResult.fail("Перемещение не подтверждено");
                return ToolResult.ok(J.put(new JSONObject(), "path", sb.rel(to)), sb.rel(from) + " → " + sb.rel(to));
            });
    }

    static int deleteTree(File f) {
        int n = 0;
        if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) {
            File[] c = f.listFiles();
            if (c != null) for (File x : c) n += deleteTree(x);
        }
        if (f.delete()) n++;
        return n;
    }

    public static boolean looksBinary(byte[] b) {
        int n = Math.min(b.length, 4096), ctl = 0;
        for (int i = 0; i < n; i++) {
            int c = b[i] & 0xff;
            if (c == 0) return true;
            if (c < 9 || (c > 13 && c < 32)) ctl++;
        }
        return n > 0 && ctl * 100 / n > 12;
    }

    /** Longest prefix length that does not end inside a UTF-8 multi-byte sequence. */
    public static int utf8SafeLength(byte[] b, int len) {
        int i = len - 1, back = 0;
        while (i >= 0 && back < 4 && (b[i] & 0xC0) == 0x80) { i--; back++; }
        if (i < 0) return len;
        int lead = b[i] & 0xff, need = lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1;
        return (need > 1 && back + 1 < need) ? i : len;
    }
}
