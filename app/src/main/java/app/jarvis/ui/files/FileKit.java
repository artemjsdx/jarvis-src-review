package app.jarvis.ui.files;

import android.content.Context;
import android.text.TextUtils;
import android.widget.TextView;
import app.jarvis.R;
import app.jarvis.core.util.Mime;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Shared helpers for the Files screen: icon by type, human-readable sizes/dates/durations, Russian plurals. */
public final class FileKit {
    private FileKit() { }

    public static final Locale RU = new Locale("ru", "RU");

    public static int iconFor(File f) {
        String n = f.getName().toLowerCase(Locale.US);
        if (f.isDirectory()) return R.drawable.ic_folder;
        String m = Mime.guess(n);
        if (m.startsWith("audio/")) return R.drawable.ic_file_audio;
        if (m.startsWith("image/")) return R.drawable.ic_image;
        if (m.startsWith("video/")) return R.drawable.ic_video;
        if (m.equals("application/zip")) return R.drawable.ic_archive;
        if (m.equals("application/vnd.android.package-archive")) return R.drawable.ic_package;
        if (Mime.isText(n) || m.equals("application/pdf")) return R.drawable.ic_file_text;
        return R.drawable.ic_file;
    }

    /** Type label for the details sheet: "Папка", "Текст (md)", "Аудио (mp3)"… */
    public static String typeLabel(File f) {
        if (f.isDirectory()) return "Папка";
        String n = f.getName().toLowerCase(Locale.US);
        int d = n.lastIndexOf('.');
        String ext = d < 0 ? "" : n.substring(d + 1);
        String m = Mime.guess(n);
        String kind;
        if (m.startsWith("audio/")) kind = "Аудио";
        else if (m.startsWith("image/")) kind = "Изображение";
        else if (m.startsWith("video/")) kind = "Видео";
        else if (m.equals("application/zip")) kind = "Архив";
        else if (m.equals("application/vnd.android.package-archive")) kind = "Приложение Android";
        else if (m.equals("application/pdf")) kind = "PDF";
        else if (Mime.isText(n)) kind = "Текст";
        else kind = "Файл";
        return ext.isEmpty() ? kind : kind + " (" + ext + ")";
    }

    public static String size(long b) {
        if (b < 1024) return b + " Б";
        double v = b;
        if (v < 1024.0 * 1024) return num(v / 1024.0) + " КБ";
        if (v < 1024.0 * 1024 * 1024) return num(v / (1024.0 * 1024)) + " МБ";
        return num(v / (1024.0 * 1024 * 1024)) + " ГБ";
    }

    /** 1,2 / 12 / 123 — one decimal only under 10. */
    private static String num(double v) {
        if (v >= 100) return String.format(RU, "%.0f", v);
        if (v >= 10) return String.format(RU, "%.1f", v).replace(",0", "");
        return String.format(RU, "%.1f", v);
    }

    public static String date(long ms) {
        if (ms <= 0) return "";
        Date d = new Date(ms);
        String pattern;
        Calendar calc = Calendar.getInstance();
        if (calc.get(Calendar.YEAR) == d.getYear() + 1900) pattern = "d MMM, HH:mm";
        else pattern = "d MMM yyyy";
        return new SimpleDateFormat(pattern, RU).format(d);
    }

    /** 450 мс · 1,2 с · 2 мин 03 с · 1 ч 04 мин */
    public static String duration(long ms) {
        if (ms < 0) return "";
        if (ms < 1000) return ms + " мс";
        long s = Math.round(ms / 1000.0);
        if (s < 60) return num(ms / 1000.0) + " с";
        if (s < 3600) return (s / 60) + " мин " + pad(s % 60) + " с";
        return (s / 3600) + " ч " + pad((s % 3600) / 60) + " мин";
    }

    private static String pad(long v) { return v < 10 ? "0" + v : String.valueOf(v); }

    public static String plural(long n, String one, String few, String many) {
        long m10 = n % 10, m100 = n % 100;
        if (m10 == 1 && m100 != 11) return one;
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return few;
        return many;
    }

    public static String entryCount(long n) { return n + " " + plural(n, "элемент", "элемента", "элементов"); }

    /** Subtitle for a list row: "1,2 МБ · 28 окт, 14:32" for files, "5 элементов" for folders. */
    public static String subtitle(File f) {
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            return entryCount(c == null ? 0 : c.length);
        }
        String s = size(f.length());
        String d = date(f.lastModified());
        return d.isEmpty() ? s : s + " · " + d;
    }

    /** Applies single-line end ellipsis (long file names must never wrap a list row). */
    public static void ellipsize(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
    }
}
