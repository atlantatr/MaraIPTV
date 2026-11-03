package com.atlanta.maraplayer.player;

import android.os.Handler;
import android.os.Looper;

import androidx.media3.exoplayer.ExoPlayer;

/**
 * [Android 5.0 / 7.0] Onizleme oynaticisini tam ekrana devreder.
 *
 * Kucuk onizlemeden tam ekrana gecerken yeni bir oynatici kurmak, canli TS/HLS
 * yayininda yayinin canli kenarindan (yani ileriden) baslamasina ve kisa bir ses
 * kaymasina yol aciyordu. Bunun yerine ayni ExoPlayer ornegi tam ekran ekrana
 * tasinir: ses hic kesilmez, konum korunur, yayin geri sarmaz.
 */
public final class PreviewHandoff {

    private static final long EXPIRE_MS = 10000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static ExoPlayer player;
    private static String channelId;

    private static final Runnable EXPIRE = new Runnable() {
        @Override
        public void run() {
            // Tam ekran acilmadi/donmedi: oynaticiyi birak, sizinti kalmasin.
            discard();
        }
    };

    private PreviewHandoff() {
    }

    /** Onizleme oynaticisini devret. */
    public static void put(ExoPlayer p, String id) {
        discard();
        player = p;
        channelId = id;
        MAIN.removeCallbacks(EXPIRE);
        MAIN.postDelayed(EXPIRE, EXPIRE_MS);
    }

    /**
     * Tam ekran ekraninda devralinir. Kanal eslesmiyorsa oynatici birakilir ve
     * null doner (yeni oynatici kurulur).
     */
    public static ExoPlayer take(String id) {
        if (player == null || id == null || !id.equals(channelId)) {
            discard();
            return null;
        }
        ExoPlayer p = player;
        player = null;
        channelId = null;
        MAIN.removeCallbacks(EXPIRE);
        return p;
    }

    /** Devredilen oynatici varsa birak. */
    public static void discard() {
        MAIN.removeCallbacks(EXPIRE);
        ExoPlayer p = player;
        player = null;
        channelId = null;
        if (p != null) {
            try {
                p.release();
            } catch (Exception ignored) {
            }
        }
    }
}
