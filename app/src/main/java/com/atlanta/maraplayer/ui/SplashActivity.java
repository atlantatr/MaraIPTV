package com.atlanta.maraplayer.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

import com.atlanta.maraplayer.BuildConfig;
import com.atlanta.maraplayer.R;
import com.atlanta.maraplayer.data.Repo;

/**
 * Acilis ekrani: logo animasyonu (buyume + solma) ve ardindan profil ekrani.
 * Tum surumlerde (4.2 / 5.0 / 7.0) ortaktir; modern surumlerde layout
 * src/modern/res/layout/activity_splash.xml ile zenginlestirilir.
 */
public class SplashActivity extends Activity {

    private static final long HOLD_MS = 1500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean navigated;

    private final Runnable go = new Runnable() {
        @Override
        public void run() {
            navigate();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        View mark = findViewById(R.id.splashMark);
        View word = findViewById(R.id.splashWord);
        View tagline = null; // alt yazi kaldirildi
        View version = findViewById(R.id.splashVersion);

        if (version instanceof TextView) {
            try {
                ((TextView) version).setText(getString(R.string.version_x,
                        getPackageManager().getPackageInfo(getPackageName(), 0).versionName)
                        + "  \u2022  " + BuildConfig.EDITION);
            } catch (Exception ignored) {
            }
        }

        animateIn(mark, word, tagline, version);
        handler.postDelayed(go, HOLD_MS);
    }

    private void animateIn(View mark, View word, View tagline, View version) {
        float rise = 18 * getResources().getDisplayMetrics().density;

        if (mark != null) {
            mark.setAlpha(0f);
            mark.setScaleX(0.78f);
            mark.setScaleY(0.78f);
            mark.animate()
                    .alpha(1f)
                    .scaleX(1f).scaleY(1f)
                    .setStartDelay(80)
                    .setDuration(620)
                    .setInterpolator(new OvershootInterpolator(0.9f))
                    .start();
        }

        if (word != null) {
            word.setAlpha(0f);
            word.setTranslationY(rise);
            word.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(340)
                    .setDuration(460)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }

        if (tagline != null) {
            tagline.setAlpha(0f);
            tagline.setTranslationY(rise);
            tagline.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(520)
                    .setDuration(420)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }

        if (version != null) {
            version.setAlpha(0f);
            version.animate()
                    .alpha(1f)
                    .setStartDelay(760)
                    .setDuration(400)
                    .start();
        }
    }

    private void navigate() {
        if (navigated || isFinishing()) return;
        navigated = true;
        // Oturum hala bellekteyse (uygulama arka plandan dondu) dogrudan ana ekran.
        // Sifirdan acilista Profiller ekrani tek hesabi kendisi otomatik acar.
        Class<?> next = Repo.hasSession() ? HomeActivity.class : ProfilesActivity.class;
        startActivity(new Intent(this, next));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override
    public void onBackPressed() {
        // Acilis ekraninda geri: uygulamadan cik
        handler.removeCallbacks(go);
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(go);
        super.onDestroy();
    }
}
