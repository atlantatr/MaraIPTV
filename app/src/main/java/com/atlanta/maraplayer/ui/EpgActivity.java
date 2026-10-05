package com.atlanta.maraplayer.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import com.atlanta.maraplayer.R;
import com.atlanta.maraplayer.data.Models;
import com.atlanta.maraplayer.data.Models.Epg;
import com.atlanta.maraplayer.data.Models.Item;
import com.atlanta.maraplayer.data.Prefs;
import com.atlanta.maraplayer.data.Repo;
import com.atlanta.maraplayer.util.Async;
import com.atlanta.maraplayer.util.Ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Kanal yayin akisi + catch-up (geri izleme). */
public class EpgActivity extends BaseActivity {

    private Item channel;
    private ListView list;
    private Adapters.RowAdapter adapter;
    private TextView detail;

    public static void open(Activity a, Item ch) {
        a.startActivity(putItem(new Intent(a, EpgActivity.class), ch));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (sessionLost) return;
        setContentView(R.layout.activity_list);
        channel = getItem(getIntent());
        channel.type = Models.LIVE;
        ((TextView) v(R.id.title)).setText("Yayin Akisi: " + channel.name);
        ((TextView) v(R.id.hint)).setText(channel.archive
                ? "'Geri izle' yazan programlar acilabilir (" + channel.archiveDays + " gun arsiv)"
                : "Bu kanalda geri izleme yok");
        list = v(R.id.list);
        detail = v(R.id.detail);
        detail.setVisibility(View.VISIBLE);
        adapter = new Adapters.RowAdapter(this);
        list.setAdapter(adapter);

        list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View view, int pos, long id) {
                Epg e = (Epg) adapter.getItem(pos).tag;
                detail.setText(e.description);
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                Epg e = (Epg) adapter.getItem(pos).tag;
                click(e);
            }
        });
        load();
    }

    private void load() {
        v(R.id.loading).setVisibility(View.VISIBLE);
        Async.run(this, new Async.Task<List<Epg>>() {
            @Override
            public List<Epg> run() throws Exception {
                List<Epg> l = Repo.api().fullEpg(channel.id, channel.epgId);
                if (l.isEmpty()) l = Repo.api().shortEpg(channel.id, channel.epgId, 30);
                return l;
            }
        }, new Async.Callback<List<Epg>>() {
            @Override
            public void on(List<Epg> l) {
                v(R.id.loading).setVisibility(View.GONE);
                show(l);
            }
        }, new Async.Callback<Exception>() {
            @Override
            public void on(Exception e) {
                v(R.id.loading).setVisibility(View.GONE);
                TextView m = v(R.id.msg);
                m.setText("Yayin akisi alinamadi: " + Ui.err(e));
                m.setVisibility(View.VISIBLE);
            }
        });
    }

    private void show(List<Epg> l) {
        if (l.isEmpty()) {
            TextView m = v(R.id.msg);
            m.setText("Bu kanal icin yayin akisi yok.");
            m.setVisibility(View.VISIBLE);
            return;
        }
        long off = Prefs.epgOffsetSec();
        long now = System.currentTimeMillis() / 1000L - off;
        long archiveFrom = now - Math.max(1, channel.archiveDays) * 86400L;
        List<Adapters.Row> rows = new ArrayList<>();
        int nowIdx = 0;
        String lastDay = "";
        for (Epg e : l) {
            Adapters.Row r = new Adapters.Row();
            String day = Ui.day(e.start + off);
            String prefix = day.equals(lastDay) ? "" : day + "\n";
            lastDay = day;
            r.title = Ui.time(e.start + off) + " - " + Ui.time(e.stop + off) + "   " + e.title;
            if (e.isNow(now)) {
                nowIdx = rows.size();
                r.right = "SIMDI";
                r.progress = (int) ((now - e.start) * 100 / Math.max(1, e.stop - e.start));
            } else if (e.stop <= now && canCatchup(e, archiveFrom)) {
                r.right = "Geri izle";
            } else if (e.start > now) {
                r.right = "OK: Kaydet";
            }
            r.sub = prefix.isEmpty() ? null : prefix.trim();
            r.tag = e;
            rows.add(r);
        }
        adapter.set(rows);
        list.setSelection(Math.max(0, nowIdx - 2));
        list.requestFocus();
        final int sel = nowIdx;
        list.post(new Runnable() {
            @Override
            public void run() {
                list.setSelection(sel);
            }
        });
    }

    private boolean canCatchup(Epg e, long archiveFrom) {
        if (!channel.archive) return false;
        return e.hasArchive || e.start >= archiveFrom;
    }

    private void click(final Epg e) {
        long off = Prefs.epgOffsetSec();
        long now = System.currentTimeMillis() / 1000L - off;
        if (e.isNow(now)) {
            Ui.choose(this, e.title, new String[]{"Canli izle", "Kaydet (program sonuna kadar)"}, -1, new Ui.Choice() {
                @Override
                public void on(int which) {
                    if (which == 0) {
                        List<Item> one = Collections.singletonList(channel);
                        Play.playLive(EpgActivity.this, new ArrayList<>(one), 0);
                    } else {
                        RecordUi.recordNow(EpgActivity.this, channel, e.stop);
                    }
                }
            });
        } else if (e.stop <= now) {
            if (canCatchup(e, now - Math.max(1, channel.archiveDays) * 86400L)) {
                Play.playCatchup(this, channel, e);
            } else {
                Ui.toast(this, "Bu program icin geri izleme yok");
            }
        } else {
            // ileri tarihli program -> zamanli kayit
            RecordUi.schedule(this, channel, e);
        }
    }
}
