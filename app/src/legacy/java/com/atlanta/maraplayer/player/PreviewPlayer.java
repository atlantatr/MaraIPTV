package com.atlanta.maraplayer.player;

import android.content.Context;
import android.view.SurfaceView;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;

/** [Android 4.2 surumu] Canli TV listesindeki kucuk onizleme oynaticisi. */
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
