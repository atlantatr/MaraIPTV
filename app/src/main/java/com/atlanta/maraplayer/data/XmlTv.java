package com.atlanta.maraplayer.data;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.util.Log;
import android.util.Xml;

import com.atlanta.maraplayer.data.Models.Epg;
import com.atlanta.maraplayer.data.Models.Profile;
import com.atlanta.maraplayer.net.Http;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;

/**
 * Harici XMLTV EPG: indirir (gz destekli), akista ayristirir (XmlPullParser) ve SQLite'a yazar.
 * Buyuk dosyalarda bile bellek kullanimi dusuk kalir.
 */
public final class XmlTv {

    private static final String TAG = "XmlTv";
    private static volatile boolean running;

    private XmlTv() {
    }

    public static boolean isRunning() {
        return running;
    }

    public static List<Epg> shortEpg(long pid, String channel, int limit) {
        long now = System.currentTimeMillis() / 1000L - Prefs.epgOffsetSec();
        return Db.get().epgQuery(pid, channel, now, now + 2 * 86400L, Math.max(1, limit));
    }

    public static List<Epg> fullEpg(long pid, String channel) {
        long now = System.currentTimeMillis() / 1000L;
        return Db.get().epgQuery(pid, channel, now - 7 * 86400L, now + 4 * 86400L, 800);
    }

    /** Profil icin XMLTV adresi: profilde girilmis olan ya da kaynagin varsayilani */
    public static String urlFor(Profile p, Source src) {
        if (p.epgUrl != null && !p.epgUrl.trim().isEmpty()) return p.epgUrl.trim();
        return src == null ? "" : src.defaultXmltvUrl();
    }

    /** Gerekirse (24 saatten eski) arka planda gunceller. Arka plan is parcaciginda cagirin. */
    public static void updateIfStale(Profile p, Source src) {
        boolean wanted = p.isM3u() || Prefs.epgXmltvOnly() || Prefs.xmltvAuto()
                || (p.epgUrl != null && !p.epgUrl.trim().isEmpty());
        if (!wanted) return;
        long last = Prefs.xmltvLast(p.id);
        if (System.currentTimeMillis() - last < 24L * 3600 * 1000 && Db.get().epgCount(p.id) > 0) return;
        String url = urlFor(p, src);
        if (url.isEmpty()) return;
        try {
            update(p.id, url);
        } catch (Exception e) {
            Log.w(TAG, "XMLTV guncellenemedi", e);
        }
    }

    /** @return kaydedilen program sayisi */
    public static synchronized int update(long pid, String url) throws Exception {
        running = true;
        InputStream raw = null;
        SQLiteDatabase db = Db.get().getWritableDatabase();
        try {
            raw = Http.stream(Http.open(url, 30000));
            BufferedInputStream bis = new BufferedInputStream(raw, 65536);
            bis.mark(4);
            int b1 = bis.read(), b2 = bis.read();
            bis.reset();
            InputStream in = (b1 == 0x1f && b2 == 0x8b) ? new GZIPInputStream(bis, 65536) : bis;

            XmlPullParser xp = Xml.newPullParser();
            xp.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            xp.setInput(in, null);

            long now = System.currentTimeMillis() / 1000L;
            long from = now - 8 * 86400L, to = now + 8 * 86400L;
            SimpleDateFormat fz = new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US);
            SimpleDateFormat fu = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
            fu.setTimeZone(TimeZone.getTimeZone("UTC"));

            int count = 0;
            db.beginTransaction();
            try {
                db.delete("epg", "profile_id=?", new String[]{String.valueOf(pid)});
                SQLiteStatement st = db.compileStatement(
                        "INSERT INTO epg(profile_id,channel,start,stop,title,descr) VALUES (?,?,?,?,?,?)");
                String channel = null, title = null, desc = null;
                long start = 0, stop = 0;
                boolean inProg = false;
                int ev = xp.getEventType();
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        String n = xp.getName();
                        if ("programme".equals(n)) {
                            inProg = true;
                            channel = xp.getAttributeValue(null, "channel");
                            start = parse(xp.getAttributeValue(null, "start"), fz, fu);
                            stop = parse(xp.getAttributeValue(null, "stop"), fz, fu);
                            title = null;
                            desc = null;
                        } else if (inProg && "title".equals(n) && title == null) {
                            title = xp.nextText();
                        } else if (inProg && "desc".equals(n) && desc == null) {
                            desc = xp.nextText();
                        }
                    } else if (ev == XmlPullParser.END_TAG && "programme".equals(xp.getName())) {
                        inProg = false;
                        if (channel != null && start > 0 && stop > start && stop > from && start < to) {
                            st.clearBindings();
                            st.bindLong(1, pid);
                            st.bindString(2, channel);
                            st.bindLong(3, start);
                            st.bindLong(4, stop);
                            st.bindString(5, title == null ? "" : title.trim());
                            st.bindString(6, desc == null ? "" : desc.trim());
                            st.executeInsert();
                            count++;
                        }
                    }
                    ev = xp.next();
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            Prefs.setXmltvLast(pid, System.currentTimeMillis());
            return count;
        } finally {
            running = false;
            if (raw != null) {
                try {
                    raw.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static long parse(String s, SimpleDateFormat fz, SimpleDateFormat fu) {
        if (s == null) return 0;
        s = s.trim();
        try {
            if (s.length() >= 20) return fz.parse(s.substring(0, 14) + " " + s.substring(15).trim()).getTime() / 1000L;
            if (s.length() >= 14) return fu.parse(s.substring(0, 14)).getTime() / 1000L;
        } catch (Exception ignored) {
        }
        return 0;
    }
}
