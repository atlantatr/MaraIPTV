package com.atlanta.maraplayer.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.atlanta.maraplayer.R;
import com.atlanta.maraplayer.data.Models;
import com.atlanta.maraplayer.data.Models.Item;
import com.atlanta.maraplayer.data.Prefs;
import com.atlanta.maraplayer.data.Repo;
import com.atlanta.maraplayer.util.Async;
import com.atlanta.maraplayer.util.ImageLoader;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Son eklenenler: güncel kanal, film ve diziler tek ekranda (ekleme tarihine göre).
 * Sekmeler: Tümü / Kanallar / Filmler / Diziler.
 *
 * Hız: her tür ayrı ayrı ve paralel yüklenir; ilk sonuç gelir gelmez liste çizilir.
 * Oturum içinde ekran tekrar açıldığında sonuçlar bellekten anında gösterilir.
 */
public class NewActivity extends BaseActivity {

    private static final int PER_TYPE = 80;
    /** Bellekteki son sonuç (oturum içinde anında açılış) */
    private static Data cached;
    private static long cachedAt;
    private static long cachedPid = -2;
    private static boolean cachedKids;

    private int tab = -1;
    private ListView list;
    private NewAdapter adapter;
    private TextView msg;
    private View loading;
    private Button[] tabs;
    private boolean loadingNow;
    private int pending;
    private Data data = new Data();

    private static class Data {
        List<Item> live = new ArrayList<>();
        List<Item> movies = new ArrayList<>();
        List<Item> series = new ArrayList<>();
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (sessionLost) return;
        setContentView(R.layout.activity_list);
        list = v(R.id.list);
        msg = v(R.id.msg);
        loading = v(R.id.loading);
        adapter = new NewAdapter();
        list.setAdapter(adapter);
        ((TextView) v(R.id.title)).setText(R.string.new_added);
        ((TextView) v(R.id.hint)).setText(R.string.new_hint);

