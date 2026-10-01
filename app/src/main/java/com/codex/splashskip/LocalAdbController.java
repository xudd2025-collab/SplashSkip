package com.codex.splashskip;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import io.github.muntashirakon.adb.android.AdbMdns;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class LocalAdbController {
    private static final int PAIR_NOTIFICATION = 702;
    private static LocalAdbController instance;
    private final Context context;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LocalAdbClient client;
    private volatile boolean ready, busy;
    private long lastAttempt;
    private AdbMdns pairingDiscovery;
    private long pairingGeneration;

    static synchronized LocalAdbController get(Context context) {
        if (instance == null) instance = new LocalAdbController(context.getApplicationContext());
        return instance;
    }
    private LocalAdbController(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        state(prefs.getBoolean("local_adb_paired", false) ? "授权已保存，点一键连接恢复" : "首次使用请在本应用配对本机");
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                if (ready && !busy) {
                    busy = true;
                    worker.execute(() -> {
                        try {
                            String uid = client().shell("id -u", 2500);
                            if (!uid.equals("2000") && !uid.equals("0"))
                                throw new java.io.IOException("Shell connection lost");
                        } catch (Exception error) { fail(error, "本机连接已断开，授权仍保存；开启无线调试后可恢复"); }
                        finally { busy = false; }
                    });
                } else if (!ready) reconnect(false);
                handler.postDelayed(this, 20000);
            }
        }, 20000);
    }
    boolean ready() { return ready; }
    boolean busy() { return busy; }
    boolean paired() { return prefs.getBoolean("local_adb_paired", false); }
    private LocalAdbClient client() throws Exception {
        if (client == null) client = new LocalAdbClient(context);
        return client;
    }
    void reconnect(boolean explicit) {
        if ((!explicit && ready) || busy || (!explicit && (!paired() || SystemClock.elapsedRealtime() - lastAttempt < 30000))) return;
        if (!explicit && Build.VERSION.SDK_INT >= 30 &&
                Settings.Global.getInt(context.getContentResolver(), "adb_wifi_enabled", 0) == 0) return;
        busy = true; lastAttempt = SystemClock.elapsedRealtime();
        worker.execute(() -> {
            try { connectSavedOrDiscover(); }
            catch (Exception | LinkageError error) { fail(error, "连接未成功：开启无线调试后重试，或手填连接端口"); }
            finally { busy = false; }
        });
    }
    private void connectSavedOrDiscover() throws Exception {
        LocalAdbClient adb = client();
        if (ready) {
            try {
                if (adb.shell("id -u", 2500).equals("2000")) {
                    state("本机已连接 · 授权已保存 · 可拔掉 USB"); return;
                }
            } catch (Exception ignored) { }
            ready = false; adb.disconnect();
        }
        int saved = prefs.getInt("local_adb_port", 0);
        if (saved > 0) {
            state("正在连接保存的本机端口…");
            try { if (connectPort(adb, saved)) return; }
            catch (Exception error) { adb.disconnect(); }
        }
        state("查找本机无线调试端口（最多 6 秒）…");
        int port = discover(AdbMdns.SERVICE_TYPE_TLS_CONNECT, 6000);
        if (port < 1) throw new java.io.IOException("Discovery timeout");
        if (!connectPort(adb, port)) throw new java.io.IOException("Connection rejected");
    }
    void connectManual(int port) {
        if (busy) return;
        busy = true;
        worker.execute(() -> {
            try {
                ready = false;
                LocalAdbClient adb = client(); adb.disconnect();
                state("正在连接本机端口…");
                if (!connectPort(adb, port)) throw new java.io.IOException("Connection rejected");
            } catch (Exception | LinkageError error) { fail(error, "连接失败：请使用无线调试首页的连接端口，首次使用先配对"); }
            finally { busy = false; }
        });
    }
    private boolean connectPort(LocalAdbClient adb, int port) throws Exception {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid port");
        if (!adb.isConnected() && !adb.connect("127.0.0.1", port)) { adb.disconnect(); return false; }
        String uid = adb.shell("id -u", 2500);
        if (!uid.equals("2000") && !uid.equals("0")) { adb.disconnect(); throw new java.io.IOException("Not shell UID"); }
        prefs.edit().putBoolean("local_adb_paired", true).putInt("local_adb_port", port).apply();
        ready = true;
        state("本机已连接 · 授权已保存 · 可拔掉 USB");
        Diagnostics.append(context, "local ADB connected port=" + port + " uid=" + uid);
        handler.post(() -> SensorGuardController.get(context).localReady());
        return true;
    }
    private int discover(String type, long timeout) throws Exception {
        AtomicInteger port = new AtomicInteger(-1);
        CountDownLatch signal = new CountDownLatch(1);
        AdbMdns discovery = new AdbMdns(context, type, (address, value) -> {
            if (value > 0) { port.set(value); signal.countDown(); }
        });
        try { discovery.start(); signal.await(timeout, TimeUnit.MILLISECONDS); }
        finally { discovery.stop(); }
        return port.get();
    }
    void pair(int port, String code) {
        if (busy) return;
        if (!code.matches("[0-9]{6}") || port < 1 || port > 65535) {
            state("请输入有效的 6 位配对码和配对端口"); return;
        }
        busy = true;
        stopPairingDiscovery();
        worker.execute(() -> {
            try {
                state("正在与本机配对…");
                if (!client().pair("127.0.0.1", port, code)) throw new java.io.IOException("Pairing rejected");
                prefs.edit().putBoolean("local_adb_paired", true).apply();
                state("配对成功，正在连接…");
                Diagnostics.append(context, "local ADB paired; key saved privately");
                connectSavedOrDiscover();
            } catch (Exception | LinkageError error) {
                fail(error, paired() ? "授权已保存；请点一键连接或手填连接端口" : "配对失败：保持系统配对码窗口打开，使用当前配对端口和配对码");
            } finally { busy = false; }
        });
    }
    void startPairingDiscovery() {
        if (Build.VERSION.SDK_INT < 30) { state("Android 11 以下请使用已授权的 Shizuku 连接"); return; }
        stopPairingDiscovery();
        long generation = ++pairingGeneration;
        state("等待系统配对码窗口；检测到本机端口后会显示输入通知（3 分钟）");
        pairingDiscovery = new AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING, (address, port) -> {
            if (port < 1) return;
            handler.post(() -> {
                if (generation != pairingGeneration) return;
                prefs.edit().putInt("local_pair_port", port).putLong("local_pair_seen", System.currentTimeMillis()).apply();
                state("已找到本机配对端口 " + port + "，下拉通知输入系统配对码");
                pairNotification(port);
            });
        });
        pairingDiscovery.start();
        handler.postDelayed(() -> {
            if (generation != pairingGeneration) return;
            stopPairingDiscovery();
            state("本次配对等待已结束；可重新开始，或分屏手填配对端口");
        }, 180000);
    }
    void pairFromNotification(String code) {
        int port = prefs.getInt("local_pair_port", 0);
        if (System.currentTimeMillis() - prefs.getLong("local_pair_seen", 0) > 180000 || port < 1) {
            state("配对端口已过期，请重新打开系统配对码窗口"); return;
        }
        pair(port, code.trim());
    }
    private void pairNotification(int port) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("local_pair", "本机配对", NotificationManager.IMPORTANCE_HIGH));
        Intent intent = new Intent(context, PairingReceiver.class).setAction("com.codex.splashskip.PAIR");
        PendingIntent reply = PendingIntent.getBroadcast(context, 702, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
        Notification.Action action = new Notification.Action.Builder(android.R.drawable.ic_lock_lock, "输入配对码", reply)
                .addRemoteInput(new RemoteInput.Builder("pair_code").setLabel("系统显示的 6 位配对码").build()).build();
        PendingIntent open = PendingIntent.getActivity(context, 703, new Intent(context, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        try {
            manager.notify(PAIR_NOTIFICATION, new Notification.Builder(context, "local_pair")
                    .setSmallIcon(android.R.drawable.ic_lock_lock).setContentTitle("开屏助手 · 输入本机配对码")
                    .setContentText("端口 " + port + " · 保持系统配对码窗口打开").addAction(action)
                    .setContentIntent(open).setOnlyAlertOnce(true).setTimeoutAfter(180000).build());
        } catch (SecurityException error) { state("请允许通知，或用分屏手填配对码"); }
    }
    void stopPairingDiscovery() {
        ++pairingGeneration;
        if (pairingDiscovery != null) { pairingDiscovery.stop(); pairingDiscovery = null; }
        context.getSystemService(NotificationManager.class).cancel(PAIR_NOTIFICATION);
    }
    synchronized String shell(String command, int timeoutMs) throws Exception {
        if (!ready) throw new java.io.IOException("Local connection unavailable");
        try { return client().shell(command, timeoutMs); }
        catch (Exception error) {
            ready = false;
            state("本机连接已断开；开启无线调试后点一键恢复");
            handler.post(() -> SensorGuardController.get(context).localLost());
            reconnect(false); throw error;
        }
    }
    private void fail(Throwable error, String message) {
        ready = false;
        try { if (client != null) client.disconnect(); } catch (Exception ignored) { }
        state(message);
        handler.post(() -> SensorGuardController.get(context).localLost());
        Diagnostics.append(context, "local ADB failed: " + error.getClass().getSimpleName() + " " + error.getMessage());
    }
    private void state(String message) { prefs.edit().putString("local_adb_status", message).apply(); }
}
