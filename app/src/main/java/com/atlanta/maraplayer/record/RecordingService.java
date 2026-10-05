package com.atlanta.maraplayer.record;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import com.atlanta.maraplayer.data.Db;
import com.atlanta.maraplayer.net.Http;
import com.atlanta.maraplayer.ui.RecordingsActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kayit servisi. MPEG-TS akisini dogrudan dosyaya yazar; HLS (.m3u8) ise segmentleri sirayla indirir.
 * Baglanti koparsa bitis zamanina kadar yeniden baglanip ayni dosyaya devam eder.
 */
public class RecordingService extends Service {

    public static final String ACTION_START = "com.atlanta.maraplayer.REC_START";
    public static final String ACTION_SCHEDULED = "com.atlanta.maraplayer.REC_SCHEDULED";
    public static final String ACTION_STOP_ALL = "com.atlanta.maraplayer.REC_STOP_ALL";

    private static final String TAG = "Recorder";
    private static final String CHANNEL = "recording";
    private static final int NOTIF_ID = 8765;
    private static final AtomicLong IDS = new AtomicLong(1);

    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mara:rec");
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, notification("Kayit hazirlaniyor..."));
        if (intent == null) {
            stopIfIdle();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP_ALL.equals(action)) {
            for (Recorder.Active a : Recorder.ACTIVE.values()) a.stop = true;
        } else if (ACTION_SCHEDULED.equals(action)) {
            long sid = intent.getLongExtra("scheduleId", -1);
            Db.Scheduled s = Db.get().schedule(sid);
            if (s != null) {
                Db.get().deleteSchedule(sid);
                long stopAt = s.stop * 1000L + 120000L; // 2 dk pay
                if (stopAt > System.currentTimeMillis()) begin(s.url, s.channel + " - " + s.title, stopAt);
            }
        } else if (ACTION_START.equals(action)) {
            begin(intent.getStringExtra("url"), intent.getStringExtra("title"), intent.getLongExtra("stopAt", 0));
        }
        stopIfIdle();
        return START_NOT_STICKY;
    }

    private void begin(final String url, String title, long stopAt) {
        if (url == null || url.isEmpty()) return;
        final Recorder.Active a = new Recorder.Active();
        a.id = IDS.getAndIncrement();
        a.title = title == null ? "Kayit" : title;
        a.startedAt = System.currentTimeMillis();
        a.stopAt = stopAt;
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
        a.file = new File(Recorder.targetDir(this), Recorder.safeName(a.title) + "_" + stamp + ".ts");
        Recorder.ACTIVE.put(a.id, a);
        if (wakeLock != null) wakeLock.acquire();
        updateNotification();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    record(url, a);
                } finally {
                    Recorder.ACTIVE.remove(a.id);
                    if (a.file.exists() && a.file.length() == 0) //noinspection ResultOfMethodCallIgnored
                        a.file.delete();
                    com.atlanta.maraplayer.util.Async.MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            updateNotification();
                            stopIfIdle();
                        }
                    });
                }
            }
        }, "rec-" + a.id).start();
    }

    private boolean done(Recorder.Active a) {
        return a.stop || (a.stopAt > 0 && System.currentTimeMillis() >= a.stopAt);
    }

    private void record(String url, Recorder.Active a) {
        OutputStream out = null;
        try {
            out = new FileOutputStream(a.file, true);
            int fails = 0;
            while (!done(a)) {
                try {
                    String path = url.toLowerCase(Locale.US);
                    int q = path.indexOf('?');
                    if (q > 0) path = path.substring(0, q);
                    if (path.endsWith(".m3u8") || path.endsWith(".m3u")) recordHls(url, a, out);
                    else recordTs(url, a, out);
                    fails = 0;
                } catch (IOException e) {
                    fails++;
                    a.status = "Yeniden baglaniyor (" + fails + ")";
                    Log.w(TAG, "kayit kesildi", e);
                    if (a.stopAt == 0 && fails > 30) break; // elle kayit: cok uzun kopuklukta birak
                }
                if (!done(a)) sleep(2000);
            }
        } catch (IOException e) {
            Log.e(TAG, "dosya acilamadi", e);
            a.status = "Hata: " + e.getMessage();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void recordTs(String url, Recorder.Active a, OutputStream out) throws IOException {
        HttpURLConnection c = Http.open(url, 15000);
        InputStream in = Http.stream(c);
        a.status = "Kaydediliyor";
        byte[] buf = new byte[64 * 1024];
        try {
            int n;
            while (!done(a) && (n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                a.bytes += n;
            }
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
            c.disconnect();
        }
    }

    /** Basit HLS kaydedici: (master ise ilk varyant) medya listesini izleyip yeni segmentleri ekler */
    private void recordHls(String url, Recorder.Active a, OutputStream out) throws IOException {
        String media = url;
        List<String> lines = readLines(media);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("#EXT-X-STREAM-INF")) {
                for (int j = i + 1; j < lines.size(); j++) {
                    String l = lines.get(j).trim();
                    if (!l.isEmpty() && !l.startsWith("#")) {
                        media = new URL(new URL(url), l).toString();
                        break;
                    }
                }
                break;
            }
        }
        Set<String> seen = new HashSet<>();
        byte[] buf = new byte[64 * 1024];
        a.status = "Kaydediliyor (HLS)";
        while (!done(a)) {
            List<String> pl = readLines(media);
            boolean ended = false;
            for (String l : pl) {
                if (done(a)) break;
                l = l.trim();
                if (l.startsWith("#EXT-X-ENDLIST")) ended = true;
                if (l.isEmpty() || l.startsWith("#")) continue;
                String seg = new URL(new URL(media), l).toString();
                if (!seen.add(seg)) continue;
                HttpURLConnection c = Http.open(seg, 15000);
                InputStream in = Http.stream(c);
                try {
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        a.bytes += n;
                    }
                } finally {
                    in.close();
                    c.disconnect();
                }
            }
            if (ended) {
                a.stop = true;
                break;
            }
            if (seen.size() > 5000) seen.clear();
            sleep(3000);
        }
    }

    private static List<String> readLines(String url) throws IOException {
        List<String> out = new ArrayList<>();
        BufferedReader br = new BufferedReader(new InputStreamReader(Http.getStream(url), "UTF-8"));
        try {
            String l;
            while ((l = br.readLine()) != null) out.add(l);
        } finally {
            br.close();
        }
        return out;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // ------------------------------------------------------------ Bildirim

    private Notification notification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Kayit", NotificationManager.IMPORTANCE_LOW));
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, RecordingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags);
        b.setContentTitle("Mara IPTV - Kayit")
                .setContentText(text)
                .setSmallIcon(com.atlanta.maraplayer.R.drawable.ic_notif)
                .setContentIntent(pi)
                .setOngoing(true);
        //noinspection deprecation
        return b.getNotification();
    }

    private void updateNotification() {
        int n = Recorder.ACTIVE.size();
        String text;
        if (n == 0) text = "Kayit yok";
        else if (n == 1) text = "Kaydediliyor: " + Recorder.ACTIVE.values().iterator().next().title;
        else text = n + " kayit devam ediyor";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && n > 0) nm.notify(NOTIF_ID, notification(text));
    }

    private void stopIfIdle() {
        if (Recorder.ACTIVE.isEmpty()) {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
            stopForeground(true);
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
