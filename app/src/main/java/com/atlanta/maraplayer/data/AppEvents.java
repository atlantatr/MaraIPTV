package com.atlanta.maraplayer.data;

import com.atlanta.maraplayer.util.Async;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Uygulama ici olaylar (orn. web panelden hesap eklendi / degisti).
 * Dinleyiciler ana is parcaciginda cagrilir.
 */
public final class AppEvents {

    public interface Listener {
        /** @param added true = yeni hesap eklendi, false = duzenlendi veya silindi */
        void onProfilesChanged(long profileId, boolean added);
    }

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /** Web panelden eklenen ve henuz acilmamis hesap (-1 = yok) */
    public static volatile long pendingOpen = -1;

    private AppEvents() {
    }

    public static void register(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public static void unregister(Listener l) {
        listeners.remove(l);
    }

    public static void profilesChanged(final long id, final boolean added) {
        if (added) pendingOpen = id;
        Async.MAIN.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) l.onProfilesChanged(id, added);
            }
        });
    }

    /** Bekleyen hesabi al ve temizle */
    public static long takePending() {
        long p = pendingOpen;
        pendingOpen = -1;
        return p;
    }
}
