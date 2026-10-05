package com.atlanta.maraplayer.util;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Arka plan is parcaciklari (AsyncTask yerine basit yardimci). */
public final class Async {

    public interface Task<T> {
        T run() throws Exception;
    }

    public interface Callback<T> {
        void on(T value);
    }

    private static final ExecutorService EX = Executors.newFixedThreadPool(4);
    public static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Async() {
    }

    public static <T> void run(final Activity owner, final Task<T> task, final Callback<T> ok, final Callback<Exception> err) {
        EX.execute(new Runnable() {
            @Override
            public void run() {
                T result = null;
                Exception error = null;
                try {
                    result = task.run();
                } catch (Exception e) {
                    error = e;
                } catch (OutOfMemoryError oom) {
                    error = new Exception("Yetersiz bellek (liste cok buyuk)");
                }
                final T r = result;
                final Exception e = error;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (owner != null && (owner.isFinishing() || owner.isDestroyed())) return; // isDestroyed: API 17
                        if (e != null) {
                            if (err != null) err.on(e);
                        } else if (ok != null) {
                            ok.on(r);
                        }
                    }
                });
            }
        });
    }

    public static void bg(Runnable r) {
        EX.execute(r);
    }
}
