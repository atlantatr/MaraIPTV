package com.atlanta.maraplayer.data;

import android.util.Base64;
import android.util.JsonReader;
import android.util.JsonToken;

import com.atlanta.maraplayer.data.Models.Account;
import com.atlanta.maraplayer.data.Models.Category;
import com.atlanta.maraplayer.data.Models.Epg;
import com.atlanta.maraplayer.data.Models.Episode;
import com.atlanta.maraplayer.data.Models.Item;
import com.atlanta.maraplayer.data.Models.Profile;
import com.atlanta.maraplayer.data.Models.Season;
import com.atlanta.maraplayer.data.Models.SeriesInfo;
import com.atlanta.maraplayer.data.Models.VodInfo;
import com.atlanta.maraplayer.net.Http;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Xtream Codes "player_api.php" istemcisi.
 * Buyuk listeler android.util.JsonReader ile akista (stream) islenir; dusuk RAM'li cihazlar icin.
 */
public class XtreamApi implements Source {

    public interface ObjReader<T> {
        T read(JsonReader r) throws IOException;
    }

    private final String base;
    private final String user;
    private final String pass;
    private final long pid;
    /** stream_id -> epg_channel_id (XMLTV eslestirmesi icin) */
    private final Map<String, String> epgIds = new HashMap<>();

    public XtreamApi(Profile p) {
        this.base = normalizeUrl(p.url);
        this.user = p.user.trim();
        this.pass = p.pass.trim();
        this.pid = p.id;
    }

    // ---------------------------------------------------------------- URL'ler

