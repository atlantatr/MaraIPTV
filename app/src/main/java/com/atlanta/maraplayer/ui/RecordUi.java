package com.atlanta.maraplayer.ui;

import android.app.Activity;

import com.atlanta.maraplayer.data.Db;
import com.atlanta.maraplayer.data.Models.Epg;
import com.atlanta.maraplayer.data.Models.Item;
import com.atlanta.maraplayer.data.Prefs;
import com.atlanta.maraplayer.data.Repo;
import com.atlanta.maraplayer.record.Recorder;
import com.atlanta.maraplayer.util.Ui;

import java.util.ArrayList;
import java.util.List;

/** Kayit baslatma / zamanlama diyaloglari (tum surumlerde ortak). */
public final class RecordUi {

    private RecordUi() {
    }

    /** Canli kanali hemen kaydet. programEndSec: su anki programin bitisi (bilinmiyorsa 0) */
    public static void recordNow(final Activity a, final Item ch, final long programEndSec) {
        final List<String> labels = new ArrayList<>();
        final List<Integer> mins = new ArrayList<>();
        long now = System.currentTimeMillis() / 1000L;
        if (programEndSec > 0 && programEndSec + Prefs.epgOffsetSec() > now) {
            int m = (int) ((programEndSec + Prefs.epgOffsetSec() - now) / 60) + 2;
            labels.add("Program bitene kadar (" + m + " dk)");
            mins.add(m);
        }
        int[] opts = {30, 60, 90, 120, 180};
        for (int o : opts) {
            labels.add(o + " dakika");
            mins.add(o);
        }
        labels.add("Ben durdurana kadar");
        mins.add(0);
        Ui.choose(a, "Kaydet: " + ch.name, labels.toArray(new String[0]), -1, new Ui.Choice() {
            @Override
            public void on(int which) {
                String url = Repo.api().liveUrl(ch.id, "ts");
                Recorder.start(a, url, ch.name, mins.get(which));
                Ui.toast(a, "Kayit basladi: " + ch.name + "  (Ana sayfa > Kayitlar)");
            }
        });
    }

    /** EPG'deki ileri tarihli programi zamanla */
    public static void schedule(final Activity a, final Item ch, final Epg e) {
        final long off = Prefs.epgOffsetSec();
        Ui.confirm(a, "Zamanli kayit", ch.name + "\n" + e.title + "\n" + Ui.dayTime(e.start + off) + " - " + Ui.time(e.stop + off)
                + "\n\nBu program kaydedilsin mi? (Uygulama kapali olsa da kayit baslar.)", new Runnable() {
            @Override
            public void run() {
                Db.Scheduled s = new Db.Scheduled();
                s.profileId = Repo.pid();
                s.streamId = ch.id;
                s.channel = ch.name;
                s.title = e.title;
                s.url = Repo.api().liveUrl(ch.id, "ts");
                s.start = e.start + off;
                s.stop = e.stop + off;
                Recorder.schedule(a, s);
                Ui.toast(a, "Kayit zamanlandi: " + Ui.dayTime(s.start));
            }
        });
    }

    /** EPG'deki programa hatirlatma (bildirim) ekler / kaldirir */
    public static void toggleReminder(final Activity a, final Item ch, final Epg e) {
        final long off = Prefs.epgOffsetSec();
        final long pid = Repo.pid();
        final String label = ch.name + "  ·  " + e.title + "\n" + Ui.dayTime(e.start + off);
        boolean on = !Prefs.hasReminder(pid, ch.id, e.start);
        Prefs.setReminder(pid, ch.id, e.start, on);
        if (on) {
            Prefs.addReminderList(pid, ch.id, e.start, e.stop);
            Ui.toast(a, "Hatirlatildi: " + e.title);
        } else {
            Prefs.removeReminderList(pid, ch.id, e.start);
            Ui.toast(a, "Hatirlatma kaldirildi");
        }
    }
}
