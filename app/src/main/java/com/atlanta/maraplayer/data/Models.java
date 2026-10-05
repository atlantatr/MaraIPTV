package com.atlanta.maraplayer.data;

import java.util.ArrayList;
import java.util.List;

/** Tum veri modelleri. */
public final class Models {

    private Models() {
    }

    public static final int LIVE = 0;
    public static final int MOVIE = 1;
    public static final int SERIES = 2;
    public static final int EPISODE = 3;

    public static String typeKey(int type) {
        switch (type) {
            case LIVE: return "live";
            case MOVIE: return "movie";
            case SERIES: return "series";
            default: return "episode";
        }
    }

    public static class Profile {
        public long id;
        public String name = "";
        public String url = "";
        public String user = "";
        public String pass = "";
        public long lastUsed;
    }

    public static class Account {
        public String status = "";
        public long expDate;          // unix saniye, 0 = sinirsiz
        public int maxConnections;
        public int activeConnections;
        public boolean trial;
        public String message = "";
        public String timezone = "";
        public long serverTimeNow;    // unix saniye
    }

    public static class Category {
        public String id;
        public String name;
        public int type;
        /** Sanal kategoriler */
        public static final String ALL = "*";
        public static final String FAV = "#fav";
        public static final String SEARCH = "#search";
        public static final String RECENT = "#recent";

        public Category() {
        }

        public Category(String id, String name, int type) {
            this.id = id;
            this.name = name;
            this.type = type;
        }

        public boolean isVirtual() {
            return id != null && (id.equals(ALL) || id.startsWith("#"));
        }
    }

    /** Kanal, film veya dizi ogesi. */
    public static class Item {
        public int type;
        public String id = "";
        public String name = "";
        public String icon = "";
        public String categoryId = "";
        public String ext = "";        // container_extension
        public String epgId = "";
        public boolean archive;
        public int archiveDays;
        public String rating = "";
        public long added;
        public int num;
        public String plot = "";
        public String year = "";
        public String genre = "";

        public String key() {
            return Models.typeKey(type) + ":" + id;
        }
    }

    public static class Episode {
        public String id = "";
        public int season;
        public int number;
        public String title = "";
        public String ext = "mp4";
        public String image = "";
        public String plot = "";
        public String duration = "";
        public int durationSecs;
        public String rating = "";
    }

    public static class Season {
        public int number;
        public String name = "";
        public String cover = "";
        public final List<Episode> episodes = new ArrayList<>();
    }

    public static class SeriesInfo {
        public String name = "";
        public String cover = "";
        public String backdrop = "";
        public String plot = "";
        public String cast = "";
        public String director = "";
        public String genre = "";
        public String releaseDate = "";
        public String rating = "";
        public String trailer = "";
        public final List<Season> seasons = new ArrayList<>();
    }

    public static class VodInfo {
        public String name = "";
        public String image = "";
        public String backdrop = "";
        public String plot = "";
        public String cast = "";
        public String director = "";
        public String genre = "";
        public String releaseDate = "";
        public String duration = "";
        public String rating = "";
        public String trailer = "";
        public String ext = "";
        public String country = "";
    }

    public static class Epg {
        public String title = "";
        public String description = "";
        public long start;     // unix saniye
        public long stop;
        public String startRaw = ""; // "2024-01-01 10:00:00" sunucu saatinde (catch-up icin)
        public boolean hasArchive;

        public boolean isNow(long nowSec) {
            return start <= nowSec && stop > nowSec;
        }
    }

    /** Oynatma kuyrugu elemani. */
    public static class PlayItem {
        public int type;           // LIVE, MOVIE, EPISODE, -1 = catchup
        public String id = "";
        public String title = "";
        public String subtitle = "";
        public String url = "";
        public String icon = "";
        public int num;
        public String ext = "";
        public boolean archive;
        public int archiveDays;
        public String categoryId = "";
        // dizi bolumu icin
        public String seriesId = "";
        public String seriesName = "";

        public String resumeKey() {
            if (type == MOVIE) return "movie:" + id;
            if (type == EPISODE) return "episode:" + id;
            return null;
        }
    }

    public static final int CATCHUP = -1;
}
