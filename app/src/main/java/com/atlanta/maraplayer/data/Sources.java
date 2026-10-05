package com.atlanta.maraplayer.data;

import com.atlanta.maraplayer.data.Models.Profile;

/** Profil tipine gore dogru kaynagi olusturur. */
public final class Sources {

    private Sources() {
    }

    public static Source create(Profile p) {
        return p.isM3u() ? new M3uSource(p) : new XtreamApi(p);
    }
}
