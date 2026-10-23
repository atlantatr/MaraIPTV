package com.atlanta.maraplayer.player;

import android.content.Context;
import android.util.Log;

import androidx.media3.exoplayer.Renderer;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Jellyfin media3-ffmpeg-decoder yutularini (ac3, eac3, dts, truehd, mp2...)
 * Media3 renderer dizisine ekler.
 *
 * Neden reflection: build her zaman derlensin diye (mara.ffmpeg=false veya
 * kutyaplan mevcut degilse) derleme zamani icinde bu siniflar compile classpath'ine
 * girmez; sadece calisma zamaninda classpath'te varlarsa kullanilir.
 *
 * Neden varsayilanlarin ONUNE ekliyoruz:
 * Media3'un DefaultRenderersFactory kendi donanim (MediaCodec) renderer'larini
 * once olusturur. EAC-3 gibi codec'ler donanimdaki MediaCodec'te yoksa,
 * Media3 "track supported" diye bakip donanimi dener, basarisiz olur,
 * ve error fırlatabilir. Yazilim yutucusunu RENDERER DIZISININ BASINA
 * koyarsak, EAC-3 track'i yazilim yutucusuna dogrudan girer, donanimi beklemez.
 */
public final class FfmpegExtension {

    private static final String TAG = "MaraFfmpeg";
    private static boolean logged;

    /**
     * Jellyfin kutyaplaninin olasi sinif adlari. Birincisi bulunmazsa
     * digeri deneriz; hepsi bulunmazsa log'a yazar ve sessizce doneriz.
     */
    private static final String[] AUDIO_RENDERER_NAMES = {
            "com.android.jellyfin.media3.extension.FfmpegAudioRenderer",
            "com.android.jellyfin.media3.ffmpeg.FfmpegAudioRenderer",
    };
    private static final String[] VIDEO_RENDERER_NAMES = {
            "com.android.jellyfin.media3.extension.FfmpegVideoRenderer",
            "com.android.jellyfin.media3.ffmpeg.FfmpegVideoRenderer",
    };
    private static final String[] LOADER_NAMES = {
            "com.android.jellyfin.media3.extension.FfmpegLibraryLoader",
            "com.android.jellyfin.media3.ffmpeg.FfmpegLibraryLoader",
    };

    private FfmpegExtension() {
    }

    /**
     * @param c     context
     * @param audio audio renderer'i ekle (ac3/eac3/dts/truehd/mp2...)
     * @param video video renderer'i ekle (hevc, av1, vp9, ...)
     * @return eklenen renderer'lari iceren liste (dizinin basina konmalidir).
     *         Kutyaplan yoksa bo liste doner, cagri taraf donanim yutucuyla
     *         devam eder.
     */
    public static List<Renderer> create(Context c, boolean audio, boolean video) {
        List<Renderer> out = new ArrayList<Renderer>(4);
        if (audio) out.addAll(find(c, AUDIO_RENDERER_NAMES, "audio"));
        if (video) out.addAll(find(c, VIDEO_RENDERER_NAMES, "video"));
        if (!out.isEmpty()) {
            ensureNativeLibraryLoaded(c);
            if (!logged) {
                logged = true;
                Log.i(TAG, "FFmpeg yutucu(leri) olusturuldu: " + out.size());
            }
        } else if (!logged) {
            logged = true;
            Log.w(TAG, "Jellyfin FFmpeg kutyaplanı bulunamadi; donanim yutucuyla devam");
        }
        return out;
    }

    private static List<Renderer> find(Context c, String[] names, String role) {
        List<Renderer> found = new ArrayList<Renderer>(1);
        for (String name : names) {
            try {
                Class<?> cls = Class.forName(name, false, c.getClassLoader());
                Object inst = instantiate(cls, c);
                if (inst instanceof Renderer) {
                    found.add((Renderer) inst);
                    if (!logged) Log.i(TAG, role + " renderer: " + name);
                    return found;
                }
                if (!logged) Log.w(TAG, role + " renderer degil (Renderer interface'i eksik): " + name);
                return new ArrayList<Renderer>();
            } catch (ClassNotFoundException ignored) {
                // Next candidate
            } catch (Throwable t) {
                if (!logged) Log.w(TAG, role + " renderer olusturulamadi: " + name, t);
                return new ArrayList<Renderer>();
            }
        }
        return found;
    }

    private static Object instantiate(Class<?> cls, Context c) throws Exception {
        // Tercih edilecek constructor'lari sirayla dene: (Context), (Object), ()
        try {
            Constructor<?> ctor = cls.getConstructor(Context.class);
            return ctor.newInstance(c.getApplicationContext());
        } catch (NoSuchMethodException ignored) {
        }
        try {
            Constructor<?> ctor = cls.getConstructor(Object.class);
            return ctor.newInstance((Object) c.getApplicationContext());
        } catch (NoSuchMethodException ignored) {
        }
        try {
            Constructor<?> ctor = cls.getConstructor();
            return ctor.newInstance();
        } catch (NoSuchMethodException ignored) {
        }
        // En son deneme: tek argumanli constructor (Context veya Object olarak)
        Constructor<?>[] ctors = cls.getConstructors();
        if (ctors.length > 0) {
            Constructor<?> ctor = ctors[0];
            if (ctor.getParameterCount() == 0) return ctor.newInstance();
            Object[] args = new Object[ctor.getParameterCount()];
            for (int i = 0; i < args.length; i++) args[i] = c.getApplicationContext();
            return ctor.newInstance(args);
        }
        throw new NoSuchMethodException("constructor yok: " + cls.getName());
    }

    /**
     * Jellyfin FFmpeg kutyaplaninin native .so dosyalarini yukler
     * (libffmpeg, libjellyfinffmpeg...). Yuklenemezse hata loglanir,
     * renderer yine de denenir (belki static initializer kendisi yukler).
     */
    private static void ensureNativeLibraryLoaded(Context c) {
        for (String loader : LOADER_NAMES) {
            try {
                Class<?> cls = Class.forName(loader, false, c.getClassLoader());
                for (Method m : cls.getDeclaredMethods()) {
                    if (!Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getParameterCount() != 1) continue;
                    if (m.getParameterTypes()[0] == Context.class) {
                        m.setAccessible(true);
                        try {
                            m.invoke(null, c.getApplicationContext());
                            Log.i(TAG, "Native kutyaplan yuklendi: " + loader + "." + m.getName());
                            return;
                        } catch (Throwable t) {
                            Log.w(TAG, "Native kutyaplan yuklenemedi: " + loader + "." + m.getName(), t);
                            return;
                        }
                    }
                }
                return;
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable t) {
                Log.w(TAG, "loader hatasi: " + loader, t);
                return;
            }
        }
        Log.w(TAG, "loader sinifi bulunamadi; native kutyaplan statik initializer'a kaliyor");
    }
}
