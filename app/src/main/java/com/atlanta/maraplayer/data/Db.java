package com.atlanta.maraplayer.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.atlanta.maraplayer.data.Models.Item;
import com.atlanta.maraplayer.data.Models.Profile;

import java.util.ArrayList;
import java.util.List;

/** Profiller, favoriler ve "kaldigin yerden devam" kayitlari. */
public final class Db extends SQLiteOpenHelper {

    private static Db instance;

    public static void init(Context c) {
        instance = new Db(c.getApplicationContext());
    }

    public static Db get() {
        return instance;
    }

    private Db(Context c) {
        super(c, "mara.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE profiles (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, url TEXT, user TEXT, pass TEXT, last_used INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE favorites (profile_id INTEGER, type INTEGER, item_id TEXT, name TEXT, icon TEXT, category_id TEXT, ext TEXT, epg_id TEXT, archive INTEGER, archive_days INTEGER, num INTEGER, added_at INTEGER, PRIMARY KEY(profile_id, type, item_id))");
        db.execSQL("CREATE TABLE resume (profile_id INTEGER, rkey TEXT, type INTEGER, item_id TEXT, name TEXT, subtitle TEXT, icon TEXT, ext TEXT, series_id TEXT, series_name TEXT, position INTEGER, duration INTEGER, updated_at INTEGER, PRIMARY KEY(profile_id, rkey))");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
    }

    // ------------------------------------------------------------ Profiller

    public List<Profile> profiles() {
        List<Profile> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery("SELECT id,name,url,user,pass,last_used FROM profiles ORDER BY last_used DESC, id", null);
        try {
            while (c.moveToNext()) {
                Profile p = new Profile();
                p.id = c.getLong(0);
                p.name = nz(c.getString(1));
                p.url = nz(c.getString(2));
                p.user = nz(c.getString(3));
                p.pass = nz(c.getString(4));
                p.lastUsed = c.getLong(5);
                out.add(p);
            }
        } finally {
            c.close();
        }
        return out;
    }

    public Profile profile(long id) {
        for (Profile p : profiles()) if (p.id == id) return p;
        return null;
    }

    public long saveProfile(Profile p) {
        ContentValues v = new ContentValues();
        v.put("name", p.name);
        v.put("url", p.url);
        v.put("user", p.user);
        v.put("pass", p.pass);
        SQLiteDatabase db = getWritableDatabase();
        if (p.id > 0) {
            db.update("profiles", v, "id=?", new String[]{String.valueOf(p.id)});
        } else {
            p.id = db.insert("profiles", null, v);
        }
        return p.id;
    }

