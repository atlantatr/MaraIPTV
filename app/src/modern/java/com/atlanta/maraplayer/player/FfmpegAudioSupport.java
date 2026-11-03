package com.atlanta.maraplayer.player;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ForwardingRenderer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.metadata.MetadataOutput;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Film / dizi (VOD) sesinde AC-3, E-AC-3, DTS ve TrueHD icin FFmpeg yazilim
 * cozucusunu devreye alir.
 *
 * Neden gerekli: Bazi Amlogic / Hisilicon kutularda MediaCodec "audio/eac3"
 * destekli gorunur (ORNEK: OMX.google.eac3.decoder) ve ExoPlayer donanim
 * cozucuyu secer; ancak kutu sesi PCM'e ceviremedigi icin goruntu oynar,
 * ses gelmez. Hata da olusmadigi icin uygulama bunu anlayamaz.
 *
 * Cozum: sorunlu codec'lerde donanim MediaCodec renderer'ina "desteklemiyor"
 * dedirtiyoruz; FFmpeg renderer'i ise SADECE bu codec'lerde "destekliyor"
 * diyor. Boylece:
 *   - E-AC-3 / AC-3 / DTS / TrueHD -> FFmpeg yazilim decoder (ses gelir)
 *   - AAC / MP3 / MP2 / Opus ...    -> donanim MediaCodec (CPU yuku artmaz)
 *
 * FFmpeg kutuphanesi derlemeye dahil degilse (mara.ffmpeg=false) veya sinif
 * adlari degisirse, sessizce donanim davranisina doner; uygulama bozulmaz.
 */
@OptIn(markerClass = UnstableApi.class)
public final class FfmpegAudioSupport {

    private static final String TAG = "MaraFfmpeg";

    /** FFmpeg yazilim decoder'a yonlendirilecek ses tipleri. */
    private static final Set<String> SOFTWARE_MIMES = new HashSet<String>(Arrays.asList(
            "audio/ac3",
            "audio/eac3",
            "audio/eac3-joc",
            "audio/vnd.dts",
            "audio/vnd.dts.hd",
            "audio/true-hd"));

    private static Boolean available;

    private FfmpegAudioSupport() {
    }

    /**
     * VOD icin renderer fabrikasini sarar. FFmpeg yoksa {@code base} aynen doner.
     */
    public static RenderersFactory wrap(final DefaultRenderersFactory base, final Context c) {
        if (!isAvailable(c)) return base;
        final Context app = c.getApplicationContext();
        return new RenderersFactory() {
            @Override
            public Renderer[] createRenderers(Handler handler,
                                              VideoRendererEventListener videoListener,
                                              AudioRendererEventListener audioListener,
                                              TextOutput textOutput,
                                              MetadataOutput metadataOutput) {
                Renderer[] baseRenderers = base.createRenderers(handler, videoListener,
                        audioListener, textOutput, metadataOutput);
                Renderer ffmpeg = createFfmpegRenderer(app, handler, audioListener);
                if (ffmpeg == null) return baseRenderers;

                Renderer[] out = new Renderer[baseRenderers.length + 1];
                int n = 0;
                out[n++] = new MimeFilterRenderer(ffmpeg, true);
                for (Renderer r : baseRenderers) {
                    if (r.getTrackType() == C.TRACK_TYPE_AUDIO) {
                        out[n++] = new MimeFilterRenderer(r, false);
                    } else {
                        out[n++] = r;
                    }
                }
                Log.i(TAG, "FFmpeg ses aktif: AC-3/E-AC-3/DTS/TrueHD yazilimla cozulecek");
                return out;
            }
        };
    }

    private static boolean isAvailable(Context c) {
        if (available != null) return available;
        try {
            Class<?> lib = Class.forName("androidx.media3.decoder.ffmpeg.FfmpegLibrary",
                    false, c.getClassLoader());
            Object ok = lib.getMethod("isAvailable").invoke(null);
            available = ok instanceof Boolean && (Boolean) ok;
            if (available) {
                Object ver = lib.getMethod("getVersion").invoke(null);
                Log.i(TAG, "FFmpeg native hazir: " + ver);
            } else {
                Log.w(TAG, "FFmpeg native yuklenemedi; donanim decoder kullanilacak");
            }
        } catch (Throwable t) {
            available = false;
            Log.w(TAG, "FFmpeg kutuphanesi yok; donanim decoder kullanilacak", t);
        }
        return available;
    }

    private static Renderer createFfmpegRenderer(Context c, Handler handler,
                                                 AudioRendererEventListener listener) {
        try {
            Class<?> cls = Class.forName("androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer",
                    false, c.getClassLoader());
            AudioSink sink = new DefaultAudioSink.Builder(c).build();
            Constructor<?> ctor = cls.getConstructor(Handler.class,
                    AudioRendererEventListener.class, AudioSink.class);
            return (Renderer) ctor.newInstance(handler, listener, sink);
        } catch (Throwable t) {
            Log.w(TAG, "FFmpeg audio renderer olusturulamadi; donanim decoder kullanilacak", t);
            return null;
        }
    }

    /**
     * Renderer'i oldugu gibi kullanir; yalnizca "hangi ses tiplerini
     * destekledigi" cevabini filtreler. Boylece ayni anda iki renderer
     * ayni codec'i sahiplenmez.
     */
    private static final class MimeFilterRenderer extends ForwardingRenderer {

        private final boolean software;
        private final RendererCapabilities capabilities;

        MimeFilterRenderer(Renderer renderer, boolean software) {
            super(renderer);
            this.software = software;
            final RendererCapabilities delegate = renderer.getCapabilities();
            this.capabilities = new RendererCapabilities() {
                @Override
                public String getName() {
                    return delegate.getName();
                }

                @Override
                public int getTrackType() {
                    return delegate.getTrackType();
                }

                @Override
                public int supportsFormat(Format format) throws ExoPlaybackException {
                    boolean listed = SOFTWARE_MIMES.contains(format.sampleMimeType);
                    if (software != listed) {
                        return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
                    }
                    return delegate.supportsFormat(format);
                }

                @Override
                public int supportsMixedMimeTypeAdaptation() throws ExoPlaybackException {
                    return delegate.supportsMixedMimeTypeAdaptation();
                }

                @Override
                public void setListener(Listener listener) {
                    delegate.setListener(listener);
                }

                @Override
                public void clearListener() {
                    delegate.clearListener();
                }
            };
        }

        @Override
        public RendererCapabilities getCapabilities() {
            return capabilities;
        }
    }
}
