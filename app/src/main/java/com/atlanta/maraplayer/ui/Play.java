package com.atlanta.maraplayer.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.atlanta.maraplayer.data.Db;
import com.atlanta.maraplayer.data.Models;
import com.atlanta.maraplayer.data.Models.Epg;
import com.atlanta.maraplayer.data.Models.Episode;
import com.atlanta.maraplayer.data.Models.Item;
import com.atlanta.maraplayer.data.Models.PlayItem;
import com.atlanta.maraplayer.data.Prefs;
import com.atlanta.maraplayer.data.Repo;
import com.atlanta.maraplayer.data.Source;
import com.atlanta.maraplayer.util.Ui;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Oynatici baslaticilari. Tum surumlerde (4.2 / 5.0 / 7.0) ortak;
 * PlayerActivity her surumun kendi klasorunde (src/legacy, src/modern) bulunur.
 */
public final class Play {

    public static final String EXTRA_INDEX = "index";
    public static final String EXTRA_START = "start";

    private Play() {
    }

    public static PlayItem liveItem(Item it, int fallbackNum) {
        Source api = Repo.api();
        PlayItem p = new PlayItem();
        p.type = Models.LIVE;
        p.id = it.id;
        p.title = it.name;
        p.icon = it.icon;
        p.num = it.num > 0 ? it.num : fallbackNum;
        p.archive = it.archive;
        p.archiveDays = it.archiveDays;
        p.categoryId = it.categoryId;
        p.epgId = it.epgId;
        p.url = api.liveUrl(it.id, Prefs.liveFormat());
        return p;
    }

    public static void playLive(Activity a, List<Item> channels, int index) {
        List<PlayItem> q = new ArrayList<>();
        for (int i = 0; i < channels.size(); i++) q.add(liveItem(channels.get(i), i + 1));
        launch(a, q, index, 0);
    }

    public static void playMovie(Activity a, Item it, long startMs) {
        PlayItem p = new PlayItem();
        p.type = Models.MOVIE;
        p.id = it.id;
        p.title = it.name;
        p.subtitle = "Film";
        p.icon = it.icon;
        p.ext = it.ext;
        p.url = Repo.api().movieUrl(it.id, it.ext);
        List<PlayItem> q = new ArrayList<>();
        q.add(p);
        launch(a, q, 0, startMs);
    }

    public static void playEpisodes(Activity a, String seriesId, String seriesName, String cover,
                                    List<Episode> eps, int index, long startMs) {
        List<PlayItem> q = new ArrayList<>();
        for (Episode e : eps) {
            PlayItem p = new PlayItem();
            p.type = Models.EPISODE;
            p.id = e.id;
            p.title = Ui.empty(e.title) ? "Bolum " + e.number : e.title;
            p.subtitle = "S" + e.season + " B" + e.number;
            p.icon = Ui.empty(e.image) ? cover : e.image;
            p.ext = e.ext;
            p.seriesId = seriesId;
            p.seriesName = seriesName;
            p.url = Repo.api().episodeUrl(e.id, e.ext);
            q.add(p);
        }
        launch(a, q, index, startMs);
    }

    public static void playCatchup(Activity a, Item ch, Epg e) {
        String startRaw = e.startRaw;
        if (Ui.empty(startRaw)) {
            startRaw = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(e.start * 1000L));
        }
        int minutes = (int) ((e.stop - e.start) / 60) + 1;
        PlayItem p = new PlayItem();
        p.type = Models.CATCHUP;
        p.id = ch.id;
        p.title = e.title;
        long off = Prefs.epgOffsetSec();
        p.subtitle = ch.name + "  ·  " + Ui.dayTime(e.start + off);
        p.icon = ch.icon;
        p.url = Repo.api().catchupUrl(ch.id, startRaw, minutes, "ts");
        List<PlayItem> q = new ArrayList<>();
        q.add(p);
        launch(a, q, 0, 0);
    }

    public static void playResume(Activity a, Db.Resume r) {
        PlayItem p = new PlayItem();
        p.type = r.type;
        p.id = r.itemId;
        p.title = r.name;
        p.subtitle = r.subtitle;
        p.icon = r.icon;
        p.ext = r.ext;
        p.seriesId = r.seriesId;
        p.seriesName = r.seriesName;
        p.url = r.type == Models.MOVIE ? Repo.api().movieUrl(r.itemId, r.ext) : Repo.api().episodeUrl(r.itemId, r.ext);
        List<PlayItem> q = new ArrayList<>();
        q.add(p);
        launch(a, q, 0, r.position);
    }

    /** Yerel kayit dosyasini oynat */
    public static void playFile(Activity a, File f) {
        PlayItem p = new PlayItem();
        p.type = Models.RECORDING;
        p.id = f.getName();
        p.title = f.getName();
        p.subtitle = "Kayit";
        p.url = Uri.fromFile(f).toString();
        List<PlayItem> q = new ArrayList<>();
        q.add(p);
        launch(a, q, 0, 0);
    }

    public static void launch(Activity a, List<PlayItem> q, int index, long startMs) {
        if (q.isEmpty()) return;
        index = Math.max(0, Math.min(index, q.size() - 1));
        PlayItem p = q.get(index);
        if (p.type == Models.LIVE) Prefs.setLastChannel(Repo.pid(), p.id);
        // Yerel kayit dosyalari her zaman dahili oynaticida (file:// baska uygulamaya verilemez)
        if (Prefs.externalPlayer() && p.type != Models.RECORDING) {
            openExternal(a, p);
            return;
        }
        Repo.playQueue = q;
        a.startActivity(new Intent(a, PlayerActivity.class).putExtra(EXTRA_INDEX, index).putExtra(EXTRA_START, startMs));
    }

    public static void openExternal(Context a, PlayItem p) {
        if (p.type == Models.RECORDING) {
            Ui.toast(a, "Kayit dosyalari sadece dahili oynaticida acilir");
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse(p.url), "video/*");
        i.putExtra("title", p.title);
        Intent chooser = Intent.createChooser(i, "Oynatici secin");
        if (!(a instanceof Activity)) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            a.startActivity(chooser);
        } catch (ActivityNotFoundException e) {
            Ui.toast(a, "Harici oynatici bulunamadi. VLC veya MX Player kurun.");
        }
    }

    /** PlayItem -> Item (favori / EPG / kayit icin) */
    public static Item toItem(PlayItem p) {
        Item it = new Item();
        it.type = Models.LIVE;
        it.id = p.id;
        it.name = p.title;
        it.icon = p.icon;
        it.num = p.num;
        it.archive = p.archive;
        it.archiveDays = p.archiveDays;
        it.categoryId = p.categoryId;
        it.epgId = p.epgId;
        return it;
    }
}
