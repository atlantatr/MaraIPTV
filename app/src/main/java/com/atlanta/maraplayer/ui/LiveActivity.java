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

import com.atlanta.maraplayer.BuildConfig;
import com.atlanta.maraplayer.R;
import com.atlanta.maraplayer.data.BroadcastApi;
import com.atlanta.maraplayer.data.Db;
import com.atlanta.maraplayer.data.Models;
import com.atlanta.maraplayer.data.Models.Category;
import com.atlanta.maraplayer.data.Models.Epg;
import com.atlanta.maraplayer.data.Models.Item;
import com.atlanta.maraplayer.data.Prefs;
import com.atlanta.maraplayer.data.Repo;
import com.atlanta.maraplayer.player.PreviewPlayer;
import com.atlanta.maraplayer.util.Async;
import com.atlanta.maraplayer.util.Ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    private PreviewPlayer previewPlayer;
    private long currentProgramEnd;
    private String previewId;
    private Item previewItem;
    private boolean previewRelay;
    /** Onizlemesi hata veren kanal: tekrar denemek yerine dogrudan tam ekran acilir. */
    private String previewFailed;

    // onResume'da son izlenen kanal ID'si buraya yazilir; kategori listesi
    // asenkron gelince openCategory callback'i bu ID'ye gore sec + onizleme/EPG
    // baslatir. Boylece onizleme/EPG icin kanal listesinin hazir olup olmamasi
    // onemli degil (ilk acilista liste henuz bos olmasina ragmen dogru kanala gider).
    private String pendingId;

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
    /** Kanal odakta beklerken onizlemeyi baslat (OK artik dogrudan tam ekran). */
    private Item pendingPreview;
    private final Runnable previewHover = new Runnable() {
        @Override
        public void run() {
            Item it = pendingPreview;
            if (it == null || previewPlayer != null) return;
            if (!Prefs.preview() || Prefs.externalPlayer()) return;
            if (it.id.equals(previewFailed)) return;
            startPreview(it);
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
        ListNav.install(cats);
        ListNav.install(channels);

        previewHint.setText(Prefs.preview() ? R.string.preview_hint : R.string.preview_off_hint);

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
                chName.setText(BroadcastApi.displayChannel(it.name));
                pendingEpg = it;
                Async.MAIN.removeCallbacks(epgHover);
                Async.MAIN.postDelayed(epgHover, 500);
                // Odakta bekleyen kanal icin kucuk onizleme (OK ile tam ekran).
                pendingPreview = it;
                Async.MAIN.removeCallbacks(previewHover);
                Async.MAIN.postDelayed(previewHover, 700);
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        channels.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                // OK: dogrudan tam ekran (onizleme odakta bekleyince kendiliginden acilir)
                channels.setItemChecked(pos, true);
                fullscreen(pos);
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
                return Repo.visibleCategories(Models.LIVE);
            }
        }, new Async.Callback<List<Category>>() {
            @Override
            public void on(List<Category> list) {
                loading.setVisibility(View.GONE);
                List<Category> all = new ArrayList<>();
                all.add(new Category(Category.SEARCH, getString(R.string.search_dots), Models.LIVE));
                all.add(new Category(Category.RECENT, getString(R.string.recently_watched), Models.LIVE));
                all.add(new Category(Category.FAV, getString(R.string.favorites), Models.LIVE));
                all.add(new Category(Category.ALL, getString(R.string.all_channels), Models.LIVE));
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
                showMsg(getString(R.string.categories_failed) + "\n" + Ui.err(e));
            }
        });
    }

    /** Son izlenen kanallar (recent first, unlocked only) */
    private List<Item> recentChannels(long pid) {
        String raw = Prefs.recentChannels(pid);
        if (raw == null || raw.isEmpty()) return new ArrayList<Item>();
        Map<String, Item> byId = new HashMap<String, Item>();
        try {
            for (Item it : Repo.allUnlocked(Models.LIVE)) byId.put(it.id, it);
        } catch (java.io.IOException e) {
            return new ArrayList<Item>();
        }
        List<Item> out = new ArrayList<Item>();
        for (String id : raw.split(",")) {
            Item it = byId.get(id);
            if (it != null) out.add(it);
        }
        return out;
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
                if (c.id.equals(Category.FAV)) return Repo.filterKids(Models.LIVE, Db.get().favorites(Repo.pid(), Models.LIVE));
                if (c.id.equals(Category.ALL)) return Repo.allUnlocked(Models.LIVE);
                if (c.id.equals(Category.RECENT)) return recentChannels(Repo.pid());
                return Repo.items(Models.LIVE, c.id);
            }
        }, new Async.Callback<List<Item>>() {
            @Override
            public void on(List<Item> list) {
                if (token != loadToken) return;
                loading.setVisibility(View.GONE);
                list = dedupe(list);
                chAdapter.set(list);
                catTitle.setText(c.name + "  (" + list.size() + ")");
                if (list.isEmpty()) {
                    showMsg(c.id.equals(Category.FAV)
                            ? getString(R.string.no_fav_channels)
                            : getString(R.string.no_channels_in_cat));
                    return;
                }
                // son izlenen kanali sec
                String last = Prefs.lastChannel(Repo.pid());
                int sel = 0;
                if (last != null) {
                    for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(last)) { sel = i; break; }
                }
                channels.setSelection(sel);
                // onResume'da bekletilen son izlenen kanal: bu listedeyse sec +
                // onizleme/EPG baslat (ilk acilis / tam ekrandan donus).
                if (pendingId != null) {
                    int p = -1;
                    for (int i = 0; i < list.size(); i++)
                        if (list.get(i).id.equals(pendingId)) { p = i; break; }
                    if (p >= 0) {
                        sel = p;
                        channels.setSelection(p);
                        Item it = list.get(p);
                        channels.setItemChecked(p, true);
                        if (Prefs.preview() && !Prefs.externalPlayer()) startPreview(it);
                        loadEpg(it);
                    }
                    pendingId = null;
                }
                if (focusAfter) channels.requestFocus();
            }
        }, new Async.Callback<Exception>() {
            @Override
            public void on(Exception e) {
                if (token != loadToken) return;
                loading.setVisibility(View.GONE);
                showMsg(getString(R.string.channels_failed) + "\n" + Ui.err(e));
            }
        });
    }

    private void showMsg(String s) {
        msg.setText(s);
        msg.setVisibility(View.VISIBLE);
    }

    /**
     * Ayni kanalin tekrarlarini (ornegin "TR: beIN 1" / "beIN Sports 1" /
     * "... FHD") listeden ayiklar. Sirayla ilk konum korunur; daha iyi kalite
     * veya TR etiketli kayit varsa onunla degistirilir.
     */
    private static List<Item> dedupe(List<Item> src) {
        List<Item> out = new ArrayList<>();
        Map<String, Integer> pos = new HashMap<>();
        Map<String, Integer> score = new HashMap<>();
        Map<String, Boolean> ids = new HashMap<>();
        for (Item it : src) {
            if (it == null) continue;
            if (ids.containsKey(it.id)) continue;
            String key = BroadcastApi.channelKey(it.name);
            if (key.isEmpty()) {
                ids.put(it.id, Boolean.TRUE);
                out.add(it);
                continue;
            }
            Integer p = pos.get(key);
            int sc = BroadcastApi.channelScore(it.name);
            if (p == null) {
                pos.put(key, out.size());
                score.put(key, sc);
                ids.put(it.id, Boolean.TRUE);
                out.add(it);
            } else if (sc > score.get(key)) {
                // iyi kaydi sec, eski kimligi de "goruldu" say
                ids.put(out.get(p).id, Boolean.TRUE);
                out.set(p, it);
                score.put(key, sc);
                ids.put(it.id, Boolean.TRUE);
            } else {
                ids.put(it.id, Boolean.TRUE);
            }
        }
        return out;
    }

    private void categoryOptions(final Category c) {
        if (c.isVirtual()) return;
        final boolean locked = Prefs.isLockedPermanent(Repo.pid(), c.type, c.id, c.name);
        Ui.choose(this, c.name, new String[]{getString(locked ? R.string.unlock_category : R.string.lock_category)}, -1, new Ui.Choice() {
            @Override
            public void on(int which) {
                Ui.askPin(LiveActivity.this, getString(R.string.parental_pin), new Runnable() {
                    @Override
                    public void run() {
                        Prefs.setLocked(Repo.pid(), c.type, c.id, c.name, !locked);
                        if (!locked) {
                            Ui.toast(LiveActivity.this, getString(R.string.category_locked));
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
        // Lite surumde kayit yok: secenek listesinden cikarilir.
        String[] opts = BuildConfig.LITE
                ? new String[]{getString(fav ? R.string.fav_remove : R.string.fav_add),
                getString(it.archive ? R.string.epg_and_catchup : R.string.epg),
                getString(R.string.epg_grid),
                getString(R.string.watch_fullscreen),
                getString(Prefs.preview() ? R.string.preview_disable : R.string.preview_enable)}
                : new String[]{getString(fav ? R.string.fav_remove : R.string.fav_add),
                getString(it.archive ? R.string.epg_and_catchup : R.string.epg),
                getString(R.string.epg_grid),
                getString(R.string.watch_fullscreen), getString(R.string.record),
                getString(Prefs.preview() ? R.string.preview_disable : R.string.preview_enable)};
        Ui.choose(this, BroadcastApi.displayChannel(it.name), opts, -1, new Ui.Choice() {
            @Override
            public void on(int which) {
                if (which == 0) {
                    boolean now = Db.get().toggleFavorite(Repo.pid(), it);
                    Ui.toast(LiveActivity.this, getString(now ? R.string.fav_added : R.string.fav_removed));
                    if (currentCat != null && currentCat.id.equals(Category.FAV)) {
                        Category c = currentCat;
                        currentCat = null;
                        openCategory(c, false);
                    } else {
                        chAdapter.reloadFavs();
                    }
                } else if (which == 1) {
                    EpgActivity.open(LiveActivity.this, it);
                } else if (which == 2) {
                    EpgGridActivity.open(LiveActivity.this, it);
                } else if (which == 3) {
                    int pos = chAdapter.data().indexOf(it);
                    if (pos >= 0) fullscreen(pos);
                } else if (!BuildConfig.LITE && which == 4) {
                    RecordUi.recordNow(LiveActivity.this, it, it.id.equals(epgFor) ? currentProgramEnd : 0);
                } else {
                    togglePreview();
                }
            }
        });
    }

    /** Onizlemeyi kalici olarak ac/kapat (ayarlardaki anahtarla ayni). */
    private void togglePreview() {
        boolean on = !Prefs.preview();
        Prefs.sp().edit().putBoolean("preview", on).apply();
        if (!on) releasePreview();
        previewHint.setVisibility(View.VISIBLE);
        previewHint.setText(on ? R.string.preview_hint : R.string.preview_off_hint);
        Async.MAIN.removeCallbacks(previewHover);
        Ui.toast(this, getString(on ? R.string.preview_enabled : R.string.preview_disabled));
    }

    private void fullscreen(int pos) {
        // Onizleme acikken tam ekrana gecis: yayini yeniden kurmak canli TS/HLS
        // akisinda canli kenardan (ileriden) baslayip kisa bir ses kaymasina yol
        // aciyordu. Media3 surumlerinde ayni oynatici tam ekrana devredilir; ses
        // kesilmez ve yayin geri sarmaz. Devir yoksa (Android 4.2) konum aktarilir.
        long start = 0;
        if (previewPlayer != null) {
            long dur = previewPlayer.getDuration();
            long cur = previewPlayer.getPosition();
            if (dur > 0 && cur > 2000 && previewPlayer.isSeekable()) {
                start = Math.max(0, cur - 1000);
            }
        }
        Item target = pos >= 0 && pos < chAdapter.data().size() ? chAdapter.data().get(pos) : null;
        boolean handed = previewPlayer != null
                && previewPlayer.detachForFullscreen(target != null ? target.id : null);
        previewFailed = null;
        releasePreview();
        Play.playLive(this, chAdapter.data(), pos, start, handed);
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
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // 1) onizleme acikken geri -> onizlemeyi kapat, sayfada kal
            if (previewPlayer != null) {
                releasePreview();
                channels.requestFocus();
                return true;
            }
            // 2) EXIT -> ana ekran (uygulamayi asla tam kapatma; task'i koru)
            startActivity(new Intent(this, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------ EPG

    private void loadEpg(final Item it) {
        if (it.id.equals(epgFor)) return;
        epgFor = it.id;
        chName.setText(BroadcastApi.displayChannel(it.name));
        nowTitle.setText(R.string.epg_loading);
        nowDesc.setText("");
        nextList.setText("");
        nowProgress.setVisibility(View.INVISIBLE);
        final int token = ++epgToken;
        Async.run(this, new Async.Task<List<Epg>>() {
            @Override
            public List<Epg> run() throws Exception {
                return Repo.api().shortEpg(it.id, it.epgId, 6);
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
                nowTitle.setText(R.string.no_epg);
            }
        });
    }

    private void showEpg(List<Epg> list) {
        long off = Prefs.epgOffsetSec();
        long now = System.currentTimeMillis() / 1000L - off;
        Epg current = null;
        currentProgramEnd = 0;
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
            currentProgramEnd = current.stop;
            nowTitle.setText(Ui.time(current.start + off) + " - " + Ui.time(current.stop + off) + "   " + current.title);
            nowDesc.setText(current.description);
            int pct = (int) ((now - current.start) * 100 / Math.max(1, current.stop - current.start));
            nowProgress.setProgress(pct);
            nowProgress.setVisibility(View.VISIBLE);
        } else {
            nowTitle.setText(list.isEmpty() ? getString(R.string.no_epg) : "");
            nowDesc.setText("");
            nowProgress.setVisibility(View.INVISIBLE);
        }
        nextList.setText(next.toString());
    }

    // ------------------------------------------------------------ Onizleme

    private void startPreview(Item it) {
        releasePreview();
        previewId = it.id;
        previewItem = it;
        previewFailed = null;
        previewHint.setVisibility(View.GONE);
        previewLoading.setVisibility(View.VISIBLE);
        final Item channel = it;
        previewPlayer = new PreviewPlayer(this, preview, new PreviewPlayer.Listener() {
            @Override
            public void onBuffering(boolean b) {
                previewLoading.setVisibility(b ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onError(String message) {
                previewLoading.setVisibility(View.GONE);
                previewHint.setVisibility(View.VISIBLE);
                previewHint.setText(getString(R.string.stream_failed) + "\n" + message);
                // Ayni kanala tekrar basinca onizlemeyi yeniden denemek yerine
                // dogrudan tam ekran acilir (yayin geri sarmasin).
                previewFailed = channel.id;
                previewId = null;
            }
        });
        // Kanal kaydediliyorsa ikinci baglanti acmadan kayit akisindan besle
        // (saglayici genelde tek baglantiya izin verir). Lite surumde kayit yok.
        String src = BuildConfig.LITE ? null : com.atlanta.maraplayer.record.Recorder.relayUrl(it.id);
        previewRelay = src != null;
        if (src == null) src = Repo.api().liveUrl(it.id, Prefs.liveFormat());
        previewPlayer.play(this, src);
        Prefs.setLastChannel(Repo.pid(), it.id, BroadcastApi.displayChannel(it.name));
    }

    /** Kayit baslarsa onizlemeyi yerel aktarima, biterse canliya gecirir. */
    private final Runnable relayTick = new Runnable() {
        @Override
        public void run() {
            if (BuildConfig.LITE) return; // Lite surumde kayit/relay yok
            if (previewPlayer != null && previewItem != null) {
                String r = com.atlanta.maraplayer.record.Recorder.relayUrl(previewItem.id);
                if ((r != null) != previewRelay) startPreview(previewItem);
            }
            Async.MAIN.postDelayed(this, 2000);
        }
    };

    private void releasePreview() {
        if (previewPlayer != null) {
            previewPlayer.release();
            previewPlayer = null;
        }
        previewId = null;
        previewItem = null;
        previewRelay = false;
        if (previewHint != null) {
            previewHint.setVisibility(View.VISIBLE);
            previewHint.setText(Prefs.preview() ? R.string.preview_hint : R.string.preview_off_hint);
            previewLoading.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!BuildConfig.LITE) {
            Async.MAIN.removeCallbacks(relayTick);
            Async.MAIN.postDelayed(relayTick, 2000);
        }
        if (chAdapter != null) chAdapter.reloadFavs();
        // tam ekrandan donuste son izlenen kanali sec + otomatik onizlemeyi ac
        // (MertStream: tikladigin gibi yayin baslar)
        String last = Prefs.lastChannel(Repo.pid());
        if (last == null || chAdapter == null) return;
        List<Item> d = chAdapter.data();
        // HIZLI YOL: liste zaten yuklendiyse (tam ekrandan donus, ayni kategori)
        // su an sec + onizleme/EPG. Bulunamazsa beklet;
        // openCategory'nin asenkron callback'i pendingId'yi kullaniyor.
        for (int i = 0; i < d.size(); i++) {
            if (d.get(i).id.equals(last)) {
                channels.setSelection(i);
                channels.setItemChecked(i, true);
                if (Prefs.preview() && !Prefs.externalPlayer()) startPreview(d.get(i));
                loadEpg(d.get(i));
                return;
            }
        }
        // YOL 2: listede henuz yok (bos liste / ilk acilis / eski kategori) -> beklet;
        // openCategory'nin asenkron callback'i pendingId'ye gore secimi +
        // onizlemeyi + EPG'yi tetikleyecek.
        pendingId = last;
    }

    @Override
    protected void onPause() {
        super.onPause();
        Async.MAIN.removeCallbacks(relayTick);
        releasePreview();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Async.MAIN.removeCallbacks(catHover);
        Async.MAIN.removeCallbacks(epgHover);
        Async.MAIN.removeCallbacks(previewHover);
        Async.MAIN.removeCallbacks(relayTick);
    }
}
