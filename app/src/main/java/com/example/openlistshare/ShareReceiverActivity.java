package com.example.openlistshare;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

public class ShareReceiverActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleShare(getIntent());
        finish();
    }

    private void handleShare(Intent incoming) {
        if (incoming == null) return;

        List<Uri> uris = extractUris(incoming);
        if (uris.isEmpty()) return;

        int grantFlags = incoming.getFlags() &
                (Intent.FLAG_GRANT_READ_URI_PERMISSION |
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

        if ((grantFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            for (Uri uri : uris) {
                try {
                    getContentResolver().takePersistableUriPermission(
                            uri,
                            grantFlags
                    );
                } catch (Exception ignored) {
                }
            }
        }

        Intent service = new Intent(
                this,
                UploadService.class
        );

        service.putParcelableArrayListExtra(
                MainActivity.EXTRA_URIS,
                new ArrayList<>(uris)
        );
        service.putExtra(
                UploadService.EXTRA_FROM_SHARE,
                true
        );

        if (grantFlags != 0) {
            service.addFlags(grantFlags);
        }

        ClipData clipData = incoming.getClipData();

        if (clipData == null) {
            clipData = buildClipData(uris);
        }

        if (clipData != null) {
            service.setClipData(clipData);
        }

        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(service);
            } else {
                startService(service);
            }
        } catch (RuntimeException ignored) {
        }
    }

    private ClipData buildClipData(List<Uri> uris) {
        if (uris.isEmpty()) return null;

        ClipData data = ClipData.newUri(
                getContentResolver(),
                "OpenList 分享",
                uris.get(0)
        );

        for (int i = 1; i < uris.size(); i++) {
            data.addItem(
                    new ClipData.Item(uris.get(i))
            );
        }

        return data;
    }

    private List<Uri> extractUris(Intent intent) {
        List<Uri> uris = new ArrayList<>();

        Uri data = intent.getData();

        if (data != null &&
                Intent.ACTION_SEND.equals(intent.getAction())) {
            uris.add(data);
        }

        try {
            if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
                ArrayList<Uri> multiple;

                if (Build.VERSION.SDK_INT >= 33) {
                    multiple = intent.getParcelableArrayListExtra(
                            Intent.EXTRA_STREAM,
                            Uri.class
                    );
                } else {
                    multiple = intent.getParcelableArrayListExtra(
                            Intent.EXTRA_STREAM
                    );
                }

                if (multiple != null) {
                    for (Uri uri : multiple) {
                        if (uri != null && !uris.contains(uri)) {
                            uris.add(uri);
                        }
                    }
                }
            } else {
                Uri extra;

                if (Build.VERSION.SDK_INT >= 33) {
                    extra = intent.getParcelableExtra(
                            Intent.EXTRA_STREAM,
                            Uri.class
                    );
                } else {
                    extra = intent.getParcelableExtra(
                            Intent.EXTRA_STREAM
                    );
                }

                if (extra != null && !uris.contains(extra)) {
                    uris.add(extra);
                }
            }
        } catch (Exception ignored) {
        }

        ClipData clipData = intent.getClipData();

        if (clipData != null) {
            for (int i = 0;
                    i < clipData.getItemCount();
                    i++) {
                Uri uri = clipData.getItemAt(i).getUri();

                if (uri != null && !uris.contains(uri)) {
                    uris.add(uri);
                }
            }
        }

        return uris;
    }
}
