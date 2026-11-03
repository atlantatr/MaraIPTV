package com.atlanta.maraplayer.sport;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.atlanta.maraplayer.R;
import com.atlanta.maraplayer.ui.SportsActivity;

/** Zamanlayici ateslendiginde "mac basliyor" bildirimini gosterir. */
public class MatchReminderReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.atlanta.maraplayer.MATCH_REMINDER";
    public static final String EXTRA_KEY = "match_key";
    public static final String EXTRA_TITLE = "match_title";
    public static final String EXTRA_LEAD = "match_lead";
    public static final String EXTRA_CHANNEL_ID = "match_channel_id";
    public static final String EXTRA_CHANNEL_NAME = "match_channel_name";

    private static final String CHANNEL = "match_reminders";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        String key = intent.getStringExtra(EXTRA_KEY);
        String title = intent.getStringExtra(EXTRA_TITLE);
        int lead = intent.getIntExtra(EXTRA_LEAD, 0);
        String chId = intent.getStringExtra(EXTRA_CHANNEL_ID);
        String chName = intent.getStringExtra(EXTRA_CHANNEL_NAME);
        if (title == null || title.isEmpty()) title = context.getString(R.string.sports_guide);

        MatchReminder.done(context, key);

        // TV kutularinda bildirim cubugu gorunmeyebilir; once uygulama ici
        // hatirlatma penceresini ac, sonra bildirimi de birak.
        try {
            context.startActivity(new Intent(context, com.atlanta.maraplayer.ui.MatchReminderActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(com.atlanta.maraplayer.ui.MatchReminderActivity.EXTRA_TITLE, title)
                    .putExtra(com.atlanta.maraplayer.ui.MatchReminderActivity.EXTRA_LEAD, lead)
                    .putExtra(com.atlanta.maraplayer.ui.MatchReminderActivity.EXTRA_CHANNEL_ID, chId)
                    .putExtra(com.atlanta.maraplayer.ui.MatchReminderActivity.EXTRA_CHANNEL_NAME, chName));
        } catch (Exception ignored) {
        }

        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    context.getString(R.string.match_reminder_channel),
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription(context.getString(R.string.match_reminder_channel_desc));
            nm.createNotificationChannel(ch);
        }

        Intent open;
        if (chId != null && !chId.isEmpty()) {
            open = new Intent(context, com.atlanta.maraplayer.ui.MatchReminderActivity.class)
                    .putExtra(com.atlanta.maraplayer.ui.MatchReminderActivity.EXTRA_TITLE, title)
                    .putExtra(com.atlanta.maraplayer.ui.MatchReminderActivity.EXTRA_LEAD, lead)
                    .putExtra(com.atlanta.maraplayer.ui.MatchReminderActivity.EXTRA_CHANNEL_ID, chId)
                    .putExtra(com.atlanta.maraplayer.ui.MatchReminderActivity.EXTRA_CHANNEL_NAME, chName);
        } else {
            open = new Intent(context, SportsActivity.class);
        }
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(context, 1, open, flags);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL)
                : new Notification.Builder(context);
        b.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(lead > 0
                        ? context.getString(R.string.match_reminder_soon, lead)
                        : context.getString(R.string.match_reminder_now))
                .setAutoCancel(true)
                .setContentIntent(pi);
        if (Build.VERSION.SDK_INT >= 21) {
            b.setCategory(Notification.CATEGORY_EVENT);
        }
        nm.notify(key == null ? 7712 : key.hashCode(), b.build());
    }
}
