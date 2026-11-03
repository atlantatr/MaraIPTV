package com.atlanta.maraplayer.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.WindowManager;

import com.atlanta.maraplayer.R;
import com.atlanta.maraplayer.data.Repo;
import com.atlanta.maraplayer.util.Ui;

/**
 * Zamanlayici ateslendiginde TV'nin uzerinde beliren mac hatirlatmasi.
 * Bildirim cubugu bazi TV kutularinda gorunmedigi icin uygulama ici
 * diyalog kullanilir; "Spor Rehberi" ile maca gidilebilir.
 */
public class MatchReminderActivity extends Activity {

    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_LEAD = "lead";
    public static final String EXTRA_CHANNEL_ID = "channel_id";
    public static final String EXTRA_CHANNEL_NAME = "channel_name";

    private String channelId = "";
    private String channelName = "";

    private final Handler handler = new Handler();
    private final Runnable closer = new Runnable() {
        @Override
        public void run() {
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title == null || title.isEmpty()) title = getString(R.string.sports_guide);
        int lead = getIntent().getIntExtra(EXTRA_LEAD, 0);
        channelId = nz(getIntent().getStringExtra(EXTRA_CHANNEL_ID));
        channelName = nz(getIntent().getStringExtra(EXTRA_CHANNEL_NAME));

        String msg = title + "\n\n" + (lead > 0
                ? getString(R.string.match_reminder_soon, lead)
                : getString(R.string.match_reminder_now));
        if (!channelId.isEmpty()) {
            msg += "\n▶ " + (channelName.isEmpty() ? getString(R.string.sports_tv_iptv) : channelName);
        }

        AlertDialog.Builder d = Ui.dialog(this);
        d.setTitle(R.string.match_reminder_title);
        d.setMessage(msg);
        if (!channelId.isEmpty()) {
            d.setPositiveButton(R.string.match_reminder_watch, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    openChannel();
                    finish();
                }
            });
            d.setNeutralButton(R.string.match_reminder_open, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    openGuide();
                    finish();
                }
            });
            d.setNegativeButton(R.string.close, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    finish();
                }
            });
        } else {
            d.setPositiveButton(R.string.match_reminder_open, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    openGuide();
                    finish();
                }
            });
            d.setNegativeButton(R.string.close, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    finish();
                }
            });
        }
        d.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                finish();
            }
        });
        d.show();

        // Kimse dokunmazsa 2 dakika sonra kendiliginden kapanir.
        handler.postDelayed(closer, 120000L);
    }

    /**
     * Spor Rehberi'ne gider. Surec yeniden basladiysa oturum henuz yoktur;
     * bu durumda once profil ekrani uzerinden otomatik giris yapilir.
     */
    private void openGuide() {
        if (Repo.hasSession()) {
            startActivity(new Intent(this, SportsActivity.class));
            return;
        }
        startActivity(new Intent(this, ProfilesActivity.class)
                .putExtra(ProfilesActivity.EXTRA_OPEN_SPORTS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    /** Hatirlatmadaki kanali acar; oturum yoksa once otomatik giris yapilir. */
    private void openChannel() {
        if (channelId.isEmpty()) return;
        if (Repo.hasSession()) {
            Play.playLiveById(this, channelId, channelName);
            return;
        }
        startActivity(new Intent(this, ProfilesActivity.class)
                .putExtra(ProfilesActivity.EXTRA_PLAY_CHANNEL_ID, channelId)
                .putExtra(ProfilesActivity.EXTRA_PLAY_CHANNEL_NAME, channelName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(closer);
        super.onDestroy();
    }
}
