package com.atlanta.maraplayer.sport;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.atlanta.maraplayer.data.Prefs;

/**
 * Spor rehberindeki bir macin baslama saatinde IPTV kanalini otomatik acar.
 * Tek seferlik alarm; bilgiler Prefs icinde tutulur ve cihaz yeniden
 * baslatildiginda BootReceiver tarafindan geri kurulur.
 */
public final class MatchAlarm {

    private static final int REQUEST = 7711;

    public static class Info {
        public long at;
        public long profileId;
        public String key = "";
        public String title = "";
        public String channelId = "";
        public String channelName = "";
    }

    private MatchAlarm() {
    }

    public static Info get() {
        Info i = new Info();
        i.at = Prefs.matchAlarmAt();
        if (i.at <= 0) return null;
        i.profileId = Prefs.matchAlarmProfile();
        i.key = Prefs.matchAlarmKey();
        i.title = Prefs.matchAlarmTitle();
        i.channelId = Prefs.matchAlarmChannel();
        i.channelName = Prefs.matchAlarmChannelName();
        if (i.channelId == null || i.channelId.isEmpty()) return null;
        return i;
    }

    public static boolean isFor(String matchKey) {
        Info i = get();
        return i != null && matchKey != null && matchKey.equals(i.key);
    }

    public static void schedule(Context c, long at, long profileId, String matchKey,
                                String title, String channelId, String channelName) {
        cancel(c);
        long now = System.currentTimeMillis();
        if (at < now + 5000) at = now + 5000;
        Prefs.setMatchAlarm(at, profileId, matchKey, title, channelId, channelName);
        alarm(c, at);
    }

    /** Kayitli alarmi iptal eder (hem Prefs hem AlarmManager). */
    public static void cancel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pending(c));
        Prefs.clearMatchAlarm();
    }

    /** Yeniden baslatma / guncelleme sonrasi gelecekteki alarmi geri kurar. */
    public static void reschedule(Context c) {
        Info i = get();
        if (i == null) return;
        long now = System.currentTimeMillis();
        if (i.at <= now + 1000) {
            Prefs.clearMatchAlarm();
            return;
        }
        alarm(c, i.at);
    }

    private static void alarm(Context c, long at) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(c);
        if (Build.VERSION.SDK_INT >= 23) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } else if (Build.VERSION.SDK_INT >= 19) {
            am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
        } else {
            am.set(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    private static PendingIntent pending(Context c) {
        Intent i = new Intent(c, MatchAlarmReceiver.class).setAction(MatchAlarmReceiver.ACTION);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, REQUEST, i, flags);
    }
}
