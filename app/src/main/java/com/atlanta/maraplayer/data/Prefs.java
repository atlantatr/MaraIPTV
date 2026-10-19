package com.atlanta.maraplayer.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Uygulama ayarlari + ebeveyn kilidi. SettingsActivity ayni anahtarlari kullanir. */
public final class Prefs {

    private static SharedPreferences sp;

    /** Session boyunca PIN ile acilmis kategoriler */
    private static final Set<String> sessionUnlocked = new HashSet<>();

    private static final Pattern ADULT = Pattern.compile(
            "(adult|xxx|porn|\\+18|18\\+|erotic|erotik|yeti[sş]kin|for adults|\\bsex)", Pattern.CASE_INSENSITIVE);

    private Prefs() {
    }

    public static void init(Context c) {
        sp = PreferenceManager.getDefaultSharedPreferences(c);
    }

    public static SharedPreferences sp() {
        return sp;
    }

    // ------------------------------------------------------------ Oynatici

    public static boolean externalPlayer() {
        return "external".equals(sp.getString("engine", "exo"));
    }

    public static String liveFormat() {
        return sp.getString("live_format", "ts");
    }

    public static String buffer() {
        return sp.getString("buffer", "normal");
    }

    public static int aspect() {
        try {
            return Integer.parseInt(sp.getString("aspect", "0"));
        } catch (Exception e) {
            return 0;
        }
    }

    public static boolean preview() {
        return sp.getBoolean("preview", true);
    }

    public static boolean autoNext() {
        return sp.getBoolean("auto_next", true);
    }

    /** Uygulama dili: system, tr, en, de, ru, es, it */
    public static String language() {
        return sp.getString("lang", "tr");
    }

    /** Android 7+ : HOME tusunda canli yayini resim icinde resim olarak surdur */
    public static boolean pipOnHome() {
        return sp.getBoolean("pip_home", true);
    }

    public static boolean autoLogin() {
        return sp.getBoolean("auto_login", true);
    }

    public static boolean trustAllCerts() {
        return sp.getBoolean("trust_all", false);
    }

    public static String userAgent() {
        String ua = sp.getString("user_agent", "");
        return ua == null || ua.trim().isEmpty() ? "MaraIPTV/1.0 (Linux; Android)" : ua.trim();
    }

    /** EPG saat duzeltmesi (saniye) */
    public static long epgOffsetSec() {
        try {
            return Long.parseLong(sp.getString("epg_offset", "0")) * 3600L;
        } catch (Exception e) {
            return 0;
        }
    }

    /** EPG kaynagi: "auto" (Xtream, yoksa XMLTV) veya "xmltv" (sadece XMLTV) */
    public static boolean epgXmltvOnly() {
        return "xmltv".equals(sp.getString("epg_source", "auto"));
    }

    /** Xtream profillerde xmltv.php'yi de gunluk indir */
    public static boolean xmltvAuto() {
        return sp.getBoolean("xmltv_auto", false);
    }

    public static long xmltvLast(long pid) {
        return sp.getLong("xmltv_last_" + pid, 0);
    }

    public static void setXmltvLast(long pid, long t) {
        sp.edit().putLong("xmltv_last_" + pid, t).apply();
    }

    // ------------------------------------------------------------ Web panel / kayit

    public static boolean webPanel() {
        return sp.getBoolean("web_panel", true);
    }

    public static final int WEB_PORT = 8765;

    /** "auto" = en cok bos alani olan depolama (USB dahil), "internal" = dahili */
    public static String recStorage() {
        return sp.getString("rec_storage", "auto");
    }

    public static long lastProfile() {
        return sp.getLong("last_profile", -1);
    }

    public static void setLastProfile(long id) {
        sp.edit().putLong("last_profile", id).apply();
    }

    public static String lastChannel(long pid) {
        return sp.getString("last_channel_" + pid, null);
    }

    public static void setLastChannel(long pid, String id) {
        sp.edit().putString("last_channel_" + pid, id).apply();
    }

    public static String lastLiveCategory(long pid) {
        return sp.getString("last_live_cat_" + pid, null);
    }

    public static void setLastLiveCategory(long pid, String id) {
        sp.edit().putString("last_live_cat_" + pid, id).apply();
    }

    /** Son izlenen kanaller (yeni -> eski, JSON benzeri basit list) */
    public static String recentChannels(long pid) {
        return sp.getString("recent_channels_" + pid, "");
    }

