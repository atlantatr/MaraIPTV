package com.atlanta.maraplayer.data;

import com.atlanta.maraplayer.data.Models.Account;
import com.atlanta.maraplayer.data.Models.Category;
import com.atlanta.maraplayer.data.Models.Item;
import com.atlanta.maraplayer.data.Models.PlayItem;
import com.atlanta.maraplayer.data.Models.Profile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Oturum (secili profil) ve bellek onbellegi. */
public final class Repo {

    private static Profile profile;
    private static Account account;
    private static Source api;

    private static final Map<Integer, List<Category>> categories = new HashMap<>();
    private static final Map<String, List<Item>> items = new HashMap<>();
    private static final Map<Integer, Map<String, String>> categoryNames = new HashMap<>();

    /** PlayerActivity'ye buyuk listeyi Intent ile tasimamak icin */
    public static List<PlayItem> playQueue = new ArrayList<>();

    private Repo() {
    }

    /** login() icin kullanilan kaynak nesnesi tekrar kullanilir (M3U listesi yeniden indirilmesin) */
    public static void setSession(final Profile p, Account a, Source src) {
        synchronized (Repo.class) {
            profile = p;
            account = a;
            api = src != null ? src : Sources.create(p);
            clearCache();
        }
        Prefs.clearSessionUnlocks();
        final Source s = api;
        new Thread(new Runnable() {
            @Override
            public void run() {
                XmlTv.updateIfStale(p, s);
            }
        }, "xmltv").start();
    }

    public static void setSession(Profile p, Account a) {
        setSession(p, a, null);
    }

    public static synchronized void clearCache() {
        categories.clear();
        items.clear();
        categoryNames.clear();
    }

    public static Profile profile() {
        return profile;
    }

    public static long pid() {
        return profile == null ? -1 : profile.id;
    }

    public static Account account() {
        return account;
    }

    public static Source api() {
        return api;
    }

    public static boolean hasSession() {
        return profile != null && api != null;
    }

    /** Sunucu kategorileri (sanal kategoriler haric). Arka planda cagrilmali. */
    public static List<Category> categories(int type) throws IOException {
        List<Category> c;
        synchronized (Repo.class) {
            c = categories.get(type);
        }
        if (c == null) {
            c = api.categories(type);
            Map<String, String> names = new HashMap<>();
            for (Category cat : c) names.put(cat.id, cat.name);
            synchronized (Repo.class) {
                categories.put(type, c);
                categoryNames.put(type, names);
            }
        }
        return c;
    }

    /** Web panelden gizlenen kategoriler haric */
    public static List<Category> visibleCategories(int type) throws IOException {
        List<Category> all = categories(type);
        List<Category> out = new ArrayList<>(all.size());
        long pid = pid();
        for (Category c : all) if (!Prefs.isHidden(pid, type, c.id)) out.add(c);
        return out;
    }

    public static synchronized String categoryName(int type, String id) {
        Map<String, String> m = categoryNames.get(type);
        return m == null ? null : m.get(id);
    }

    /** categoryId: gercek id veya Category.ALL. Arka planda cagrilmali. */
    public static List<Item> items(int type, String categoryId) throws IOException {
        String key = type + "|" + categoryId;
        List<Item> l;
        synchronized (Repo.class) {
            l = items.get(key);
            if (l == null && !Category.ALL.equals(categoryId)) {
                // Tum liste zaten yuklendiyse oradan filtrele
                List<Item> all = items.get(type + "|" + Category.ALL);
                if (all != null) {
                    l = new ArrayList<>();
                    for (Item it : all) if (categoryId.equals(it.categoryId)) l.add(it);
                    items.put(key, l);
                }
            }
        }
        if (l == null) {
            l = api.items(type, Category.ALL.equals(categoryId) ? null : categoryId);
            synchronized (Repo.class) {
                items.put(key, l);
            }
        }
        return l;
    }

    /** Ebeveyn kilidi olan ve gizlenen kategorileri disarida birakarak tum liste. */
    public static List<Item> allUnlocked(int type) throws IOException {
        categories(type);
        List<Item> all = items(type, Category.ALL);
        List<Item> out = new ArrayList<>(all.size());
        long pid = pid();
        for (Item it : all) {
            if (Prefs.isHidden(pid, type, it.categoryId)) continue;
            if (!Prefs.isLocked(pid, type, it.categoryId, categoryName(type, it.categoryId))) out.add(it);
        }
        return out;
    }

    public static List<Item> sorted(List<Item> src, int mode) {
        List<Item> l = new ArrayList<>(src);
        switch (mode) {
            case 1:
                Collections.sort(l, (a, b) -> a.name.compareToIgnoreCase(b.name));
                break;
            case 2:
                Collections.sort(l, (a, b) -> b.added < a.added ? -1 : (b.added == a.added ? 0 : 1)); // Long.compare API 19
                break;
            case 3:
                Collections.sort(l, (a, b) -> Double.compare(rating(b), rating(a)));
                break;
            default:
                break;
        }
        return l;
    }

    private static double rating(Item i) {
        try {
            return Double.parseDouble(i.rating);
        } catch (Exception e) {
            return 0;
        }
    }
}
