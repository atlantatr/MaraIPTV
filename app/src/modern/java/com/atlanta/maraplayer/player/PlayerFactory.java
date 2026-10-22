package com.atlanta.maraplayer.player;

import android.content.Context;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;

import com.atlanta.maraplayer.data.Prefs;
import com.atlanta.maraplayer.net.Http;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * [Android 5.0 / 7.0 surumleri] AndroidX Media3 oynatici.
 * VOD'da jellyfin media3-ffmpeg-decoder'daki FFmpeg audio renderer'i reflection
 * ile bulunup renderer dizisinin BASINA eklenir -> donanim MediaCodec'e donemeyen
 * AC3 / EAC3 / DTS / TrueHD / MP2 sesleri yazilimla cozulur.
 * Canli TV'de FFmpeg KAPALI (yazilim video decoder'i donma + RAM baskisi).
 */
@OptIn(markerClass = UnstableApi.class)
public final class PlayerFactory {

    private static final String TAG = "MaraAudio";

    /** FFmpeg classlarinin adlari (jellyfin kütüphanesi + Google legacy fallback) */
    private static final String[] LOADER_NAMES = {
            "com.android.jellyfin.media3.extension.FfmpegLibraryLoader",
            "org.jellyfin.media3.extension.FfmpegLibraryLoader",
            "com.google.android.exoplayer2.ffmpeg.FfmpegLibraryLoader",
    };
    private static final String[] RENDERER_NAMES = {
            "com.android.jellyfin.media3.extension.FfmpegAudioRenderer",
            "org.jellyfin.media3.extension.FfmpegAudioRenderer",
    };

    private static boolean probed;

    private PlayerFactory() {
    }

    public static ExoPlayer create(Context c, boolean live) {
        // Extension modu her zaman OFF: VOD'da FFmpeg audio renderer'i
        // createFfmpegAudioRenderers() ile dizinin BASINA ekliyoruz.
        // (Media3'te extension renderer'lar dizi SONUNA eklenir; donanim
        // audio renderer'i onceliginde kalir -> EAC-3 hala cozulemezdi.)
        DefaultRenderersFactory rf = new DefaultRenderersFactory(c)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);

        // VOD: FFmpeg audio renderer'ini (EAC-3/AC-3/DTS) renderer dizisinin basina ekle
        if (!live) {
            List<Renderer> defaultRenderers = rf.createRenderers(c);
            List<Renderer> ffmpeg = createFfmpegAudioRenderers(c.getApplicationContext());
            if (!ffmpeg.isEmpty()) {
                List<Renderer> all = new ArrayList<Renderer>(ffmpeg.size() + defaultRenderers.size());
                all.addAll(ffmpeg);
                all.addAll(defaultRenderers);
                Renderer[] arr = all.toArray(new Renderer[0]);
                ExoPlayer player = new ExoPlayer.Builder(c, arr)
                        .setLoadControl(buildLoadControl(live))
                        .setTrackSelector(buildTrackSelector(c))
                        .build();
                return player;
            }
        }

