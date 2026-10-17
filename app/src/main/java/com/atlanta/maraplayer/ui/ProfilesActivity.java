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

import com.atlanta.maraplayer.BuildConfig;
import com.atlanta.maraplayer.R;
import com.atlanta.maraplayer.data.AppEvents;
import com.atlanta.maraplayer.data.Db;
import com.atlanta.maraplayer.data.Models.Account;
import com.atlanta.maraplayer.data.Models.Profile;
import com.atlanta.maraplayer.data.Prefs;
import com.atlanta.maraplayer.data.Repo;
import com.atlanta.maraplayer.data.Source;
import com.atlanta.maraplayer.data.Sources;
import com.atlanta.maraplayer.data.XtreamApi;
import com.atlanta.maraplayer.util.Async;
import com.atlanta.maraplayer.util.Ui;
import com.atlanta.maraplayer.web.WebServer;

import java.util.ArrayList;
import java.util.List;

public class ProfilesActivity extends BaseActivity implements AppEvents.Listener {

    public static final String EXTRA_MANUAL = "manual";

    private Adapters.RowAdapter adapter;
    private ListView list;
    private static boolean autoTried;
    private boolean loggingIn;

    @Override
    protected boolean needsSession() {
        return false;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_profiles);
        list = v(R.id.list);
        ListNav.install(list);
        adapter = new Adapters.RowAdapter(this);
        list.setAdapter(adapter);
        try {
            ((TextView) v(R.id.version)).setText(getString(R.string.version_x,
                    getPackageManager().getPackageInfo(getPackageName(), 0).versionName) + "  ·  " + BuildConfig.EDITION);
        } catch (Exception ignored) {
        }

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View view, int pos, long id) {
                doLogin((Profile) adapter.getItem(pos).tag);
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
        if (!manual && !autoTried) {
            autoTried = true;
            List<Profile> all = Db.get().profiles();
            Profile p = null;
            if (all.size() == 1) {
                // tek hesap varsa secim yapmadan dogrudan ac
                p = all.get(0);
            } else if (Prefs.autoLogin()) {
                p = Db.get().profile(Prefs.lastProfile());
            }
            if (p != null) doLogin(p);
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
        AppEvents.register(this);
        // web panelden bu ekran kapaliyken eklenen hesap varsa ac
        long pending = AppEvents.takePending();
        if (pending > 0) {
            Profile p = Db.get().profile(pending);
            if (p != null) doLogin(p);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        AppEvents.unregister(this);
    }

    /** Web panelden hesap eklendi/degisti/silindi */
    @Override
    public void onProfilesChanged(long profileId, boolean added) {
        reload();
        if (added) {
            AppEvents.takePending();
            Profile p = Db.get().profile(profileId);
            if (p != null) {
                Ui.toast(this, getString(R.string.web_account_added, p.name));
                doLogin(p);
            }
        }
    }

    private void doLogin(Profile p) {
        if (loggingIn) return;
        loggingIn = true;
        login(this, p, new Runnable() {
            @Override
            public void run() {
                loggingIn = false;
            }
        });
    }

    private void reload() {
        List<Profile> ps = Db.get().profiles();
        List<Adapters.Row> rows = new ArrayList<>();
        for (Profile p : ps) {
            Adapters.Row r = new Adapters.Row(p.name, p.isM3u() ? "M3U  ·  " + p.url
                    : p.user + "  @  " + XtreamApi.normalizeUrl(p.url), p);
            if (p.id == Prefs.lastProfile()) r.right = getString(R.string.last_used);
            rows.add(r);
        }
        adapter.set(rows);
        v(R.id.empty).setVisibility(ps.isEmpty() ? View.VISIBLE : View.GONE);
        list.setVisibility(ps.isEmpty() ? View.GONE : View.VISIBLE);
        String web = WebServer.address();
        ((TextView) v(R.id.webInfo)).setText(web != null && WebServer.isRunning()
                ? getString(R.string.web_panel_x, web) + "\n" + getString(R.string.web_panel_hint) : "");
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
        Ui.choose(this, p.name, new String[]{getString(R.string.connect), getString(R.string.edit), getString(R.string.delete)}, -1,
                new Ui.Choice() {
                    @Override
                    public void on(int which) {
                        if (which == 0) {
                            doLogin(p);
                        } else if (which == 1) {
                            startActivity(new Intent(ProfilesActivity.this, ProfileEditActivity.class)
                                    .putExtra(ProfileEditActivity.EXTRA_ID, p.id));
                        } else {
                            Ui.confirm(ProfilesActivity.this, getString(R.string.profile_delete_q),
                                    getString(R.string.profile_delete_msg, p.name), new Runnable() {
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

    public static void login(final Activity a, final Profile p) {
        login(a, p, null);
    }

    /** Profile baglanir, basariliysa ana ekrana gecer. done: basari/basarisizlikta cagrilir */
    public static void login(final Activity a, final Profile p, final Runnable done) {
        final ProgressDialog pd = Ui.progress(a, a.getString(R.string.connecting_x, p.name));
        final Source src = Sources.create(p);
        Async.run(a, new Async.Task<Account>() {
            @Override
            public Account run() throws Exception {
                return src.login();
            }
        }, new Async.Callback<Account>() {
            @Override
            public void on(Account acc) {
                Ui.dismiss(pd);
                if (done != null) done.run();
                Repo.setSession(p, acc, src);
                Db.get().touchProfile(p.id);
                Prefs.setLastProfile(p.id);
                if (acc.status != null && !acc.status.isEmpty() && !"Active".equalsIgnoreCase(acc.status)) {
                    Ui.toast(a, a.getString(R.string.account_status_x, acc.status));
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
                if (done != null) done.run();
                Ui.dialog(a).setTitle(R.string.connection_failed).setMessage(Ui.err(e)).setPositiveButton(R.string.ok, null).show();
            }
        });
    }
}
