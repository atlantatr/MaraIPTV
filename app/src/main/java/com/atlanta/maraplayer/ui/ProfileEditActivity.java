package com.atlanta.maraplayer.ui;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.atlanta.maraplayer.R;
import com.atlanta.maraplayer.data.Db;
import com.atlanta.maraplayer.data.Models.Account;
import com.atlanta.maraplayer.data.Models.Profile;
import com.atlanta.maraplayer.data.Sources;
import com.atlanta.maraplayer.data.XtreamApi;
import com.atlanta.maraplayer.util.Async;
import com.atlanta.maraplayer.util.Ui;

/** Xtream veya M3U profili ekleme / duzenleme. */
public class ProfileEditActivity extends BaseActivity {

    public static final String EXTRA_ID = "id";

    private EditText name, url, user, pass, epgUrl;
    private TextView status;
    private Profile profile = new Profile();
    private String type = Profile.TYPE_XTREAM;

    @Override
    protected boolean needsSession() {
        return false;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_profile_edit);
        name = v(R.id.name);
        url = v(R.id.url);
        user = v(R.id.user);
        pass = v(R.id.pass);
        epgUrl = v(R.id.epgUrl);
        status = v(R.id.status);

        long id = getIntent().getLongExtra(EXTRA_ID, -1);
        if (id > 0) {
            Profile p = Db.get().profile(id);
            if (p != null) {
                profile = p;
                type = p.type;
                ((TextView) v(R.id.title)).setText("Profili Duzenle");
                name.setText(p.name);
                url.setText(p.url);
                user.setText(p.user);
                pass.setText(p.pass);
                epgUrl.setText(p.epgUrl);
            }
        }
        applyType();

        v(R.id.typeXtream).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                type = Profile.TYPE_XTREAM;
                applyType();
            }
        });
        v(R.id.typeM3u).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                type = Profile.TYPE_M3U;
                applyType();
            }
        });

        // Xtream: M3U linki yapistirilirsa kullanici adi / sifreyi otomatik doldur
        url.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean has) {
                if (!has) parseM3uLink();
            }
        });

        v(R.id.btnSave).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!collect()) return;
                Db.get().saveProfile(profile);
                // basariliysa login() bu ekrani kapatir
                ProfilesActivity.login(ProfileEditActivity.this, profile);
            }
        });
        v(R.id.btnTest).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!collect()) return;
                test();
            }
        });
        v(R.id.btnCancel).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
    }

    private void applyType() {
        boolean m3u = Profile.TYPE_M3U.equals(type);
        v(R.id.typeXtream).setSelected(!m3u);
        v(R.id.typeM3u).setSelected(m3u);
        v(R.id.credBox).setVisibility(m3u ? View.GONE : View.VISIBLE);
        ((TextView) v(R.id.urlLabel)).setText(m3u ? "M3U calma listesi linki" : "Sunucu adresi (veya M3U linki yapistirin)");
        url.setHint(m3u ? "http://.../liste.m3u" : "http://sunucu.com:8080");
    }

    private void parseM3uLink() {
        if (Profile.TYPE_M3U.equals(type)) return;
        String s = url.getText().toString().trim();
        if (!s.contains("username=")) return;
        try {
            Uri u = Uri.parse(s.startsWith("http") ? s : "http://" + s);
            String un = u.getQueryParameter("username");
            String pw = u.getQueryParameter("password");
            if (un != null) user.setText(un);
            if (pw != null) pass.setText(pw);
            url.setText(XtreamApi.normalizeUrl(s));
            status.setText("M3U linkinden bilgiler dolduruldu.");
        } catch (Exception ignored) {
        }
    }

    private boolean collect() {
        parseM3uLink();
        boolean m3u = Profile.TYPE_M3U.equals(type);
        String raw = url.getText().toString().trim();
        String u = m3u ? raw : XtreamApi.normalizeUrl(raw);
        if (u.isEmpty() || (!m3u && (Ui.empty(user.getText().toString()) || Ui.empty(pass.getText().toString())))) {
            Ui.toast(this, m3u ? "M3U linki zorunlu" : "Sunucu, kullanici adi ve sifre zorunlu");
            return false;
        }
        profile.type = type;
        profile.url = u;
        profile.user = m3u ? "" : user.getText().toString().trim();
        profile.pass = m3u ? "" : pass.getText().toString().trim();
        profile.epgUrl = epgUrl.getText().toString().trim();
        String n = name.getText().toString().trim();
        if (n.isEmpty()) {
            String host = Uri.parse(u.startsWith("http") ? u : "http://" + u).getHost();
            n = m3u ? "M3U @ " + host : profile.user + " @ " + host;
        }
        profile.name = n;
        return true;
    }

    private void test() {
        status.setText("Test ediliyor...");
        final Profile p = profile;
        Async.run(this, new Async.Task<Account>() {
            @Override
            public Account run() throws Exception {
                return Sources.create(p).login();
            }
        }, new Async.Callback<Account>() {
            @Override
            public void on(Account a) {
                String exp = a.expDate > 0 ? Ui.date(a.expDate) : "Sinirsiz";
                status.setText("Basarili. Durum: " + a.status + "   Bitis: " + exp
                        + "   Baglanti: " + a.activeConnections + "/" + a.maxConnections);
                status.setTextColor(getResources().getColor(R.color.ok));
            }
        }, new Async.Callback<Exception>() {
            @Override
            public void on(Exception e) {
                status.setText("Hata: " + Ui.err(e));
                status.setTextColor(getResources().getColor(R.color.bad));
            }
        });
    }
}
