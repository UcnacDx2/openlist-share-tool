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
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class UploadService extends Service {
    public static final String EXTRA_FROM_SHARE = "from_share";

    private static final String CHANNEL_ID = "openlist_uploads";
    private static final int NOTIFICATION_ID = 20260924;
    private static final int PAGE_SIZE = 1000;
    private static final int DEFAULT_LARGE_FILE_THRESHOLD_MB = 512;
    private static final int DEFAULT_CHUNK_SIZE_MB = 2;
    private static final int DEFAULT_CHUNK_PARALLEL = 3;
    private static final int DEFAULT_FILE_PARALLEL = 2;
    private static final int MAX_CHUNK_SIZE_MB = 64;
    private static final int MAX_CHUNK_PARALLEL = 8;
    private static final int MAX_FILE_PARALLEL = 4;
    private static final int CHUNK_RETRIES = 3;
    private static final int PROGRESS_WRITE_SIZE = 128 * 1024;
    private static final long AS_TASK_POLL_MS = 2000L;
    private static final int AS_TASK_MAX_POLLS = 900;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile BatchProgress activeBatchProgress;
    private ScheduledExecutorService progressScheduler;

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

        boolean fromShare =
                intent != null &&
                        intent.getBooleanExtra(
                                EXTRA_FROM_SHARE,
                                false
                        );

        startForeground(
                NOTIFICATION_ID,
                buildNotification(
                        "OpenList 快传",
                        fromShare
                                ? "已收到分享： " +
                                        uris.size() +
                                        " 个文件，准备上传"
                                : "准备上传 " +
                                        uris.size() +
                                        " 个文件",
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
        stopProgressScheduler();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void uploadAll(List<Uri> uris) {
        SharedPreferences prefs =
                getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);

        String base = normalizeBase(
                prefs.getString(MainActivity.KEY_BASE, "")
        );
        String token =
                prefs.getString(MainActivity.KEY_TOKEN, "").trim();
        String dir = normalizeDir(
                prefs.getString(MainActivity.KEY_DIR, "/uploads")
        );
        boolean overwrite = prefs.getBoolean(
                MainActivity.KEY_OVERWRITE,
                false
        );

        int chunkSizeMb = clampInt(
                prefs.getInt(
                        MainActivity.KEY_CHUNK_SIZE_MB,
                        DEFAULT_CHUNK_SIZE_MB
                ),
                1,
                MAX_CHUNK_SIZE_MB
        );
        int chunkParallel = clampInt(
                prefs.getInt(
                        MainActivity.KEY_CHUNK_PARALLEL,
                        DEFAULT_CHUNK_PARALLEL
                ),
                1,
                MAX_CHUNK_PARALLEL
        );
        int fileParallel = clampInt(
                prefs.getInt(
                        MainActivity.KEY_FILE_PARALLEL,
                        DEFAULT_FILE_PARALLEL
                ),
                1,
                MAX_FILE_PARALLEL
        );
        int thresholdMb = clampInt(
                prefs.getInt(
                        MainActivity.KEY_LARGE_FILE_THRESHOLD_MB,
                        DEFAULT_LARGE_FILE_THRESHOLD_MB
                ),
                1,
                1024
        );

        long chunkSize = chunkSizeMb * 1024L * 1024L;
        long largeFileThreshold =
                thresholdMb * 1024L * 1024L;

        if (base.isEmpty() || token.isEmpty()) {
            finishWithError("请先配置 OpenList 地址和 Token");
            return;
        }

        BatchProgress batchProgress =
                new BatchProgress(uris.size());

        activeBatchProgress = batchProgress;
        initializeProgress(batchProgress, uris);
        startProgressScheduler(
                batchProgress,
                uris.size()
        );

        Set<String> reservedTargets =
                Collections.synchronizedSet(new HashSet<>());

        ExecutorService fileExecutor =
                Executors.newFixedThreadPool(fileParallel);

        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int index = 0; index < uris.size(); index++) {
                final int fileIndex = index;
                final Uri uri = uris.get(index);

                futures.add(
                        fileExecutor.submit(() ->
                                uploadSingleFile(
                                        base,
                                        token,
                                        dir,
                                        overwrite,
                                        uri,
                                        fileIndex,
                                        uris.size(),
                                        chunkSize,
                                        chunkParallel,
                                        largeFileThreshold,
                                        reservedTargets
                                )
                        )
                );
            }

            boolean anyFailed = false;

            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    anyFailed = true;
                }
            }

            publishProgressNow(
                    batchProgress,
                    uris.size()
            );
            stopProgressScheduler();
            postBatchFinished(
                    batchProgress,
                    uris.size(),
                    anyFailed
            );
        } finally {
            fileExecutor.shutdownNow();
            stopProgressScheduler();
            activeBatchProgress = null;
        }
    }

    private void uploadSingleFile(
            String base,
            String token,
            String dir,
            boolean overwrite,
            Uri uri,
            int index,
            int total,
            long chunkSize,
            int chunkParallel,
            long largeFileThreshold,
            Set<String> reservedTargets
    ) {
        String originalName =
                sanitizeFileName(displayName(uri));
        long size = sizeOf(uri);
        String finalName = originalName;
        String stage = "准备";

        try {
            String target = reserveTarget(
                    base,
                    token,
                    dir,
                    originalName,
                    overwrite,
                    reservedTargets
            );

            finalName = baseName(target);

            stage = "准备上传";
            updateProgress(
                    index,
                    total,
                    finalName,
                    0,
                    "准备上传",
                    "uploading"
            );

            stage = "上传文件";

            if (size > largeFileThreshold) {
                multipartUpload(
                        base,
                        token,
                        uri,
                        target,
                        overwrite,
                        size,
                        chunkSize,
                        chunkParallel,
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
            String directUrl = getOpenList302Url(
                    base,
                    token,
                    target
            );

            saveLastLink(directUrl, finalName);
            saveHistory(
                    directUrl,
                    finalName,
                    "success",
                    ""
            );

            updateProgress(
                    index,
                    total,
                    finalName,
                    100,
                    "上传完成",
                    "completed"
            );
        } catch (Exception e) {
            String reason = friendlyError(e);

            saveHistory(
                    "",
                    finalName,
                    "failed",
                    stage + "： " + reason
            );

            updateProgress(
                    index,
                    total,
                    finalName,
                    0,
                    stage + "： " + reason,
                    "failed"
            );
        }
    }

    private String reserveTarget(
            String base,
            String token,
            String dir,
            String originalName,
            boolean overwrite,
            Set<String> reservedTargets
    ) throws Exception {
        String target = joinPath(dir, originalName);

        synchronized (reservedTargets) {
            if (reservedTargets.contains(target)) {
                target = localUniqueTarget(
                        originalName,
                        dir,
                        reservedTargets
                );
            }

            reservedTargets.add(target);
        }

        if (!overwrite && exists(base, token, target)) {
            synchronized (reservedTargets) {
                reservedTargets.remove(target);
                target = timestampTarget(
                        base,
                        token,
                        dir,
                        originalName
                );

                while (reservedTargets.contains(target)) {
                    target = timestampTarget(
                            base,
                            token,
                            dir,
                            originalName
                    );
                }

                reservedTargets.add(target);
            }
        }

        return target;
    }

    private String localUniqueTarget(
            String originalName,
            String dir,
            Set<String> reservedTargets
    ) {
        String stamp = new SimpleDateFormat(
                "yyyy-MM-dd HH-mm-ss",
                Locale.getDefault()
        ).format(new Date());

        int serial = 2;
        String target;

        do {
            String candidate = appendTimestamp(
                    originalName,
                    stamp + " #" + serial++
            );

            target = joinPath(dir, candidate);
        } while (reservedTargets.contains(target));

        return target;
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void initializeProgress(
            BatchProgress batchProgress,
            List<Uri> uris
    ) {
        for (int i = 0; i < uris.size(); i++) {
            Uri uri = uris.get(i);

            batchProgress.names.set(
                    i,
                    sanitizeFileName(displayName(uri))
            );
            batchProgress.sizes[i] =
                    Math.max(0L, sizeOf(uri));
        }

        publishProgressNow(
                batchProgress,
                uris.size()
        );
    }

    private void startProgressScheduler(
            BatchProgress batchProgress,
            int total
    ) {
        progressScheduler =
                new ScheduledThreadPoolExecutor(1);

        progressScheduler.scheduleAtFixedRate(
                () -> publishProgress(
                        batchProgress,
                        total
                ),
                0L,
                250L,
                TimeUnit.MILLISECONDS
        );
    }

    private void stopProgressScheduler() {
        ScheduledExecutorService scheduler =
                progressScheduler;

        progressScheduler = null;

        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void publishProgressNow(
            BatchProgress batchProgress,
            int total
    ) {
        publishProgress(
                batchProgress,
                total
        );
    }

    private void publishProgress(
            BatchProgress batchProgress,
            int total
    ) {
        JSONArray files = buildProgressJson(batchProgress);
        persistProgress(files);
        notifyUploadProgress(
                files,
                total
        );
    }

    private synchronized void persistProgress(
            JSONArray files
    ) {
        getSharedPreferences(
                MainActivity.PREFS,
                MODE_PRIVATE
        ).edit()
                .putString(
                        MainActivity.KEY_UPLOAD_PROGRESS,
                        files.toString()
                )
                .apply();
    }

    private JSONArray buildProgressJson(
            BatchProgress batchProgress
    ) {
        JSONArray files = new JSONArray();

        for (int i = 0;
                i < batchProgress.total;
                i++) {
            JSONObject item = new JSONObject();

            try {
                String name =
                        batchProgress.names.get(i);
                String status =
                        batchProgress.status.get(i);
                String detail =
                        batchProgress.detail.get(i);

                int progress =
                        batchProgress.progress.get(i);

                long size =
                        batchProgress.sizes[i];
                long transferred =
                        batchProgress.transferred.get(i);

                if (size > 0 &&
                        "uploading".equals(status)) {
                    progress = Math.max(
                            progress,
                            Math.min(
                                    99,
                                    (int) (
                                            transferred *
                                                    100L /
                                                    size
                                    )
                            )
                    );
                }

                if ("completed".equals(status)) {
                    progress = 100;
                }

                item.put(
                        "name",
                        name == null
                                ? "upload.bin"
                                : name
                );
                item.put(
                        "size",
                        Math.max(0L, size)
                );
                item.put(
                        "progress",
                        progress
                );
                item.put(
                        "status",
                        status == null
                                ? "pending"
                                : status
                );
                item.put(
                        "detail",
                        detail == null
                                ? ""
                                : detail
                );
            } catch (Exception ignored) {
            }

            files.put(item);
        }

        return files;
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

        String candidate = appendTimestamp(
                originalName,
                stamp
        );

        int serial = 2;

        while (exists(
                base,
                token,
                joinPath(dir, candidate)
        )) {
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

            if (result.httpCode < 200 ||
                    result.httpCode >= 300) {
                throw new IOException(
                        "检查重名失败 HTTP " +
                                result.httpCode +
                                "：" +
                                safeMessage(result.body)
                );
            }

            JSONObject json =
                    new JSONObject(result.body);
            int code = json.optInt(
                    "code",
                    result.httpCode
            );

            if (code != 200) {
                throw new IOException(
                        "检查重名失败 " +
                                code +
                                "：" +
                                json.optString(
                                        "message",
                                        result.body
                                )
                );
            }

            JSONObject data =
                    json.optJSONObject("data");

            if (data == null) return false;

            JSONArray content =
                    data.optJSONArray("content");

            int reportedTotal =
                    data.optInt(
                            "total",
                            content == null
                                    ? 0
                                    : content.length()
                    );

            if (content != null) {
                for (int i = 0;
                        i < content.length();
                        i++) {
                    JSONObject item =
                            content.optJSONObject(i);

                    if (item != null &&
                            name.equals(
                                    item.optString(
                                            "name",
                                            ""
                                    )
                            )) {
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

        throw new IOException(
                "目录文件过多，无法安全检查同名文件"
        );
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
            // The foreground connection only uploads bytes into OpenList's
            // task cache. The actual storage write happens server-side after
            // the request is accepted, so don't impose a response deadline.
            conn.setReadTimeout(0);
            conn.setDoInput(true);
            conn.setDoOutput(true);

            conn.setRequestProperty("Authorization", token);
            conn.setRequestProperty(
                    "File-Path",
                    Uri.encode(target, "/")
            );
            conn.setRequestProperty("As-Task", "true");
            conn.setRequestProperty(
                    "Overwrite",
                    overwrite ? "true" : "false"
            );
            conn.setRequestProperty(
                    "Content-Type",
                    "application/octet-stream"
            );

            if (size >= 0) {
                conn.setRequestProperty(
                        "X-File-Size",
                        Long.toString(size)
                );
                conn.setFixedLengthStreamingMode(size);
            } else {
                conn.setChunkedStreamingMode(
                        PROGRESS_WRITE_SIZE
                );
            }

            try (
                    InputStream raw =
                            getContentResolver().openInputStream(uri)
            ) {
                if (raw == null) {
                    throw new IOException(
                            "无法读取文件"
                    );
                }

                try (
                        InputStream in =
                                new BufferedInputStream(
                                        raw,
                                        PROGRESS_WRITE_SIZE
                                );
                        OutputStream out =
                                conn.getOutputStream()
                ) {
                    byte[] buffer =
                            new byte[PROGRESS_WRITE_SIZE];
                    long sent = 0L;
                    long lastUpdate = 0L;
                    int read;

                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        sent += read;

                        long now =
                                System.currentTimeMillis();

                        if (size > 0 &&
                                (now - lastUpdate >= 250L ||
                                        sent == size)) {
                            int progress =
                                    (int) Math.min(
                                            99L,
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

                    out.flush();
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

            JSONObject json =
                    body.isEmpty()
                            ? new JSONObject()
                            : new JSONObject(body);

            int apiCode =
                    json.optInt(
                            "code",
                            code
                    );

            if (apiCode != 200) {
                throw new IOException(
                        "OpenList 上传失败 " +
                                apiCode +
                                "：" +
                                json.optString(
                                        "message",
                                        body
                                )
                );
            }

            // With As-Task=true OpenList has accepted the request and created
            // a background upload task. Poll that task until the actual storage
            // write succeeds, instead of guessing from file visibility.
            JSONObject task =
                    json.optJSONObject("data") == null
                            ? null
                            : json.optJSONObject("data")
                                    .optJSONObject("task");

            String taskId =
                    task == null
                            ? ""
                            : task.optString("id", "");

            if (!taskId.isEmpty()) {
                updateProgress(
                        index,
                        total,
                        displayName,
                        99,
                        "服务器后台写入",
                        "uploading"
                );

                for (int poll = 0;
                        poll < AS_TASK_MAX_POLLS;
                        poll++) {
                    TaskResult taskResult =
                            getUploadTaskInfo(
                                    base,
                                    token,
                                    taskId
                            );

                    if (taskResult.data != null) {
                        double taskProgress =
                                taskResult.data.optDouble(
                                        "progress",
                                        -1.0
                                );

                        if (taskProgress >= 0.0) {
                            updateProgress(
                                    index,
                                    total,
                                    displayName,
                                    Math.max(
                                            99,
                                            Math.min(
                                                    99,
                                                    (int) taskProgress
                                            )
                                    ),
                                    "服务器后台写入 · " +
                                            String.format(
                                                    Locale.US,
                                                    "%.1f",
                                                    taskProgress
                                            ) +
                                            "%",
                                    "uploading"
                            );
                        }

                        String state =
                                taskResult.data.optString(
                                        "state",
                                        ""
                                );

                        if ("succeeded".equalsIgnoreCase(state)) {
                            if (fileReady(
                                    base,
                                    token,
                                    target,
                                    size,
                                    overwrite
                            )) {
                                return;
                            }
                        }

                        if ("failed".equalsIgnoreCase(state) ||
                                "canceled".equalsIgnoreCase(state) ||
                                "canceling".equalsIgnoreCase(state) ||
                                "errored".equalsIgnoreCase(state)) {
                            throw new IOException(
                                    "服务器后台任务失败：" +
                                            taskResult.data.optString(
                                                    "error",
                                                    taskResult.data.optString(
                                                            "status",
                                                            state
                                                    )
                                            )
                            );
                        }
                    }

                    Thread.sleep(
                            AS_TASK_POLL_MS
                    );
                }

                throw new IOException(
                        "服务器后台任务处理超时"
                );
            }

            // Older/custom OpenList builds may accept As-Task but omit the
            // task object. Fall back to polling the final object.
            for (int poll = 0;
                    poll < AS_TASK_MAX_POLLS;
                    poll++) {
                if (fileReady(
                        base,
                        token,
                        target,
                        size,
                        overwrite
                )) {
                    return;
                }

                Thread.sleep(
                        AS_TASK_POLL_MS
                );
            }

            throw new IOException(
                    "服务器后台任务处理超时"
            );
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private TaskResult getUploadTaskInfo(
            String base,
            String token,
            String taskId
    ) {
        try {
            HttpResult result =
                    requestJson(
                            "POST",
                            base +
                                    "/api/task/upload/info?tid=" +
                                    Uri.encode(taskId),
                            token,
                            "{}"
                    );

            if (result.httpCode < 200 ||
                    result.httpCode >= 300 ||
                    result.body.isEmpty()) {
                return new TaskResult(
                        result.httpCode,
                        null
                );
            }

            JSONObject json =
                    new JSONObject(
                            result.body
                    );

            if (json.optInt(
                    "code",
                    result.httpCode
            ) != 200) {
                return new TaskResult(
                        json.optInt(
                                "code",
                                result.httpCode
                        ),
                        null
                );
            }

            return new TaskResult(
                    result.httpCode,
                    json.optJSONObject("data")
            );
        } catch (Exception ignored) {
            return new TaskResult(-1, null);
        }
    }

    private boolean fileReady(
            String base,
            String token,
            String target,
            long expectedSize,
            boolean overwrite
    ) throws Exception {
        String body =
                new JSONObject()
                        .put("path", target)
                        .put("password", "")
                        .toString();

        HttpResult result =
                requestJson(
                        "POST",
                        base + "/api/fs/get",
                        token,
                        body
                );

        if (result.httpCode == 404) {
            return false;
        }

        if (result.httpCode < 200 ||
                result.httpCode >= 300) {
            return false;
        }

        JSONObject json =
                result.body.isEmpty()
                        ? new JSONObject()
                        : new JSONObject(result.body);

        if (json.optInt(
                "code",
                result.httpCode
        ) != 200) {
            return false;
        }

        JSONObject data =
                json.optJSONObject("data");

        if (data == null) {
            return false;
        }

        long actualSize =
                data.optLong(
                        "size",
                        -1L
                );

        // Some storage drivers may not expose size immediately. Existence
        // is enough once As-Task has accepted the whole request; when size is
        // available, require it to match to avoid linking a stale object.
        return actualSize < 0L ||
                expectedSize < 0L ||
                actualSize == expectedSize;
    }

    private void multipartUpload(
            String base,
            String token,
            Uri uri,
            String target,
            boolean overwrite,
            long size,
            long chunkSize,
            int chunkParallel,
            int index,
            int total,
            String displayName
    ) throws Exception {
        // The multipart engine follows the current OpenList frontend protocol:
        // bounded ascending concurrency, flow-control retries, resumable
        // session probing, and an explicit backend-completion phase.
        // OpenList's reference frontend uses a small bounded in-flight
        // window. Keep the setting configurable, while the uploader itself
        // clamps it to a safe range.
        MultipartUploader uploader =
                new MultipartUploader(this);

        final long initialChunkSize = chunkSize;

        uploader.upload(
                base,
                token,
                uri,
                target,
                overwrite,
                size,
                initialChunkSize,
                chunkParallel,
                (uploadedBytes, progress, detail) ->
                        updateProgress(
                                index,
                                total,
                                displayName,
                                progress,
                                detail,
                                "uploading"
                        )
        );
    }

    private JSONObject multipartInit(
            String base,
            String token,
            String target,
            boolean overwrite,
            long size,
            long chunkSize
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
                    Long.toString(chunkSize)
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

    private interface ChunkProgressListener {
        void onDelta(long delta);
    }

    private void multipartChunk(
            String base,
            String token,
            String uploadId,
            int chunkIndex,
            byte[] buffer,
            int length,
            ChunkProgressListener progressListener
    ) throws Exception {
        HttpURLConnection conn = null;
        long sentThisAttempt = 0;

        try {
            URL url =
                    new URL(base + "/api/fs/multipart/chunk");
            conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("PUT");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(0);
            conn.setDoOutput(true);

            conn.setRequestProperty(
                    "Authorization",
                    token
            );
            conn.setRequestProperty(
                    "X-Upload-Id",
                    uploadId
            );
            conn.setRequestProperty(
                    "X-Chunk-Index",
                    Integer.toString(chunkIndex)
            );
            conn.setRequestProperty(
                    "Content-Type",
                    "application/octet-stream"
            );
            conn.setFixedLengthStreamingMode(length);

            try (OutputStream out = conn.getOutputStream()) {
                int offset = 0;

                while (offset < length) {
                    int writeLength = Math.min(
                            PROGRESS_WRITE_SIZE,
                            length - offset
                    );

                    out.write(
                            buffer,
                            offset,
                            writeLength
                    );

                    offset += writeLength;
                    sentThisAttempt += writeLength;

                    if (progressListener != null) {
                        progressListener.onDelta(writeLength);
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
                json = body.isEmpty()
                        ? new JSONObject()
                        : new JSONObject(body);
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

            int code =
                    json.optInt(
                            "code",
                            httpCode
                    );

            if (code != 200) {
                throw new IOException(
                        "OpenList 分片 " +
                                (chunkIndex + 1) +
                                " 失败 " +
                                code +
                                "：" +
                                json.optString(
                                        "message",
                                        body
                                )
                );
            }
        } catch (Exception e) {
            if (sentThisAttempt > 0 &&
                    progressListener != null) {
                progressListener.onDelta(-sentThisAttempt);
            }

            throw e;
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

    private synchronized void saveLastLink(String url, String name) {
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .edit()
                .putString(MainActivity.KEY_LAST_URL, url)
                .putString(MainActivity.KEY_LAST_NAME, name)
                .apply();
    }

    private synchronized void saveHistory(String url, String name, String status, String error) {
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

    private void updateProgress(
            int index,
            int total,
            String name,
            int fileProgress
    ) {
        updateProgress(
                index,
                total,
                name,
                fileProgress,
                "上传中",
                "uploading"
        );
    }

    private void updateProgress(
            int index,
            int total,
            String name,
            int fileProgress,
            String detail,
            String status
    ) {
        BatchProgress batchProgress =
                activeBatchProgress;

        if (batchProgress == null ||
                index < 0 ||
                index >= batchProgress.total) {
            return;
        }

        batchProgress.names.set(index, name);
        batchProgress.progress.set(
                index,
                Math.max(
                        0,
                        Math.min(100, fileProgress)
                )
        );
        batchProgress.status.set(
                index,
                status
        );
        batchProgress.detail.set(
                index,
                detail == null ? "" : detail
        );
    }


    private void notifyUploadProgress(
            JSONArray files,
            int total
    ) {
        long totalBytes = 0;
        long completedBytes = 0;
        int completedCount = 0;
        StringBuilder active = new StringBuilder();

        for (int i = 0; i < files.length(); i++) {
            JSONObject item = files.optJSONObject(i);
            if (item == null) continue;

            long size = Math.max(
                    0L,
                    item.optLong("size", 0)
            );

            int progress = Math.max(
                    0,
                    Math.min(
                            100,
                            item.optInt("progress", 0)
                    )
            );

            String status =
                    item.optString("status", "pending");

            if (size > 0) {
                totalBytes += size;
                completedBytes +=
                        size * progress / 100L;
            }

            if ("completed".equals(status)) {
                completedCount++;
            } else if ("uploading".equals(status)) {
                if (active.length() > 0) {
                    active.append(" · ");
                }

                active.append(
                        item.optString(
                                "name",
                                "upload.bin"
                        )
                ).append(" ").append(progress).append("%");
            }
        }

        int overall = totalBytes <= 0
                ? 0
                : (int) Math.min(
                        100L,
                        completedBytes * 100L / totalBytes
                );

        String summary =
                "总进度 " +
                        overall +
                        "% · " +
                        completedCount +
                        "/" +
                        total +
                        " 完成";

        if (active.length() > 0) {
            summary += " · " + active;
        }

        NotificationManager nm =
                (NotificationManager) getSystemService(
                        NOTIFICATION_SERVICE
                );

        boolean finished =
                completedCount == total && total > 0;

        nm.notify(
                NOTIFICATION_ID,
                buildNotification(
                        finished
                                ? "OpenList 快传完成"
                                : "OpenList 快传",
                        summary,
                        overall,
                        !finished,
                        getLastUrl()
                )
        );
    }

    private void postBatchFinished(
            BatchProgress batchProgress,
            int total,
            boolean anyFailed
    ) {
        JSONArray files = buildProgressJson(batchProgress);

        try {
            int completed = 0;
            int failed = 0;

            for (int i = 0; i < files.length(); i++) {
                JSONObject item = files.optJSONObject(i);
                if (item == null) continue;

                String status =
                        item.optString("status", "");

                if ("completed".equals(status)) {
                    completed++;
                } else if ("failed".equals(status)) {
                    failed++;
                }
            }

            String title;
            String text;

            if (failed == 0 && completed == total) {
                title = "OpenList 快传完成";
                text = "全部 " + total + " 个文件上传成功";
            } else {
                title = "OpenList 快传结束";
                text = completed + " 个成功";

                if (failed > 0) {
                    text += " · " + failed + " 个失败";
                }
            }

            NotificationManager nm =
                    (NotificationManager) getSystemService(
                            NOTIFICATION_SERVICE
                    );

            nm.notify(
                    NOTIFICATION_ID,
                    buildNotification(
                            title,
                            text,
                            completed == total ? 100 : 0,
                            false,
                            getLastUrl()
                    )
            );
        } catch (Exception ignored) {
        }

        detachForeground();
    }

    private Notification buildNotification(
            String title,
            String text,
            int progress,
            boolean ongoing,
            String link
    ) {
        Intent openIntent =
                new Intent(this, MainActivity.class);
        openIntent.addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP |
                        Intent.FLAG_ACTIVITY_SINGLE_TOP |
                        Intent.FLAG_ACTIVITY_NEW_TASK
        );
        PendingIntent openPending =
                PendingIntent.getActivity(
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

        String compact = body
                .replace("\r", "")
                .replace("\n", " ")
                .replaceAll("\\s+", " ")
                .trim();

        if (compact.startsWith("<!doctype") ||
                compact.startsWith("<html") ||
                (compact.contains("generator") && compact.contains("OpenList"))) {
            return "服务器返回 OpenList 前端页面，当前服务端未提供 Multipart API；请升级 OpenList 至 v4.2.5 或更高版本";
        }

        try {
            return new JSONObject(body)
                    .optString("message", body);
        } catch (Exception ignored) {
            return compact.length() > 300
                    ? compact.substring(0, 300)
                    : compact;
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

    private static final class TaskResult {
        final int httpCode;
        final JSONObject data;

        TaskResult(int httpCode, JSONObject data) {
            this.httpCode = httpCode;
            this.data = data;
        }
    }

    private static final class BatchProgress {
        final int total;
        final long[] sizes;
        final AtomicLongArray transferred;
        final AtomicIntegerArray progress;
        final AtomicReferenceArray<String> names;
        final AtomicReferenceArray<String> status;
        final AtomicReferenceArray<String> detail;

        BatchProgress(int total) {
            this.total = total;
            sizes = new long[total];
            transferred = new AtomicLongArray(total);
            progress = new AtomicIntegerArray(total);
            names = new AtomicReferenceArray<>(total);
            status = new AtomicReferenceArray<>(total);
            detail = new AtomicReferenceArray<>(total);

            for (int i = 0; i < total; i++) {
                names.set(i, "upload.bin");
                status.set(i, "pending");
                detail.set(i, "等待上传");
            }
        }
    }

    private static final class ChunkUploadResult {
        final int index;
        final int length;

        ChunkUploadResult(int index, int length) {
            this.index = index;
            this.length = length;
        }
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