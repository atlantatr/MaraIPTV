package com.atlanta.maraplayer.ui;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.TextView;

import com.atlanta.maraplayer.R;
import com.atlanta.maraplayer.data.Db;
import com.atlanta.maraplayer.data.Models.Account;
import com.atlanta.maraplayer.data.Models.Profile;
import com.atlanta.maraplayer.data.Prefs;
import com.atlanta.maraplayer.data.Repo;
import com.atlanta.maraplayer.data.XtreamApi;
import com.atlanta.maraplayer.util.Async;
import com.atlanta.maraplayer.util.Ui;

import java.util.ArrayList;
import java.util.List;

public class ProfilesActivity extends BaseActivity {

    public static final String EXTRA_MANUAL = "manual";

    private Adapters.RowAdapter adapter;
    private ListView list;
    private static boolean autoTried;

    @Override
    protected boolean needsSession() {
        return false;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_profiles);
        list = v(R.id.list);
        adapter = new Adapters.RowAdapter(this);
        list.setAdapter(adapter);
        try {
            ((TextView) v(R.id.version)).setText("Surum " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception ignored) {
        }

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                login(ProfilesActivity.this, (Profile) adapter.getItem(pos).tag);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> p, View view, int pos, long id) {
                options((Profile) adapter.getItem(pos).tag);
                return true;
            }
        });
        v(R.id.btnAdd).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(ProfilesActivity.this, ProfileEditActivity.class));
            }
        });
        v(R.id.btnSettings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(ProfilesActivity.this, SettingsActivity.class));
            }
        });

        boolean manual = getIntent().getBooleanExtra(EXTRA_MANUAL, false);
        if (!manual && !autoTried && Prefs.autoLogin()) {
            autoTried = true;
            Profile p = Db.get().profile(Prefs.lastProfile());
            if (p != null) login(this, p);
        }
        autoTried = true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        List<Profile> ps = Db.get().profiles();
        List<Adapters.Row> rows = new ArrayList<>();
        for (Profile p : ps) {
            Adapters.Row r = new Adapters.Row(p.name, p.user + "  @  " + XtreamApi.normalizeUrl(p.url), p);
            if (p.id == Prefs.lastProfile()) r.right = "son kullanilan";
            rows.add(r);
        }
        adapter.set(rows);
        v(R.id.empty).setVisibility(ps.isEmpty() ? View.VISIBLE : View.GONE);
        list.setVisibility(ps.isEmpty() ? View.GONE : View.VISIBLE);
        if (ps.isEmpty()) v(R.id.btnAdd).requestFocus();
        else list.requestFocus();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU && list.hasFocus() && list.getSelectedItemPosition() >= 0) {
            options((Profile) adapter.getItem(list.getSelectedItemPosition()).tag);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void options(final Profile p) {
        Ui.choose(this, p.name, new String[]{"Baglan", "Duzenle", "Sil"}, -1, new Ui.Choice() {
            @Override
            public void on(int which) {
                if (which == 0) {
                    login(ProfilesActivity.this, p);
                } else if (which == 1) {
                    startActivity(new Intent(ProfilesActivity.this, ProfileEditActivity.class)
                            .putExtra(ProfileEditActivity.EXTRA_ID, p.id));
                } else {
                    Ui.confirm(ProfilesActivity.this, "Profil silinsin mi?",
                            p.name + " ve bu profile ait favoriler / izleme gecmisi silinecek.", new Runnable() {
                                @Override
                                public void run() {
                                    Db.get().deleteProfile(p.id);
                                    reload();
                                }
                            });
                }
            }
        });
    }

    /** Profile baglanir, basariliysa ana ekrana gecer. */
    public static void login(final Activity a, final Profile p) {
        final ProgressDialog pd = Ui.progress(a, "Baglaniliyor: " + p.name + " ...");
        Async.run(a, new Async.Task<Account>() {
            @Override
            public Account run() throws Exception {
                return new XtreamApi(p).login();
            }
        }, new Async.Callback<Account>() {
            @Override
            public void on(Account acc) {
                Ui.dismiss(pd);
                Repo.setSession(p, acc);
                Db.get().touchProfile(p.id);
                Prefs.setLastProfile(p.id);
                if (acc.status != null && !acc.status.isEmpty() && !"Active".equalsIgnoreCase(acc.status)) {
                    Ui.toast(a, "Hesap durumu: " + acc.status);
                }
                Intent i = new Intent(a, HomeActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                a.startActivity(i);
                if (!(a instanceof ProfilesActivity)) a.finish();
            }
        }, new Async.Callback<Exception>() {
            @Override
            public void on(Exception e) {
                Ui.dismiss(pd);
                Ui.dialog(a).setTitle("Baglanti basarisiz").setMessage(Ui.err(e)).setPositiveButton("Tamam", null).show();
            }
        });
    }
}
