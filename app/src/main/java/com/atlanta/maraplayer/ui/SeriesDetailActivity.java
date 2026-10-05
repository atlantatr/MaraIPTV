package com.atlanta.maraplayer.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.atlanta.maraplayer.R;
import com.atlanta.maraplayer.data.Db;
import com.atlanta.maraplayer.data.Models;
import com.atlanta.maraplayer.data.Models.Episode;
import com.atlanta.maraplayer.data.Models.Item;
import com.atlanta.maraplayer.data.Models.Season;
import com.atlanta.maraplayer.data.Models.SeriesInfo;
import com.atlanta.maraplayer.data.Repo;
import com.atlanta.maraplayer.util.Async;
import com.atlanta.maraplayer.util.ImageLoader;
import com.atlanta.maraplayer.util.Ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SeriesDetailActivity extends BaseActivity {

    private Item item;
    private SeriesInfo info;
    private ListView seasons, episodes;
    private Adapters.RowAdapter seasonAdapter, episodeAdapter;
    private Button btnPlay, btnFav, btnTrailer;
    private int currentSeason = -1;
    private final Map<String, Db.Resume> resumes = new HashMap<>();
    private Db.Resume last;

    public static void open(Activity a, Item it) {
        a.startActivity(putItem(new Intent(a, SeriesDetailActivity.class), it));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (sessionLost) return;
        setContentView(R.layout.activity_detail);
        item = getItem(getIntent());
        item.type = Models.SERIES;
        v(R.id.seriesBox).setVisibility(View.VISIBLE);
        ((TextView) v(R.id.plot)).setMaxLines(3);
        ((TextView) v(R.id.people)).setMaxLines(1);
        seasons = v(R.id.seasons);
        episodes = v(R.id.episodes);
        btnPlay = v(R.id.btnPlay);
        btnFav = v(R.id.btnFav);
        btnTrailer = v(R.id.btnTrailer);
        seasonAdapter = new Adapters.RowAdapter(this);
        episodeAdapter = new Adapters.RowAdapter(this);
        seasons.setAdapter(seasonAdapter);
        episodes.setAdapter(episodeAdapter);

        ((TextView) v(R.id.name)).setText(item.name);
        ImageLoader.get().load((ImageView) v(R.id.poster), item.icon, R.drawable.ph_poster, dp(200), dp(300));
        ((TextView) v(R.id.plot)).setText(item.plot);
        btnPlay.setText("Oynat");

        seasons.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View view, int pos, long id) {
                showSeason(pos);
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        seasons.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                showSeason(pos);
                episodes.requestFocus();
            }
        });
        episodes.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                playEpisode(currentSeason, pos, true);
            }
        });
        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                playLastOrFirst();
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
                if (info != null) MovieDetailActivity.openTrailer(SeriesDetailActivity.this, info.trailer);
            }
        });
        updateFav();
        btnPlay.requestFocus();
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (item == null) return;
        loadResumes();
        if (info != null && currentSeason >= 0) {
            int sel = episodes.getSelectedItemPosition();
            int s = currentSeason;
            currentSeason = -1;
            showSeason(s);
            if (sel >= 0) episodes.setSelection(sel);
        }
        updatePlayButton();
    }

    private void loadResumes() {
        resumes.clear();
        List<Db.Resume> l = Db.get().resumeForSeries(Repo.pid(), item.id);
        last = l.isEmpty() ? null : l.get(0);
        for (Db.Resume r : l) resumes.put(r.itemId, r);
    }

    private void updateFav() {
        boolean fav = Db.get().isFavorite(Repo.pid(), Models.SERIES, item.id);
        btnFav.setText(fav ? "Favorilerden Cikar" : "Favorilere Ekle");
    }

    private void load() {
        v(R.id.loading).setVisibility(View.VISIBLE);
        Async.run(this, new Async.Task<SeriesInfo>() {
            @Override
            public SeriesInfo run() throws Exception {
                return Repo.api().seriesInfo(item.id);
            }
        }, new Async.Callback<SeriesInfo>() {
            @Override
            public void on(SeriesInfo si) {
                v(R.id.loading).setVisibility(View.GONE);
                info = si;
                show();
            }
        }, new Async.Callback<Exception>() {
            @Override
            public void on(Exception e) {
                v(R.id.loading).setVisibility(View.GONE);
                ((TextView) v(R.id.plot)).setText("Dizi bilgisi alinamadi: " + Ui.err(e));
            }
        });
    }

    private void show() {
        StringBuilder meta = new StringBuilder();
        String r = Adapters.cleanRating(Ui.empty(info.rating) ? item.rating : info.rating);
        if (r != null) meta.append("★ ").append(r).append("   ");
        if (info.releaseDate.length() >= 4) meta.append(info.releaseDate.substring(0, 4)).append("   ");
        if (!Ui.empty(info.genre)) meta.append(info.genre).append("   ");
        meta.append(info.seasons.size()).append(" sezon");
        ((TextView) v(R.id.meta)).setText(meta.toString());
        if (!Ui.empty(info.plot)) ((TextView) v(R.id.plot)).setText(info.plot);
        StringBuilder ppl = new StringBuilder();
        if (!Ui.empty(info.cast)) ppl.append("Oyuncular: ").append(info.cast);
        else if (!Ui.empty(info.director)) ppl.append("Yonetmen: ").append(info.director);
        ((TextView) v(R.id.people)).setText(ppl.toString());
        if (!Ui.empty(info.backdrop)) {
            ImageLoader.get().load((ImageView) v(R.id.backdrop), info.backdrop, android.R.color.transparent, dp(640), dp(360));
        }
        btnTrailer.setVisibility(Ui.empty(info.trailer) ? View.GONE : View.VISIBLE);

        List<Adapters.Row> rows = new ArrayList<>();
        for (Season s : info.seasons) {
            rows.add(new Adapters.Row(s.name, s.episodes.size() + " bolum", s));
        }
        seasonAdapter.set(rows);
        if (info.seasons.isEmpty()) {
            ((TextView) v(R.id.people)).setText("Bu dizi icin bolum bulunamadi.");
            return;
        }
        // son izlenen sezonu ac
        int si = 0;
        if (last != null) {
            for (int i = 0; i < info.seasons.size(); i++) {
                for (Episode e : info.seasons.get(i).episodes) if (e.id.equals(last.itemId)) si = i;
            }
        }
        seasons.setSelection(si);
        seasons.setItemChecked(si, true);
        showSeason(si);
        updatePlayButton();
    }

    private void showSeason(int pos) {
        if (info == null || pos < 0 || pos >= info.seasons.size() || pos == currentSeason) return;
        currentSeason = pos;
        seasons.setItemChecked(pos, true);
        Season s = info.seasons.get(pos);
        List<Adapters.Row> rows = new ArrayList<>();
        for (Episode e : s.episodes) {
            Adapters.Row r = new Adapters.Row();
            r.title = String.format(Ui.TR, "%02d. ", e.number) + (Ui.empty(e.title) ? "Bolum " + e.number : e.title);
            StringBuilder sub = new StringBuilder();
            if (!Ui.empty(e.duration)) sub.append(e.duration).append("  ");
            if (!Ui.empty(e.plot)) sub.append(e.plot);
            r.sub = sub.toString().trim();
            r.icon = Ui.empty(e.image) ? s.cover : e.image;
            r.showIcon = true;
            r.placeholder = R.drawable.ph_channel;
            Db.Resume res = resumes.get(e.id);
            if (res != null) {
                if (res.finished()) {
                    r.right = "Izlendi";
                } else {
                    r.progress = res.percent();
                    r.right = Ui.duration(res.position);
                }
            }
            r.tag = e;
            rows.add(r);
        }
        episodeAdapter.set(rows);
    }

    private void updatePlayButton() {
        if (info == null) return;
        int[] pos = lastPosition();
        if (pos != null) {
            Episode e = info.seasons.get(pos[0]).episodes.get(pos[1]);
            Db.Resume r = resumes.get(e.id);
            boolean finished = r != null && r.finished();
            if (finished) {
                int[] nx = next(pos);
                if (nx != null) {
                    Episode n = info.seasons.get(nx[0]).episodes.get(nx[1]);
                    btnPlay.setText("Sonraki: S" + n.season + " B" + n.number);
                    return;
                }
            }
            btnPlay.setText("Devam Et: S" + e.season + " B" + e.number);
        } else if (!info.seasons.isEmpty()) {
            btnPlay.setText("Oynat: S" + info.seasons.get(0).number + " B" + info.seasons.get(0).episodes.get(0).number);
        }
    }

    private int[] lastPosition() {
        if (last == null || info == null) return null;
        for (int i = 0; i < info.seasons.size(); i++) {
            List<Episode> eps = info.seasons.get(i).episodes;
            for (int j = 0; j < eps.size(); j++) if (eps.get(j).id.equals(last.itemId)) return new int[]{i, j};
        }
        return null;
    }

    private int[] next(int[] p) {
        if (p[1] + 1 < info.seasons.get(p[0]).episodes.size()) return new int[]{p[0], p[1] + 1};
        if (p[0] + 1 < info.seasons.size()) return new int[]{p[0] + 1, 0};
        return null;
    }

    private void playLastOrFirst() {
        if (info == null || info.seasons.isEmpty()) return;
        int[] pos = lastPosition();
        if (pos == null) {
            playEpisode(0, 0, false);
            return;
        }
        Db.Resume r = resumes.get(info.seasons.get(pos[0]).episodes.get(pos[1]).id);
        if (r != null && r.finished()) {
            int[] nx = next(pos);
            if (nx != null) pos = nx;
        }
        playEpisode(pos[0], pos[1], true);
    }

    private void playEpisode(final int season, final int index, boolean askResume) {
        if (info == null || season < 0) return;
        final List<Episode> eps = info.seasons.get(season).episodes;
        if (index < 0 || index >= eps.size()) return;
        final Episode e = eps.get(index);
        final String name = Ui.empty(info.name) ? item.name : info.name;
        final String cover = Ui.empty(info.cover) ? item.icon : info.cover;
        Db.Resume r = resumes.get(e.id);
        if (askResume && r != null && r.position > 30000 && !r.finished()) {
            final long pos = r.position;
            Ui.choose(this, "S" + e.season + " B" + e.number, new String[]{"Devam et (" + Ui.duration(pos) + ")", "Bastan oynat"}, -1,
                    new Ui.Choice() {
                        @Override
                        public void on(int which) {
                            PlayerActivity.playEpisodes(SeriesDetailActivity.this, item.id, name, cover, eps, index, which == 0 ? pos : 0);
                        }
                    });
        } else {
            PlayerActivity.playEpisodes(this, item.id, name, cover, eps, index, 0);
        }
    }
}
