package com.atlanta.maraplayer.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.atlanta.maraplayer.R;
import com.atlanta.maraplayer.data.Db;
import com.atlanta.maraplayer.data.Models;
import com.atlanta.maraplayer.data.Models.Category;
import com.atlanta.maraplayer.data.Models.Epg;
import com.atlanta.maraplayer.data.Models.Item;
import com.atlanta.maraplayer.data.Prefs;
import com.atlanta.maraplayer.data.Repo;
import com.atlanta.maraplayer.player.PlayerFactory;
import com.atlanta.maraplayer.util.Async;
import com.atlanta.maraplayer.util.Ui;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;

import java.util.ArrayList;
import java.util.List;

public class LiveActivity extends BaseActivity {

    private ListView cats, channels;
    private Adapters.CategoryAdapter catAdapter;
    private Adapters.ChannelAdapter chAdapter;
    private TextView catTitle, msg, chName, nowTitle, nowDesc, nextList, previewHint;
    private ProgressBar loading, nowProgress, previewLoading;
    private SurfaceView preview;

    private Category currentCat;
    private int loadToken;
    private int epgToken;
    private String epgFor;

    private ExoPlayer previewPlayer;
    private String previewId;

    private Category pendingCat;
    private final Runnable catHover = new Runnable() {
        @Override
        public void run() {
            if (pendingCat != null) openCategory(pendingCat, false);
        }
    };
    private Item pendingEpg;
    private final Runnable epgHover = new Runnable() {
        @Override
        public void run() {
            if (pendingEpg != null) loadEpg(pendingEpg);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (sessionLost) return;
        setContentView(R.layout.activity_live);
        cats = v(R.id.cats);
        channels = v(R.id.channels);
        catTitle = v(R.id.catTitle);
        msg = v(R.id.msg);
        loading = v(R.id.loading);
        chName = v(R.id.chName);
        nowTitle = v(R.id.nowTitle);
        nowDesc = v(R.id.nowDesc);
        nowProgress = v(R.id.nowProgress);
        nextList = v(R.id.nextList);
        preview = v(R.id.preview);
        previewHint = v(R.id.previewHint);
        previewLoading = v(R.id.previewLoading);

        catAdapter = new Adapters.CategoryAdapter(this);
        cats.setAdapter(catAdapter);
        chAdapter = new Adapters.ChannelAdapter(this);
        channels.setAdapter(chAdapter);

        if (!Prefs.preview()) {
            previewHint.setText("Onizleme kapali\nOK: Tam ekran");
        }

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

        channels.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View view, int pos, long id) {
                Item it = chAdapter.getItem(pos);
                chName.setText(it.name);
                pendingEpg = it;
                Async.MAIN.removeCallbacks(epgHover);
                Async.MAIN.postDelayed(epgHover, 500);
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        channels.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                channels.setItemChecked(pos, true);
                Item it = chAdapter.getItem(pos);
                if (Prefs.preview() && !Prefs.externalPlayer() && !it.id.equals(previewId)) {
                    startPreview(it);
                    loadEpg(it);
                } else {
                    fullscreen(pos);
                }
            }
        });
        channels.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> p, View view, int pos, long id) {
                channelOptions(chAdapter.getItem(pos));
                return true;
            }
        });

        loadCategories();
    }

    // ------------------------------------------------------------ Kategoriler

    private void loadCategories() {
        loading.setVisibility(View.VISIBLE);
        Async.run(this, new Async.Task<List<Category>>() {
            @Override
            public List<Category> run() throws Exception {
                return Repo.categories(Models.LIVE);
            }
        }, new Async.Callback<List<Category>>() {
            @Override
            public void on(List<Category> list) {
                loading.setVisibility(View.GONE);
                List<Category> all = new ArrayList<>();
                all.add(new Category(Category.SEARCH, "Ara...", Models.LIVE));
                all.add(new Category(Category.FAV, "Favoriler", Models.LIVE));
                all.add(new Category(Category.ALL, "Tum Kanallar", Models.LIVE));
                all.addAll(list);
                catAdapter.set(all);
                // son kategoriyi ac
                String last = Prefs.lastLiveCategory(Repo.pid());
                int idx = 3 < all.size() ? 3 : 2;
                if (last != null) {
                    for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(last)) idx = i;
                }
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
                showMsg("Kategoriler yuklenemedi:\n" + Ui.err(e));
            }
        });
    }

    private void openCategory(final Category c, boolean byClick) {
        if (c.id.equals(Category.SEARCH)) {
            if (byClick) startActivity(new Intent(this, SearchActivity.class).putExtra(SearchActivity.EXTRA_TYPE, Models.LIVE));
            return;
        }
        if (currentCat == c && !byClick) return;
        if (!c.isVirtual() && Prefs.isLocked(Repo.pid(), c.type, c.id, c.name)) {
            currentCat = null;
            chAdapter.set(new ArrayList<Item>());
            catTitle.setText(c.name);
            showMsg("Bu kategori kilitli.\nAcmak icin OK'e basip PIN girin.");
            if (byClick) {
                Ui.askPin(this, "Kilitli kategori - PIN", new Runnable() {
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
            channels.requestFocus();
            return;
        }
        currentCat = c;
        int pos = catAdapter.data().indexOf(c);
        if (pos >= 0) cats.setItemChecked(pos, true);
        catTitle.setText(c.name);
        Prefs.setLastLiveCategory(Repo.pid(), c.id);
        final int token = ++loadToken;
        msg.setVisibility(View.GONE);
        loading.setVisibility(View.VISIBLE);
        chAdapter.set(new ArrayList<Item>());
        final boolean focusAfter = byClick;
        Async.run(this, new Async.Task<List<Item>>() {
            @Override
            public List<Item> run() throws Exception {
                if (c.id.equals(Category.FAV)) return Db.get().favorites(Repo.pid(), Models.LIVE);
                if (c.id.equals(Category.ALL)) return Repo.allUnlocked(Models.LIVE);
                return Repo.items(Models.LIVE, c.id);
            }
        }, new Async.Callback<List<Item>>() {
            @Override
            public void on(List<Item> list) {
                if (token != loadToken) return;
                loading.setVisibility(View.GONE);
                chAdapter.set(list);
                catTitle.setText(c.name + "  (" + list.size() + ")");
                if (list.isEmpty()) {
                    showMsg(c.id.equals(Category.FAV)
                            ? "Favori kanaliniz yok.\nKanal uzerinde MENU > Favorilere ekle"
                            : "Bu kategoride kanal yok.");
                    return;
                }
                // son izlenen kanali sec
                String last = Prefs.lastChannel(Repo.pid());
                int sel = 0;
                if (last != null) {
                    for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(last)) { sel = i; break; }
                }
                channels.setSelection(sel);
                if (focusAfter) channels.requestFocus();
            }
        }, new Async.Callback<Exception>() {
            @Override
            public void on(Exception e) {
                if (token != loadToken) return;
                loading.setVisibility(View.GONE);
                showMsg("Kanallar yuklenemedi:\n" + Ui.err(e));
            }
        });
    }

    private void showMsg(String s) {
        msg.setText(s);
        msg.setVisibility(View.VISIBLE);
    }

    private void categoryOptions(final Category c) {
        if (c.isVirtual()) return;
        final boolean locked = Prefs.isLockedPermanent(Repo.pid(), c.type, c.id, c.name);
        Ui.choose(this, c.name, new String[]{locked ? "Kilidi kaldir" : "Kategoriyi kilitle"}, -1, new Ui.Choice() {
            @Override
            public void on(int which) {
                Ui.askPin(LiveActivity.this, "Ebeveyn PIN", new Runnable() {
                    @Override
                    public void run() {
                        Prefs.setLocked(Repo.pid(), c.type, c.id, c.name, !locked);
                        if (!locked) {
                            Ui.toast(LiveActivity.this, "Kategori kilitlendi");
                            Prefs.clearSessionUnlocks();
                        }
                        catAdapter.notifyDataSetChanged();
                        currentCat = null;
                        openCategory(c, false);
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------ Kanal

    private void channelOptions(final Item it) {
        final boolean fav = Db.get().isFavorite(Repo.pid(), Models.LIVE, it.id);
        String[] opts = {fav ? "Favorilerden cikar" : "Favorilere ekle", "Yayin akisi" + (it.archive ? " / Geri izle" : ""), "Tam ekran izle"};
        Ui.choose(this, it.name, opts, -1, new Ui.Choice() {
            @Override
            public void on(int which) {
                if (which == 0) {
                    boolean now = Db.get().toggleFavorite(Repo.pid(), it);
                    Ui.toast(LiveActivity.this, now ? "Favorilere eklendi" : "Favorilerden cikarildi");
                    if (currentCat != null && currentCat.id.equals(Category.FAV)) {
                        Category c = currentCat;
                        currentCat = null;
                        openCategory(c, false);
                    } else {
                        chAdapter.reloadFavs();
                    }
                } else if (which == 1) {
                    EpgActivity.open(LiveActivity.this, it);
                } else {
                    int pos = chAdapter.data().indexOf(it);
                    if (pos >= 0) fullscreen(pos);
                }
            }
        });
    }

    private void fullscreen(int pos) {
        releasePreview();
        PlayerActivity.playLive(this, chAdapter.data(), pos);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) {
            if (channels.hasFocus() && channels.getSelectedItemPosition() >= 0) {
                channelOptions(chAdapter.getItem(channels.getSelectedItemPosition()));
                return true;
            }
            if (cats.hasFocus() && cats.getSelectedItemPosition() >= 0) {
                categoryOptions(catAdapter.getItem(cats.getSelectedItemPosition()));
                return true;
            }
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && channels.hasFocus()) {
            cats.requestFocus();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------ EPG

    private void loadEpg(final Item it) {
        if (it.id.equals(epgFor)) return;
        epgFor = it.id;
        chName.setText(it.name);
        nowTitle.setText("Yayin akisi yukleniyor...");
        nowDesc.setText("");
        nextList.setText("");
        nowProgress.setVisibility(View.INVISIBLE);
        final int token = ++epgToken;
        Async.run(this, new Async.Task<List<Epg>>() {
            @Override
            public List<Epg> run() throws Exception {
                return Repo.api().shortEpg(it.id, 6);
            }
        }, new Async.Callback<List<Epg>>() {
            @Override
            public void on(List<Epg> list) {
                if (token != epgToken) return;
                showEpg(list);
            }
        }, new Async.Callback<Exception>() {
            @Override
            public void on(Exception e) {
                if (token != epgToken) return;
                epgFor = null;
                nowTitle.setText("Yayin akisi yok");
            }
        });
    }

    private void showEpg(List<Epg> list) {
        long off = Prefs.epgOffsetSec();
        long now = System.currentTimeMillis() / 1000L - off;
        Epg current = null;
        StringBuilder next = new StringBuilder();
        for (Epg e : list) {
            if (e.stop <= now) continue;
            if (current == null && e.isNow(now)) {
                current = e;
                continue;
            }
            if (next.length() > 0) next.append('\n');
            next.append(Ui.time(e.start + off)).append("  ").append(e.title);
        }
        if (current != null) {
            nowTitle.setText(Ui.time(current.start + off) + " - " + Ui.time(current.stop + off) + "   " + current.title);
            nowDesc.setText(current.description);
            int pct = (int) ((now - current.start) * 100 / Math.max(1, current.stop - current.start));
            nowProgress.setProgress(pct);
            nowProgress.setVisibility(View.VISIBLE);
        } else {
            nowTitle.setText(list.isEmpty() ? "Yayin akisi yok" : "");
            nowDesc.setText("");
            nowProgress.setVisibility(View.INVISIBLE);
        }
        nextList.setText(next.toString());
    }

    // ------------------------------------------------------------ Onizleme

    private void startPreview(Item it) {
        releasePreview();
        previewId = it.id;
        previewHint.setVisibility(View.GONE);
        previewLoading.setVisibility(View.VISIBLE);
        previewPlayer = PlayerFactory.create(this, true);
        previewPlayer.setVideoSurfaceView(preview);
        previewPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                previewLoading.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                previewLoading.setVisibility(View.GONE);
                previewHint.setVisibility(View.VISIBLE);
                previewHint.setText("Yayin acilamadi\n" + error.getErrorCodeName());
                previewId = null;
            }
        });
        previewPlayer.setMediaSource(PlayerFactory.source(Repo.api().liveUrl(it.id, Prefs.liveFormat())));
        previewPlayer.setPlayWhenReady(true);
        previewPlayer.prepare();
        Prefs.setLastChannel(Repo.pid(), it.id);
    }

    private void releasePreview() {
        if (previewPlayer != null) {
            previewPlayer.release();
            previewPlayer = null;
        }
        previewId = null;
        if (previewHint != null) {
            previewHint.setVisibility(View.VISIBLE);
            previewHint.setText(Prefs.preview() ? "OK: Onizleme\nTekrar OK: Tam ekran" : "Onizleme kapali\nOK: Tam ekran");
            previewLoading.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (chAdapter != null) chAdapter.reloadFavs();
        // tam ekrandan donuste son izlenen kanali sec
        String last = Prefs.lastChannel(Repo.pid());
        if (last != null && chAdapter != null) {
            List<Item> d = chAdapter.data();
            for (int i = 0; i < d.size(); i++) {
                if (d.get(i).id.equals(last)) {
                    channels.setSelection(i);
                    channels.setItemChecked(i, true);
                    break;
                }
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        releasePreview();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Async.MAIN.removeCallbacks(catHover);
        Async.MAIN.removeCallbacks(epgHover);
    }
}
