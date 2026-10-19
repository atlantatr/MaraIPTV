package com.atlanta.maraplayer.net;

import android.content.Context;
import android.util.Log;

import com.atlanta.maraplayer.data.Prefs;

import org.conscrypt.Conscrypt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Basit HTTP istemcisi (HttpURLConnection). ExoPlayer da ayni SSL ayarlarini kullanir. */
public final class Http {

    private static final String TAG = "Http";
    private static Provider conscrypt;
    private static HostnameVerifier defaultVerifier;

    private Http() {
    }

    public static void init(Context c) {
        defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
        try {
            conscrypt = Conscrypt.newProvider();
            Security.insertProviderAt(conscrypt, 1);
        } catch (Throwable t) {
            conscrypt = null;
            Log.w(TAG, "Conscrypt yuklenemedi, sistem TLS kullanilacak", t);
        }
        applySsl();
        // Eski Android surumlerinde keep-alive havuzu bazen bozuk baglanti dondurur
        System.setProperty("http.keepAlive", "true");
        System.setProperty("http.maxConnections", "6");
    }

    /** Ayarlar degisince yeniden cagrilir (sertifika dogrulamayi atla vb.) */
    public static void applySsl() {
        boolean trustAll = Prefs.trustAllCerts();
        try {
            SSLContext ctx;
            try {
                ctx = conscrypt != null ? SSLContext.getInstance("TLS", conscrypt) : SSLContext.getInstance("TLS");
            } catch (Throwable t) {
                ctx = SSLContext.getInstance("TLS");
            }
            TrustManager[] tms = trustAll ? new TrustManager[]{new TrustAll()} : null;
            ctx.init(null, tms, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(new Tls12SocketFactory(ctx.getSocketFactory()));
        } catch (Throwable t) {
            Log.e(TAG, "SSL ayarlanamadi", t);
        }
        if (trustAll) {
            HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            });
        } else if (defaultVerifier != null) {
            HttpsURLConnection.setDefaultHostnameVerifier(defaultVerifier);
        }
    }

    public static String userAgent() {
        return Prefs.userAgent();
    }

    /** Yonlendirmeleri (http<->https dahil) elle takip ederek baglanti acar. */
    public static HttpURLConnection open(String url, int timeoutMs) throws IOException {
        String current = url;
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs * 2);
            c.setRequestProperty("User-Agent", userAgent());
            c.setRequestProperty("Accept-Encoding", "gzip");
            c.setRequestProperty("Accept", "*/*");
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("Yonlendirme adresi yok");
                current = new URL(new URL(current), loc).toString();
                continue;
            }
            return c;
        }
        throw new IOException("Cok fazla yonlendirme");
    }

    public static InputStream stream(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        if (code >= 400) {
            throw new HttpException(code, "HTTP " + code);
        }
        InputStream in = c.getInputStream();
        if ("gzip".equalsIgnoreCase(c.getContentEncoding())) in = new GZIPInputStream(in);
        return in;
    }

    public static InputStream getStream(String url) throws IOException {
        return stream(open(url, 15000));
    }

    /** Otomatik giris icin kullanilir: sunucu yanit vermeyince beklemeden hata doner */
    public static InputStream getStreamFast(String url) throws IOException {
        return stream(open(url, 5000));
    }

    public static String getString(String url) throws IOException {
        InputStream in = getStream(url);
        try {
            return new String(readAll(in), "UTF-8");
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(16384);
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    public static class HttpException extends IOException {
        public final int code;

        public HttpException(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    private static class TrustAll implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
