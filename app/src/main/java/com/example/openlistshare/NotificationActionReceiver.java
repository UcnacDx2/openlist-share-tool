package com.example.openlistshare;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

public class NotificationActionReceiver extends BroadcastReceiver {
    public static final String ACTION_COPY_LINK =
            "com.example.openlistshare.COPY_LINK";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_COPY_LINK.equals(intent.getAction())) return;

        String url = context
                .getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                .getString(MainActivity.KEY_LAST_URL, "");

        if (url.isEmpty()) return;

        ClipboardManager clipboard =
                (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);

        if (clipboard != null) {
            clipboard.setPrimaryClip(
                    ClipData.newPlainText("OpenList 直链", url)
            );
            Toast.makeText(context, "直链已复制", Toast.LENGTH_SHORT).show();
        }
    }
}
