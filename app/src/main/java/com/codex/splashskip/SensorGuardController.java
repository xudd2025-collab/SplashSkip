package com.codex.splashskip;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import rikka.shizuku.Shizuku;

final class SensorGuardController {
    private static SensorGuardController instance;
    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, Boolean> eligible = new HashMap<>();
    private final Shizuku.UserServiceArgs args;
    private final LocalAdbController local;
    private final LocalSensorBackend localBackend;
    private volatile ISensorGuard remote;
    private boolean binding;
    private volatile String foreground = "";
    private String pendingPackage;
    private long pendingSince;
    private long statusGeneration;

    static synchronized SensorGuardController get(Context context) {
        if (instance == null) instance = new SensorGuardController(context.getApplicationContext());
        return instance;
    }

    private SensorGuardController(Context context) {
        this.context = context;
        local = LocalAdbController.get(context);
        localBackend = new LocalSensorBackend(local);
        args = new Shizuku.UserServiceArgs(new ComponentName(context, SensorGuardService.class))
                .daemon(false).processNameSuffix("sensor_guard").version(2).debuggable(false);
        Shizuku.addBinderReceivedListenerSticky(this::connect);
        Shizuku.addBinderDeadListener(() -> {
            remote = null;
            binding = false;
            if (!local.ready()) state("连接已停止，请在应用内一键恢复");
        });
        Shizuku.addRequestPermissionResultListener((code, result) -> {
            if (result == PackageManager.PERMISSION_GRANTED) connect();
            else if (!local.ready()) state("未授权，可使用应用内配对连接");
        });
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            binding = false;
            remote = ISensorGuard.Stub.asInterface(binder);
            state("已就绪：进入普通应用后保护 6 秒");
            if (pendingPackage != null && android.os.SystemClock.uptimeMillis() - pendingSince < 1500) {
                protect(pendingPackage);
            }
            pendingPackage = null;
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            remote = null;
            binding = false;
            if (!local.ready()) state("连接已断开，请在应用内一键恢复");
        }
    };

    boolean enabled() {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("anti_shake", true);
    }

    void connect() {
        if (!enabled()) return;
        if (local.ready()) return;
        local.reconnect(false);
        if (remote != null || binding) return;
        try {
            if (!Shizuku.pingBinder()) { state("尚未生效：请在本应用配对并连接本机"); return; }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                state("需要授权 Shizuku，当前未生效");
                return;
            }
            binding = true;
            Shizuku.bindUserService(args, connection);
            handler.postDelayed(() -> {
                if (binding) { binding = false; state("连接超时，请重新授权或启动 Shizuku"); }
            }, 4000);
        } catch (Exception error) {
            binding = false;
            state("连接失败：" + error.getClass().getSimpleName());
        }
    }

    void localReady() {
        if (!enabled()) return;
        state("本机连接已就绪：进入普通应用后保护 6 秒");
        if (pendingPackage != null && android.os.SystemClock.uptimeMillis() - pendingSince < 1500)
            protect(pendingPackage);
        pendingPackage = null;
    }

    void localLost() {
        if (remote == null && enabled()) state("连接已断开：防摇一摇暂未生效，授权已保留");
    }

    void setEnabled(boolean value) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putBoolean("anti_shake", value).apply();
        if (value) connect();
        else {
            pendingPackage = null;
            worker.execute(() -> {
                try { if (remote != null) remote.restoreAll(); } catch (Exception ignored) { }
                try { if (local.ready()) localBackend.restoreAll(); } catch (Exception ignored) { }
                state("已关闭防摇一摇");
            });
        }
    }

    void foreground(String pkg) {
        if (pkg.equals(foreground)) return;
        foreground = pkg;
        pendingPackage = null;
        if (!enabled() || pkg.isEmpty() || pkg.equals(context.getPackageName()) || pkg.contains("launcher")) return;
        Boolean allowed = eligible.get(pkg);
        if (allowed == null) {
            try {
                ApplicationInfo info = context.getPackageManager().getApplicationInfo(pkg, 0);
                allowed = info.uid >= 10000 && (info.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
            } catch (Exception error) { allowed = false; }
            eligible.put(pkg, allowed);
        }
        if (!allowed) return;
        if (remote == null && !local.ready()) {
            pendingPackage = pkg;
            pendingSince = android.os.SystemClock.uptimeMillis();
            connect();
        } else protect(pkg);
    }

    private void protect(String pkg) {
        int userId = android.os.Process.myUid() / 100000;
        worker.execute(() -> {
            try {
                ISensorGuard service = remote;
                boolean useLocal = local.ready();
                if (!enabled() || !pkg.equals(foreground) || (!useLocal && service == null)) return;
                String result = useLocal ? localBackend.protect(pkg, userId) : service.protect(pkg, userId);
                Diagnostics.append(context, "sensor guard " + pkg + " " + result);
                if (result.equals("already-active")) {
                    state(pkg + "：保护中，沿用原来的 6 秒计时");
                } else if (result.equals("protected")) {
                    long generation = ++statusGeneration;
                    state(pkg + "：已暂停运动传感器，6 秒后恢复");
                    handler.postDelayed(() -> worker.execute(() -> {
                        try {
                            String restored = useLocal ? localBackend.getState(pkg, userId) : service.getState(pkg, userId);
                            Diagnostics.append(context, "sensor guard restored " + pkg + " state=" + restored);
                            if (enabled() && generation == statusGeneration) {
                                state(restored.equals("active") || restored.equals("idle") ?
                                        "6 秒保护已结束，已恢复系统传感器管理" :
                                        "6 秒计时已结束，暂时无法读取系统状态");
                            }
                        } catch (Exception error) {
                            Diagnostics.append(context, "sensor restore status unavailable; timed shell restores independently");
                        }
                    }), 6500);
                } else state("当前系统未接受传感器暂停：" + result);
            } catch (Exception error) {
                Diagnostics.append(context, "sensor guard failed " + pkg + " " + error.getClass().getSimpleName() + " " + error.getMessage());
                state("防摇一摇未生效：" + error.getClass().getSimpleName());
            }
        });
    }

    private void state(String value) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                .putString("sensor_status", value).apply();
    }
}
