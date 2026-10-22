package com.atlanta.maraplayer.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import com.atlanta.maraplayer.R;
import com.atlanta.maraplayer.data.AppEvents;
import com.atlanta.maraplayer.data.Db;
import com.atlanta.maraplayer.data.Models;
import com.atlanta.maraplayer.data.Models.Account;
import com.atlanta.maraplayer.data.Models.Profile;
import com.atlanta.maraplayer.data.Prefs;
import com.atlanta.maraplayer.data.Repo;
import com.atlanta.maraplayer.data.Source;
import com.atlanta.maraplayer.data.Sources;
import com.atlanta.maraplayer.tv.TvHome;
import com.atlanta.maraplayer.util.Async;
import com.atlanta.maraplayer.util.ImageLoader;
import com.atlanta.maraplayer.util.L;
import com.atlanta.maraplayer.util.Ui;
import com.atlanta.maraplayer.web.WebServer;

import java.text.SimpleDateFormat;
import java.util.Date;

public class HomeActivity extends BaseActivity implements View.OnClickListener, AppEvents.Listener {

    /** Ana ekranda iken listeler bu sureden eskiyse arka planda yenilenir */
    private static final long AUTO_REFRESH_MS = 30 * 60 * 1000L;
    private static long lastRefresh = System.currentTimeMillis();

    private TextView clock, refreshInfo;
    private boolean refreshing;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            clock.setText(new SimpleDateFormat("HH:mm\nEEE d MMM", L.locale()).format(new Date()));
            if (System.currentTimeMillis() - lastRefresh > AUTO_REFRESH_MS) refreshInBackground(null, false);
            Async.MAIN.postDelayed(this, 20000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (sessionLost) return;
        setContentView(R.layout.activity_home);
        clock = v(R.id.clock);
        refreshInfo = v(R.id.refreshInfo);
        bindHeader();

        int[] ids = {R.id.tLive, R.id.tMovies, R.id.tSeries, R.id.tContinue, R.id.tFav, R.id.tSearch,
                R.id.tRec, R.id.tRefresh, R.id.tSettings, R.id.tProfiles};
        for (int id : ids) v(id).setOnClickListener(this);
        v(R.id.tLive).requestFocus();
    }

