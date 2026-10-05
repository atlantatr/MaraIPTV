package com.atlanta.maraplayer.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.text.InputType;

import com.atlanta.maraplayer.R;
import com.atlanta.maraplayer.data.Prefs;
import com.atlanta.maraplayer.data.Repo;
import com.atlanta.maraplayer.net.Http;
import com.atlanta.maraplayer.data.Models;
import com.atlanta.maraplayer.data.XmlTv;
import com.atlanta.maraplayer.util.Async;
import com.atlanta.maraplayer.util.ImageLoader;
import com.atlanta.maraplayer.util.Ui;
import com.atlanta.maraplayer.web.WebServer;

/** Ayarlar (PreferenceActivity: Android 4.2'de de kumandayla sorunsuz). */
@SuppressWarnings("deprecation")
public class SettingsActivity extends PreferenceActivity implements SharedPreferences.OnSharedPreferenceChangeListener {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        addPreferencesFromResource(R.xml.settings);
        getListView().setBackgroundColor(getResources().getColor(R.color.bg));
        getListView().setCacheColorHint(0);

        for (String k : new String[]{"engine", "live_format", "buffer", "aspect", "epg_offset", "epg_source", "rec_storage"}) updateSummary(k);

        // Android 7.0 oncesi surumlerde resim icinde resim yok
        if (!"nougat".equals(com.atlanta.maraplayer.BuildConfig.FLAVOR)) {
            Preference pip = findPreference("pip_home");
            if (pip != null) pip.setEnabled(false);
        }
        String web = WebServer.address();
        if (web != null) findPreference("web_panel").setSummary("Tarayicida acin: " + web + "  (giris: ebeveyn PIN'i)");

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
                Ui.askPin(SettingsActivity.this, "Ebeveyn PIN", new Runnable() {
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
                Ui.askPin(SettingsActivity.this, "Ebeveyn PIN", new Runnable() {
                    @Override
                    public void run() {
                        Prefs.clearAllLocks();
                        Prefs.clearSessionUnlocks();
                        Ui.toast(SettingsActivity.this, "Kategori kilitleri sifirlandi");
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
                Ui.toast(SettingsActivity.this, "Onbellek temizlendi");
                return true;
            }
        });
        Preference about = findPreference("about");
        String ver = "";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        about.setSummary(getString(R.string.app_name) + " " + ver + " - Xtream Codes API, min. Android 4.2");
    }

    private void changePin() {
        Ui.askPin(this, "Mevcut PIN", new Runnable() {
            @Override
            public void run() {
                Ui.input(SettingsActivity.this, "Yeni PIN (4 hane)", "",
                        InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD, new Ui.TextCb() {
                            @Override
                            public void on(final String p1) {
                                if (p1.trim().length() < 4) {
                                    Ui.toast(SettingsActivity.this, "PIN en az 4 haneli olmali");
                                    return;
                                }
                                Ui.input(SettingsActivity.this, "Yeni PIN (tekrar)", "",
                                        InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD, new Ui.TextCb() {
                                            @Override
                                            public void on(String p2) {
                                                if (p1.trim().equals(p2.trim())) {
                                                    Prefs.setPin(p1.trim());
                                                    Ui.toast(SettingsActivity.this, "PIN degistirildi");
                                                } else {
                                                    Ui.toast(SettingsActivity.this, "PIN'ler eslesmiyor");
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
            Ui.toast(this, "Once bir profile baglanin");
            return;
        }
        if (XmlTv.isRunning()) {
            Ui.toast(this, "EPG guncellemesi zaten suruyor");
            return;
        }
        final Models.Profile p = Repo.profile();
        final String url = XmlTv.urlFor(p, Repo.api());
        if (url.isEmpty()) {
            Ui.toast(this, "Bu profil icin XMLTV adresi yok (profili duzenleyip ekleyin)");
            return;
        }
        final android.app.ProgressDialog pd = Ui.progress(this, "Yayin akisi indiriliyor... (buyuk listelerde birkac dakika surebilir)");
        Async.run(this, new Async.Task<Integer>() {
            @Override
            public Integer run() throws Exception {
                return XmlTv.update(p.id, url);
            }
        }, new Async.Callback<Integer>() {
            @Override
            public void on(Integer n) {
                Ui.dismiss(pd);
                Ui.toast(SettingsActivity.this, n + " program kaydedildi");
            }
        }, new Async.Callback<Exception>() {
            @Override
            public void on(Exception e) {
                Ui.dismiss(pd);
                Ui.toast(SettingsActivity.this, "EPG indirilemedi: " + Ui.err(e));
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
