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
        final String sha256;
        final long size;
        final String apiUrl;
        Result(String message, String version, String notes, String releaseUrl, String apkUrl, boolean newer) {
            this(message,version,notes,releaseUrl,apkUrl,newer,"",0);
        }
        Result(String message,String version,String notes,String releaseUrl,String apkUrl,boolean newer,String sha256,long size) {
            this(message,version,notes,releaseUrl,apkUrl,newer,sha256,size,"");
        }
        Result(String message,String version,String notes,String releaseUrl,String apkUrl,boolean newer,String sha256,long size,String apiUrl) {
            this.message=message;this.version=version;this.notes=notes;this.releaseUrl=releaseUrl;this.apkUrl=apkUrl;this.newer=newer;this.sha256=sha256;this.size=size;this.apiUrl=apiUrl;
        }
        String[] sources(boolean accelerate){return UpdateSources.packages(apiUrl,apkUrl,accelerate);}
    }
    interface Callback { void complete(Result result); }
    private final SharedPreferences prefs;
    private static final String OFFICIAL_REPOSITORY = "xudd2025-collab/SplashSkip";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile boolean closed;
    private final Handler handler = new Handler(Looper.getMainLooper());
    UpdateChecker(Context context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        // Ignore and remove v0.4.0's editable source when upgrading.
        if (prefs.contains("update_repository")) prefs.edit().remove("update_repository")
                .remove("update_status").remove("update_checked_at").apply();
    }
    String repository() { return OFFICIAL_REPOSITORY; }
    boolean busy() { return busy.get(); }
    Result cached(String installed) {
        try {
            JSONObject cache=new JSONObject(prefs.getString("update_cache","{}"));
            String tag=cache.getString("version"),page=UpdatePolicy.releaseUrl(repository(),cache.getString("releaseUrl"),false);
            if(page.isEmpty())return null;
            String apk=UpdatePolicy.releaseUrl(repository(),cache.optString("apkUrl"),true);
            return new Result("发现新版本 "+tag+" · 上次检查的版本",tag,cache.optString("notes"),page,apk,
                    UpdatePolicy.newer(tag,installed),cache.optString("sha256"),cache.optLong("size"),validatedApi(cache.optString("apiUrl")));
        } catch(Exception invalidCache) { return null; }
    }
    void check(String installed, Callback callback) {
        final String repo = repository();
        final String[] routes=UpdateSources.metadata(prefs.getBoolean("update_acceleration",true));
        if (closed || !busy.compareAndSet(false, true)) return;
        prefs.edit().putLong("update_attempted_at",System.currentTimeMillis()).apply();
        worker.execute(() -> {
            Result result;
            JSONObject cacheToSave=null;
            try {
                Result fetched=null;
                for(int attempt=1;attempt<=3;attempt++) {
                    try {fetched=fetch(repo,installed,routes[(attempt-1)%routes.length]);break;}
                    catch(java.io.IOException error){if(attempt==3)throw error;Thread.sleep(attempt*700L);}
                }
                result=fetched;
                if(result!=null && !result.version.isEmpty()) {
                    cacheToSave=new JSONObject().put("version",result.version).put("notes",result.notes).put("releaseUrl",result.releaseUrl)
                            .put("apkUrl",result.apkUrl).put("sha256",result.sha256).put("size",result.size).put("apiUrl",result.apiUrl);
                }
            }
            catch (Exception error) {
                String message = error instanceof IllegalArgumentException ? error.getMessage() : "检查失败，已自动切换线路重试。请检查网络，或开启“更新加速”后重试。";
                result = new Result(message, "", "", "", "", false);
                try { if(!(error instanceof java.io.IOException))throw error;
                    JSONObject cache=new JSONObject(prefs.getString("update_cache","{}"));String tag=cache.getString("version");
                    String page=UpdatePolicy.releaseUrl(repo,cache.getString("releaseUrl"),false),apk=UpdatePolicy.releaseUrl(repo,cache.optString("apkUrl"),true);
                    if(!page.isEmpty()) {
                        String checked=java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT)
                                .format(new java.util.Date(prefs.getLong("update_cache_at",0)));
                        result=new Result("更新线路暂不可达 · 上次检查 "+checked+" · 缓存版本 "+tag,tag,cache.optString("notes"),page,apk,
                                UpdatePolicy.newer(tag,installed),cache.optString("sha256"),cache.optLong("size"),validatedApi(cache.optString("apiUrl")));
                    }
                } catch(Exception ignored) { }
            }
            if(!storeResult(result,cacheToSave))return;
            final Result delivered = result;
            handler.post(() -> { busy.set(false); if(!closed)callback.complete(delivered); });
        });
    }
    private Result fetch(String repo, String installed,String route) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)new URL(route).openConnection();
        connection.setConnectTimeout(6000); connection.setReadTimeout(8000); connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        connection.setRequestProperty("User-Agent", "SplashSkip/" + installed);
        try {
            int code = connection.getResponseCode();
            if (code != 200) throw new java.io.IOException("更新线路 HTTP "+code);
            JSONObject release;
            try (InputStream input = connection.getInputStream()) { release = new JSONObject(read(input, 512 * 1024)); }
            catch(org.json.JSONException error){throw new java.io.IOException("更新线路返回无效响应",error);}
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) throw new IllegalArgumentException("更新源未提供正式稳定版本");
            String tag = release.getString("tag_name"), link = UpdatePolicy.releaseUrl(repo, release.optString("html_url"), false);
            if (link.isEmpty()) throw new IllegalArgumentException("发布页面地址与配置的仓库不一致");
            boolean newer = UpdatePolicy.newer(tag, installed);
            String apk = "",sha="",api="";long size=0;
            JSONArray assets = release.optJSONArray("assets");
            if (assets != null) for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (asset.optString("name").matches("(?i)^SplashSkip.*\\.apk$")) {
                    apk = UpdatePolicy.releaseUrl(repo, asset.optString("browser_download_url"), true);
                    if (!apk.isEmpty()) {
                        String digest=asset.optString("digest");
                        if(digest.matches("sha256:[a-f0-9]{64}"))sha=digest.substring(7);
                        size=asset.optLong("size");long id=asset.optLong("id");
                        if(id>0)api="https://api.github.com/repos/"+repo+"/releases/assets/"+id;
                        break;
                    }
                }
            }
            String notes = release.optString("body", "暂无版本说明"); if (notes.length() > 2400) notes = notes.substring(0, 2400) + "…";
            boolean localNewer=UpdatePolicy.newer(installed,tag);
            return new Result(newer ? "发现新版本 " + tag : localNewer ? "当前 v"+installed+" · 官方发布 "+tag+"（本地版本较新）" :
                    "已是最新版本 v" + installed, tag, notes, link, apk, newer,sha,size,api);
        } finally { connection.disconnect(); }
    }
    private static String read(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] bytes = new byte[4096]; int count;
        while ((count = input.read(bytes)) != -1) { if (out.size() + count > limit) throw new IllegalArgumentException("更新响应过大"); out.write(bytes, 0, count); }
        return out.toString("UTF-8");
    }
    private synchronized boolean storeResult(Result result,JSONObject cache) {
        if(closed)return false;
        long now=System.currentTimeMillis();
        SharedPreferences.Editor edit=prefs.edit().putString("update_status",result.message).putLong("update_checked_at",now);
        if(cache!=null)edit.putString("update_cache",cache.toString()).putLong("update_cache_at",now).putLong("update_success_at",now);
        edit.apply();return true;
    }
    synchronized void close() { closed=true;busy.set(false);worker.shutdownNow(); }
    private static String validatedApi(String value) {
        return value.matches("https://api\\.github\\.com/repos/xudd2025-collab/SplashSkip/releases/assets/[1-9][0-9]*")?value:"";
    }
}
