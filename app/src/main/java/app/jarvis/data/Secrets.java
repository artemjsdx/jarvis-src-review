package app.jarvis.data;

import android.util.Base64;
import app.jarvis.core.util.J;
import app.jarvis.core.util.Redactor;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/**
 * API keys and tokens. Values are AES-256-GCM encrypted with a key that lives in the Android Keystore (non-exportable);
 * when the Keystore is unavailable (emulators, broken vendor builds) they are stored in clear and {@link #isEncrypted()}
 * says so. All values are registered with the Redactor so they can never reach the journal.
 */
public final class Secrets {
    private static final String ALIAS = "jarvis_secrets_v1";
    private final File file;
    private final LinkedHashMap<String, String> plain = new LinkedHashMap<>();   // in memory only
    private SecretKey key;
    private boolean encrypted;
    private String keystoreProblem = "";

    public Secrets(File file) {
        this.file = file;
        initKey();
        load();
    }

    private void initKey() {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (!ks.containsAlias(ALIAS)) {
                KeyGenerator g = KeyGenerator.getInstance("AES", "AndroidKeyStore");
                g.init(new android.security.keystore.KeyGenParameterSpec.Builder(ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
                g.generateKey();
            }
            key = (SecretKey) ks.getKey(ALIAS, null);
            encrypted = key != null;
            // self-test: a vendor keystore that cannot actually encrypt must not silently eat the user's keys
            if (encrypted) decrypt(encrypt("ok"));
        } catch (Throwable t) {
            key = null;
            encrypted = false;
            keystoreProblem = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    public boolean isEncrypted() { return encrypted; }
    public String keystoreProblem() { return keystoreProblem; }

    private String encrypt(String s) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = c.getIV(), ct = c.doFinal(s.getBytes(StandardCharsets.UTF_8));
        byte[] all = new byte[1 + iv.length + ct.length];
        all[0] = (byte) iv.length;
        System.arraycopy(iv, 0, all, 1, iv.length);
        System.arraycopy(ct, 0, all, 1 + iv.length, ct.length);
        return "e1:" + Base64.encodeToString(all, Base64.NO_WRAP);
    }

    private String decrypt(String s) throws Exception {
        byte[] all = Base64.decode(s.substring(3), Base64.NO_WRAP);
        int n = all[0] & 0xff;
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 1, n));
        return new String(c.doFinal(all, 1 + n, all.length - 1 - n), StandardCharsets.UTF_8);
    }

    private void load() {
        if (!file.isFile()) return;
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] b = new byte[(int) file.length()];
            int off = 0;
            while (off < b.length) { int n = in.read(b, off, b.length - off); if (n < 0) break; off += n; }
            JSONObject o = J.parseOrNull(new String(b, 0, off, StandardCharsets.UTF_8));
            if (o == null) return;
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next(), v = J.str(o, k);
                try {
                    String val = v.startsWith("e1:") ? (key != null ? decrypt(v) : null) : v.startsWith("p1:") ? new String(Base64.decode(v.substring(3), Base64.NO_WRAP), StandardCharsets.UTF_8) : null;
                    if (val != null) { plain.put(k, val); Redactor.addSecret(val); }
                } catch (Exception e) { /* undecryptable (key lost after reinstall): drop silently, user re-enters */ }
            }
        } catch (IOException ignored) { }
    }

    private synchronized void persist() {
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, String> e : plain.entrySet()) {
                String v = key != null ? encrypt(e.getValue()) : "p1:" + Base64.encodeToString(e.getValue().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
                J.put(o, e.getKey(), v);
            }
            File dir = file.getAbsoluteFile().getParentFile();
            if (dir != null) dir.mkdirs();
            File tmp = new File(dir, file.getName() + ".tmp");
            try (FileOutputStream os = new FileOutputStream(tmp)) { os.write(o.toString().getBytes(StandardCharsets.UTF_8)); os.getFD().sync(); }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            try { file.setReadable(false, false); file.setReadable(true, true); file.setWritable(false, false); file.setWritable(true, true); } catch (Throwable ignored) { }
        } catch (Exception ignored) { }
    }

    public synchronized String get(String name) { String v = plain.get(name); return v == null ? "" : v; }
    public synchronized boolean has(String name) { String v = plain.get(name); return v != null && !v.isEmpty(); }
    public synchronized TreeSet<String> names() { return new TreeSet<>(plain.keySet()); }

    public synchronized void set(String name, String value) {
        value = value == null ? "" : value.trim();
        if (value.isEmpty()) { remove(name); return; }
        plain.put(name, value);
        Redactor.addSecret(value);
        persist();
    }

    public synchronized void remove(String name) { if (plain.remove(name) != null) persist(); }

    /** Imports {"name":"value",...}; returns how many entries were stored. Names starting with '_' are ignored. */
    public int importJson(String json) {
        JSONObject o = J.parseOrNull(json);
        if (o == null) return 0;
        int n = 0;
        Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (k.startsWith("_")) continue;
            String v = J.str(o, k).trim();
            if (!v.isEmpty()) { synchronized (this) { plain.put(k, v); } Redactor.addSecret(v); n++; }
        }
        persist();
        return n;
    }

    /** "sk_7e…765" style hint for UI; never the whole key. */
    public static String mask(String v) {
        if (v == null || v.isEmpty()) return "не задан";
        if (v.length() <= 10) return "••••";
        return v.substring(0, 4) + "…" + v.substring(v.length() - 3) + "  (" + v.length() + " симв.)";
    }
}
