package com.atlanta.maraplayer.record;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;

import com.atlanta.maraplayer.data.Db;
import com.atlanta.maraplayer.data.Prefs;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Canli yayin kaydi: aninda kayit, zamanli kayit, kayit dosyalari. */
public final class Recorder {

    private Recorder() {
    }

    /** Devam eden kayit bilgisi */
    public static class Active {
        public long id;
        public String title;
        public File file;
        public long startedAt;
        public long stopAt; // ms, 0 = elle durdurulana kadar
        public volatile long bytes;
        public volatile boolean stop;
        public volatile String status = "Baslatiliyor";
    }

    static final Map<Long, Active> ACTIVE = new ConcurrentHashMap<>();

    public static List<Active> active() {
        return new ArrayList<>(ACTIVE.values());
    }

    public static boolean isRecording(String title) {
        for (Active a : ACTIVE.values()) if (a.title.equals(title)) return true;
        return false;
    }

    // ------------------------------------------------------------ Baslat / durdur

    /** @param minutes 0 = elle durdurulana kadar */
    public static void start(Context c, String url, String title, int minutes) {
        Intent i = new Intent(c, RecordingService.class).setAction(RecordingService.ACTION_START)
                .putExtra("url", url).putExtra("title", title)
                .putExtra("stopAt", minutes > 0 ? System.currentTimeMillis() + minutes * 60000L : 0L);
        startService(c, i);
    }

    public static void stop(Context c, long id) {
        Active a = ACTIVE.get(id);
        if (a != null) a.stop = true;
    }

    static void startService(Context c, Intent i) {
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    // ------------------------------------------------------------ Zamanli kayit

    public static void schedule(Context c, Db.Scheduled s) {
        Db.get().addSchedule(s);
        setAlarm(c, s);
    }

    public static void cancel(Context c, long id) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pending(c, id));
        Db.get().deleteSchedule(id);
    }

    /** Acilista / yeniden baslatmada alarmlari kur, gecmistekileri temizle */
    public static void rescheduleAll(Context c) {
        long now = System.currentTimeMillis() / 1000L;
        for (Db.Scheduled s : Db.get().schedules()) {
            if (s.stop <= now) Db.get().deleteSchedule(s.id);
            else setAlarm(c, s);
        }
    }

    private static PendingIntent pending(Context c, long id) {
        Intent i = new Intent(c, RecordingService.class).setAction(RecordingService.ACTION_SCHEDULED)
                .putExtra("scheduleId", id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        if (Build.VERSION.SDK_INT >= 26) return PendingIntent.getForegroundService(c, (int) id, i, flags);
        return PendingIntent.getService(c, (int) id, i, flags);
    }

    private static void setAlarm(Context c, Db.Scheduled s) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long at = Math.max(System.currentTimeMillis() + 2000, s.start * 1000L - 60000L); // 1 dk erken
        PendingIntent pi = pending(c, s.id);
        if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        else if (Build.VERSION.SDK_INT >= 19) am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
        else am.set(AlarmManager.RTC_WAKEUP, at, pi);
    }

    // ------------------------------------------------------------ Dosyalar

    /** Kayit klasorleri (dahili + USB/SD) */
    public static List<File> dirs(Context c) {
        List<File> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 19) {
            File[] fs = c.getExternalFilesDirs(Environment.DIRECTORY_MOVIES);
            if (fs != null) for (File f : fs) if (f != null) out.add(f);
        } else {
            File f = c.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            if (f != null) out.add(f);
        }
        if (out.isEmpty()) out.add(new File(c.getFilesDir(), "kayit"));
        return out;
    }

    /** Yeni kayit icin klasor: ayara gore dahili ya da en cok bos alani olan */
    public static File targetDir(Context c) {
        List<File> ds = dirs(c);
        File best = ds.get(0);
        if (!"internal".equals(Prefs.recStorage())) {
            for (File d : ds) {
                //noinspection ResultOfMethodCallIgnored
                d.mkdirs();
                if (d.getUsableSpace() > best.getUsableSpace()) best = d;
            }
        }
        //noinspection ResultOfMethodCallIgnored
        best.mkdirs();
        return best;
    }

    public static List<File> recordings(Context c) {
        List<File> out = new ArrayList<>();
        for (File d : dirs(c)) {
            File[] fs = d.listFiles();
            if (fs != null) out.addAll(Arrays.asList(fs));
        }
        List<File> files = new ArrayList<>();
        for (File f : out) if (f.isFile() && f.getName().endsWith(".ts")) files.add(f);
        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                long x = a.lastModified(), y = b.lastModified();
                return x > y ? -1 : (x == y ? 0 : 1);
            }
        });
        return files;
    }

    public static String safeName(String s) {
        String n = s == null ? "kayit" : s.replaceAll("[\\\\/:*?\"<>|\\n\\r\\t]", "_").trim();
        if (n.length() > 60) n = n.substring(0, 60);
        return n.isEmpty() ? "kayit" : n;
    }

    public static String size(long bytes) {
        if (bytes > 1024L * 1024 * 1024) return String.format(java.util.Locale.US, "%.2f GB", bytes / 1073741824.0);
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }
}
