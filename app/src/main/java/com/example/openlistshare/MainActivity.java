package com.example.openlistshare;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PREFS = "openlist_config";
    private static final String KEY_BASE = "base_url";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_DIR = "upload_dir";
    private static final String KEY_OVERWRITE = "overwrite";

    private EditText baseUrlInput;
    private EditText tokenInput;
    private EditText dirInput;
    private CheckBox overwriteBox;
    private TextView incomingText;
    private TextView statusText;
    private ProgressBar progressBar;
    private Button uploadButton;
    private Button testButton;
    private Button copyButton;
    private Button shareButton;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<Uri> pendingUris = new ArrayList<>();
    private String lastDirectUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        loadConfig();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("OpenList 快传");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText("从系统分享/打开文件，上传到 OpenList 并获取直链");
        subtitle.setTextSize(14);
        subtitle.setPadding(0, 0, 0, dp(18));
        root.addView(subtitle, matchWrap());

        root.addView(label("OpenList 地址"));
        baseUrlInput = input("https://example.com");
        root.addView(baseUrlInput, matchWrap());

        root.addView(label("Authorization 令牌"));
        tokenInput = input("粘贴 OpenList Token");
        tokenInput.setSingleLine(false);
        tokenInput.setMinLines(2);
        root.addView(tokenInput, matchWrap());

        root.addView(label("上传目录"));
        dirInput = input("/uploads");
        root.addView(dirInput, matchWrap());

        overwriteBox = new CheckBox(this);
        overwriteBox.setText("覆盖同名文件");
        root.addView(overwriteBox, matchWrap());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        testButton = new Button(this);
        testButton.setText("测试连接");
        uploadButton = new Button(this);
        uploadButton.setText("上传当前文件");
        row.addView(testButton, weightWrap(1));
        row.addView(uploadButton, weightWrap(1));
        root.addView(row, matchWrap());

        incomingText = new TextView(this);
        incomingText.setText("当前没有待上传文件");
        incomingText.setTextSize(15);
        incomingText.setPadding(0, dp(18), 0, dp(8));
        root.addView(incomingText, matchWrap());

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        root.addView(progressBar, matchWrap());

        statusText = new TextView(this);
        statusText.setText("就绪");
        statusText.setTextSize(14);
        statusText.setPadding(0, dp(8), 0, dp(12));
        root.addView(statusText, matchWrap());

        copyButton = new Button(this);
        copyButton.setText("复制直链");
        copyButton.setEnabled(false);
        root.addView(copyButton, matchWrap());

        shareButton = new Button(this);
        shareButton.setText("分享直链");
        shareButton.setEnabled(false);
        root.addView(shareButton, matchWrap());

        testButton.setOnClickListener(v -> testConnection());
        uploadButton.setOnClickListener(v -> uploadPendingFiles());
        copyButton.setOnClickListener(v -> copyLastUrl());
        shareButton.setOnClickListener(v -> shareLastUrl());
    }

    private TextView label(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(13);
        v.setPadding(0, dp(10), 0, dp(5));
        return v;
    }

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(15);
        e.setSingleLine(true);
        return e;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private LinearLayout.LayoutParams weightWrap(float weight) {
        return new LinearLayout.LayoutParams(0, -2, weight);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void loadConfig() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        baseUrlInput.setText(p.getString(KEY_BASE, ""));
        tokenInput.setText(p.getString(KEY_TOKEN, ""));
        dirInput.setText(p.getString(KEY_DIR, "/uploads"));
        overwriteBox.setChecked(p.getBoolean(KEY_OVERWRITE, true));
    }

    private void saveConfig() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_BASE, baseUrlInput.getText().toString().trim())
                .putString(KEY_TOKEN, tokenInput.getText().toString().trim())
                .putString(KEY_DIR, normalizeDir(dirInput.getText().toString()))
                .putBoolean(KEY_OVERWRITE, overwriteBox.isChecked())
                .apply();
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        List<Uri> uris = extractUris(intent);
        if (uris.isEmpty()) return;
        pendingUris.clear();
        pendingUris.addAll(uris);
        StringBuilder sb = new StringBuilder("待上传：");
        for (Uri uri : uris) {
            sb.append("\n• ").append(displayName(uri));
        }
        incomingText.setText(sb.toString());
        statusText.setText("已接收系统分享文件，可以直接上传");
        if (uris.size() == 1 && isConfigured()) {
            uploadPendingFiles();
        }
    }

    private List<Uri> extractUris(Intent intent) {
        List<Uri> uris = new ArrayList<>();
        Uri data = intent.getData();
        if (data != null && (Intent.ACTION_VIEW.equals(intent.getAction()) || Intent.ACTION_SEND.equals(intent.getAction()))) {
            uris.add(data);
        }
        Object extra = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (extra instanceof Uri) uris.add((Uri) extra);
        ClipData clipData = intent.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                Uri uri = clipData.getItemAt(i).getUri();
                if (uri != null && !uris.contains(uri)) uris.add(uri);
            }
        }
        return uris;
    }

    private boolean isConfigured() {
        return !baseUrlInput.getText().toString().trim().isEmpty() && !tokenInput.getText().toString().trim().isEmpty();
    }

    private void testConnection() {
        saveConfig();
        final String base = normalizedBaseUrl();
        final String token = tokenInput.getText().toString().trim();
        if (base.isEmpty() || token.isEmpty()) {
            showStatus("请先填写 OpenList 地址和 Token");
            return;
        }
        setBusy(true);
        showStatus("测试 OpenList API...");
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("path", "/");
                body.put("password", "");
                body.put("page", 1);
                body.put("per_page", 1);
                body.put("refresh", false);
                HttpResult result = requestJson("POST", base + "/api/fs/list", token, body.toString());
                if (result.httpCode >= 200 && result.httpCode < 300) {
                    JSONObject json = new JSONObject(result.body);
                    int code = json.optInt("code", result.httpCode);
                    String msg = json.optString("message", "success");
                    post(() -> showStatus(code == 200 ? "连接成功：" + msg : "API 返回 " + code + "：" + msg));
                } else {
                    post(() -> showStatus("连接失败 HTTP " + result.httpCode + "：" + safeMessage(result.body)));
                }
            } catch (Exception e) {
                post(() -> showStatus("连接失败：" + friendlyError(e)));
            } finally {
                post(() -> setBusy(false));
            }
        });
    }

    private void uploadPendingFiles() {
        if (pendingUris.isEmpty()) {
            showStatus("没有待上传文件，请从系统分享文件到 OpenList 快传");
            return;
        }
        if (!isConfigured()) {
            showStatus("请先填写 OpenList 地址和 Token");
            return;
        }
        saveConfig();
        final String base = normalizedBaseUrl();
        final String token = tokenInput.getText().toString().trim();
        final String dir = normalizeDir(dirInput.getText().toString());
        final boolean overwrite = overwriteBox.isChecked();
        final List<Uri> files = new ArrayList<>(pendingUris);
        setBusy(true);
        copyButton.setEnabled(false);
        shareButton.setEnabled(false);
        progressBar.setProgress(0);

        executor.execute(() -> {
            int done = 0;
            try {
                for (Uri uri : files) {
                    String name = displayName(uri);
                    long size = sizeOf(uri);
                    String target = joinPath(dir, name);
                    post(() -> showStatus("上传中：" + name));
                    uploadOne(base, token, uri, target, overwrite, size);
                    String link = getDirectLink(base, token, target);
                    lastDirectUrl = link;
                    done++;
                    final int completed = done;
                    post(() -> {
                        progressBar.setProgress(100);
                        incomingText.setText("已上传：" + completed + "/" + files.size() + "\n• " + name);
                        copyToClipboardSilently(link);
                        statusText.setText("完成（直链已自动复制）：" + link);
                        copyButton.setEnabled(true);
                        shareButton.setEnabled(true);
                    });
                }
            } catch (Exception e) {
                post(() -> showStatus("上传失败：" + friendlyError(e)));
            } finally {
                post(() -> setBusy(false));
            }
        });
    }

    private void uploadOne(String base, String token, Uri uri, String target, boolean overwrite, long size) throws Exception {
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
            if (size >= 0) conn.setFixedLengthStreamingMode(size);
            else conn.setChunkedStreamingMode(64 * 1024);

            try (InputStream raw = getContentResolver().openInputStream(uri)) {
                if (raw == null) throw new IOException("无法读取文件");
                try (InputStream in = new BufferedInputStream(raw, 128 * 1024);
                     OutputStream out = conn.getOutputStream()) {
                    byte[] buffer = new byte[128 * 1024];
                    long sent = 0;
                    int read;
                    long lastUpdate = 0;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                        sent += read;
                        long now = System.currentTimeMillis();
                        if (size > 0 && (now - lastUpdate >= 120 || sent == size)) {
                            int p = (int) Math.min(100, (sent * 100L) / size);
                            lastUpdate = now;
                            post(() -> progressBar.setProgress(p));
                        }
                    }
                }
            }

            int code = conn.getResponseCode();
            String body = readBody(conn);
            if (code < 200 || code >= 300) {
                throw new IOException("OpenList 上传 HTTP " + code + "：" + safeMessage(body));
            }
            JSONObject json = new JSONObject(body);
            int apiCode = json.optInt("code", code);
            if (apiCode != 200) {
                throw new IOException("OpenList 上传失败 " + apiCode + "：" + json.optString("message", body));
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String getDirectLink(String base, String token, String target) throws Exception {
        JSONObject req = new JSONObject();
        req.put("path", target);
        req.put("password", "");
        HttpResult result = requestJson("POST", base + "/api/fs/get", token, req.toString());
        if (result.httpCode < 200 || result.httpCode >= 300) {
            throw new IOException("获取直链 HTTP " + result.httpCode + "：" + safeMessage(result.body));
        }
        JSONObject json = new JSONObject(result.body);
        int code = json.optInt("code", result.httpCode);
        if (code != 200) throw new IOException("获取直链失败 " + code + "：" + json.optString("message", result.body));
        JSONObject data = json.optJSONObject("data");
        if (data == null) throw new IOException("OpenList 未返回文件信息");
        String rawUrl = data.optString("raw_url", "");
        String url = data.optString("url", "");
        String link = !rawUrl.isEmpty() ? rawUrl : url;
        if (link.isEmpty()) {
            link = base + "/d" + (target.startsWith("/") ? target : "/" + target);
        }
        return normalizeDirectLink(link, base);
    }

    private String normalizeDirectLink(String link, String base) {
        if (link.startsWith("/")) link = base + link;
        if (link.contains("/p/")) link = link.replaceFirst("/p/", "/d/");
        return link;
    }

    private HttpResult requestJson(String method, String urlText, String token, String jsonBody) throws Exception {
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
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
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
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private long sizeOf(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst()) return c.isNull(0) ? -1 : c.getLong(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return -1;
    }

    private String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (name != null && !name.trim().isEmpty()) return name;
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        String path = uri.getPath();
        if (path == null || path.isEmpty()) return "upload.bin";
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    private void copyLastUrl() {
        if (lastDirectUrl.isEmpty()) return;
        copyToClipboardSilently(lastDirectUrl);
        Toast.makeText(this, "直链已复制", Toast.LENGTH_SHORT).show();
    }

    private void copyToClipboardSilently(String url) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("OpenList 直链", url));
    }

    private void shareLastUrl() {
        if (lastDirectUrl.isEmpty()) return;
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, lastDirectUrl);
        startActivity(Intent.createChooser(intent, "分享 OpenList 直链"));
    }

    private void setBusy(boolean busy) {
        testButton.setEnabled(!busy);
        uploadButton.setEnabled(!busy && !pendingUris.isEmpty());
        baseUrlInput.setEnabled(!busy);
        tokenInput.setEnabled(!busy);
        dirInput.setEnabled(!busy);
        overwriteBox.setEnabled(!busy);
        if (!busy && progressBar.getProgress() < 100) progressBar.setProgress(0);
    }

    private void showStatus(String text) {
        runOnUiThread(() -> statusText.setText(text));
    }

    private void post(Runnable r) {
        runOnUiThread(r);
    }

    private String normalizedBaseUrl() {
        String s = baseUrlInput.getText().toString().trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private String normalizeDir(String value) {
        String s = value == null ? "" : value.trim();
        if (s.isEmpty()) return "/";
        if (!s.startsWith("/")) s = "/" + s;
        while (s.endsWith("/") && s.length() > 1) s = s.substring(0, s.length() - 1);
        return s;
    }

    private String joinPath(String dir, String name) {
        String safe = name.replace("/", "_").replace("\\", "_");
        return "/".equals(dir) ? "/" + safe : dir + "/" + safe;
    }

    private String friendlyError(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isEmpty()) msg = e.getClass().getSimpleName();
        return msg;
    }

    private String safeMessage(String body) {
        if (body == null || body.isEmpty()) return "无返回内容";
        try {
            JSONObject json = new JSONObject(body);
            return json.optString("message", body);
        } catch (Exception ignored) {
            return body.length() > 300 ? body.substring(0, 300) : body;
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
