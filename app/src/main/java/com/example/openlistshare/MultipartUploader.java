package com.example.openlistshare;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

public final class MultipartUploader {
    public interface Listener {
        void onProgress(
                long uploadedBytes,
                int progress,
                String detail
        );
    }

    private static final int DEFAULT_INFLIGHT = 3;
    private static final int MAX_FLOW_RETRIES = 400;
    private static final int BUFFER_SIZE = 256 * 1024;
    private static final long RETRY_BASE_MS = 800L;
    private static final long RETRY_MAX_MS = 4800L;

    private final Context context;
    private final ContentResolver resolver;
    private final OkHttpClient client;

    public MultipartUploader(Context context) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();

        Dispatcher dispatcher = new Dispatcher();
        dispatcher.setMaxRequests(16);
        dispatcher.setMaxRequestsPerHost(16);

        this.client = new OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build();
    }

    public void upload(
            String base,
            String token,
            Uri uri,
            String target,
            boolean overwrite,
            long size,
            long requestedChunkSize,
            int requestedInflight,
            Listener listener
    ) throws Exception {
        if (size <= 0) {
            throw new IOException("分片上传要求文件大小大于 0");
        }

        JSONObject session = multipartInit(
                base,
                token,
                target,
                overwrite,
                size,
                requestedChunkSize
        );

        if (session.optBoolean(
                "_multipart_unavailable",
                false
        )) {
            throw new IOException(
                    "OpenList 当前存储驱动不支持 multipart"
            );
        }

        String uploadId = session.optString("upload_id", "");
        if (uploadId.isEmpty()) {
            throw new IOException(
                    "OpenList 分片初始化未返回 upload_id"
            );
        }

        String state = session.optString("state", "");
        if ("completed".equals(state)) {
            listener.onProgress(size, 100, "服务器已完成上传");
            return;
        }

        long chunkSize =
                session.optLong("chunk_size", requestedChunkSize);

        if (chunkSize <= 0) {
            throw new IOException("OpenList 返回了无效的 chunk_size");
        }

        int totalChunks = session.optInt("total_chunks", 0);
        if (totalChunks <= 0) {
            totalChunks =
                    (int) ((size + chunkSize - 1) / chunkSize);
        }

        Set<Integer> received = parseReceived(session);
        List<Integer> missing = new ArrayList<>();

        for (int i = 0; i < totalChunks; i++) {
            if (!received.contains(i)) {
                missing.add(i);
            }
        }

        if (missing.isEmpty()) {
            completeAndWait(
                    base,
                    token,
                    uploadId,
                    size,
                    listener
            );
            return;
        }

        final int inflight = Math.max(
                1,
                Math.min(8, requestedInflight > 0
                        ? requestedInflight
                        : DEFAULT_INFLIGHT)
        );

        ChunkSource source = ChunkSource.open(
                context,
                resolver,
                uri,
                size
        );

        try {
            AtomicInteger next = new AtomicInteger(0);
            AtomicLong ackedBytes = new AtomicLong(
                    Math.max(
                            0L,
                            Math.min(
                                    size,
                                    session.optLong(
                                            "received_bytes",
                                            0L
                                    )
                            )
                    )
            );
            AtomicLong inFlightBytes = new AtomicLong(0L);
            AtomicLong peakBytes = new AtomicLong(
                    ackedBytes.get()
            );
            AtomicLong lastReportAt = new AtomicLong(0L);

            ConcurrentMap<Integer, AtomicLong> attemptLoaded =
                    new ConcurrentHashMap<>();

            final int workerCount =
                    Math.min(inflight, missing.size());

            ExecutorService workers =
                    Executors.newFixedThreadPool(workerCount);

            List<Future<?>> futures = new ArrayList<>();

            try {
                for (int worker = 0;
                        worker < workerCount;
                        worker++) {
                    futures.add(
                            workers.submit(() -> {
                                while (true) {
                                    int pos =
                                            next.getAndIncrement();

                                    if (pos >= missing.size()) {
                                        return;
                                    }

                                    int chunkIndex =
                                            missing.get(pos);

                                    sendChunkWithRetry(
                                            base,
                                            token,
                                            uploadId,
                                            uri,
                                            source,
                                            chunkIndex,
                                            chunkSize,
                                            size,
                                            totalChunks,
                                            ackedBytes,
                                            inFlightBytes,
                                            peakBytes,
                                            attemptLoaded,
                                            lastReportAt,
                                            listener,
                                            inflight
                                    );
                                }
                            })
                    );
                }

                for (Future<?> future : futures) {
                    future.get();
                }
            } finally {
                workers.shutdownNow();
            }

            listener.onProgress(
                    Math.min(size, peakBytes.get()),
                    99,
                    "分片已全部上传，服务器正在处理"
            );

            completeAndWait(
                    base,
                    token,
                    uploadId,
                    size,
                    listener
            );
        } finally {
            source.close();
        }
    }

    private void sendChunkWithRetry(
            String base,
            String token,
            String uploadId,
            Uri uri,
            ChunkSource source,
            int chunkIndex,
            long chunkSize,
            long fileSize,
            int totalChunks,
            AtomicLong ackedBytes,
            AtomicLong inFlightBytes,
            AtomicLong peakBytes,
            ConcurrentMap<Integer, AtomicLong> attemptLoaded,
            AtomicLong lastReportAt,
            Listener listener,
            int inflight
    ) throws Exception {
        long chunkLength =
                Math.min(
                        chunkSize,
                        fileSize - chunkIndex * chunkSize
                );

        int retries = 0;

        while (true) {
            AtomicLong loaded =
                    new AtomicLong(0L);

            attemptLoaded.put(chunkIndex, loaded);

            boolean requestFinished = false;
            try {
                Response response = executeChunk(
                        base,
                        token,
                        uploadId,
                        chunkIndex,
                        source,
                        chunkIndex * chunkSize,
                        chunkLength,
                        loaded,
                        inFlightBytes,
                        ackedBytes,
                        peakBytes,
                        lastReportAt,
                        listener
                );

                int httpCode = response.code();
                String body = responseBody(response);

                int apiCode = httpCode;
                JSONObject json = null;

                if (!body.isEmpty()) {
                    try {
                        json = new JSONObject(body);
                        apiCode = json.optInt(
                                "code",
                                httpCode
                        );
                    } catch (Exception ignored) {
                    }
                }

                requestFinished = true;

                if (apiCode == 200 &&
                        httpCode >= 200 &&
                        httpCode < 300) {
                    long sent =
                            loaded.getAndSet(0L);

                    if (sent != 0L) {
                        inFlightBytes.addAndGet(-sent);
                    }

                    long acked =
                            ackedBytes.addAndGet(chunkLength);

                    report(
                            acked,
                            inFlightBytes,
                            fileSize,
                            peakBytes,
                            lastReportAt,
                            listener,
                            "分片 " +
                                    (chunkIndex + 1) +
                                    "/" +
                                    totalChunks +
                                    " · " +
                                    inflight +
                                    "路并行"
                    );

                    return;
                }

                if (apiCode == 429 ||
                        apiCode == 409 ||
                        httpCode == 408 ||
                        httpCode == 429 ||
                        httpCode >= 500) {
                    retries++;

                    if (retries > MAX_FLOW_RETRIES) {
                        throw new IOException(
                                "第 " +
                                        (chunkIndex + 1) +
                                        " 片等待服务器过久"
                        );
                    }

                    sleepBackoff(retries);
                    continue;
                }

                throw new IOException(
                        "第 " +
                                (chunkIndex + 1) +
                                "/" +
                                totalChunks +
                                " 片上传失败：" +
                                messageFrom(
                                        json,
                                        body,
                                        httpCode
                                )
                );
            } catch (IOException networkError) {
                if (requestFinished) {
                    throw networkError;
                }

                JSONObject status =
                        safeStatus(
                                base,
                                token,
                                uploadId
                        );

                if (isCompleted(status)) {
                    long current =
                            Math.max(
                                    fileSize,
                                    ackedBytes.get()
                            );
                    peakBytes.accumulateAndGet(
                            current,
                            Math::max
                    );
                    return;
                }

                if (isFailed(status)) {
                    throw new IOException(
                            status.optString(
                                    "error",
                                    "OpenList 分片上传失败"
                            ),
                            networkError
                    );
                }

                retries++;

                if (retries > MAX_FLOW_RETRIES) {
                    throw new IOException(
                            "第 " +
                                    (chunkIndex + 1) +
                                    " 片网络异常，重试次数耗尽",
                            networkError
                    );
                }

                sleepBackoff(retries);
            } finally {
                long sent =
                        loaded.getAndSet(0L);

                if (sent != 0L) {
                    inFlightBytes.addAndGet(-sent);
                }

                attemptLoaded.remove(chunkIndex);
            }
        }
    }

    private Response executeChunk(
            String base,
            String token,
            String uploadId,
            int chunkIndex,
            ChunkSource source,
            long offset,
            long length,
            AtomicLong loaded,
            AtomicLong inFlightBytes,
            AtomicLong ackedBytes,
            AtomicLong peakBytes,
            AtomicLong lastReportAt,
            Listener listener
    ) throws IOException {
        RequestBody body = new ChunkRequestBody(
                source,
                offset,
                length,
                (delta, totalLoaded) -> {
                    loaded.addAndGet(delta);
                    inFlightBytes.addAndGet(delta);

                    report(
                            ackedBytes.get(),
                            inFlightBytes,
                            source.size,
                            peakBytes,
                            lastReportAt,
                            listener,
                            "上传分片 " +
                                    (chunkIndex + 1)
                    );
                }
        );

        Request request = new Request.Builder()
                .url(base + "/api/fs/multipart/chunk")
                .put(body)
                .header("Authorization", token)
                .header("X-Upload-Id", uploadId)
                .header(
                        "X-Chunk-Index",
                        Integer.toString(chunkIndex)
                )
                .header(
                        "Content-Type",
                        "application/octet-stream"
                )
                .build();

        return client.newCall(request).execute();
    }

    private void completeAndWait(
            String base,
            String token,
            String uploadId,
            long size,
            Listener listener
    ) throws Exception {
        ExecutorService completeExecutor =
                Executors.newSingleThreadExecutor();

        Future<Response> future =
                completeExecutor.submit(
                        () -> multipartComplete(
                                base,
                                token,
                                uploadId
                        )
                );

        try {
            while (!future.isDone()) {
                JSONObject status =
                        safeStatus(
                                base,
                                token,
                                uploadId
                        );

                if (status != null) {
                    String state =
                            status.optString(
                                    "state",
                                    ""
                            );

                    if ("completed".equals(state)) {
                        future.cancel(true);
                        listener.onProgress(
                                size,
                                100,
                                "上传完成"
                        );
                        return;
                    }

                    if (state.startsWith("failed") ||
                            "aborted".equals(state)) {
                        throw new IOException(
                                status.optString(
                                        "error",
                                        "OpenList 后端处理失败"
                                )
                        );
                    }

                    double storage =
                            status.optDouble(
                                    "storage_progress",
                                    -1.0
                            );

                    if (storage >= 0.0) {
                        listener.onProgress(
                                size,
                                99,
                                "服务器正在处理 · 后端 " +
                                        String.format(
                                                java.util.Locale.US,
                                                "%.1f",
                                                storage
                                        ) +
                                        "%"
                        );
                    } else {
                        listener.onProgress(
                                size,
                                99,
                                "服务器正在处理"
                        );
                    }
                }

                Thread.sleep(2000L);
            }

            Response response = future.get();
            String body = responseBody(response);
            int apiCode = response.code();

            JSONObject json = null;
            if (!body.isEmpty()) {
                try {
                    json = new JSONObject(body);
                    apiCode = json.optInt(
                            "code",
                            apiCode
                    );
                } catch (Exception ignored) {
                }
            }

            if (apiCode == 200 &&
                    response.code() >= 200 &&
                    response.code() < 300) {
                listener.onProgress(
                        size,
                        100,
                        "上传完成"
                );
                return;
            }

            throw new IOException(
                    "OpenList 分片完成失败：" +
                            messageFrom(
                                    json,
                                    body,
                                    response.code()
                            )
            );
        } finally {
            completeExecutor.shutdownNow();
        }
    }

    private JSONObject multipartInit(
            String base,
            String token,
            String target,
            boolean overwrite,
            long size,
            long chunkSize
    ) throws Exception {
        Request request = new Request.Builder()
                .url(base + "/api/fs/multipart/init")
                .post(EMPTY_BODY)
                .header("Authorization", token)
                .header(
                        "File-Path",
                        Uri.encode(target, "/")
                )
                .header(
                        "X-File-Size",
                        Long.toString(size)
                )
                .header(
                        "X-Chunk-Size",
                        Long.toString(chunkSize)
                )
                .header(
                        "Overwrite",
                        overwrite ? "true" : "false"
                )
                .header(
                        "Content-Type",
                        "application/octet-stream"
                )
                .build();

        Response response = client.newCall(request).execute();
        String body = responseBody(response);

        if (!response.isSuccessful()) {
            throw new IOException(
                    "OpenList 分片初始化 HTTP " +
                            response.code() +
                            "：" +
                            body
            );
        }

        JSONObject json =
                body.isEmpty()
                        ? new JSONObject()
                        : new JSONObject(body);

        int code =
                json.optInt(
                        "code",
                        response.code()
                );

        if (code != 200) {
            throw new IOException(
                    "OpenList 分片初始化失败 " +
                            code +
                            "：" +
                            json.optString(
                                    "message",
                                    body
                            )
            );
        }

        JSONObject data =
                json.optJSONObject("data");

        // The current OpenList frontend treats data:null as an explicit
        // "multipart unavailable" response and falls back to /fs/put.
        if (data == null) {
            JSONObject empty = new JSONObject();
            empty.put("_multipart_unavailable", true);
            return empty;
        }

        return data;
    }

    private Response multipartComplete(
            String base,
            String token,
            String uploadId
    ) throws Exception {
        Request request = new Request.Builder()
                .url(base + "/api/fs/multipart/complete")
                .post(EMPTY_BODY)
                .header("Authorization", token)
                .header("X-Upload-Id", uploadId)
                .build();

        return client.newCall(request).execute();
    }

    private JSONObject safeStatus(
            String base,
            String token,
            String uploadId
    ) {
        try {
            Request request = new Request.Builder()
                    .url(
                            base +
                                    "/api/fs/multipart/status?upload_id=" +
                                    Uri.encode(uploadId)
                    )
                    .get()
                    .header("Authorization", token)
                    .header("Accept", "application/json")
                    .build();

            Response response =
                    client.newCall(request).execute();

            String body = responseBody(response);

            if (response.code() == 404) {
                return null;
            }

            if (!response.isSuccessful() ||
                    body.isEmpty()) {
                return null;
            }

            JSONObject json = new JSONObject(body);
            if (json.optInt("code", response.code()) != 200) {
                return null;
            }

            return json.optJSONObject("data");
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isCompleted(JSONObject status) {
        return status != null &&
                "completed".equals(
                        status.optString("state", "")
                );
    }

    private boolean isFailed(JSONObject status) {
        if (status == null) return false;
        String state = status.optString("state", "");
        return state.startsWith("failed") ||
                "aborted".equals(state);
    }

    private Set<Integer> parseReceived(JSONObject session) {
        Set<Integer> received = new HashSet<>();
        JSONArray ranges =
                session.optJSONArray("received");

        if (ranges == null) {
            return received;
        }

        for (int i = 0; i < ranges.length(); i++) {
            JSONArray pair =
                    ranges.optJSONArray(i);

            if (pair == null ||
                    pair.length() < 2) {
                continue;
            }

            int lo = pair.optInt(0, -1);
            int hi = pair.optInt(1, -1);

            if (lo < 0 || hi < lo) {
                continue;
            }

            for (int chunk = lo; chunk <= hi; chunk++) {
                received.add(chunk);
            }
        }

        return received;
    }

    private void report(
            long ackedBytes,
            AtomicLong inFlightBytes,
            long fileSize,
            AtomicLong peakBytes,
            AtomicLong lastReportAt,
            Listener listener,
            String detail
    ) {
        long current =
                Math.max(
                        0L,
                        Math.min(
                                fileSize,
                                ackedBytes +
                                        Math.max(
                                                0L,
                                                inFlightBytes.get()
                                        )
                        )
                );

        peakBytes.accumulateAndGet(
                current,
                Math::max
        );

        long now = System.currentTimeMillis();
        long last = lastReportAt.get();

        if (last != 0L &&
                now - last < 200L &&
                current < fileSize) {
            return;
        }

        if (lastReportAt.compareAndSet(
                last,
                now
        )) {
            int progress =
                    (int) Math.min(
                            99L,
                            peakBytes.get() *
                                    100L /
                                    fileSize
                    );

            listener.onProgress(
                    peakBytes.get(),
                    progress,
                    detail
            );
        }
    }

    private void sleepBackoff(int retry) throws InterruptedException {
        long delay =
                Math.min(
                        RETRY_MAX_MS,
                        RETRY_BASE_MS * Math.max(1, retry)
                );

        long jitter =
                (long) (
                        Math.random() *
                                Math.max(1L, delay / 4L)
                );

        Thread.sleep(delay + jitter);
    }

    private String responseBody(Response response)
            throws IOException {
        try (Response ignored = response) {
            if (response.body() == null) {
                return "";
            }

            return response.body().string();
        }
    }

    private String messageFrom(
            JSONObject json,
            String body,
            int httpCode
    ) {
        if (json != null) {
            String message =
                    json.optString(
                            "message",
                            ""
                    );

            if (!message.isEmpty()) {
                return message;
            }
        }

        if (body != null &&
                !body.isEmpty()) {
            return body.length() > 300
                    ? body.substring(0, 300)
                    : body;
        }

        return "HTTP " + httpCode;
    }

    private static final RequestBody EMPTY_BODY =
            new RequestBody() {
                @Override
                public long contentLength() {
                    return 0L;
                }

                @Override
                public void writeTo(
                        BufferedSink sink
                ) {
                }
            };

    private interface ProgressSink {
        void onWrite(long delta, long loaded);
    }

    private static final class ChunkRequestBody
            extends RequestBody {
        private final ChunkSource source;
        private final long offset;
        private final long length;
        private final ProgressSink progress;

        ChunkRequestBody(
                ChunkSource source,
                long offset,
                long length,
                ProgressSink progress
        ) {
            this.source = source;
            this.offset = offset;
            this.length = length;
            this.progress = progress;
        }

        @Override
        public long contentLength() {
            return length;
        }

        @Override
        public void writeTo(BufferedSink sink)
                throws IOException {
            try (InputStream in =
                         source.open(
                                 offset,
                                 length
                         )) {
                byte[] buffer =
                        new byte[BUFFER_SIZE];

                long loaded = 0L;

                while (loaded < length) {
                    int wanted =
                            (int) Math.min(
                                    buffer.length,
                                    length - loaded
                            );

                    int read =
                            in.read(
                                    buffer,
                                    0,
                                    wanted
                            );

                    if (read < 0) {
                        throw new IOException(
                                "分片读取提前结束：期望 " +
                                        length +
                                        " 字节，实际 " +
                                        loaded +
                                        " 字节"
                        );
                    }

                    if (read == 0) {
                        continue;
                    }

                    sink.write(
                            buffer,
                            0,
                            read
                    );

                    loaded += read;

                    if (progress != null) {
                        progress.onWrite(
                                read,
                                loaded
                        );
                    }
                }
            }
        }
    }

    private static final class ChunkSource {
        final Context context;
        final ContentResolver resolver;
        final Uri uri;
        final long size;
        final File spoolFile;

        private final boolean seekable;

        private ChunkSource(
                Context context,
                ContentResolver resolver,
                Uri uri,
                long size,
                boolean seekable,
                File spoolFile
        ) {
            this.context = context;
            this.resolver = resolver;
            this.uri = uri;
            this.size = size;
            this.seekable = seekable;
            this.spoolFile = spoolFile;
        }

        static ChunkSource open(
                Context context,
                ContentResolver resolver,
                Uri uri,
                long size
        ) throws IOException {
            if (isSeekable(resolver, uri, size)) {
                return new ChunkSource(
                        context,
                        resolver,
                        uri,
                        size,
                        true,
                        null
                );
            }

            File dir =
                    new File(
                            context.getCacheDir(),
                            "openlist-upload"
                    );

            if (!dir.exists() &&
                    !dir.mkdirs() &&
                    !dir.isDirectory()) {
                throw new IOException(
                        "无法创建上传缓存目录"
                );
            }

            File spool =
                    File.createTempFile(
                            "upload-",
                            ".bin",
                            dir
                    );

            try (
                    InputStream in =
                            resolver.openInputStream(uri);
                    FileOutputStream out =
                            new FileOutputStream(spool)
            ) {
                if (in == null) {
                    throw new IOException(
                            "无法读取分享文件"
                    );
                }

                byte[] buffer =
                        new byte[BUFFER_SIZE];

                long copied = 0L;
                int read;

                while ((read =
                        in.read(buffer)) != -1) {
                    if (read == 0) continue;

                    out.write(
                            buffer,
                            0,
                            read
                    );

                    copied += read;
                }

                if (copied != size) {
                    throw new IOException(
                            "文件缓存大小不一致：期望 " +
                                    size +
                                    " 字节，实际 " +
                                    copied +
                                    " 字节"
                    );
                }
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                spool.delete();

                if (e instanceof IOException) {
                    throw (IOException) e;
                }

                throw new IOException(
                        "创建上传缓存失败",
                        e
                );
            }

            return new ChunkSource(
                    context,
                    resolver,
                    uri,
                    size,
                    false,
                    spool
            );
        }

        InputStream open(long offset, long length)
                throws IOException {
            if (seekable) {
                ParcelFileDescriptor pfd =
                        resolver.openFileDescriptor(
                                uri,
                                "r"
                        );

                if (pfd == null) {
                    throw new IOException(
                            "无法打开文件描述符"
                    );
                }

                ParcelFileDescriptor.AutoCloseInputStream in =
                        new ParcelFileDescriptor.AutoCloseInputStream(
                                pfd
                        );

                try {
                    FileChannel channel =
                            in.getChannel();

                    channel.position(offset);

                    return new LimitedInputStream(
                            in,
                            length
                    );
                } catch (Exception e) {
                    try {
                        in.close();
                    } catch (Exception ignored) {
                    }

                    throw new IOException(
                            "文件不支持随机读取",
                            e
                    );
                }
            }

            FileInputStream in =
                    new FileInputStream(spoolFile);

            try {
                in.getChannel().position(offset);

                return new LimitedInputStream(
                        in,
                        length
                );
            } catch (Exception e) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }

                throw new IOException(
                        "上传缓存无法随机读取",
                        e
                );
            }
        }

        void close() {
            if (spoolFile != null) {
                //noinspection ResultOfMethodCallIgnored
                spoolFile.delete();
            }
        }

        private static boolean isSeekable(
                ContentResolver resolver,
                Uri uri,
                long size
        ) {
            ParcelFileDescriptor pfd = null;
            ParcelFileDescriptor.AutoCloseInputStream in = null;

            try {
                pfd =
                        resolver.openFileDescriptor(
                                uri,
                                "r"
                        );

                if (pfd == null) return false;

                in =
                        new ParcelFileDescriptor.AutoCloseInputStream(
                                pfd
                        );

                FileChannel channel =
                        in.getChannel();

                long probe =
                        size > 1 ? 1L : 0L;

                channel.position(probe);
                channel.position(0L);

                return true;
            } catch (Exception ignored) {
                return false;
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    private static final class LimitedInputStream
            extends InputStream {
        private final InputStream delegate;
        private long remaining;

        LimitedInputStream(
                InputStream delegate,
                long length
        ) {
            this.delegate = delegate;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;

            int value = delegate.read();

            if (value >= 0) {
                remaining--;
            }

            return value;
        }

        @Override
        public int read(
                byte[] buffer,
                int offset,
                int length
        ) throws IOException {
            if (remaining <= 0) return -1;

            int wanted =
                    (int) Math.min(
                            remaining,
                            length
                    );

            int read =
                    delegate.read(
                            buffer,
                            offset,
                            wanted
                    );

            if (read > 0) {
                remaining -= read;
            }

            return read;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
