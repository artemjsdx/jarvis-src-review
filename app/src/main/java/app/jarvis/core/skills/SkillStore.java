package app.jarvis.core.skills;

import app.jarvis.core.log.Journal;
import app.jarvis.core.net.ZipSafe;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Skills in the sandbox: {@code skills/<name>/SKILL.md} plus optional references/, templates/, scripts/, assets/.
 * A batch of operations is applied to an in-memory copy of the touched skills, validated, then swapped into place
 * directory by directory; any failure restores the previous state of every touched skill.
 */
public final class SkillStore {
    public static final int MAX_SKILLS = 100, MAX_SKILL_MD = 20 * 1024, MAX_SKILL_TOTAL = 200 * 1024, MAX_FILE = 64 * 1024;
    public static final List<String> SUBDIRS = Collections.unmodifiableList(Arrays.asList("references", "templates", "scripts", "assets"));
    public static final String SKILL_MD = "SKILL.md";

    private static final String TX = ".tx", SEEDED = ".seeded";
    private static final String HEADER =
        "Перед ответом просмотри список навыков; если навык подходит хотя бы частично — загрузи его skill_view и следуй ему.\n"
        + "После сложной или итеративной задачи предложи сохранить навык.\n"
        + "Если загруженный навык оказался неполным или неверным — исправь его skill_manage(patch).\n";

    /** One element of a skill_manage batch. Only the fields of the named action are used. */
    public static final class Op {
        public String action, name, content, oldString, newString, filePath, fileContent;
        public byte[] fileBytes;
        public boolean replaceAll;
    }

    private static final class Work {
        final String name;
        boolean onDisk, existed;
        final Map<String, byte[]> files = new LinkedHashMap<>();
        Work(String name) { this.name = name; }
    }

    private static final class Moved {
        final File live, old;
        Moved(File live, File old) { this.live = live; this.old = old; }
    }

    private final File dir;
    private final Journal journal;

    public SkillStore(File dir, Journal journal) {
        this.dir = dir;
        this.journal = journal;
        recoverInterrupted();
    }

    /** Every readable skill, sorted by name. Broken skills are skipped and reported to the journal. */
    public synchronized List<SkillDoc> listForUi() {
        List<SkillDoc> out = new ArrayList<>();
        for (File d : skillDirs()) {
            File md = new File(d, SKILL_MD);
            if (!md.isFile()) continue;
            try {
                out.add(SkillFormat.parse(readText(md), d.getName()));
            } catch (IOException e) {
                journal.w(Journal.Cat.AGENT, "Навык «" + d.getName() + "» пропущен: " + e.getMessage());
            }
        }
        return out;
    }

    /** Supporting file paths of a skill (relative, sorted), without SKILL.md. */
    public synchronized List<String> supportingFiles(String name) throws IOException {
        Map<String, File> found = new TreeMap<>();
        collectFiles(requireDir(name), "", found);
        found.remove(SKILL_MD);
        return new ArrayList<>(found.keySet());
    }

    /** Text of SKILL.md or of one supporting file. */
    public synchronized String read(String name, String file) throws IOException {
        File base = requireDir(name);
        String rel = file == null || file.trim().isEmpty() ? SKILL_MD : file.trim();
        checkRel(rel);
        File f = new File(base, rel);
        if (!f.isFile()) throw new SkillException("Нет файла «" + rel + "» в навыке «" + name + "»");
        return readText(f);
    }

