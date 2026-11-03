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

    /** Kayit süresi "00s 12dk" gibi kisaltilir. */
    public static String compactDuration(long ms) {
        if (ms <= 0) return "";
        long sec = ms / 1000L;
        long h = sec / 3600L, m = (sec % 3600L) / 60L;
        return h > 0 ? String.format(java.util.Locale.US, "%d sa %02d dk", h, m)
                : String.format(java.util.Locale.US, "%d dk", m);
    }

    /** Kullanicinin ilk gormesi gereken kontroller: disk + cakisma, ardindan devam. */
    private static void preChecks(final Activity a, final String stepTitle, final StringBuilder warn,
                                  final Runnable next) {
        if (Recorder.lowSpace(a)) {
            warn.append("\n⚠ Depolama alanı az (")
                    .append(Recorder.size(Recorder.targetDir(a).getUsableSpace())).append(" boş).");
        }
        if (warn.length() > 0) {
            warn.append("\n\nYine de devam edilsin mi?");
            Ui.confirm(a, stepTitle, warn.toString().trim(), next);
            return;
        }
        next.run();
    }

    /**
     * Zamanlanmis kayitlarla cakisanlari bulur (ayni kanal dahil hepsi):
     * araliklar kesisirse kayitlar birbirini keser.
     */
    public static List<Db.Scheduled> conflicts(Db.Scheduled s) {
        List<Db.Scheduled> out = new ArrayList<>();
        for (Db.Scheduled o : Db.get().schedules()) {
            if (o.id == s.id) continue;
            if (o.start < s.stop && s.start < o.stop) out.add(o);
        }
        return out;
    }

    /** Yeni zamanlanmis kayitla cakisan ACTİF kayitlari bulur. */
    public static List<Recorder.Active> activeConflicts(Db.Scheduled s) {
        List<Recorder.Active> out = new ArrayList<>();
        long sStart = s.start * 1000L, sStop = s.stop * 1000L;
        for (Recorder.Active ac : Recorder.active()) {
            long aStart = ac.startedAt;
            long aStop = ac.stopAt > 0 ? ac.stopAt : Long.MAX_VALUE;
            if (aStart < sStop && sStart < aStop) out.add(ac);
        }
        return out;
    }

    /** Aninda kayit: disk uyarisi ile devam akisi. */
    public static void recordNow(final Activity a, final Item ch, final long programEndSec) {
        final StringBuilder warn = new StringBuilder();
        preChecks(a, "Kaydet: " + ch.name, warn, new Runnable() {
            @Override
            public void run() {
                showRecordNowOptions(a, ch, programEndSec);
            }
        });
    }

    private static void showRecordNowOptions(final Activity a, final Item ch, final long programEndSec) {
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
                Recorder.start(a, ch.id, url, ch.name, mins.get(which));
                Ui.toast(a, "Kayıt başladı: " + ch.name + "  (Ana sayfa > Kayitlar)");
            }
        });
    }

    /** EPG'deki ileri tarihli programi zamanla */
    public static void schedule(final Activity a, final Item ch, final Epg e) {
        final long off = Prefs.epgOffsetSec();
        final Db.Scheduled s = new Db.Scheduled();
        s.profileId = Repo.pid();
        s.streamId = ch.id;
        s.channel = ch.name;
        s.title = e.title;
        s.url = Repo.api().liveUrl(ch.id, "ts");
        s.start = e.start + off;
        s.stop = e.stop + off;

        final StringBuilder warn = new StringBuilder();
        List<Db.Scheduled> conf = conflicts(s);
        for (Db.Scheduled o : conf) {
            warn.append("\n⚠ Çakışma: ").append(o.channel).append(" - ").append(o.title)
                    .append(" (").append(Ui.time(o.start)).append(" - ").append(Ui.time(o.stop)).append(")");
        }
        for (Recorder.Active ac : activeConflicts(s)) {
            warn.append("\n⚠ O anda kayıt sürüyor: ").append(ac.title);
        }
        preChecks(a, "Zamanlı kayıt", warn, new Runnable() {
            @Override
            public void run() {
                askRecordingScope(a, ch, e, s);
            }
        });
    }

    /** Bolum'u mu, EPG'de bulunan tum bolumleri mi kaydedeyim. */
    private static void askRecordingScope(final Activity a, final Item ch, final Epg e,
                                          final Db.Scheduled s) {
        String[] opts = {"Bu bölüm", "Tüm bölümler (EPG'de bulunanlar)"};
        Ui.choose(a, "Zamanlı kayıt", opts, 0, new Ui.Choice() {
            @Override
            public void on(int which) {
                if (which == 1) {
                    scheduleSeries(a, ch, e, s);
                } else {
                    Recorder.schedule(a, s);
                    Ui.toast(a, "Kayıt zamanlandı: " + Ui.dayTime(s.start));
                }
            }
        });
    }

    /**
     * Seri kaydı: ayni kanalin EPG'sinde gelecekteki, ayni dizinin diger
     * bolumlerini bulup hepsine zamanlanmis kayit kurar.
     */
    private static void scheduleSeries(final Activity a, final Item ch, final Epg e, final Db.Scheduled first) {
        final android.app.ProgressDialog pd = Ui.progress(a, "Bölümler aranıyor…");
        com.atlanta.maraplayer.util.Async.run(a, new com.atlanta.maraplayer.util.Async.Task<List<Epg>>() {
            @Override
            public List<Epg> run() throws Exception {
                return com.atlanta.maraplayer.data.Repo.api().fullEpg(ch.id, ch.epgId);
            }
        }, new com.atlanta.maraplayer.util.Async.Callback<List<Epg>>() {
            @Override
            public void on(List<Epg> list) {
                Ui.dismiss(pd);
                if (a.isFinishing() || a.isDestroyed()) return;
                long now = System.currentTimeMillis() / 1000L;
                String base = seriesBase(e.title);
                String exact = Ui.norm(e.title);
                List<Epg> eps = new ArrayList<>();
                if (list != null) {
                    for (Epg p : list) {
                        if (p.start <= now || p.start == e.start) continue;
                        String t = Ui.norm(p.title);
                        boolean same = !t.isEmpty() && (t.equals(exact) || seriesBase(p.title).equals(base));
                        if (same) eps.add(p);
                    }
                }
                int n = 0;
                Recorder.schedule(a, first);
                n++;
                for (Epg p : eps) {
                    if (n >= 30) break; // ust sinir
                    Db.Scheduled s2 = new Db.Scheduled();
                    s2.profileId = first.profileId;
                    s2.streamId = first.streamId;
                    s2.channel = first.channel;
                    s2.title = p.title;
                    s2.url = first.url;
                    s2.start = p.start + Prefs.epgOffsetSec();
                    s2.stop = p.stop + Prefs.epgOffsetSec();
                    Recorder.schedule(a, s2);
                    n++;
                }
                Ui.toast(a, "Zamanlandı: " + n + " bölüm (ilk: " + Ui.dayTime(first.start) + ")");
            }
        }, new com.atlanta.maraplayer.util.Async.Callback<Exception>() {
            @Override
            public void on(Exception e2) {
                Ui.dismiss(pd);
                if (a.isFinishing() || a.isDestroyed()) return;
                Ui.toast(a, "Bölümler alınamadı, sadece bu bölüm zamanlandı.");
                Recorder.schedule(a, first);
            }
        });
    }

    /** "Dizi - 12. Bolum" -> "dizi" gibi ortak seri adi. */
    private static String seriesBase(String title) {
        String s = title == null ? "" : title.trim();
        s = s.replaceAll("(?i)[\\s·:\\-–]*\\(?\\b(sezon|season)\\s*\\d+\\)?\\s*$", "");
        s = s.replaceAll("(?i)[\\s·:\\-–]*\\b\\d+\\.?\\s*(bolum|bölüm|blm|part)\\s*(\\d+)?\\s*$", "");
        s = s.replaceAll("(?i)[\\s·:\\-–]?(s\\d+)\\s*(e\\d+)\\s*$", "");
        s = s.replaceAll("[\\s·:\\-–]+\\d+\\s*$", "");
        s = s.replaceAll("[\\s·:\\-–]+$", "");
        return Ui.norm(s).trim();
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
            Ui.toast(a, "Hatırlatıldı: " + e.title);
        } else {
            Prefs.removeReminderList(pid, ch.id, e.start);
            Ui.toast(a, "Hatırlatma kaldırıldı");
        }
    }
}
