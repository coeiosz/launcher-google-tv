package dev.tvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Remembers each app's tile so a reload, or a cold start, doesn't open every other app's
 * resources again. Two levels: the last load's entries in memory, and one file per app (label
 * plus the rendered banner as PNG) under the cache dir. An entry is valid only while the
 * package's lastUpdateTime, the locales, the tile size and FORMAT all match.
 *
 * Background threads only; loads may overlap, so the memory map is swapped whole, files are
 * written to a temp file and renamed, and every failure just means "not cached".
 */
final class TileCache {
    private static final String TAG = "tvhome";
    // Bump when the file layout or the banner rendering changes.
    private static final int FORMAT = 1;
    private static final String SUFFIX = ".tile", TEMP = ".tmp";
    private static final int MAX_PNG = 4 << 20;

    /** What a file holds: the label, and the banner (null for a label-only entry). */
    static final class Stored {
        final String name;
        final Bitmap banner;

        Stored(String name, Bitmap banner) {
            this.name = name;
            this.banner = banner;
        }
    }

    private static final class Memory {
        final String locales;
        final Map<String, Entry> byKey;

        Memory(String locales, Map<String, Entry> byKey) {
            this.locales = locales;
            this.byKey = byKey;
        }
    }

    private final File dir;
    private final int w, h;
    private volatile Memory memory = new Memory("", Collections.emptyMap());

    TileCache(File dir, int w, int h) {
        this.dir = dir;
        this.w = w;
        this.h = h;
    }

    /** A copy of the last load's entry when the app is unchanged since, else null. */
    Entry fromMemory(String key, long updated, String locales) {
        Memory m = memory;
        if (!m.locales.equals(locales)) return null;
        Entry e = m.byKey.get(key);
        return e != null && e.updated == updated ? e.copy() : null;
    }

    /** Keeps this load's entries for the next one. */
    void remember(List<Entry> entries, String locales) {
        Map<String, Entry> byKey = new HashMap<>();
        for (Entry e : entries) if (e.updated != 0) byKey.put(e.key, e.copy());
        memory = new Memory(locales, byKey);
    }

    /** The app's file, or null when missing, stale, corrupt or unreadable. */
    Stored read(String key, long updated, String locales) {
        File f = file(key);
        if (!f.isFile()) return null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            if (in.readInt() != FORMAT || !in.readUTF().equals(key) || in.readLong() != updated
                    || !in.readUTF().equals(locales) || in.readInt() != w || in.readInt() != h) {
                return null;
            }
            String name = in.readUTF();
            int len = in.readInt();
            if (len < 0 || len > MAX_PNG) return null;
            if (len == 0) return new Stored(name, null);
            byte[] png = new byte[len];
            in.readFully(png);
            Bitmap b = BitmapFactory.decodeByteArray(png, 0, len);
            if (b == null) return null;
            if (b.getWidth() != w || b.getHeight() != h) {
                b.recycle();
                return null;
            }
            return new Stored(name, b);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "tile cache read failed for " + key, e);
            return null;
        }
    }

    /** Writes (or rewrites) the app's file. banner may be null (label only); it is not recycled. */
    void write(String key, long updated, String locales, CharSequence name, Bitmap banner) {
        File tmp = null;
        try {
            byte[] png = new byte[0];
            if (banner != null) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(32 << 10);
                if (!banner.compress(Bitmap.CompressFormat.PNG, 100, bytes)) return;
                png = bytes.toByteArray();
            }
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            tmp = File.createTempFile("tile", TEMP, dir);
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
                out.writeInt(FORMAT);
                out.writeUTF(key);
                out.writeLong(updated);
                out.writeUTF(locales);
                out.writeInt(w);
                out.writeInt(h);
                out.writeUTF(name.toString());
                out.writeInt(png.length);
                out.write(png);
            }
            if (tmp.renameTo(file(key))) tmp = null;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "tile cache write failed for " + key, e);
        } finally {
            if (tmp != null && !tmp.delete()) Log.w(TAG, "could not delete " + tmp);
        }
    }

    /** Deletes files of apps no longer installed, and temp files left by a killed process. */
    void prune(Set<String> installedKeys) {
        File[] files = dir.listFiles();
        if (files == null) return;
        Set<String> keep = new HashSet<>();
        for (String k : installedKeys) keep.add(file(k).getName());
        long old = System.currentTimeMillis() - 60_000;
        for (File f : files) {
            String n = f.getName();
            boolean stale = n.endsWith(SUFFIX) ? !keep.contains(n) : n.endsWith(TEMP) && f.lastModified() < old;
            if (stale && !f.delete()) Log.w(TAG, "could not delete " + f);
        }
    }

    /** Hashed, since keys are long and full of dots and '/'. The key inside guards collisions. */
    private File file(String key) {
        String name;
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            name = sb.toString();
        } catch (NoSuchAlgorithmException e) {
            name = Integer.toHexString(key.hashCode());
        }
        return new File(dir, name + SUFFIX);
    }
}