    /**
     * The stable block for the system prompt: instructions plus a compact "name — description" index, at most about
     * {@code maxChars} characters. Empty when there are no skills (so the prompt stays unchanged).
     */
    public synchronized String systemBlock(int maxChars) {
        List<SkillDoc> all = listForUi();
        if (all.isEmpty()) return "";
        int room = Math.max(0, maxChars - HEADER.length() - 40);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < all.size(); i++) {
            SkillDoc d = all.get(i);
            String line = "- " + d.name + " — " + d.description + "\n";
            if (sb.length() + line.length() > room) {
                sb.append("- …ещё ").append(all.size() - i).append(" (полный список: skills_list)\n");
                break;
            }
            sb.append(line);
        }
        return "НАВЫКИ:\n" + HEADER + "<available_skills>\n" + sb + "</available_skills>";
    }

    /**
     * Applies the batch atomically. Returns one Russian summary per operation. Throws {@link SkillException}
     * (with the 1-based operation number) and leaves the disk untouched when any operation or check fails.
     */
    public synchronized List<String> apply(List<Op> ops) throws IOException {
        if (ops == null || ops.isEmpty()) throw new SkillException("Пустой список операций");
        Map<String, Work> work = new LinkedHashMap<>();
        Map<String, Integer> perName = new HashMap<>();
        List<String> summaries = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            String name = op.name == null ? "" : op.name.trim();
            perName.put(name, perName.containsKey(name) ? perName.get(name) + 1 : 1);
            try {
                summaries.add(applyOne(op, name, work));
            } catch (SkillException e) {
                throw e.op != 0 ? e : new SkillException(e.getMessage(), i + 1);
            }
        }
        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            String name = op.name == null ? "" : op.name.trim();
            if ("delete".equals(op.action) && perName.get(name) > 1) {
                throw new SkillException("delete должен быть единственной операцией над навыком «" + name + "»", i + 1);
            }
        }
        int count = skillCount();
        for (Work w : work.values()) {
            if (w.onDisk && !w.existed) count--;
            if (!w.onDisk && w.existed) count++;
            if (w.existed) validate(w);
        }
        if (count > MAX_SKILLS) throw new SkillException("Навыков не больше " + MAX_SKILLS + ": удалите ненужные");
        commit(work);
        for (String s : summaries) journal.i(Journal.Cat.AGENT, "Навык: " + s);
        return summaries;
    }

    private String applyOne(Op op, String name, Map<String, Work> work) throws IOException {
        String action = op.action == null ? "" : op.action.trim();
        SkillFormat.checkName(name);
        Work w = work(name, work);
        switch (action) {
            case "create": {
                if (w.existed) throw new SkillException("Навык «" + name + "» уже существует: меняйте его через patch");
                if (op.content == null || op.content.trim().isEmpty()) throw new SkillException("create: нужен content (полный текст SKILL.md)");
                w.files.clear();
                w.files.put(SKILL_MD, bytes(op.content));
                w.existed = true;
                return "создан навык «" + name + "»";
            }
            case "patch": {
                requireExisting(w);
                String rel = rel(op.filePath);
                if (op.content != null) {
                    if (!SKILL_MD.equals(rel)) throw new SkillException("content переписывает только SKILL.md; для файла используйте old_string/new_string");
                    w.files.put(SKILL_MD, bytes(op.content));
                    return "SKILL.md навыка «" + name + "» переписан";
                }
                if (op.oldString == null || op.oldString.isEmpty()) throw new SkillException("patch: нужен old_string (непустой текст для поиска)");
                if (op.newString == null) throw new SkillException("patch: нужен new_string (пустая строка удаляет найденный текст)");
                byte[] cur = w.files.get(rel);
                if (cur == null) throw new SkillException("В навыке «" + name + "» нет файла «" + rel + "»");
                String text = text(cur);
                int hits = occurrences(text, op.oldString);
                if (hits == 0) throw new SkillException("Текст old_string не найден в «" + rel + "» навыка «" + name + "»: читайте навык через skill_view и копируйте точно");
                if (hits > 1 && !op.replaceAll) throw new SkillException("old_string найден " + hits + " раз: уточните текст или укажите replace_all");
                String out;
                if (op.replaceAll) {
                    out = text.replace(op.oldString, op.newString);
                } else {
                    int at = text.indexOf(op.oldString);
                    out = text.substring(0, at) + op.newString + text.substring(at + op.oldString.length());
                }
                w.files.put(rel, bytes(out));
                return "изменён навык «" + name + "» (" + rel + ")";
            }
            case "write_file": {
                requireExisting(w);
                String rel = rel(op.filePath);
                if (SKILL_MD.equals(rel)) throw new SkillException("write_file не пишет SKILL.md: используйте patch");
                byte[] b = op.fileBytes != null ? op.fileBytes : bytes(op.fileContent == null ? "" : op.fileContent);
                if (b.length > MAX_FILE) throw new SkillException("Файл «" + rel + "» больше " + MAX_FILE / 1024 + " КБ");
                w.files.put(rel, b);
                return "файл записан: " + name + "/" + rel;
            }
            case "remove_file": {
                requireExisting(w);
                String rel = rel(op.filePath);
                if (SKILL_MD.equals(rel) || !w.files.containsKey(rel)) throw new SkillException("Нет файла «" + rel + "» в навыке «" + name + "»");
                w.files.remove(rel);
                return "файл удалён: " + name + "/" + rel;
            }
            case "delete": {
                requireExisting(w);
                w.files.clear();
                w.existed = false;
                return "навык «" + name + "» удалён";
            }
            default:
                throw new SkillException("Неизвестное действие «" + action + "»: create, patch, write_file, remove_file или delete");
        }
    }

    private Work work(String name, Map<String, Work> work) throws IOException {
        Work w = work.get(name);
        if (w != null) return w;
        w = new Work(name);
        File base = new File(dir, name);
        if (new File(base, SKILL_MD).isFile()) {
            w.onDisk = true;
            w.existed = true;
            Map<String, File> found = new TreeMap<>();
            collectFiles(base, "", found);
            for (Map.Entry<String, File> e : found.entrySet()) w.files.put(e.getKey(), readBytes(e.getValue()));
        }
        work.put(name, w);
        return w;
    }

    private static void requireExisting(Work w) throws SkillException {
        if (!w.existed) throw new SkillException("Нет навыка «" + w.name + "» (сначала create)");
    }

    private void validate(Work w) throws IOException {
        byte[] md = w.files.get(SKILL_MD);
        if (md == null) throw new SkillException("В навыке «" + w.name + "» нет SKILL.md");
        if (md.length > MAX_SKILL_MD) throw new SkillException("SKILL.md навыка «" + w.name + "» больше " + MAX_SKILL_MD / 1024 + " КБ");
        long total = 0;
        for (Map.Entry<String, byte[]> e : w.files.entrySet()) {
            checkRel(e.getKey());
            total += e.getValue().length;
        }
        if (total > MAX_SKILL_TOTAL) throw new SkillException("Навык «" + w.name + "» больше " + MAX_SKILL_TOTAL / 1024 + " КБ вместе с файлами");
        try {
            SkillFormat.parse(text(md), w.name);
        } catch (SkillException e) {
            throw new SkillException("SKILL.md навыка «" + w.name + "»: " + e.getMessage());
        }
    }

    /** Stages the new trees in .tx, moves old and new into place, and rolls back on any failure. */
    private void commit(Map<String, Work> work) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Не удалось создать папку навыков");
        File tx = new File(dir, TX);
        ZipSafe.deleteTree(tx);
        if (!tx.mkdirs()) throw new IOException("Не удалось создать временную папку навыков");
        List<Moved> moved = new ArrayList<>();
        try {
            for (Work w : work.values()) if (w.existed) writeTree(new File(tx, w.name + ".new"), w.files);
            for (Work w : work.values()) {
                File live = new File(dir, w.name);
                if (live.exists()) {
                    File old = new File(tx, w.name + ".old");
                    Files.move(live.toPath(), old.toPath());
                    moved.add(new Moved(live, old));
                }
                if (w.existed) {
                    Files.move(new File(tx, w.name + ".new").toPath(), live.toPath());
                    moved.add(new Moved(live, null));
                }
            }
        } catch (IOException e) {
            for (int i = moved.size() - 1; i >= 0; i--) {
                Moved m = moved.get(i);
                ZipSafe.deleteTree(m.live);
                if (m.old != null && m.old.exists()) Files.move(m.old.toPath(), m.live.toPath());
            }
            ZipSafe.deleteTree(tx);
            throw e;
        }
        ZipSafe.deleteTree(tx);
    }

    /** A commit that was killed midway leaves .tx behind; restore the previous versions and drop the staging area. */
    private void recoverInterrupted() {
        File tx = new File(dir, TX);
        if (!tx.isDirectory()) return;
        try {
            File[] fs = tx.listFiles();
            if (fs != null) for (File f : fs) {
                if (!f.getName().endsWith(".old")) continue;
                String name = f.getName().substring(0, f.getName().length() - 4);
                ZipSafe.deleteTree(new File(dir, name));
                Files.move(f.toPath(), new File(dir, name).toPath());
            }
            ZipSafe.deleteTree(tx);
            journal.w(Journal.Cat.AGENT, "Незавершённое изменение навыков откачено");
        } catch (IOException e) {
            journal.w(Journal.Cat.AGENT, "Не удалось восстановить навыки после сбоя: " + e.getMessage());
        }
    }

    /** Writes the skill folder as a zip with entries {@code <name>/<relative path>}. */
    public synchronized void exportZip(String name, File out) throws IOException {
        Map<String, File> files = new TreeMap<>();
        collectFiles(requireDir(name), "", files);
        File tmp = new File(out.getParentFile(), out.getName() + ".tmp");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(tmp))) {
            for (Map.Entry<String, File> e : files.entrySet()) {
                z.putNextEntry(new ZipEntry(name + "/" + e.getKey()));
                z.write(readBytes(e.getValue()));
                z.closeEntry();
            }
        }
        Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /** Imports an exported archive as one atomic create batch. The archive must hold exactly one skill folder. */
    public synchronized List<String> importZip(File zip) throws IOException {
        ZipSafe.verify(zip);
        String top = null;
        List<Op> ops = new ArrayList<>();
        try (ZipFile z = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String n = ZipSafe.safeName(e.getName());
                int slash = n.indexOf('/');
                if (slash <= 0) throw new SkillException("В архиве навыка все файлы должны лежать в папке навыка: «" + n + "»");
                String first = n.substring(0, slash);
                if (top == null) top = first;
                else if (!top.equals(first)) throw new SkillException("В архиве больше одного навыка");
                String rel = n.substring(slash + 1);
                long limit = SKILL_MD.equals(rel) ? MAX_SKILL_MD : MAX_FILE;
                if (e.getSize() > limit) throw new SkillException("Файл «" + rel + "» в архиве слишком большой");
                byte[] b = readLimited(z.getInputStream(e), limit);
                Op op = new Op();
                op.name = first;
                if (SKILL_MD.equals(rel)) {
                    op.action = "create";
                    op.content = text(b);
                } else {
                    op.action = "write_file";
                    op.filePath = rel;
                    op.fileBytes = b;
                }
                ops.add(op);
            }
        }
        if (top == null) throw new SkillException("Архив пуст");
        boolean hasMd = false;
        for (Op op : ops) if ("create".equals(op.action)) hasMd = true;
        if (!hasMd) throw new SkillException("В архиве нет SKILL.md");
        ops.sort((a, b) -> "create".equals(a.action) ? -1 : "create".equals(b.action) ? 1 : 0);
        return apply(ops);
    }

    /**
     * Installs a bundled skill once. Names are remembered in {@code .seeded}, so a bundled skill the user deleted
     * does not come back, and an existing skill of the same name is never overwritten.
     */
    public synchronized boolean installSeed(String name, String skillMd) throws IOException {
        SkillFormat.checkName(name);
        SkillFormat.parse(skillMd, name);
        File marker = new File(dir, SEEDED);
        List<String> seeded = new ArrayList<>();
        if (marker.isFile()) for (String l : readText(marker).split("\n")) if (!l.trim().isEmpty()) seeded.add(l.trim());
        if (seeded.contains(name)) return false;
        seeded.add(name);
        boolean installed = false;
        if (!new File(new File(dir, name), SKILL_MD).exists()) {
            writeTree(new File(dir, name), Collections.singletonMap(SKILL_MD, bytes(skillMd)));
            installed = true;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Не удалось создать папку навыков");
        writeFile(marker, bytes(String.join("\n", seeded) + "\n"));
        return installed;
    }

    /** Relative path rule for SKILL.md and supporting files: only the four subfolders, no dots, slashes or traversal. */
    static void checkRel(String rel) throws SkillException {
        if (SKILL_MD.equals(rel)) return;
        String[] seg = rel.split("/", -1);
        if (seg.length < 2 || !SUBDIRS.contains(seg[0])) {
            throw new SkillException("Файл навыка должен лежать в references/, templates/, scripts/ или assets/: «" + rel + "»");
        }
        for (String s : seg) {
            if (!s.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")) throw new SkillException("Недопустимое имя файла: «" + rel + "»");
        }
    }

    private static String rel(String filePath) throws SkillException {
        String r = filePath == null || filePath.trim().isEmpty() ? SKILL_MD : filePath.trim();
        checkRel(r);
        return r;
    }

    private File requireDir(String name) throws IOException {
        SkillFormat.checkName(name);
        File base = new File(dir, name);
        if (!new File(base, SKILL_MD).isFile()) throw new SkillException("Нет навыка «" + name + "»");
        return base;
    }

    private List<File> skillDirs() {
        List<File> out = new ArrayList<>();
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) if (f.isDirectory() && !f.getName().startsWith(".")) out.add(f);
        Collections.sort(out, (a, b) -> a.getName().compareTo(b.getName()));
        return out;
    }

    private int skillCount() {
        int n = 0;
        for (File d : skillDirs()) if (new File(d, SKILL_MD).isFile()) n++;
        return n;
    }

    private static void collectFiles(File base, String prefix, Map<String, File> out) {
        File[] fs = base.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            String rel = prefix.isEmpty() ? f.getName() : prefix + "/" + f.getName();
            if (Files.isSymbolicLink(f.toPath())) continue;
            if (f.isDirectory()) collectFiles(f, rel, out);
            else if (f.isFile()) out.put(rel, f);
        }
    }

    private static void writeTree(File root, Map<String, byte[]> files) throws IOException {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Не удалось создать " + root.getName());
        for (Map.Entry<String, byte[]> e : files.entrySet()) writeFile(new File(root, e.getKey()), e.getValue());
    }

    private static void writeFile(File f, byte[] b) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Не удалось создать папку для " + f.getName());
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(b);
            os.getFD().sync();
        }
    }

    private static int occurrences(String text, String needle) {
        int n = 0, at = 0;
        while ((at = text.indexOf(needle, at)) >= 0) { n++; at += needle.length(); }
        return n;
    }

    private static byte[] readLimited(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (InputStream i = in) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = i.read(buf)) > 0) {
                bo.write(buf, 0, r);
                if (bo.size() > limit) throw new SkillException("Файл в архиве больше " + limit / 1024 + " КБ");
            }
        }
        return bo.toByteArray();
    }

    private static byte[] readBytes(File f) throws IOException { return Files.readAllBytes(f.toPath()); }
    private static String readText(File f) throws IOException { return text(readBytes(f)); }
    private static String text(byte[] b) { return new String(b, StandardCharsets.UTF_8); }
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
