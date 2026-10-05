package com.atlanta.maraplayer.data;

import com.atlanta.maraplayer.data.Models.Account;
import com.atlanta.maraplayer.data.Models.Category;
import com.atlanta.maraplayer.data.Models.Epg;
import com.atlanta.maraplayer.data.Models.Item;
import com.atlanta.maraplayer.data.Models.Profile;
import com.atlanta.maraplayer.data.Models.SeriesInfo;
import com.atlanta.maraplayer.data.Models.VodInfo;
import com.atlanta.maraplayer.net.Http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M3U / M3U8 calma listesi kaynagi.
 * #EXTINF satirindaki tvg-id, tvg-logo, tvg-chno, group-title okunur.
 * Adresinde /movie/ gecenler Film, /series/ gecenler Dizi(bolum) olarak, digerleri Canli TV olarak siniflanir.
 */
public class M3uSource implements Source {

    private static final Pattern ATTR = Pattern.compile("([a-zA-Z0-9_-]+)=\"([^\"]*)\"");

    private final Profile profile;
    private final List<Item> live = new ArrayList<>();
    private final List<Item> movies = new ArrayList<>();
    private final Map<String, String> urls = new HashMap<>();
    private final Map<Integer, Map<String, String>> groups = new HashMap<>(); // tip -> (kategoriId -> ad)
    private String urlTvg = "";
    private boolean loaded;

    public M3uSource(Profile p) {
        this.profile = p;
    }

    private static String fullUrl(String u) {
        u = u == null ? "" : u.trim();
        if (!u.isEmpty() && !u.startsWith("http://") && !u.startsWith("https://") && !u.startsWith("file:")) u = "http://" + u;
        return u;
    }

    private synchronized void ensureLoaded() throws IOException {
        if (loaded) return;
        InputStream in = Http.getStream(fullUrl(profile.url));
        BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"), 65536);
        try {
            groups.put(Models.LIVE, new LinkedHashMap<String, String>());
            groups.put(Models.MOVIE, new LinkedHashMap<String, String>());
            String line;
            String name = null, logo = "", tvgId = "", group = "";
            int chno = 0;
            int n = 0;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (first) {
                    first = false;
                    if (line.startsWith("﻿")) line = line.substring(1);
                    if (line.startsWith("#EXTM3U")) {
                        Map<String, String> a = attrs(line);
                        String t = a.get("url-tvg");
                        if (t == null) t = a.get("x-tvg-url");
                        if (t != null) urlTvg = t.split(",")[0].trim();
                        continue;
                    }
                }
                if (line.isEmpty()) continue;
                if (line.startsWith("#EXTINF")) {
                    Map<String, String> a = attrs(line);
                    int comma = line.lastIndexOf(',');
                    name = comma >= 0 ? line.substring(comma + 1).trim() : "";
                    if (name.isEmpty() && a.get("tvg-name") != null) name = a.get("tvg-name");
                    logo = nz(a.get("tvg-logo"));
                    tvgId = nz(a.get("tvg-id"));
                    group = nz(a.get("group-title"));
                    chno = XtreamApi.toInt(a.get("tvg-chno"));
                } else if (line.startsWith("#EXTGRP:")) {
                    group = line.substring(8).trim();
                } else if (line.startsWith("#")) {
                    // diger etiketler (EXTVLCOPT vb.) yok sayilir
                } else if (name != null) {
                    n++;
                    String lower = line.toLowerCase();
                    int type = (lower.contains("/movie/") || lower.contains("/series/")) ? Models.MOVIE : Models.LIVE;
                    if (group.isEmpty()) group = type == Models.LIVE ? "Diger" : "Filmler";
                    Item it = new Item();
                    it.type = type;
                    it.id = String.valueOf(n);
                    it.name = name.isEmpty() ? "Kanal " + n : name;
                    it.icon = logo;
                    it.epgId = tvgId;
                    it.num = chno > 0 ? chno : (type == Models.LIVE ? live.size() + 1 : 0);
                    String catId = type + "_" + group;
                    it.categoryId = catId;
                    int dot = lower.lastIndexOf('.');
                    if (dot > lower.lastIndexOf('/')) it.ext = lower.substring(dot + 1);
                    groups.get(type).put(catId, group);
                    urls.put(it.id, line);
                    if (type == Models.LIVE) live.add(it);
                    else movies.add(it);
                    name = null;
                    group = "";
                    chno = 0;
                }
            }
        } finally {
            br.close();
        }
        loaded = true;
    }

    private static Map<String, String> attrs(String line) {
        Map<String, String> m = new HashMap<>();
        Matcher mt = ATTR.matcher(line);
        while (mt.find()) m.put(mt.group(1).toLowerCase(), mt.group(2));
        return m;
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    @Override
    public Account login() throws IOException {
        ensureLoaded();
        if (live.isEmpty() && movies.isEmpty()) throw new IOException("Calma listesi bos veya gecersiz");
        Account a = new Account();
        a.status = "M3U (" + live.size() + " kanal, " + movies.size() + " video)";
        return a;
    }

    @Override
    public List<Category> categories(int type) throws IOException {
        ensureLoaded();
        List<Category> out = new ArrayList<>();
        Map<String, String> g = groups.get(type);
        if (g == null) return out;
        for (Map.Entry<String, String> e : g.entrySet()) out.add(new Category(e.getKey(), e.getValue(), type));
        return out;
    }

    @Override
    public List<Item> items(int type, String categoryId) throws IOException {
        ensureLoaded();
        List<Item> src = type == Models.LIVE ? live : type == Models.MOVIE ? movies : new ArrayList<Item>();
        if (categoryId == null) return new ArrayList<>(src);
        List<Item> out = new ArrayList<>();
        for (Item it : src) if (categoryId.equals(it.categoryId)) out.add(it);
        return out;
    }

    @Override
    public VodInfo vodInfo(String vodId) {
        VodInfo v = new VodInfo();
        for (Item it : movies) {
            if (it.id.equals(vodId)) {
                v.name = it.name;
                v.image = it.icon;
                v.ext = it.ext;
                break;
            }
        }
        return v;
    }

    @Override
    public SeriesInfo seriesInfo(String seriesId) {
        return new SeriesInfo();
    }

    @Override
    public List<Epg> shortEpg(String streamId, String epgId, int limit) {
        return XmlTv.shortEpg(profile.id, epgId, limit);
    }

    @Override
    public List<Epg> fullEpg(String streamId, String epgId) {
        return XmlTv.fullEpg(profile.id, epgId);
    }

    private String url(String id) {
        String u = urls.get(id);
        return u == null ? "" : u;
    }

    @Override
    public String liveUrl(String streamId, String format) {
        return url(streamId);
    }

    @Override
    public String movieUrl(String streamId, String ext) {
        return url(streamId);
    }

    @Override
    public String episodeUrl(String episodeId, String ext) {
        return url(episodeId);
    }

    @Override
    public String catchupUrl(String streamId, String startRaw, int durationMin, String format) {
        return url(streamId);
    }

    @Override
    public String defaultXmltvUrl() {
        return urlTvg;
    }
}
