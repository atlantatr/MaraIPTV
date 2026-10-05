package com.atlanta.maraplayer.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import com.atlanta.maraplayer.R;
import com.atlanta.maraplayer.data.Models;
import com.atlanta.maraplayer.data.Models.Item;
import com.atlanta.maraplayer.data.Repo;
import com.atlanta.maraplayer.util.Async;
import com.atlanta.maraplayer.util.Ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Kanal / film / dizi arama. */
public class SearchActivity extends BaseActivity {

    public static final String EXTRA_TYPE = "type";
    private static final int MAX_RESULTS = 300;

    private EditText search;
    private ListView list;
    private Adapters.RowAdapter adapter;
    private TextView msg;
    private Button[] tabs;
    private int filter = -1; // -1 = tumu
    private final Map<Integer, List<Item>> data = new HashMap<>();
    private final Map<Integer, String[]> normNames = new HashMap<>();
    private int loadingCount;

    private final Runnable doSearch = new Runnable() {
        @Override
        public void run() {
            runSearch();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (sessionLost) return;
        setContentView(R.layout.activity_list);
        filter = getIntent().getIntExtra(EXTRA_TYPE, -1);
        ((TextView) v(R.id.title)).setText("Ara");
        search = v(R.id.search);
        search.setVisibility(View.VISIBLE);
        list = v(R.id.list);
        msg = v(R.id.msg);
        adapter = new Adapters.RowAdapter(this);
        list.setAdapter(adapter);

        v(R.id.tabs).setVisibility(View.VISIBLE);
        tabs = new Button[]{v(R.id.tab0), v(R.id.tab1), v(R.id.tab2), v(R.id.tab3)};
        tabs[3].setVisibility(View.VISIBLE);
        final String[] names = {"Tumu", "Kanallar", "Filmler", "Diziler"};
        final int[] types = {-1, Models.LIVE, Models.MOVIE, Models.SERIES};
        for (int i = 0; i < 4; i++) {
            final int t = types[i];
            tabs[i].setText(names[i]);
            tabs[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    filter = t;
                    ensureLoaded();
                    updateTabs();
                    runSearch();
                }
            });
        }
        updateTabs();

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                Async.MAIN.removeCallbacks(doSearch);
                Async.MAIN.postDelayed(doSearch, 400);
            }
        });
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView tv, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                    hideKeyboard();
                    runSearch();
                    if (adapter.getCount() > 0) list.requestFocus();
                    return true;
                }
                return false;
            }
        });

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                Item it = (Item) adapter.getItem(pos).tag;
                if (it.type == Models.LIVE) {
                    List<Item> chans = new ArrayList<>();
                    int idx = 0;
                    for (Adapters.Row r : adapter.data()) {
                        Item x = (Item) r.tag;
                        if (x.type == Models.LIVE) {
                            if (x == it) idx = chans.size();
                            chans.add(x);
                        }
                    }
                    PlayerActivity.playLive(SearchActivity.this, chans, idx);
                } else if (it.type == Models.MOVIE) {
                    MovieDetailActivity.open(SearchActivity.this, it);
                } else {
                    SeriesDetailActivity.open(SearchActivity.this, it);
                }
            }
        });

        ensureLoaded();
        search.requestFocus();
    }

    private void updateTabs() {
        int[] types = {-1, Models.LIVE, Models.MOVIE, Models.SERIES};
        for (int i = 0; i < 4; i++) tabs[i].setSelected(types[i] == filter);
    }

    private void ensureLoaded() {
        int[] want = filter == -1 ? new int[]{Models.LIVE, Models.MOVIE, Models.SERIES} : new int[]{filter};
        for (final int t : want) {
            if (data.containsKey(t)) continue;
            data.put(t, new ArrayList<Item>()); // tekrar yuklemeyi engelle
            loadingCount++;
            v(R.id.loading).setVisibility(View.VISIBLE);
            Async.run(this, new Async.Task<List<Item>>() {
                @Override
                public List<Item> run() throws Exception {
                    return Repo.allUnlocked(t);
                }
            }, new Async.Callback<List<Item>>() {
                @Override
                public void on(List<Item> l) {
                    data.put(t, l);
                    String[] n = new String[l.size()];
                    for (int i = 0; i < n.length; i++) n[i] = Ui.norm(l.get(i).name);
                    normNames.put(t, n);
                    done();
                    runSearch();
                }
            }, new Async.Callback<Exception>() {
                @Override
                public void on(Exception e) {
                    data.remove(t);
                    Ui.toast(SearchActivity.this, "Liste yuklenemedi: " + Ui.err(e));
                    done();
                }
            });
        }
    }

    private void done() {
        loadingCount--;
        if (loadingCount <= 0) v(R.id.loading).setVisibility(View.GONE);
    }

    private void runSearch() {
        String q = Ui.norm(search.getText().toString().trim());
        List<Adapters.Row> rows = new ArrayList<>();
        if (q.length() >= 2) {
            int[] types = filter == -1 ? new int[]{Models.LIVE, Models.MOVIE, Models.SERIES} : new int[]{filter};
            for (int t : types) {
                List<Item> l = data.get(t);
                String[] n = normNames.get(t);
                if (l == null || n == null) continue;
                for (int i = 0; i < l.size() && rows.size() < MAX_RESULTS; i++) {
                    if (!n[i].contains(q)) continue;
                    Item it = l.get(i);
                    Adapters.Row r = new Adapters.Row();
                    r.title = it.name;
                    String cat = Repo.categoryName(t, it.categoryId);
                    r.sub = (t == Models.LIVE ? "Kanal" : t == Models.MOVIE ? "Film" : "Dizi") + (cat != null ? "  ·  " + cat : "");
                    r.icon = it.icon;
                    r.showIcon = true;
                    r.placeholder = t == Models.LIVE ? R.drawable.ph_channel : R.drawable.ph_poster;
                    r.tag = it;
                    rows.add(r);
                }
            }
        }
        adapter.set(rows);
        if (q.length() < 2) {
            msg.setText("En az 2 harf yazin");
            msg.setVisibility(View.VISIBLE);
        } else if (rows.isEmpty()) {
            msg.setText(loadingCount > 0 ? "Listeler yukleniyor..." : "Sonuc bulunamadi");
            msg.setVisibility(View.VISIBLE);
        } else {
            msg.setVisibility(View.GONE);
        }
        ((TextView) v(R.id.hint)).setText(rows.size() >= MAX_RESULTS ? "Ilk " + MAX_RESULTS + " sonuc" : rows.size() + " sonuc");
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Async.MAIN.removeCallbacks(doSearch);
    }
}
