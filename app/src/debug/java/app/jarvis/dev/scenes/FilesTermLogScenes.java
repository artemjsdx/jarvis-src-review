package app.jarvis.dev.scenes;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import app.jarvis.core.log.Journal;
import app.jarvis.core.tools.Sandbox;
import app.jarvis.data.SqlJournal;
import app.jarvis.dev.PreviewHost;
import app.jarvis.dev.PreviewKit;
import app.jarvis.rt.Jarvis;
import app.jarvis.ui.Shell;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;

/**
 * Scenes for the FILES / TERMINAL / LOG screens. Everything shown is real: the sandbox files actually exist, the
 * terminal output comes from real shell commands through j.shell, the journal rows went through j.journal.log, and the
 * export sheet is shown after a real SqlJournal.export.
 */
public final class FilesTermLogScenes {
    private FilesTermLogScenes() { }

    public static void render() throws Exception {
        Jarvis j = PreviewKit.jarvis();
        seedSandbox(j.sandbox);
        seedJournal(j);

        Shell s = PreviewKit.shell("files");
        PreviewHost.pump(400);
        PreviewHost.render(s, "01_files_root");

        s.navigate("files/work");
        PreviewHost.pump(400);
        PreviewHost.render(s, "02_files_work");

        app.jarvis.ui.screens.FilesScreen files = (app.jarvis.ui.screens.FilesScreen) s.current();
        files.openViewer(new File(j.sandbox.work, "отчёт.md"));
        PreviewHost.pump(500);
        PreviewHost.render(s, "03_text_viewer");

        s.overlays().dismissAll();
        PreviewHost.pump(300);
        files.openViewer(new File(j.sandbox.work, "диаграмма.png"));
        PreviewHost.pump(900);
        PreviewHost.render(s, "04_image_viewer");

        s.overlays().dismissAll();
        PreviewHost.pump(300);
        File zip = new File(j.sandbox.downloads, "архив-данных.zip");
        if (zip.isFile()) {
            files.openViewer(zip);
            PreviewHost.pump(300);
            // sha-256 is computed only on demand: tap the row through its click listener
            View shaRow = findRowWithText(s, "SHA-256");
            if (shaRow != null) shaRow.performClick();
            PreviewHost.pump(1200);
            PreviewHost.render(s, "05_details_sha256");
            s.overlays().dismissAll();
            PreviewHost.pump(300);
        }

        File mp3 = new File(j.sandbox.music, "привет-джарвис.mp3");
        if (mp3.isFile()) {
            files.openViewer(mp3);
            PreviewHost.pump(700);
            PreviewHost.render(s, "06_audio_sheet");
            s.overlays().dismissAll();
            PreviewHost.pump(300);
        }

        // folder picker (the move/copy chooser)
        app.jarvis.ui.files.FolderPicker.show(PreviewKit.host(), "Переместить «отчёт.md» в…", j.sandbox, j.sandbox.work, new app.jarvis.ui.files.FolderPicker.Listener() {
            @Override public void onPicked(File dir) { }
        });
        PreviewHost.pump(400);
        PreviewHost.render(s, "07_folder_picker");
        s.overlays().dismissAll();
        PreviewHost.pump(200);

        s.navigate("files/tmp/пустая");
        PreviewHost.pump(400);
        PreviewHost.render(s, "08_files_empty");

        // ---------------------------------------------------------------- terminal: real commands via the screen
        s.navigate("terminal");
        PreviewHost.pump(200);
        PreviewHost.render(s, "09_terminal_blank");

        app.jarvis.ui.screens.TerminalScreen term = (app.jarvis.ui.screens.TerminalScreen) s.current();
        runUntilIdle(term, "uname -a");
        runUntilIdle(term, "ls -la");
        runUntilIdle(term, "df -h | head -5");
        runUntilIdle(term, "ls /нет-такой-каталог");
        PreviewHost.render(s, "10_terminal_output");

        term.runCommand("sleep 1");
        PreviewHost.pump(400);
        PreviewHost.render(s, "11_terminal_running");
        waitIdle(term);
        PreviewHost.pump(200);

        term.showLimits();
        PreviewHost.pump(400);
        PreviewHost.render(s, "12_terminal_limits");
        s.overlays().dismissAll();
        PreviewHost.pump(200);

        // -------------------------------------------------------------------------------------------------------------------------------- log
        s.navigate("log");
        PreviewHost.pump(900);
        PreviewHost.render(s, "13_log_list");

        app.jarvis.ui.screens.LogScreen log = (app.jarvis.ui.screens.LogScreen) s.current();
        log.selectCategory("llm");
        PreviewHost.pump(600);
        PreviewHost.render(s, "14_log_llm");

        SqlJournal.Row top = j.journal.query("llm", null, "", 0, 1).isEmpty() ? null : j.journal.query("llm", null, "", 0, 1).get(0);
        if (top == null) top = j.journal.query(null, null, "", 0, 1).get(0);
        log.openDetail(top);
        PreviewHost.pump(400);
        PreviewHost.render(s, "15_log_detail");
        s.overlays().dismissAll();
        PreviewHost.pump(200);

        log.export();
        PreviewHost.pump(1200);
        PreviewHost.render(s, "16_log_export");
        File exported = new File(j.home, "exports");
        File[] exp = exported.listFiles();
        System.out.println("[scene] exports dir: " + (exp == null ? "none" : exp.length + " files, e.g. " + (exp.length > 0 ? exp[0].getPath() : "")));
    }

