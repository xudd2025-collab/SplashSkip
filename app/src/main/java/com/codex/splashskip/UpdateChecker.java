package com.codex.splashskip;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

final class UpdateChecker {
    static final class Result {
        final String message, version, notes, releaseUrl, apkUrl;
        final boolean newer;
        Result(String message, String version, String notes, String releaseUrl, String apkUrl, boolean newer) {
            this.message = message; this.version = version; this.notes = notes; this.releaseUrl = releaseUrl; this.apkUrl = apkUrl; this.newer = newer;
        }
    }
    interface Callback { void complete(Result result); }
    private final SharedPreferences prefs;
    private static final String OFFICIAL_REPOSITORY = "xudd2025-collab/SplashSkip";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Handler handler = new Handler(Looper.getMainLooper());
    UpdateChecker(Context context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        // Ignore and remove v0.4.0's editable source when upgrading.
        if (prefs.contains("update_repository")) prefs.edit().remove("update_repository")
                .remove("update_status").remove("update_checked_at").apply();
    }
    String repository() { return OFFICIAL_REPOSITORY; }
    boolean busy() { return busy.get(); }
    void check(String installed, Callback callback) {
        final String repo = repository();
        if (!busy.compareAndSet(false, true)) return;
        worker.execute(() -> {
            Result result;
            try { result = fetch(repo, installed); }
            catch (Exception error) {
                String message = error instanceof IllegalArgumentException ? error.getMessage() : "检查失败，请确认网络连接后重试";
                result = new Result(message, "", "", "", "", false);
            }
            prefs.edit().putString("update_status", result.message).putLong("update_checked_at", System.currentTimeMillis()).apply();
            final Result delivered = result;
            handler.post(() -> { busy.set(false); callback.complete(delivered); });
        });
    }
    private Result fetch(String repo, String installed) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)new URL("https://api.github.com/repos/" + UpdatePolicy.repository(repo) + "/releases/latest").openConnection();
        connection.setConnectTimeout(6000); connection.setReadTimeout(8000); connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        connection.setRequestProperty("User-Agent", "SplashSkip/" + installed);
        try {
            int code = connection.getResponseCode();
            if (code == 404) return new Result("仓库尚无公开的正式 Release，或仓库不可访问", "", "", "", "", false);
            if (code == 403 || code == 429) return new Result("GitHub 限制了检查频率，请稍后重试", "", "", "", "", false);
            if (code != 200) return new Result("更新服务器返回 HTTP " + code + "，请稍后重试", "", "", "", "", false);
            JSONObject release;
            try (InputStream input = connection.getInputStream()) { release = new JSONObject(read(input, 512 * 1024)); }
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) throw new IllegalArgumentException("更新源未提供正式稳定版本");
            String tag = release.getString("tag_name"), link = UpdatePolicy.releaseUrl(repo, release.optString("html_url"), false);
            if (link.isEmpty()) throw new IllegalArgumentException("发布页面地址与配置的仓库不一致");
            boolean newer = UpdatePolicy.newer(tag, installed);
            String apk = "";
            JSONArray assets = release.optJSONArray("assets");
            if (assets != null) for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (asset.optString("name").matches("(?i)^SplashSkip.*\\.apk$")) {
                    apk = UpdatePolicy.releaseUrl(repo, asset.optString("browser_download_url"), true);
                    if (!apk.isEmpty()) break;
                }
            }
            String notes = release.optString("body", "暂无版本说明"); if (notes.length() > 2400) notes = notes.substring(0, 2400) + "…";
            return new Result(newer ? "发现新版本 " + tag : "已是最新版本 v" + installed, tag, notes, link, apk, newer);
        } finally { connection.disconnect(); }
    }
    private static String read(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] bytes = new byte[4096]; int count;
        while ((count = input.read(bytes)) != -1) { if (out.size() + count > limit) throw new IllegalArgumentException("更新响应过大"); out.write(bytes, 0, count); }
        return out.toString("UTF-8");
    }
    void close() { worker.shutdown(); }
}
