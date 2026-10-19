package com.atlanta.maraplayer.player;

import android.content.Context;

import com.atlanta.maraplayer.data.Prefs;
import com.atlanta.maraplayer.net.Http;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.extractor.ts.DefaultTsPayloadReaderFactory;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;

/** [Android 4.2 surumu] ExoPlayer 2.19.1 olusturma ve kaynak secimi. */
public final class PlayerFactory {

    private PlayerFactory() {
    }

    private static final boolean ffmpegDecoderLoaded;

    static {
        // ExoPlayer 2.19.1 icin extension-ffmpeg (AC3/EAC3/DTS/TrueHD/MP2 yazilim cozeni)
        boolean ok = false;
        try {
            // ExoPlayer 2.19.1 DefaultRenderersFactory, extension-ffmpeg
            // classpath'te varligrini Class.forName ile otomatik kontrol eder.
            Class.forName("com.google.android.exoplayer2.ext.ffmpeg.FfmpegDecoder");
            ok = true;
        } catch (Throwable ignored) {
            // extension-ffmpeg paketi yok -> MediaCodec fallback yeter
        }
        ffmpegDecoderLoaded = ok;
    }

    public static ExoPlayer create(Context c, boolean live) {
        DefaultRenderersFactory rf = new DefaultRenderersFactory(c)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(ffmpegDecoderLoaded
                        ? DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                        : DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);

        int[] b = bufferMs(live);
        DefaultLoadControl lc = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(b[0], b[1], b[2], b[3])
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        DefaultTrackSelector ts = new DefaultTrackSelector(c);
        ts.setParameters(ts.buildUponParameters()
                .setExceedRendererCapabilitiesIfNecessary(true)
                .setExceedVideoConstraintsIfNecessary(true)
                .setPreferredAudioLanguage("tr")
                .setPreferredTextLanguage("tr"));

        return new ExoPlayer.Builder(c, rf)
                .setLoadControl(lc)
                .setTrackSelector(ts)
                .build();
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
            default:
                min = 15000; max = 50000; start = 2000; rebuffer = 4000;
        }
        if (live) {
            max = Math.min(max, 30000);
            min = Math.min(min, max);
        }
        return new int[]{min, max, start, rebuffer};
    }

    public static MediaSource source(Context c, String url) {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(Http.userAgent())
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000);
        // DefaultDataSource: http(s) + yerel dosya (kayitlar) destegi
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
        DefaultExtractorsFactory ef = new DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                        | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
                .setConstantBitrateSeekingEnabled(true);
        return new ProgressiveMediaSource.Factory(ds, ef).createMediaSource(item);
    }
}
