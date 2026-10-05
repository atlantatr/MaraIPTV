package com.atlanta.maraplayer.ui;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import com.atlanta.maraplayer.R;
import com.atlanta.maraplayer.data.Db;
import com.atlanta.maraplayer.data.Models;
import com.atlanta.maraplayer.data.Models.Item;
import com.atlanta.maraplayer.data.Repo;
import com.atlanta.maraplayer.util.Ui;

import java.util.ArrayList;
import java.util.List;

/** Favoriler (kanal/film/dizi) veya "Devam Et" listesi. */
public class FavoritesActivity extends BaseActivity {

    public static final String EXTRA_MODE = "mode";
    public static final int MODE_FAV = 0;
    public static final int MODE_CONTINUE = 1;

    private int mode;
    private int tab = Models.LIVE;
    private ListView list;
    private Adapters.RowAdapter adapter;
    private TextView msg;
    private Button[] tabs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (sessionLost) return;
        setContentView(R.layout.activity_list);
        mode = getIntent().getIntExtra(EXTRA_MODE, MODE_FAV);
        list = v(R.id.list);
        msg = v(R.id.msg);
        adapter = new Adapters.RowAdapter(this);
        list.setAdapter(adapter);
        ((TextView) v(R.id.title)).setText(mode == MODE_FAV ? "Favoriler" : "Izlemeye Devam Et");
        ((TextView) v(R.id.hint)).setText("MENU / uzun bas: listeden kaldir");

        if (mode == MODE_FAV) {
            v(R.id.tabs).setVisibility(View.VISIBLE);
            tabs = new Button[]{v(R.id.tab0), v(R.id.tab1), v(R.id.tab2)};
            String[] names = {"Kanallar", "Filmler", "Diziler"};
            for (int i = 0; i < 3; i++) {
                final int t = i;
                tabs[i].setText(names[i]);
                tabs[i].setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        tab = t;
                        reload();
                        if (adapter.getCount() > 0) list.requestFocus();
                    }
                });
                tabs[i].setOnFocusChangeListener(new View.OnFocusChangeListener() {
                    @Override
                    public void onFocusChange(View view, boolean has) {
                        if (has && tab != t) {
                            tab = t;
                            reload();
                        }
                    }
                });
            }
        }

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                open(pos);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> p, View view, int pos, long id) {
                remove(pos);
                return true;
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter == null) return;
        reload();
        if (mode == MODE_CONTINUE || adapter.getCount() > 0) list.requestFocus();
        else if (tabs != null) tabs[tab].requestFocus();
    }

    private void reload() {
        List<Adapters.Row> rows = new ArrayList<>();
        long pid = Repo.pid();
        if (mode == MODE_FAV) {
            for (Item it : Db.get().favorites(pid, tab)) {
                Adapters.Row r = new Adapters.Row(it.name, null, it);
                r.icon = it.icon;
                r.showIcon = true;
                r.placeholder = tab == Models.LIVE ? R.drawable.ph_channel : R.drawable.ph_poster;
                rows.add(r);
            }
            for (int i = 0; i < 3; i++) tabs[i].setSelected(i == tab);
        } else {
            for (Db.Resume res : Db.get().continueWatching(pid, 60)) {
                Adapters.Row r = new Adapters.Row();
                r.title = res.type == Models.EPISODE ? res.seriesName : res.name;
                r.sub = res.type == Models.EPISODE ? res.subtitle + "  " + res.name : "Film";
                r.right = Ui.duration(res.position) + " / " + Ui.duration(res.duration);
                r.progress = res.percent();
                r.icon = res.icon;
                r.showIcon = true;
                r.placeholder = R.drawable.ph_poster;
                r.tag = res;
                rows.add(r);
            }
        }
        adapter.set(rows);
        msg.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        msg.setText(mode == MODE_FAV ? "Bu listede favori yok." : "Yarida kalan film veya bolum yok.");
    }

    private void open(int pos) {
        Object tag = adapter.getItem(pos).tag;
        if (tag instanceof Db.Resume) {
            PlayerActivity.playResume(this, (Db.Resume) tag);
            return;
        }
        Item it = (Item) tag;
        if (it.type == Models.LIVE) {
            List<Item> chans = new ArrayList<>();
            for (Adapters.Row r : adapter.data()) chans.add((Item) r.tag);
            PlayerActivity.playLive(this, chans, pos);
        } else if (it.type == Models.MOVIE) {
            MovieDetailActivity.open(this, it);
        } else {
            SeriesDetailActivity.open(this, it);
        }
    }

    private void remove(final int pos) {
        if (pos < 0 || pos >= adapter.getCount()) return;
        final Object tag = adapter.getItem(pos).tag;
        Ui.confirm(this, "Kaldir", adapter.getItem(pos).title + " listeden kaldirilsin mi?", new Runnable() {
            @Override
            public void run() {
                if (tag instanceof Db.Resume) Db.get().deleteResume(Repo.pid(), ((Db.Resume) tag).key);
                else Db.get().toggleFavorite(Repo.pid(), (Item) tag);
                reload();
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) && list.hasFocus()) {
            remove(list.getSelectedItemPosition());
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
