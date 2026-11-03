package com.atlanta.maraplayer.record;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.atlanta.maraplayer.sport.MatchAlarm;
import com.atlanta.maraplayer.sport.MatchReminder;

/** Cihaz yeniden baslayinca zamanli kayit alarmlarini tekrar kurar (App.onCreate icinde). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // App.onCreate zaten Recorder.rescheduleAll() ve web paneli baslatir
        Recorder.rescheduleAll(context.getApplicationContext());
        MatchAlarm.reschedule(context.getApplicationContext());
        MatchReminder.rescheduleAll(context.getApplicationContext());
    }
}
