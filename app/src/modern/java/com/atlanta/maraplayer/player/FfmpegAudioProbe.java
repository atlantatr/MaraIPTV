package com.atlanta.maraplayer.player;

import android.content.Context;
import android.util.Log;

import androidx.media3.exoplayer.DefaultRenderersFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Jellyfin media3-ffmpeg-decoder kütüphanesinin FFmpeg audio renderer'ını
 * DefaultRenderersFactory'e compile-time bağımlılığı OLMADAN (saf reflection)
 * bağlar. Amaç:
 *
 *  - Build asla bozulmaz: hiçbir yeni compile-time API çağrısı yok;
 *    tüm çağrılar reflection + Proxy ile yapılır.
 *  - Kütüphane classpath'te yoksa veya sınıf adları değişmişse, çalışmaya
 *    devam edilir (donanım decoder'lar) ama MaraFfmpeg tag'inde tam teşhis
 *    loglanır — böylece tek bir logcat'ten eksik parçayı netleştiririz.
 *
 * Neden manuel bağlamamız gerekiyor: Media3'ün otomatik extension keşfi
 * (EXTENSION_RENDERER_MODE_ON) Google'ın "com.google.android.exoplayer2.ffmpeg"
 * package'ini arar; Jellyfin fork'u aynı rolleri farklı package'te sunar,
 * keşif sessizce boş döner ve EAC-3/AC-3 donanıma düşer.
 */
public final class FfmpegAudioProbe {

    private static final String TAG = "MaraFfmpeg";

    private static final String[] LOADER_CLASS_CANDIDATES = {
            "com.android.jellyfin.media3.extension.FfmpegLibraryLoader",
            "com.android.jellyfin.media3.extension.FfmpegExtensionLoader",
            "org.jellyfin.media3.extension.FfmpegLibraryLoader",
            "com.google.android.exoplayer2.ffmpeg.FfmpegLibraryLoader",
    };

    private static final String[] AUDIO_RENDERER_CLASS_CANDIDATES = {
            "com.android.jellyfin.media3.extension.FfmpegAudioRenderer",
            "com.android.jellyfin.media3.ffmpeg.FfmpegAudioRenderer",
            "org.jellyfin.media3.extension.FfmpegAudioRenderer",
            "com.google.android.exoplayer2.ffmpeg.FfmpegAudioRenderer",
    };

    private static boolean probed;

    private FfmpegAudioProbe() {
    }

    /**
     * VOD player'ı kurarken çağrılır. FFmpeg audio renderer'ını factory'e
     * eklemeye çalışır; her durumda donanıma düşen davranışa geri dönebilir.
     */
    public static void attach(DefaultRenderersFactory factory, Context c) {
        if (probed) return;
        probed = true;

        Context appCtx = c.getApplicationContext();

        // 1) Native kütüphane yükleyici (varsa)
        Class<?> loaderClass = firstExisting(LOADER_CLASS_CANDIDATES);
        logCandidates("loader", LOADER_CLASS_CANDIDATES, loaderClass);
        if (loaderClass != null) {
            invokeStaticSingleContextMethod(loaderClass, appCtx, "loadNativeLibraries");
            invokeStaticSingleContextMethod(loaderClass, appCtx, "loadLibraries");
            invokeStaticSingleContextMethod(loaderClass, appCtx, "initialize");
        }

        // 2) Audio renderer instance
        Object audioRenderer = createFirst(
                AUDIO_RENDERER_CLASS_CANDIDATES, appCtx, "audio");
        if (audioRenderer == null) {
            Log.w(TAG, "Jellyfin FFmpeg audio renderer classpath'te bulunamadi; "
                    + "EAC-3/AC-3 donanim decoder'a kaliyor. Yukaridaki 'candidates' loguna bak.");
            return;
        }

        // 3) Factory'e extension renderers factory olarak ekle (reflection).
        //    DefaultRenderersFactory'de setExtensionRenderersFactory(ExtensionRenderersFactory)
        //    imzasi surumlar arasinda degisiklik gosterdi; bu yuzden method
        //    parametresinin tipini runtime'da okuyup Proxy ile dolduruyoruz.
        try {
            Method setter = findSetter(factory.getClass());
            if (setter == null) {
                Log.w(TAG, "DefaultRenderersFactory.setExtensionRenderersFactory methodu yok; "
                        + "otomatik kesfe emanet kalindi.");
                return;
            }
            Class<?> paramType = setter.getParameterTypes()[0];
            Object proxy = Proxy.newProxyInstance(
                    paramType.getClassLoader(),
                    new Class<?>[]{paramType},
                    new ExtensionRenderersHandler(audioRenderer));
            setter.invoke(factory, proxy);
            Log.i(TAG, "Jellyfin FFmpeg audio renderer factory'e BAHLANDI "
                    + "(" + audioRenderer.getClass().getName() + ")");
        } catch (Throwable t) {
            Log.w(TAG, "FFmpeg audio factory baglama hatasi; donanim decoder'a donus", t);
        }
    }

    // ---- yardımcı metotlar -------------------------------------------------

    private static Class<?> firstExisting(String[] names) {
        for (String n : names) {
            try {
                return Class.forName(n, false, FfmpegAudioProbe.class.getClassLoader());
            } catch (ClassNotFoundException ignored) {
            }
        }
        return null;
    }

    private static void logCandidates(String role, String[] names, Class<?> found) {
        StringBuilder sb = new StringBuilder("candidates(").append(role).append("):");
        for (String n : names) {
            boolean ok = n.equals(found != null ? found.getName() : "");
            sb.append(' ').append(n).append(ok ? " [VARKEN BULUNDU]" : "");
        }
        Log.i(TAG, sb.toString());
    }

    private static void invokeStaticSingleContextMethod(Class<?> cls, Context ctx, String... names) {
        for (String name : names) {
            try {
                Method m = cls.getMethod(name, Context.class);
                m.invoke(null, ctx);
                Log.i(TAG, "native library load: " + cls.getName() + "." + name + " -> ok");
                return;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                Log.w(TAG, "native library load: " + name + " hatasi", t);
                return;
            }
        }
        Log.w(TAG, "loader sinifinda Context alani static load metodu bulunamadi: " + cls.getName());
    }

    private static Object createFirst(String[] names, Context ctx, String role) {
        for (String n : names) {
            try {
                Class<?> cls = Class.forName(n, false, FfmpegAudioProbe.class.getClassLoader());
                Object inst = instantiate(cls, ctx);
                if (inst != null) {
                    Log.i(TAG, role + " renderer instance olusturuldu: " + n);
                    return inst;
                }
            } catch (ClassNotFoundException ignored) {
            } catch (Throwable t) {
                Log.w(TAG, role + " renderer instance hatasi: " + n, t);
            }
        }
        return null;
    }

    private static Object instantiate(Class<?> cls, Context ctx) {
        try {
            try {
                Constructor<?> c = cls.getConstructor(Context.class);
                return c.newInstance(ctx);
            } catch (NoSuchMethodException e) {
                Constructor<?> c = cls.getConstructor();
                return c.newInstance();
            }
        } catch (Throwable t) {
            Log.w(TAG, "constructor uretilmedi: " + cls.getName(), t);
            return null;
        }
    }

    private static Method findSetter(Class<?> factoryClass) {
        for (Method m : factoryClass.getMethods()) {
            if (m.getName().equals("setExtensionRenderersFactory")
                    && m.getParameterCount() == 1) {
                return m;
            }
        }
        return null;
    }

    /**
     * ExtensionRenderersFactory (veya surume gore farkli isimli) interface'i
     * icin Proxy elcemani. createRenderers(...) cagrildiginda hazir renderer
     * listesini doner; diger metotlar no-op.
     */
    private static final class ExtensionRenderersHandler
            implements java.lang.reflect.InvocationHandler {
        private final Object renderer;

        ExtensionRenderersHandler(Object renderer) {
            this.renderer = renderer;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if (name.equals("createRenderers")
                    || name.equals("createExtensionRenderers")) {
                List<Object> list = new ArrayList<Object>(Arrays.asList(renderer));
                return list;
            }
            if (name.equals("toString")) return "MaraFfmpegExtensionProxy";
            if (name.equals("hashCode")) return System.identityHashCode(proxy);
            if (name.equals("equals")) return proxy == args[0];
            return null;
        }
    }
}
