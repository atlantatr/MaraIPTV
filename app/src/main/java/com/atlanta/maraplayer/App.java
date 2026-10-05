package com.atlanta.maraplayer;

import android.content.Context;

import androidx.multidex.MultiDexApplication;

import com.atlanta.maraplayer.data.Db;
import com.atlanta.maraplayer.data.Prefs;
import com.atlanta.maraplayer.net.Http;
import com.atlanta.maraplayer.util.ImageLoader;

public class App extends MultiDexApplication {

    private static App instance;

    public static Context ctx() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Prefs.init(this);
        Http.init(this);
        Db.init(this);
        ImageLoader.init(this);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        ImageLoader.get().clearMemory();
    }
}
