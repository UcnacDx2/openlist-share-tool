package com.example.openlistshare;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

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
    public static final String PREFS = "openlist_config";
    public static final String KEY_BASE = "base_url";
    public static final String KEY_TOKEN = "token";
    public static final String KEY_DIR = "upload_dir";
    public static final String KEY_OVERWRITE = "overwrite";
    public static final String KEY_LAST_URL = "last_url";
    public static final String KEY_LAST_NAME = "last_name";
    public static final String EXTRA_URIS = "uris";

    private static final int REQUEST_POST_NOTIFICATIONS = 1001;

    private EditText baseUrlInput;
    private EditText tokenInput;
    private EditText dirInput;
    private CheckBox overwriteBox;
    private TextView incomingText;
    private TextView statusText;
    private TextView lastLinkText;
    private Button uploadButton;
    private Button testButton;
    private Button copyButton;
    private Button shareButton;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<Uri> pendingUris = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        loadConfig();
        requestNotificationPermission();
        handleIntent(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLastLink();
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
        subtitle.setText("系统分享文件 → OpenList 上传 → OpenList /d 直链");
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
        overwriteBox.setText("同名文件直接覆盖（关闭时自动追加时间戳）");
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
        incomingText.setPadding(0, dp(18), 0, dp(10));
        root.addView(incomingText, matchWrap());

        statusText = new TextView(this);
        statusText.setText("就绪");
        statusText.setTextSize(14);
        statusText.setPadding(0, dp(4), 0, dp(14));
        root.addView(statusText, matchWrap());

        root.addView(label("最近一次直链"));
        lastLinkText = new TextView(this);
        lastLinkText.setText("暂无");
        lastLinkText.setTextIsSelectable(true);
        lastLinkText.setTextSize(14);
        lastLinkText.setPadding(0, 0, 0, dp(8));
        root.addView(lastLinkText, matchWrap());

        copyButton = new Button(this);
        copyButton.setText("复制直链");
        copyButton.setEnabled(false);
        root.addView(copyButton, matchWrap());

        shareButton = new Button(this);
        shareButton.setText("分享直链");
        shareButton.setEnabled(false);
        root.addView(shareButton, matchWrap());

        testButton.setOnClickListener(v -> testConnection());
        uploadButton.setOnClickListener(v -> startUploadService(false));
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

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
        }
    }

    private void loadConfig() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        baseUrlInput.setText(p.getString(KEY_BASE, ""));
        tokenInput.setText(p.getString(KEY_TOKEN, ""));
        dirInput.setText(p.getString(KEY_DIR, "/uploads"));
        overwriteBox.setChecked(p.getBoolean(KEY_OVERWRITE, false));
        refreshLastLink();
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

        if (isConfigured()) {
            statusText.setText("已接收文件，正在转交后台上传任务");
            startUploadService(true);
        } else {
            statusText.setText("已接收文件，请先填写 OpenList 地址和 Token");
        }
    }

    private List<Uri> extractUris(Intent intent) {
        List<Uri> uris = new ArrayList<>();

        Uri data = intent.getData();
        if (data != null &&
                (Intent.ACTION_VIEW.equals(intent.getAction()) || Intent.ACTION_SEND.equals(intent.getAction()))) {
            uris.add(data);
        }

        try {
            if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
                ArrayList<Uri> multiple;
                if (Build.VERSION.SDK_INT >= 33) {
                    multiple = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class);
                } else {
                    multiple = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                }
                if (multiple != null) {
                    for (Uri uri : multiple) {
                        if (uri != null && !uris.contains(uri)) uris.add(uri);
                    }
                }
            } else {
                Uri extra;
                if (Build.VERSION.SDK_INT >= 33) {
                    extra = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
                } else {
                    extra = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                }
                if (extra != null && !uris.contains(extra)) uris.add(extra);
            }
        } catch (Exception ignored) {
        }

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
        return !baseUrlInput.getText().toString().trim().isEmpty()
                && !tokenInput.getText().toString().trim().isEmpty();
    }

    private void startUploadService(boolean fromShare) {
        if (pendingUris.isEmpty()) {
            statusText.setText("没有待上传文件");
            return;
        }

        if (!isConfigured()) {
            statusText.setText("请先填写 OpenList 地址和 Token");
            return;
        }

        saveConfig();

        Intent service = new Intent(this, UploadService.class);
        service.putParcelableArrayListExtra(EXTRA_URIS, new ArrayList<>(pendingUris));

        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(service);
        } else {
            startService(service);
        }

        statusText.setText("后台上传已启动，请在通知栏查看进度");
        if (fromShare) {
            finish();
        }
    }

    private void testConnection() {
        saveConfig();
        String base = normalizedBaseUrl();
        String token = tokenInput.getText().toString().trim();

        if (base.isEmpty() || token.isEmpty()) {
            statusText.setText("请先填写 OpenList 地址和 Token");
            return;
        }

        testButton.setEnabled(false);
        statusText.setText("测试 OpenList API...");

        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("path", "/");
                body.put("password", "");
                body.put("page", 1);
                body.put("per_page", 1);
                body.put("refresh", false);

                HttpResult result = requestJson("POST", base + "/api/fs/list", token, body.toString());
                JSONObject json = result.body.isEmpty() ? new JSONObject() : new JSONObject(result.body);
                int code = json.optInt("code", result.httpCode);
                String msg = json.optString("message", "success");

                runOnUiThread(() -> statusText.setText(
                        result.httpCode >= 200 && result.httpCode < 300 && code == 200
                                ? "连接成功：" + msg
                                : "连接失败：HTTP " + result.httpCode + " " + msg
                ));
            } catch (Exception e) {
                runOnUiThread(() -> statusText.setText("连接失败：" + friendlyError(e)));
            } finally {
                runOnUiThread(() -> testButton.setEnabled(true));
            }
        });
    }

    private void refreshLastLink() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String url = p.getString(KEY_LAST_URL, "");
        String name = p.getString(KEY_LAST_NAME, "");

        if (url.isEmpty()) {
            lastLinkText.setText("暂无");
            copyButton.setEnabled(false);
            shareButton.setEnabled(false);
            return;
        }

        lastLinkText.setText(name.isEmpty() ? url : name + "\n" + url);
        copyButton.setEnabled(true);
        shareButton.setEnabled(true);
    }

    private String getLastUrl() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST_URL, "");
    }

    private void copyLastUrl() {
        String url = getLastUrl();
        if (url.isEmpty()) {
            Toast.makeText(this, "暂无直链", Toast.LENGTH_SHORT).show();
            return;
        }

        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("OpenList 直链", url));
            Toast.makeText(this, "直链已复制", Toast.LENGTH_SHORT).show();
        }
    }

    private void shareLastUrl() {
        String url = getLastUrl();
        if (url.isEmpty()) return;

        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, url);
        startActivity(Intent.createChooser(intent, "分享 OpenList 直链"));
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

    private long sizeOf(Uri uri) {
        CursorHolder holder = new CursorHolder();
        try {
            holder.cursor = getContentResolver().query(
                    uri,
                    new String[]{OpenableColumns.SIZE},
                    null,
                    null,
                    null
            );
            if (holder.cursor != null && holder.cursor.moveToFirst()) {
                return holder.cursor.isNull(0) ? -1 : holder.cursor.getLong(0);
            }
        } catch (Exception ignored) {
        } finally {
            holder.close();
        }
        return -1;
    }

    private String displayName(Uri uri) {
        CursorHolder holder = new CursorHolder();
        try {
            holder.cursor = getContentResolver().query(
                    uri,
                    new String[]{OpenableColumns.DISPLAY_NAME},
                    null,
                    null,
                    null
            );
            if (holder.cursor != null && holder.cursor.moveToFirst()) {
                String name = holder.cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) return name;
            }
        } catch (Exception ignored) {
        } finally {
            holder.close();
        }

        String path = uri.getPath();
        if (path == null || path.isEmpty()) return "upload.bin";
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
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

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private String friendlyError(Exception e) {
        String msg = e.getMessage();
        return msg == null || msg.isEmpty() ? e.getClass().getSimpleName() : msg;
    }

    private static final class HttpResult {
        final int httpCode;
        final String body;

        HttpResult(int httpCode, String body) {
            this.httpCode = httpCode;
            this.body = body;
        }
    }

    private static final class CursorHolder {
        android.database.Cursor cursor;

        void close() {
            if (cursor != null) cursor.close();
        }
    }
}
