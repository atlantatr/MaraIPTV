package com.atlanta.maraplayer.util;

import android.content.Context;
import android.content.res.Configuration;

import com.atlanta.maraplayer.data.Prefs;

import java.util.Locale;

/**
 * Dil (yerellestirme) yardimcisi.
 * Uygulama ici dil secimi: Ayarlar > Dil  (Sistem / Turkce / English / Deutsch / Русский / Espanol / Italiano).
 * Metinler res/values-xx/strings.xml dosyalarindadir; varsayilan (values/) Turkcedir.
 */
public final class L {

    public static final String[] CODES = {"system", "tr", "en", "de", "ru", "es", "it"};

    private static Context ctx;
    private static Locale locale = Locale.getDefault();

    private L() {
    }

    /** App.onCreate icinde Prefs.init'ten sonra cagrilir */
    public static void init(Context app) {
        ctx = wrap(app.getApplicationContext());
    }

    /** Secili dile gore Context (Activity.attachBaseContext icin) */
    public static Context wrap(Context base) {
        String code = Prefs.sp() == null ? "system" : Prefs.language();
        Locale loc;
        if ("system".equals(code)) {
            loc = Locale.getDefault(); // sistem dili
            Configuration sys = base.getResources().getConfiguration();
            if (sys.locale != null) loc = sys.locale;
        } else {
            loc = new Locale(code);
        }
        locale = loc;
        if ("system".equals(code)) return base;
        Locale.setDefault(loc);
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.setLocale(loc);
        return base.createConfigurationContext(cfg);
    }

    public static Locale locale() {
        return locale;
    }

    public static boolean isTurkish() {
        return "tr".equals(locale.getLanguage());
    }

    public static String t(int resId) {
        return ctx.getString(resId);
    }

    public static String t(int resId, Object... args) {
        return ctx.getString(resId, args);
    }

    public static String[] arr(int resId) {
        return ctx.getResources().getStringArray(resId);
    }
}
