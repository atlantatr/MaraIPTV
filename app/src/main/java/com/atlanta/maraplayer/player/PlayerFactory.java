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
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;

/** ExoPlayer olusturma ve kaynak (MediaSource) secimi. */
public final class PlayerFactory {

    private PlayerFactory() {
    }

    public static ExoPlayer create(Context c, boolean live) {
        DefaultRenderersFactory rf = new DefaultRenderersFactory(c)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);

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
            // canli yayinda asiri tampon gecikme yaratir
            max = Math.min(max, 30000);
            min = Math.min(min, max);
        }
        DefaultLoadControl lc = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(min, max, start, rebuffer)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();

        DefaultTrackSelector ts = new DefaultTrackSelector(c);
        ts.setParameters(ts.buildUponParameters()
                .setPreferredAudioLanguage("tr")
                .setPreferredTextLanguage("tr")
                .setExceedRendererCapabilitiesIfNecessary(true)
                .setExceedVideoConstraintsIfNecessary(true));

        return new ExoPlayer.Builder(c, rf)
                .setLoadControl(lc)
                .setTrackSelector(ts)
                .build();
    }

    public static MediaSource source(String url) {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(Http.userAgent())
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000);
        MediaItem item = MediaItem.fromUri(url);
        String lower = url.toLowerCase();
        int q = lower.indexOf('?');
        String path = q > 0 ? lower.substring(0, q) : lower;
        if (path.endsWith(".m3u8") || path.endsWith(".m3u")) {
            return new HlsMediaSource.Factory(http)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(item);
        }
        DefaultExtractorsFactory ef = new DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
                        | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
                .setConstantBitrateSeekingEnabled(true);
        return new ProgressiveMediaSource.Factory(http, ef).createMediaSource(item);
    }
}
