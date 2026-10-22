package com.atlanta.maraplayer.ui;

import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.KeyEvent;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import com.atlanta.maraplayer.BuildConfig;
import com.atlanta.maraplayer.R;
import com.atlanta.maraplayer.data.Db;
import com.atlanta.maraplayer.data.Models;
import com.atlanta.maraplayer.data.Models.Epg;
import com.atlanta.maraplayer.data.Models.Item;
import com.atlanta.maraplayer.data.Models.PlayItem;
import com.atlanta.maraplayer.data.Prefs;
import com.atlanta.maraplayer.data.Repo;
import com.atlanta.maraplayer.data.XtreamApi;
import com.atlanta.maraplayer.player.PlayerFactory;
import com.atlanta.maraplayer.tv.TvHome;
import com.atlanta.maraplayer.util.Async;
import com.atlanta.maraplayer.util.ImageLoader;
import com.atlanta.maraplayer.util.Ui;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * [Android 5.0 / 7.0 surumleri - AndroidX Media3]
 * Tam ekran oynatici: canli (zapping, EPG), film, dizi, catch-up, kayit +
 * resim icinde resim (7.0), coklu ekran, oynatma hizi, uyku zamanlayicisi, FFmpeg ses.
 */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends BaseActivity {

    private static final int MAX_RETRY = 6;
    private static final String[] ASPECTS = {"Sigdir", "Doldur", "Yakinlastir", "16:9", "4:3"};
    private static final boolean PIP = "nougat".equals(BuildConfig.FLAVOR);
    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};

    private List<PlayItem> queue;
    private int index;
    private long startMs;
    private ExoPlayer player;
    private int aspect;
    private int videoW, videoH;
    private float pixelRatio = 1f;
    private int retry;
    private boolean errorShown;
    private boolean inPip;
    private float speed = 1f;
    private long sleepAt;

    private View videoBox, infoBar, controls, chPanel;
    private TextureView surface;
    private ProgressBar buffering, infoProgress, ctlProgress;
    private TextView subs, osd, numEntry, infoName, infoNow, infoNext, infoClock;
    private TextView ctlTitle, ctlSub, ctlPos, ctlDur, ctlState;
    private ImageView infoLogo;
    private ListView chList;
    private Adapters.RowAdapter chAdapter;
    private boolean audioDebugOnce = true;

    private final StringBuilder numBuf = new StringBuilder();
    private final Map<String, List<Epg>> epgCache = new HashMap<>();
    private final Map<String, Long> epgTime = new HashMap<>();
    private int previousIndex = -1;
    private long currentProgramEnd;

    private PlayItem cur() {
        return queue.get(index);
    }

    private boolean isLive() {
        return cur().type == Models.LIVE;
    }

    private boolean isSeekable() {
        return cur().type != Models.LIVE;
    }

    // ================================================================ Yasam dongusu

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
        setContentView(R.layout.activity_player);
        index = getIntent().getIntExtra(Play.EXTRA_INDEX, 0);
        startMs = getIntent().getLongExtra(Play.EXTRA_START, 0);
        if (b != null) {
            index = b.getInt("idx", index);
            startMs = b.getLong("pos", startMs);
        }
        index = Math.max(0, Math.min(index, queue.size() - 1));
        aspect = Prefs.aspect();

        videoBox = v(R.id.videoBox);
        surface = v(R.id.surface);
        buffering = v(R.id.buffering);
        subs = v(R.id.subs);
        osd = v(R.id.osd);
        numEntry = v(R.id.numEntry);
        infoBar = v(R.id.infoBar);
        infoName = v(R.id.infoName);
        infoNow = v(R.id.infoNow);
        infoNext = v(R.id.infoNext);
        infoClock = v(R.id.infoClock);
        infoLogo = v(R.id.infoLogo);
        infoProgress = v(R.id.infoProgress);
        controls = v(R.id.controls);
        ctlTitle = v(R.id.ctlTitle);
        ctlSub = v(R.id.ctlSub);
        ctlPos = v(R.id.ctlPos);
        ctlDur = v(R.id.ctlDur);
        ctlState = v(R.id.ctlState);
        ctlProgress = v(R.id.ctlProgress);
        chPanel = v(R.id.chPanel);
        chList = v(R.id.chList);

        videoBox.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View view, int l, int t, int r, int bt, int ol, int ot, int or, int ob) {
                if (r - l != or - ol || bt - t != ob - ot) applyAspect();
            }
        });

        if (isLive()) setupChannelPanel();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("idx", index);
        if (player != null && isSeekable()) out.putLong("pos", player.getCurrentPosition());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (queue == null || queue.isEmpty() || sessionLost) return;
        launchingChild = false;
        if (player == null) play(index, startMs);
        Async.MAIN.removeCallbacks(progressTick);
        Async.MAIN.post(progressTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Android 7+ : resim icinde resim / coklu pencerede oynatma devam etsin, onStop'ta birak
        if (Build.VERSION.SDK_INT < 24) releasePlayer();
        else saveProgress();
    }

    @Override
    protected void onStop() {
        super.onStop();
        releasePlayer();
        if (inPip) finish(); // PiP penceresi kapatildi
    }

    private void releasePlayer() {
        if (player != null) {
            saveProgress();
            if (isSeekable()) startMs = player.getCurrentPosition();
            player.release();
            player = null;
            Async.bg(new Runnable() {
                @Override
                public void run() {
                    TvHome.update(getApplicationContext());
                }
            });
        }
        Async.MAIN.removeCallbacks(progressTick);
        Async.MAIN.removeCallbacks(retryRunnable);
        Async.MAIN.removeCallbacks(zapRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Async.MAIN.removeCallbacks(hideInfo);
        Async.MAIN.removeCallbacks(hideControls);
        Async.MAIN.removeCallbacks(hideOsd);
        Async.MAIN.removeCallbacks(hidePanel);
        Async.MAIN.removeCallbacks(commitNumber);
        Async.MAIN.removeCallbacks(sleepRunnable);
    }

    // ================================================================ Resim icinde resim (Android 7+)

    private boolean pipSupported() {
        return PIP && Build.VERSION.SDK_INT >= 24
                && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    private void enterPip() {
        if (!pipSupported()) {
            showOsd("Bu cihaz resim icinde resmi desteklemiyor");
            return;
        }
        hideOverlays();
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                Rational r = new Rational(16, 9);
                if (videoW > 0 && videoH > 0) {
                    float a = (float) videoW / videoH;
                    if (a > 0.42f && a < 2.38f) r = new Rational(videoW, videoH);
                }
                enterPictureInPictureMode(new PictureInPictureParams.Builder().setAspectRatio(r).build());
            } else {
                enterPictureInPictureMode();
            }
        } catch (Exception e) {
            showOsd("Resim icinde resim acilamadi");
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // HOME tusuna basinca canli yayin kucuk pencerede devam etsin (uygulama icinden ekran acilinca degil)
        if (!launchingChild && player != null && isLive() && Prefs.pipOnHome() && pipSupported()) enterPip();
    }

    /** Oynaticidan baska ekran acilirken PiP'e gecilmesin */
    private boolean launchingChild;

    private void startChild(Intent i, int request) {
        launchingChild = true;
        if (request > 0) startActivityForResult(i, request);
        else startActivity(i);
    }

    private static final int REQ_MULTIVIEW = 51;

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_MULTIVIEW && res == RESULT_OK && data != null) {
            int ch = data.getIntExtra(Play.EXTRA_INDEX, -1);
            if (ch >= 0 && ch < queue.size()) {
                index = ch;
                startMs = 0;
            }
        }
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPip) {
        super.onPictureInPictureModeChanged(isInPip);
        inPip = isInPip;
        if (isInPip) hideOverlays();
    }

    private void hideOverlays() {
        infoBar.setVisibility(View.GONE);
        controls.setVisibility(View.GONE);
        chPanel.setVisibility(View.GONE);
        osd.setVisibility(View.GONE);
        numEntry.setVisibility(View.GONE);
    }

    // ================================================================ Oynatma

    private void play(int idx, long startPos) {
        if (idx != index) retry = 0;
        index = idx;
        errorShown = false;
        PlayItem p = cur();
        if (player == null) {
            player = PlayerFactory.create(this, p.type == Models.LIVE);
            player.setVideoTextureView(surface);
            player.addListener(listener);
        } else {
            player.stop();
        }
        subs.setVisibility(View.GONE);
        videoW = videoH = 0;
        currentProgramEnd = 0;
        player.setMediaSource(PlayerFactory.source(this, p.url), Math.max(0, startPos));
        player.setPlaybackSpeed(p.type == Models.LIVE ? 1f : speed);
        player.setPlayWhenReady(true);
        player.prepare();

        if (inPip) return;
        if (p.type == Models.LIVE) {
            Prefs.setLastChannel(Repo.pid(), p.id);
            showInfo();
            if (chAdapter != null) chList.setItemChecked(index, true);
        } else {
            showControls();
        }
    }

    private void replayCurrent() {
        long pos = player != null && isSeekable() ? player.getCurrentPosition() : 0;
        play(index, pos);
    }

    private final Runnable retryRunnable = new Runnable() {
        @Override
        public void run() {
            if (player != null) replayCurrent();
        }
    };

    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            buffering.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
            if (state == Player.STATE_READY) {
                retry = 0;
                if (!isLive() && audioDebugOnce) {
                    audioDebugOnce = false;
                    Log.i("MaraAudio", "VOD playback READY (ses durumu logcat taramasi ile izlenir: ExoPlayerImpl / MediaCodecAudioRenderer / audio_hw_primary)");
                }
            } else if (state == Player.STATE_ENDED) {
                onEnded();
            }
        }

        @Override
        public void onIsPlayingChanged(boolean playing) {
            ctlState.setText(playing ? "▶" : "II");
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            buffering.setVisibility(View.GONE);
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && player != null) {
                player.seekToDefaultPosition();
                player.prepare();
                return;
            }
            // VOD'da audio codec cozulmediyse otomatik harici oynaticiye gec
            // (Hisilicon vb. kutularda AC-3/EAC-3/DTS donanim cozen yok)
            if (!isLive() && retry >= 2) {
                String cm = error.getCause() != null ? error.getCause().toString() : "";
                if (cm.contains("EAC-3") || cm.contains("AC-3") || cm.contains("ac3")
                        || cm.contains("eac3") || cm.contains("DTS") || cm.contains("TrueHD")
                        || error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                        || error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) {
                    showOsd("Ses codec cozemedi, harici oynatici aciliyor...");
                    releasePlayer();
                    Play.openExternal(PlayerActivity.this, cur());
                    return;
                }
            }
            if (retry < (isLive() ? MAX_RETRY : 2)) {
                retry++;
                showOsd("Baglanti sorunu, yeniden deneniyor (" + retry + ")...");
                Async.MAIN.removeCallbacks(retryRunnable);
                Async.MAIN.postDelayed(retryRunnable, 1500L * retry);
                return;
            }
            showError(error);
        }

        @Override
        public void onVideoSizeChanged(VideoSize vs) {
            videoW = vs.width;
            videoH = vs.height;
            pixelRatio = vs.pixelWidthHeightRatio > 0 ? vs.pixelWidthHeightRatio : 1f;
            applyAspect();
        }

        @Override
        public void onCues(CueGroup cueGroup) {
            StringBuilder sb = new StringBuilder();
            for (Cue c : cueGroup.cues) {
                if (c.text == null) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(c.text);
            }
            if (sb.length() == 0) {
                subs.setVisibility(View.GONE);
            } else {
                subs.setText(sb);
                subs.setVisibility(View.VISIBLE);
            }
        }
    };

    private void onEnded() {
        PlayItem p = cur();
        if (p.type == Models.LIVE) {
            Async.MAIN.removeCallbacks(retryRunnable);
            Async.MAIN.postDelayed(retryRunnable, 1000);
            return;
        }
        if (p.resumeKey() != null && player != null) {
            long d = player.getDuration();
            if (d > 0) Db.get().saveResume(Repo.pid(), p, d, d);
        }
        if (p.type == Models.EPISODE && Prefs.autoNext() && index + 1 < queue.size()) {
            showOsd("Sonraki bolum: " + queue.get(index + 1).subtitle);
            play(index + 1, resumeOf(queue.get(index + 1)));
            return;
        }
        finish();
    }

    private long resumeOf(PlayItem p) {
        String k = p.resumeKey();
        if (k == null) return 0;
        Db.Resume r = Db.get().resume(Repo.pid(), k);
        return r != null && !r.finished() && r.position > 30000 ? r.position : 0;
    }

    private void showError(PlaybackException e) {
        if (errorShown || isFinishing() || inPip) return;
        errorShown = true;
        String msg = "Yayin oynatilamadi.\n\n" + e.getErrorCodeName();
        if (e.getCause() != null && e.getCause().getMessage() != null) msg += "\n" + e.getCause().getMessage();
        String cm = e.getCause() != null ? e.getCause().toString() : "";
        if (cm.contains("EAC-3") || cm.contains("AC-3") || cm.contains("ac3") || cm.contains("eac3")
                || cm.contains("DTS") || cm.contains("TrueHD") || cm.contains("mp2")) {
            msg += "\n\nSes codec'i (AC-3/EAC-3/DTS) bu cihazda cozulamadi. "
                    + "Ayarlar > Oynatici: Harici oynatici (VLC/MX) secin veya su anahtari kapat: \nmara.ffmpeg=false";
        }
        if (e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                || e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                || e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) {
            msg += "\n\nBu cihaz bu formati desteklemiyor olabilir. Harici oynaticiyi deneyin.";
        } else if (isLive()) {
            msg += "\n\nAyarlar > Canli yayin formati (TS / HLS) degistirmeyi deneyin.";
        }
        Ui.dialog(this).setTitle(cur().title).setMessage(msg)
                .setPositiveButton("Tekrar dene", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        retry = 0;
                        replayCurrent();
                    }
                })
                .setNeutralButton("Harici oynatici", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        openExternal();
                    }
                })
                .setNegativeButton(isLive() ? "Kapat" : "Cik", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        if (!isLive()) finish();
                    }
                })
                .show();
    }

    private void saveProgress() {
        PlayItem p = cur();
        if (player == null || p.resumeKey() == null) return;
        long pos = player.getCurrentPosition();
        long dur = player.getDuration();
        if (dur <= 0 || dur == C.TIME_UNSET) return;
        if (pos < 10000) return;
        Db.get().saveResume(Repo.pid(), p, pos, dur);
    }

    // ================================================================ Goruntu orani

    private void applyAspect() {
        int cw = videoBox.getWidth(), ch = videoBox.getHeight();
        if (cw == 0 || ch == 0) return;
        float va;
        switch (aspect) {
            case 3: va = 16f / 9f; break;
            case 4: va = 4f / 3f; break;
            default: va = (videoW > 0 && videoH > 0) ? videoW * pixelRatio / videoH : 16f / 9f;
        }
        float ca = (float) cw / ch;
        int w, h;
        if (aspect == 1) {
            w = cw;
            h = ch;
        } else if (aspect == 2) {
            if (ca > va) { w = cw; h = (int) (cw / va); } else { h = ch; w = (int) (ch * va); }
        } else {
            if (ca > va) { h = ch; w = (int) (ch * va); } else { w = cw; h = (int) (cw / va); }
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            lp.gravity = android.view.Gravity.CENTER;
            surface.setLayoutParams(lp);
        }
    }

    // ================================================================ Arayuz

    private final Runnable hideInfo = new Runnable() {
        @Override
        public void run() {
            infoBar.setVisibility(View.GONE);
        }
    };
    private final Runnable hideControls = new Runnable() {
        @Override
        public void run() {
            if (player != null && !player.isPlaying() && player.getPlaybackState() == Player.STATE_READY) return;
            controls.setVisibility(View.GONE);
        }
    };
    private final Runnable hideOsd = new Runnable() {
        @Override
        public void run() {
            osd.setVisibility(View.GONE);
        }
    };
    private final Runnable hidePanel = new Runnable() {
        @Override
        public void run() {
            chPanel.setVisibility(View.GONE);
        }
    };
    private final Runnable sleepRunnable = new Runnable() {
        @Override
        public void run() {
            Ui.toast(PlayerActivity.this, "Uyku zamanlayicisi: oynatma durduruldu");
            finish();
        }
    };

    private final Runnable progressTick = new Runnable() {
        private int n;

        @Override
        public void run() {
            if (player != null && controls.getVisibility() == View.VISIBLE) updateProgress();
            if (++n % 20 == 0 && player != null && player.isPlaying()) saveProgress();
            Async.MAIN.postDelayed(this, 500);
        }
    };

    private void showOsd(String s) {
        if (inPip) return;
        osd.setText(s);
        osd.setVisibility(View.VISIBLE);
        Async.MAIN.removeCallbacks(hideOsd);
        Async.MAIN.postDelayed(hideOsd, 2500);
    }

    private void showControls() {
        if (inPip) return;
        PlayItem p = cur();
        if (p.type == Models.EPISODE) {
            ctlTitle.setText(p.seriesName);
            ctlSub.setText(p.subtitle + "  ·  " + p.title + (queue.size() > 1 ? "   (" + (index + 1) + "/" + queue.size() + ")" : ""));
        } else {
            ctlTitle.setText(p.title);
            String sub = p.subtitle == null ? "" : p.subtitle;
            if (speed != 1f) sub += "   Hiz: " + speed + "x";
            if (sleepAt > 0) sub += "   Uyku: " + Ui.time(sleepAt / 1000);
            ctlSub.setText(sub);
        }
        controls.setVisibility(View.VISIBLE);
        updateProgress();
        Async.MAIN.removeCallbacks(hideControls);
        Async.MAIN.postDelayed(hideControls, 4500);
    }

    private void updateProgress() {
        if (player == null) return;
        long pos = player.getCurrentPosition();
        long dur = player.getDuration();
        ctlPos.setText(Ui.duration(pos));
        if (dur > 0 && dur != C.TIME_UNSET) {
            ctlDur.setText(Ui.duration(dur));
            ctlProgress.setProgress((int) (pos * 1000 / dur));
            ctlProgress.setSecondaryProgress((int) (player.getBufferedPosition() * 1000 / dur));
        } else {
            ctlDur.setText("--:--");
            ctlProgress.setProgress(0);
        }
        ctlState.setText(player.isPlaying() ? "▶" : "II");
    }

    private void showInfo() {
        if (inPip) return;
        final PlayItem p = cur();
        infoName.setText(p.num + "   " + p.title);
        ImageLoader.get().load(infoLogo, p.icon, R.drawable.ph_channel, dp(110), dp(76));
        infoClock.setText(new SimpleDateFormat("HH:mm", Ui.TR).format(new Date()));
        infoBar.setVisibility(View.VISIBLE);
        Async.MAIN.removeCallbacks(hideInfo);
        Async.MAIN.postDelayed(hideInfo, 5000);

        List<Epg> cached = epgCache.get(p.id);
        Long t = epgTime.get(p.id);
        if (cached != null && t != null && System.currentTimeMillis() - t < 5 * 60 * 1000) {
            bindEpg(cached);
            return;
        }
        infoNow.setText("");
        infoNext.setText("");
        infoProgress.setProgress(0);
        Async.run(this, new Async.Task<List<Epg>>() {
            @Override
            public List<Epg> run() throws Exception {
                return Repo.api().shortEpg(p.id, p.epgId, 3);
            }
        }, new Async.Callback<List<Epg>>() {
            @Override
            public void on(List<Epg> l) {
                epgCache.put(p.id, l);
                epgTime.put(p.id, System.currentTimeMillis());
                if (cur().id.equals(p.id)) bindEpg(l);
            }
        }, null);
    }

    private void bindEpg(List<Epg> l) {
        long off = Prefs.epgOffsetSec();
        long now = System.currentTimeMillis() / 1000L - off;
        Epg cur = null, next = null;
        for (Epg e : l) {
            if (e.stop <= now) continue;
            if (cur == null && e.isNow(now)) cur = e;
            else if (next == null && e.start >= now) next = e;
        }
        if (cur != null) {
            currentProgramEnd = cur.stop;
            infoNow.setText("Simdi  " + Ui.time(cur.start + off) + " - " + Ui.time(cur.stop + off) + "   " + cur.title);
            infoProgress.setProgress((int) ((now - cur.start) * 100 / Math.max(1, cur.stop - cur.start)));
        } else {
            infoNow.setText("Yayin akisi bilgisi yok");
            infoProgress.setProgress(0);
        }
        infoNext.setText(next != null ? "Sonra  " + Ui.time(next.start + off) + "   " + next.title : "");
    }

    // ================================================================ Kanal paneli

    private void setupChannelPanel() {
        chAdapter = new Adapters.RowAdapter(this);
        List<Adapters.Row> rows = new ArrayList<>();
        for (PlayItem p : queue) {
            Adapters.Row r = new Adapters.Row();
            r.title = p.num + "   " + p.title;
            r.icon = p.icon;
            r.showIcon = true;
            r.tag = p;
            rows.add(r);
        }
        chAdapter.set(rows);
        chList.setAdapter(chAdapter);
        String cat = Repo.categoryName(Models.LIVE, cur().categoryId);
        ((TextView) v(R.id.chPanelTitle)).setText((cat != null ? cat : "Kanallar") + "  (" + queue.size() + ")");
        chList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                chPanel.setVisibility(View.GONE);
                if (pos != index) {
                    previousIndex = index;
                    play(pos, 0);
                }
            }
        });
        chList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View view, int pos, long id) {
                bumpPanel();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
    }

    private void showPanel() {
        if (chAdapter == null || inPip) return;
        chPanel.setVisibility(View.VISIBLE);
        infoBar.setVisibility(View.GONE);
        chList.setItemChecked(index, true);
        chList.setSelection(index);
        chList.requestFocus();
        bumpPanel();
    }

    private void bumpPanel() {
        Async.MAIN.removeCallbacks(hidePanel);
        Async.MAIN.postDelayed(hidePanel, 10000);
    }

    // ================================================================ Zapping

    private int zapTarget = -1;
    private final Runnable zapRunnable = new Runnable() {
        @Override
        public void run() {
            if (zapTarget >= 0 && zapTarget != index) {
                previousIndex = index;
                play(zapTarget, 0);
            }
            zapTarget = -1;
        }
    };

    private void zap(int delta) {
        int base = zapTarget >= 0 ? zapTarget : index;
        int n = queue.size();
        int target = ((base + delta) % n + n) % n;
        zapTarget = target;
        PlayItem p = queue.get(target);
        infoName.setText(p.num + "   " + p.title);
        ImageLoader.get().load(infoLogo, p.icon, R.drawable.ph_channel, dp(110), dp(76));
        infoNow.setText("");
        infoNext.setText("");
        infoBar.setVisibility(View.VISIBLE);
        Async.MAIN.removeCallbacks(hideInfo);
        Async.MAIN.postDelayed(hideInfo, 5000);
        Async.MAIN.removeCallbacks(zapRunnable);
        Async.MAIN.postDelayed(zapRunnable, 450);
    }

    private final Runnable commitNumber = new Runnable() {
        @Override
        public void run() {
            numEntry.setVisibility(View.GONE);
            if (numBuf.length() == 0) return;
            int n = XtreamApi.toInt(numBuf.toString());
            numBuf.setLength(0);
            int target = -1;
            for (int i = 0; i < queue.size(); i++) if (queue.get(i).num == n) { target = i; break; }
            if (target < 0 && n >= 1 && n <= queue.size()) target = n - 1;
            if (target < 0) {
                showOsd("Kanal " + n + " bulunamadi");
            } else if (target != index) {
                previousIndex = index;
                play(target, 0);
            }
        }
    };

    private void digit(int d) {
        if (numBuf.length() >= 4) numBuf.setLength(0);
        numBuf.append(d);
        numEntry.setText(numBuf.toString());
        numEntry.setVisibility(View.VISIBLE);
        Async.MAIN.removeCallbacks(commitNumber);
        Async.MAIN.postDelayed(commitNumber, 1800);
    }

    // ================================================================ Tuslar

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Geri/EXIT tusu HICBIR DURUMDA uygulamayi tam kapatmasin;
        // paneli kapatir veya player activity'sini kapatir (alti ekrana doner)
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (chPanel != null && chPanel.getVisibility() == View.VISIBLE) {
                chPanel.setVisibility(View.GONE);
                return true;
            }
            // RAM baskisiyla alt activity kill edilmisse task bos olur,
            // finish() launcher'a atar. O yüzden VOD'da Home'a gecen bir
            // intent baslat; CANLI TV'de sadece finish() (altta LiveActivity
            // zaten vardi, onune don).
            if (queue != null && queue.get(index).type != Models.LIVE) {
                startActivity(new Intent(this, HomeActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            }
            finish(); // onStop() releasePlayer() cagirir; cift release olmaz
            return true;
        }
        if (player == null) return super.onKeyDown(keyCode, event);

        if (chPanel.getVisibility() == View.VISIBLE) {
            bumpPanel();
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                chPanel.setVisibility(View.GONE);
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9 && isLive()) {
            digit(keyCode - KeyEvent.KEYCODE_0);
            return true;
        }
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9 && isLive()) {
            digit(keyCode - KeyEvent.KEYCODE_NUMPAD_0);
            return true;
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SETTINGS:
                options();
                return true;
            case KeyEvent.KEYCODE_INFO:
            case KeyEvent.KEYCODE_GUIDE:
                if (isLive()) {
                    if (keyCode == KeyEvent.KEYCODE_GUIDE) openEpg();
                    else showInfo();
                } else {
                    showControls();
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_RECORD:
                if (isLive()) RecordUi.recordNow(this, Play.toItem(cur()), currentProgramEnd);
                return true;
            case KeyEvent.KEYCODE_WINDOW:
                enterPip();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                togglePause();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                player.setPlayWhenReady(true);
                if (!isLive()) showControls();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                player.setPlayWhenReady(false);
                if (!isLive()) showControls();
                return true;
            case KeyEvent.KEYCODE_MEDIA_STOP:
                finish();
                return true;
        }

        if (isLive()) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    showPanel();
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_CHANNEL_UP:
                case KeyEvent.KEYCODE_PAGE_UP:
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                    zap(keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                case KeyEvent.KEYCODE_PAGE_DOWN:
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                    zap(keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    showPanel();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    showInfo();
                    return true;
                case 229: // KEYCODE_LAST_CHANNEL
                    if (previousIndex >= 0 && previousIndex < queue.size()) {
                        int p = previousIndex;
                        previousIndex = index;
                        play(p, 0);
                    }
                    return true;
            }
        } else {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (controls.getVisibility() != View.VISIBLE) showControls();
                    else togglePause();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                    seek(-step(event));
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                    seek(step(event));
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    showControls();
                    return true;
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                case KeyEvent.KEYCODE_CHANNEL_UP:
                    if (index + 1 < queue.size()) {
                        saveProgress();
                        play(index + 1, resumeOf(queue.get(index + 1)));
                    }
                    return true;
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                    if (index > 0) {
                        saveProgress();
                        play(index - 1, resumeOf(queue.get(index - 1)));
                    }
                    return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private long step(KeyEvent e) {
        int r = e.getRepeatCount();
        if (r < 4) return 10000;
        if (r < 15) return 30000;
        return 60000;
    }

    private void seek(long delta) {
        long dur = player.getDuration();
        long pos = player.getCurrentPosition() + delta;
        if (dur > 0 && dur != C.TIME_UNSET) pos = Math.min(pos, dur - 2000);
        player.seekTo(Math.max(0, pos));
        showControls();
    }

    private void togglePause() {
        player.setPlayWhenReady(!player.getPlayWhenReady());
        if (isLive()) showOsd(player.getPlayWhenReady() ? "Oynatiliyor" : "Duraklatildi");
        else showControls();
    }

    // ================================================================ Secenekler

    private void options() {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        labels.add("Ses dili / parca");
        actions.add(new Runnable() { @Override public void run() { chooseTrack(C.TRACK_TYPE_AUDIO); } });
        labels.add("Altyazi");
        actions.add(new Runnable() { @Override public void run() { chooseTrack(C.TRACK_TYPE_TEXT); } });
        labels.add("Goruntu orani (" + ASPECTS[aspect] + ")");
        actions.add(new Runnable() { @Override public void run() { chooseAspect(); } });
        if (isLive()) {
            final PlayItem p = cur();
            final Item it = Play.toItem(p);
            final boolean fav = Db.get().isFavorite(Repo.pid(), Models.LIVE, p.id);
            labels.add(fav ? "Favorilerden cikar" : "Favorilere ekle");
            actions.add(new Runnable() {
                @Override
                public void run() {
                    boolean now = Db.get().toggleFavorite(Repo.pid(), it);
                    showOsd(now ? "Favorilere eklendi" : "Favorilerden cikarildi");
                }
            });
            labels.add("Kaydet");
            actions.add(new Runnable() { @Override public void run() { RecordUi.recordNow(PlayerActivity.this, it, currentProgramEnd); } });
            labels.add("Coklu ekran (2 / 4 kanal)");
            actions.add(new Runnable() { @Override public void run() { openMultiView(); } });
            labels.add("Yayin akisi" + (p.archive ? " / Geri izle" : ""));
            actions.add(new Runnable() { @Override public void run() { openEpg(); } });
            labels.add("Kanal listesi");
            actions.add(new Runnable() { @Override public void run() { showPanel(); } });
        } else {
            labels.add("Oynatma hizi (" + speed + "x)");
            actions.add(new Runnable() { @Override public void run() { chooseSpeed(); } });
        }
        if (pipSupported()) {
            labels.add("Resim icinde resim");
            actions.add(new Runnable() { @Override public void run() { enterPip(); } });
        }
        labels.add("Uyku zamanlayicisi" + (sleepAt > 0 ? " (" + Ui.time(sleepAt / 1000) + ")" : ""));
        actions.add(new Runnable() { @Override public void run() { chooseSleep(); } });
        labels.add("Yeniden baglan");
        actions.add(new Runnable() { @Override public void run() { retry = 0; replayCurrent(); } });
        labels.add("Yayin bilgisi");
        actions.add(new Runnable() { @Override public void run() { streamInfo(); } });
        labels.add("Harici oynaticida ac");
        actions.add(new Runnable() { @Override public void run() { openExternal(); } });

        Ui.choose(this, cur().title, labels.toArray(new String[0]), -1, new Ui.Choice() {
            @Override
            public void on(int which) {
                actions.get(which).run();
            }
        });
    }

    private void openMultiView() {
        Repo.playQueue = queue;
        // cozucu yetsin diye tam ekran oynaticiyi once birak
        releasePlayer();
        startChild(new Intent(this, MultiViewActivity.class).putExtra(Play.EXTRA_INDEX, index), REQ_MULTIVIEW);
    }

    private void chooseSpeed() {
        String[] names = new String[SPEEDS.length];
        int checked = 2;
        for (int i = 0; i < SPEEDS.length; i++) {
            names[i] = SPEEDS[i] + "x";
            if (SPEEDS[i] == speed) checked = i;
        }
        Ui.choose(this, "Oynatma hizi", names, checked, new Ui.Choice() {
            @Override
            public void on(int which) {
                speed = SPEEDS[which];
                if (player != null) player.setPlaybackSpeed(speed);
                showOsd("Hiz: " + speed + "x");
            }
        });
    }

    private void chooseSleep() {
        final int[] mins = {0, 15, 30, 45, 60, 90, 120};
        String[] names = {"Kapali", "15 dakika", "30 dakika", "45 dakika", "60 dakika", "90 dakika", "120 dakika"};
        Ui.choose(this, "Uyku zamanlayicisi", names, -1, new Ui.Choice() {
            @Override
            public void on(int which) {
                Async.MAIN.removeCallbacks(sleepRunnable);
                if (mins[which] == 0) {
                    sleepAt = 0;
                    showOsd("Uyku zamanlayicisi kapatildi");
                } else {
                    sleepAt = System.currentTimeMillis() + mins[which] * 60000L;
                    Async.MAIN.postDelayed(sleepRunnable, mins[which] * 60000L);
                    showOsd("Oynatma " + mins[which] + " dk sonra duracak");
                }
            }
        });
    }

    private void openEpg() {
        startChild(BaseActivity.putItem(new Intent(this, EpgActivity.class), Play.toItem(cur())), 0);
    }

    private void openExternal() {
        launchingChild = true;
        Play.openExternal(this, cur());
    }

    private void chooseAspect() {
        Ui.choose(this, "Goruntu orani", ASPECTS, aspect, new Ui.Choice() {
            @Override
            public void on(int which) {
                aspect = which;
                applyAspect();
                showOsd("Oran: " + ASPECTS[which]);
            }
        });
    }

    private void chooseTrack(final int trackType) {
        if (player == null) return;
        final List<String> labels = new ArrayList<>();
        final List<TrackGroup> groups = new ArrayList<>();
        final List<Integer> idx = new ArrayList<>();
        int checked = -1;
        if (trackType == C.TRACK_TYPE_TEXT) {
            labels.add("Kapali");
            groups.add(null);
            idx.add(-1);
            if (player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) checked = 0;
        }
        Tracks tracks = player.getCurrentTracks();
        for (Tracks.Group g : tracks.getGroups()) {
            if (g.getType() != trackType) continue;
            for (int i = 0; i < g.length; i++) {
                Format f = g.getTrackFormat(i);
                String name = trackName(f, labels.size() + (trackType == C.TRACK_TYPE_TEXT ? 0 : 1));
                if (!g.isTrackSupported(i)) name += " (desteklenmiyor)";
                if (g.isTrackSelected(i)) checked = labels.size();
                labels.add(name);
                groups.add(g.getMediaTrackGroup());
                idx.add(i);
            }
        }
        if (labels.isEmpty() || (trackType == C.TRACK_TYPE_TEXT && labels.size() == 1)) {
            showOsd(trackType == C.TRACK_TYPE_AUDIO ? "Baska ses parcasi yok" : "Altyazi yok");
            return;
        }
        Ui.choose(this, trackType == C.TRACK_TYPE_AUDIO ? "Ses" : "Altyazi", labels.toArray(new String[0]), checked, new Ui.Choice() {
            @Override
            public void on(int which) {
                if (player == null) return;
                TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon();
                TrackGroup tg = groups.get(which);
                if (tg == null) {
                    b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
                    subs.setVisibility(View.GONE);
                } else {
                    b.setTrackTypeDisabled(trackType, false);
                    b.setOverrideForType(new TrackSelectionOverride(tg, idx.get(which)));
                }
                player.setTrackSelectionParameters(b.build());
                showOsd(labels.get(which));
            }
        });
    }

    private static String trackName(Format f, int n) {
        StringBuilder sb = new StringBuilder();
        if (f.language != null && !f.language.isEmpty() && !"und".equals(f.language)) {
            String lang = new Locale(f.language).getDisplayLanguage(Ui.TR);
            sb.append(lang.isEmpty() ? f.language : lang);
        }
        if (f.label != null && !f.label.isEmpty()) {
            if (sb.length() > 0) sb.append(" - ");
            sb.append(f.label);
        }
        if (f.channelCount > 0) {
            sb.append(sb.length() > 0 ? "  " : "").append(f.channelCount == 6 ? "5.1" : f.channelCount == 8 ? "7.1" : f.channelCount == 2 ? "Stereo" : f.channelCount == 1 ? "Mono" : f.channelCount + "ch");
        }
        if (f.sampleMimeType != null) {
            String m = f.sampleMimeType;
            int s = m.indexOf('/');
            sb.append(" [").append((s >= 0 ? m.substring(s + 1) : m).toUpperCase(Locale.US)).append("]");
        }
        return sb.length() == 0 ? "Parca " + n : sb.toString().trim();
    }

    private void streamInfo() {
        if (player == null) return;
        StringBuilder sb = new StringBuilder();
        Format v = player.getVideoFormat();
        Format a = player.getAudioFormat();
        if (v != null) {
            sb.append("Video: ").append(v.width).append("x").append(v.height);
            if (v.frameRate > 0) sb.append(" @ ").append(String.format(Locale.US, "%.0f", v.frameRate)).append("fps");
            if (v.sampleMimeType != null) sb.append("  ").append(v.sampleMimeType);
            if (v.bitrate > 0) sb.append("  ").append(v.bitrate / 1000).append(" kbps");
            sb.append('\n');
        }
        if (a != null) {
            sb.append("Ses: ").append(a.sampleMimeType).append("  ").append(a.channelCount).append("ch  ").append(a.sampleRate).append(" Hz\n");
        }
        sb.append("Tampon: ").append((player.getBufferedPosition() - player.getCurrentPosition()) / 1000).append(" sn\n");
        sb.append("Motor: Media3 (").append(BuildConfig.EDITION).append(")\n");
        String pass = Repo.profile().pass;
        sb.append("Adres: ").append(Ui.empty(pass) ? cur().url : cur().url.replace(pass, "****"));
        Ui.dialog(this).setTitle("Yayin bilgisi").setMessage(sb.toString()).setPositiveButton("Tamam", null).show();
    }
}
