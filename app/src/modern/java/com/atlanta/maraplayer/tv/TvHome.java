package com.atlanta.maraplayer.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.tvprovider.media.tv.TvContractCompat;
import androidx.tvprovider.media.tv.WatchNextProgram;

import com.atlanta.maraplayer.R;
import com.atlanta.maraplayer.data.Db;
import com.atlanta.maraplayer.data.Models;
import com.atlanta.maraplayer.data.Prefs;
import com.atlanta.maraplayer.data.Repo;
import com.atlanta.maraplayer.net.Http;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * [Android 5.0 / 7.0 surumleri] Android TV ana ekran entegrasyonu.
 * Android 8.0+ : "Izlemeye devam" (Watch Next) satirina yarim kalan film/bolumleri ekler.
 * Android 5.0 - 7.1 : Android TV "Oneriler" satirina kart olarak ekler.
 * Karta basilinca mara://play baglantisi ile dogrudan kaldigi yerden oynatir.
 */
public final class TvHome {

    private static final String TAG = "TvHome";
    private static final int MAX = 8;

    private TvHome() {
    }

    /** Arka plan is parcaciginda cagirin */
    public static synchronized void update(Context c) {
        try {
            if (!c.getPackageManager().hasSystemFeature("android.software.leanback")) return;
            long pid = Repo.pid();
            if (pid < 0) pid = Prefs.lastProfile();
            if (pid < 0) return;
            List<Db.Resume> items = Db.get().continueWatching(pid, MAX);
            if (Build.VERSION.SDK_INT >= 26) watchNext(c, pid, items);
            else recommendations(c, pid, items);
        } catch (Throwable t) {
            Log.w(TAG, "ana ekran guncellenemedi", t);
        }
    }

    public static Uri deepLink(long pid, Db.Resume r) {
        return Uri.parse("mara://play").buildUpon()
                .appendQueryParameter("pid", String.valueOf(pid))
                .appendQueryParameter("type", String.valueOf(r.type))
                .appendQueryParameter("id", r.itemId)
                .appendQueryParameter("ext", r.ext)
                .appendQueryParameter("pos", String.valueOf(r.position))
                .appendQueryParameter("name", r.name)
                .appendQueryParameter("sub", r.subtitle)
                .appendQueryParameter("icon", r.icon)
                .appendQueryParameter("sid", r.seriesId)
                .appendQueryParameter("sname", r.seriesName)
                .build();
    }

    private static String title(Db.Resume r) {
        return r.type == Models.EPISODE && r.seriesName != null && !r.seriesName.isEmpty() ? r.seriesName : r.name;
    }

    // ------------------------------------------------------------ Android 8+

    private static void watchNext(Context c, long pid, List<Db.Resume> items) {
        ContentResolver cr = c.getContentResolver();
        SharedPreferences sp = Prefs.sp();
        Set<String> old = new HashSet<>(sp.getStringSet("wn_ids", new HashSet<String>()));
        for (String id : old) {
            try {
                cr.delete(TvContractCompat.buildWatchNextProgramUri(Long.parseLong(id)), null, null);
            } catch (Exception ignored) {
            }
        }
        Set<String> ids = new HashSet<>();
        for (Db.Resume r : items) {
            WatchNextProgram.Builder b = new WatchNextProgram.Builder();
            b.setType(r.type == Models.EPISODE ? TvContractCompat.WatchNextPrograms.TYPE_TV_EPISODE
                    : TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
                    .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                    .setLastEngagementTimeUtcMillis(r.updatedAt)
                    .setLastPlaybackPositionMillis((int) r.position)
                    .setDurationMillis((int) r.duration)
                    .setIntentUri(deepLink(pid, r))
                    .setInternalProviderId(r.key)
                    .setTitle(title(r))
                    .setDescription(r.type == Models.EPISODE ? r.subtitle + "  " + r.name : "Film");
            if (r.icon != null && r.icon.startsWith("http")) b.setPosterArtUri(Uri.parse(r.icon));
            Uri u = cr.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, b.build().toContentValues());
            if (u != null) ids.add(String.valueOf(ContentUris.parseId(u)));
        }
        sp.edit().putStringSet("wn_ids", ids).apply();
    }

    // ------------------------------------------------------------ Android 5.0 - 7.1

    private static void recommendations(Context c, long pid, List<Db.Resume> items) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        int n = Math.min(5, items.size());
        for (int i = 0; i < n; i++) {
            Db.Resume r = items.get(i);
            Intent open = new Intent(Intent.ACTION_VIEW, deepLink(pid, r)).setPackage(c.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(c, 2000 + i, open, flags);
            @SuppressWarnings("deprecation")
            Notification.Builder b = new Notification.Builder(c)
                    .setContentTitle(title(r))
                    .setContentText(r.type == Models.EPISODE ? r.subtitle + "  " + r.name : "Izlemeye devam et")
                    .setSmallIcon(R.drawable.ic_notif)
                    .setLocalOnly(true)
                    .setOngoing(true)
                    .setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_RECOMMENDATION)
                    .setProgress(100, r.percent(), false)
                    .setContentIntent(pi);
            Bitmap poster = poster(r.icon);
            if (poster != null) b.setLargeIcon(poster);
            nm.notify("mara_rec", 1000 + i, b.build());
        }
        for (int i = n; i < 5; i++) nm.cancel("mara_rec", 1000 + i);
    }

    private static Bitmap poster(String url) {
        if (url == null || !url.startsWith("http")) return null;
        try {
            InputStream in = Http.getStream(url);
            try {
                byte[] data = Http.readAll(in);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                int s = 1;
                while (o.outWidth / (s * 2) >= 300) s *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = s;
                return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unused")
    private static void ensureChannel(NotificationManager nm) {
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel("tv") == null) {
            nm.createNotificationChannel(new NotificationChannel("tv", "Oneriler", NotificationManager.IMPORTANCE_LOW));
        }
    }
}
