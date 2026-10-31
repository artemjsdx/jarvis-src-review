package app.jarvis.dev.checks;

import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.HandlerThread;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Loads every cue clip through a real SoundPool on the phone's ART and reads its length with MediaExtractor.
 * Nothing is played: play() is never called, so no sound leaves the speaker. Args: assetsSoundsDir [extraOggDir].
 */
public final class CueAssetCheck {
    public static void main(String[] a) {
        try {
            File dir = new File(a[0]);
            List<File> files = new ArrayList<>();
            JSONArray sets = new JSONArray(read(new File(dir, "sounds.json")));
            for (int i = 0; i < sets.length(); i++) {
                JSONObject o = sets.getJSONObject(i);
                files.add(new File(dir, o.getString("start")));
                files.add(new File(dir, o.getString("stop")));
                files.add(new File(dir, o.getString("error")));
            }
            if (a.length > 1) {
                File[] extra = new File(a[1]).listFiles();
                if (extra != null) for (File f : extra) if (f.getName().endsWith(".ogg")) files.add(f);
            }
            final Map<Integer, String> names = new TreeMap<>();
            final CountDownLatch loaded = new CountDownLatch(files.size());
            final int[] ok = {0};
            final int[] failed = {0};
            HandlerThread ht = new HandlerThread("cue-check");
            ht.start();
            final SoundPool[] poolRef = new SoundPool[1];
            final List<File> toLoad = files;
            new Handler(ht.getLooper()).post(new Runnable() {
                @Override public void run() {
                    AudioAttributes attrs = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                    SoundPool pool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs).build();
                    poolRef[0] = pool;
                    pool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
                        @Override public void onLoadComplete(SoundPool sp, int sampleId, int status) {
                            if (status == 0) ok[0]++; else failed[0]++;
                            loaded.countDown();
                        }
                    });
                    for (File f : toLoad) {
                        int id = pool.load(f.getPath(), 1);
                        names.put(id, f.getName());
                    }
                }
            });
            boolean all = loaded.await(30, TimeUnit.SECONDS);
            System.out.println("CUEPOOL files=" + files.size() + " loaded=" + ok[0] + " failed=" + failed[0] + " complete=" + all);
            for (File f : files) {
                android.media.MediaExtractor ex = new android.media.MediaExtractor();
                ex.setDataSource(f.getPath());
                long us = ex.getTrackFormat(0).getLong(android.media.MediaFormat.KEY_DURATION);
                ex.release();
                String d = Long.toString(us / 1000);
                System.out.println("CUEFILE " + f.getName() + " ms=" + d + " bytes=" + f.length());
            }
            if (poolRef[0] != null) poolRef[0].release();
            ht.quitSafely();
            System.out.println(ok[0] == files.size() && all ? "CUECHECK OK" : "CUECHECK FAILED");
        } catch (Throwable t) {
            t.printStackTrace(System.out);
        }
    }

    private static String read(File f) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
    }
}
