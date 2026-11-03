package com.atlanta.maraplayer.data;

import android.content.SharedPreferences;

import com.atlanta.maraplayer.data.Models.Item;
import com.atlanta.maraplayer.data.SportsApi.Match;
import com.atlanta.maraplayer.net.Http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maclarin hangi yayincida oldugunu LiveOnSat gunluk listesinden okur.
 *
 * Eslesme mantigi FootOnSat ile aynidir: yayinci adlari oldugu gibi gosterilir,
 * IPTV kanali yalnizca adi yayinci adiyla BIREBIR ayniysa onerilir. Boylece
 * "TV2 Play Danmark" gibi bir yayinci yanlislikla "TR: TV2" kanalina baglanmaz.
 * Tahmin yok; emin olunamayan durumda yalnizca yayinci adi yazilir.
 *
 * Kaynak: https://www.liveonsat.com/2day.php (ucretsiz HTML liste).
 * Sonuc 3 saat onbelleklenir; ag istegi yalnizca Spor Rehberi acildiginda
 * arka planda bir kez yapilir.
 */
public final class BroadcastApi {

    /** LiveOnSat'teki tek bir mac satiri. */
    public static class Entry {
        public long start;
        public String league = "";
        public String home = "";
        public String away = "";
        public final List<String> channels = new ArrayList<>();

        public String title() {
            return (home.isEmpty() ? "" : home + " - ") + away;
        }
    }

    /** Bir yayinci adinin IPTV kanalinda birebir karsiligi. */
    public static class Candidate {
        public String broadcast = "";
        public String channel = "";
        public String channelId = "";
        /** Kullanici elle eslestirdi mi (otomatik birebir eslesme degil). */
        public boolean manual;

        public Candidate(String broadcast, String channel, String channelId) {
            this.broadcast = broadcast;
            this.channel = channel;
            this.channelId = channelId;
        }
    }

    /** Yayinci adi ile IPTV kanali arasinda elle kurulan kalici bag. */
    public static class Link {
        public String broadcast = "";
        public String channelId = "";
        public String channelName = "";
    }

    private static final String LINK_SEP = "\u0001";

    /** Kayitli elle eslestirmeler: anahtar = normalize edilmis yayinci adi. */
    public static Map<String, Link> links() {
        Map<String, Link> out = new HashMap<>();
        for (String raw : Prefs.channelLinks()) {
            if (raw == null) continue;
            String[] f = raw.split(LINK_SEP, 3);
            if (f.length < 3 || f[0].isEmpty() || f[1].isEmpty()) continue;
            Link l = new Link();
            l.broadcast = f[0];
            l.channelId = f[1];
            l.channelName = f[2];
            out.put(f[0], l);
        }
        return out;
    }

    public static Link linkFor(String broadcast) {
        if (broadcast == null) return null;
        String norm = normChannel(broadcast);
        return norm.isEmpty() ? null : links().get(norm);
    }

    /** Yayinciyi bir IPTV kanalina baglar (ayni yayinci icin onceki bag degisir). */
    public static void link(String broadcast, String channelId, String channelName) {
        String norm = normChannel(broadcast);
        if (norm.isEmpty() || channelId == null || channelId.isEmpty()) return;
        Set<String> set = new HashSet<>();
        for (String raw : Prefs.channelLinks()) {
            if (raw != null && raw.startsWith(norm + LINK_SEP)) continue;
            set.add(raw);
        }
        set.add(norm + LINK_SEP + channelId + LINK_SEP
                + (channelName == null ? "" : channelName.replace(LINK_SEP, " ")));
        Prefs.setChannelLinks(set);
    }

    public static void unlink(String broadcast) {
        String norm = normChannel(broadcast);
        if (norm.isEmpty()) return;
        Set<String> set = new HashSet<>();
        for (String raw : Prefs.channelLinks()) {
            if (raw != null && raw.startsWith(norm + LINK_SEP)) continue;
            set.add(raw);
        }
        Prefs.setChannelLinks(set);
    }

    /** Bir macin yayin bilgisi. */
    public static class MatchTv {
        /** Yayinci adlari (IPTV'de karsiligi olmasa da gosterilir). */
        public String broadcast = "";
        /** Birebir eslesen IPTV kanallari. */
        public final List<Candidate> channels = new ArrayList<>();

        /** Satirda gosterilecek kisa etiket (once IPTV kanali, sonra yayinci). */
        public String label() {
            if (!channels.isEmpty()) return channels.get(0).channel;
            return broadcast;
        }

