package com.atlanta.maraplayer.util;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.atlanta.maraplayer.R;
import com.atlanta.maraplayer.data.Prefs;
import com.atlanta.maraplayer.net.Http;

import java.io.FileNotFoundException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import javax.net.ssl.SSLException;

public final class Ui {

    private Ui() {
    }

    /** Turkce kucuk harf / arama normallestirmesi icin */
    public static final Locale TR = new Locale("tr", "TR");

    public static void toast(Context c, String msg) {
        Toast.makeText(c, msg, Toast.LENGTH_LONG).show();
    }

    public static AlertDialog.Builder dialog(Context c) {
        return new AlertDialog.Builder(c, AlertDialog.THEME_HOLO_DARK);
    }

    public static ProgressDialog progress(Activity a, String msg) {
        ProgressDialog d = new ProgressDialog(a, AlertDialog.THEME_HOLO_DARK);
        d.setMessage(msg);
        d.setCancelable(true);
        d.show();
        return d;
    }

    public static void dismiss(DialogInterface d) {
        try {
            if (d != null) d.dismiss();
        } catch (Exception ignored) {
        }
    }

    public interface Choice {
        void on(int which);
    }

    /** Acik diyaloglari tutar; activity onDestroy'de kapatilir (window leak onleme) */
    private static final java.util.List<AlertDialog> openDialogs = new java.util.ArrayList<AlertDialog>();

    public static void choose(Context c, String title, String[] items, int checked, final Choice cb) {
        AlertDialog.Builder b = dialog(c).setTitle(title);
        DialogInterface.OnClickListener l = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                d.dismiss();
                cb.on(which);
            }
        };
        if (checked >= 0) b.setSingleChoiceItems(items, checked, l);
        else b.setItems(items, l);
        b.setNegativeButton(R.string.close, null);
        trackDialog(b.create(), b);
    }

    public static void confirm(Context c, String title, String msg, final Runnable yes) {
        AlertDialog.Builder b = dialog(c).setTitle(title).setMessage(msg)
                .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        yes.run();
                    }
                })
                .setNegativeButton(R.string.no, null);
        trackDialog(b.create(), b);
    }

    /** Dialog'i olustur, acik liste takibine ekle, gosterir; kapatil listeden silinir */
    private static void trackDialog(final AlertDialog dlg, AlertDialog.Builder b) {
        dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                synchronized (openDialogs) { openDialogs.remove(dlg); }
            }
        });
        // builder'a set edilen listener'lar create() ile birlikte dlg'ye gecmez;
        // builder'dan alinip dlg'ye atanlar zaten set'li -> sadece show
        synchronized (openDialogs) { openDialogs.add(dlg); }
        dlg.show();
    }

    /** BaseActivity.onDestroy'de cagirilir; acik diyaloglari kapatir (window leak onleme) */
    public static void dismissAllDialogs() {
        synchronized (openDialogs) {
            for (AlertDialog d : openDialogs) {
                try {
                    if (d.isShowing()) d.dismiss();
                } catch (Exception ignored) {
                }
            }
            openDialogs.clear();
        }
    }

    public interface TextCb {
        void on(String text);
    }

    public static void input(Context c, String title, String initial, int inputType, final TextCb cb) {
        final EditText et = new EditText(c);
        et.setInputType(inputType);
        et.setSingleLine(true);
        et.setText(initial == null ? "" : initial);
        et.setSelection(et.getText().length());
        final AlertDialog d = dialog(c).setTitle(title).setView(et)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dd, int w) {
                        cb.on(et.getText().toString());
                    }
                })
                .setNegativeButton(R.string.cancel, null).create();
        et.setImeOptions(EditorInfo.IME_ACTION_DONE);
        et.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (event != null && event.getAction() != KeyEvent.ACTION_DOWN) return true;
                if (!d.isShowing()) return true;
                d.dismiss();
                cb.on(et.getText().toString());
                return true;
            }
        });
        if (d.getWindow() != null) d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    /** PIN sorar, dogruysa ok calisir. */
    public static void askPin(final Context c, String title, final Runnable ok) {
        input(c, title == null ? c.getString(R.string.enter_pin) : title, "",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD, new TextCb() {
                    @Override
                    public void on(String text) {
                        if (Prefs.checkPin(text.trim())) ok.run();
                        else toast(c, c.getString(R.string.wrong_pin));
                    }
                });
    }

    // ------------------------------------------------------------ Bicimlendirme (secili dile gore)

    public static String time(long unixSec) {
        return new SimpleDateFormat("HH:mm", L.locale()).format(new Date(unixSec * 1000L));
    }

    public static String dayTime(long unixSec) {
        return new SimpleDateFormat("EEE d MMM  HH:mm", L.locale()).format(new Date(unixSec * 1000L));
    }

    public static String day(long unixSec) {
        return new SimpleDateFormat("EEEE, d MMMM", L.locale()).format(new Date(unixSec * 1000L));
    }

    public static String date(long unixSec) {
        return new SimpleDateFormat("dd.MM.yyyy", L.locale()).format(new Date(unixSec * 1000L));
    }

    public static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, sec);
        return String.format(Locale.US, "%02d:%02d", m, sec);
    }

    /** Arama icin: buyuk/kucuk harf ve Turkce/aksanli harf farkini kaldirir */
    public static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase(TR).replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
                .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
                .replace('ä', 'a').replace('ß', 's').replace('é', 'e').replace('è', 'e')
                .replace('á', 'a').replace('í', 'i').replace('ó', 'o').replace('ú', 'u').replace('ñ', 'n')
                .replace('à', 'a').replace('ò', 'o').replace('ù', 'u').replace('ё', 'е');
    }

    public static boolean empty(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** Kullaniciya anlasilir hata mesaji */
    public static String err(Exception e) {
        if (e instanceof UnknownHostException) return L.t(R.string.err_unknown_host);
        if (e instanceof SocketTimeoutException) return L.t(R.string.err_timeout);
        if (e instanceof ConnectException) return L.t(R.string.err_connect);
        if (e instanceof SSLException) return L.t(R.string.err_ssl);
        if (e instanceof FileNotFoundException) return L.t(R.string.err_404);
        if (e instanceof Http.HttpException) {
            int code = ((Http.HttpException) e).code;
            if (code == 401 || code == 403) return L.t(R.string.err_denied_x, code);
            return L.t(R.string.err_http_x, code);
        }
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }
}
