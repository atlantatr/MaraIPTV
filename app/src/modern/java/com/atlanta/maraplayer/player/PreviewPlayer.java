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

    public PreviewPlayer(Context c, SurfaceView surface, final Listener l) {
        player = PlayerFactory.create(c, true);
        player.setVideoSurfaceView(surface);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                l.onBuffering(state == Player.STATE_BUFFERING);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                l.onError(error.getErrorCodeName());
            }
        });
    }

    public void play(Context c, String url) {
        if (player == null) return;
        player.setMediaSource(PlayerFactory.source(c, url));
        player.setPlayWhenReady(true);
        player.prepare();
    }

    public void release() {
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
