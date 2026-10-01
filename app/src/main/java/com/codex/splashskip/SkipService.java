package com.codex.splashskip;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SkipService extends AccessibilityService {
    private static final long SCAN_WINDOW_MS = 8_000;
    private static final long SCAN_INTERVAL_MS = 180;
    private static final long CLICK_COOLDOWN_MS = 8_000;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pollRunnable;
    private String currentPackage = "";
    private long activeUntil;
    private long lastScan;
    private long lastClick;
    private long lastScreenshot;
    private long lastProfileEvent;
    private long lastDiagnostic;
    private long lastEventTrace;
    private boolean firstFrame;
    private VisualRuleMatcher.Match verifyMatch;
    private long verifyAfter;
    private VisualRuleMatcher.Match stableCandidate;
    private long stableCandidateAt;
    private long lastSplashClick;
    private long lastPopupClick;
    private long screenshotSerial;
    private boolean screenshotPending;
    private VisualRuleMatcher visualRules;
    private SkipTextModel textModel;
    private SensorGuardController sensorGuard;
    private BilibiliRules biliRules;
    private final ExecutorService biliWorker = Executors.newSingleThreadExecutor();
    private Runnable biliPoll;
    private long biliUntil, lastBiliScan, lastBiliFrame, lastBiliClick, lastBiliAdClick, lastBiliLiveClick;
    private boolean biliFramePending, destroyed;
    private BilibiliVisualMatcher.Hit biliStable, biliVerify;
    private long biliStableAt;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        if (textModel != null) { textModel.close(); textModel = null; }
        sensorGuard = SensorGuardController.get(this);
        sensorGuard.connect();
        visualRules = new VisualRuleMatcher(this);
        biliRules = new BilibiliRules(this);
        try {
            textModel = new SkipTextModel(this);
        } catch (Exception | LinkageError error) {
            Log.e("SplashSkipModel", "Model could not be loaded", error);
        }
        trace("service connected; model=" + (textModel != null));
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED &&
                AppProfiles.find(this, pkg) != null &&
                getSharedPreferences("settings", MODE_PRIVATE).getBoolean("capture_click", false)) {
            AccessibilityNodeInfo source = event.getSource();
            Rect bounds = new Rect();
            String id = "";
            String label = "";
            if (source != null) {
                source.getBoundsInScreen(bounds);
                id = value(source.getViewIdResourceName());
                label = value(source.getText()) + " " + value(source.getContentDescription());
            }
            String detail = "class=" + value(event.getClassName()) +
                    " id=" + id + " label=" + label.trim() + " bounds=" + bounds +
                    " source=" + (source != null);
            Log.i("SplashSkipTap", detail);
            getSharedPreferences("settings", MODE_PRIVATE).edit()
                    .putBoolean("capture_click", false)
                    .putString("last_tap", detail).apply();
        }
        // Accessibility events from background windows must not replace the foreground app
        // and cancel the short screenshot polling window.
        AccessibilityNodeInfo activeRoot = activeRoot();
        boolean rootMatches = activeRoot != null && activeRoot.getPackageName() != null &&
                pkg.contentEquals(activeRoot.getPackageName());
        if (sensorGuard != null && (rootMatches ||
                (AppProfiles.find(this, pkg) != null &&
                 event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))) {
            sensorGuard.foreground(pkg);
        }
        if (pkg.equals(getPackageName()) || pkg.equals("com.android.systemui") ||
                pkg.startsWith("com.android.settings")) return;
        if (pkg.equals(BilibiliRules.PACKAGE) && rootMatches) {
            onBiliEvent();
            return;
        }
        if (AppProfiles.find(this, pkg) != null &&
                SystemClock.uptimeMillis() - lastEventTrace > 750) {
            lastEventTrace = SystemClock.uptimeMillis();
            trace("event=" + event.getEventType() + " root=" +
                    (activeRoot == null ? "null" : activeRoot.getPackageName()) +
                    " tracked=" + currentPackage + " remaining=" + (activeUntil - lastEventTrace));
        }
        // A registered app launch event can arrive before its root window is ready.
        // Start polling from that event; the screenshot callback checks the root again.
        if (!rootMatches && AppProfiles.find(this, pkg) == null) return;
        if (pkg.contains("launcher")) {
            currentPackage = "";
            return;
        }
        if (!getSharedPreferences("settings", MODE_PRIVATE).getBoolean("enabled", true)) return;
        long now = SystemClock.uptimeMillis();
        boolean newLaunch = !pkg.equals(currentPackage) ||
                (AppProfiles.find(this, pkg) != null && now > activeUntil &&
                (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                 event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
                 now - lastProfileEvent > 2_000));
        if (AppProfiles.find(this, pkg) != null) lastProfileEvent = now;
        if (newLaunch) {
            currentPackage = pkg;
            activeUntil = now + (AppProfiles.find(this, pkg) != null ?
                    AppProfiles.find(this, pkg).scanWindowMs : SCAN_WINDOW_MS);
            lastClick = 0;
            lastSplashClick = 0;
            lastPopupClick = 0;
            firstFrame = true;
            verifyMatch = null;
            stableCandidate = null;
            schedulePoll(pkg);
            if (AppProfiles.find(this, pkg) != null) {
                trace("scan window started event=" + event.getEventType());
            }
        }
        if (AppProfiles.find(this, pkg) != null &&
                AppProfiles.enabled(this)) {
            scanProfileImage(pkg, now);
        }
        scan(pkg);
    }

    private void schedulePoll(String pkg) {
        if (pollRunnable != null) handler.removeCallbacks(pollRunnable);
        pollRunnable = new Runnable() {
            @Override public void run() {
                if (!pkg.equals(currentPackage) || SystemClock.uptimeMillis() > activeUntil) return;
                if (AppProfiles.find(SkipService.this, pkg) != null &&
                        AppProfiles.enabled(SkipService.this)) {
                    scanProfileImage(pkg, SystemClock.uptimeMillis());
                }
                scan(pkg);
                handler.postDelayed(this, 350);
            }
        };
        handler.postDelayed(pollRunnable, 350);
    }

    private void onBiliEvent() {
        long now = SystemClock.uptimeMillis();
        if (!BilibiliRules.PACKAGE.equals(currentPackage)) {
            if (pollRunnable != null) handler.removeCallbacks(pollRunnable);
            currentPackage = BilibiliRules.PACKAGE;
            activeUntil = now + SCAN_WINDOW_MS;
            lastClick = 0; biliStable = null; biliVerify = null;
        }
        if (getSharedPreferences("settings", MODE_PRIVATE).getBoolean("enabled", true)) scan(BilibiliRules.PACKAGE);
        if (!BilibiliRules.ads(this) && !BilibiliRules.live(this)) return;
        biliUntil = now + 4_000;
        scanBili();
        if (biliPoll == null) {
            biliPoll = new Runnable() {
                @Override public void run() {
                    if (destroyed || SystemClock.uptimeMillis() > biliUntil || !biliForeground()) { biliPoll = null; return; }
                    scanBili(); handler.postDelayed(this, 450);
                }
            };
            handler.postDelayed(biliPoll, 450);
        }
    }

    private boolean biliForeground() {
        KeyguardManager keyguard = (KeyguardManager)getSystemService(KEYGUARD_SERVICE);
        if (keyguard != null && keyguard.isKeyguardLocked()) return false;
        AccessibilityNodeInfo root = activeRoot();
        return root != null && root.getPackageName() != null && BilibiliRules.PACKAGE.contentEquals(root.getPackageName());
    }

    private void scanBili() {
        long now = SystemClock.uptimeMillis();
        if (biliRules == null || !biliForeground() || now - lastBiliScan < 250) return;
        lastBiliScan = now;
        boolean ads = BilibiliRules.ads(this), live = BilibiliRules.live(this);
        if (!ads && !live) return;
        if (biliVerify == null) {
            BilibiliRules.NodeHit hit = biliRules.node(activeRoot(), getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels, ads && now - lastBiliAdClick > CLICK_COOLDOWN_MS,
                    live && now - lastBiliLiveClick > CLICK_COOLDOWN_MS);
            if (hit != null) {
                AccessibilityNodeInfo target = hit.node;
                if (!target.isClickable() && target.getParent() != null) {
                    AccessibilityNodeInfo parent = target.getParent(); Rect rect = new Rect(); parent.getBoundsInScreen(rect);
                    if (parent.isClickable() && rect.width() < getResources().getDisplayMetrics().widthPixels * .65f &&
                            rect.height() < getResources().getDisplayMetrics().heightPixels * .12f) target = parent;
                }
                boolean accepted = target.isClickable() && target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (accepted) rememberAction(BilibiliRules.PACKAGE + "  " + hit.rule + " 控件");
                else accepted = tapPoint(hit.bounds.centerX(), hit.bounds.centerY(), hit.rule);
                if (accepted) {
                    lastBiliClick = now;
                    if (hit.rule.equals(BilibiliVisualMatcher.LIVE)) lastBiliLiveClick = now; else lastBiliAdClick = now;
                    biliVerify = new BilibiliVisualMatcher.Hit(hit.rule, hit.bounds.centerX(), hit.bounds.centerY(), 1);
                    trace(hit.rule + " node accepted=" + accepted);
                    getSharedPreferences("settings", MODE_PRIVATE).edit().putString("last_visual", hit.rule + " 控件与场景匹配")
                            .putString("last_result", "已提交点击，等待画面检查").apply();
                }
            }
        }
        if (android.os.Build.VERSION.SDK_INT < 30 || biliFramePending || now - lastBiliFrame < 800 ||
                (biliVerify == null && (!ads || now - lastBiliAdClick < CLICK_COOLDOWN_MS) &&
                 (!live || now - lastBiliLiveClick < CLICK_COOLDOWN_MS))) return;
        lastBiliFrame = now; biliFramePending = true;
        try { takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                HardwareBuffer buffer = result.getHardwareBuffer();
                try { biliWorker.execute(() -> {
                    Bitmap wrapped = null, screen = null;
                    BilibiliVisualMatcher.Hit hit = null;
                    try {
                        wrapped = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        if (wrapped != null) screen = wrapped.copy(Bitmap.Config.ARGB_8888, false);
                        if (screen != null) hit = biliRules.image(screen, ads, live);
                    } catch (RuntimeException error) { trace("bili frame error: " + error.getClass().getSimpleName()); }
                    finally {
                        if (screen != null) screen.recycle(); if (wrapped != null) wrapped.recycle(); buffer.close();
                    }
                    final BilibiliVisualMatcher.Hit match = hit;
                    handler.post(() -> { biliFramePending = false; applyBiliFrame(match, now); });
                }); } catch (RuntimeException error) { buffer.close(); biliFramePending = false; }
            }
            @Override public void onFailure(int errorCode) { biliFramePending = false; trace("bili screenshot unavailable: " + errorCode); }
        }); } catch (RuntimeException error) { biliFramePending = false; }
    }

    private void applyBiliFrame(BilibiliVisualMatcher.Hit hit, long requestedAt) {
        long now = SystemClock.uptimeMillis();
        if (destroyed || now - requestedAt > 2_500 || !biliForeground() || !BilibiliRules.PACKAGE.equals(currentPackage)) {
            biliStable = null; return;
        }
        if (biliVerify != null && requestedAt >= lastBiliClick + 350) {
            boolean remains = hit != null && hit.rule.equals(biliVerify.rule) &&
                    Math.abs(hit.x - biliVerify.x) < 35 && Math.abs(hit.y - biliVerify.y) < 35;
            getSharedPreferences("settings", MODE_PRIVATE).edit().putString("last_result",
                    remains ? "B站点击后仍识别到原目标" : "B站点击后原位置未识别到目标").apply();
            trace(biliVerify.rule + " post-tap remains=" + remains); biliVerify = null;
        }
        if (hit == null) { biliStable = null; return; }
        boolean live = hit.rule.equals(BilibiliVisualMatcher.LIVE);
        if (now - (live ? lastBiliLiveClick : lastBiliAdClick) < CLICK_COOLDOWN_MS) { biliStable = null; return; }
        if (live ? !BilibiliRules.live(this) : !BilibiliRules.ads(this)) { biliStable = null; return; }
        getSharedPreferences("settings", MODE_PRIVATE).edit().putString("last_visual", hit.rule + " 特征=" +
                String.format(Locale.ROOT, "%.2f", hit.score)).apply();
        boolean stable = biliStable != null && hit.rule.equals(biliStable.rule) && now - biliStableAt < 2_000 &&
                Math.abs(hit.x - biliStable.x) < 25 && Math.abs(hit.y - biliStable.y) < 25;
        if (live || stable) {
            biliStable = null;
            if (tapPoint(hit.x, hit.y, hit.rule)) {
                lastBiliClick = now; biliVerify = hit;
                if (live) lastBiliLiveClick = now; else lastBiliAdClick = now;
                getSharedPreferences("settings", MODE_PRIVATE).edit().putString("last_result", "已提交点击，等待画面检查").apply();
            }
        } else { biliStable = hit; biliStableAt = now; }
    }

    private void scan(String pkg) {
        // Registered self-drawn splashes are handled by screenshots. Walking their accessibility
        // tree here can block the main thread long enough to miss the entire countdown.
        if (AppProfiles.find(this, pkg) != null && AppProfiles.find(this, pkg).bypassNodeTree && textModel != null &&
                android.os.Build.VERSION.SDK_INT >= 30 &&
                AppProfiles.enabled(this)) return;
        long now = SystemClock.uptimeMillis();
        if (now > activeUntil || now - lastScan < SCAN_INTERVAL_MS ||
                now - lastClick < CLICK_COOLDOWN_MS) return;
        lastScan = now;
        AccessibilityNodeInfo root = activeRoot();
        if (root == null || root.getPackageName() == null ||
                !pkg.contentEquals(root.getPackageName())) return;
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collect(root, nodes, 0);
        boolean splashContext = false;
        for (AccessibilityNodeInfo node : nodes) {
            String id = lower(node.getViewIdResourceName());
            if (id.contains("splash") || id.contains("ad_splash")) {
                splashContext = true;
                break;
            }
        }
        boolean strict = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("strict", true);
        AccessibilityNodeInfo best = null;
        int bestScore = 0;
        for (AccessibilityNodeInfo node : nodes) {
            int score = score(node, splashContext, strict);
            if (score > bestScore) {
                best = node;
                bestScore = score;
            }
        }
        if (best != null) click(best, pkg, now);
    }

    private void scanProfileImage(String pkg, long now) {
        final AppProfiles.Profile profile = AppProfiles.find(this, pkg);
        if (profile == null) return;
        if (now > activeUntil) return;
        // Keep one request in flight. Reissuing while its callback is queued invalidates
        // that callback and can repeatedly discard every frame of a short advertisement.
        if (android.os.Build.VERSION.SDK_INT < 30 || visualRules == null || screenshotPending ||
                now - lastScreenshot < 500) return;
        lastScreenshot = now;
        screenshotPending = true;
        long serial = ++screenshotSerial;
        try { takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                if (serial == screenshotSerial) screenshotPending = false;
                HardwareBuffer buffer = result.getHardwareBuffer();
                Bitmap wrapped = null;
                Bitmap screen = null;
                try {
                    if (serial != screenshotSerial ||
                            SystemClock.uptimeMillis() - now > 2_500) {
                        trace("frame discarded: stale age=" + (SystemClock.uptimeMillis() - now));
                        return;
                    }
                    wrapped = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                    if (wrapped == null) return;
                    screen = wrapped.copy(Bitmap.Config.ARGB_8888, false);
                    if (screen == null || textModel == null ||
                            !pkg.equals(currentPackage) || !AppProfiles.enabled(SkipService.this) ||
                            SystemClock.uptimeMillis() > activeUntil) {
                        trace("frame discarded: state model=" + (textModel != null) +
                                " tracked=" + currentPackage);
                        return;
                    }
                    KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
                    if (keyguard != null && keyguard.isKeyguardLocked()) {
                        trace("frame discarded: locked");
                        return;
                    }
                    AccessibilityNodeInfo root = activeRoot();
                    if (root == null || root.getPackageName() == null ||
                            !pkg.contentEquals(root.getPackageName())) {
                        stableCandidate = null;
                        trace("frame discarded: root=" + (root == null ? "null" : root.getPackageName()));
                        return;
                    }
                    long current = SystemClock.uptimeMillis();
                    if (firstFrame) {
                        firstFrame = false;
                        Diagnostics.frame(SkipService.this, screen, "first-frame.png");
                        trace("first frame=" + screen.getWidth() + "x" + screen.getHeight());
                    }
                    if (verifyMatch != null && current >= verifyAfter) {
                        float remaining = textModel.probability(screen, verifyMatch.textBox);
                        trace("post-tap " + verifyMatch.rule + " same-text-score=" + remaining);
                        Diagnostics.frame(SkipService.this, screen, "after-tap.png");
                        getSharedPreferences("settings", MODE_PRIVATE).edit().putString("last_result",
                                remaining >= .95f ? "点击后仍检测到跳过文字" : "点击后原位置未检测到跳过文字").apply();
                        verifyMatch = null;
                    }
                    if (current - lastPopupClick > CLICK_COOLDOWN_MS) {
                        VisualRuleMatcher.Match popup = visualRules.findPopup(screen, profile);
                        if (popup != null && tapPoint(popup.x, popup.y, popup.rule)) {
                            lastPopupClick = current;
                            Log.i("SplashSkipVisual", popup.rule + " score=" + popup.score);
                            getSharedPreferences("settings", MODE_PRIVATE).edit()
                                    .putString("last_visual", popup.rule + " 特征=" +
                                            String.format(Locale.ROOT, "%.2f", popup.score))
                                    .apply();
                            return;
                        }
                    }
                    if (current - lastSplashClick <= CLICK_COOLDOWN_MS) return;
                    VisualRuleMatcher.Match match = visualRules.findText(screen, textModel, profile);
                    if (match != null) {
                        if (current - lastDiagnostic >= 2_000) {
                            lastDiagnostic = current;
                            trace("scan " + match.rule + " model=" + match.score +
                                    " x=" + match.x + " y=" + match.y +
                                    " elapsed=" + (SystemClock.uptimeMillis() - current) +
                                    " frameAge=" + (current - now));
                        }
                        if (match.score >= 0.5f) {
                            trace(match.rule + " model=" + match.score +
                                    " x=" + match.x + " y=" + match.y);
                            Diagnostics.frame(SkipService.this, screen, "candidate-frame.png");
                            getSharedPreferences("settings", MODE_PRIVATE).edit()
                                    .putString("last_visual", match.rule + " 模型=" +
                                            String.format(Locale.ROOT, "%.2f", match.score))
                                    .apply();
                        }
                        if (match.score >= 0.95f) {
                            int tolerance = Math.max(12, screen.getWidth() / 50);
                            boolean stable = stableCandidate != null &&
                                    match.rule.equals(stableCandidate.rule) &&
                                    Math.abs(match.x - stableCandidate.x) <= tolerance &&
                                    Math.abs(match.y - stableCandidate.y) <= tolerance &&
                                    current - stableCandidateAt >= 250 &&
                                    current - stableCandidateAt <= 1500;
                            if (stable) {
                                stableCandidate = null;
                                if (tapPoint(match.x, match.y, match.rule)) {
                                    lastSplashClick = current;
                                    verifyMatch = match;
                                    verifyAfter = SystemClock.uptimeMillis() + 300;
                                }
                            } else {
                                stableCandidate = match;
                                stableCandidateAt = current;
                                trace("text candidate: awaiting next frame " + match.rule);
                            }
                        } else {
                            stableCandidate = null;
                        }
                    }
                } catch (RuntimeException error) {
                    trace("frame error: " + error);
                    Log.w("SplashSkipVisual", "Screenshot matching failed", error);
                } finally {
                    if (screen != null) screen.recycle();
                    if (wrapped != null) wrapped.recycle();
                    buffer.close();
                }
            }
            @Override public void onFailure(int errorCode) {
                if (serial == screenshotSerial) screenshotPending = false;
                trace("Screenshot unavailable: " + errorCode);
            }
        }); } catch (RuntimeException error) {
            screenshotPending = false;
            Log.e("SplashSkipVisual", "takeScreenshot threw", error);
        }
    }

    private boolean tapPoint(int x, int y, String rule) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 75)).build();
        boolean submitted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription completed) {
                trace(rule + " gesture completed x=" + x + " y=" + y);
            }

            @Override public void onCancelled(GestureDescription cancelled) {
                trace(rule + " gesture cancelled x=" + x + " y=" + y);
            }
        }, null);
        if (submitted) {
            trace(rule + " gesture submitted x=" + x + " y=" + y);
            lastClick = SystemClock.uptimeMillis();
            rememberAction(currentPackage + "  " + rule);
            return true;
        }
        trace(rule + " gesture rejected x=" + x + " y=" + y);
        return false;
    }

    private void collect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> nodes, int depth) {
        if (node == null || depth > 40 || nodes.size() >= 500) return;
        nodes.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), nodes, depth + 1);
    }

    private int score(AccessibilityNodeInfo node, boolean splashContext, boolean strict) {
        String id = lower(node.getViewIdResourceName());
        String label = (value(node.getText()) + " " + value(node.getContentDescription())).trim().toLowerCase(Locale.ROOT);
        boolean idSkip = id.contains("skip") || id.contains("jump_over");
        boolean countdown = id.contains("count_down") || id.contains("countdown");
        boolean textSkip = label.matches("^(跳过|跳过\\s*[0-9０-９]+|skip|skip\\s*[0-9]+)(\\s*[秒s])?$");
        if (!idSkip && !countdown && !textSkip) return 0;
        if (strict && !idSkip && !(countdown && splashContext) &&
                !(textSkip && (splashContext || label.matches(".*[0-9０-９].*")))) return 0;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        if (bounds.isEmpty() || bounds.width() > width * 0.45f ||
                bounds.height() > height * 0.18f || bounds.left < 0 || bounds.top < 0 ||
                bounds.right > width || bounds.bottom > height) return 0;
        int cx = bounds.centerX();
        int cy = bounds.centerY();
        if (!(cx > width * 0.58f || cx < width * 0.42f) ||
                !(cy < height * 0.35f || cy > height * 0.65f)) return 0;
        if (!node.isClickable()) {
            AccessibilityNodeInfo parent = node.getParent();
            if (parent == null || !parent.isClickable()) return 0;
            Rect parentBounds = new Rect();
            parent.getBoundsInScreen(parentBounds);
            if (parentBounds.width() > width * 0.45f ||
                    parentBounds.height() > height * 0.18f) return 0;
        }
        return (idSkip ? 100 : 0) + (countdown ? 80 : 0) +
                (textSkip ? 60 : 0) + (node.isClickable() ? 10 : 0);
    }

    private void click(AccessibilityNodeInfo node, String pkg, long now) {
        AccessibilityNodeInfo target = node.isClickable() ? node : node.getParent();
        if (target == null) return;
        boolean clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        if (AppProfiles.find(this, pkg) != null) {
            Rect targetBounds = new Rect();
            target.getBoundsInScreen(targetBounds);
            trace("node click id=" + target.getViewIdResourceName() + " bounds=" + targetBounds +
                    " accepted=" + clicked);
        }
        if (!clicked) {
            Rect bounds = new Rect();
            target.getBoundsInScreen(bounds);
            Path path = new Path();
            path.moveTo(bounds.centerX(), bounds.centerY());
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 50)).build();
            clicked = dispatchGesture(gesture, null, null);
        }
        if (clicked) {
            lastClick = now;
            rememberAction(pkg);
        }
    }

    private void rememberAction(String description) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putString("last_action", time + "  " + description).apply();
    }

    private void trace(String message) {
        Log.i("SplashSkipVisual", message);
        Diagnostics.append(this, message);
    }

    private AccessibilityNodeInfo activeRoot() {
        // We only need the root package for foreground validation, not prefetched descendants.
        if (android.os.Build.VERSION.SDK_INT >= 33) return getRootInActiveWindow(0);
        return getRootInActiveWindow();
    }

    private String lower(String input) {
        return input == null ? "" : input.toLowerCase(Locale.ROOT);
    }

    private String value(CharSequence input) {
        return input == null ? "" : input.toString();
    }

    @Override public void onDestroy() {
        destroyed = true;
        if (biliPoll != null) handler.removeCallbacks(biliPoll);
        biliWorker.shutdown();
        if (pollRunnable != null) handler.removeCallbacks(pollRunnable);
        if (textModel != null) textModel.close();
        super.onDestroy();
    }

    @Override public void onInterrupt() { }
}