    public static void addRecentChannel(long pid, String id) {
        if (id == null || id.isEmpty()) return;
        String[] cur = sp.getString("recent_channels_" + pid, "").split(",");
        StringBuilder sb = new StringBuilder();
        for (String c : cur) {
            if (c.equals(id) || c.isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(c);
        }
        sp.edit().putString("recent_channels_" + pid, sb.length() > 0 ? id + "," + sb : id).apply();
    }

    /** EPG hatirlatmalari: key = "epg_rem_" + pid + "_" + streamId + "_" + startSec */
    public static String reminderKey(long pid, String streamId, long startSec) {
        return "epg_rem_" + pid + "_" + streamId + "_" + startSec;
    }

    public static boolean hasReminder(long pid, String streamId, long startSec) {
        return sp.getBoolean(reminderKey(pid, streamId, startSec), false);
    }

    public static void setReminder(long pid, String streamId, long startSec, boolean on) {
        sp.edit().putBoolean(reminderKey(pid, streamId, startSec), on).apply();
    }

    /** Hatirlatilmak istenen programlar: [(streamId, startSec, stopSec)] - boot'ta kontrol */
    public static java.util.List<long[]> reminders(long pid) {
        java.util.List<long[]> out = new java.util.ArrayList<long[]>();
        String raw = sp.getString("epg_rem_list_" + pid, "");
        if (raw.isEmpty()) return out;
        for (String t : raw.split(",")) {
            String[] p = t.split("_");
            if (p.length == 3) {
                try {
                    out.add(new long[]{0, Long.parseLong(p[1]), Long.parseLong(p[2])});
                } catch (NumberFormatException e) { }
            }
        }
        return out;
    }

    public static void addReminderList(long pid, String streamId, long start, long stop) {
        String raw = sp.getString("epg_rem_list_" + pid, "");
        StringBuilder sb = new StringBuilder(raw);
        if (sb.length() > 0) sb.append(';');
        sb.append(streamId).append('_').append(start).append('_').append(stop);
        sp.edit().putString("epg_rem_list_" + pid, sb.toString()).apply();
    }

    public static void removeReminderList(long pid, String streamId, long start) {
        String raw = sp.getString("epg_rem_list_" + pid, "");
        if (raw.isEmpty()) return;
        String key = streamId + "_" + start + "_";
        StringBuilder sb = new StringBuilder();
        for (String t : raw.split(";")) {
            if (t.startsWith(key)) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(t);
        }
        sp.edit().putString("epg_rem_list_" + pid, sb.toString()).apply();
    }

    // ------------------------------------------------------------ Ebeveyn kilidi

    public static boolean parentalEnabled() {
        return sp.getBoolean("parental", true);
    }

    public static boolean adultAutoLock() {
        return sp.getBoolean("adult_autolock", true);
    }

    public static void setParental(boolean on) {
        sp.edit().putBoolean("parental", on).apply();
    }

    public static void setAdultAutoLock(boolean on) {
        sp.edit().putBoolean("adult_autolock", on).apply();
    }

    // ------------------------------------------------------------ Gizli kategoriler (web panel)

    public static boolean isHidden(long pid, int type, String catId) {
        return catId != null && set("hidden_" + pid).contains(catKey(type, catId));
    }

    public static void setHidden(long pid, int type, String catId, boolean hidden) {
        Set<String> s = set("hidden_" + pid);
        if (hidden) s.add(catKey(type, catId));
        else s.remove(catKey(type, catId));
        sp.edit().putStringSet("hidden_" + pid, s).apply();
    }

    public static String pin() {
        return sp.getString("pin", "0000");
    }

    public static void setPin(String pin) {
        sp.edit().putString("pin", pin).apply();
    }

    public static boolean checkPin(String p) {
        return p != null && p.equals(pin());
    }

    private static String catKey(int type, String catId) {
        return type + ":" + catId;
    }

    public static boolean looksAdult(String name) {
        return name != null && ADULT.matcher(name.toLowerCase(Locale.US)).find();
    }

    /** Kategori kilitli mi (oturumda acilmis olanlar haric) */
    public static boolean isLocked(long pid, int type, String catId, String catName) {
        if (!parentalEnabled() || catId == null) return false;
        String k = catKey(type, catId);
        if (sessionUnlocked.contains(pid + "/" + k)) return false;
        return isLockedPermanent(pid, type, catId, catName);
    }

    /** Oturum kilidi dikkate alinmadan kalici kilit durumu */
    public static boolean isLockedPermanent(long pid, int type, String catId, String catName) {
        if (catId == null) return false;
        String k = catKey(type, catId);
        if (set("locked_" + pid).contains(k)) return true;
        if (adultAutoLock() && looksAdult(catName) && !set("unlocked_" + pid).contains(k)) return true;
        return false;
    }

    public static void unlockForSession(long pid, int type, String catId) {
        sessionUnlocked.add(pid + "/" + catKey(type, catId));
    }

    public static void clearSessionUnlocks() {
        sessionUnlocked.clear();
    }

    /** Kalici kilidi ac/kapat */
    public static void setLocked(long pid, int type, String catId, String catName, boolean locked) {
        String k = catKey(type, catId);
        Set<String> lockedSet = set("locked_" + pid);
        Set<String> unlockedSet = set("unlocked_" + pid);
        if (locked) {
            unlockedSet.remove(k);
            if (!(adultAutoLock() && looksAdult(catName))) lockedSet.add(k);
        } else {
            lockedSet.remove(k);
            if (adultAutoLock() && looksAdult(catName)) unlockedSet.add(k);
        }
        sp.edit().putStringSet("locked_" + pid, lockedSet).putStringSet("unlocked_" + pid, unlockedSet).apply();
    }

    public static void clearAllLocks() {
        SharedPreferences.Editor e = sp.edit();
        for (String k : sp.getAll().keySet()) {
            if (k.startsWith("locked_") || k.startsWith("unlocked_")) e.remove(k);
        }
        e.apply();
    }

    private static Set<String> set(String key) {
        // getStringSet'in dondurdugu set degistirilmemeli -> kopya
        return new HashSet<>(sp.getStringSet(key, new HashSet<String>()));
    }
}
