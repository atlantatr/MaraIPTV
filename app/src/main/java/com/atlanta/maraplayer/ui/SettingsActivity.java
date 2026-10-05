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
import com.atlanta.maraplayer.util.ImageLoader;
import com.atlanta.maraplayer.util.Ui;

/** Ayarlar (PreferenceActivity: Android 4.2'de de kumandayla sorunsuz). */
@SuppressWarnings("deprecation")
public class SettingsActivity extends PreferenceActivity implements SharedPreferences.OnSharedPreferenceChangeListener {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        addPreferencesFromResource(R.xml.settings);
        getListView().setBackgroundColor(getResources().getColor(R.color.bg));
        getListView().setCacheColorHint(0);

        for (String k : new String[]{"engine", "live_format", "buffer", "aspect", "epg_offset"}) updateSummary(k);

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

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
        updateSummary(key);
        if ("trust_all".equals(key)) Http.applySsl();
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
