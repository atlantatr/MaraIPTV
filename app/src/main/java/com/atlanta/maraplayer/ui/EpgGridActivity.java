package com.atlanta.maraplayer.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * Tam ekran EPG: 7 gunluk, gunluk 3 iki saatlik dilim.
 * Her satir = 2 saatlik dilim; sutunlar = 7 gun. Her kutu o gun o dilimdeki ilk programi gosterir.
 * Kutu secimi: EpgActivity'nin click() mantigiyla ayni.
 */
public class EpgGridActivity extends BaseActivity {

    static final int DAYS = 7;
    static final int SLOTS = 3;

    private Item channel;
    private ListView list;
    private TextView detail;
    private GridAdapter adapter;
    private List<Epg> programs;
    private long[] dayStart;
    private String[] dayName;

    public static void open(Activity a, Item ch) {
        a.startActivity(putItem(new Intent(a, EpgGridActivity.class), ch));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (sessionLost) return;
        setContentView(R.layout.activity_epg_grid);
        channel = getItem(getIntent());
        channel.type = Models.LIVE;
        ((TextView) v(R.id.title)).setText(getString(R.string.epg_grid_title, channel.name));
        ((TextView) v(R.id.hint)).setText(getString(R.string.epg_grid_hint, DAYS));
        detail = v(R.id.detail);
        detail.setVisibility(View.VISIBLE);
        list = v(R.id.list);
        adapter = new GridAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                int d = pos / SLOTS, s = pos % SLOTS;
                Epg e = adapter.cellFor(d, s);
                if (e != null) click(e);
            }
        });
        load();
    }

    private void load() {
        v(R.id.loading).setVisibility(View.VISIBLE);
        Async.run(this, new Async.Task<List<Epg>>() {
            @Override
            public List<Epg> run() throws Exception {
                return Repo.api().fullEpg(channel.id, channel.epgId);
            }
        }, new Async.Callback<List<Epg>>() {
            @Override
            public void on(List<Epg> l) {
                v(R.id.loading).setVisibility(View.GONE);
                if (l == null) l = new ArrayList<Epg>();
                programs = l;
                Collections.sort(programs, new Comparator<Epg>() {
                    @Override
                    public int compare(Epg x, Epg y) {
                        return Long.compare(x.start, y.start);
                    }
                });
                build();
            }
        }, new Async.Callback<Exception>() {
            @Override
            public void on(Exception e) {
                v(R.id.loading).setVisibility(View.GONE);
                TextView m = v(R.id.msg);
                m.setText(getString(R.string.epg_failed_x, Ui.err(e)));
                m.setVisibility(View.VISIBLE);
            }
        });
    }

    private void build() {
        long off = Prefs.epgOffsetSec();
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long today = c.getTimeInMillis() / 1000L;
        dayStart = new long[DAYS];
        dayName = new String[DAYS];
        SimpleDateFormat nameFmt = new SimpleDateFormat("EEE", Ui.TR);
        for (int d = 0; d < DAYS; d++) {
            dayStart[d] = today + d * 86400L;
            dayName[d] = nameFmt.format(new Date((dayStart[d] - off) * 1000L));
        }
        adapter.days = dayName;
        adapter.notifyDataSetChanged();
        // simdi cizilen satira git
        long now = System.currentTimeMillis() / 1000L - off;
        int slot = (int) Math.min(SLOTS - 1, Math.max(0, (now - today) / (2 * 3600L)));
        list.setSelection(slot);
        list.requestFocus();
        showDetailSlot(slot, 0);
    }

    private void showDetailSlot(int slot, int day) {
        Epg e = adapter.cellFor(day, slot);
        if (e != null) detail.setText(Ui.time(e.start) + " " + e.title + "\n" + e.description);
        else detail.setText("");
    }

    private void click(final Epg e) {
        long off = Prefs.epgOffsetSec();
        long now = System.currentTimeMillis() / 1000L - off;
        if (e.isNow(now)) {
            Ui.choose(this, e.title, new String[]{
                    getString(R.string.watch_fullscreen),
                    getString(R.string.schedule_rec)}, -1, new Ui.Choice() {
                @Override
                public void on(int which) {
                    if (which == 0) {
                        List<Item> one = new ArrayList<Item>();
                        one.add(channel);
                        Play.playLive(EpgGridActivity.this, one, 0);
                    } else {
                        RecordUi.recordNow(EpgGridActivity.this, channel, e.stop);
                    }
                }
            });
        } else if (e.stop <= now) {
            if (e.start >= now - Math.max(1, channel.archiveDays) * 86400L) {
                Play.playCatchup(this, channel, e);
            } else {
                Ui.toast(this, getString(R.string.no_archive));
            }
        } else {
            RecordUi.schedule(this, channel, e);
        }
    }

    // ============================================================ Adapter

    private class GridAdapter extends BaseAdapter {
        String[] days;
        final int[] DAY_IDS = {R.id.d0, R.id.d1, R.id.d2, R.id.d3, R.id.d4, R.id.d5, R.id.d6};
        final int[] BLK_IDS = {R.id.p0, R.id.p1, R.id.p2, R.id.p3, R.id.p4, R.id.p5, R.id.p6};

        @Override
        public int getCount() {
            return SLOTS;
        }

        @Override
        public Object getItem(int p) {
            return p;
        }

        @Override
        public long getItemId(int p) {
            return p;
        }

        @Override
        public View getView(int slot, View v, ViewGroup parent) {
            if (v == null) v = getLayoutInflater().inflate(R.layout.item_epg_grid, parent, false);
            long off = Prefs.epgOffsetSec();
            SimpleDateFormat hFmt = new SimpleDateFormat("HH", Ui.TR);
            SimpleDateFormat tFmt = new SimpleDateFormat("HH:mm", Ui.TR);
            long nowSec = System.currentTimeMillis() / 1000L - off;

            ((TextView) v.findViewById(R.id.hour)).setText(
                    hFmt.format(new Date(((dayStart[0] + slot * 2 * 3600L) - off) * 1000L)));

            // gun basliklari yalnizca ilk satirda
            if (slot == 0) {
                for (int d = 0; d < DAYS; d++) {
                    ((TextView) v.findViewById(DAY_IDS[d])).setText(days != null ? days[d] : "");
                }
            }

            for (int d = 0; d < DAYS; d++) {
                TextView blk = v.findViewById(BLK_IDS[d]);
                Epg cell = cellFor(d, slot);
                if (cell == null) {
                    blk.setText("");
                    blk.setVisibility(View.INVISIBLE);
                } else {
                    blk.setVisibility(View.VISIBLE);
                    StringBuilder sb = new StringBuilder();
                    sb.append(tFmt.format(new Date((cell.start - off) * 1000L)));
                    if (cell.stop > cell.start) {
                        sb.append("-").append(tFmt.format(new Date((cell.stop - off) * 1000L)));
                    }
                    sb.append('\n').append(cell.title);
                    if (cell.isNow(nowSec)) sb.append("\n► ").append(getString(R.string.now));
                    blk.setText(sb.toString());
                }
            }
            return v;
        }

        /** Bu gunun (d) bu slotundaki ilk program */
        Epg cellFor(int d, int slot) {
            if (programs == null || dayStart == null) return null;
            long off = Prefs.epgOffsetSec();
            long s0 = dayStart[d] + slot * 2 * 3600L - off;
            long s1 = s0 + 2 * 3600L;
            Epg best = null;
            for (Epg e : programs) {
                if (e.stop > s0 && e.start < s1) {
                    if (best == null || e.start < best.start) best = e;
                }
            }
            return best;
        }
    }
}
