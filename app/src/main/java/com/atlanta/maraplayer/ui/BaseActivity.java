package com.atlanta.maraplayer.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import com.atlanta.maraplayer.data.Repo;

/** Oturum gerektiren ekranlarin temeli: surec oldurulup geri gelinirse profillere doner. */
public abstract class BaseActivity extends Activity {

    protected boolean sessionLost;

    protected boolean needsSession() {
        return true;
    }

    /** Uygulama ici dil secimini uygula */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(com.atlanta.maraplayer.util.L.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (needsSession() && !Repo.hasSession()) {
            sessionLost = true;
            Intent i = new Intent(this, ProfilesActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            finish();
        }
    }

    @SuppressWarnings("unchecked")
    protected <T extends View> T v(int id) {
        return (T) findViewById(id);
    }

    protected int dp(int v) {
        return Adapters.dp(this, v);
    }

    /** Item'i Intent'e koy / geri al */
    public static Intent putItem(Intent i, com.atlanta.maraplayer.data.Models.Item it) {
        return i.putExtra("it_type", it.type).putExtra("it_id", it.id).putExtra("it_name", it.name)
                .putExtra("it_icon", it.icon).putExtra("it_cat", it.categoryId).putExtra("it_ext", it.ext)
                .putExtra("it_epg", it.epgId).putExtra("it_archive", it.archive).putExtra("it_adays", it.archiveDays)
                .putExtra("it_rating", it.rating).putExtra("it_num", it.num).putExtra("it_plot", it.plot)
                .putExtra("it_year", it.year).putExtra("it_genre", it.genre);
    }

    public static com.atlanta.maraplayer.data.Models.Item getItem(Intent i) {
        com.atlanta.maraplayer.data.Models.Item it = new com.atlanta.maraplayer.data.Models.Item();
        it.type = i.getIntExtra("it_type", 0);
        it.id = str(i, "it_id");
        it.name = str(i, "it_name");
        it.icon = str(i, "it_icon");
        it.categoryId = str(i, "it_cat");
        it.ext = str(i, "it_ext");
        it.epgId = str(i, "it_epg");
        it.archive = i.getBooleanExtra("it_archive", false);
        it.archiveDays = i.getIntExtra("it_adays", 0);
        it.rating = str(i, "it_rating");
        it.num = i.getIntExtra("it_num", 0);
        it.plot = str(i, "it_plot");
        it.year = str(i, "it_year");
        it.genre = str(i, "it_genre");
        return it;
    }

    private static String str(Intent i, String k) {
        String s = i.getStringExtra(k);
        return s == null ? "" : s;
    }
}