    // -------------------------------------------------------------------------------------------------------------- helpers
    private static void runUntilIdle(app.jarvis.ui.screens.TerminalScreen term, String cmd) throws Exception {
        term.runCommand(cmd);
        waitIdle(term);
        PreviewHost.pump(120);
    }

    private static void waitIdle(app.jarvis.ui.screens.TerminalScreen term) throws Exception {
        long deadline = System.currentTimeMillis() + 20000;
        while (isRunning(term) && System.currentTimeMillis() < deadline) PreviewHost.pump(60);
        PreviewHost.pump(200);
    }

    private static boolean isRunning(app.jarvis.ui.screens.TerminalScreen term) {
        try {
            java.lang.reflect.Field f = app.jarvis.ui.screens.TerminalScreen.class.getDeclaredField("running");
            f.setAccessible(true);
            return f.getBoolean(term);
        } catch (Throwable t) { return false; }
    }

    private static View findRowWithText(View root, String text) {
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findRowWithText(g.getChildAt(i), text);
                if (r != null) return r;
            }
        }
        if (root instanceof TextView && ((TextView) root).getText().toString().contains(text)) {
            View p = root;
            while (p != null && !(p.getParent() instanceof ViewGroup && ((ViewGroup) p.getParent()).isClickable() && ((ViewGroup) p.getParent()).hasOnClickListeners())) p = (View) p.getParent();
            return p != null && p != root ? p : root;
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------------------------- seeding
    private static void seedSandbox(Sandbox sb) throws Exception {
        write(new File(sb.work, "отчёт.md"), "# Отчёт за неделю\n\n## Сделано\n- Голосовой ввод работает\n- Импорт файлов готов\n- Терминал показывает вывод потоково\n\n## План\n- Проснуться по слову «Джарвис»\n- Утренняя сводка с погодой\n\n> Данные настоящие: файл записан сценой предпросмотра.\n");
        write(new File(sb.work, "config.json"), "{\n  \"provider\": \"inception\",\n  \"model\": \"mercury-3\",\n  \"temperature\": 0.75,\n  \"fallbacks\": [\"openrouter\"],\n  \"voice\": {\n    \"stt\": \"groq\",\n    \"tts\": \"edge\",\n    \"wake\": true\n  }\n}\n");
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 4000; i++) big.append("2026-10-28 ").append(String.format(java.util.Locale.US, "%02d", i % 24)).append(":1").append(i % 10)
            .append(":00 INFO worker задача ").append(i).append(" завершена за ").append(20 + i % 90).append(" мс\n");
        write(new File(sb.work, "server.log"), big.toString());
        write(new File(sb.work, "очень длинное имя файла — отчёт о поездке в Санкт-Петербург за октябрь 2026 года с приложениями и фотографиями.txt"),
            "Поездка прошла хорошо. Погода была пасмурная, но два дня выдались ясными.\nЭрмитаж посетили в четверг, очереди не было.\n");
        File sub = new File(sb.work, "подпроект");
        sub.mkdirs();
        write(new File(sub, "заметки.md"), "# Подпроект\n\nЧерновики и материалы.\n");
        write(new File(sub, "данные.csv"), "дата,значение\n2026-10-26,42\n2026-10-27,57\n2026-10-28,51\n");

        // image: a real gradient PNG drawn with Bitmap (640x400)
        Bitmap b = Bitmap.createBitmap(640, 400, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 640, 400, 0xFF5B3FD8, 0xFFB9A6FF, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, 640, 400, p);
        p.setShader(null);
        p.setColor(0xFFFFFFFF);
        p.setTextSize(56);
        cv.drawText("Jarvis preview", 60, 220, p);
        File png = new File(sb.work, "диаграмма.png");
        try (FileOutputStream os = new FileOutputStream(png)) { b.compress(Bitmap.CompressFormat.PNG, 100, os); }
        b.recycle();

        // downloads: 60 small reports
        for (int i = 1; i <= 60; i++) write(new File(sb.downloads, String.format(java.util.Locale.US, "выгрузка-2026-10-%02d.txt", i)),
            "Выгрузка за день " + i + "\nстрок: " + (100 + i * 7) + "\nстатус: ок\n");

        // music: real speech mp3 (copied from the local sample set) + a real generated wav (8 kHz sine)
        File sample = new File(System.getenv("TMPDIR") == null ? "/data/data/com.termux/files/usr/tmp" : System.getenv("TMPDIR"), "edge_ru-RU-SvetlanaNeural.mp3");
        if (sample.isFile()) copy(sample, new File(sb.music, "привет-джарвис.mp3"));
        writeWav(new File(sb.music, "тон-440.wav"), 440.0, 3);

        // inbox: csv + a real zip (2 entries)
        write(new File(sb.inbox, "показания.csv"), "время,температура\n08:00,6.2\n12:00,11.8\n18:00,9.4\n");
        File zip = new File(sb.downloads, "архив-данных.zip");
        try (ZipOutputStream zs = new ZipOutputStream(new FileOutputStream(zip))) {
            zs.putNextEntry(new ZipEntry("readme.txt"));
            zs.write("Архив собран сценой предпросмотра через java.util.zip.\n".getBytes(StandardCharsets.UTF_8));
            zs.closeEntry();
            zs.putNextEntry(new ZipEntry("показания.csv"));
            zs.write("время,температура\n08:00,6.2\n".getBytes(StandardCharsets.UTF_8));
            zs.closeEntry();
        }

        // tmp: a 2000-entry folder + an empty folder
        File many = new File(sb.tmp, "очень-много-файлов");
        many.mkdirs();
        for (int i = 0; i < 2000; i++) write(new File(many, String.format(java.util.Locale.US, "кусок-%04d.tmp", i)), "" + i);
        new File(sb.tmp, "пустая").mkdirs();

        // docs
        write(new File(sb.docs, "README.md"), "# Документы\n\nПапка для заметок агента и пользователя.\n");
        write(new File(sb.docs, "план-голоса.txt"), "1. Пробуждение по фразе\n2. STT через Groq\n3. TTS через Edge\n");

    }

    private static void seedJournal(Jarvis j) {
        String[] msgs = {
            "Запуск Jarvis 0.1.0 (bc71b5b-dirty), Android 16 (SDK 36), vivo I2407", "Подключение к LLM установлено, модель mercury-3",
            "Ответ модели получен, 1 842 токена за 3,1 с", "Вызов инструмента fs_write завершён успешно", "Команда shell_exec: ls -la (exit 0)",
            "Файл отчёт.md сохранён в work/", "Загрузка http-fetch завершена, 1,2 МБ", "Пробуждение по фразе «Джарвис»",
            "Распознавание речи: «какая погода завтра»", "Синтез речи: край, 3,4 с аудио", "Плеер играет: привет-джарвис.mp3",
            "ADB не настроен: беспроводная отладка выключена", "Запрошено разрешение RECORD_AUDIO", "Кадр производительности: p95 ответа 2,8 с",
            "Соединение с LLM оборвалось, повтор через 2 с", "Ключ провайдера не задан", "Распознавание не дало текста",
            "Медленный ответ инструмента: 4,2 с", "Импорт файла отменён пользователем", "Не удалось скачать: host не найден",
        };
        Journal.Level[] lv = Journal.Level.values();
        String[] cats = {Journal.Cat.SYSTEM, Journal.Cat.AGENT, Journal.Cat.LLM, Journal.Cat.TOOLS, Journal.Cat.TERMINAL, Journal.Cat.FILES,
            Journal.Cat.DOWNLOADS, Journal.Cat.WAKE, Journal.Cat.STT, Journal.Cat.TTS, Journal.Cat.PLAYER, Journal.Cat.ADB,
            Journal.Cat.PERMISSIONS, Journal.Cat.PERFORMANCE};
        for (int i = 0; i < 300; i++) {
            String cat = cats[i % cats.length];
            Journal.Level level = i % 23 == 0 ? Journal.Level.ERROR : i % 7 == 0 ? Journal.Level.WARN : i % 5 == 0 ? Journal.Level.DEBUG : Journal.Level.INFO;
            String msg = msgs[(i * 7 + i / 14) % msgs.length];
            String runId = (i % 3 == 0) ? ("r-20261028-" + (100 + i / 3)) : null;
            JSONObject det = null;
            long dur = -1;
            if (i % 4 == 0) {
                det = new JSONObject();
                try {
                    det.put("модель", "mercury-3");
                    det.put("токены", 900 + (i % 500));
                    det.put("провайдер", "inception");
                } catch (Throwable ignored) { }
            }
            if (i % 2 == 0) dur = 20L + (i % 90) * 47;
            j.journal.log(cat, level, msg + " №" + i, runId, det, dur);
        }
    }

    private static void write(File f, String text) throws IOException {
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory()) dir.mkdirs();
        try (FileOutputStream os = new FileOutputStream(f)) { os.write(text.getBytes(StandardCharsets.UTF_8)); }
    }

    private static void copy(File src, File dst) throws IOException {
        try (FileInputStream in = new FileInputStream(src); FileOutputStream os = new FileOutputStream(dst)) {
            byte[] b = new byte[64 * 1024];
            int n;
            while ((n = in.read(b)) > 0) os.write(b, 0, n);
        }
    }

    /** A real 8 kHz 16-bit mono WAV: header + sine samples (playable, honest data). */
    private static void writeWav(File f, double hz, int seconds) throws IOException {
        int rate = 8000, samples = rate * seconds;
        try (FileOutputStream os = new FileOutputStream(f)) {
            byte[] header = new byte[44];
            int dataLen = samples * 2, fileLen = 36 + dataLen;
            header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
            put(header, 4, fileLen); header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
            header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
            put(header, 16, 16); header[20] = 1; header[22] = 1;
            put(header, 24, rate); put(header, 28, rate * 2); header[32] = 2; header[34] = 16;
            header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
            put(header, 40, dataLen);
            os.write(header);
            for (int i = 0; i < samples; i++) {
                short v = (short) (Math.sin(2 * Math.PI * hz * i / rate) * 9000 * Math.min(1.0, (seconds - (double) i / rate)));
                os.write(v & 0xFF); os.write((v >> 8) & 0xFF);
            }
        }
    }

    private static void put(byte[] b, int off, int v) {
        b[off] = (byte) v; b[off + 1] = (byte) (v >> 8); b[off + 2] = (byte) (v >> 16); b[off + 3] = (byte) (v >> 24);
    }
}