        /**
         * Mac kartinda tek satirlik etiket: IPTV kanali varsa "▶ kanal",
         * yoksa yalnizca ilk yayinci adi. Uzun yayinci listesi karta sigmaz.
         */
        public String rowLabel() {
            if (!channels.isEmpty()) {
                String s = channels.get(0).channel;
                if (channels.size() > 1) s += " +" + (channels.size() - 1);
                return "▶ " + s;
            }
            int cut = broadcast.indexOf(" · ");
            return "• " + (cut > 0 ? broadcast.substring(0, cut) : broadcast);
        }
    }

    private static final String URL = "https://www.liveonsat.com/2day.php";
    private static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final long CACHE_MS = 3 * 60 * 60 * 1000L;
    private static final String KEY_DATA = "tv_cache2";
    private static final String KEY_AT = "tv_cache2_at";

    /** Bir maca en fazla kac yayinci adi yazilir. */
    private static final int MAX_BROADCASTS = 6;
    /** Bir maca en fazla kac IPTV kanali onerilir. */
    private static final int MAX_CHANNELS = 3;

    private static final Pattern COMP_HEAD = Pattern.compile(
            "<span\\s+class\\s*=\\s*comp_head\\s*>(.*?)</span>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern BLOCK = Pattern.compile(
            "class\\s*=\\s*blockfix\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIX_TEXT = Pattern.compile(
            "class\\s*=\\s*fix_text\\b(.*?)(?=class\\s*=\\s*notes\\b|<div\\s+class\\s*=\\s*blockfix\\b|$)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern F_LEFT = Pattern.compile(
            "class\\s*=\\s*fLeft\\b[^>]*>(.*?)</div>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TIMESTAMP = Pattern.compile(
            "data-timestamp\\s*=\\s*[\"']?(\\d{6,})", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIXTURE_SEP = Pattern.compile(
            "\\s+(?:v|vs\\.?|ve)\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHAN_MARK = Pattern.compile(
            "chan_live", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAGS = Pattern.compile("<[^>]+>", Pattern.DOTALL);
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern COMBINING = Pattern.compile("\\p{M}+");
    private static final Pattern QUESTION = Pattern.compile("[?]{1,}");

    private static List<Entry> cache;
    private static long cacheAt;

    private BroadcastApi() {
    }

    // ------------------------------------------------------------ Onbellek

    public static synchronized List<Entry> cached() {
        if (cache == null) loadCache();
        if (cache == null) return null;
        if (System.currentTimeMillis() - cacheAt > CACHE_MS) return null;
        return cache;
    }

    /** Onbellek bayat olsa da son bilinen listeyi dondurur (cevrimdisi kullanim). */
    public static synchronized List<Entry> lastKnown() {
        if (cache == null) loadCache();
        return cache;
    }

    private static void loadCache() {
        SharedPreferences sp = Prefs.sp();
        if (sp == null) return;
        String raw = sp.getString(KEY_DATA, "");
        if (raw == null || raw.isEmpty()) return;
        List<Entry> out = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String[] f = line.split("\\|", -1);
            if (f.length < 5) continue;
            try {
                Entry e = new Entry();
                e.start = Long.parseLong(f[0]);
                e.league = f[1];
                e.home = f[2];
                e.away = f[3];
                if (!f[4].isEmpty()) {
                    for (String c : f[4].split(",")) {
                        c = c.trim();
                        if (!c.isEmpty()) e.channels.add(c);
                    }
                }
                if (e.start > 0 && (!e.home.isEmpty() || !e.away.isEmpty())) out.add(e);
            } catch (Exception ignored) {
            }
        }
        if (!out.isEmpty()) {
            cache = out;
            cacheAt = sp.getLong(KEY_AT, 0);
        }
    }

    private static synchronized void saveCache(List<Entry> list) {
        cache = list;
        cacheAt = System.currentTimeMillis();
        SharedPreferences sp = Prefs.sp();
        if (sp == null) return;
        StringBuilder sb = new StringBuilder();
        for (Entry e : list) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.start).append('|').append(clean(e.league)).append('|')
                    .append(clean(e.home)).append('|').append(clean(e.away)).append('|');
            for (int i = 0; i < e.channels.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(clean(e.channels.get(i)));
            }
        }
        sp.edit().putString(KEY_DATA, sb.toString()).putLong(KEY_AT, cacheAt).apply();
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace('|', ' ').replace(',', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    // ------------------------------------------------------------ Ag + ayristirma

    /** Gunluk listeyi indirir (gerekirse onbellekten). */
    public static List<Entry> load() throws IOException {
        List<Entry> c = cached();
        if (c != null) return c;
        return fetch();
    }

    public static List<Entry> fetch() throws IOException {
        String html = get(URL);
        List<Entry> out = parse(html);
        if (out.isEmpty()) throw new IOException("Yayın listesi okunamadı");
        saveCache(out);
        return out;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = Http.open(url, 12000, BROWSER_UA);
        InputStream in = null;
        try {
            in = Http.stream(c);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(65536);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            byte[] bytes = bos.toByteArray();
            try {
                // LiveOnSat cogu zaman UTF-8; gecersiz bayt varsa windows-1252'ye dus.
                String utf8 = new String(bytes, "UTF-8");
                if (utf8.indexOf('\uFFFD') < 0) return utf8;
                return new String(bytes, "windows-1252");
            } catch (Exception e) {
                return new String(bytes, "ISO-8859-1");
            }
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

    /** LiveOnSat HTML'ini mac listesine cevirir. */
    public static List<Entry> parse(String html) {
        List<Entry> out = new ArrayList<>();
        if (html == null || html.isEmpty()) return out;
        Matcher cm = COMP_HEAD.matcher(html);
        List<int[]> sections = new ArrayList<>();
        List<String> leagues = new ArrayList<>();
        while (cm.find()) {
            sections.add(new int[]{cm.start(), cm.end()});
            leagues.add(text(cm.group(1)));
        }
        for (int s = 0; s < sections.size(); s++) {
            int start = sections.get(s)[1];
            int end = s + 1 < sections.size() ? sections.get(s + 1)[0] : html.length();
            String league = leagues.get(s);
            String body = html.substring(start, end);
            String[] blocks = BLOCK.split(body);
            for (int b = 1; b < blocks.length; b++) {
                Entry e = block(blocks[b], league);
                if (e != null) out.add(e);
            }
        }
        return out;
    }

    private static Entry block(String body, String league) {
        Matcher tm = TIMESTAMP.matcher(body);
        if (!tm.find()) return null;
        long start;
        try {
            start = Long.parseLong(tm.group(1)) * 1000L;
        } catch (Exception e) {
            return null;
        }

        String title = "";
        Matcher fm = FIX_TEXT.matcher(body);
        String fix = fm.find() ? fm.group(1) : body.substring(0, Math.min(body.length(), tm.start()));
        Matcher names = F_LEFT.matcher(fix);
        while (names.find()) {
            String t = text(names.group(1));
            if (t.length() > title.length() && isFixture(t)) title = t;
        }
        if (title.isEmpty()) {
            Matcher all = F_LEFT.matcher(body.substring(0, Math.min(body.length(), tm.start())));
            while (all.find()) {
                String t = text(all.group(1));
                if (t.length() > title.length() && isFixture(t)) title = t;
            }
        }
        if (title.isEmpty()) return null;

        String home;
        String away;
        Matcher sep = FIXTURE_SEP.matcher(title);
        if (sep.find()) {
            home = title.substring(0, sep.start()).trim();
            away = title.substring(sep.end()).trim();
        } else {
            home = title;
            away = "";
        }

        Entry e = new Entry();
        e.start = start;
        e.league = league;
        e.home = home;
        e.away = away;

        // Kanal baglantilari: <a ... class=chan_live...>KANAL</a>
        // onmouseover ozniteliginde HTML blogu (>) bulundugu icin etiket siniri
        // sondaki '>' ile bulunur; basit regex yanlis metin yakalar.
        Matcher ch = CHAN_MARK.matcher(body);
        Set<String> seen = new HashSet<>();
        int from = 0;
        while (true) {
            if (!ch.find(from)) break;
            int close = body.indexOf("</a>", ch.start());
            if (close < 0) break;
            int gt = body.lastIndexOf('>', close);
            from = close + 4;
            if (gt < ch.start()) continue;
            String name = prettyChannel(text(body.substring(gt + 1, close)));
            if (name.isEmpty()) continue;
            String key = name.toLowerCase(Locale.US);
            if (seen.add(key)) e.channels.add(name);
        }
        return e;
    }

    private static boolean isFixture(String s) {
        if (s == null || s.length() < 5 || s.length() > 90) return false;
        if (FIXTURE_SEP.matcher(s).find()) return true;
        return s.contains(" - ") || s.contains(" – ");
    }

    private static String text(String s) {
        if (s == null) return "";
        s = TAGS.matcher(s).replaceAll(" ");
        s = decode(s);
        s = s.replace('\u00a0', ' ');
        s = SPACES.matcher(s).replaceAll(" ").trim();
        return s;
    }

    private static String decode(String s) {
        s = s.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&ndash;", "–").replace("&mdash;", "—")
                .replace("&lt;", "<").replace("&gt;", ">");
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]+);").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            try {
                int cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
                m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))));
            } catch (Exception e) {
                m.appendReplacement(sb, " ");
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** "beIN Sports MENA 1 HD ??" -> "beIN Sports MENA 1" */
    private static String prettyChannel(String s) {
        s = QUESTION.matcher(s).replaceAll(" ");
        s = SPACES.matcher(s).replaceAll(" ").trim();
        return s;
    }

    // ------------------------------------------------------------ Eslesme

    /**
     * Spor rehberindeki maclari gunluk yayin listesiyle eslestirir.
     * IPTV kanali yalnizca normalize edilmis adi yayinci adiyla birebir
     * ayni oldugunda eklenir. Anahtar: {@link #key(Match)}.
     */
    public static Map<String, MatchTv> resolve(List<Match> matches, List<Item> iptv) throws IOException {
        Map<String, MatchTv> out = new HashMap<>();
        if (matches == null || matches.isEmpty()) return out;
        List<Entry> entries = load();
        if (entries == null || entries.isEmpty()) return out;

        Map<String, Item> byName = indexChannels(iptv);
        long now = System.currentTimeMillis();
        long until = now + 48 * 60 * 60 * 1000L;
        for (Match m : matches) {
            if (m.start < now - 3 * 60 * 60 * 1000L || m.start > until) continue;
            Entry e = findEntry(entries, m);
            if (e == null) continue;

            MatchTv tv = new MatchTv();
            tv.broadcast = joinBroadcasts(e.channels);
            Set<String> used = new HashSet<>();
            Map<String, Link> manual = links();
            // 1) Kullanicinin elle bagladigi yayincilar once gelir.
            if (!manual.isEmpty()) {
                for (String bc : e.channels) {
                    Link l = manual.get(normChannel(bc));
                    if (l == null || !used.add(l.channelId)) continue;
                    Candidate c = new Candidate(bc, l.channelName, l.channelId);
                    c.manual = true;
                    tv.channels.add(c);
                }
            }
            // 2) Sonra birebir otomatik eslesmeler.
            if (!byName.isEmpty()) {
                for (String bc : e.channels) {
                    String norm = normChannel(bc);
                    if (norm.isEmpty()) continue;
                    Item hit = byName.get(norm);
                    if (hit == null || !used.add(hit.id)) continue;
                    tv.channels.add(new Candidate(bc, hit.name, hit.id));
                    if (tv.channels.size() >= MAX_CHANNELS) break;
                }
            }
            out.put(key(m), tv);
        }
        return out;
    }

    /**
     * IPTV kanallarini normalize edilmis ada gore indeksler. Ayni ada sahip
     * birden fazla kayit varsa ülke etiketi ve kaliteye gore en uygunu secilir.
     */
    private static Map<String, Item> indexChannels(List<Item> iptv) {
        Map<String, Item> out = new HashMap<>();
        Map<String, Integer> score = new HashMap<>();
        if (iptv == null) return out;
        for (Item it : iptv) {
            if (it == null || it.id == null || it.name == null) continue;
            String norm = normIptv(it.name);
            if (norm.isEmpty()) continue;
            int sc = channelScore(it.name);
            Integer old = score.get(norm);
            if (old == null || sc > old) {
                score.put(norm, sc);
                out.put(norm, it);
            }
        }
        return out;
    }

    /** Ayni adli kanallar arasinda TR etiketli ve yuksek kaliteli olani secer. */
    public static int channelScore(String name) {
        String l = name.toLowerCase(Locale.US);
        int q = 0;
        if (l.contains("fhd")) q += 8;
        else if (l.contains("4k") || l.contains("uhd")) q += 6;
        else if (l.contains("hd")) q += 4;
        if (l.contains("backup") || l.contains("yedek")) q -= 6;
        if (l.startsWith("tr:") || l.startsWith("tr |") || l.contains("turkish") || l.contains("turk ")) q += 20;
        else if (foreignTag(l)) q -= 5;
        return q;
    }

    private static final Set<String> FOREIGN_TAGS = new HashSet<>();

    static {
        Collections.addAll(FOREIGN_TAGS,
                "ar", "us", "uk", "gb", "de", "fr", "it", "es", "nl", "pt", "ru", "pl",
                "gr", "al", "rs", "hr", "me", "mk", "bg", "ro", "hu", "cz", "sk", "sa",
                "ae", "qa", "eg", "ma", "dz", "tn", "ir", "il", "in", "pk", "bd", "ca",
                "au", "nz", "br", "mx", "exyu", "arabic", "balkan");
    }

    /** Kanal adi yabanci bir ulke etiketiyle basliyorsa true. */
    private static boolean foreignTag(String lower) {
        int cut = lower.length();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == ':' || c == '|' || c == ' ' || c == '-') {
                cut = i;
                break;
            }
        }
        if (cut == 0) return false;
        String tag = lower.substring(0, cut);
        return FOREIGN_TAGS.contains(tag);
    }

    /**
     * Yayinci adlarini tek satirda gosterilecek bicimde birlestirir.
     * Ilk {@link #MAX_BROADCASTS} ad yazilir; ayni ada sahip tekrarlar atlanir.
     */
    private static String joinBroadcasts(List<String> channels) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String c : channels) {
            if (c == null) continue;
            String s = c.trim();
            if (s.isEmpty()) continue;
            if (sb.indexOf(s) >= 0) continue;
            if (n > 0) sb.append(" · ");
            sb.append(s);
            if (++n >= MAX_BROADCASTS) break;
        }
        return sb.toString();
    }

    public static String key(Match m) {
        return m.start + "|" + m.home + "|" + m.away;
    }

    /** Mac ile ayni takimlari ve yakin saati tasiyan gunluk yayin satiri. */
    private static Entry findEntry(List<Entry> entries, Match m) {
        Entry best = null;
        int bestScore = 0;
        for (Entry e : entries) {
            long diff = Math.abs(e.start - m.start);
            if (diff > 3 * 60 * 60 * 1000L) continue;
            int hs = teamScore(m.home, e.home);
            int as = teamScore(m.away, e.away);
            int rs = teamScore(m.home, e.away);
            int ls = teamScore(m.away, e.home);
            int sc = Math.max(hs + as, rs + ls);
            if (hs + as == 0 && rs + ls == 0) continue;
            // iki tarafin da eslesmesi gerekir; tek takim yeterli degil
            if (hs > 0 && as > 0 || rs > 0 && ls > 0) {
                if (sc > bestScore) {
                    bestScore = sc;
                    best = e;
                }
            }
        }
        return bestScore >= 6 ? best : null;
    }

    // ------------------------------------------------------------ Takim adlari

    private static final String[] TEAM_SUFFIX = {
            " football club", " futbol kulubu", " futbol kulübü", " calcio", " fc", " cf", " sc", " ac",
            " afc", " sk", " fk", " if", " bk", " cd", " ud", " sv", " vfl", " vfb", " tsg", " spvgg",
            " club", " kulubu", " kulübü"
    };

    private static int teamScore(String a, String b) {
        String na = normTeam(a);
        String nb = normTeam(b);
        if (na.isEmpty() || nb.isEmpty()) return 0;
        if (na.equals(nb)) return 5;
        List<String> ta = words(na);
        List<String> tb = words(nb);
        Set<String> sa = new HashSet<>(ta);
        Set<String> sb = new HashSet<>(tb);
        if (sa.equals(sb)) return 5;
        String pa = " " + na + " ";
        String pb = " " + nb + " ";
        if (pa.contains(" " + nb + " ") || pb.contains(" " + na + " ")) return 4;
        int inter = 0;
        for (String t : sa) if (sb.contains(t)) inter++;
        int min = Math.min(sa.size(), sb.size());
        if (inter >= 2 && inter >= min) return 3;
        if (min == 1 && inter == 1) {
            for (String t : sa) if (sb.contains(t) && t.length() >= 4) return 3;
        }
        return 0;
    }

    private static final Map<String, String> TEAM_ALIAS = new HashMap<>();

    static {
        alias("manchester united", "man utd", "man united", "manchester utd");
        alias("manchester city", "man city");
        alias("inter", "inter milan", "internazionale", "internazionale milano", "fc internazionale");
        alias("milan", "ac milan");
        alias("bayern", "bayern munich", "bayern munchen", "bayern münchen", "fc bayern");
        alias("dortmund", "borussia dortmund", "bvb");
        alias("monchengladbach", "borussia monchengladbach", "borussia mönchengladbach");
        alias("psg", "paris saint germain", "paris st germain", "paris sg");
        alias("atletico", "atletico madrid", "atletico de madrid", "club atletico de madrid");
        alias("sporting", "sporting cp", "sporting lisbon", "sporting clube de portugal");
        alias("benfica", "sl benfica");
        alias("porto", "fc porto");
        alias("basaksehir", "istanbul basaksehir", "istanbul basaksehir fk", "basaksehir fk");
        alias("young boys", "bsc young boys", "yb");
        alias("crvena zvezda", "red star belgrade", "red star");
        alias("dynamo kyiv", "dynamo kiev", "fc dynamo kyiv");
        alias("slavia prague", "slavia praha", "sk slavia prague");
        alias("sparta prague", "sparta praha", "ac sparta prague");
        alias("copenhagen", "fc copenhagen", "fc kobenhavn", "kobenhavn");
        alias("club brugge", "brugge", "club brugge kv");
        alias("union sg", "union saint gilloise", "union st gilloise");
        alias("psv", "psv eindhoven");
        alias("az", "az alkmaar");
        alias("olympiacos", "olympiakos", "olympiacos piraeus");
        alias("aek", "aek athens", "aek atina");
        alias("panathinaikos", "panathinaikos athens");
        alias("ferencvaros", "ferencvaros tc", "ferencváros");
        alias("partizan", "partizan belgrade");
        alias("legia warsaw", "legia warszawa", "legia");
        alias("lech poznan", "lech poznan");
        alias("rangers", "glasgow rangers");
        alias("celtic", "celtic glasgow");
        alias("newcastle", "newcastle united", "newcastle utd");
        alias("tottenham", "tottenham hotspur", "spurs");
        alias("wolverhampton", "wolves", "wolverhampton wanderers");
        alias("brighton", "brighton hove albion");
        alias("nottingham forest", "nottm forest", "forest");
        alias("west ham", "west ham united");
        alias("leicester", "leicester city");
        alias("leeds", "leeds united");
        alias("sheffield united", "sheff utd", "sheffield utd");
        alias("sheffield wednesday", "sheff wed");
        alias("real betis", "betis");
        alias("real sociedad", "sociedad");
        alias("athletic bilbao", "athletic club", "athletic");
        alias("valencia", "valencia cf");
        alias("villarreal", "villarreal cf");
        alias("sevilla", "sevilla fc");
        alias("espanyol", "rcd espanyol");
        alias("getafe", "getafe cf");
        alias("osasuna", "ca osasuna");
        alias("alaves", "deportivo alaves", "alavés");
        alias("mallorca", "rcd mallorca");
        alias("girona", "girona fc");
        alias("celta vigo", "celta");
        alias("rayo vallecano", "rayo");
        alias("las palmas", "ud las palmas");
        alias("leganes", "cd leganes", "leganés");
        alias("valladolid", "real valladolid");
        alias("elche", "elche cf");
        alias("levante", "levante ud");
        alias("almeria", "ud almeria", "almería");
        alias("cadiz", "cadiz cf", "cádiz");
        alias("granada", "granada cf");
        alias("inter turku", "fc inter turku");
        alias("hjk", "hjk helsinki");
        alias("galatasaray", "galatasaray sk", "galatasaray istanbul");
        alias("fenerbahce", "fenerbahçe", "fenerbahce sk");
        alias("besiktas", "beşiktaş", "besiktas jk");
        alias("trabzonspor", "trabzonspor k");
        alias("adana demirspor", "adana demir");
        alias("gaziantep", "gaziantep fk", "gaziantepspor");
        alias("kayserispor", "kayseri");
        alias("konyaspor", "konya");
        alias("antalyaspor", "antalya");
        alias("sivasspor", "sivas");
        alias("alanyaspor", "alanya");
        alias("rizespor", "rize");
        alias("kasimpasa", "kasımpaşa");
        alias("hatayspor", "hatay");
        alias("samsunspor", "samsun");
        alias("goztepe", "göztepe");
        alias("eyupspor", "eyüpspor", "eyup");
        alias("bodrum", "bodrum fk", "bodrumspor");
        alias("istanbulspor", "istanbul spor");
        alias("umraniyespor", "ümraniyespor", "umraniye");
        alias("manisa", "manisa fk", "manisaspor");
        alias("boluspor", "bolu");
        alias("erzurumspor", "erzurum");
        alias("sakaryaspor", "sakarya");
        alias("amed sport", "amedspor", "amed");
        alias("keciorengucu", "keçiörengücü", "keciorengucu");
        alias("adanaspor", "adana");
        alias("sanliurfaspor", "şanlıurfaspor", "urfa");
        alias("pendikspor", "pendik");
        alias("tuzlaspor", "tuzla");
        alias("giresunspor", "giresun");
        alias("genclerbirligi", "gençlerbirliği");
        alias("altay", "altay sk");
        alias("bandirmaspor", "bandırma");
        alias("corum", "çorum fk", "corum fk");
        alias("petrolul", "petrolul ploiesti");
        alias("rapid", "rapid bucuresti", "rapid bucharest");
        alias("fcsb", "steaua bucuresti", "steaua bucharest");
        alias("cluj", "cfr cluj");
        alias("universitatea craiova", "craiova");
        alias("sheriff", "sheriff tiraspol");
        alias("maccabi", "maccabi tel aviv");
        alias("hapoel", "hapoel tel aviv");
        alias("al ahly", "ahly");
        alias("zamalek", "zamalek sc");
        alias("esperance", "esperance tunis");
        alias("wydad", "wydad casablanca");
        alias("raja", "raja casablanca");
        alias("al hilal", "hilal");
        alias("al nassr", "nassr");
        alias("al ittihad", "ittihad");
        alias("al ahli", "ahli");
        alias("shanghai port", "shanghai sipg");
        alias("shanghai shenhua", "shenhua");
        alias("kawasaki frontale", "kawasaki");
        alias("yokohama f marinos", "yokohama marinos", "yokohama fm");
        alias("vissel kobe", "kobe");
        alias("ulsan", "ulsan hyundai");
        alias("jeonbuk", "jeonbuk hyundai motors");
        alias("pohang", "pohang steelers");
        alias("buriram", "buriram united");
        alias("bangkok united", "bangkok utd");
        alias("melbourne city", "melb city");
        alias("melbourne victory", "melb victory");
        alias("western sydney", "western sydney wanderers");
        alias("newcastle jets", "newcastle united jets", "jets");
        alias("central coast", "central coast mariners");
        alias("macarthur", "macarthur fc");
        alias("wellington", "wellington phoenix");
        alias("adelaide", "adelaide united");
        alias("perth", "perth glory");
        alias("brisbane", "brisbane roar");
        alias("sydney", "sydney fc");
        alias("auckland", "auckland fc");
    }

    private static void alias(String canonical, String... variants) {
        TEAM_ALIAS.put(canonical, canonical);
        for (String v : variants) TEAM_ALIAS.put(v, canonical);
    }

    private static String normTeam(String raw) {
        if (raw == null) return "";
        String s = tokens(deburr(raw.toLowerCase(Locale.US)));
        String hit = TEAM_ALIAS.get(s);
        if (hit != null) return hit;
        for (String suf : TEAM_SUFFIX) {
            if (s.endsWith(suf)) {
                s = s.substring(0, s.length() - suf.length()).trim();
                break;
            }
        }
        hit = TEAM_ALIAS.get(s);
        return hit != null ? hit : s;
    }

    /** Kucuk harfe cevrilmis metinden yalnizca harf/rakam token'lari (tek boslukla). */
    private static String tokens(String s) {
        StringBuilder out = new StringBuilder(s.length());
        StringBuilder cur = new StringBuilder();
        int par = 0;
        int br = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                par++;
                continue;
            }
            if (c == ')') {
                if (par > 0) par--;
                continue;
            }
            if (c == '[') {
                br++;
                continue;
            }
            if (c == ']') {
                if (br > 0) br--;
                continue;
            }
            boolean ok = par == 0 && br == 0
                    && ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'));
            if (ok) {
                cur.append(c);
            } else if (cur.length() > 0) {
                if (out.length() > 0) out.append(' ');
                out.append(cur);
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            if (out.length() > 0) out.append(' ');
            out.append(cur);
        }
        return out.toString();
    }

    // ------------------------------------------------------------ Kanal adlari

    private static final Set<String> CH_STOP = new HashSet<>();

    static {
        // Yalnizca kalite/bicim belirten kelimeler atilir. Ulke etiketleri
        // ("FR:", "AR:", "MENA"...) korunur; boylece "FR: Paramount Channel"
        // yayinci adi "Paramount Channel" ile yanlislikla eslesmez.
        Collections.addAll(CH_STOP,
                "hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "h265", "h264", "raw", "backup",
                "yedek", "vip", "iptv", "canli", "live", "yayin", "kanal", "channel", "stream",
                "test");
    }

    private static final String[][] CH_ALIAS = {
            {"bein sports mena", "bein sports"},
            {"bein sports arabia", "bein sports"},
            {"bein connect mena", "bein connect"},
            {"supersport", "super sport"},
            {"sky sports pl", "sky sports premier league"},
            {"sky sports main event", "sky sports main event"},
            {"tnt sports", "tnt sports"},
            {"canal plus", "canal"},
            {"canal+", "canal"},
            {"movistar plus", "movistar"},
            {"dazn la liga", "dazn laliga"},
            {"eleven sports", "eleven"},
            {"match tv", "match"},
            {"match football", "match"},
            {"match premier", "match"},
            {"sport tv", "sportv"},
            {"sportv", "sportv"},
            {"trt spor yildiz", "trt spor yildiz"},
            {"smart spor", "smart spor"},
            {"tivibu spor", "tivibu spor"},
            {"s sport", "s sport"},
            {"ssport", "s sport"},
            {"tabii spor", "tabii"},
            {"exxen", "exxen"},
            {"bein sports max", "bein sports max"},
            {"bein sports 1", "bein sports 1"},
            {"bein sports 2", "bein sports 2"},
            {"bein sports 3", "bein sports 3"},
            {"bein sports 4", "bein sports 4"},
            {"bein sports 5", "bein sports 5"}
    };

    /**
     * Yayinci ve kanal adini karsilastirilabilir bicime getirir.
     * Ornek: "TR: beIN Sports 1 FHD" -> "bein sports 1".
     */
    private static String normChannel(String raw) {
        return norm(raw, false);
    }

    /**
     * IPTV kanal adi icin normalize bicim. Yalnizca "TR:" on eki atilir;
     * diger ulke etiketleri ("FR:", "AR:"...) adin parcasi olarak kalir.
     */
    private static String normIptv(String raw) {
        return norm(raw, true);
    }

    /**
     * Listede gosterilecek kanal adi: basindaki "TR:" / "TR |" ulke etiketi
     * atilir, bosluklar duzeltilir. Diger ulke etiketleri korunur.
     */
    public static String displayChannel(String raw) {
        if (raw == null) return "";
        String s = SPACES.matcher(raw.trim()).replaceAll(" ");
        String l = s.toLowerCase(Locale.US);
        if (l.startsWith("tr:") || l.startsWith("tr |") || l.startsWith("tr -")
                || l.startsWith("tr|") || l.startsWith("tr-")) {
            int i = 2;
            while (i < s.length() && (s.charAt(i) == ':' || s.charAt(i) == '|'
                    || s.charAt(i) == '-' || s.charAt(i) == ' ')) i++;
            s = s.substring(i).trim();
        }
        return s.isEmpty() ? raw.trim() : s;
    }

    /** Ayni kanalin tekrarini bulmak icin (ulke etiketi ve kalite farki yok sayilir). */
    public static String channelKey(String raw) {
        return normIptv(raw);
    }

    private static String norm(String raw, boolean iptv) {
        if (raw == null) return "";
        String s = tokens(deburr(raw.toLowerCase(Locale.US)));
        if (iptv) {
            if (s.equals("tr")) s = "";
            else if (s.startsWith("tr ")) s = s.substring(3);
        }
        for (String[] a : CH_ALIAS) {
            if (s.contains(a[0])) s = s.replace(a[0], a[1]);
        }
        List<String> out = new ArrayList<>();
        for (String t : words(s)) {
            if (CH_STOP.contains(t)) continue;
            out.add(t);
        }
        StringBuilder sb = new StringBuilder();
        for (String t : out) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(t);
        }
        return sb.toString();
    }

    private static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String t : s.split(" ")) if (!t.isEmpty()) out.add(t);
        return out;
    }

    private static String deburr(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean nonAscii = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case 'ı': c = 'i'; break;
                case 'ş': c = 's'; break;
                case 'ğ': c = 'g'; break;
                case 'ü': c = 'u'; break;
                case 'ö': c = 'o'; break;
                case 'ç': c = 'c'; break;
                default: break;
            }
            if (c > 127) nonAscii = true;
            sb.append(c);
        }
        String out = sb.toString();
        if (!nonAscii) return out;
        try {
            out = Normalizer.normalize(out, Normalizer.Form.NFD);
            out = COMBINING.matcher(out).replaceAll("");
        } catch (Exception ignored) {
        }
        return out;
    }

}
