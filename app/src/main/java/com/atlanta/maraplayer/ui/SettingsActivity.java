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
        setTheme(R.style.SettingsTheme);
        super.onCreate(b);
        addPreferencesFromResource(R.xml.settings);
        getListView().setBackgroundColor(getResources().getColor(R.color.settings_bg));
        getListView().setCacheColorHint(0);
        if (BuildConfig.ADVANCED) {
            int pad = Adapters.dp(this, 14);
            getListView().setPadding(pad, Adapters.dp(this, 4), pad, Adapters.dp(this, 18));
            getListView().setClipToPadding(false);
            getListView().setDivider(null);
            getListView().setDividerHeight(0);
            // Kart gorunumlu satirlarda varsayilan liste secimi gorunmedigi icin
            // secili satiri "activated" durumuyla vurgula (kumanda ile gezinme).
            getListView().setSelector(android.R.color.transparent);
            getListView().setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                    for (int i = 0; i < parent.getChildCount(); i++) {
                        android.view.View child = parent.getChildAt(i);
                        child.setActivated(child == view);
                    }
                }

                @Override
                public void onNothingSelected(android.widget.AdapterView<?> parent) {
                }
            });
        }

        for (String k : new String[]{"lang", "engine", "live_format", "buffer", "aspect", "epg_offset", "epg_source", "rec_storage", "user_agent"}) {
            updateSummary(k);
        }

        // Lite surumde kayit yok: kayit klasoru ayarini gosterme.
        // NOT: Preference.getParent() API 24'te eklendi; Android 4.2'de yok.
        // Bu yuzden kategori PreferenceScreen uzerinden bulunur.
        if (BuildConfig.LITE) {
            android.preference.PreferenceScreen screen = getPreferenceScreen();
            for (int i = 0; i < screen.getPreferenceCount(); i++) {
                Preference p = screen.getPreference(i);
                if (p instanceof android.preference.PreferenceCategory) {
                    android.preference.PreferenceCategory cat = (android.preference.PreferenceCategory) p;
                    Preference rec = cat.findPreference("rec_storage");
                    if (rec != null) {
                        cat.setTitle(R.string.pref_cat_web);
                        cat.removePreference(rec);
                        break;
                    }
                }
            }
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

        // Cocuk modu: acmak serbest, kapatmak PIN ister
        findPreference("kids_mode").setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(final Preference p, Object newValue) {
                boolean on = newValue instanceof Boolean ? (Boolean) newValue : false;
                if (!on) {
                    Ui.askPin(SettingsActivity.this, getString(R.string.parental_pin), new Runnable() {
                        @Override
                        public void run() {
                            Prefs.setKidsMode(false);
                            Repo.clearCache();
                            ((CheckBoxPreference) p).setChecked(false);
                            Ui.toast(SettingsActivity.this, getString(R.string.kids_off));
                        }
                    });
                    return false;
                }
                Prefs.setKidsMode(true);
                Repo.clearCache();
                Ui.toast(SettingsActivity.this, getString(R.string.kids_on));
                return true;
            }
        });

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

        click("update_check", new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                checkUpdates();
                return true;
            }
        });
        Preference upd = findPreference("update_check");
        if (upd != null) {
            upd.setSummary(getString(R.string.update_current_x,
                    com.atlanta.maraplayer.update.UpdateChecker.currentVersion()));
        }

        click("backup_now", new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                backupNow();
                return true;
            }
        });
        click("backup_restore", new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                restoreBackup();
                return true;
            }
        });
        click("backup_web", new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                String addr = WebServer.address();
                Ui.toast(SettingsActivity.this, getString(R.string.pref_backup_web_hint,
                        addr == null ? "-" : addr + "/backup.json"));
                return true;
            }
        });
        bindBackupSummary();
    }

    /** Tercih bulunamazsa (farkli layout) sessizce gec. */
    private void click(String key, Preference.OnPreferenceClickListener l) {
        Preference p = findPreference(key);
        if (p != null) p.setOnPreferenceClickListener(l);
    }

    /** Ayarlar ekranindaki yedek satirinda son yedek zamanini goster. */
    private void bindBackupSummary() {
        Preference p = findPreference("backup_now");
        if (p == null) return;
        long at = com.atlanta.maraplayer.data.Backup.lastBackupAt(this);
        if (at <= 0) {
            p.setSummary(R.string.pref_backup_now_sum);
        } else {
            p.setSummary(getString(R.string.pref_backup_last_x,
                    android.text.format.DateFormat.format("dd.MM.yyyy HH:mm", at)));
        }
    }

    /** GitHub releases'ten yeni surum var mi diye bakar; varsa notlari gosterir. */
    private void checkUpdates() {
        final android.app.ProgressDialog pd = Ui.progress(this, getString(R.string.update_checking));
        Async.run(this, new Async.Task<com.atlanta.maraplayer.update.UpdateChecker.Release>() {
            @Override
            public com.atlanta.maraplayer.update.UpdateChecker.Release run() throws Exception {
                return com.atlanta.maraplayer.update.UpdateChecker.latest();
            }
        }, new Async.Callback<com.atlanta.maraplayer.update.UpdateChecker.Release>() {
            @Override
            public void on(final com.atlanta.maraplayer.update.UpdateChecker.Release r) {
                Ui.dismiss(pd);
                if (isFinishing()) return;
                if (r == null) {
                    Ui.toast(SettingsActivity.this, getString(R.string.update_no_release));
                    return;
                }
                if (!r.newer) {
                    Ui.toast(SettingsActivity.this, getString(R.string.update_none,
                            com.atlanta.maraplayer.update.UpdateChecker.currentVersion()));
                    return;
                }
                String notes = r.notes == null ? "" : r.notes.trim();
                if (notes.length() > 1800) notes = notes.substring(0, 1800) + "…";
                if (notes.isEmpty()) notes = getString(R.string.update_notes_empty);
                String size = r.size > 0
                        ? android.text.format.Formatter.formatFileSize(SettingsActivity.this, r.size) : "";
                String msg = getString(R.string.update_current_x,
                        com.atlanta.maraplayer.update.UpdateChecker.currentVersion())
                        + "\n" + getString(R.string.update_latest_x, r.tag)
                        + (size.isEmpty() ? "" : "  ·  " + size)
                        + "\n\n" + notes;
                new android.app.AlertDialog.Builder(SettingsActivity.this)
                        .setTitle(getString(R.string.update_new_title, r.tag))
                        .setMessage(msg)
                        .setPositiveButton(R.string.update_download, new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                startUpdateDownload(r);
                            }
                        })
                        .setNegativeButton(R.string.close, null)
                        .show();
            }
        }, new Async.Callback<Exception>() {
            @Override
            public void on(Exception e) {
                Ui.dismiss(pd);
                if (isFinishing()) return;
                Ui.toast(SettingsActivity.this, getString(R.string.update_failed_x, Ui.err(e)));
            }
        });
    }

    private void startUpdateDownload(com.atlanta.maraplayer.update.UpdateChecker.Release r) {
        if (r.apkUrl == null || r.apkUrl.isEmpty()) {
            Ui.toast(this, getString(R.string.update_no_apk));
            return;
        }
        try {
            com.atlanta.maraplayer.update.UpdateChecker.download(this, r);
            Ui.toast(this, getString(R.string.update_downloading_x, r.apkName));
        } catch (Exception e) {
            Ui.toast(this, getString(R.string.update_start_failed_x, Ui.err(e)));
        }
    }

    private void backupNow() {
        try {
            java.io.File f = com.atlanta.maraplayer.data.Backup.write(this);
            bindBackupSummary();
            Ui.toast(this, getString(R.string.backup_done_x, f.getAbsolutePath()));
        } catch (Exception e) {
            Ui.toast(this, getString(R.string.backup_failed_x, Ui.err(e)));
        }
    }

    /** Yedek klasorundeki JSON dosyalarini listeleyip geri yukler. */
    private void restoreBackup() {
        java.io.File dir = com.atlanta.maraplayer.data.Backup.dir(this);
        final java.io.File[] files = dir.listFiles(new java.io.FileFilter() {
            @Override
            public boolean accept(java.io.File f) {
                return f.isFile() && f.getName().endsWith(".json");
            }
        });
        if (files == null || files.length == 0) {
            Ui.toast(this, getString(R.string.backup_none));
            return;
        }
        String[] names = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            names[i] = files[i].getName() + "  (" + android.text.format.DateFormat.format(
                    "dd.MM.yyyy HH:mm", files[i].lastModified()) + ")";
        }
        Ui.choose(this, getString(R.string.pref_backup_restore), names, 0, new Ui.Choice() {
            @Override
            public void on(int which) {
                if (which < 0 || which >= files.length) return;
                confirmRestore(files[which]);
            }
        });
    }

    private void confirmRestore(final java.io.File f) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.pref_backup_restore)
                .setMessage(getString(R.string.backup_restore_confirm_x, f.getName()))
                .setPositiveButton(R.string.yes, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        doRestore(f);
                    }
                })
                .setNegativeButton(R.string.no, null)
                .show();
    }

    private void doRestore(final java.io.File f) {
        try {
            com.atlanta.maraplayer.data.Backup.Result r =
                    com.atlanta.maraplayer.data.Backup.restore(this, f);
            if (r.error != null) {
                Ui.toast(this, getString(R.string.backup_failed_x, r.error));
                return;
            }
            Ui.toast(this, getString(R.string.backup_restored_x, r.summary()));
        } catch (Exception e) {
            Ui.toast(this, getString(R.string.backup_failed_x, Ui.err(e)));
        }
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

    /**
     * Ayarlar gorev koku kalirsa (ornegin uygulama dogrudan bu ekranla acildiysa)
     * EXIT uygulamayi kapatmasin; ana ekrana don.
     */
    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK && isTaskRoot()
                && Repo.hasSession()) {
            startActivity(new Intent(this, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
