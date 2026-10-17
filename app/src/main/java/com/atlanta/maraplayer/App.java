package com.atlanta.maraplayer;

import android.content.Context;

import androidx.multidex.MultiDexApplication;

import com.atlanta.maraplayer.data.Db;
import com.atlanta.maraplayer.data.Prefs;
import com.atlanta.maraplayer.net.Http;
import com.atlanta.maraplayer.record.Recorder;
import com.atlanta.maraplayer.util.ImageLoader;
import com.atlanta.maraplayer.util.L;
import com.atlanta.maraplayer.web.WebServer;

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
        L.init(this); // uygulama dili
        Http.init(this);
        Db.init(this);
        ImageLoader.init(this);
        // Yerel ag web paneli: http://CIHAZ_IP:8765
        WebServer.start(this);
        // Zamanli kayitlarin alarmlarini kur (yeniden baslatma / guncelleme sonrasi)
        try {
            Recorder.rescheduleAll(this);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        ImageLoader.get().clearMemory();
    }
}
