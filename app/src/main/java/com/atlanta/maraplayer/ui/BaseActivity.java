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
        com.atlanta.maraplayer.util.KeyGuard.reset();
        if (needsSession() && !Repo.hasSession()) {
            sessionLost = true;
            Intent i = new Intent(this, ProfilesActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            finish();
        }
    }

    /** Acik diyaloglar kapatilir (window leak onleme); cagri yapanlarin onunde override yok */
    @Override
    protected void onDestroy() {
        com.atlanta.maraplayer.util.Ui.dismissAllDialogs();
        super.onDestroy();
    }

    /**
     * Kumanda tus tekrari korumasi: ayni OK/yon/geri tusunun 120 ms icindeki
     * ikinci basimi yutulur; basili tutma (tekrar) etkilenmez.
     */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (com.atlanta.maraplayer.util.KeyGuard.swallow(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    /**
     * Alt ekranlar gorev koku kalmissa (ornegin ana ekran bellekten dusuruldugunde
     * spor rehberi dogrudan acildiysa) EXIT'e basinca uygulamadan atilmak yerine
     * ana ekrana don.
     */
    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK && isTaskRoot()
                && needsSession() && Repo.hasSession()) {
            startActivity(new Intent(this, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
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