        return new ExoPlayer.Builder(c, rf)
                .setLoadControl(buildLoadControl(live))
                .setTrackSelector(buildTrackSelector(c))
                .build();
    }

    private static DefaultLoadControl buildLoadControl(boolean live) {
        int[] b = bufferMs(live);
        return new DefaultLoadControl.Builder()
                .setBufferDurationsMs(b[0], b[1], b[2], b[3])
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
    }

    private static DefaultTrackSelector buildTrackSelector(Context c) {
        DefaultTrackSelector ts = new DefaultTrackSelector(c);
        ts.setParameters(ts.buildUponParameters()
                .setExceedRendererCapabilitiesIfNecessary(true)
                .setExceedVideoConstraintsIfNecessary(true)
                .setPreferredAudioLanguage("tr")
                .setPreferredTextLanguage("tr"));
        return ts;
    }

    // ================================================================ FFmpeg (jellyfin)

    /**
     * classpath'teki FFmpeg audio renderer'larini dondurur (bulamazsa bo liste).
     * Tum islemler reflection ile: jellyfin kütüphanesi derleme aninda
     * classpath'te olmayabilir (mara.ffmpeg=false) ama kod derlenmelidir.
     */
    private static List<Renderer> createFfmpegAudioRenderers(Context c) {
        List<Renderer> out = new ArrayList<Renderer>();
        synchronized (PlayerFactory.class) {
            if (probed) {
                Log.d(TAG, "VOD: FFmpeg probe yapildi (onceki sonuc gecerli)");
            } else {
                probed = true;
            }
        }
        // 1) Hangi classlar mevcut?
        for (String n : LOADER_NAMES) Log.i(TAG, "probe loader  " + n + " -> " + exists(n));
        for (String n : RENDERER_NAMES) Log.i(TAG, "probe renderer " + n + " -> " + exists(n));

        // 2) Native kütüphane yukeleyici varsa calistir (loadNativeLibraries vb.)
        for (String ln : LOADER_NAMES) {
            if (!exists(ln)) continue;
            if (invokeNativeLoader(ln, c)) break;
        }

        // 3) Audio renderer olustur
        for (String rn : RENDERER_NAMES) {
            if (!exists(rn)) continue;
            try {
                Class<?> cl = Class.forName(rn);
                Object inst = null;
                if (hasConstructor(cl, Context.class)) {
                    inst = cl.getConstructor(Context.class).newInstance(c);
                } else if (hasConstructor(cl)) {
                    inst = cl.getConstructor().newInstance();
                }
                if (inst == null) {
                    // Hangi constructor'lar var? Log'a yaz, bir sonraki turde duzelt
                    StringBuilder sb = new StringBuilder();
                    for (Constructor<?> cs : cl.getConstructors()) {
                        if (sb.length() > 0) sb.append(" | ");
                        sb.append(cs);
                    }
                    Log.w(TAG, "FFmpeg audio renderer constructor uymuyor: " + sb);
                    continue;
                }
                if (inst instanceof Renderer) {
                    out.add((Renderer) inst);
                    Log.i(TAG, "FFmpeg audio renderer EKLENDI: " + rn);
                } else {
                    Log.w(TAG, "FFmpeg renderer Renderer interface'i implement etmiyor: " + rn + "." + inst.getClass().getName());
                }
            } catch (Throwable t) {
                Log.w(TAG, "FFmpeg audio renderer olusturulamadi", t);
            }
        }
        if (out.isEmpty()) Log.w(TAG, "FFmpeg audio renderer bulunamadi -> EAC-3/AC-3 donanimdaki decoder'e kalir");
        return out;
    }

    private static boolean exists(String className) {
        try {
            Class.forName(className, false, PlayerFactory.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean hasConstructor(Class<?> cl, Class<?>... params) {
        try {
            cl.getConstructor(params);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /** Loader sinifindaki static Context-tek-parametreli metodu (loadNativeLibraries vb.) calistirir */
    private static boolean invokeNativeLoader(String className, Context c) {
        try {
            Class<?> cl = Class.forName(className, false, PlayerFactory.class.getClassLoader());
            for (Method m : cl.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterTypes().length != 1 || m.getParameterTypes()[0] != Context.class) continue;
                try {
                    m.setAccessible(true);
                    m.invoke(null, c);
                    Log.i(TAG, "native loader basarili: " + className + "." + m.getName());
                    return true;
                } catch (Throwable t) {
                    Log.w(TAG, "native loader hatasi: " + className + "." + m.getName(), t);
                    return false;
                }
            }
            Log.i(TAG, "loader sinifinda static (Context) metot yok: " + className);
        } catch (Throwable t) {
            Log.w(TAG, "loader sinifi yuklenemedi: " + className, t);
        }
        return false;
    }

    /** min, max, playbackStart, rebuffer (ms) */
    public static int[] bufferMs(boolean live) {
        int min, max, start, rebuffer;
        switch (Prefs.buffer()) {
            case "low":
                min = 4000; max = 15000; start = 1000; rebuffer = 2000;
                break;
            case "high":
                min = 30000; max = 90000; start = 4000; rebuffer = 8000;
                break;
            case "fhd":
                min = 20000; max = 60000; start = 3000; rebuffer = 6000;
                break;
            default:
                // "normal": bol tampon, donma onleme icin
                min = 20000; max = 60000; start = 2000; rebuffer = 4000;
                break;
        }
        if (live) {
            // Canlı TV: RAM baskisini azaltmak icin max buffer 30s, playbackStart 1s.
            // 1080p donanim decoder fallback senaryosunda (ion OOM) buffer arttirdikca
            // yuzu katli gecer; low/normal modu 30s'e sinir, fhd/yuksek 45s'te kalir.
            int cap = (Prefs.buffer().equals("fhd") || Prefs.buffer().equals("high")) ? 45000 : 30000;
            max = Math.min(max, cap);
            min = Math.min(min, max);
            start = Math.min(start, 1000);
            rebuffer = Math.min(rebuffer, 2500);
        } else {
            // VOD (film/dizi): canli TV'deki sinirlar gecerli degil;
            // donarak oynatma sorunu icin minimum tamponu asgari 20sn tut,
            // kesinti olustugunda daha cesarli beklesin
            min = Math.max(min, 20000);
            start = Math.min(start, 3000);
            rebuffer = Math.max(rebuffer, 4000);
        }
        return new int[]{min, max, start, rebuffer};
    }

    public static MediaSource source(Context c, String url) {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(Http.userAgent())
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000);
        DefaultDataSource.Factory ds = new DefaultDataSource.Factory(c, http);
        MediaItem item = MediaItem.fromUri(url);
        String lower = url.toLowerCase();
        int q = lower.indexOf('?');
        String path = q > 0 ? lower.substring(0, q) : lower;
        if (path.endsWith(".m3u8") || path.endsWith(".m3u")) {
            return new HlsMediaSource.Factory(ds)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(item);
        }
        if (path.endsWith(".mpd")) {
            return new DashMediaSource.Factory(ds).createMediaSource(item);
        }
        DefaultExtractorsFactory ef = new DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                        | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
                .setConstantBitrateSeekingEnabled(true);
        return new ProgressiveMediaSource.Factory(ds, ef).createMediaSource(item);
    }
}
