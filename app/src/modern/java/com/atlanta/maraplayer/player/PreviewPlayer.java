package com.atlanta.maraplayer.player;

import android.content.Context;
import android.view.SurfaceView;

import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

/** [Android 5.0 / 7.0 surumleri] Canli TV listesindeki kucuk onizleme oynaticisi (Media3). */
public class PreviewPlayer {

    public interface Listener {
        void onBuffering(boolean buffering);

        void onError(String message);
    }

    private ExoPlayer player;
    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            if (l != null) l.onBuffering(state == Player.STATE_BUFFERING);
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            if (l != null) l.onError(error.getErrorCodeName());
        }
    };
    private Listener l;

    public PreviewPlayer(Context c, SurfaceView surface, final Listener l) {
        this.l = l;
        player = PlayerFactory.create(c, true);
        player.setVideoSurfaceView(surface);
        player.addListener(listener);
    }

    /** Onizlemenin su anki konumu (ms). Tam ekrana gecerken kaldigi yerden devam etmek icin. */
    public long getPosition() {
        return player != null ? player.getCurrentPosition() : 0;
    }

    /** Toplam sure (ms). Canli yayinda genellikle C.TIME_UNSET (negatif) doner. */
    public long getDuration() {
        return player != null ? player.getDuration() : 0;
    }

    /** Yayin ileri/geri sarilabiliyor mu? */
    public boolean isSeekable() {
        return player != null && player.isCurrentMediaItemSeekable();
    }

    public void play(Context c, String url) {
        if (player == null) return;
        player.setMediaSource(PlayerFactory.source(c, url));
        player.setPlayWhenReady(true);
        player.prepare();
    }

    /**
     * Tam ekrana gecerken oynaticiyi birakmadan devreder.
     *
     * @return true ise oynatici tam ekran ekranina tasindi (yayin kesintisiz surer)
     */
    public boolean detachForFullscreen(String channelId) {
        ExoPlayer p = player;
        player = null;
        if (p == null) return false;
        try {
            p.removeListener(listener);
        } catch (Exception ignored) {
        }
        PreviewHandoff.put(p, channelId);
        return true;
    }

    public void release() {
        l = null;
        if (player != null) {
            try {
                player.removeListener(listener);
            } catch (Exception ignored) {
            }
            player.release();
            player = null;
        }
    }
}
