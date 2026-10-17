package com.atlanta.maraplayer.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.atlanta.maraplayer.R;
import com.atlanta.maraplayer.data.Db;
import com.atlanta.maraplayer.data.Models;
import com.atlanta.maraplayer.data.Models.Item;
import com.atlanta.maraplayer.data.Models.VodInfo;
import com.atlanta.maraplayer.data.Repo;
import com.atlanta.maraplayer.util.Async;
import com.atlanta.maraplayer.util.ImageLoader;
import com.atlanta.maraplayer.util.Ui;

public class MovieDetailActivity extends BaseActivity {

    private Item item;
    private VodInfo info;
    private Button btnPlay, btnRestart, btnFav, btnTrailer;
    private Db.Resume resume;

    public static void open(Activity a, Item it) {
        a.startActivity(putItem(new Intent(a, MovieDetailActivity.class), it));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (sessionLost) return;
        setContentView(R.layout.activity_detail);
        item = getItem(getIntent());
        item.type = Models.MOVIE;
        btnPlay = v(R.id.btnPlay);
        btnRestart = v(R.id.btnRestart);
        btnFav = v(R.id.btnFav);
        btnTrailer = v(R.id.btnTrailer);

        ((TextView) v(R.id.name)).setText(item.name);
        ImageLoader.get().load((ImageView) v(R.id.poster), item.icon, R.drawable.ph_poster, dp(200), dp(300));
        String r = Adapters.cleanRating(item.rating);
        ((TextView) v(R.id.meta)).setText(r != null ? "★ " + r : "");

        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                play(resume != null ? resume.position : 0);
            }
        });
        btnRestart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                play(0);
            }
        });
        btnFav.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Db.get().toggleFavorite(Repo.pid(), item);
                updateFav();
            }
        });
        btnTrailer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openTrailer(MovieDetailActivity.this, info.trailer);
            }
        });
        btnPlay.requestFocus();
        updateFav();
        loadInfo();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (item == null) return;
        resume = Db.get().resume(Repo.pid(), "movie:" + item.id);
        if (resume != null && (resume.position < 30000 || resume.finished())) resume = null;
        if (resume != null) {
            btnPlay.setText(getString(R.string.resume_x, Ui.duration(resume.position)));
            btnRestart.setVisibility(View.VISIBLE);
        } else {
            btnPlay.setText(R.string.play);
            btnRestart.setVisibility(View.GONE);
        }
    }

    private void updateFav() {
        boolean fav = Db.get().isFavorite(Repo.pid(), Models.MOVIE, item.id);
        btnFav.setText(fav ? R.string.fav_remove : R.string.fav_add);
    }

    private void loadInfo() {
        v(R.id.loading).setVisibility(View.VISIBLE);
        Async.run(this, new Async.Task<VodInfo>() {
            @Override
            public VodInfo run() throws Exception {
                return Repo.api().vodInfo(item.id);
            }
        }, new Async.Callback<VodInfo>() {
            @Override
            public void on(VodInfo vi) {
                v(R.id.loading).setVisibility(View.GONE);
                info = vi;
                show();
            }
        }, new Async.Callback<Exception>() {
            @Override
            public void on(Exception e) {
                v(R.id.loading).setVisibility(View.GONE);
                ((TextView) v(R.id.plot)).setText(R.string.details_failed);
            }
        });
    }

    private void show() {
        if (!Ui.empty(info.ext)) item.ext = info.ext;
        StringBuilder meta = new StringBuilder();
        String r = Adapters.cleanRating(Ui.empty(info.rating) ? item.rating : info.rating);
        if (r != null) meta.append("★ ").append(r).append("   ");
        if (info.releaseDate.length() >= 4) meta.append(info.releaseDate.substring(0, 4)).append("   ");
        if (!Ui.empty(info.duration)) meta.append(info.duration).append("   ");
        if (!Ui.empty(info.genre)) meta.append(info.genre).append("   ");
        if (!Ui.empty(info.country)) meta.append(info.country);
        ((TextView) v(R.id.meta)).setText(meta.toString().trim());
        ((TextView) v(R.id.plot)).setText(info.plot);
        StringBuilder ppl = new StringBuilder();
        if (!Ui.empty(info.director)) ppl.append(getString(R.string.director)).append(": ").append(info.director).append('\n');
        if (!Ui.empty(info.cast)) ppl.append(getString(R.string.cast)).append(": ").append(info.cast);
        ((TextView) v(R.id.people)).setText(ppl.toString().trim());
        if (!Ui.empty(info.image) && Ui.empty(item.icon)) {
            ImageLoader.get().load((ImageView) v(R.id.poster), info.image, R.drawable.ph_poster, dp(200), dp(300));
        }
        if (!Ui.empty(info.backdrop)) {
            ImageLoader.get().load((ImageView) v(R.id.backdrop), info.backdrop, android.R.color.transparent, dp(640), dp(360));
        }
        btnTrailer.setVisibility(Ui.empty(info.trailer) ? View.GONE : View.VISIBLE);
    }

    private void play(long startMs) {
        Play.playMovie(this, item, startMs);
    }

    static void openTrailer(Activity a, String t) {
        if (Ui.empty(t)) return;
        String url = t.startsWith("http") ? t : "https://www.youtube.com/watch?v=" + t.trim();
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Ui.toast(a, a.getString(R.string.no_trailer_app));
        }
    }
}
