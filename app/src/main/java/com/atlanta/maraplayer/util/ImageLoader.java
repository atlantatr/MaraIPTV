package com.atlanta.maraplayer.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.atlanta.maraplayer.net.Http;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Hafif resim yukleyici: bellek (LRU) + disk onbellegi, boyut kuculterek decode. */
public final class ImageLoader {

    private static ImageLoader instance;

    private final LruCache<String, Bitmap> mem;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File dir;
    private final Set<String> failed = new HashSet<>();

    public static void init(Context c) {
        instance = new ImageLoader(c.getApplicationContext());
    }

    public static ImageLoader get() {
        return instance;
    }

    private ImageLoader(Context c) {
        int max = (int) (Runtime.getRuntime().maxMemory() / 1024);
        mem = new LruCache<String, Bitmap>(max / 8) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getRowBytes() * value.getHeight() / 1024;
            }
        };
        dir = new File(c.getCacheDir(), "img");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        pool.execute(new Runnable() {
            @Override
            public void run() {
                trimDisk(60L * 1024 * 1024);
            }
        });
    }

    public void clearMemory() {
        mem.evictAll();
    }

    public void clearDisk() {
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
        synchronized (failed) {
            failed.clear();
        }
    }

    /**
     * @param reqW/reqH hedef boyut (px); decode bu boyuta yakin kucultulur
     */
    public void load(final ImageView iv, final String url, int placeholder, final int reqW, final int reqH) {
        iv.setTag(url);
        if (url == null || url.trim().isEmpty() || !url.startsWith("http")) {
            iv.setImageResource(placeholder);
            return;
        }
        final String key = url + "#" + reqW;
        Bitmap b = mem.get(key);
        if (b != null) {
            iv.setImageBitmap(b);
            return;
        }
        iv.setImageResource(placeholder);
        synchronized (failed) {
            if (failed.contains(url)) return;
        }
        pool.execute(new Runnable() {
            @Override
            public void run() {
                if (!url.equals(iv.getTag())) return; // satir geri donusturulmus
                final Bitmap bmp = fetch(url, reqW, reqH);
                if (bmp == null) {
                    synchronized (failed) {
                        failed.add(url);
                    }
                    return;
                }
                mem.put(key, bmp);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (url.equals(iv.getTag())) iv.setImageBitmap(bmp);
                    }
                });
            }
        });
    }

    private Bitmap fetch(String url, int reqW, int reqH) {
        File f = new File(dir, md5(url));
        try {
            if (!f.exists() || f.length() == 0) {
                HttpURLConnection c = Http.open(url, 8000);
                InputStream in;
                try {
                    in = Http.stream(c);
                } catch (IOException e) {
                    c.disconnect();
                    throw e;
                }
                File tmp = new File(dir, f.getName() + ".tmp");
                FileOutputStream out = new FileOutputStream(tmp);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    long total = 0;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        total += n;
                        if (total > 4 * 1024 * 1024) throw new IOException("resim cok buyuk");
                    }
                } finally {
                    out.close();
                    in.close();
                    c.disconnect();
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(f);
            } else {
                //noinspection ResultOfMethodCallIgnored
                f.setLastModified(System.currentTimeMillis());
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            if (o.outWidth <= 0) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                return null;
            }
            int sample = 1;
            while (o.outWidth / (sample * 2) >= reqW && o.outHeight / (sample * 2) >= reqH) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            o2.inPreferredConfig = Bitmap.Config.ARGB_8888; // seffaf logolar icin
            return BitmapFactory.decodeFile(f.getPath(), o2);
        } catch (Throwable t) {
            return null;
        }
    }

    private void trimDisk(long maxBytes) {
        try {
            File[] files = dir.listFiles();
            if (files == null) return;
            long total = 0;
            // tarihleri bir kez oku: siralama sirasinda degisirse sort hata verir
            final Map<File, Long> lm = new HashMap<>();
            for (File f : files) {
                total += f.length();
                lm.put(f, f.lastModified());
            }
            if (total <= maxBytes) return;
            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    long x = lm.get(a), y = lm.get(b);
                    return x < y ? -1 : (x == y ? 0 : 1);
                }
            });
            for (File f : files) {
                if (total <= maxBytes * 0.7) break;
                total -= f.length();
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}