        v(R.id.tabs).setVisibility(View.VISIBLE);
        tabs = new Button[]{v(R.id.tab0), v(R.id.tab1), v(R.id.tab2), v(R.id.tab3)};
        v(R.id.tab3).setVisibility(View.VISIBLE);
        String[] names = {
                getString(R.string.new_tab_all),
                getString(R.string.tile_live),
                getString(R.string.tile_movies),
                getString(R.string.tile_series)
        };
        final int[] values = {-1, Models.LIVE, Models.MOVIE, Models.SERIES};
        for (int i = 0; i < tabs.length; i++) {
            final int idx = i;
            tabs[i].setText(names[i]);
            tabs[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    tab = values[idx];
                    render();
                    if (adapter.getCount() > 0) list.requestFocus();
                }
            });
        }
        tabs[0].setSelected(true);
        tabs[0].requestFocus();

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                open(pos);
            }
        });

        boolean sameSession = cached != null && cachedPid == Repo.pid() && cachedKids == Prefs.kidsMode();
        if (sameSession) {
            data = cached;
            render();
            // 5 dakikadan eskiyse arka planda sessizce tazele
            if (System.currentTimeMillis() - cachedAt > 5 * 60 * 1000L) refresh(false);
        } else {
            refresh(true);
        }
    }

    /** Üç türü paralel yükler; her tür hazır oldukça liste güncellenir. */
    private void refresh(boolean spinner) {
        if (loadingNow) return;
        loadingNow = true;
        pending = 3;
        if (spinner) loading.setVisibility(View.VISIBLE);
        msg.setVisibility(View.GONE);
        loadOne(Models.LIVE);
        loadOne(Models.MOVIE);
        loadOne(Models.SERIES);
    }

    private void loadOne(final int type) {
        Async.run(this, new Async.Task<List<Item>>() {
            @Override
            public List<Item> run() throws Exception {
                return latest(Repo.allUnlocked(type));
            }
        }, new Async.Callback<List<Item>>() {
            @Override
            public void on(List<Item> items) {
                if (isFinishing()) return;
                assign(type, items);
                loading.setVisibility(View.GONE);
                render();
                done();
            }
        }, new Async.Callback<Exception>() {
            @Override
            public void on(Exception e) {
                if (isFinishing()) return;
                done();
            }
        });
    }

    private void assign(int type, List<Item> items) {
        if (type == Models.LIVE) data.live = items;
        else if (type == Models.MOVIE) data.movies = items;
        else data.series = items;
    }

    private void done() {
        pending--;
        if (pending > 0) return;
        loadingNow = false;
        loading.setVisibility(View.GONE);
        cached = data;
        cachedAt = System.currentTimeMillis();
        cachedPid = Repo.pid();
        cachedKids = Prefs.kidsMode();
        render();
    }

    /** Ekleme tarihine göre yeniden eskiye; hiç tarih yoksa listenin sonu başa gelir. */
    private static List<Item> latest(List<Item> src) {
        List<Item> l = new ArrayList<>(src);
        Collections.sort(l, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return a.added < b.added ? 1 : (a.added == b.added ? 0 : -1);
            }
        });
        if (l.size() > PER_TYPE) return new ArrayList<>(l.subList(0, PER_TYPE));
        return l;
    }

    private List<Item> current() {
        List<Item> out = new ArrayList<>();
        if (tab == Models.LIVE) out.addAll(data.live);
        else if (tab == Models.MOVIE) out.addAll(data.movies);
        else if (tab == Models.SERIES) out.addAll(data.series);
        else {
            out.addAll(data.live);
            out.addAll(data.movies);
            out.addAll(data.series);
            Collections.sort(out, new Comparator<Item>() {
                @Override
                public int compare(Item a, Item b) {
                    return a.added < b.added ? 1 : (a.added == b.added ? 0 : -1);
                }
            });
        }
        return out;
    }

    private void render() {
        adapter.set(current());
        boolean empty = adapter.getCount() == 0;
        msg.setVisibility(empty && pending <= 0 ? View.VISIBLE : View.GONE);
        msg.setText(R.string.new_empty);
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setSelected((i == 0 && tab == -1)
                    || (i == 1 && tab == Models.LIVE)
                    || (i == 2 && tab == Models.MOVIE)
                    || (i == 3 && tab == Models.SERIES));
        }
    }

    private String date(long unixSec) {
        return new SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(new Date(unixSec * 1000L));
    }

    private void open(int pos) {
        if (pos < 0 || pos >= adapter.getCount()) return;
        Item it = adapter.getItem(pos).item;
        if (it.type == Models.LIVE) {
            List<Item> chans = new ArrayList<>();
            int index = 0;
            for (Item row : adapter.data()) {
                if (row.type == Models.LIVE) {
                    if (row == it) index = chans.size();
                    chans.add(row);
                }
            }
            Play.playLive(this, chans, index);
        } else if (it.type == Models.MOVIE) {
            MovieDetailActivity.open(this, it);
        } else {
            SeriesDetailActivity.open(this, it);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------ Satır adaptörü

    private static class Row {
        Item item;
        String badge;
    }

    private class NewAdapter extends BaseAdapter {
        private final LayoutInflater inf = LayoutInflater.from(NewActivity.this);
        private final List<Row> data = new ArrayList<>();

        void set(List<Item> items) {
            data.clear();
            for (Item it : items) {
                Row r = new Row();
                r.item = it;
                r.badge = it.type == Models.LIVE ? getString(R.string.type_channel)
                        : (it.type == Models.MOVIE ? getString(R.string.type_movie) : getString(R.string.type_series));
                data.add(r);
            }
            notifyDataSetChanged();
        }

        List<Item> data() {
            List<Item> out = new ArrayList<>(data.size());
            for (Row r : data) out.add(r.item);
            return out;
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Row getItem(int p) {
            return data.get(p);
        }

        @Override
        public long getItemId(int p) {
            return p;
        }

        @Override
        public View getView(int p, View v, ViewGroup parent) {
            if (v == null) v = inf.inflate(R.layout.row_new, parent, false);
            Row r = data.get(p);
            Item it = r.item;
            ((TextView) v.findViewById(R.id.title)).setText(it.name);
            TextView sub = v.findViewById(R.id.sub);
            if (it.added > 0) {
                sub.setText(date(it.added));
                sub.setVisibility(View.VISIBLE);
            } else {
                sub.setVisibility(View.GONE);
            }
            ((TextView) v.findViewById(R.id.badge)).setText(r.badge);
            ImageLoader.get().load((ImageView) v.findViewById(R.id.thumb), it.icon,
                    it.type == Models.LIVE ? R.drawable.ph_channel : R.drawable.ph_poster,
                    dp(104), dp(59));
            return v;
        }
    }
}
