package com.atlanta.maraplayer.data;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Profiller, favoriler, takip edilen takimlar, kanal eslestirmeleri ve mac
 * hatirlatmalarini tek bir JSON dosyasina yazar / dosyadan geri yukler.
 *
 * Dosya uygulamanin kendi klasorunde tutulur:
 *   Android/data/com.atlanta.maraplayer/files/backup/mara-backup.json
 * Web panelden /backup.json adresiyle indirilebilir.
 */
public final class Backup {

    public static final int VERSION = 1;
    public static final String FILE_NAME = "mara-backup.json";

    /** Geri yukleme sonucu. */
    public static class Result {
        public int profiles;
        public int favorites;
        public int teams;
        public int links;
        public int reminders;
        public String error;

        public String summary() {
            return "profiller: " + profiles + ", favoriler: " + favorites
                    + ", takimlar: " + teams + ", kanal baglari: " + links
                    + ", hatirlatmalar: " + reminders;
        }
    }

    private Backup() {
    }

    /** Yedek klasoru (yoksa olusturulur). */
    public static File dir(Context c) {
        File base = null;
        try {
            base = c.getExternalFilesDir(null);
        } catch (Exception ignored) {
        }
        if (base == null) base = c.getFilesDir();
        File d = new File(base, "backup");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File file(Context c) {
        return new File(dir(c), FILE_NAME);
    }

    /** Yedegi yazar ve dosyayi dondurur. */
    public static File write(Context c) throws Exception {
        JSONObject json = build(c);
        File f = file(c);
        File tmp = new File(f.getAbsolutePath() + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8");
        try {
            w.write(json.toString(2));
        } finally {
            w.close();
            fos.close();
        }
        if (f.exists()) f.delete();
        if (!tmp.renameTo(f)) {
            // rename basarisiz olursa dogrudan yaz
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(json.toString(2).getBytes("UTF-8"));
            } finally {
                out.close();
            }
            tmp.delete();
        }
        return f;
    }

    /** Bellekteki yedek JSON'u. */
    public static JSONObject build(Context c) throws Exception {
        Db db = Db.get();
        JSONObject root = new JSONObject();
        root.put("app", "MaraIPTV");
        root.put("version", VERSION);
        root.put("createdAt", System.currentTimeMillis());
        root.put("device", Build.MANUFACTURER + " " + Build.MODEL);
        root.put("android", Build.VERSION.RELEASE);

        JSONArray profiles = new JSONArray();
        List<Models.Profile> list = db.profiles();
        for (Models.Profile p : list) {
            JSONObject o = new JSONObject();
            o.put("id", p.id);
            o.put("name", nz(p.name));
            o.put("url", nz(p.url));
            o.put("user", nz(p.user));
            o.put("pass", nz(p.pass));
            o.put("type", nz(p.type));
            o.put("epgUrl", nz(p.epgUrl));
            o.put("lastUsed", p.lastUsed);
            profiles.put(o);
        }
        root.put("profiles", profiles);

        JSONArray favs = new JSONArray();
        for (Models.Profile p : list) {
            for (int type : new int[]{Models.LIVE, Models.MOVIE, Models.SERIES}) {
                for (Models.Item it : db.favorites(p.id, type)) {
                    JSONObject o = new JSONObject();
                    o.put("profile", p.id);
                    o.put("type", type);
                    o.put("id", nz(it.id));
                    o.put("name", nz(it.name));
                    o.put("icon", nz(it.icon));
                    o.put("categoryId", nz(it.categoryId));
                    o.put("ext", nz(it.ext));
                    o.put("epgId", nz(it.epgId));
                    o.put("archive", it.archive);
                    o.put("archiveDays", it.archiveDays);
                    o.put("num", it.num);
                    favs.put(o);
                }
            }
        }
        root.put("favorites", favs);

        root.put("favoriteTeams", new JSONArray(Prefs.favoriteTeams()));
        root.put("channelLinks", new JSONArray(Prefs.channelLinks()));
        root.put("matchReminders", new JSONArray(Prefs.matchReminders()));
        root.put("autoTeamReminders", Prefs.autoTeamReminders());
        return root;
    }

    /** Dosyadan geri yukler. */
    public static Result restore(Context c, File f) throws Exception {
        if (f == null || !f.exists()) {
            Result r = new Result();
            r.error = "Yedek dosyasi bulunamadi";
            return r;
        }
        InputStream in = new FileInputStream(f);
        String text;
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            text = new String(bos.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
        return restore(c, text);
    }

    /** JSON metninden geri yukler (birlestirir, silmez). */
    public static Result restore(Context c, String json) throws Exception {
        Result res = new Result();
        JSONObject root = new JSONObject(json);
        Db db = Db.get();

        // Eski profil id -> yeni profil id eslemesi
        java.util.Map<Long, Long> idMap = new java.util.HashMap<>();
        JSONArray profiles = root.optJSONArray("profiles");
        if (profiles != null) {
            for (int i = 0; i < profiles.length(); i++) {
                JSONObject o = profiles.optJSONObject(i);
                if (o == null) continue;
                long oldId = o.optLong("id", 0);
                Models.Profile p = findProfile(db, o.optString("url"), o.optString("user"));
                if (p == null) {
                    p = new Models.Profile();
                    p.name = o.optString("name");
                    p.url = o.optString("url");
                    p.user = o.optString("user");
                    p.pass = o.optString("pass");
                    p.type = o.optString("type", Models.Profile.TYPE_XTREAM);
                    p.epgUrl = o.optString("epgUrl", "");
                    p.id = db.saveProfile(p);
                    res.profiles++;
                } else if (!o.optString("pass").isEmpty() && !o.optString("pass").equals(p.pass)) {
                    p.pass = o.optString("pass");
                    db.saveProfile(p);
                    res.profiles++;
                }
                if (oldId > 0) idMap.put(oldId, p.id);
            }
        }

        JSONArray favs = root.optJSONArray("favorites");
        if (favs != null) {
            for (int i = 0; i < favs.length(); i++) {
                JSONObject o = favs.optJSONObject(i);
                if (o == null) continue;
                long pid = mapId(idMap, o.optLong("profile", 0));
                if (pid <= 0) continue;
                Models.Item it = new Models.Item();
                it.type = o.optInt("type", Models.LIVE);
                it.id = o.optString("id");
                if (it.id.isEmpty()) continue;
                it.name = o.optString("name");
                it.icon = o.optString("icon");
                it.categoryId = o.optString("categoryId");
                it.ext = o.optString("ext");
                it.epgId = o.optString("epgId");
                it.archive = o.optBoolean("archive");
                it.archiveDays = o.optInt("archiveDays");
                it.num = o.optInt("num");
                if (!db.isFavorite(pid, it.type, it.id)) {
                    db.toggleFavorite(pid, it);
                    res.favorites++;
                }
            }
        }

        JSONArray teams = root.optJSONArray("favoriteTeams");
        if (teams != null && teams.length() > 0) {
            Set<String> cur = Prefs.favoriteTeams();
            int before = cur.size();
            for (int i = 0; i < teams.length(); i++) {
                String t = teams.optString(i, "").trim();
                if (!t.isEmpty()) cur.add(t);
            }
            Prefs.sp().edit().putStringSet("fav_teams", cur).apply();
            res.teams = cur.size() - before;
        }

        JSONArray links = root.optJSONArray("channelLinks");
        if (links != null && links.length() > 0) {
            Set<String> cur = new HashSet<>(Prefs.channelLinks());
            int before = cur.size();
            for (int i = 0; i < links.length(); i++) {
                String l = links.optString(i, "");
                if (!l.isEmpty()) cur.add(l);
            }
            Prefs.setChannelLinks(cur);
            res.links = cur.size() - before;
        }

        JSONArray rems = root.optJSONArray("matchReminders");
        if (rems != null && rems.length() > 0) {
            Set<String> cur = new HashSet<>(Prefs.matchReminders());
            int before = cur.size();
            for (int i = 0; i < rems.length(); i++) {
                String l = rems.optString(i, "");
                if (!l.isEmpty()) cur.add(l);
            }
            Prefs.setMatchReminders(cur);
            res.reminders = cur.size() - before;
        }

        if (root.has("autoTeamReminders")) {
            Prefs.setAutoTeamReminders(root.optBoolean("autoTeamReminders", true));
        }
        return res;
    }

    private static Models.Profile findProfile(Db db, String url, String user) {
        List<Models.Profile> list = db.profiles();
        for (Models.Profile p : list) {
            if (nz(p.url).equals(nz(url)) && nz(p.user).equals(nz(user))) return p;
        }
        return null;
    }

    private static long mapId(java.util.Map<Long, Long> map, long old) {
        Long v = map.get(old);
        return v == null ? old : v;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** Panel icin: en guncel yedegin son yazilma zamani. */
    public static long lastBackupAt(Context c) {
        File f = file(c);
        return f.exists() ? f.lastModified() : 0;
    }

    /** Tum profillerin adlari (panelde gostermek icin). */
    public static List<String> profileNames() {
        List<String> out = new ArrayList<>();
        for (Models.Profile p : Db.get().profiles()) out.add(p.name);
        return out;
    }
}
