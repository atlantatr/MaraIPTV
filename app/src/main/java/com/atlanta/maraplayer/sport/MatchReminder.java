package com.atlanta.maraplayer.sport;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.atlanta.maraplayer.data.Prefs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Spor rehberindeki maclar icin hatirlatma bildirimi.
 *
 * Iki secenek vardir: mactan 10 dakika once veya mac baslarken. Her mac icin
 * tek bir hatirlatma tutulur; yeni secim eskisinin yerine gecer. Bildirim
 * uygulama kapaliyken de gelir (AlarmManager + BroadcastReceiver).
 */
public final class MatchReminder {

    /** Mac baslamadan kac dakika once hatirlatilacagi. 0 = mac saatinde. */
    public static final int LEAD_TEN = 10;
    public static final int LEAD_START = 0;

    private static final String SEP = "\u0001";

    public static class Item {
        public String key = "";
        public String title = "";
        public long at;
        public int lead;
        /** Hatirlatmayla birlikte acilabilecek IPTV kanali (varsa). */
        public String channelId = "";
        public String channelName = "";
    }

    private MatchReminder() {
    }

    /** Kayitli tum hatirlatmalar (suresi gecmis olanlar haric). */
    public static List<Item> all() {
        List<Item> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (String raw : Prefs.matchReminders()) {
            Item it = parse(raw);
            if (it == null) continue;
            if (it.at <= now) continue;
            out.add(it);
        }
        return out;
    }

    /** Bu mac icin kurulu hatirlatmanin dakikasi; yoksa -1. */
    public static int leadFor(String matchKey) {
        if (matchKey == null) return -1;
        for (Item it : all()) if (matchKey.equals(it.key)) return it.lead;
        return -1;
    }

    public static boolean isFor(String matchKey) {
        return leadFor(matchKey) >= 0;
    }

    /** Hatirlatmayi kurar (ayni macin onceki hatirlatmasi iptal edilir). */
    public static void schedule(Context c, String matchKey, String title, long start, int lead) {
        schedule(c, matchKey, title, start, lead, "", "");
    }

    /** Kanal bilgisiyle hatirlatma: pencereden dogrudan yayina gidilebilir. */
    public static void schedule(Context c, String matchKey, String title, long start, int lead,
                                String channelId, String channelName) {
        if (c == null || matchKey == null || matchKey.isEmpty()) return;
        cancel(c, matchKey);
        long at = start - lead * 60 * 1000L;
        long now = System.currentTimeMillis();
        if (at <= now + 3000) at = now + 3000;

        Set<String> set = Prefs.matchReminders();
        set.add(serialize(matchKey, title, at, lead, channelId, channelName));
        Prefs.setMatchReminders(set);
        alarm(c, matchKey, at, title, lead, channelId, channelName);
    }

    public static void cancel(Context c, String matchKey) {
        if (c == null || matchKey == null) return;
        Set<String> set = new HashSet<>();
        for (String raw : Prefs.matchReminders()) {
            Item it = parse(raw);
            if (it != null && matchKey.equals(it.key)) continue;
            set.add(raw);
        }
        Prefs.setMatchReminders(set);
        cancelAlarm(c, matchKey);
    }

    /** Bildirim gosterildikten sonra kaydi temizler. */
    public static void done(Context c, String matchKey) {
        if (matchKey == null) return;
        Set<String> set = new HashSet<>();
        for (String raw : Prefs.matchReminders()) {
            Item it = parse(raw);
            if (it != null && matchKey.equals(it.key)) continue;
            set.add(raw);
        }
        Prefs.setMatchReminders(set);
    }

    /** Yeniden baslatma / guncelleme sonrasi gelecekteki hatirlatmalari geri kurar. */
    public static void rescheduleAll(Context c) {
        Set<String> keep = new HashSet<>();
        long now = System.currentTimeMillis();
        for (String raw : Prefs.matchReminders()) {
            Item it = parse(raw);
            if (it == null || it.at <= now) continue;
            keep.add(raw);
            alarm(c, it.key, it.at, it.title, it.lead, it.channelId, it.channelName);
        }
        Prefs.setMatchReminders(keep);
    }

    private static void alarm(Context c, String matchKey, long at, String title, int lead) {
        alarm(c, matchKey, at, title, lead, "", "");
    }

    private static void alarm(Context c, String matchKey, long at, String title, int lead,
                              String channelId, String channelName) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(c, matchKey, title, lead, channelId, channelName);
        if (Build.VERSION.SDK_INT >= 23) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } else if (Build.VERSION.SDK_INT >= 19) {
            am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
        } else {
            am.set(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    private static void cancelAlarm(Context c, String matchKey) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.cancel(pending(c, matchKey, "", 0, "", ""));
    }

    private static PendingIntent pending(Context c, String matchKey, String title, int lead,
                                         String channelId, String channelName) {
        Intent i = new Intent(c, MatchReminderReceiver.class)
                .setAction(MatchReminderReceiver.ACTION)
                .putExtra(MatchReminderReceiver.EXTRA_KEY, matchKey)
                .putExtra(MatchReminderReceiver.EXTRA_TITLE, title == null ? "" : title)
                .putExtra(MatchReminderReceiver.EXTRA_LEAD, lead)
                .putExtra(MatchReminderReceiver.EXTRA_CHANNEL_ID, channelId == null ? "" : channelId)
                .putExtra(MatchReminderReceiver.EXTRA_CHANNEL_NAME, channelName == null ? "" : channelName);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, matchKey.hashCode(), i, flags);
    }

    // ------------------------------------------------------------ Kayit bicimi

    private static String serialize(String key, String title, long at, int lead) {
        return serialize(key, title, at, lead, "", "");
    }

    private static String serialize(String key, String title, long at, int lead,
                                    String channelId, String channelName) {
        return key + SEP + at + SEP + lead + SEP + (title == null ? "" : title.replace(SEP, " "))
                + SEP + (channelId == null ? "" : channelId.replace(SEP, " "))
                + SEP + (channelName == null ? "" : channelName.replace(SEP, " "));
    }

    private static Item parse(String raw) {
        if (raw == null) return null;
        String[] f = raw.split(SEP, 6);
        if (f.length < 4) return null;
        try {
            Item it = new Item();
            it.key = f[0];
            it.at = Long.parseLong(f[1]);
            it.lead = Integer.parseInt(f[2]);
            it.title = f[3];
            if (f.length > 4) it.channelId = f[4];
            if (f.length > 5) it.channelName = f[5];
            return it.key.isEmpty() ? null : it;
        } catch (Exception e) {
            return null;
        }
    }
}
