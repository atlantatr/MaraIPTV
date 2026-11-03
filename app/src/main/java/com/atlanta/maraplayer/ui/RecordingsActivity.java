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
import com.atlanta.maraplayer.record.Recorder;
import com.atlanta.maraplayer.util.Async;
import com.atlanta.maraplayer.util.Ui;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Kayitlar: devam eden, tamamlanan ve zamanlanmis kayitlar. */
public class RecordingsActivity extends BaseActivity {

    private ListView list;
    private Adapters.RowAdapter adapter;
    private TextView msg;
    private Button[] tabs;
    private int tab;

    /** Son yenilemedeki aktif kayit sayisi: kayit bitince liste de yenilensin. */
    private int lastActive = -1;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            int n = Recorder.active().size();
            // Kayit surerken boyut/sure canli kalsin; kayit bitince (n=0) satir
            // "Kaydediliyor" olarak takili kalmasin diye gecisde de yenile.
            if (tab == 0 && (n > 0 || n != lastActive)) {
                lastActive = n;
                reload();
            }
            Async.MAIN.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (sessionLost) return;
        setContentView(R.layout.activity_list);
        ((TextView) v(R.id.title)).setText("Kayıtlar");
        ((TextView) v(R.id.hint)).setText("MENÜ / uzun bas: sil / iptal");
        list = v(R.id.list);
        msg = v(R.id.msg);
        adapter = new Adapters.RowAdapter(this);
        list.setAdapter(adapter);
        v(R.id.tabs).setVisibility(View.VISIBLE);
        tabs = new Button[]{v(R.id.tab0), v(R.id.tab1), v(R.id.tab2)};
        String[] names = {"Kayıtlar", "Zamanlanmis", "Kayıt yeri"};
        for (int i = 0; i < 3; i++) {
            final int t = i;
            tabs[i].setText(names[i]);
            tabs[i].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (t == 2) {
                        showStorage();
                        return;
                    }
                    tab = t;
                    reload();
                }
            });
        }
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                open(adapter.getItem(pos).tag);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> p, View view, int pos, long id) {
                remove(adapter.getItem(pos).tag);
                return true;
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter == null) return;
        reload();
        Async.MAIN.postDelayed(refresh, 3000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Async.MAIN.removeCallbacks(refresh);
    }

    private void reload() {
        List<Adapters.Row> rows = new ArrayList<>();
        if (tab == 0) {
            long now = System.currentTimeMillis();
            for (Recorder.Active a : Recorder.active()) {
                long elapsed = Math.max(0, now - a.startedAt);
                Adapters.Row r = new Adapters.Row("● " + a.title, a.status
                        + "  ·  " + Recorder.size(a.bytes)
                        + "  ·  süren " + RecordUi.compactDuration(elapsed)
                        + (a.stopAt > 0 ? "  ·  bitis " + Ui.time(a.stopAt / 1000) : ""), a);
                r.right = "OK: Durdur";
                rows.add(r);
            }
            for (File f : Recorder.recordings(this)) {
                boolean busy = false;
                for (Recorder.Active a : Recorder.active()) if (a.file.equals(f)) busy = true;
                if (busy) continue;
                long dur = Recorder.durationMs(f);
                Adapters.Row r = new Adapters.Row(f.getName(),
                        Ui.dayTime(f.lastModified() / 1000) + "  ·  " + Recorder.size(f.length())
                                + (dur > 0 ? "  ·  " + RecordUi.compactDuration(dur) : ""), f);
                rows.add(r);
            }
        } else {
            for (Db.Scheduled s : Db.get().schedules()) {
                Adapters.Row r = new Adapters.Row(s.channel + " - " + s.title,
                        Ui.dayTime(s.start) + " - " + Ui.time(s.stop), s);
                r.right = "Zamanli";
                rows.add(r);
            }
        }
        for (int i = 0; i < 2; i++) tabs[i].setSelected(i == tab);
        int sel = list.getSelectedItemPosition();
        adapter.set(rows);
        if (sel >= 0 && sel < rows.size()) list.setSelection(sel);
        msg.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        msg.setText(tab == 0 ? "Henüz kayıt yok.\nCanlı yayında MENÜ > Kaydet veya yayın akışından program kaydedin."
                : "Zamanlanmış kayıt yok.\nYayın akışında ileri tarihli bir programa basın > Kaydet.");
    }

    private void open(final Object tag) {
        if (tag instanceof Recorder.Active) {
            final Recorder.Active a = (Recorder.Active) tag;
            Ui.confirm(this, "Kaydi durdur", a.title + " kaydı durdurulsun mu?", new Runnable() {
                @Override
                public void run() {
                    Recorder.stop(RecordingsActivity.this, a.id);
                    Ui.toast(RecordingsActivity.this, "Kayıt durduruluyor...");
                    // Servis kaydi kapatana kadar kisa bekle, sonra listeyi tazele
                    Async.MAIN.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (adapter != null) reload();
                        }
                    }, 2000);
                }
            });
        } else if (tag instanceof File) {
            Play.playFile(this, (File) tag);
        } else if (tag instanceof Db.Scheduled) {
            remove(tag);
        }
    }

    private void remove(final Object tag) {
        if (tag instanceof File) {
            final File f = (File) tag;
            Ui.confirm(this, "Kaydı sil", f.getName() + " silinsin mi?", new Runnable() {
                @Override
                public void run() {
                    Recorder.deleteRecording(f);
                    reload();
                }
            });
        } else if (tag instanceof Db.Scheduled) {
            final Db.Scheduled s = (Db.Scheduled) tag;
            Ui.confirm(this, "Zamanlı kaydı iptal et", s.title + " iptal edilsin mi?", new Runnable() {
                @Override
                public void run() {
                    Recorder.cancel(RecordingsActivity.this, s.id);
                    reload();
                }
            });
        } else if (tag instanceof Recorder.Active) {
            open(tag);
        }
    }

    private void showStorage() {
        StringBuilder sb = new StringBuilder();
        File target = Recorder.targetDir(this);
        for (File d : Recorder.dirs(this)) {
            sb.append(d.equals(target) ? "> " : "   ").append(d.getAbsolutePath())
                    .append("\n     bos: ").append(Recorder.size(d.getUsableSpace())).append("\n");
        }
        sb.append("\nYeni kayıtlar '>' işaretli klasöre yazılır. Ayarlar > Kayıt yeri ile değiştirilebilir.");
        Ui.dialog(this).setTitle("Kayıt yeri").setMessage(sb.toString()).setPositiveButton("Tamam", null).show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) && list.hasFocus()) {
            int p = list.getSelectedItemPosition();
            if (p >= 0) remove(adapter.getItem(p).tag);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            startActivity(new android.content.Intent(this, HomeActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
