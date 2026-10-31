package app.jarvis.dev.checks.media;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Looper;

/** One-off probe: why does AudioTrack fail to initialize from app_process, and which setup path works? */
public final class AudioProbe {

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) { System.out.println("CRASH " + t); t.printStackTrace(System.out); }
        System.out.flush();
    }

    static void p(String s) { System.out.println("  " + s); System.out.flush(); }

    static void run() throws Exception {
        Looper.prepareMainLooper();
        p("looper prepared");

        Context sys = null;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("systemMain").invoke(null);
            sys = (Context) at.getMethod("getSystemContext").invoke(thread);
            p("systemMain ok: " + sys.getPackageName() + " opPackage=" + sys.getOpPackageName());
        } catch (Throwable t) {
            p("systemMain FAILED: " + t + " cause=" + (t.getCause() == null ? "-" : t.getCause()));
        }

        AudioManager am = sys == null ? null : (AudioManager) sys.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            p("AudioManager ok, isMusicActive=" + am.isMusicActive() + " ringer=" + am.getRingerMode());
        } else {
            p("no AudioManager");
        }

        int rate = 48_000;
        int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        p("getMinBufferSize(48k) = " + min);

        // 1) Builder with assistant attributes (what PcmTrack does)
        try {
            AudioTrack t = new AudioTrack(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                    new AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                    min * 2, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            p("1 builder/assistant: state=" + t.getState() + " session=" + t.getAudioSessionId());
            if (t.getState() == AudioTrack.STATE_INITIALIZED) { t.play(); t.write(new byte[4800], 0, 4800, AudioTrack.WRITE_BLOCKING); p("1 played"); t.release(); return; }
            t.release();
        } catch (Throwable t) { p("1 builder/assistant THREW: " + t); }

        // 2) legacy constructor
        try {
            @SuppressWarnings("deprecation")
            AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, min * 2, AudioTrack.MODE_STREAM);
            p("2 legacy: state=" + t.getState());
            if (t.getState() == AudioTrack.STATE_INITIALIZED) { t.play(); t.write(new byte[4800], 0, 4800, AudioTrack.WRITE_BLOCKING); p("2 played"); t.release(); return; }
            t.release();
        } catch (Throwable t) { p("2 legacy THREW: " + t); }

        // 3) builder with USAGE_MEDIA
        try {
            AudioTrack t = new AudioTrack(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                    new AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                    min * 2, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            p("3 builder/media: state=" + t.getState());
            if (t.getState() == AudioTrack.STATE_INITIALIZED) { t.play(); t.write(new byte[4800], 0, 4800, AudioTrack.WRITE_BLOCKING); p("3 played"); t.release(); return; }
            t.release();
        } catch (Throwable t) { p("3 builder/media THREW: " + t); }

        // 4) Builder.setContext(createPackageContext(<our REAL binder uid's package>)) — proper attribution
        //    (PRoot shows a translated uid; the real one is 10346 = com.termux, per `cmd package list packages -U`)
        Context pkgCtx = null;
        try {
            String owner = "com.termux";
            pkgCtx = sys.createPackageContext(owner, 0);
            Object as = null;
            try { as = Context.class.getMethod("getAttributionSource").invoke(pkgCtx); } catch (Throwable ignored) { }
            p("4 pkgCtx=" + pkgCtx.getPackageName() + " op=" + pkgCtx.getOpPackageName() + " attribution=" + as);
        } catch (Throwable t) { p("4 createPackageContext THREW: " + t); }
        if (pkgCtx != null) try {
            java.lang.reflect.Method sc = AudioTrack.Builder.class.getMethod("setContext", Context.class);
            AudioTrack.Builder b = (AudioTrack.Builder) sc.invoke(new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                            .setBufferSizeInBytes(min * 2).setTransferMode(AudioTrack.MODE_STREAM),
                    pkgCtx);
            AudioTrack t = b.build();
            p("4 builder.setContext(pkg): state=" + t.getState() + " session=" + t.getAudioSessionId());
            if (t.getState() == AudioTrack.STATE_INITIALIZED) { t.play(); t.write(new byte[4800], 0, 4800, AudioTrack.WRITE_BLOCKING); p("4 played"); t.release(); return; }
            t.release();
        } catch (Throwable t) { p("4 setContext THREW: " + t + " cause=" + t.getCause()); }

        // 5) hidden Builder.setAttributionSource(AttributionSource) with a hand-built one for our uid
        try {
            Class<?> asClass = Class.forName("android.content.AttributionSource");
            Object as = Class.forName("android.content.AttributionSource$Builder").getConstructor(int.class)
                    .newInstance(10347);
            as.getClass().getMethod("setPackageName", String.class).invoke(as, "bin.mt.plus");
            as = as.getClass().getMethod("build").invoke(as);
            AudioTrack.Builder b = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(min * 2).setTransferMode(AudioTrack.MODE_STREAM);
            try {
                java.lang.reflect.Method m = AudioTrack.Builder.class.getMethod("setAttributionSource", asClass);
                b = (AudioTrack.Builder) m.invoke(b, as);
                p("5 hidden setAttributionSource present");
            } catch (NoSuchMethodException nsme) {
                p("5 hidden setAttributionSource NOT on Builder: " + nsme);
            }
            AudioTrack t = b.build();
            p("5 setAttributionSource: state=" + t.getState());
            if (t.getState() == AudioTrack.STATE_INITIALIZED) { t.play(); t.write(new byte[4800], 0, 4800, AudioTrack.WRITE_BLOCKING); p("5 played"); t.release(); return; }
            t.release();
        } catch (Throwable t) { p("5 setAttributionSource THREW: " + t + " cause=" + t.getCause()); }

        // 6) repoint the system ActivityThread's op-package to our uid's package, then retry the plain builder
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object ct = at.getMethod("currentActivityThread").invoke(null);
            for (java.lang.reflect.Field f : at.getDeclaredFields()) {
                if (f.getType() == String.class && f.getName().toLowerCase().contains("packag")) {
                    f.setAccessible(true);
                    p("6 field " + f.getName() + " = " + f.get(ct));
                    f.set(ct, "bin.mt.plus");
                }
            }
            Object myAs = Class.forName("android.content.AttributionSource").getMethod("myAttributionSource").invoke(null);
            p("6 myAttributionSource = " + myAs);
            AudioTrack t = new AudioTrack(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                    new AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                    min * 2, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            p("6 plain builder after repoint: state=" + t.getState());
            if (t.getState() == AudioTrack.STATE_INITIALIZED) { t.play(); t.write(new byte[4800], 0, 4800, AudioTrack.WRITE_BLOCKING); p("6 played"); t.release(); return; }
            t.release();
        } catch (Throwable t) { p("6 repoint THREW: " + t + " cause=" + t.getCause()); }

        p("all paths failed; sleeping 2 s so logcat catches up");
        Thread.sleep(2000);
    }
}
