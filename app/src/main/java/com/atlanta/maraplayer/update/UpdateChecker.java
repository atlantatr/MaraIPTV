package com.atlanta.maraplayer.update;

import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.atlanta.maraplayer.BuildConfig;
import com.atlanta.maraplayer.data.Prefs;
import com.atlanta.maraplayer.net.Http;
import com.atlanta.maraplayer.util.Ui;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub releases uzerinden guncelleme kontrolu.
 *
 * En son surumun notlari gosterilir; kurulu flavour'a (legacy/lollipop/nougat)
 * uygun APK indirilip kurulum ekrani acilir. Indirme DownloadManager ile
 * yapildigi icin Android 7+ dosya izinleri (FileProvider) gerekmez.
 */
public final class UpdateChecker {

    /** Guncellemelerin yayinlandigi depo. */
    public static final String REPO = "atlantatr/MaraIPTV";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";

    public static final String APK_MIME = "application/vnd.android.package-archive";

    /** versionName icindeki "a71.261028-1224" kalibi. */
    private static final Pattern STAMP = Pattern.compile("a(\\d+)\\.(\\d{6})-(\\d{4})");

    public static class Release {
        public String tag = "";
        public String name = "";
        public String notes = "";
        public String apkName = "";
        public String apkUrl = "";
        public long size;
        public boolean newer;
    }

    private UpdateChecker() {
    }

    /** En son release'i getirir (ag hatasi firlatabilir; release yoksa null). */
    public static Release latest() throws Exception {
        String json;
        try {
            json = Http.getString(API);
        } catch (Http.HttpException e) {
            if (e.code == 404) return null; // hic release yayınlanmamis
            throw e;
        } catch (Exception e) {
            // Android 4.2: sistem kok deposunda GitHub sertifikasi yok.
            // Lite surumde yalnizca bu istek icin dogrulamayi atlayip yeniden dene.
            // Cevrimdisi hatalarda (DNS/baglanti/zaman asimi) tekrar denenmez.
            boolean offline = e instanceof java.net.UnknownHostException
                    || e instanceof java.net.ConnectException
                    || e instanceof java.net.SocketTimeoutException;
            if (!BuildConfig.LITE || offline) throw e;
            json = Http.getStringTrustAll(API);
        }
        JSONObject o = new JSONObject(json);
        Release r = new Release();
        r.tag = o.optString("tag_name", "");
        r.name = o.optString("name", r.tag);
        r.notes = o.optString("body", "");
        r.newer = isNewer(r.tag, currentVersion());

        JSONObject best = null;
        JSONArray assets = o.optJSONArray("assets");
        if (assets != null) {
            String want = BuildConfig.FLAVOR == null ? "" : BuildConfig.FLAVOR.toLowerCase(Locale.US);
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) continue;
                String n = a.optString("name", "");
                if (!n.toLowerCase(Locale.US).endsWith(".apk")) continue;
                if (best == null) best = a;
                if (!want.isEmpty() && n.toLowerCase(Locale.US).contains(want)) {
                    best = a;
                    break;
                }
            }
        }
        if (best != null) {
            r.apkName = best.optString("name", "MaraIPTV.apk");
            r.apkUrl = best.optString("browser_download_url", "");
            r.size = best.optLong("size", 0);
        }
        return r;
    }

    public static String currentVersion() {
        return BuildConfig.VERSION_NAME;
    }

    /**
     * Tag, kurulu surumden yeni mi? Once "aNN.YYMMDD-HHmm" damgasi
     * karsilastirilir; kalip yoksa farkli tag "yeni" sayilir.
     */
    public static boolean isNewer(String tag, String current) {
        if (tag == null || tag.isEmpty()) return false;
        String t = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
        String c = current == null ? "" : current;
        if (t.equalsIgnoreCase(c)) return false;
        long ts = stamp(t);
        long cs = stamp(c);
        if (ts > 0 && cs > 0) return ts > cs;
        // Damga okunamadi: tag icinde surum numarasi varsa farkli olmasi yeterli
        return !t.equalsIgnoreCase(c);
    }

    /** "2.0.0-a71.261028-1224" -> 2610281224 (YYMMDDHHmm). */
    private static long stamp(String v) {
        Matcher m = STAMP.matcher(v == null ? "" : v);
        if (!m.find()) return -1;
        try {
            return Long.parseLong(m.group(2) + m.group(3));
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * APK'yi indirir. Tamamlaninca {@link UpdateInstallReceiver} kurulum
     * ekranini acar.
     */
    public static void download(Context c, Release r) {
        if (BuildConfig.LITE) {
            downloadInApp(c, r);
            return;
        }
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) throw new IllegalStateException("DownloadManager yok");
        String name = r.apkName == null || r.apkName.isEmpty() ? "MaraIPTV.apk" : r.apkName;
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(r.apkUrl))
                .setTitle("MaraIPTV " + r.tag)
                .setDescription(name)
                .setMimeType(APK_MIME)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(c, Environment.DIRECTORY_DOWNLOADS, name);
        long id = dm.enqueue(req);
        Prefs.sp().edit()
                .putLong("update_download_id", id)
                .putString("update_apk_name", name)
                .apply();
    }

    private static volatile boolean downloading;

    /**
     * Lite/Android 4.2: DownloadManager sistem TLS'ini kullanir ve eski Android'de
     * GitHub sertifikasi guvenilir olmadigi icin indirme basarisiz olur. Bu yuzden
     * APK uygulama icinde indirilir, harici klasore yazilir ve kurulum ekrani acilir.
     */
    private static void downloadInApp(final Context c, final Release r) {
        if (downloading) return;
        final String name = r.apkName == null || r.apkName.isEmpty() ? "MaraIPTV.apk" : r.apkName;
        final Handler ui = new Handler(Looper.getMainLooper());
        downloading = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                File dir = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) dir = c.getFilesDir();
                final File out = new File(dir, name);
                File tmp = new File(out.getAbsolutePath() + ".part");
                Exception err = null;
                InputStream in = null;
                FileOutputStream fos = null;
                try {
                    in = Http.getStreamTrustAll(r.apkUrl);
                    fos = new FileOutputStream(tmp);
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
                    fos.flush();
                    fos.close();
                    fos = null;
                    in.close();
                    in = null;
                    if (out.exists() && !out.delete()) throw new IOException("Eski dosya silinemedi");
                    if (!tmp.renameTo(out)) throw new IOException("Dosya kaydedilemedi");
                    out.setReadable(true, false);
                } catch (Exception e) {
                    err = e;
                } finally {
                    downloading = false;
                    if (fos != null) try { fos.close(); } catch (IOException ignored) { }
                    if (in != null) try { in.close(); } catch (IOException ignored) { }
                    if (err != null) tmp.delete();
                }
                final Exception fe = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fe != null) {
                            Toast.makeText(c, c.getString(
                                    com.atlanta.maraplayer.R.string.update_failed_x, Ui.err(fe)),
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        Intent i = new Intent(Intent.ACTION_VIEW)
                                .setDataAndType(Uri.fromFile(out), APK_MIME)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            c.startActivity(i);
                        } catch (Exception e) {
                            Toast.makeText(c, out.getAbsolutePath(), Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "mara-update-download").start();
    }

    public static long pendingDownloadId() {
        return Prefs.sp().getLong("update_download_id", -1);
    }

    public static void clearPending() {
        Prefs.sp().edit().remove("update_download_id").remove("update_apk_name").apply();
    }
}
