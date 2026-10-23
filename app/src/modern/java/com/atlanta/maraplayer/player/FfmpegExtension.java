package com.atlanta.maraplayer.player;

import android.content.Context;
import android.util.Log;

import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.Renderer;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

/**
 * Jellyfin media3-ffmpeg-decoder uzantisini Media3'e manuel baglar.
 *
 * Media3'un otomatik extension keşfi, FFmpeg renderers'ini kütüphanedeki
 * tam sinif adina gore reflection ile bulur; ad uyumsuzlugunda sessizce
 * islemeyebilir. Bu sinif, kütüphanedeki FfmpegAudioRenderer / FfmpegVideoRenderer
 * siniflarini ogretigince deneyerek (compile-time baglamligi olmadan)
 * DefaultRenderersFactory'e ek extension renderer olarak verir.
 *
 * Kütüphane classpath'te degilse (mara.ffmpeg=false) veya sinif adlari
 * degismissse, derleme hatasi DEGIL, calisma zamaninda uyarli fallback
 * saglanir — oynatici donanim decodeclar'la devam eder.
 */
public final class FfmpegExtension {

    private static final String TAG = "MaraFfmpeg";

    /**
     * Jellyfin kütüphanesindeki muhtemel sinif adlari. Birden fazla
     * aday deneriz; ilki basariyorsa kullanilir.
     */
    private static final String[] AUDIO_RENDERER_NAMES = {
            "com.android.jellyfin.media3.extension.FfmpegAudioRenderer",
            "com.android.jellyfin.media3.extension.FfmpegAudioCodecRenderer",
            "com.android.jellyfin.media3.ffmpeg.FfmpegAudioRenderer",
    };

    private static final String[] VIDEO_RENDERER_NAMES = {
            "com.android.jellyfin.media3.extension.FfmpegVideoRenderer",
            "com.android.jellyfin.media3.ffmpeg.FfmpegVideoRenderer",
    };

    private FfmpegExtension() {
    }

    /**
     * DefaultRenderersFactory'e FFmpeg extension renderer'larini
     * ekler. Kütüphane mevcut degilse log'lar ama hata vermez.
     *
     * @param factory Varsayilan renderers factory (cagri tarafindan olusturulmus)
     * @param c       Context
     * @param video   Video renderer'inda FFmpeg kullanilsin mi (canli TV'de false, donma riski)
     */
    public static DefaultRenderersFactory attach(
            DefaultRenderersFactory factory, Context c, boolean video) {
        try {
            List<Renderer> extra = new ArrayList<Renderer>();

            if (video) {
                extra.addAll(instantiate(c, VIDEO_RENDERER_NAMES, "video"));
            }
            // Ses her zaman eklenir: AC3/EAC3/DTS/TrueHD/MP2 donanimda yok
            extra.addAll(instantiate(c, AUDIO_RENDERER_NAMES, "audio"));

            if (extra.isEmpty()) {
                Log.w(TAG, "FFmpeg extension siniflari bulunamadi; donanim decodeclar kullanilacak");
                return factory;
            }

            final List<Renderer> extension = extra;
            factory.setExtensionRenderersFactory(new DefaultRenderersFactory.ExtensionRenderersFactory() {
                @Override
                public List<Renderer> createRenderers(Context context, Object... args) {
                    return extension;
                }
            });
            Log.i(TAG, "FFmpeg extension baglandi (" + extension.size() + " renderer)");
            return factory;
        } catch (Throwable t) {
            // Extension baglanamazsa oynatici donamimla calismaya devam etmelidir
            Log.w(TAG, "FFmpeg extension baglanamadi", t);
            return factory;
        }
    }

    /** Aday sinif adlari arasindan ilki ilk bulunana kadar dener, Renderer olusturur */
    private static List<Renderer> instantiate(Context c, String[] names, String role) {
        for (String name : names) {
            try {
                Class<?> clz = Class.forName(name);
                Object inst = newInstance(clz, c);
                if (inst instanceof Renderer) {
                    Log.i(TAG, role + " renderer olusturuldu: " + name);
                    List<Renderer> one = new ArrayList<Renderer>(1);
                    one.add((Renderer) inst);
                    return one;
                } else {
                    Log.w(TAG, name + " Renderer degil: " + inst.getClass().getName());
                }
            } catch (ClassNotFoundException ignored) {
                // Bir sonraki adayi dene
            } catch (Throwable t) {
                Log.w(TAG, role + " renderer olusturulamadı: " + name, t);
            }
        }
        return new ArrayList<Renderer>();
    }

    /** Context parametreli constructor'u dener; yoksa paramsiz dener */
    private static Object newInstance(Class<?> clz, Context c) throws Exception {
        try {
            Constructor<?> ctor = clz.getConstructor(Context.class);
            return ctor.newInstance(c.getApplicationContext());
        } catch (NoSuchMethodException noCtor) {
            Constructor<?> ctor = clz.getConstructor();
            return ctor.newInstance();
        }
    }
}
