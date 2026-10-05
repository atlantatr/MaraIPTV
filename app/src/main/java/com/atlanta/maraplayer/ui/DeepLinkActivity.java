package com.atlanta.maraplayer.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import com.atlanta.maraplayer.data.Db;
import com.atlanta.maraplayer.data.Models;
import com.atlanta.maraplayer.data.Models.Account;
import com.atlanta.maraplayer.data.Models.Profile;
import com.atlanta.maraplayer.data.Prefs;
import com.atlanta.maraplayer.data.Repo;
import com.atlanta.maraplayer.data.Source;
import com.atlanta.maraplayer.data.Sources;
import com.atlanta.maraplayer.data.XtreamApi;
import com.atlanta.maraplayer.util.Async;
import com.atlanta.maraplayer.util.Ui;

/**
 * mara://play?... baglantilarini acar (Android TV ana ekran "Izlemeye devam" / oneri kartlari).
 * Oturum yoksa once ilgili profile sessizce baglanir.
 */
public class DeepLinkActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Uri u = getIntent().getData();
        if (u == null) {
            finish();
            return;
        }
        final Db.Resume r = new Db.Resume();
        r.type = XtreamApi.toInt(u.getQueryParameter("type"));
        r.itemId = nz(u.getQueryParameter("id"));
        r.ext = nz(u.getQueryParameter("ext"));
        r.position = XtreamApi.toLong(u.getQueryParameter("pos"));
        r.name = nz(u.getQueryParameter("name"));
        r.subtitle = nz(u.getQueryParameter("sub"));
        r.icon = nz(u.getQueryParameter("icon"));
        r.seriesId = nz(u.getQueryParameter("sid"));
        r.seriesName = nz(u.getQueryParameter("sname"));
        if (r.itemId.isEmpty() || (r.type != Models.MOVIE && r.type != Models.EPISODE)) {
            finish();
            return;
        }
        long pid = XtreamApi.toLong(u.getQueryParameter("pid"));
        if (Repo.hasSession() && (pid <= 0 || pid == Repo.pid())) {
            Play.playResume(this, r);
            finish();
            return;
        }
        Profile p = Db.get().profile(pid);
        if (p == null) p = Db.get().profile(Prefs.lastProfile());
        if (p == null) {
            startActivity(new Intent(this, ProfilesActivity.class));
            finish();
            return;
        }
        final Profile prof = p;
        Async.run(this, new Async.Task<Account>() {
            @Override
            public Account run() throws Exception {
                Source s = Sources.create(prof);
                Account a = s.login();
                Repo.setSession(prof, a, s);
                Db.get().touchProfile(prof.id);
                Prefs.setLastProfile(prof.id);
                return a;
            }
        }, new Async.Callback<Account>() {
            @Override
            public void on(Account a) {
                Play.playResume(DeepLinkActivity.this, r);
                finish();
            }
        }, new Async.Callback<Exception>() {
            @Override
            public void on(Exception e) {
                Ui.toast(DeepLinkActivity.this, "Baglanti basarisiz: " + Ui.err(e));
                startActivity(new Intent(DeepLinkActivity.this, ProfilesActivity.class));
                finish();
            }
        });
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
