package com.atlanta.maraplayer.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import com.atlanta.maraplayer.R;
import com.atlanta.maraplayer.data.Models;
import com.atlanta.maraplayer.data.Models.Account;
import com.atlanta.maraplayer.data.Repo;
import com.atlanta.maraplayer.util.Async;
import com.atlanta.maraplayer.util.ImageLoader;
import com.atlanta.maraplayer.util.Ui;

import java.text.SimpleDateFormat;
import java.util.Date;

public class HomeActivity extends BaseActivity implements View.OnClickListener {

    private TextView clock;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            clock.setText(new SimpleDateFormat("HH:mm\nEEE d MMM", Ui.TR).format(new Date()));
            Async.MAIN.postDelayed(this, 20000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (sessionLost) return;
        setContentView(R.layout.activity_home);
        clock = v(R.id.clock);
        ((TextView) v(R.id.profile)).setText(Repo.profile().name);
        Account a = Repo.account();
        StringBuilder sb = new StringBuilder();
        sb.append("Bitis: ").append(a.expDate > 0 ? Ui.date(a.expDate) : "Sinirsiz");
        if (a.expDate > 0) {
            long days = (a.expDate * 1000L - System.currentTimeMillis()) / 86400000L;
            if (days >= 0) sb.append(" (").append(days).append(" gun)");
        }
        sb.append("   Baglanti: ").append(a.activeConnections).append("/").append(a.maxConnections);
        if (a.trial) sb.append("   [Deneme]");
        if (!Ui.empty(a.status)) sb.append("   Durum: ").append(a.status);
        ((TextView) v(R.id.account)).setText(sb.toString());

        int[] ids = {R.id.tLive, R.id.tMovies, R.id.tSeries, R.id.tContinue, R.id.tFav, R.id.tSearch,
                R.id.tRefresh, R.id.tSettings, R.id.tProfiles};
        for (int id : ids) v(id).setOnClickListener(this);
        v(R.id.tLive).requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (clock != null) tick.run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Async.MAIN.removeCallbacks(tick);
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
        } else if (id == R.id.tRefresh) {
            Repo.clearCache();
            ImageLoader.get().clearMemory();
            Ui.toast(this, "Listeler sunucudan yeniden yuklenecek");
        } else if (id == R.id.tSettings) {
            startActivity(new Intent(this, SettingsActivity.class));
        } else if (id == R.id.tProfiles) {
            Intent i = new Intent(this, ProfilesActivity.class).putExtra(ProfilesActivity.EXTRA_MANUAL, true);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
            finish();
        }
    }
}