    /** "host:port", "http://host:port/", ".../player_api.php", ".../get.php?..." -> "http://host:port" */
    public static String normalizeUrl(String url) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) return u;
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        int q = u.indexOf('?');
        if (q > 0) u = u.substring(0, q);
        for (String tail : new String[]{"player_api.php", "get.php", "xmltv.php", "panel_api.php"}) {
            if (u.endsWith(tail)) u = u.substring(0, u.length() - tail.length());
        }
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private String api(String action, String extra) {
        StringBuilder sb = new StringBuilder(base).append("/player_api.php?username=")
                .append(enc(user)).append("&password=").append(enc(pass));
        if (action != null) sb.append("&action=").append(action);
        if (extra != null) sb.append(extra);
        return sb.toString();
    }

    /** "kullanici/sifre/" — ozel karakterler (#, ?, /, bosluk) URL yolunu bozmasin diye kodlanir */
    private String creds() {
        return android.net.Uri.encode(user) + "/" + android.net.Uri.encode(pass) + "/";
    }

    public String liveUrl(String streamId, String format) {
        return base + "/live/" + creds() +streamId + "." + format;
    }

    public String movieUrl(String streamId, String ext) {
        return base + "/movie/" + creds() +streamId + "." + (empty(ext) ? "mp4" : ext);
    }

    public String episodeUrl(String episodeId, String ext) {
        return base + "/series/" + creds() +episodeId + "." + (empty(ext) ? "mp4" : ext);
    }

    /**
     * Catch-up (timeshift) adresi. startRaw sunucu saatindedir: "2024-01-31 20:00:00"
     * -> /timeshift/user/pass/{dakika}/2024-01-31:20-00/{id}.ts
     */
    public String catchupUrl(String streamId, String startRaw, int durationMin, String format) {
        String s = startRaw.trim();
        String t;
        if (s.length() >= 16) {
            t = s.substring(0, 10) + ":" + s.substring(11, 13) + "-" + s.substring(14, 16);
        } else {
            t = s;
        }
        return base + "/timeshift/" + creds() +durationMin + "/" + t + "/" + streamId + "." + format;
    }

    // ---------------------------------------------------------------- Istekler

    public Account login() throws IOException {
        return readUrl(api(null, null), new ObjReader<Account>() {
            @Override
            public Account read(JsonReader r) throws IOException {
                if (r.peek() != JsonToken.BEGIN_OBJECT) throw new IOException("Gecersiz sunucu yaniti");
                Account a = new Account();
                boolean auth = false;
                r.beginObject();
                while (r.hasNext()) {
                    String n = r.nextName();
                    if ("user_info".equals(n) && r.peek() == JsonToken.BEGIN_OBJECT) {
                        r.beginObject();
                        while (r.hasNext()) {
                            String k = r.nextName();
                            String v = str(r);
                            switch (k) {
                                case "auth": auth = "1".equals(v) || "true".equals(v); break;
                                case "status": a.status = v; break;
                                case "exp_date": a.expDate = toLong(v); break;
                                case "max_connections": a.maxConnections = toInt(v); break;
                                case "active_cons": a.activeConnections = toInt(v); break;
                                case "is_trial": a.trial = "1".equals(v); break;
                                case "message": a.message = v; break;
                                default: break;
                            }
                        }
                        r.endObject();
                    } else if ("server_info".equals(n) && r.peek() == JsonToken.BEGIN_OBJECT) {
                        r.beginObject();
                        while (r.hasNext()) {
                            String k = r.nextName();
                            String v = str(r);
                            if ("timezone".equals(k)) a.timezone = v;
                            else if ("timestamp_now".equals(k)) a.serverTimeNow = toLong(v);
                        }
                        r.endObject();
                    } else {
                        r.skipValue();
                    }
                }
                r.endObject();
                if (!auth) throw new IOException("Kullanici adi veya sifre hatali");
                return a;
            }
        });
    }

    public List<Category> categories(final int type) throws IOException {
        String action = type == Models.LIVE ? "get_live_categories"
                : type == Models.MOVIE ? "get_vod_categories" : "get_series_categories";
        return readList(api(action, null), new ObjReader<Category>() {
            @Override
            public Category read(JsonReader r) throws IOException {
                Category c = new Category();
                c.type = type;
                r.beginObject();
                while (r.hasNext()) {
                    String k = r.nextName();
                    if ("category_id".equals(k)) c.id = str(r);
                    else if ("category_name".equals(k)) c.name = str(r);
                    else r.skipValue();
                }
                r.endObject();
                return c.id == null ? null : c;
            }
        });
    }

    /** categoryId == null ise tum liste. */
    public List<Item> items(final int type, String categoryId) throws IOException {
        String action = type == Models.LIVE ? "get_live_streams"
                : type == Models.MOVIE ? "get_vod_streams" : "get_series";
        String extra = categoryId == null ? null : "&category_id=" + enc(categoryId);
        return readList(api(action, extra), new ObjReader<Item>() {
            @Override
            public Item read(JsonReader r) throws IOException {
                Item it = new Item();
                it.type = type;
                r.beginObject();
                while (r.hasNext()) {
                    String k = r.nextName();
                    switch (k) {
                        case "stream_id":
                        case "series_id": it.id = str(r); break;
                        case "name": it.name = str(r).trim(); break;
                        case "stream_icon":
                        case "cover": it.icon = str(r); break;
                        case "category_id": it.categoryId = str(r); break;
                        case "container_extension": it.ext = str(r); break;
                        case "epg_channel_id": it.epgId = str(r); break;
                        case "tv_archive": it.archive = "1".equals(str(r)); break;
                        case "tv_archive_duration": it.archiveDays = toInt(str(r)); break;
                        case "rating": it.rating = str(r); break;
                        case "added":
                        case "last_modified": it.added = Math.max(it.added, toLong(str(r))); break;
                        case "num": it.num = toInt(str(r)); break;
                        case "plot": it.plot = str(r); break;
                        case "genre": it.genre = str(r); break;
                        case "releaseDate":
                        case "release_date":
                        case "year": {
                            String y = str(r);
                            if (y.length() >= 4 && it.year.isEmpty()) it.year = y.substring(0, 4);
                            break;
                        }
                        default: r.skipValue();
                    }
                }
                r.endObject();
                if (it.id.isEmpty()) return null;
                if (type == Models.LIVE && !it.epgId.isEmpty()) {
                    synchronized (epgIds) {
                        epgIds.put(it.id, it.epgId);
                    }
                }
                return it;
            }
        });
    }

    public VodInfo vodInfo(String vodId) throws IOException {
        return readUrl(api("get_vod_info", "&vod_id=" + enc(vodId)), new ObjReader<VodInfo>() {
            @Override
            public VodInfo read(JsonReader r) throws IOException {
                VodInfo v = new VodInfo();
                if (r.peek() != JsonToken.BEGIN_OBJECT) {
                    r.skipValue();
                    return v;
                }
                r.beginObject();
                while (r.hasNext()) {
                    String n = r.nextName();
                    if ("info".equals(n) && r.peek() == JsonToken.BEGIN_OBJECT) {
                        r.beginObject();
                        while (r.hasNext()) {
                            String k = r.nextName();
                            switch (k) {
                                case "name": v.name = str(r); break;
                                case "movie_image":
                                case "cover_big": { String s = str(r); if (!s.isEmpty()) v.image = s; break; }
                                case "backdrop_path": v.backdrop = firstString(r); break;
                                case "plot":
                                case "description": { String s = str(r); if (!s.isEmpty()) v.plot = s; break; }
                                case "cast":
                                case "actors": { String s = str(r); if (!s.isEmpty()) v.cast = s; break; }
                                case "director": v.director = str(r); break;
                                case "genre": v.genre = str(r); break;
                                case "releasedate":
                                case "release_date": { String s = str(r); if (!s.isEmpty()) v.releaseDate = s; break; }
                                case "duration": v.duration = str(r); break;
                                case "rating": v.rating = str(r); break;
                                case "youtube_trailer": v.trailer = str(r); break;
                                case "country": v.country = str(r); break;
                                default: r.skipValue();
                            }
                        }
                        r.endObject();
                    } else if ("movie_data".equals(n) && r.peek() == JsonToken.BEGIN_OBJECT) {
                        r.beginObject();
                        while (r.hasNext()) {
                            String k = r.nextName();
                            if ("container_extension".equals(k)) v.ext = str(r);
                            else if ("name".equals(k)) { String s = str(r); if (v.name.isEmpty()) v.name = s; }
                            else r.skipValue();
                        }
                        r.endObject();
                    } else {
                        r.skipValue();
                    }
                }
                r.endObject();
                return v;
            }
        });
    }

    public SeriesInfo seriesInfo(String seriesId) throws IOException {
        return readUrl(api("get_series_info", "&series_id=" + enc(seriesId)), new ObjReader<SeriesInfo>() {
            @Override
            public SeriesInfo read(JsonReader r) throws IOException {
                SeriesInfo s = new SeriesInfo();
                Map<Integer, Season> seasons = new HashMap<>();
                if (r.peek() != JsonToken.BEGIN_OBJECT) {
                    r.skipValue();
                    return s;
                }
                r.beginObject();
                while (r.hasNext()) {
                    String n = r.nextName();
                    if ("info".equals(n) && r.peek() == JsonToken.BEGIN_OBJECT) {
                        r.beginObject();
                        while (r.hasNext()) {
                            String k = r.nextName();
                            switch (k) {
                                case "name": s.name = str(r); break;
                                case "cover": s.cover = str(r); break;
                                case "backdrop_path": s.backdrop = firstString(r); break;
                                case "plot": s.plot = str(r); break;
                                case "cast": s.cast = str(r); break;
                                case "director": s.director = str(r); break;
                                case "genre": s.genre = str(r); break;
                                case "releaseDate":
                                case "release_date": s.releaseDate = str(r); break;
                                case "rating": s.rating = str(r); break;
                                case "youtube_trailer": s.trailer = str(r); break;
                                default: r.skipValue();
                            }
                        }
                        r.endObject();
                    } else if ("seasons".equals(n) && r.peek() == JsonToken.BEGIN_ARRAY) {
                        r.beginArray();
                        while (r.hasNext()) {
                            if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue; }
                            Season se = new Season();
                            r.beginObject();
                            while (r.hasNext()) {
                                String k = r.nextName();
                                if ("season_number".equals(k)) se.number = toInt(str(r));
                                else if ("name".equals(k)) se.name = str(r);
                                else if ("cover".equals(k) || "cover_big".equals(k)) { String c = str(r); if (!c.isEmpty()) se.cover = c; }
                                else r.skipValue();
                            }
                            r.endObject();
                            Season old = seasons.get(se.number);
                            if (old == null) seasons.put(se.number, se);
                            else { old.name = se.name; old.cover = se.cover; }
                        }
                        r.endArray();
                    } else if ("episodes".equals(n)) {
                        if (r.peek() == JsonToken.BEGIN_OBJECT) {
                            // {"1":[...], "2":[...]}
                            r.beginObject();
                            while (r.hasNext()) {
                                int sn = toInt(r.nextName());
                                readEpisodeArray(r, seasons, sn);
                            }
                            r.endObject();
                        } else if (r.peek() == JsonToken.BEGIN_ARRAY) {
                            // [[...],[...]] bazi panellerde
                            r.beginArray();
                            while (r.hasNext()) readEpisodeArray(r, seasons, -1);
                            r.endArray();
                        } else {
                            r.skipValue();
                        }
                    } else {
                        r.skipValue();
                    }
                }
                r.endObject();
                List<Season> list = new ArrayList<>();
                for (Season se : seasons.values()) {
                    if (!se.episodes.isEmpty()) {
                        Collections.sort(se.episodes, new Comparator<Episode>() {
                            @Override
                            public int compare(Episode a, Episode b) {
                                return a.number - b.number;
                            }
                        });
                        if (se.name.isEmpty()) se.name = "Sezon " + se.number;
                        list.add(se);
                    }
                }
                Collections.sort(list, new Comparator<Season>() {
                    @Override
                    public int compare(Season a, Season b) {
                        return a.number - b.number;
                    }
                });
                s.seasons.addAll(list);
                return s;
            }
        });
    }

    private void readEpisodeArray(JsonReader r, Map<Integer, Season> seasons, int seasonNum) throws IOException {
        if (r.peek() != JsonToken.BEGIN_ARRAY) {
            r.skipValue();
            return;
        }
        r.beginArray();
        while (r.hasNext()) {
            if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue; }
            Episode e = new Episode();
            e.season = seasonNum;
            r.beginObject();
            while (r.hasNext()) {
                String k = r.nextName();
                switch (k) {
                    case "id": e.id = str(r); break;
                    case "episode_num": e.number = toInt(str(r)); break;
                    case "title": e.title = str(r); break;
                    case "container_extension": e.ext = str(r); break;
                    case "season": { int s = toInt(str(r)); if (s > 0 || e.season < 0) e.season = s; break; }
                    case "info":
                        if (r.peek() == JsonToken.BEGIN_OBJECT) {
                            r.beginObject();
                            while (r.hasNext()) {
                                String ik = r.nextName();
                                switch (ik) {
                                    case "movie_image": e.image = str(r); break;
                                    case "plot": e.plot = str(r); break;
                                    case "duration": e.duration = str(r); break;
                                    case "duration_secs": e.durationSecs = toInt(str(r)); break;
                                    case "rating": e.rating = str(r); break;
                                    default: r.skipValue();
                                }
                            }
                            r.endObject();
                        } else {
                            r.skipValue();
                        }
                        break;
                    default: r.skipValue();
                }
            }
            r.endObject();
            if (e.id.isEmpty()) continue;
            if (e.season < 0) e.season = 1;
            Season se = seasons.get(e.season);
            if (se == null) {
                se = new Season();
                se.number = e.season;
                seasons.put(e.season, se);
            }
            se.episodes.add(e);
        }
        r.endArray();
    }

    /** Kisa EPG (simdi/sonra). Ayara gore Xtream API veya XMLTV veritabanindan. */
    @Override
    public List<Epg> shortEpg(String streamId, String epgId, int limit) throws IOException {
        if (empty(epgId)) epgId = epgIdOf(streamId);
        if (Prefs.epgXmltvOnly()) return XmlTv.shortEpg(pid, epgId, limit);
        List<Epg> l;
        try {
            l = epg(api("get_short_epg", "&stream_id=" + enc(streamId) + "&limit=" + limit));
        } catch (IOException e) {
            l = new ArrayList<>();
        }
        if (l.isEmpty()) l = XmlTv.shortEpg(pid, epgId, limit);
        return l;
    }

    /** Tum EPG (arsiv bilgisi dahil). */
    @Override
    public List<Epg> fullEpg(String streamId, String epgId) throws IOException {
        if (empty(epgId)) epgId = epgIdOf(streamId);
        if (Prefs.epgXmltvOnly()) return XmlTv.fullEpg(pid, epgId);
        List<Epg> l;
        try {
            l = epg(api("get_simple_data_table", "&stream_id=" + enc(streamId)));
        } catch (IOException e) {
            l = new ArrayList<>();
        }
        if (l.isEmpty()) l = XmlTv.fullEpg(pid, epgId);
        return l;
    }

    private String epgIdOf(String streamId) {
        synchronized (epgIds) {
            String s = epgIds.get(streamId);
            return s == null ? "" : s;
        }
    }

    @Override
    public String defaultXmltvUrl() {
        return base + "/xmltv.php?username=" + enc(user) + "&password=" + enc(pass);
    }

    private List<Epg> epg(String url) throws IOException {
        List<Epg> list = readUrl(url, new ObjReader<List<Epg>>() {
            @Override
            public List<Epg> read(JsonReader r) throws IOException {
                final List<Epg> out = new ArrayList<>();
                if (r.peek() != JsonToken.BEGIN_OBJECT) {
                    r.skipValue();
                    return out;
                }
                r.beginObject();
                while (r.hasNext()) {
                    String n = r.nextName();
                    if ("epg_listings".equals(n) && r.peek() == JsonToken.BEGIN_ARRAY) {
                        r.beginArray();
                        while (r.hasNext()) {
                            if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue; }
                            Epg e = new Epg();
                            String endRaw = "";
                            r.beginObject();
                            while (r.hasNext()) {
                                String k = r.nextName();
                                switch (k) {
                                    case "title": e.title = b64(str(r)); break;
                                    case "description": e.description = b64(str(r)); break;
                                    case "start": e.startRaw = str(r); break;
                                    case "end": endRaw = str(r); break;
                                    case "start_timestamp": e.start = toLong(str(r)); break;
                                    case "stop_timestamp": e.stop = toLong(str(r)); break;
                                    case "has_archive": e.hasArchive = "1".equals(str(r)); break;
                                    default: r.skipValue();
                                }
                            }
                            r.endObject();
                            if (e.start == 0) e.start = parseDate(e.startRaw);
                            if (e.stop == 0) e.stop = parseDate(endRaw);
                            if (e.start > 0 && e.stop > e.start) out.add(e);
                        }
                        r.endArray();
                    } else {
                        r.skipValue();
                    }
                }
                r.endObject();
                return out;
            }
        });
        Collections.sort(list, new Comparator<Epg>() {
            @Override
            public int compare(Epg a, Epg b) {
                return a.start < b.start ? -1 : (a.start == b.start ? 0 : 1);
            }
        });
        return list;
    }

    // ---------------------------------------------------------------- JSON yardimcilari

    private <T> T readUrl(String url, ObjReader<T> reader) throws IOException {
        InputStream in = Http.getStream(url);
        JsonReader r = new JsonReader(new InputStreamReader(in, "UTF-8"));
        r.setLenient(true);
        try {
            return reader.read(r);
        } catch (IllegalStateException e) {
            throw new IOException("Sunucu yaniti okunamadi", e);
        } finally {
            try {
                r.close();
            } catch (Exception ignored) {
            }
        }
    }

    private <T> List<T> readList(String url, final ObjReader<T> itemReader) throws IOException {
        return readUrl(url, new ObjReader<List<T>>() {
            @Override
            public List<T> read(JsonReader r) throws IOException {
                List<T> out = new ArrayList<>();
                JsonToken t = r.peek();
                if (t == JsonToken.BEGIN_ARRAY) {
                    r.beginArray();
                    while (r.hasNext()) {
                        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue; }
                        T item = itemReader.read(r);
                        if (item != null) out.add(item);
                    }
                    r.endArray();
                } else if (t == JsonToken.BEGIN_OBJECT) {
                    // Bazi paneller {"0":{...},"1":{...}} doner
                    r.beginObject();
                    while (r.hasNext()) {
                        r.nextName();
                        if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue; }
                        T item = itemReader.read(r);
                        if (item != null) out.add(item);
                    }
                    r.endObject();
                } else {
                    r.skipValue();
                }
                return out;
            }
        });
    }

    /** Her tipte degeri String olarak okur; nesne/dizi ise atlar. */
    static String str(JsonReader r) throws IOException {
        JsonToken t = r.peek();
        switch (t) {
            case STRING:
            case NUMBER:
                return r.nextString();
            case BOOLEAN:
                return r.nextBoolean() ? "1" : "0";
            case NULL:
                r.nextNull();
                return "";
            default:
                r.skipValue();
                return "";
        }
    }

    /** String ya da String dizisinden ilkini okur (backdrop_path icin). */
    static String firstString(JsonReader r) throws IOException {
        if (r.peek() == JsonToken.BEGIN_ARRAY) {
            String first = "";
            r.beginArray();
            while (r.hasNext()) {
                String s = str(r);
                if (first.isEmpty()) first = s;
            }
            r.endArray();
            return first;
        }
        return str(r);
    }

    static String b64(String s) {
        if (s == null || s.isEmpty()) return "";
        try {
            byte[] b = Base64.decode(s, Base64.DEFAULT);
            String out = new String(b, "UTF-8");
            // Base64 olmayan duz metin olabilir: cozulmus metinde kontrol karakteri varsa ham metni dondur
            for (int i = 0; i < out.length(); i++) {
                char c = out.charAt(i);
                if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') return s;
                if (c == 0xFFFD) return s;
            }
            return out;
        } catch (Exception e) {
            return s;
        }
    }

    private static long parseDate(String s) {
        if (s == null || s.length() < 19) return 0;
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            return f.parse(s.substring(0, 19)).getTime() / 1000L;
        } catch (Exception e) {
            return 0;
        }
    }

    public static int toInt(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return (int) Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    public static long toLong(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return (long) Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    static boolean empty(String s) {
        return s == null || s.trim().isEmpty();
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }
}