    private void bindHeader() {
        if (Repo.profile() == null || Repo.account() == null) return;
        ((TextView) v(R.id.profile)).setText(Repo.profile().name);
        Account a = Repo.account();
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.expires)).append(": ")
                .append(a.expDate > 0 ? Ui.date(a.expDate) : getString(R.string.unlimited));
        if (a.expDate > 0) {
            long days = (a.expDate * 1000L - System.currentTimeMillis()) / 86400000L;
            if (days >= 0) sb.append(" (").append(getString(R.string.days_x, days)).append(")");
        }
        sb.append("   ").append(getString(R.string.connections)).append(": ")
                .append(a.activeConnections).append("/").append(a.maxConnections);
        if (a.trial) sb.append("   [").append(getString(R.string.trial)).append("]");
        if (!Ui.empty(a.status)) sb.append("   ").append(getString(R.string.status)).append(": ").append(a.status);
        ((TextView) v(R.id.account)).setText(sb.toString());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (clock == null) return;
        tick.run();
        AppEvents.register(this);
        String web = WebServer.address();
        ((TextView) v(R.id.webInfo)).setText(web != null && WebServer.isRunning() ? getString(R.string.web_panel_x, web) : "");
        Async.bg(new Runnable() {
            @Override
            public void run() {
                TvHome.update(getApplicationContext());
            }
        });
        // bu ekran arka plandayken web panelden hesap eklendiyse ona gec
        long pending = AppEvents.takePending();
        if (pending > 0 && pending != Repo.pid()) {
            Profile p = Db.get().profile(pending);
            if (p != null) refreshInBackground(p, true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Async.MAIN.removeCallbacks(tick);
        AppEvents.unregister(this);
    }

    /** Web panelden hesap eklendi / duzenlendi / silindi */
    @Override
    public void onProfilesChanged(long profileId, boolean added) {
        Profile p = Db.get().profile(profileId);
        if (added && p != null) {
            AppEvents.takePending();
            Ui.toast(this, getString(R.string.web_account_added, p.name));
            refreshInBackground(p, true);
        } else if (profileId == Repo.pid()) {
            if (p == null) {
                // aktif hesap silindi -> profil secimine don
                openProfiles();
            } else {
                refreshInBackground(p, false);
            }
        }
    }

    /**
     * Arka planda yeniden baglanir, listeleri yeniler (ekran degismez).
     * @param target null = aktif profil
     * @param switching yeni hesaba geciliyor
     */
    private void refreshInBackground(Profile target, final boolean switching) {
        if (refreshing) return;
        final Profile p = target != null ? target : Repo.profile();
        if (p == null) return;
        refreshing = true;
        lastRefresh = System.currentTimeMillis();
        refreshInfo.setText(switching ? getString(R.string.switching_to_x, p.name) : getString(R.string.refreshing));
        Async.run(this, new Async.Task<Account>() {
            @Override
            public Account run() throws Exception {
                Source src = Sources.create(p);
                Account a = src.login();
                Repo.setSession(p, a, src);
                Db.get().touchProfile(p.id);
                Prefs.setLastProfile(p.id);
                // ana kategorileri onceden yukle -> menuler aninda acilsin
                try {
                    Repo.categories(Models.LIVE);
                    Repo.categories(Models.MOVIE);
                    Repo.categories(Models.SERIES);
                } catch (Exception ignored) {
                }
                return a;
            }
        }, new Async.Callback<Account>() {
            @Override
            public void on(Account a) {
                refreshing = false;
                bindHeader();
                refreshInfo.setText(getString(R.string.updated_at_x,
                        new SimpleDateFormat("HH:mm", L.locale()).format(new Date())));
            }
        }, new Async.Callback<Exception>() {
            @Override
            public void on(Exception e) {
                refreshing = false;
                refreshInfo.setText(getString(R.string.refresh_failed_x, Ui.err(e)));
            }
        });
    }

    private void openProfiles() {
        Intent i = new Intent(this, ProfilesActivity.class).putExtra(ProfilesActivity.EXTRA_MANUAL, true);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
        finish();
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        if (id == R.id.tLive) {
            startActivity(new Intent(this, LiveActivity.class));
        } else if (id == R.id.tMovies) {
            startActivity(new Intent(this, VodActivity.class).putExtra(VodActivity.EXTRA_TYPE, Models.MOVIE));
        } else if (id == R.id.tSeries) {
            startActivity(new Intent(this, VodActivity.class).putExtra(VodActivity.EXTRA_TYPE, Models.SERIES));
        } else if (id == R.id.tContinue) {
            startActivity(new Intent(this, FavoritesActivity.class).putExtra(FavoritesActivity.EXTRA_MODE, FavoritesActivity.MODE_CONTINUE));
        } else if (id == R.id.tFav) {
            startActivity(new Intent(this, FavoritesActivity.class).putExtra(FavoritesActivity.EXTRA_MODE, FavoritesActivity.MODE_FAV));
        } else if (id == R.id.tSearch) {
            startActivity(new Intent(this, SearchActivity.class));
        } else if (id == R.id.tRec) {
            startActivity(new Intent(this, RecordingsActivity.class));
        } else if (id == R.id.tRefresh) {
            ImageLoader.get().clearMemory();
            refreshInBackground(null, false);
        } else if (id == R.id.tSettings) {
            startActivity(new Intent(this, SettingsActivity.class));
        } else if (id == R.id.tProfiles) {
            openProfiles();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Home'da EXIT/geri tusu: task'i arka plana at (launcher'a don).
        // moveTaskToBack uygulamayi KAPATMAZ, sadece minimize eder;
        // process RAM'de kalir, tekrar acilista durum korunur.
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            moveTaskToBack(true);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
