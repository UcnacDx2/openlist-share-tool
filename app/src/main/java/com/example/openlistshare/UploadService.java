package com.example.openlistshare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UploadService extends Service {
    private static final String CHANNEL_ID = "openlist_uploads";
    private static final int NOTIFICATION_ID = 20260924;
    private static final int PAGE_SIZE = 1000;
    private static final int LARGE_FILE_THRESHOLD = 8 * 1024 * 1024;
    private static final int REQUESTED_CHUNK_SIZE = 8 * 1024 * 1024;
    private static final int CHUNK_RETRIES = 3;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ArrayList<Uri> uris = intent == null
                ? null
                : intent.getParcelableArrayListExtra(MainActivity.EXTRA_URIS);

        if (uris == null || uris.isEmpty()) {
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }

        startForeground(
                NOTIFICATION_ID,
                buildNotification(
                        "OpenList 快传",
                        "准备上传 " + uris.size() + " 个文件",
                        0,
                        true,
                        ""
                )
        );

        executor.execute(() -> {
            try {
                uploadAll(uris);
            } finally {
                detachForeground();
                stopSelf(startId);
            }
        });

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void uploadAll(List<Uri> uris) {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);

        String base = normalizeBase(prefs.getString(MainActivity.KEY_BASE, ""));
        String token = prefs.getString(MainActivity.KEY_TOKEN, "").trim();
        String dir = normalizeDir(prefs.getString(MainActivity.KEY_DIR, "/uploads"));
        boolean overwrite = prefs.getBoolean(MainActivity.KEY_OVERWRITE, false);

        if (base.isEmpty() || token.isEmpty()) {
            finishWithError("请先配置 OpenList 地址和 Token");
            return;
        }

        int total = uris.size();

        for (int index = 0; index < total; index++) {
            Uri uri = uris.get(index);
            String originalName = sanitizeFileName(displayName(uri));
            long size = sizeOf(uri);
            String finalName = originalName;
            String stage = "准备";

            try {
                String target = joinPath(dir, originalName);

                stage = "检查重名";
                if (!overwrite && exists(base, token, target)) {
                    stage = "生成避免重名的新文件名";
                    target = timestampTarget(base, token, dir, originalName);
                }

                finalName = baseName(target);
                stage = "准备上传";
                updateProgress(index, total, finalName, 0);

                stage = "上传文件";
                if (size > LARGE_FILE_THRESHOLD) {
                    multipartUpload(
                            base,
                            token,
                            uri,
                            target,
                            overwrite,
                            size,
                            index,
                            total,
                            finalName
                    );
                } else {
                    uploadOne(
                            base,
                            token,
                            uri,
                            target,
                            overwrite,
                            size,
                            index,
                            total,
                            finalName
                    );
                }

                stage = "生成 OpenList 302 直链";
                String directUrl = getOpenList302Url(base, token, target);
                saveLastLink(directUrl, finalName);
                saveHistory(directUrl, finalName, "success", "");

                postComplete(index + 1, total, finalName, directUrl);
            } catch (Exception e) {
                String reason = friendlyError(e);
                saveHistory("", finalName, "failed", stage + "： " + reason);
                finishWithError(
                        "文件：" + originalName +
                                "\n阶段：" + stage +
                                "\n原因：" + reason
                );
                return;
            }
        }
    }

    private String timestampTarget(
            String base,
            String token,
            String dir,
            String originalName
    ) throws Exception {
        String stamp = new SimpleDateFormat(
                "yyyy-MM-dd HH-mm-ss",
                Locale.getDefault()
        ).format(new Date());

        String candidate = appendTimestamp(originalName, stamp);
        int serial = 2;

        while (exists(base, token, joinPath(dir, candidate))) {
            candidate = appendTimestamp(
                    originalName,
                    stamp + " #" + serial++
            );
        }

        return joinPath(dir, candidate);
    }

    private boolean exists(
            String base,
            String token,
            String target
    ) throws Exception {
        String dir = parentPath(target);
        String name = baseName(target);

        int page = 1;

        while (page <= 10000) {
            JSONObject req = new JSONObject();
            req.put("path", dir);
            req.put("password", "");
            req.put("page", page);
            req.put("per_page", PAGE_SIZE);
            req.put("refresh", false);

            HttpResult result = requestJson(
                    "POST",
                    base + "/api/fs/list",
                    token,
                    req.toString()
            );

            if (result.httpCode < 200 || result.httpCode >= 300) {
                throw new IOException(
                        "检查重名失败 HTTP " +
                                result.httpCode +
                                "：" +
                                safeMessage(result.body)
                );
            }

            JSONObject json = new JSONObject(result.body);
            int code = json.optInt("code", result.httpCode);

            if (code != 200) {
                throw new IOException(
                        "检查重名失败 " +
                                code +
                                "：" +
                                json.optString("message", result.body)
                );
            }

            JSONObject data = json.optJSONObject("data");
            if (data == null) return false;

            JSONArray content = data.optJSONArray("content");
            int reportedTotal = data.optInt(
                    "total",
                    content == null ? 0 : content.length()
            );

            if (content != null) {
                for (int i = 0; i < content.length(); i++) {
                    JSONObject item = content.optJSONObject(i);

                    if (item != null &&
                            name.equals(item.optString("name", ""))) {
                        return true;
                    }
                }
            }

            if (content == null ||
                    content.length() == 0 ||
                    page * PAGE_SIZE >= reportedTotal) {
                return false;
            }

            page++;
        }

        throw new IOException("目录文件过多，无法安全检查同名文件");
    }

    private void uploadOne(
            String base,
            String token,
            Uri uri,
            String target,
            boolean overwrite,
            long size,
            int index,
            int total,
            String displayName
    ) throws Exception {
        HttpURLConnection conn = null;

        try {
            URL url = new URL(base + "/api/fs/put");
            conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("PUT");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(0);
            conn.setDoOutput(true);

            conn.setRequestProperty("Authorization", token);
            conn.setRequestProperty("File-Path", Uri.encode(target, "/"));
            conn.setRequestProperty("As-Task", "false");
            conn.setRequestProperty("Overwrite", overwrite ? "true" : "false");
            conn.setRequestProperty("Content-Type", "application/octet-stream");

            if (size >= 0) {
                conn.setRequestProperty("X-File-Size", Long.toString(size));
                conn.setFixedLengthStreamingMode(size);
            } else {
                conn.setChunkedStreamingMode(128 * 1024);
            }

            try (InputStream raw = getContentResolver().openInputStream(uri)) {
                if (raw == null) throw new IOException("无法读取文件");

                try (InputStream in = new BufferedInputStream(raw, 128 * 1024);
                     OutputStream out = conn.getOutputStream()) {

                    byte[] buffer = new byte[128 * 1024];
                    long sent = 0;
                    long lastUpdate = 0;
                    int read;

                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        sent += read;

                        long now = System.currentTimeMillis();

                        if (size > 0 &&
                                (now - lastUpdate >= 300 || sent == size)) {
                            int progress = (int) Math.min(
                                    100L,
                                    sent * 100L / size
                            );

                            lastUpdate = now;

                            updateProgress(
                                    index,
                                    total,
                                    displayName,
                                    progress
                            );
                        }
                    }
                }
            }

            int code = conn.getResponseCode();
            String body = readBody(conn);

            if (code < 200 || code >= 300) {
                throw new IOException(
                        "OpenList 上传 HTTP " +
                                code +
                                "：" +
                                safeMessage(body)
                );
            }

            JSONObject json = body.isEmpty()
                    ? new JSONObject()
                    : new JSONObject(body);

            int apiCode = json.optInt("code", code);

            if (apiCode != 200) {
                throw new IOException(
                        "OpenList 上传失败 " +
                                apiCode +
                                "：" +
                                json.optString("message", body)
                );
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void multipartUpload(
            String base,
            String token,
            Uri uri,
            String target,
            boolean overwrite,
            long size,
            int index,
            int total,
            String displayName
    ) throws Exception {
        if (size <= 0) {
            throw new IOException("分片上传要求文件大小大于 0");
        }

        updateProgress(index, total, displayName, 0);

        JSONObject init = new JSONObject();
        JSONObject initData = multipartInit(
                base,
                token,
                target,
                overwrite,
                size
        );

        String uploadId = initData.optString("upload_id", "");
        if (uploadId.isEmpty()) {
            throw new IOException("OpenList 分片初始化未返回 upload_id");
        }

        long chunkSize = initData.optLong("chunk_size", REQUESTED_CHUNK_SIZE);
        if (chunkSize <= 0) {
            throw new IOException("OpenList 返回了无效的分片大小：" + chunkSize);
        }

        int totalChunks = initData.optInt(
                "total_chunks",
                (int) ((size + chunkSize - 1) / chunkSize)
        );

        byte[] buffer = new byte[(int) Math.min(chunkSize, Integer.MAX_VALUE)];
        long uploadedBefore = 0;
        int completedChunks = 0;

        try (InputStream raw = getContentResolver().openInputStream(uri)) {
            if (raw == null) {
                throw new IOException("无法读取文件");
            }

            InputStream in = new BufferedInputStream(raw, 128 * 1024);

            for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
                int expected = (int) Math.min(
                        chunkSize,
                        size - uploadedBefore
                );

                int actual = readChunk(in, buffer, expected);
                if (actual != expected) {
                    throw new IOException(
                            "文件读取不完整：第 " +
                                    (chunkIndex + 1) +
                                    "/" +
                                    totalChunks +
                                    " 片，期望 " +
                                    expected +
                                    " 字节，实际 " +
                                    actual +
                                    " 字节"
                    );
                }

                long chunkStart = uploadedBefore;
                Exception lastError = null;
                boolean success = false;

                for (int attempt = 1; attempt <= CHUNK_RETRIES; attempt++) {
                    try {
                        multipartChunk(
                                base,
                                token,
                                uploadId,
                                chunkIndex,
                                buffer,
                                actual,
                                index,
                                total,
                                displayName,
                                chunkStart,
                                size
                        );
                        success = true;
                        break;
                    } catch (Exception e) {
                        lastError = e;
                        updateProgress(
                                index,
                                total,
                                displayName +
                                        " · 第 " +
                                        (chunkIndex + 1) +
                                        "/" +
                                        totalChunks +
                                        " 片重试 " +
                                        attempt +
                                        "/" +
                                        CHUNK_RETRIES,
                                (int) Math.min(
                                        100L,
                                        (chunkStart * 100L) / size
                                )
                        );

                        if (attempt < CHUNK_RETRIES) {
                            try {
                                Thread.sleep(1000L);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IOException("上传被中断", interrupted);
                            }
                        }
                    }
                }

                if (!success) {
                    throw new IOException(
                            "第 " +
                                    (chunkIndex + 1) +
                                    "/" +
                                    totalChunks +
                                    " 片上传失败：" +
                                    friendlyError(lastError)
                    );
                }

                uploadedBefore += actual;
                completedChunks++;

                int progress = (int) Math.min(
                        100L,
                        uploadedBefore * 100L / size
                );

                updateProgress(
                        index,
                        total,
                        displayName +
                                " · 分片 " +
                                completedChunks +
                                "/" +
                                totalChunks,
                        progress
                );
            }
        }

        JSONObject complete = multipartComplete(
                base,
                token,
                uploadId
        );

        String state = complete.optString("state", "");
        if (!"completed".equals(state)) {
            String error = complete.optString("error", "");
            if (error.isEmpty()) {
                error = "OpenList 返回状态：" + state;
            }
            throw new IOException("分片合并失败：" + error);
        }

        updateProgress(
                index,
                total,
                displayName,
                100
        );
    }

    private JSONObject multipartInit(
            String base,
            String token,
            String target,
            boolean overwrite,
            long size
    ) throws Exception {
        HttpURLConnection conn = null;

        try {
            URL url = new URL(base + "/api/fs/multipart/init");
            conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setDoInput(true);

            conn.setRequestProperty("Authorization", token);
            conn.setRequestProperty("File-Path", Uri.encode(target, "/"));
            conn.setRequestProperty("X-File-Size", Long.toString(size));
            conn.setRequestProperty(
                    "X-Chunk-Size",
                    Integer.toString(REQUESTED_CHUNK_SIZE)
            );
            conn.setRequestProperty(
                    "Overwrite",
                    overwrite ? "true" : "false"
            );
            conn.setRequestProperty(
                    "Content-Type",
                    "application/octet-stream"
            );

            int httpCode = conn.getResponseCode();
            String body = readBody(conn);

            if (httpCode < 200 || httpCode >= 300) {
                throw new IOException(
                        "OpenList 分片初始化 HTTP " +
                                httpCode +
                                "：" +
                                safeMessage(body)
                );
            }

            JSONObject json;
            try {
                json = body.isEmpty() ? new JSONObject() : new JSONObject(body);
            } catch (Exception e) {
                throw new IOException(
                        "OpenList 分片初始化返回非 JSON HTTP " +
                                httpCode +
                                "：" +
                                safeMessage(body),
                        e
                );
            }

            int code = json.optInt("code", httpCode);
            if (code != 200) {
                throw new IOException(
                        "OpenList 分片初始化失败 " +
                                code +
                                "：" +
                                json.optString("message", body)
                );
            }

            JSONObject data = json.optJSONObject("data");
            if (data == null) {
                throw new IOException("OpenList 分片初始化没有 data");
            }

            return data;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void multipartChunk(
            String base,
            String token,
            String uploadId,
            int chunkIndex,
            byte[] buffer,
            int length,
            int fileIndex,
            int totalFiles,
            String displayName,
            long overallOffset,
            long totalSize
    ) throws Exception {
        HttpURLConnection conn = null;

        try {
            URL url = new URL(base + "/api/fs/multipart/chunk");
            conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("PUT");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(0);
            conn.setDoOutput(true);

            conn.setRequestProperty("Authorization", token);
            conn.setRequestProperty("X-Upload-Id", uploadId);
            conn.setRequestProperty("X-Chunk-Index", Integer.toString(chunkIndex));
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setFixedLengthStreamingMode(length);

            try (OutputStream out = conn.getOutputStream()) {
                int offset = 0;
                final int step = 64 * 1024;
                long lastUpdate = System.currentTimeMillis();

                while (offset < length) {
                    int n = Math.min(step, length - offset);
                    out.write(buffer, offset, n);
                    offset += n;

                    long now = System.currentTimeMillis();
                    if (now - lastUpdate >= 250 || offset == length) {
                        long uploaded = overallOffset + offset;
                        int progress = (int) Math.min(
                                100L,
                                uploaded * 100L / totalSize
                        );
                        updateProgress(
                                fileIndex,
                                totalFiles,
                                displayName +
                                        " · 分片 " +
                                        (chunkIndex + 1),
                                progress
                        );
                        lastUpdate = now;
                    }
                }
            }

            int httpCode = conn.getResponseCode();
            String body = readBody(conn);

            if (httpCode < 200 || httpCode >= 300) {
                throw new IOException(
                        "OpenList 分片 " +
                                (chunkIndex + 1) +
                                " HTTP " +
                                httpCode +
                                "：" +
                                safeMessage(body)
                );
            }

            JSONObject json;
            try {
                json = body.isEmpty() ? new JSONObject() : new JSONObject(body);
            } catch (Exception e) {
                throw new IOException(
                        "OpenList 分片 " +
                                (chunkIndex + 1) +
                                " 返回非 JSON HTTP " +
                                httpCode +
                                "：" +
                                safeMessage(body),
                        e
                );
            }

            int code = json.optInt("code", httpCode);
            if (code != 200) {
                throw new IOException(
                        "OpenList 分片 " +
                                (chunkIndex + 1) +
                                " 失败 " +
                                code +
                                "：" +
                                json.optString("message", body)
                );
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private JSONObject multipartComplete(
            String base,
            String token,
            String uploadId
    ) throws Exception {
        HttpURLConnection conn = null;

        try {
            URL url = new URL(base + "/api/fs/multipart/complete");
            conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(0);
            conn.setDoInput(true);

            conn.setRequestProperty("Authorization", token);
            conn.setRequestProperty("X-Upload-Id", uploadId);

            int httpCode = conn.getResponseCode();
            String body = readBody(conn);

            if (httpCode < 200 || httpCode >= 300) {
                throw new IOException(
                        "OpenList 分片完成 HTTP " +
                                httpCode +
                                "：" +
                                safeMessage(body)
                );
            }

            JSONObject json;
            try {
                json = body.isEmpty() ? new JSONObject() : new JSONObject(body);
            } catch (Exception e) {
                throw new IOException(
                        "OpenList 分片完成返回非 JSON HTTP " +
                                httpCode +
                                "：" +
                                safeMessage(body),
                        e
                );
            }

            int code = json.optInt("code", httpCode);
            if (code != 200) {
                throw new IOException(
                        "OpenList 分片完成失败 " +
                                code +
                                "：" +
                                json.optString("message", body)
                );
            }

            JSONObject data = json.optJSONObject("data");
            if (data == null) {
                throw new IOException("OpenList 分片完成没有 data");
            }

            return data;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private int readChunk(InputStream in, byte[] buffer, int expected)
            throws IOException {
        int total = 0;

        while (total < expected) {
            int n = in.read(buffer, total, expected - total);

            if (n == -1) {
                break;
            }

            if (n == 0) {
                int one = in.read();
                if (one == -1) break;
                buffer[total++] = (byte) one;
                continue;
            }

            total += n;
        }

        return total;
    }

    private String getOpenList302Url(
            String base,
            String token,
            String target
    ) throws Exception {
        JSONObject req = new JSONObject();
        req.put("path", target);
        req.put("password", "");

        HttpResult result = requestJson(
                "POST",
                base + "/api/fs/get",
                token,
                req.toString()
        );

        if (result.httpCode < 200 || result.httpCode >= 300) {
            throw new IOException(
                    "获取 OpenList 直链失败 HTTP " +
                            result.httpCode +
                            "：" +
                            safeMessage(result.body)
            );
        }

        JSONObject json = new JSONObject(result.body);
        int code = json.optInt("code", result.httpCode);

        if (code != 200) {
            throw new IOException(
                    "获取 OpenList 直链失败 " +
                            code +
                            "：" +
                            json.optString("message", result.body)
            );
        }

        JSONObject data = json.optJSONObject("data");
        if (data == null) {
            throw new IOException("OpenList 未返回文件信息");
        }

        String sign = data.optString("sign", "");
        String link = base + "/d" + Uri.encode(target, "/");

        if (!sign.isEmpty()) {
            link += "?sign=" + Uri.encode(sign);
        }

        return link;
    }

    private void saveLastLink(String url, String name) {
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .edit()
                .putString(MainActivity.KEY_LAST_URL, url)
                .putString(MainActivity.KEY_LAST_NAME, name)
                .apply();
    }

    private void saveHistory(String url, String name, String status, String error) {
        SharedPreferences prefs =
                getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);

        JSONArray oldHistory;

        try {
            String raw = prefs.getString(MainActivity.KEY_HISTORY, "");
            oldHistory = raw.isEmpty() ? new JSONArray() : new JSONArray(raw);
        } catch (Exception e) {
            oldHistory = new JSONArray();
        }

        JSONArray newHistory = new JSONArray();

        JSONObject item = new JSONObject();

        try {
            item.put(
                    "time",
                    new SimpleDateFormat(
                            "yyyy-MM-dd HH:mm:ss",
                            Locale.getDefault()
                    ).format(new Date())
            );
            item.put("name", name);
            item.put("url", url);
            item.put("status", status);
            if (error != null && !error.isEmpty()) {
                item.put("error", error);
            }
        } catch (Exception ignored) {
        }

        newHistory.put(item);

        for (int i = 0; i < oldHistory.length() && i < MainActivityHistoryLimit(); i++) {
            JSONObject old = oldHistory.optJSONObject(i);
            if (old != null) newHistory.put(old);
        }

        prefs.edit()
                .putString(MainActivity.KEY_HISTORY, newHistory.toString())
                .apply();
    }

    private int MainActivityHistoryLimit() {
        return 49;
    }

    private void postComplete(
            int completed,
            int total,
            String name,
            String url
    ) {
        int overall = total == 0
                ? 100
                : (completed * 100 / total);

        String text = completed == total
                ? name + "\n" + url
                : completed + "/" + total + " · " + name + "\n" + url;

        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        nm.notify(
                NOTIFICATION_ID,
                buildNotification(
                        completed == total
                                ? "OpenList 快传完成"
                                : "OpenList 快传",
                        text,
                        overall,
                        completed != total,
                        url
                )
        );
    }

    private void updateProgress(
            int index,
            int total,
            String name,
            int fileProgress
    ) {
        int completed = index;

        int overall = total <= 0
                ? fileProgress
                : (int) Math.min(
                        100L,
                        ((long) completed * 100L + fileProgress) / total
                );

        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        nm.notify(
                NOTIFICATION_ID,
                buildNotification(
                        "OpenList 快传",
                        (index + 1) + "/" + total +
                                " · " + name +
                                " · " + fileProgress + "%",
                        overall,
                        true,
                        getLastUrl()
                )
        );
    }

    private Notification buildNotification(
            String title,
            String text,
            int progress,
            boolean ongoing,
            String link
    ) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(
                this,
                1,
                openIntent,
                pendingFlags()
        );

        Notification.Builder builder =
                new Notification.Builder(this, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.stat_sys_upload)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setStyle(
                                new Notification.BigTextStyle().bigText(text)
                        )
                        .setContentIntent(openPending)
                        .setOnlyAlertOnce(true)
                        .setOngoing(ongoing)
                        .setAutoCancel(!ongoing);

        if (ongoing) {
            builder.setProgress(
                    100,
                    Math.max(0, Math.min(100, progress)),
                    false
            );
        }

        if (link != null && !link.isEmpty()) {
            Intent copyIntent =
                    new Intent(this, NotificationActionReceiver.class);

            copyIntent.setAction(
                    NotificationActionReceiver.ACTION_COPY_LINK
            );
            copyIntent.putExtra(
                    NotificationActionReceiver.EXTRA_LINK,
                    link
            );

            PendingIntent copyPending =
                    PendingIntent.getBroadcast(
                            this,
                            2,
                            copyIntent,
                            pendingFlags()
                    );

            builder.addAction(
                    new Notification.Action.Builder(
                            Icon.createWithResource(
                                    this,
                                    android.R.drawable.ic_menu_save
                            ),
                            "复制直链",
                            copyPending
                    ).build()
            );
        }

        return builder.build();
    }

    private int pendingFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT |
                PendingIntent.FLAG_IMMUTABLE;
    }

    private void createNotificationChannel() {
        NotificationChannel channel =
                new NotificationChannel(
                        CHANNEL_ID,
                        "OpenList 上传",
                        NotificationManager.IMPORTANCE_LOW
                );

        channel.setDescription("显示 OpenList 文件上传进度和直链");
        channel.setShowBadge(false);

        NotificationManager nm =
                getSystemService(NotificationManager.class);

        nm.createNotificationChannel(channel);
    }

    private void finishWithError(String message) {
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        nm.notify(
                NOTIFICATION_ID,
                buildNotification(
                        "OpenList 快传失败",
                        message,
                        0,
                        false,
                        getLastUrl()
                )
        );

        detachForeground();
    }

    private void detachForeground() {
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(Service.STOP_FOREGROUND_DETACH);
        } else {
            stopForeground(false);
        }
    }

    private String getLastUrl() {
        return getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .getString(MainActivity.KEY_LAST_URL, "");
    }

    private HttpResult requestJson(
            String method,
            String urlText,
            String token,
            String jsonBody
    ) throws Exception {
        HttpURLConnection conn = null;

        try {
            URL url = new URL(urlText);
            conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod(method);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setDoInput(true);
            conn.setDoOutput(true);

            conn.setRequestProperty("Authorization", token);
            conn.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=UTF-8"
            );

            byte[] payload =
                    jsonBody.getBytes(StandardCharsets.UTF_8);

            conn.setFixedLengthStreamingMode(payload.length);

            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }

            int code = conn.getResponseCode();
            return new HttpResult(code, readBody(conn));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String readBody(HttpURLConnection conn) throws IOException {
        InputStream stream;

        try {
            stream = conn.getInputStream();
        } catch (IOException e) {
            stream = conn.getErrorStream();
        }

        if (stream == null) return "";

        try (BufferedReader reader =
                     new BufferedReader(
                             new InputStreamReader(
                                     stream,
                                     StandardCharsets.UTF_8
                             ))) {
            StringBuilder sb = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }

            return sb.toString();
        }
    }

    private long sizeOf(Uri uri) {
        android.database.Cursor cursor = null;

        try {
            cursor = getContentResolver().query(
                    uri,
                    new String[]{OpenableColumns.SIZE},
                    null,
                    null,
                    null
            );

            if (cursor != null && cursor.moveToFirst()) {
                return cursor.isNull(0)
                        ? -1
                        : cursor.getLong(0);
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }

        return -1;
    }

    private String displayName(Uri uri) {
        android.database.Cursor cursor = null;

        try {
            cursor = getContentResolver().query(
                    uri,
                    new String[]{OpenableColumns.DISPLAY_NAME},
                    null,
                    null,
                    null
            );

            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);

                if (name != null && !name.trim().isEmpty()) {
                    return name;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }

        String path = uri.getPath();

        if (path == null || path.isEmpty()) {
            return "upload.bin";
        }

        int i = path.lastIndexOf('/');

        return i >= 0 ? path.substring(i + 1) : path;
    }

    private String sanitizeFileName(String value) {
        String s = value == null ? "" : value.trim();

        if (s.isEmpty()) s = "upload.bin";

        return s.replace("/", "_").replace("\\", "_");
    }

    private String appendTimestamp(String name, String timestamp) {
        int dot = name.lastIndexOf('.');

        if (dot > 0) {
            return name.substring(0, dot) +
                    " (" + timestamp + ")" +
                    name.substring(dot);
        }

        return name + " (" + timestamp + ")";
    }

    private String parentPath(String path) {
        int index = path.lastIndexOf('/');

        if (index <= 0) return "/";

        return path.substring(0, index);
    }

    private String baseName(String path) {
        int index = path.lastIndexOf('/');

        if (index < 0) return path;

        return path.substring(index + 1);
    }

    private String joinPath(String dir, String name) {
        return "/".equals(dir)
                ? "/" + name
                : dir + "/" + name;
    }

    private String normalizeDir(String value) {
        String s = value == null ? "" : value.trim();

        if (s.isEmpty()) return "/";

        if (!s.startsWith("/")) s = "/" + s;

        while (s.endsWith("/") && s.length() > 1) {
            s = s.substring(0, s.length() - 1);
        }

        return s;
    }

    private String normalizeBase(String value) {
        String s = value == null ? "" : value.trim();

        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }

        return s;
    }

    private String safeMessage(String body) {
        if (body == null || body.isEmpty()) return "无返回内容";

        try {
            return new JSONObject(body)
                    .optString("message", body);
        } catch (Exception ignored) {
            return body.length() > 300
                    ? body.substring(0, 300)
                    : body;
        }
    }

    private String friendlyError(Exception e) {
        Throwable current = e;
        StringBuilder sb = new StringBuilder();

        for (int i = 0; current != null && i < 3; i++, current = current.getCause()) {
            String msg = current.getMessage();

            if (msg == null || msg.isEmpty()) {
                msg = current.getClass().getSimpleName();
            }

            if (sb.length() > 0) {
                sb.append(" <- ");
            }

            sb.append(msg);
        }

        return sb.length() == 0
                ? e.getClass().getSimpleName()
                : sb.toString();
    }

    private static final class HttpResult {
        final int httpCode;
        final String body;

        HttpResult(int httpCode, String body) {
            this.httpCode = httpCode;
            this.body = body;
        }
    }
}