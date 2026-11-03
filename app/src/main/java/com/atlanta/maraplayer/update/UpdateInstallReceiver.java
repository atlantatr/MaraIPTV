package com.atlanta.maraplayer.update;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

/** Indirme bitince APK kurulum ekranini acar. */
public class UpdateInstallReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context c, Intent intent) {
        if (intent == null || !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
        long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
        long want = UpdateChecker.pendingDownloadId();
        if (want < 0 || id != want) return;

        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return;
        Uri uri = dm.getUriForDownloadedFile(id);
        if (uri == null) {
            Toast.makeText(c, "Güncelleme indirilemedi", Toast.LENGTH_LONG).show();
            UpdateChecker.clearPending();
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, UpdateChecker.APK_MIME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            c.startActivity(i);
            UpdateChecker.clearPending();
        } catch (Exception e) {
            Toast.makeText(c, "Kurulum açılamadı: bilinmeyen kaynak iznini verin", Toast.LENGTH_LONG).show();
        }
    }
}
