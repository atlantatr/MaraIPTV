package com.atlanta.maraplayer.sport;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.atlanta.maraplayer.R;
import com.atlanta.maraplayer.data.Repo;
import com.atlanta.maraplayer.ui.PendingPlay;
import com.atlanta.maraplayer.ui.Play;
import com.atlanta.maraplayer.ui.SplashActivity;
import com.atlanta.maraplayer.util.Ui;

/** Mac saati geldiginde ilgili IPTV kanalini acar. */
public class MatchAlarmReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.atlanta.maraplayer.MATCH_ALARM";

    @Override
    public void onReceive(Context context, Intent intent) {
        MatchAlarm.Info info = MatchAlarm.get();
        MatchAlarm.cancel(context);
        if (info == null) return;

        try {
            String t = info.title.isEmpty() ? info.channelName : info.title;
            Ui.toast(context, context.getString(R.string.sports_timer_started_x, t));
        } catch (Exception ignored) {
        }

        if (Repo.hasSession() && Repo.pid() == info.profileId) {
            Play.playLiveById(context, info.channelId, info.channelName);
            return;
        }

        // Uygulama kapali: acilista otomatik giris yapilip HomeActivity kanali acar.
        PendingPlay.set(info.profileId, info.channelId, info.channelName, info.title);
        Intent i = new Intent(context, SplashActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            context.startActivity(i);
        } catch (Exception ignored) {
        }
    }
}