    public void touchProfile(long id) {
        ContentValues v = new ContentValues();
        v.put("last_used", System.currentTimeMillis());
        getWritableDatabase().update("profiles", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void deleteProfile(long id) {
        SQLiteDatabase db = getWritableDatabase();
        String[] a = {String.valueOf(id)};
        db.delete("profiles", "id=?", a);
        db.delete("favorites", "profile_id=?", a);
        db.delete("resume", "profile_id=?", a);
    }

    // ------------------------------------------------------------ Favoriler

    public boolean isFavorite(long pid, int type, String itemId) {
        Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM favorites WHERE profile_id=? AND type=? AND item_id=?",
                new String[]{String.valueOf(pid), String.valueOf(type), itemId});
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    /** @return yeni durum (true = favoride) */
    public boolean toggleFavorite(long pid, Item it) {
        if (isFavorite(pid, it.type, it.id)) {
            getWritableDatabase().delete("favorites", "profile_id=? AND type=? AND item_id=?",
                    new String[]{String.valueOf(pid), String.valueOf(it.type), it.id});
            return false;
        }
        ContentValues v = new ContentValues();
        v.put("profile_id", pid);
        v.put("type", it.type);
        v.put("item_id", it.id);
        v.put("name", it.name);
        v.put("icon", it.icon);
        v.put("category_id", it.categoryId);
        v.put("ext", it.ext);
        v.put("epg_id", it.epgId);
        v.put("archive", it.archive ? 1 : 0);
        v.put("archive_days", it.archiveDays);
        v.put("num", it.num);
        v.put("added_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("favorites", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        return true;
    }

    public List<Item> favorites(long pid, int type) {
        List<Item> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT item_id,name,icon,category_id,ext,epg_id,archive,archive_days,num FROM favorites WHERE profile_id=? AND type=? ORDER BY added_at",
                new String[]{String.valueOf(pid), String.valueOf(type)});
        try {
            while (c.moveToNext()) {
                Item it = new Item();
                it.type = type;
                it.id = nz(c.getString(0));
                it.name = nz(c.getString(1));
                it.icon = nz(c.getString(2));
                it.categoryId = nz(c.getString(3));
                it.ext = nz(c.getString(4));
                it.epgId = nz(c.getString(5));
                it.archive = c.getInt(6) == 1;
                it.archiveDays = c.getInt(7);
                it.num = c.getInt(8);
                out.add(it);
            }
        } finally {
            c.close();
        }
        return out;
    }

    // ------------------------------------------------------------ Devam et

    public static class Resume {
        public String key;
        public int type;
        public String itemId;
        public String name;
        public String subtitle;
        public String icon;
        public String ext;
        public String seriesId;
        public String seriesName;
        public long position;
        public long duration;
        public long updatedAt;

        public boolean finished() {
            return duration > 0 && position >= duration * 0.93;
        }

        public int percent() {
            return duration > 0 ? (int) Math.min(100, position * 100 / duration) : 0;
        }
    }

    public void saveResume(long pid, Models.PlayItem p, long position, long duration) {
        String key = p.resumeKey();
        if (key == null) return;
        ContentValues v = new ContentValues();
        v.put("profile_id", pid);
        v.put("rkey", key);
        v.put("type", p.type);
        v.put("item_id", p.id);
        v.put("name", p.title);
        v.put("subtitle", p.subtitle);
        v.put("icon", p.icon);
        v.put("ext", p.ext);
        v.put("series_id", p.seriesId);
        v.put("series_name", p.seriesName);
        v.put("position", position);
        v.put("duration", duration);
        v.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("resume", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public Resume resume(long pid, String key) {
        List<Resume> l = queryResume("profile_id=? AND rkey=?", new String[]{String.valueOf(pid), key}, 1);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Yarida kalan film ve bolumler (en yeni once). */
    public List<Resume> continueWatching(long pid, int limit) {
        List<Resume> all = queryResume("profile_id=? AND position > 30000", new String[]{String.valueOf(pid)}, limit * 2);
        List<Resume> out = new ArrayList<>();
        for (Resume r : all) {
            if (!r.finished()) out.add(r);
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** Bir dizinin tum bolum kayitlari (en son izlenen once). */
    public List<Resume> resumeForSeries(long pid, String seriesId) {
        return queryResume("profile_id=? AND series_id=?", new String[]{String.valueOf(pid), seriesId}, 1000);
    }

    public void deleteResume(long pid, String key) {
        getWritableDatabase().delete("resume", "profile_id=? AND rkey=?", new String[]{String.valueOf(pid), key});
    }

    private List<Resume> queryResume(String where, String[] args, int limit) {
        List<Resume> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT rkey,type,item_id,name,subtitle,icon,ext,series_id,series_name,position,duration,updated_at FROM resume WHERE "
                        + where + " ORDER BY updated_at DESC LIMIT " + limit, args);
        try {
            while (c.moveToNext()) {
                Resume r = new Resume();
                r.key = c.getString(0);
                r.type = c.getInt(1);
                r.itemId = nz(c.getString(2));
                r.name = nz(c.getString(3));
                r.subtitle = nz(c.getString(4));
                r.icon = nz(c.getString(5));
                r.ext = nz(c.getString(6));
                r.seriesId = nz(c.getString(7));
                r.seriesName = nz(c.getString(8));
                r.position = c.getLong(9);
                r.duration = c.getLong(10);
                r.updatedAt = c.getLong(11);
                out.add(r);
            }
        } finally {
            c.close();
        }
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
