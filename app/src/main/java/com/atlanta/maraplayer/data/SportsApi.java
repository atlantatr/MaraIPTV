package com.atlanta.maraplayer.data;

import android.content.SharedPreferences;

import com.atlanta.maraplayer.net.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Spor rehberi: yaklasan futbol maclari.
 *
 * Kaynak: ESPN'in ucretsiz, anahtar istemeyen JSON arayuzu
 * (site.api.espn.com/apis/site/v2/sports/soccer/{lig}/scoreboard?dates=yyyyMMdd).
 * Lig basina ve gun basina ayri sorgu yapilir; sonuç 3 saat onbelleklenir.
 */
public final class SportsApi {

    public static class Match {
        public String league = "";
        public String leagueLogo = "";
        public String home = "";
        public String away = "";
        public String homeLogo = "";
        public String awayLogo = "";
        /** Baslama zamani (epoch ms) */
        public long start;
        public String status = "";
        public String score = "";

        public boolean live() {
            return "in".equals(status);
        }
    }

    private static final String BASE = "https://site.api.espn.com/apis/site/v2/sports/soccer/";
    private static final long CACHE_MS = 3 * 60 * 60 * 1000L;
    private static final String KEY_AT = "sports_cache_at";
    private static final String KEY_DATA = "sports_cache";

    /** {ESPN lig kimligi, ekranda gorunen ad, lig logosu} */
    private static final String[][] LEAGUES = {
            {"3946", "Süper Lig", "https://a.espncdn.com/i/leaguelogos/soccer/500/18.png"},
            {"775", "Şampiyonlar Ligi", "https://a.espncdn.com/i/leaguelogos/soccer/500/2.png"},
            {"776", "Avrupa Ligi", "https://a.espncdn.com/i/leaguelogos/soccer/500/2310.png"},
            {"700", "Premier League", "https://a.espncdn.com/i/leaguelogos/soccer/500/23.png"},
            {"740", "La Liga", "https://a.espncdn.com/i/leaguelogos/soccer/500/15.png"},
            {"730", "Serie A", "https://a.espncdn.com/i/leaguelogos/soccer/500/12.png"},
            {"720", "Bundesliga", "https://a.espncdn.com/i/leaguelogos/soccer/500/10.png"},
            {"710", "Ligue 1", "https://a.espncdn.com/i/leaguelogos/soccer/500/9.png"},
    };

    private static final Map<String, String> LEAGUE_NAMES = new java.util.HashMap<>();
    private static final Map<String, String> LEAGUE_LOGOS = new java.util.HashMap<>();

    static {
        for (String[] l : LEAGUES) {
            LEAGUE_NAMES.put(l[0], l[1]);
            LEAGUE_LOGOS.put(l[1], l[2]);
        }
    }

    private static List<Match> cache;
    private static long cacheAt;

    private SportsApi() {
    }

    // ------------------------------------------------------------ Onbellek

    /** Bellekteki onbellek (bayatsa null). */
    public static synchronized List<Match> cached() {
        if (cache == null) loadCache();
        if (cache == null) return null;
        if (System.currentTimeMillis() - cacheAt > CACHE_MS) return null;
        return cache;
    }

    /**
     * Takip edilen takimlarin bugunku maci (onbellekten, ag istegi yapmaz).
     * Oynaticida kose bildirimi icin kullanilir; canli mac once gelir.
     */
    public static Match favoriteMatchToday(java.util.Set<String> teams) {
        if (teams == null || teams.isEmpty()) return null;
        List<Match> all = cached();
        if (all == null || all.isEmpty()) return null;

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long dayStart = cal.getTimeInMillis();
        cal.add(Calendar.DAY_OF_MONTH, 1);
        long dayEnd = cal.getTimeInMillis();
        long now = System.currentTimeMillis();

        Match best = null;
        for (Match m : all) {
            if (!teams.contains(m.home) && !teams.contains(m.away)) continue;
            if (m.start < dayStart || m.start >= dayEnd) continue;
            if (!m.live() && m.start < now - 3 * 60 * 60 * 1000L) continue; // bitmis mac
            if (best == null) {
                best = m;
                continue;
            }
            if (m.live() && !best.live()) best = m;
            else if (m.live() == best.live() && m.start < best.start) best = m;
        }
        return best;
    }

