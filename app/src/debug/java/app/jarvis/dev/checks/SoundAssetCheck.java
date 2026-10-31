package app.jarvis.dev.checks;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import java.io.File;
import java.nio.ByteBuffer;

/** Decodes every app/assets/sounds/*.ogg with the real Android MediaCodec and reports format, length and peak. */
public final class SoundAssetCheck {
    public static void main(String[] a) {
        int bad = 0;
        try {
            File[] fs = new File(a[0]).listFiles(new java.io.FilenameFilter() { public boolean accept(File d, String n) { return n.endsWith(".ogg"); } });
            java.util.Arrays.sort(fs);
            for (File f : fs) {
                MediaExtractor ex = new MediaExtractor();
                ex.setDataSource(f.getPath());
                MediaFormat fmt = ex.getTrackFormat(0);
                ex.selectTrack(0);
                MediaCodec c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
                c.configure(fmt, null, null, 0);
                c.start();
                MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                boolean inDone = false, outDone = false;
                long samples = 0; int peak = 0;
                int rate = 0, ch = 0;
                while (!outDone) {
                    if (!inDone) {
                        int i = c.dequeueInputBuffer(10000);
                        if (i >= 0) {
                            ByteBuffer b = c.getInputBuffer(i);
                            int n = ex.readSampleData(b, 0);
                            if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true; }
                            else { c.queueInputBuffer(i, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                        }
                    }
                    int o = c.dequeueOutputBuffer(bi, 10000);
                    if (o >= 0) {
                        ByteBuffer ob = c.getOutputBuffer(o);
                        ob.position(bi.offset); ob.limit(bi.offset + bi.size);
                        java.nio.ShortBuffer sb = ob.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer();
                        while (sb.hasRemaining()) { int v = Math.abs(sb.get()); if (v > peak) peak = v; samples++; }
                        c.releaseOutputBuffer(o, false);
                        if ((bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outDone = true;
                    } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat of = c.getOutputFormat();
                        rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                }
                c.stop(); c.release(); ex.release();
                double ms = samples * 1000.0 / Math.max(1, rate * ch);
                boolean ok = rate == 44100 && ch == 1 && ms > 100 && ms < 500 && peak > 1000;
                if (!ok) bad++;
                System.out.printf("%s %s %d Hz ch%d %.0f ms peak %.1f dBFS%n", ok ? "ok  " : "FAIL", f.getName(), rate, ch, ms, 20 * Math.log10(peak / 32768.0));
            }
        } catch (Throwable t) { t.printStackTrace(System.out); bad++; }
        System.out.println(bad == 0 ? "SOUNDCHECK OK" : "SOUNDCHECK FAILURES " + bad);
        System.out.flush();
        System.exit(bad == 0 ? 0 : 1);
    }
}
