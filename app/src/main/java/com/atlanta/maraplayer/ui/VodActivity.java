package com.atlanta.maraplayer.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.GridView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.atlanta.maraplayer.R;
import com.atlanta.maraplayer.data.Db;
import com.atlanta.maraplayer.data.Models;
import com.atlanta.maraplayer.data.Models.Category;
import com.atlanta.maraplayer.data.Models.Item;
import com.atlanta.maraplayer.data.Prefs;
import com.atlanta.maraplayer.data.Repo;
import com.atlanta.maraplayer.util.Async;
import com.atlanta.maraplayer.util.Ui;

import java.util.ArrayList;
import java.util.List;

/** Filmler veya Diziler (EXTRA_TYPE) icin kategori + poster izgarasi. */
public class VodActivity extends BaseActivity {

    public static final String EXTRA_TYPE = "type";
    private String[] SORTS;

    private int type;
    private ListView cats;
    private GridView grid;
    private Adapters.CategoryAdapter catAdapter;
    private Adapters.PosterAdapter adapter;
    private TextView catTitle, msg, sortInfo;
    private ProgressBar loading;
    private Category currentCat;
    private List<Item> rawList = new ArrayList<>();
    private int sortMode;
    private int loadToken;

    private Category pendingCat;
    private final Runnable catHover = new Runnable() {
        @Override
        public void run() {
            if (pendingCat != null) openCategory(pendingCat, false);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (sessionLost) return;
        setContentView(R.layout.activity_vod);
        type = getIntent().getIntExtra(EXTRA_TYPE, Models.MOVIE);
        cats = v(R.id.cats);
        grid = v(R.id.grid);
        catTitle = v(R.id.catTitle);
        msg = v(R.id.msg);
        sortInfo = v(R.id.sortInfo);
        loading = v(R.id.loading);
        SORTS = getResources().getStringArray(R.array.sort_names);
        ((TextView) v(R.id.title)).setText(type == Models.MOVIE ? R.string.tile_movies : R.string.tile_series);
        sortMode = Prefs.sp().getInt("sort_" + type, 0);
        sortInfo.setText(getString(R.string.menu_sort_x, SORTS[sortMode]));
        ListNav.install(cats);
        ListNav.install(grid);

        catAdapter = new Adapters.CategoryAdapter(this);
        cats.setAdapter(catAdapter);
        adapter = new Adapters.PosterAdapter(this, type);
        grid.setAdapter(adapter);

        cats.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View view, int pos, long id) {
                Category c = catAdapter.getItem(pos);
                if (c.id.equals(Category.SEARCH)) return;
                pendingCat = c;
                Async.MAIN.removeCallbacks(catHover);
                Async.MAIN.postDelayed(catHover, 450);
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        cats.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                Async.MAIN.removeCallbacks(catHover);
                openCategory(catAdapter.getItem(pos), true);
            }
        });
        cats.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> p, View view, int pos, long id) {
                categoryOptions(catAdapter.getItem(pos));
                return true;
            }
        });
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                openItem(adapter.getItem(pos));
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> p, View view, int pos, long id) {
                itemOptions(adapter.getItem(pos));
                return true;
            }
        });

        loadCategories();
    }

    private void openItem(Item it) {
        if (type == Models.MOVIE) MovieDetailActivity.open(this, it);
        else SeriesDetailActivity.open(this, it);
    }

    private void loadCategories() {
        loading.setVisibility(View.VISIBLE);
        Async.run(this, new Async.Task<List<Category>>() {
            @Override
            public List<Category> run() throws Exception {
                return Repo.visibleCategories(type);
            }
        }, new Async.Callback<List<Category>>() {
            @Override
            public void on(List<Category> list) {
                loading.setVisibility(View.GONE);
                List<Category> all = new ArrayList<>();
                all.add(new Category(Category.SEARCH, getString(R.string.search_dots), type));
                all.add(new Category(Category.FAV, getString(R.string.favorites), type));
                all.add(new Category(Category.ALL, getString(R.string.all), type));
                all.addAll(list);
                catAdapter.set(all);
                int idx = all.size() > 3 ? 3 : 2;
                Category c = all.get(idx);
                if (!c.isVirtual() && Prefs.isLocked(Repo.pid(), c.type, c.id, c.name)) idx = 1;
                cats.setSelection(idx);
                cats.setItemChecked(idx, true);
                cats.requestFocus();
                openCategory(all.get(idx), false);
            }
        }, new Async.Callback<Exception>() {
            @Override
            public void on(Exception e) {
                loading.setVisibility(View.GONE);
                showMsg(getString(R.string.categories_failed) + "\n" + Ui.err(e));
            }
        });
    }

    private void openCategory(final Category c, final boolean byClick) {
        if (c.id.equals(Category.SEARCH)) {
            if (byClick) startActivity(new Intent(this, SearchActivity.class).putExtra(SearchActivity.EXTRA_TYPE, type));
            return;
        }
        if (currentCat == c && !byClick) return;
        if (!c.isVirtual() && Prefs.isLocked(Repo.pid(), c.type, c.id, c.name)) {
            currentCat = null;
            adapter.set(new ArrayList<Item>());
            catTitle.setText(c.name);
            showMsg(getString(R.string.category_locked_msg));
            if (byClick) {
                Ui.askPin(this, getString(R.string.locked_category_pin), new Runnable() {
                    @Override
                    public void run() {
                        Prefs.unlockForSession(Repo.pid(), c.type, c.id);
                        catAdapter.notifyDataSetChanged();
                        openCategory(c, true);
                    }
                });
            }
            return;
        }
        if (byClick && currentCat == c) {
            if (adapter.getCount() > 0) grid.requestFocus();
            return;
        }
        currentCat = c;
        int pos = catAdapter.data().indexOf(c);
        if (pos >= 0) cats.setItemChecked(pos, true);
        catTitle.setText(c.name);
        msg.setVisibility(View.GONE);
        loading.setVisibility(View.VISIBLE);
        adapter.set(new ArrayList<Item>());
        final int token = ++loadToken;
        Async.run(this, new Async.Task<List<Item>>() {
            @Override
            public List<Item> run() throws Exception {
                if (c.id.equals(Category.FAV)) return Db.get().favorites(Repo.pid(), type);
                if (c.id.equals(Category.ALL)) return Repo.allUnlocked(type);
                return Repo.items(type, c.id);
            }
        }, new Async.Callback<List<Item>>() {
            @Override
            public void on(List<Item> list) {
                if (token != loadToken) return;
                loading.setVisibility(View.GONE);
                rawList = list;
                applySort();
                catTitle.setText(c.name + "  (" + list.size() + ")");
                if (list.isEmpty()) {
                    showMsg(getString(c.id.equals(Category.FAV) ? R.string.no_fav_posters : R.string.category_empty));
                } else if (byClick) {
                    grid.requestFocus();
                }
            }
        }, new Async.Callback<Exception>() {
            @Override
            public void on(Exception e) {
                if (token != loadToken) return;
                loading.setVisibility(View.GONE);
                showMsg(getString(R.string.list_failed) + "\n" + Ui.err(e));
            }
        });
    }

    private void applySort() {
        adapter.set(Repo.sorted(rawList, sortMode));
        grid.setSelection(0);
    }

    private void showMsg(String s) {
        msg.setText(s);
        msg.setVisibility(View.VISIBLE);
    }

    private void categoryOptions(final Category c) {
        if (c.isVirtual()) return;
        final boolean locked = Prefs.isLockedPermanent(Repo.pid(), c.type, c.id, c.name);
        Ui.choose(this, c.name, new String[]{getString(locked ? R.string.unlock_category : R.string.lock_category)}, -1, new Ui.Choice() {
            @Override
            public void on(int which) {
                Ui.askPin(VodActivity.this, getString(R.string.parental_pin), new Runnable() {
                    @Override
                    public void run() {
                        Prefs.setLocked(Repo.pid(), c.type, c.id, c.name, !locked);
                        if (!locked) Prefs.clearSessionUnlocks();
                        catAdapter.notifyDataSetChanged();
                        currentCat = null;
                        openCategory(c, false);
                    }
                });
            }
        });
    }

    private void itemOptions(final Item it) {
        final boolean fav = Db.get().isFavorite(Repo.pid(), type, it.id);
        Ui.choose(this, it.name, new String[]{getString(R.string.open), getString(fav ? R.string.fav_remove : R.string.fav_add)}, -1, new Ui.Choice() {
            @Override
            public void on(int which) {
                if (which == 0) {
                    openItem(it);
                } else {
                    boolean now = Db.get().toggleFavorite(Repo.pid(), it);
                    Ui.toast(VodActivity.this, getString(now ? R.string.fav_added : R.string.fav_removed));
                    adapter.reloadFavs();
                }
            }
        });
    }

    private void chooseSort() {
        Ui.choose(this, getString(R.string.sort), SORTS, sortMode, new Ui.Choice() {
            @Override
            public void on(int which) {
                sortMode = which;
                Prefs.sp().edit().putInt("sort_" + type, which).apply();
                sortInfo.setText(getString(R.string.menu_sort_x, SORTS[sortMode]));
                applySort();
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) {
            if (grid.hasFocus() && grid.getSelectedItemPosition() >= 0) {
                final Item it = adapter.getItem(grid.getSelectedItemPosition());
                Ui.choose(this, it.name, new String[]{getString(R.string.fav_toggle), getString(R.string.sort)}, -1, new Ui.Choice() {
                    @Override
                    public void on(int which) {
                        if (which == 0) {
                            boolean now = Db.get().toggleFavorite(Repo.pid(), it);
                            Ui.toast(VodActivity.this, getString(now ? R.string.fav_added : R.string.fav_removed));
                            adapter.reloadFavs();
                        } else {
                            chooseSort();
                        }
                    }
                });
                return true;
            }
            if (cats.hasFocus() && cats.getSelectedItemPosition() >= 0) {
                Category c = catAdapter.getItem(cats.getSelectedItemPosition());
                if (!c.isVirtual()) categoryOptions(c);
                else chooseSort();
                return true;
            }
            chooseSort();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (grid.hasFocus()) {
                cats.requestFocus();
                return true;
            }
            if (cats.hasFocus()) {
                startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                finish();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter != null) adapter.reloadFavs();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Async.MAIN.removeCallbacks(catHover);
    }
}
