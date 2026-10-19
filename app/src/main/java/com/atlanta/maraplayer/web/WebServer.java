package com.atlanta.maraplayer.web;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.atlanta.maraplayer.BuildConfig;
import com.atlanta.maraplayer.data.AppEvents;
import com.atlanta.maraplayer.data.Db;
import com.atlanta.maraplayer.data.Models;
import com.atlanta.maraplayer.data.Models.Account;
import com.atlanta.maraplayer.data.Models.Category;
import com.atlanta.maraplayer.data.Models.Profile;
import com.atlanta.maraplayer.data.Prefs;
import com.atlanta.maraplayer.data.Repo;
import com.atlanta.maraplayer.data.Source;
import com.atlanta.maraplayer.data.Sources;
import com.atlanta.maraplayer.data.XmlTv;
import com.atlanta.maraplayer.data.XtreamApi;
import com.atlanta.maraplayer.net.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;

/**
 * Yerel ag web paneli: http://CIHAZ_IP:8765
 * Hesap (profil) ekle/duzenle/sil, ebeveyn PIN ve kilidi, kategori gizle/kilitle.
 * Harici kutuphane kullanmaz (Android 4.2'de de calisir). Giris ebeveyn PIN'i ile yapilir.
 */
public final class WebServer {

    private static final String TAG = "WebPanel";
    private static WebServer instance;

    private final Context ctx;
    private ServerSocket server;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Set<String> tokens = Collections.synchronizedSet(new HashSet<String>());
    private int failCount;
    private long lockedUntil;
    private volatile boolean running;

    private WebServer(Context c) {
        ctx = c.getApplicationContext();
    }

    public static synchronized void start(Context c) {
        if (instance != null || !Prefs.webPanel()) return;
        instance = new WebServer(c);
        instance.begin();
    }

    public static synchronized void stop() {
        if (instance == null) return;
        instance.running = false;
        try {
            if (instance.server != null) instance.server.close();
        } catch (IOException ignored) {
        }
        instance.pool.shutdownNow();
        instance = null;
    }

    public static synchronized boolean isRunning() {
        return instance != null && instance.running;
    }

    /** "http://192.168.1.20:8765" veya ag yoksa null */
    public static String address() {
        String ip = localIp();
        return ip == null ? null : "http://" + ip + ":" + Prefs.WEB_PORT;
    }

