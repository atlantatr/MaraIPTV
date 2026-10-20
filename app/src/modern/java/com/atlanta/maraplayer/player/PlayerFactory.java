package com.atlanta.maraplayer.player;

import android.content.Context;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;

import com.atlanta.maraplayer.data.Prefs;
import com.atlanta.maraplayer.net.Http;

/**
 * [Android 5.0 / 7.0 surumleri] AndroidX Media3 oynatici.
 * FFmpeg eklentisi yuklu ise (gradle.properties: mara.ffmpeg=true) cihazin cozemedigi
 * AC3 / EAC3 / DTS / TrueHD / MP2 sesleri yazilimla cozulur.
 */
@OptIn(markerClass = UnstableApi.class)
public final class PlayerFactory {

    private PlayerFactory() {
    }

    public static ExoPlayer create(Context c, boolean live) {
        // Canli TV: FFmpeg extension'u KAPALI (donanım MediaCodec) -> donma
        // engellenir. VOD (film/dizi): AC-3/EAC-3/DTS/TrueHD donanım MediaCodec'ta
        // cozulmedigi icin FFmpeg yazılım decoder'i aktif -> ses gelir.
        // (Media3 1.5.0'ta EXTENSION_RENDERER_MODE_FALLBACK sabiti yok; ON/OFF)
        int mode = live
                ? DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                : DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON;
        DefaultRenderersFactory rf = new DefaultRenderersFactory(c)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(mode);

        ExoPlayer.Builder builder = new ExoPlayer.Builder(c, rf);

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

        return builder
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
            case "fhd":
                min = 20000; max = 60000; start = 3000; rebuffer = 6000;
                break;
            default:
                // "normal": bol tampon, donma onleme icin
                min = 20000; max = 60000; start = 2000; rebuffer = 4000;
                break;
        }
        if (live) {
            // Canlı TV: tamponu 45s ile sinirle, baslangic eşiği 1.5s
            max = Math.min(max, 45000);
            min = Math.min(min, max);
            start = Math.min(start, 1500);
            rebuffer = Math.min(rebuffer, 3000);
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
