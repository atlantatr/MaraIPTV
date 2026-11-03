package com.atlanta.maraplayer.util;

import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * Kumanda tus tekrari korumasi.
 *
 * Bazi TV kutulari (ornegin Amlogic IR alicilari) tek basista iki kez
 * OK/yon olayi uretiyor; bu da listede yanlis ogeyi acmak veya bir ekrani
 * atlayarak ana ekrana donmek gibi sorunlara yol acabiliyor. Ayni tusun
 * 120 ms icindeki ikinci ACTION_DOWN olayi yutulur.
 *
 * Basili tutma (repeatCount > 0) etkilenmez; basili tutarak listede kaydirma
 * aynen calisir.
 */
public final class KeyGuard {

    private static final long MIN_GAP_MS = 120;

    private static long lastAt;
    private static int lastCode = -1;

    private KeyGuard() {
    }

    /** true ise olay yutulmali. */
    public static boolean swallow(KeyEvent e) {
        if (e == null || e.getAction() != KeyEvent.ACTION_DOWN) return false;
        int code = e.getKeyCode();
        if (!guarded(code)) return false;
        if (e.getRepeatCount() > 0) {
            // Basili tutma: kaydirmaya izin ver, tekrar sayacini sifirla.
            lastAt = 0;
            lastCode = -1;
            return false;
        }
        long now = SystemClock.uptimeMillis();
        boolean dup = code == lastCode && lastAt > 0 && now - lastAt < MIN_GAP_MS;
        lastCode = code;
        lastAt = now;
        return dup;
    }

    private static boolean guarded(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_DPAD_UP
                || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_BACK;
    }

    public static void reset() {
        lastAt = 0;
        lastCode = -1;
    }
}
