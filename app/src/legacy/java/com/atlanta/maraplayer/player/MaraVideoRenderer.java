package com.atlanta.maraplayer.player;

import android.content.Context;
import android.os.Handler;

import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer;
import com.google.android.exoplayer2.video.VideoRendererEventListener;

/**
 * [Android 4.2 surumu] Video renderer.
 *
 * <p>Android 4.2 (API 17) cihazlarda ExoPlayer'in workaround tablosu
 * {@code codecNeedsFlushWorkaround = true} sonucunu verir. Bu yuzden codec'i "flush" etmek
 * aslinda codec'i tamamen kapatip yeniden acmak demektir (release + init ~100-150 ms, ardindan
 * yeni keyframe beklenir).
 *
 * <p>Varsayilan {@code shouldDropBuffersToKeyframe} mantigi, kareler 500 ms'den fazla geciktiginde
 * tamponu bir sonraki keyframe'e atlar ve bunun icin codec'i flush eder. API 17'de bu flush
 * codec'i yeniden kurdugu icin su kisir dongu olusur:
 *
 * <pre>
 *   kareler gecikir -&gt; keyframe'e atla -&gt; codec yeniden kurulur (150 ms) -&gt;
 *   keyframe beklenir -&gt; daha da gecikir -&gt; tekrar keyframe'e atla ...
 * </pre>
 *
 * <p>Olcum (192.168.1.139, 1080p canli TS): saniyede ~1 codec release+init, saniyede ~5-10 kare
 * render, yuksek drop sayisi, surekli donma. Bu sinif keyframe'e atlamayi kapatir; geciken kareler
 * tek tek dusurulur, codec korunur. Ayni olcumle: 1 codec init, 0 drop, ~25 kare/sn.
 */
final class MaraVideoRenderer extends MediaCodecVideoRenderer {

    MaraVideoRenderer(Context context, MediaCodecSelector selector, long allowedJoiningTimeMs,
                      boolean enableDecoderFallback, Handler eventHandler,
                      VideoRendererEventListener eventListener, int maxDroppedFramesToNotify) {
        super(context, selector, allowedJoiningTimeMs, enableDecoderFallback, eventHandler,
                eventListener, maxDroppedFramesToNotify);
    }

    @Override
    protected boolean shouldDropBuffersToKeyframe(long earlyUs, long elapsedRealtimeUs,
                                                 boolean isLastBuffer) {
        // Codec'i flush etmek API 17'de yeniden kuruluma yol acar: keyframe'e atlama yapilmaz.
        // Geciken kareler tek tek dusurulur, codec calismaya devam eder.
        return false;
    }
}