    private static void loadCache() {
        SharedPreferences sp = Prefs.sp();
        if (sp == null) return;
        String raw = sp.getString(KEY_DATA, "");
        if (raw == null || raw.isEmpty()) return;
        List<Match> out = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String[] f = line.split("\\|", -1);
            if (f.length < 7) continue;
            try {
                Match m = new Match();
                m.start = Long.parseLong(f[0]);
                m.league = f[1];
                m.home = f[2];
                m.away = f[3];
                m.homeLogo = f[4];
                m.awayLogo = f[5];
                m.status = f[6];
                m.score = f.length > 7 ? f[7] : "";
                m.leagueLogo = f.length > 8 ? f[8] : "";
                if (m.leagueLogo.isEmpty()) {
                    String lg = LEAGUE_LOGOS.get(m.league);
                    if (lg != null) m.leagueLogo = lg;
                }
                out.add(m);
            } catch (Exception ignored) {
            }
        }
        if (!out.isEmpty()) {
            cache = out;
            cacheAt = sp.getLong(KEY_AT, 0);
        }
    }

    private static synchronized void saveCache(List<Match> list) {
        cache = list;
        cacheAt = System.currentTimeMillis();
        SharedPreferences sp = Prefs.sp();
        if (sp == null) return;
        StringBuilder sb = new StringBuilder();
        for (Match m : list) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(m.start).append('|').append(clean(m.league)).append('|')
                    .append(clean(m.home)).append('|').append(clean(m.away)).append('|')
                    .append(clean(m.homeLogo)).append('|').append(clean(m.awayLogo)).append('|')
                    .append(clean(m.status)).append('|').append(clean(m.score)).append('|')
                    .append(clean(m.leagueLogo));
        }
        sp.edit().putString(KEY_DATA, sb.toString()).putLong(KEY_AT, cacheAt).apply();
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace('|', ' ').replace('\n', ' ');
    }

    // ------------------------------------------------------------ Ag

    /** Yaklasan maclari getirir (gun sayisi kadar ileri). */
    public static List<Match> fetch(int days) throws IOException {
        final List<Callable<List<Match>>> jobs = new ArrayList<>();

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        SimpleDateFormat df = new SimpleDateFormat("yyyyMMdd", Locale.US);

        for (int d = 0; d < Math.max(1, days); d++) {
            final String date = df.format(cal.getTime());
            jobs.add(new Callable<List<Match>>() {
                @Override
                public List<Match> call() {
                    return dayMatches(date);
                }
            });
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }

        ExecutorService ex = Executors.newFixedThreadPool(4);
        List<Match> all = new ArrayList<>();
        IOException last = null;
        int ok = 0;
        try {
            List<Future<List<Match>>> futures = new ArrayList<>();
            for (Callable<List<Match>> job : jobs) futures.add(ex.submit(job));
            for (Future<List<Match>> f : futures) {
                try {
                    List<Match> part = f.get();
                    if (part != null) {
                        all.addAll(part);
                        ok++;
                    }
                } catch (Exception e) {
                    last = new IOException(e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
        } finally {
            ex.shutdownNow();
        }
        if (ok == 0) throw last != null ? last : new IOException("Spor verisi alınamadı");

        // Ayni mac birden fazla sorguda gelmesin
        Map<String, Match> unique = new LinkedHashMap<>();
        for (Match m : all) {
            String key = m.start + "|" + m.home + "|" + m.away;
            if (!unique.containsKey(key)) unique.put(key, m);
        }
        List<Match> out = new ArrayList<>(unique.values());
        Collections.sort(out, new Comparator<Match>() {
            @Override
            public int compare(Match a, Match b) {
                return a.start < b.start ? -1 : (a.start == b.start ? 0 : 1);
            }
        });
        saveCache(out);
        return out;
    }

    /**
     * Tek istekle gunun tum futbol maclari; yalnizca listedeki ligler alinir.
     * Boylece 8 lig icin 8 ayri istek yerine gunde 1 istek yapilir.
     */
    private static List<Match> dayMatches(String date) {
        List<Match> out = new ArrayList<>();
        try {
            String json = get("https://site.api.espn.com/apis/site/v2/sports/soccer/all/scoreboard?dates=" + date);
            JSONObject root = new JSONObject(json);
            JSONArray events = root.optJSONArray("events");
            if (events == null) return out;
            long now = System.currentTimeMillis();
            for (int i = 0; i < events.length(); i++) {
                JSONObject e = events.optJSONObject(i);
                if (e == null) continue;
                String leagueId = leagueId(e.optString("uid", ""));
                String leagueName = LEAGUE_NAMES.get(leagueId);
                if (leagueName == null) continue;

                Match m = new Match();
                m.league = leagueName;
                m.leagueLogo = LEAGUE_LOGOS.containsKey(leagueName) ? LEAGUE_LOGOS.get(leagueName) : "";
                m.start = parseIso(e.optString("date", ""));
                if (m.start <= 0) continue;
                m.status = state(e);
                if (!"pre".equals(m.status) && !"in".equals(m.status)) continue;
                if (m.start < now - 3 * 60 * 60 * 1000L) continue;

                JSONArray comps = e.optJSONArray("competitions");
                if (comps == null || comps.length() == 0) continue;
                JSONObject comp = comps.optJSONObject(0);
                JSONArray teams = comp == null ? null : comp.optJSONArray("competitors");
                if (teams == null) continue;
                for (int t = 0; t < teams.length(); t++) {
                    JSONObject c = teams.optJSONObject(t);
                    if (c == null) continue;
                    JSONObject team = c.optJSONObject("team");
                    String teamName = team == null ? "" : team.optString("displayName", "");
                    String logo = team == null ? "" : team.optString("logo", "");
                    if ("home".equals(c.optString("homeAway"))) {
                        m.home = teamName;
                        m.homeLogo = logo;
                    } else {
                        m.away = teamName;
                        m.awayLogo = logo;
                    }
                    String sc = c.optString("score", "");
                    if (!sc.isEmpty()) m.score = m.score.isEmpty() ? sc : m.score + "-" + sc;
                }
                if (m.home.isEmpty() && m.away.isEmpty()) continue;
                out.add(m);
            }
        } catch (Exception e) {
            android.util.Log.d("MaraSports", "gun alinamadi " + date + ": " + e);
        }
        return out;
    }

    /** "s:600~l:700~e:123" -> "700" */
    private static String leagueId(String uid) {
        int a = uid.indexOf("~l:");
        if (a < 0) return "";
        int b = uid.indexOf('~', a + 3);
        return b < 0 ? uid.substring(a + 3) : uid.substring(a + 3, b);
    }

    /** ESPN JSON'u kisa zaman asimiyla indirir (TV kutusunda hizli pes etsin). */
    private static String get(String url) throws IOException {
        HttpURLConnection c = Http.open(url, 8000);
        InputStream in = null;
        try {
            in = Http.stream(c);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(16384);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
            c.disconnect();
        }
    }

    private static String state(JSONObject e) {        JSONObject st = e.optJSONObject("status");
        JSONObject type = st == null ? null : st.optJSONObject("type");
        return type == null ? "" : type.optString("state", "");
    }

    private static long parseIso(String s) {
        if (s == null || s.length() < 16) return 0;
        String[] patterns = {"yyyy-MM-dd'T'HH:mm'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"};
        for (String p : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = f.parse(s);
                if (d != null) return d.getTime();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }
}
