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
import com.atlanta.maraplayer.data.XtreamApi;
import com.atlanta.maraplayer.util.Async;
import com.atlanta.maraplayer.util.Ui;

public class ProfileEditActivity extends BaseActivity {

    public static final String EXTRA_ID = "id";

    private EditText name, url, user, pass;
    private TextView status;
    private Profile profile = new Profile();

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
        status = v(R.id.status);

        long id = getIntent().getLongExtra(EXTRA_ID, -1);
        if (id > 0) {
            Profile p = Db.get().profile(id);
            if (p != null) {
                profile = p;
                ((TextView) v(R.id.title)).setText("Profili Duzenle");
                name.setText(p.name);
                url.setText(p.url);
                user.setText(p.user);
                pass.setText(p.pass);
            }
        }

        // M3U linki yapistirilirsa kullanici adi / sifreyi otomatik doldur
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

    private void parseM3uLink() {
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
        String u = XtreamApi.normalizeUrl(url.getText().toString());
        if (u.isEmpty() || Ui.empty(user.getText().toString()) || Ui.empty(pass.getText().toString())) {
            Ui.toast(this, "Sunucu, kullanici adi ve sifre zorunlu");
            return false;
        }
        profile.url = u;
        profile.user = user.getText().toString().trim();
        profile.pass = pass.getText().toString().trim();
        String n = name.getText().toString().trim();
        if (n.isEmpty()) n = profile.user + " @ " + Uri.parse(u).getHost();
        profile.name = n;
        return true;
    }

    private void test() {
        status.setText("Test ediliyor...");
        final Profile p = profile;
        Async.run(this, new Async.Task<Account>() {
            @Override
            public Account run() throws Exception {
                return new XtreamApi(p).login();
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
