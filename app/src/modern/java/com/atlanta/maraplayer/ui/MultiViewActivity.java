package com.atlanta.maraplayer.ui;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import com.atlanta.maraplayer.R;
import com.atlanta.maraplayer.data.Models.PlayItem;
import com.atlanta.maraplayer.data.Repo;
import com.atlanta.maraplayer.player.PlayerFactory;
import com.atlanta.maraplayer.util.Async;
import com.atlanta.maraplayer.util.Ui;

import java.util.ArrayList;
import java.util.List;

/**
 * [Android 5.0 / 7.0] Coklu ekran: 2 veya 4 kanali ayni anda izleme.
 * Secili pencerenin sesi acik, digerleri sessiz. Cihazin donanim video cozucu sayisi sinirlidir;
 * zayif kutularda 4 pencerede bazi kanallar acilmayabilir.
 */
@OptIn(markerClass = UnstableApi.class)
public class MultiViewActivity extends BaseActivity {

    private static class Slot {
        View root;
        SurfaceView surface;
        TextView label, audio;
        ProgressBar loading;
        ExoPlayer player;
        int channel = -1;
    }

    private List<PlayItem> queue;
    private final List<Slot> slots = new ArrayList<>();
    private int count = 4;
    private int focused;
    private int[] channels;

    private final Runnable hideHint = new Runnable() {
        @Override
        public void run() {
            View h = findViewById(R.id.hint);
            if (h != null) h.setVisibility(View.GONE);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (sessionLost) return;
        queue = new ArrayList<>(Repo.playQueue);
        if (queue.isEmpty()) {
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_multiview);
        int start = getIntent().getIntExtra(Play.EXTRA_INDEX, 0);
        channels = new int[4];
        for (int i = 0; i < 4; i++) channels[i] = (start + i) % queue.size();
        buildSlots();
        Async.MAIN.postDelayed(hideHint, 6000);
    }

    private void buildSlots() {
        releaseAll();
        LinearLayout r1 = v(R.id.row1), r2 = v(R.id.row2);
        r1.removeAllViews();
        r2.removeAllViews();
        r2.setVisibility(count == 4 ? View.VISIBLE : View.GONE);
        slots.clear();
        LayoutInflater inf = LayoutInflater.from(this);
        for (int i = 0; i < count; i++) {
            LinearLayout row = (count == 4 && i >= 2) ? r2 : r1;
            View v = inf.inflate(R.layout.multiview_slot, row, false);
            row.addView(v);
            final Slot s = new Slot();
            s.root = v;
            s.surface = v.findViewById(R.id.slotSurface);
            s.label = v.findViewById(R.id.slotLabel);
            s.audio = v.findViewById(R.id.slotAudio);
            s.loading = v.findViewById(R.id.slotLoading);
            final int idx = i;
            v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View view, boolean has) {
                    if (has) setFocused(idx);
                }
            });
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    chooseChannel(idx);
                }
            });
            slots.add(s);
        }
        if (focused >= count) focused = 0;
        for (int i = 0; i < count; i++) playIn(i, channels[i]);
        slots.get(focused).root.requestFocus();
        setFocused(focused);
    }

    private void playIn(int slotIdx, int channel) {
        final Slot s = slots.get(slotIdx);
        if (s.player == null) {
            s.player = PlayerFactory.create(this, true);
            s.player.setVideoSurfaceView(s.surface);
            s.player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    s.loading.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    s.loading.setVisibility(View.GONE);
                    s.label.setText(queue.get(s.channel).title + "  (acilamadi)");
                }
            });
        } else {
            s.player.stop();
        }
        s.channel = channel;
        channels[slotIdx] = channel;
        PlayItem p = queue.get(channel);
        s.label.setText(p.num + "  " + p.title);
        s.player.setMediaSource(PlayerFactory.source(this, p.url));
        s.player.setVolume(slotIdx == focused ? 1f : 0f);
        s.player.setPlayWhenReady(true);
        s.player.prepare();
    }

    private void setFocused(int idx) {
        focused = idx;
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            if (s.player != null) s.player.setVolume(i == idx ? 1f : 0f);
            s.audio.setVisibility(i == idx ? View.VISIBLE : View.GONE);
        }
    }

    private void chooseChannel(final int slotIdx) {
        String[] names = new String[queue.size()];
        for (int i = 0; i < names.length; i++) names[i] = queue.get(i).num + "  " + queue.get(i).title;
        Ui.choose(this, "Pencere " + (slotIdx + 1) + " - kanal sec", names, channels[slotIdx], new Ui.Choice() {
            @Override
            public void on(int which) {
                playIn(slotIdx, which);
                slots.get(slotIdx).root.requestFocus();
            }
        });
    }

    private void menu() {
        final String[] opts = {count == 4 ? "2 pencereye gec" : "4 pencereye gec", "Secili kanali tam ekran ac", "Kanal degistir"};
        Ui.choose(this, "Coklu ekran", opts, -1, new Ui.Choice() {
            @Override
            public void on(int which) {
                if (which == 0) {
                    count = count == 4 ? 2 : 4;
                    buildSlots();
                } else if (which == 1) {
                    // alttaki tam ekran oynaticiya secili kanali bildir
                    releaseAll();
                    setResult(RESULT_OK, new android.content.Intent().putExtra(Play.EXTRA_INDEX, channels[focused]));
                    finish();
                } else {
                    chooseChannel(focused);
                }
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) {
            menu();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            int d = keyCode == KeyEvent.KEYCODE_CHANNEL_UP ? 1 : -1;
            int n = queue.size();
            playIn(focused, ((channels[focused] + d) % n + n) % n);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void releaseAll() {
        for (Slot s : slots) {
            if (s.player != null) {
                s.player.release();
                s.player = null;
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (queue == null || slots.isEmpty()) return;
        for (int i = 0; i < slots.size(); i++) if (slots.get(i).player == null) playIn(i, channels[i]);
        setFocused(focused);
    }

    @Override
    protected void onStop() {
        super.onStop();
        releaseAll();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Async.MAIN.removeCallbacks(hideHint);
        releaseAll();
    }
}