    public static String localIp() {
        try {
            String fallback = null;
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            if (ifs == null) return null;
            for (NetworkInterface ni : Collections.list(ifs)) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    String n = ni.getName().toLowerCase(Locale.US);
                    if (n.startsWith("eth") || n.startsWith("wlan")) return ip;
                    if (fallback == null) fallback = ip;
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    private void begin() {
        running = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    server = new ServerSocket();
                    server.setReuseAddress(true);
                    server.bind(new java.net.InetSocketAddress(Prefs.WEB_PORT));
                    while (running) {
                        final Socket s = server.accept();
                        pool.execute(new Runnable() {
                            @Override
                            public void run() {
                                handle(s);
                            }
                        });
                    }
                } catch (Exception e) {
                    if (running) Log.e(TAG, "web panel durdu", e);
                } finally {
                    running = false;
                    synchronized (WebServer.class) {
                        if (instance == WebServer.this) instance = null;
                    }
                }
            }
        }, "web-panel").start();
    }

    // ================================================================ HTTP

    private static class Req {
        String method = "GET";
        String path = "/";
        final Map<String, String> query = new HashMap<>();
        final Map<String, String> headers = new HashMap<>();
        String body = "";
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(20000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            Req r = parse(in);
            if (r == null) return;
            try {
                route(r, out);
            } catch (Exception e) {
                Log.w(TAG, "istek hatasi", e);
                sendJson(out, 500, err(e.getMessage() == null ? e.toString() : e.getMessage()));
            }
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("satir cok uzun");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private static Req parse(InputStream in) throws IOException {
        String first = readLine(in);
        if (first == null || first.isEmpty()) return null;
        String[] parts = first.split(" ");
        if (parts.length < 2) return null;
        Req r = new Req();
        r.method = parts[0].toUpperCase(Locale.US);
        String target = parts[1];
        int q = target.indexOf('?');
        r.path = q >= 0 ? target.substring(0, q) : target;
        if (q >= 0) {
            for (String kv : target.substring(q + 1).split("&")) {
                int e = kv.indexOf('=');
                if (e > 0) r.query.put(dec(kv.substring(0, e)), dec(kv.substring(e + 1)));
            }
        }
        String line;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int c = line.indexOf(':');
            if (c > 0) r.headers.put(line.substring(0, c).trim().toLowerCase(Locale.US), line.substring(c + 1).trim());
        }
        int len = 0;
        try {
            len = Integer.parseInt(r.headers.containsKey("content-length") ? r.headers.get("content-length") : "0");
        } catch (NumberFormatException ignored) {
        }
        if (len > 0) {
            if (len > 1024 * 1024) throw new IOException("istek cok buyuk");
            byte[] buf = new byte[len];
            int read = 0;
            while (read < len) {
                int n = in.read(buf, read, len - read);
                if (n < 0) break;
                read += n;
            }
            r.body = new String(buf, 0, read, "UTF-8");
        }
        return r;
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static void send(OutputStream out, int code, String type, byte[] body, String extraHeaders) throws IOException {
        String status = code == 200 ? "OK" : code == 401 ? "Unauthorized" : code == 404 ? "Not Found" : code == 429 ? "Too Many Requests" : "Error";
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code).append(' ').append(status).append("\r\n");
        h.append("Content-Type: ").append(type).append("\r\n");
        h.append("Content-Length: ").append(body.length).append("\r\n");
        h.append("Cache-Control: no-store\r\n");
        h.append("X-Content-Type-Options: nosniff\r\n");
        h.append("Connection: close\r\n");
        if (extraHeaders != null) h.append(extraHeaders);
        h.append("\r\n");
        out.write(h.toString().getBytes("UTF-8"));
        out.write(body);
    }

    private static void sendJson(OutputStream out, int code, Object json) throws IOException {
        send(out, code, "application/json; charset=utf-8", json.toString().getBytes("UTF-8"), null);
    }

    private static JSONObject ok() {
        try {
            return new JSONObject().put("ok", true);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static JSONObject err(String msg) {
        try {
            return new JSONObject().put("ok", false).put("error", msg);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private String token(Req r) {
        String t = r.headers.get("x-token");
        if (t != null) return t;
        String cookie = r.headers.get("cookie");
        if (cookie != null) {
            for (String c : cookie.split(";")) {
                c = c.trim();
                if (c.startsWith("mara=")) return c.substring(5);
            }
        }
        return null;
    }

    // ================================================================ Yonlendirme

    private void route(Req r, OutputStream out) throws Exception {
        String p = r.path;
        if (p.equals("/") || p.equals("/index.html")) {
            send(out, 200, "text/html; charset=utf-8", asset("panel.html"), null);
            return;
        }
        if (p.equals("/logo.png") || p.equals("/logo_text.png") || p.equals("/favicon.ico")) {
            try {
                send(out, 200, "image/png", asset(p.equals("/favicon.ico") ? "logo.png" : p.substring(1)), "Cache-Control: max-age=86400\r\n");
            } catch (IOException e) {
                send(out, 404, "text/plain", new byte[0], null);
            }
            return;
        }
        if (!p.startsWith("/api/")) {
            send(out, 404, "text/plain; charset=utf-8", "Bulunamadi".getBytes("UTF-8"), null);
            return;
        }
        JSONObject body = r.body.isEmpty() ? new JSONObject() : new JSONObject(r.body);

        if (p.equals("/api/login")) {
            login(body, out);
            return;
        }
        String t = token(r);
        if (t == null || !tokens.contains(t)) {
            sendJson(out, 401, err("Oturum gerekli"));
            return;
        }

        switch (p) {
            case "/api/status":
                sendJson(out, 200, status());
                break;
            case "/api/profile/save":
                sendJson(out, 200, saveProfile(body));
                break;
            case "/api/profile/delete":
                Db.get().deleteProfile(body.getLong("id"));
                AppEvents.profilesChanged(body.getLong("id"), false);
                sendJson(out, 200, ok());
                break;
            case "/api/profile/test":
                sendJson(out, 200, testProfile(body));
                break;
            case "/api/pin":
                sendJson(out, 200, changePin(body));
                break;
            case "/api/parental":
                if (body.has("enabled")) Prefs.setParental(body.getBoolean("enabled"));
                if (body.has("adultAutolock")) Prefs.setAdultAutoLock(body.getBoolean("adultAutolock"));
                Prefs.clearSessionUnlocks();
                sendJson(out, 200, ok());
                break;
            case "/api/locks/reset":
                Prefs.clearAllLocks();
                Prefs.clearSessionUnlocks();
                sendJson(out, 200, ok());
                break;
            case "/api/categories":
                try {
                    long dpid = XtreamApi.toInt(r.query.get("pid"));
                    int dtype = XtreamApi.toInt(r.query.get("type"));
                    List<Category> cats = loadCategories(dpid, dtype);
                    if (cats == null) cats = new ArrayList<Category>();
                    JSONArray carr = new JSONArray();
                    for (Category c : cats) {
                        carr.put(ok().put("id", c.id).put("name", c.name)
                                .put("hidden", Prefs.isHidden(dpid, dtype, c.id))
                                .put("locked", Prefs.isLockedPermanent(dpid, dtype, c.id, c.name))
                                .put("adult", Prefs.looksAdult(c.name)));
                    }
                    sendJson(out, 200, ok().put("categories", carr).put("count", cats.size()));
                } catch (Exception ex) {
                    Log.e(TAG, "kategori hatasi: pid=" + r.query.get("pid") + " type=" + r.query.get("type"), ex);
                    sendJson(out, 500, err("Kategori alinamadi: " + (ex.getMessage() == null ? ex.toString() : ex.getMessage())));
                }
                break;
            case "/api/category":
                sendJson(out, 200, setCategory(body));
                break;
            case "/api/categories/bulk":
                sendJson(out, 200, bulk(body));
                break;
            case "/api/epg/update":
                sendJson(out, 200, updateEpg(body));
                break;
            case "/api/logout":
                tokens.remove(t);
                sendJson(out, 200, ok());
                break;
            default:
                sendJson(out, 404, err("Bilinmeyen istek"));
        }
    }

    private byte[] asset(String name) throws IOException {
        InputStream in = ctx.getAssets().open(name);
        try {
            return Http.readAll(in);
        } finally {
            in.close();
        }
    }

    // ================================================================ Islemler

    private synchronized void login(JSONObject body, OutputStream out) throws Exception {
        long now = System.currentTimeMillis();
        if (now < lockedUntil) {
            sendJson(out, 429, err("Cok fazla hatali deneme. " + ((lockedUntil - now) / 1000 + 1) + " sn bekleyin."));
            return;
        }
        if (!Prefs.checkPin(body.optString("pin", "").trim())) {
            failCount++;
            if (failCount >= 5) {
                lockedUntil = now + 60000;
                failCount = 0;
            }
            sendJson(out, 401, err("PIN hatali"));
            return;
        }
        failCount = 0;
        byte[] rnd = new byte[16];
        new SecureRandom().nextBytes(rnd);
        StringBuilder sb = new StringBuilder();
        for (byte b : rnd) sb.append(String.format("%02x", b & 0xff));
        String token = sb.toString();
        tokens.add(token);
        JSONObject o = ok().put("token", token);
        send(out, 200, "application/json; charset=utf-8", o.toString().getBytes("UTF-8"),
                "Set-Cookie: mara=" + token + "; Path=/; HttpOnly; SameSite=Strict\r\n");
    }

    private JSONObject status() throws Exception {
        JSONObject o = ok();
        o.put("app", "Mara IPTV Player");
        o.put("edition", BuildConfig.EDITION);
        o.put("version", BuildConfig.VERSION_NAME);
        o.put("device", Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")");
        o.put("activeProfile", Repo.pid());
        o.put("lastProfile", Prefs.lastProfile());
        JSONArray arr = new JSONArray();
        for (Profile p : Db.get().profiles()) {
            JSONObject j = new JSONObject();
            j.put("id", p.id);
            j.put("name", p.name);
            j.put("type", p.type);
            j.put("url", p.url);
            j.put("user", p.user);
            // sifre ag uzerinden gonderilmez; bos birakilirsa kayitli sifre korunur
            j.put("hasPass", !p.pass.isEmpty());
            j.put("epgUrl", p.epgUrl);
            j.put("lastUsed", p.lastUsed);
            j.put("epgCount", Db.get().epgCount(p.id));
            arr.put(j);
        }
        o.put("profiles", arr);
        JSONObject par = new JSONObject();
        par.put("enabled", Prefs.parentalEnabled());
        par.put("adultAutolock", Prefs.adultAutoLock());
        par.put("defaultPin", "0000".equals(Prefs.pin()));
        o.put("parental", par);
        return o;
    }

    private Profile fromJson(JSONObject b) {
        Profile p = new Profile();
        p.id = b.optLong("id", 0);
        p.type = Profile.TYPE_M3U.equals(b.optString("type")) ? Profile.TYPE_M3U : Profile.TYPE_XTREAM;
        p.name = b.optString("name", "").trim();
        p.url = b.optString("url", "").trim();
        p.user = b.optString("user", "").trim();
        p.pass = b.optString("pass", "").trim();
        p.epgUrl = b.optString("epgUrl", "").trim();
        if (!p.isM3u()) p.url = XtreamApi.normalizeUrl(p.url);
        return p;
    }

    private JSONObject saveProfile(JSONObject b) throws Exception {
        Profile p = fromJson(b);
        if (p.id > 0 && p.pass.isEmpty()) {
            Profile old = Db.get().profile(p.id);
            if (old != null) p.pass = old.pass;
        }
        if (p.url.isEmpty()) return err("Sunucu / liste adresi gerekli");
        if (!p.isM3u() && (p.user.isEmpty() || p.pass.isEmpty())) return err("Kullanici adi ve sifre gerekli");
        if (p.name.isEmpty()) p.name = p.isM3u() ? "M3U listesi" : p.user;
        if (p.id > 0 && Db.get().profile(p.id) == null) p.id = 0;
        boolean added = p.id <= 0;
        Db.get().saveProfile(p);
        // Cihazdaki ekranlar: yeni hesap otomatik acilir, aktif hesap degistiyse arka planda yenilenir
        AppEvents.profilesChanged(p.id, added);
        return ok().put("id", p.id);
    }

    private JSONObject testProfile(JSONObject b) throws Exception {
        Profile p = fromJson(b);
        if (p.id > 0 && p.pass.isEmpty()) {
            Profile old = Db.get().profile(p.id);
            if (old != null) p.pass = old.pass;
        }
        try {
            Account a = Sources.create(p).login();
            JSONObject o = ok();
            o.put("status", a.status);
            o.put("expDate", a.expDate);
            o.put("maxConnections", a.maxConnections);
            o.put("activeConnections", a.activeConnections);
            return o;
        } catch (Exception e) {
            return err(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private JSONObject changePin(JSONObject b) {
        String oldPin = b.optString("old", "").trim();
        String newPin = b.optString("new", "").trim();
        if (!Prefs.checkPin(oldPin)) return err("Mevcut PIN hatali");
        if (newPin.length() < 4 || !newPin.matches("\\d+")) return err("Yeni PIN en az 4 rakam olmali");
        Prefs.setPin(newPin);
        return ok();
    }

    private Source sourceFor(long pid) throws IOException {
        Profile p = Db.get().profile(pid);
        if (p == null) throw new IOException("Profil bulunamadi");
        return Sources.create(p);
    }

    private final Map<String, List<Category>> catCache = new HashMap<>();

    private List<Category> loadCategories(long pid, int type) throws IOException {
        if (pid == Repo.pid() && Repo.hasSession()) return Repo.categories(type);
        String k = pid + ":" + type;
        synchronized (catCache) {
            List<Category> c = catCache.get(k);
            if (c != null) return c;
        }
        List<Category> c = sourceFor(pid).categories(type);
        synchronized (catCache) {
            catCache.put(k, c);
        }
        return c;
    }


    private JSONObject setCategory(JSONObject b) throws Exception {
        long pid = b.getLong("pid");
        int type = b.getInt("type");
        String id = b.getString("id");
        String name = b.optString("name", "");
        if (b.has("hidden")) Prefs.setHidden(pid, type, id, b.getBoolean("hidden"));
        if (b.has("locked")) Prefs.setLocked(pid, type, id, name, b.getBoolean("locked"));
        Prefs.clearSessionUnlocks();
        return ok();
    }

    private JSONObject bulk(JSONObject b) throws Exception {
        long pid = b.getLong("pid");
        int type = b.getInt("type");
        String action = b.getString("action");
        for (Category c : loadCategories(pid, type)) {
            switch (action) {
                case "hideAll": Prefs.setHidden(pid, type, c.id, true); break;
                case "showAll": Prefs.setHidden(pid, type, c.id, false); break;
                case "lockAll": Prefs.setLocked(pid, type, c.id, c.name, true); break;
                case "unlockAll": Prefs.setLocked(pid, type, c.id, c.name, false); break;
                default: break;
            }
        }
        Prefs.clearSessionUnlocks();
        return ok();
    }

    private JSONObject updateEpg(JSONObject b) throws Exception {
        final long pid = b.getLong("pid");
        final Profile p = Db.get().profile(pid);
        if (p == null) return err("Profil bulunamadi");
        if (XmlTv.isRunning()) return err("EPG guncellemesi zaten suruyor");
        Source src;
        if (pid == Repo.pid() && Repo.hasSession()) {
            src = Repo.api();
        } else {
            src = Sources.create(p);
            // M3U: url-tvg adresi ancak liste okununca bilinir
            if (p.isM3u() && (p.epgUrl == null || p.epgUrl.isEmpty())) src.login();
        }
        final String url = XmlTv.urlFor(p, src);
        if (url.isEmpty()) return err("Bu profil icin XMLTV adresi yok (hesabi duzenleyip XMLTV adresi ekleyin)");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    XmlTv.update(pid, url);
                } catch (Exception e) {
                    Log.w(TAG, "EPG guncellenemedi", e);
                }
            }
        }, "xmltv-web").start();
        return ok().put("message", "EPG guncellemesi baslatildi");
    }

    @SuppressWarnings("unused")
    private static int typeOf(String s) {
        if ("movie".equals(s)) return Models.MOVIE;
        if ("series".equals(s)) return Models.SERIES;
        return Models.LIVE;
    }
}
