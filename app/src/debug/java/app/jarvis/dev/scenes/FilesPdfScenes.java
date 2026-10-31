package app.jarvis.dev.scenes;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import app.jarvis.dev.PreviewHost;
import app.jarvis.dev.PreviewKit;
import app.jarvis.rt.Jarvis;
import app.jarvis.ui.Shell;
import app.jarvis.ui.screens.FilesScreen;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** The PDF viewer on the real ART: a two-page PDF is written with android.graphics.pdf.PdfDocument and opened from Files. */
public final class FilesPdfScenes {
    private FilesPdfScenes() { }

    public static void render() throws Exception {
        Jarvis j = PreviewKit.jarvis();
        File pdf = new File(j.sandbox.docs, "тестовый-документ.pdf");
        makePdf(pdf);
        Shell s = PreviewKit.shell("files");
        PreviewHost.pump(400);
        FilesScreen files = (FilesScreen) s.current();
        files.openViewer(pdf);
        PreviewHost.pump(3000);
        PreviewHost.render(s, "20_pdf_viewer");
        PreviewHost.pump(1500);
        PreviewHost.render(s, "21_pdf_viewer_late");
        System.out.println("[scene] pdf bytes " + pdf.length());
        s.overlays().dismissAll();
        PreviewHost.pump(300);
        System.out.println("[scene] pdf pages " + app.jarvis.ui.files.PdfSheet.pageCount(pdf));
        Bitmap page = app.jarvis.ui.files.PdfSheet.render(pdf, 1, 600);
        File out = new File("/data/data/com.termux/files/usr/tmp/jv-pdf-page2.png");
        try (FileOutputStream o = new FileOutputStream(out)) { page.compress(Bitmap.CompressFormat.PNG, 100, o); }
        System.out.println("[scene] page 2 rendered " + page.getWidth() + "x" + page.getHeight() + " -> " + out.getPath());
    }

    static void makePdf(File f) throws IOException {
        PdfDocument doc = new PdfDocument();
        try {
            for (int i = 1; i <= 2; i++) {
                PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder(595, 842, i).create());
                Canvas c = page.getCanvas();
                Paint p = new Paint();
                p.setColor(Color.BLACK);
                p.setTextSize(28);
                c.drawText("Джарвис: страница " + i, 60, 120, p);
                p.setTextSize(18);
                c.drawText("Просмотр PDF через PdfRenderer", 60, 160, p);
                doc.finishPage(page);
            }
            try (FileOutputStream out = new FileOutputStream(f)) { doc.writeTo(out); }
        } finally {
            doc.close();
        }
    }
}
