package com.atlanta.maraplayer.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.text.InputType;

import com.atlanta.maraplayer.BuildConfig;
import com.atlanta.maraplayer.R;
import com.atlanta.maraplayer.data.Models;
import com.atlanta.maraplayer.data.Prefs;
import com.atlanta.maraplayer.data.Repo;
import com.atlanta.maraplayer.data.XmlTv;
import com.atlanta.maraplayer.net.Http;
import com.atlanta.maraplayer.util.Async;
import com.atlanta.maraplayer.util.ImageLoader;
import com.atlanta.maraplayer.util.L;
import com.atlanta.maraplayer.util.Ui;
import com.atlanta.maraplayer.web.WebServer;

/** Ayarlar (PreferenceActivity: Android 4.2'de de kumandayla sorunsuz). */
@SuppressWarnings("deprecation")
public class SettingsActivity extends PreferenceActivity implements SharedPreferences.OnSharedPreferenceChangeListener {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(L.wrap(base));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        addPreferencesFromResource(R.xml.settings);
        getListView().setBackgroundColor(getResources().getColor(R.color.bg));
        getListView().setCacheColorHint(0);

        for (String k : new String[]{"lang", "engine", "live_format", "buffer", "aspect", "epg_offset", "epg_source", "rec_storage"}) {
            updateSummary(k);
        }

        // Android 7.0 oncesi surumlerde resim icinde resim yok
        if (!"nougat".equals(BuildConfig.FLAVOR)) {
            Preference pip = findPreference("pip_home");
            if (pip != null) pip.setEnabled(false);
        }
        String web = WebServer.address();
        if (web != null) findPreference("web_panel").setSummary(getString(R.string.pref_web_open_x, web));

        findPreference("xmltv_now").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                updateXmltv();
                return true;
            }
        });

        // Ebeveyn kilidi ayarlari PIN ister
        Preference.OnPreferenceChangeListener pinGuard = new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(final Preference p, final Object newValue) {
                Ui.askPin(SettingsActivity.this, getString(R.string.parental_pin), new Runnable() {
                    @Override
                    public void run() {
                        ((CheckBoxPreference) p).setChecked((Boolean) newValue);
                        Prefs.clearSessionUnlocks();
                    }
                });
                return false;
            }
        };
        findPreference("parental").setOnPreferenceChangeListener(pinGuard);
        findPreference("adult_autolock").setOnPreferenceChangeListener(pinGuard);

        findPreference("pin_change").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                changePin();
                return true;
            }
        });
        findPreference("clear_locks").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                Ui.askPin(SettingsActivity.this, getString(R.string.parental_pin), new Runnable() {
                    @Override
                    public void run() {
                        Prefs.clearAllLocks();
                        Prefs.clearSessionUnlocks();
                        Ui.toast(SettingsActivity.this, getString(R.string.locks_reset));
                    }
                });
                return true;
            }
        });
        findPreference("clear_cache").setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                ImageLoader.get().clearMemory();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        ImageLoader.get().clearDisk();
                    }
                }).start();
                Repo.clearCache();
                Ui.toast(SettingsActivity.this, getString(R.string.cache_cleared));
                return true;
            }
        });
        Preference about = findPreference("about");
        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        about.setSummary(getString(R.string.app_name) + " " + ver + "  ·  " + BuildConfig.EDITION);
    }

    private void changePin() {
        Ui.askPin(this, getString(R.string.current_pin), new Runnable() {
            @Override
            public void run() {
                Ui.input(SettingsActivity.this, getString(R.string.new_pin), "",
                        InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD, new Ui.TextCb() {
                            @Override
                            public void on(final String p1) {
                                if (p1.trim().length() < 4) {
                                    Ui.toast(SettingsActivity.this, getString(R.string.pin_min4));
                                    return;
                                }
                                Ui.input(SettingsActivity.this, getString(R.string.new_pin_again), "",
                                        InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD, new Ui.TextCb() {
                                            @Override
                                            public void on(String p2) {
                                                if (p1.trim().equals(p2.trim())) {
                                                    Prefs.setPin(p1.trim());
                                                    Ui.toast(SettingsActivity.this, getString(R.string.pin_changed));
                                                } else {
                                                    Ui.toast(SettingsActivity.this, getString(R.string.pin_mismatch));
                                                }
                                            }
                                        });
                            }
                        });
            }
        });
    }

    private void updateSummary(String key) {
        Preference p = findPreference(key);
        if (p instanceof ListPreference) {
            CharSequence e = ((ListPreference) p).getEntry();
            p.setSummary(e == null ? "" : e.toString().replace("%", "%%"));
        }
    }

    private void updateXmltv() {
        if (!Repo.hasSession()) {
            Ui.toast(this, getString(R.string.connect_profile_first));
            return;
        }
        if (XmlTv.isRunning()) {
            Ui.toast(this, getString(R.string.epg_update_running));
            return;
        }
        final Models.Profile p = Repo.profile();
        final String url = XmlTv.urlFor(p, Repo.api());
        if (url.isEmpty()) {
            Ui.toast(this, getString(R.string.no_xmltv_url));
            return;
        }
        final android.app.ProgressDialog pd = Ui.progress(this, getString(R.string.epg_downloading));
        Async.run(this, new Async.Task<Integer>() {
            @Override
            public Integer run() throws Exception {
                return XmlTv.update(p.id, url);
            }
        }, new Async.Callback<Integer>() {
            @Override
            public void on(Integer n) {
                Ui.dismiss(pd);
                Ui.toast(SettingsActivity.this, getString(R.string.epg_saved_x, n));
            }
        }, new Async.Callback<Exception>() {
            @Override
            public void on(Exception e) {
                Ui.dismiss(pd);
                Ui.toast(SettingsActivity.this, getString(R.string.epg_failed_x, Ui.err(e)));
            }
        });
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        updateSummary(key);
        if ("trust_all".equals(key)) Http.applySsl();
        if ("web_panel".equals(key)) {
            if (Prefs.webPanel()) WebServer.start(this);
            else WebServer.stop();
        }
        if ("lang".equals(key)) {
            // yeni dili uygulamak icin uygulamayi bastan ac
            L.init(getApplicationContext());
            Intent i = Repo.hasSession() ? new Intent(this, HomeActivity.class)
                    : new Intent(this, ProfilesActivity.class).putExtra(ProfilesActivity.EXTRA_MANUAL, true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
    }
}
