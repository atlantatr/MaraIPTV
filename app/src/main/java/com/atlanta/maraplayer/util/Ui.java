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
        b.setNegativeButton("Kapat", null).show();
    }

    public static void confirm(Context c, String title, String msg, final Runnable yes) {
        dialog(c).setTitle(title).setMessage(msg)
                .setPositiveButton("Evet", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        yes.run();
                    }
                })
                .setNegativeButton("Hayir", null).show();
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
                .setPositiveButton("Tamam", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dd, int w) {
                        cb.on(et.getText().toString());
                    }
                })
                .setNegativeButton("Iptal", null).create();
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
        input(c, title == null ? "PIN girin" : title, "",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD, new TextCb() {
                    @Override
                    public void on(String text) {
                        if (Prefs.checkPin(text.trim())) ok.run();
                        else toast(c, "PIN hatali");
                    }
                });
    }

    // ------------------------------------------------------------ Bicimlendirme

    public static String time(long unixSec) {
        return new SimpleDateFormat("HH:mm", TR).format(new Date(unixSec * 1000L));
    }

    public static String dayTime(long unixSec) {
        return new SimpleDateFormat("EEE d MMM  HH:mm", TR).format(new Date(unixSec * 1000L));
    }

    public static String day(long unixSec) {
        return new SimpleDateFormat("EEEE, d MMMM", TR).format(new Date(unixSec * 1000L));
    }

    public static String date(long unixSec) {
        return new SimpleDateFormat("dd.MM.yyyy", TR).format(new Date(unixSec * 1000L));
    }

    public static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, sec);
        return String.format(Locale.US, "%02d:%02d", m, sec);
    }

    public static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase(TR).replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
                .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c');
    }

    public static boolean empty(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** Kullaniciya anlasilir hata mesaji */
    public static String err(Exception e) {
        if (e instanceof UnknownHostException) return "Sunucu bulunamadi. Adresi ve internet baglantisini kontrol edin.";
        if (e instanceof SocketTimeoutException) return "Sunucu zaman asimina ugradi.";
        if (e instanceof ConnectException) return "Sunucuya baglanilamadi.";
        if (e instanceof SSLException)
            return "Guvenli baglanti (SSL) hatasi. Ayarlar > 'Sertifika hatalarini yoksay' secenegini deneyin.";
        if (e instanceof FileNotFoundException) return "Adres bulunamadi (404).";
        if (e instanceof Http.HttpException) {
            int code = ((Http.HttpException) e).code;
            if (code == 401 || code == 403) return "Erisim reddedildi (" + code + "). Hesap suresi veya bilgiler hatali olabilir.";
            return "Sunucu hatasi: HTTP " + code;
        }
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }
}
