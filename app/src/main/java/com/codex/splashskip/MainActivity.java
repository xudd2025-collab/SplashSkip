package com.codex.splashskip;

import android.app.Activity;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import rikka.shizuku.Shizuku;

public final class MainActivity extends Activity {
    private static final int INK = Color.rgb(40, 43, 60);
    private static final int MUTED = Color.rgb(130, 136, 155);
    private static final int ACCENT = Color.rgb(116, 91, 211);
    private static final int SOFT = Color.rgb(241, 237, 255);
    private static final int BACKGROUND = Color.rgb(247, 247, 252);
    private static final int BORDER = Color.rgb(237, 237, 246);
    private static final int GREEN = Color.rgb(41, 145, 117);
    private static final int AMBER = Color.rgb(169, 116, 48);
    private SharedPreferences prefs;
    private String version;
    private TextView status, accessBadge, accessButton, guardSummary, connectionBadge;
    private TextView connectionStatus, connectButton, recentSummary, lastAction, lastVisual, lastTap;
    private TextView biliSummary, updateSummary;
    private UpdateChecker updateChecker;
    private final ScrollView[] pages = new ScrollView[3];
    private final LinearLayout[] tabViews = new LinearLayout[3];
    private final TextView[] tabLabels = new TextView[3];
    private final ImageView[] tabIcons = new ImageView[3];
    private int currentTab;
    private boolean restoreAfterSettings, resumed;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable updateStatus = new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this, 1000); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        restoreAfterSettings = saved != null && saved.getBoolean("restoreAfterSettings", false);
        updateChecker = new UpdateChecker(this);
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception error) { version = "0.5.2"; }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        LinearLayout root = column();
        root.setBackgroundColor(BACKGROUND);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        FrameLayout pageHost = new FrameLayout(this);
        root.addView(pageHost, new LinearLayout.LayoutParams(-1, 0, 1));
        for (int i = 0; i < pages.length; i++) {
            pages[i] = new ScrollView(this);
            pages[i].setFillViewport(true);
            pages[i].setVerticalScrollBarEnabled(false);
            pageHost.addView(pages[i], new FrameLayout.LayoutParams(-1, -1));
        }
        SensorGuardController.get(this).connect();
        buildHome();
        buildConnection();
        buildDiagnostics();
        View separator = new View(this); separator.setBackgroundColor(BORDER);
        root.addView(separator, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout navigation = row(); navigation.setBackgroundColor(Color.WHITE);
        navigation.setPadding(dp(12), dp(8), dp(12), dp(8));
        String[] names = {"首页", "连接", "记录"};
        int[] icons = {R.drawable.ic_home, R.drawable.ic_connection, R.drawable.ic_record};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            LinearLayout item = column(); item.setGravity(Gravity.CENTER);
            item.setBackground(ripple(Color.TRANSPARENT, 16, 0));
            item.setContentDescription(names[i]); item.setFocusable(true);
            item.setOnClickListener(v -> selectTab(index));
            ImageView icon = new ImageView(this); icon.setImageResource(icons[i]);
            item.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
            TextView label = text(names[i], 11, MUTED); label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(-2, -2); labelParams.topMargin = dp(4);
            item.addView(label, labelParams);
            navigation.addView(item, new LinearLayout.LayoutParams(0, dp(54), 1));
            tabViews[i] = item; tabIcons[i] = icon; tabLabels[i] = label;
        }
        root.addView(navigation, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        root.requestApplyInsets();
        selectTab(saved == null ? 0 : saved.getInt("tab", 0));
    }

    private LinearLayout page(int index, String title, String subtitle) {
        LinearLayout content = column(); content.setPadding(dp(20), dp(18), dp(20), dp(24));
        pages[index].addView(content, new ScrollView.LayoutParams(-1, -2));
        LinearLayout header = row(); header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView avatar = new ImageView(this); avatar.setImageResource(R.drawable.anime_avatar);
        avatar.setBackground(shape(SOFT, 18, 0)); avatar.setClipToOutline(true);
        avatar.setContentDescription("开屏助手二次元小猫头像");
        avatar.setOnClickListener(v -> about());
        header.addView(avatar, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout identity = column(); identity.setPadding(dp(12), 0, dp(4), 0);
        TextView heading = text(title, 23, INK); heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        identity.addView(heading);
        TextView caption = text(subtitle, 12, MUTED); caption.setPadding(0, dp(3), 0, 0); identity.addView(caption);
        header.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
        TextView versionChip = chip("v" + version, ACCENT, SOFT);
        versionChip.setContentDescription("版本 " + version + "，点击查看关于");
        versionChip.setOnClickListener(v -> about());
        header.addView(versionChip, new LinearLayout.LayoutParams(-2, dp(40)));
        content.addView(header, new LinearLayout.LayoutParams(-1, -2));
        return content;
    }

    private void buildHome() {
        LinearLayout content = page(0, "开屏助手", "让开屏，更轻快");
        LinearLayout access = card(content);
        LinearLayout accessTitle = row();
        accessTitle.addView(labelWithHelp("无障碍服务", () -> "开启后，助手才能读取跳过控件，并通过系统无障碍手势点击。\n\n此权限用于跳过开屏广告和处理你启用的应用规则。系统关闭服务时，请在设置中重新开启。"), new LinearLayout.LayoutParams(0, -2, 1));
        accessBadge = chip("", GREEN, Color.rgb(234, 247, 241)); accessTitle.addView(accessBadge);
        access.addView(accessTitle);
        status = text("", 13, MUTED); status.setPadding(0, dp(1), 0, dp(13)); access.addView(status);
        accessButton = actionButton("", false, () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        access.addView(accessButton, new LinearLayout.LayoutParams(-1, dp(44)));

        section(content, "功能设置");
        LinearLayout features = card(content, false);
        feature(features, "自动跳过", null, () -> "应用打开后的短时间内，寻找真正的“跳过”控件并点击。\n\n普通应用使用无障碍控件识别；已适配的特殊应用可通过 AI 强化模式识别。无法确认时会放弃点击。",
                prefs.getBoolean("enabled", true), value -> prefs.edit().putBoolean("enabled", value).apply(), true);
        feature(features, "严格识别", null, () -> "优先选择名称、计时和位置都明确的跳过控件，减少误点。\n\n开启后，文字或控件信息不明确的广告可能会被放过。",
                prefs.getBoolean("strict", true), value -> prefs.edit().putBoolean("strict", value).apply(), true);
        feature(features, "AI 强化模式", "适配库 · " + AppProfiles.labels(this), () -> "特殊应用的跳过布局、模型识别与活动弹窗特征统一放在这里。\n\n当前已适配：" + AppProfiles.labels(this) + "。普通应用继续使用控件识别。未知样式需要补充样本与规则。\n\n画面在手机本地识别，不上传。",
                AppProfiles.enabled(this), value -> prefs.edit().putBoolean("ai_enhanced", value).apply(), true);
        feature(features, "防摇一摇", "启动保护 6 秒", () -> "进入普通第三方应用后，临时暂停该应用的运动传感器，6 秒后自动恢复。\n\n需要在“连接”页完成本机连接，或使用已授权的 Shizuku。期间该应用的重力感应、指南针也会暂停。\n\n这个开关与自动跳过独立。\n\n当前状态：" + prefs.getString("sensor_status", "尚未连接"),
                SensorGuardController.get(this).enabled(), value -> { SensorGuardController.get(this).setEnabled(value); refresh(); }, false);
        guardSummary = text("", 12, MUTED);
        guardSummary.setPadding(dp(16), 0, dp(16), dp(15)); features.addView(guardSummary);

        section(content, "应用规则");
        LinearLayout appRules = card(content, false);
        biliSummary = actionRow(appRules, "哔哩哔哩", "广告卡片 · 自动进入直播间", () -> "点击这一行管理 B站的两个独立开关。\n\n广告卡片关闭受“自动跳过”和“AI 强化模式”控制。自动进入直播间的取消开关独立工作，默认关闭；正常观看直播不受影响。\n\n仅处理已匹配的界面样式。", this::bilibiliSettings, false);

        LinearLayout recent = card(content);
        recent.addView(labelWithHelp("最近活动", () -> "这里显示最近一次跳过尝试。\n\n提交点击手势不一定等于成功关闭广告；详细的画面判断和点击后检查可在“记录”页查看。"));
        recentSummary = text("", 14, MUTED); recentSummary.setLineSpacing(dp(3), 1);
        recentSummary.setPadding(0, dp(3), 0, dp(8)); recent.addView(recentSummary);
        TextView records = actionButton("查看记录", false, () -> selectTab(2));
        recent.addView(records, new LinearLayout.LayoutParams(-1, dp(42)));
        TextView privacy = text("本机处理  ·  不上传屏幕", 11, MUTED); privacy.setGravity(Gravity.CENTER);
        privacy.setPadding(0, dp(20), 0, 0); content.addView(privacy);
    }

    private void buildConnection() {
        LinearLayout content = page(1, "本机连接", "授权保存，随时恢复");
        LinearLayout connection = card(content);
        LinearLayout title = row();
        title.addView(labelWithHelp("一键连接", () -> "首次使用先完成无线配对，以后可使用保存的授权恢复连接。\n\n连接只在本机进行，无需电脑或 USB 线。无线调试需保持开启；重启、网络变化或撤销授权后可能需要重新开启或配对。"), new LinearLayout.LayoutParams(0, -2, 1));
        connectionBadge = chip("", ACCENT, SOFT); title.addView(connectionBadge); connection.addView(title);
        connectionStatus = text("", 13, MUTED); connectionStatus.setMaxLines(4); connectionStatus.setEllipsize(TextUtils.TruncateAt.END);
        connectionStatus.setLineSpacing(dp(2), 1); connectionStatus.setPadding(0, dp(2), 0, dp(18)); connection.addView(connectionStatus);
        connectButton = actionButton("连接 / 恢复", true, () -> {
            LocalAdbController local = LocalAdbController.get(this);
            if (!local.paired()) beginPairing(); else beginRestore();
            refresh();
        });
        connection.addView(connectButton, new LinearLayout.LayoutParams(-1, dp(48)));

        section(content, "连接方式");
        LinearLayout methods = card(content, false);
        actionRow(methods, "无线配对", "首次使用或重新授权", () -> "Android 11 及更新版本可在本应用配对。\n\n1. 连接 Wi-Fi，开启系统“无线调试”。\n2. 点“使用配对码配对设备”，保持窗口打开。\n3. 下拉开屏助手通知，输入系统显示的 6 位配对码。\n\n配对成功后自动连接，并保存授权。", this::beginPairing, true);
        actionRow(methods, "手填端口", "搜索不到时使用", () -> "已配对：填写系统“无线调试”首页的连接端口。\n\n首次手动配对：填写配对窗口内的配对端口和 6 位配对码，用分屏保持系统窗口打开。\n\n配对端口和连接端口不同。这里只连接本机，无需填写 IP。", () -> new AppDialog.Builder(this).setTitle("手动连接本机")
                .setItems(new String[]{"已配对：输入连接端口", "首次配对：输入端口和配对码"}, (dialog, which) -> manualEntry(which == 1)).show(), false);
        LinearLayout steps = card(content);
        steps.addView(labelWithHelp("首次连接指南", () -> "手机首次配对需要系统显示的配对码，无法省略这次授权。\n\n允许开屏助手发送通知，才能在系统配对窗口仍打开时输入配对码。"));
        guideStep(steps, "1", "开启无线调试");
        guideStep(steps, "2", "打开系统配对码窗口");
        guideStep(steps, "3", "下拉通知，输入 6 位码");

        LinearLayout advancedHeading = row(); advancedHeading.setPadding(0, dp(20), 0, dp(6));
        TextView advancedToggle = text("兼容选项  ▾", 13, MUTED); advancedToggle.setGravity(Gravity.CENTER_VERTICAL);
        advancedToggle.setMinHeight(dp(48)); advancedToggle.setFocusable(true);
        advancedHeading.addView(advancedToggle); content.addView(advancedHeading);
        LinearLayout advanced = card(content, false); advanced.setVisibility(View.GONE);
        actionRow(advanced, "Shizuku", "使用已经启动的服务", () -> "旧系统或已有 Shizuku 的手机可以使用此兼容入口。\n\n需要先启动 Shizuku，再授予开屏助手权限。点击不会自动跳转到外部管理器。", this::authorizeShizuku, false);
        advancedToggle.setOnClickListener(v -> {
            boolean show = advanced.getVisibility() != View.VISIBLE;
            advanced.setVisibility(show ? View.VISIBLE : View.GONE); advancedToggle.setText(show ? "兼容选项  ▴" : "兼容选项  ▾");
        });
    }

    private void buildDiagnostics() {
        LinearLayout content = page(2, "诊断记录", "看清每一次识别与点击");
        LinearLayout latest = card(content);
        latest.addView(labelWithHelp("最近尝试", () -> "显示最近一次控件点击或手势提交。\n\n这是一条尝试记录，不能单独证明广告已关闭。"));
        lastAction = text("", 13, MUTED); lastAction.setLineSpacing(dp(3), 1); lastAction.setPadding(0, dp(4), 0, dp(12)); latest.addView(lastAction);
        divider(latest, 0);
        latest.addView(labelWithHelp("视觉识别", () -> "这里保留模型或特征匹配结果，以及点击后原位置是否还存在跳过文字。\n\n只有符合识别条件的候选才会尝试点击。文字消失也不一定代表所有情况下都已关闭广告。"));
        lastVisual = text("", 13, MUTED); lastVisual.setLineSpacing(dp(4), 1); lastVisual.setPadding(0, dp(4), 0, dp(4)); latest.addView(lastVisual);
        section(content, "诊断工具");
        LinearLayout tools = card(content, false);
        actionRow(tools, "复制日志", "保存在手机本地", () -> "复制本应用的诊断日志，包含扫描、识别、点击结果与连接状态，方便分析无法识别或无法点击的样式。\n\n复制到剪贴板，不会自动发送或上传。", () -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("开屏助手诊断", Diagnostics.read(this)));
            Toast.makeText(this, "诊断日志已复制", Toast.LENGTH_SHORT).show();
        }, true);
        actionRow(tools, "记录手动点击", "等待强化应用的下一次点击", () -> "开启后，在已适配的强化应用中亲手点一次跳过按钮。助手会记录应用提供的控件点击事件，供后续分析。\n\n如果应用不提供这个事件，可能采集不到，需要配合截图。\n\n再次点击此入口可取消等待。", () -> {
            boolean capturing = prefs.getBoolean("capture_click", false);
            prefs.edit().putBoolean("capture_click", !capturing).apply(); refresh();
            Toast.makeText(this, capturing ? "已取消记录" : "请在强化应用中手动点一次跳过", Toast.LENGTH_SHORT).show();
        }, false);
        LinearLayout taps = card(content);
        taps.addView(labelWithHelp("手动点击记录", () -> "显示等待状态或最近收到的控件事件。\n\n当前已适配：" + AppProfiles.labels(this) + "。事件内容仅在本机记录。"));
        lastTap = text("", 13, MUTED); lastTap.setLineSpacing(dp(3), 1); lastTap.setPadding(0, dp(3), 0, 0); taps.addView(lastTap);

        section(content, "版本与更新");
        LinearLayout updates = card(content);
        updates.addView(labelWithHelp("检查更新", () -> "读取 GitHub 仓库最新的正式 Release，比较发布标签与当前 APK 版本。\n\n需要公开可访问的仓库和正式 Release，不读取你的 GitHub 账号。只发送更新请求，不上传屏幕或诊断日志。\n\n发现更新后，由你决定是否打开下载页面和安装。"));
        updateSummary = text("", 12, MUTED); updateSummary.setLineSpacing(dp(3), 1); updates.addView(updateSummary);
        TextView check = actionButton("检查更新 · v" + version, true, () -> checkUpdates(true));
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(-1, dp(44)); checkParams.topMargin = dp(14); updates.addView(check, checkParams);
        LinearLayout updateOptions = card(content, false);
        actionRow(updateOptions, "开源项目", "源码 · 贡献 · GitHub Releases", () -> "更新固定读取开屏助手官方 GitHub 仓库的正式 Releases，不支持自定义更新地址。\n\n可在项目页面查看源码、提交问题或参与共同维护。手机无需登录 GitHub。", () -> openWeb("https://github.com/" + updateChecker.repository()), true);
        feature(updateOptions, "启动检查更新", "每天最多一次，可随时手动检查", () -> "开启后，在应用启动或回到前台时从官方 GitHub 仓库检查更新，每天最多一次。\n\n关闭后仍可点击“检查更新”。", prefs.getBoolean("update_on_start", true),
                value -> prefs.edit().putBoolean("update_on_start", value).apply(), false);
    }

    private void bilibiliSettings() {
        LinearLayout options = column(); options.setPadding(dp(8), 0, dp(8), dp(12));
        feature(options, "关闭广告卡片", "处理视频下方弹出的推广卡片", () -> "同时确认右侧关闭圆圈、相邻菜单、卡片拖动条与广告标记后，点击“×”。\n\n优先使用可访问的关闭控件，缺少控件时在本机匹配截图特征。\n\n需要开启“自动跳过”和“AI 强化模式”。", prefs.getBoolean("bili_close_ads", true),
                value -> { prefs.edit().putBoolean("bili_close_ads", value).apply(); refresh(); }, true);
        feature(options, "取消自动进直播", "独立开关，默认关闭", () -> "同时识别“自动进入直播间”和下方“取消”时，点击取消。\n\n此开关独立于“自动跳过”和“AI 强化模式”。手动进入的正常直播间不会因为这个开关被退出。\n\n当前适配你提供的直播预览样式。", prefs.getBoolean("bili_cancel_live", false),
                value -> { prefs.edit().putBoolean("bili_cancel_live", value).apply(); refresh(); }, false);
        new AppDialog.Builder(this).setTitle("哔哩哔哩规则").setView(options).setPositiveButton("完成", null).show();
    }

    private void checkUpdates(boolean manual) {
        if (updateChecker.busy()) { if (manual) Toast.makeText(this, "正在检查更新", Toast.LENGTH_SHORT).show(); return; }
        if (!manual) prefs.edit().putLong("update_checked_at", System.currentTimeMillis()).apply();
        updateChecker.check(version, result -> {
            if (isFinishing() || isDestroyed()) return;
            refresh();
            if (!manual) return;
            if (result.version.isEmpty()) { showHelp("检查更新", result.message); return; }
            AppDialog.Builder dialog = new AppDialog.Builder(this).setTitle(result.message)
                    .setMessage(result.notes).setNegativeButton("稍后", null);
            if (result.newer) dialog.setPositiveButton(result.apkUrl.isEmpty() ? "打开发布页面" : "前往下载", (d, which) -> openWeb(result.apkUrl.isEmpty() ? result.releaseUrl : result.apkUrl));
            if (!result.releaseUrl.isEmpty()) dialog.setNeutralButton("发布页面", (d, which) -> openWeb(result.releaseUrl));
            dialog.show();
        });
        refresh();
    }

    private void openWeb(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (android.content.ActivityNotFoundException error) { Toast.makeText(this, "没有可打开链接的浏览器", Toast.LENGTH_LONG).show(); }
    }

    private void authorizeShizuku() {
        try {
            if (!Shizuku.pingBinder()) { Toast.makeText(this, "Shizuku 未运行，可使用本机无线配对", Toast.LENGTH_LONG).show(); return; }
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(601);
            else { SensorGuardController.get(this).connect(); refresh(); }
        } catch (Exception error) { Toast.makeText(this, "Shizuku 暂不可用", Toast.LENGTH_LONG).show(); }
    }

    private void selectTab(int index) {
        currentTab = Math.max(0, Math.min(2, index));
        for (int i = 0; i < pages.length; i++) {
            boolean selected = i == currentTab;
            pages[i].setVisibility(selected ? View.VISIBLE : View.GONE);
            tabViews[i].setSelected(selected);
            tabViews[i].setBackground(ripple(selected ? SOFT : Color.TRANSPARENT, 16, 0));
            tabLabels[i].setTextColor(selected ? ACCENT : MUTED);
            tabLabels[i].setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
            tabIcons[i].setImageTintList(ColorStateList.valueOf(selected ? ACCENT : MUTED));
        }
        refresh();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("tab", currentTab); state.putBoolean("restoreAfterSettings", restoreAfterSettings);
        super.onSaveInstanceState(state);
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (!restoreAfterSettings) LocalAdbController.get(this).reconnect(false);
        handler.removeCallbacks(updateStatus); handler.post(updateStatus);
        if (prefs.getBoolean("update_on_start", true) && System.currentTimeMillis() - prefs.getLong("update_checked_at", 0) > 86_400_000L) checkUpdates(false);
    }
    @Override protected void onPause() { resumed = false; handler.removeCallbacks(updateStatus); super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacks(updateStatus); if (updateChecker != null) updateChecker.close(); super.onDestroy(); }

    private void refresh() {
        if (status == null || connectionStatus == null || lastTap == null) return;
        String services = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean accessibility = services != null && services.contains(new ComponentName(this, SkipService.class).flattenToString());
        status.setText(accessibility ? "已准备好识别跳过按钮" : "开启后，助手才能自动点击跳过");
        accessBadge.setText(accessibility ? "已开启" : "未开启");
        tintChip(accessBadge, accessibility ? GREEN : AMBER, accessibility ? Color.rgb(234,247,241) : Color.rgb(255,246,231));
        accessButton.setText(accessibility ? "管理无障碍权限" : "开启无障碍服务");
        LocalAdbController local = LocalAdbController.get(this);
        // Wait for any in-flight health check, then restore immediately after returning from Settings.
        if (resumed && restoreAfterSettings && !local.busy()) {
            restoreAfterSettings = false;
            local.reconnect(true);
        }
        connectionBadge.setText(local.ready() ? "已连接" : local.busy() ? "连接中" : local.paired() ? "待恢复" : "待配对");
        tintChip(connectionBadge, local.ready() ? GREEN : ACCENT, local.ready() ? Color.rgb(234,247,241) : SOFT);
        connectionStatus.setText(prefs.getString("local_adb_status", "首次使用请完成本机配对"));
        connectButton.setEnabled(!local.busy()); connectButton.setAlpha(local.busy() ? .55f : 1f);
        connectButton.setText(local.busy() ? "正在连接…" : local.ready() ? "检查 / 恢复连接" : local.paired() ? "一键恢复连接" : "开始首次配对");
        String guard = prefs.getString("sensor_status", "尚未连接");
        boolean protectedNow = guard.contains("已暂停") || guard.contains("保护中");
        guardSummary.setText(!SensorGuardController.get(this).enabled() ? "防摇一摇已关闭" : protectedNow ? "保护中 · 6 秒后自动恢复" :
                (local.ready() || guard.contains("已就绪")) ? "已就绪 · 等待应用启动" : "需在“连接”页完成授权");
        guardSummary.setTextColor(protectedNow || local.ready() ? GREEN : MUTED);
        String action = prefs.getString("last_action", "暂无");
        Matcher match = Pattern.compile("(\\d{2}:\\d{2}:\\d{2})\\s+([A-Za-z0-9_.]+)").matcher(action);
        String recent = "等待下一次开屏广告";
        if (match.find()) {
            String app = "应用";
            try { app = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(match.group(2), 0)).toString(); }
            catch (Exception ignored) { }
            recent = match.group(1) + "  ·  " + (action.contains(BilibiliVisualMatcher.LIVE) ? "已尝试取消自动进入直播间" :
                    action.contains(BilibiliVisualMatcher.AD) ? "已尝试关闭B站广告卡片" : "已尝试跳过" + app);
        }
        recentSummary.setText(recent);
        lastAction.setText(action.equals("暂无") ? "暂无跳过尝试" : action);
        lastVisual.setText("最近画面判断\n" + prefs.getString("last_visual", "暂无") + "\n\n点击后检查\n" + prefs.getString("last_result", "暂无"));
        lastTap.setText(prefs.getBoolean("capture_click", false) ? "等待强化应用中的一次手动点击…" : prefs.getString("last_tap", "暂无手动点击记录"));
        if (biliSummary != null) biliSummary.setText("广告关闭" + (prefs.getBoolean("bili_close_ads", true) ? "开启" : "关闭") + " · 直播取消" + (BilibiliRules.live(this) ? "开启" : "关闭"));
        if (updateSummary != null) updateSummary.setText(updateChecker.busy() ? "正在读取官方 GitHub Releases…" :
                prefs.getString("update_status", "点击检查最新版本") + "\n" + updateChecker.repository());
    }

    private void about() {
        new AppDialog.Builder(this).setTitle("开屏助手 · v" + version).setMessage("让开屏，更轻快。\n\n画面识别在本机进行，不上传屏幕内容。网络用于本机无线调试，以及启用的 GitHub 更新检查。\n\n强化适配库：" + AppProfiles.labels(this) + "。\n\n模型已在电脑训练，手机自动选择可用的推理方式；实际加速设备由手机驱动决定。")
                .setPositiveButton("知道了", null).setNeutralButton("检查更新", (dialog, which) -> checkUpdates(true)).show();
    }
    private void showHelp(String title, String message) {
        AppDialog dialog = new AppDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("知道了", null).create();
        dialog.show();
    }

    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private LinearLayout card(LinearLayout parent) { return card(parent, true); }
    private LinearLayout card(LinearLayout parent, boolean padded) {
        LinearLayout view = column(); view.setBackground(shape(Color.WHITE, 22, BORDER));
        if (padded) view.setPadding(dp(16), dp(12), dp(16), dp(16));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(14);
        parent.addView(view, params); return view;
    }
    private void section(LinearLayout parent, String title) {
        TextView view = text(title, 13, MUTED); view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(dp(4), dp(24), 0, 0); parent.addView(view);
    }
    private LinearLayout labelWithHelp(String title, Supplier<String> help) {
        LinearLayout label = row();
        TextView name = text(title, 15, INK); name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setMaxLines(2); label.addView(name, new LinearLayout.LayoutParams(-2, -2));
        FrameLayout touchTarget = new FrameLayout(this);
        touchTarget.setContentDescription(title + "功能说明"); touchTarget.setFocusable(true);
        touchTarget.setBackground(ripple(Color.TRANSPARENT, 24, 0));
        TextView question = text("?", 12, ACCENT); question.setGravity(Gravity.CENTER);
        question.setTypeface(Typeface.DEFAULT, Typeface.BOLD); question.setBackground(shape(SOFT, 10, 0));
        touchTarget.addView(question, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        touchTarget.setOnClickListener(v -> showHelp(title, help.get()));
        label.addView(touchTarget, new LinearLayout.LayoutParams(dp(40), dp(48)));
        return label;
    }
    private void feature(LinearLayout parent, String title, String caption, Supplier<String> help,
                         boolean initial, Consumer<Boolean> listener, boolean separator) {
        LinearLayout feature = row(); feature.setPadding(dp(16), dp(2), dp(16), dp(2)); feature.setMinimumHeight(dp(caption == null ? 60 : 76));
        LinearLayout description = column(); description.addView(labelWithHelp(title, help));
        if (caption != null) {
            TextView sub = text(caption, 11, MUTED); sub.setPadding(0, 0, 0, dp(8)); description.addView(sub);
        }
        feature.addView(description, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(this); toggle.setShowText(false); toggle.setSwitchMinWidth(dp(44));
        toggle.setContentDescription(title + "开关"); toggle.setChecked(initial);
        toggle.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{ACCENT,Color.rgb(154,162,184)}));
        toggle.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{Color.rgb(206,193,251),Color.rgb(220,224,235)}));
        toggle.setOnCheckedChangeListener((button, checked) -> listener.accept(checked));
        feature.addView(toggle, new LinearLayout.LayoutParams(dp(52), dp(48))); parent.addView(feature);
        if (separator) divider(parent, 16);
    }
    private TextView actionRow(LinearLayout parent, String title, String caption, Supplier<String> help, Runnable action, boolean separator) {
        LinearLayout view = row(); view.setPadding(dp(16), dp(4), dp(16), dp(10));
        view.setBackground(ripple(Color.TRANSPARENT, 20, 0)); view.setFocusable(true); view.setOnClickListener(v -> action.run());
        LinearLayout description = column(); description.addView(labelWithHelp(title, help));
        TextView sub = text(caption, 12, MUTED); description.addView(sub);
        view.addView(description, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = text("›", 26, MUTED); arrow.setGravity(Gravity.CENTER); view.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(48)));
        parent.addView(view); if (separator) divider(parent, 16);
        return sub;
    }
    private void guideStep(LinearLayout parent, String number, String title) {
        LinearLayout view = row(); view.setPadding(0, dp(6), 0, dp(6));
        TextView badge = chip(number, ACCENT, SOFT); view.addView(badge, new LinearLayout.LayoutParams(dp(28), dp(28)));
        TextView description = text(title, 13, MUTED); description.setPadding(dp(10), 0, 0, 0); view.addView(description); parent.addView(view);
    }
    private void divider(LinearLayout parent, int inset) {
        View view = new View(this); view.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1)); params.leftMargin = dp(inset); params.rightMargin = dp(inset); parent.addView(view, params);
    }
    private TextView actionButton(String title, boolean primary, Runnable action) {
        TextView button = text(title, 14, primary ? Color.WHITE : ACCENT);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD); button.setGravity(Gravity.CENTER); button.setFocusable(true);
        button.setBackground(ripple(primary ? ACCENT : SOFT, 14, 0)); button.setOnClickListener(v -> action.run()); return button;
    }
    private TextView chip(String title, int color, int background) {
        TextView view = text(title, 11, color); view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(dp(10), dp(6), dp(10), dp(6)); view.setGravity(Gravity.CENTER); view.setBackground(shape(background, 12, 0)); return view;
    }
    private void tintChip(TextView chip, int color, int background) { chip.setTextColor(color); chip.setBackground(shape(background, 12, 0)); }
    private GradientDrawable shape(int color, int radius, int border) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius));
        if (border != 0) shape.setStroke(dp(1), border); return shape;
    }
    private RippleDrawable ripple(int color, int radius, int border) {
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(28,116,91,211)), shape(color,radius,border), shape(Color.WHITE,radius,0));
    }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setIncludeFontPadding(false); return view;
    }
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + .5f); }

    private void beginRestore() {
        if (Build.VERSION.SDK_INT < 30) { authorizeShizuku(); return; }
        restoreAfterSettings = true;
        if (openWirelessSettings(false)) {
            // The click handler refreshes once before onPause; reconnect only after Settings returns.
            resumed = false;
        } else {
            restoreAfterSettings = false;
            LocalAdbController.get(this).reconnect(true);
        }
    }

    private boolean openWirelessSettings(boolean pairing) {
        try {
            try { startActivity(new Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")); }
            catch (android.content.ActivityNotFoundException | SecurityException directUnavailable) {
                Intent developer = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
                developer.putExtra(":settings:fragment_args_key", "toggle_adb_wireless");
                Bundle arguments = new Bundle();
                arguments.putString(":settings:fragment_args_key", "toggle_adb_wireless");
                developer.putExtra(":settings:show_fragment_args", arguments);
                startActivity(developer);
            }
            Toast.makeText(this, pairing ? "开启无线调试，打开“使用配对码配对设备”" :
                    "开启无线调试后返回助手，即可用保存的授权恢复；授权已撤销时请重新配对", Toast.LENGTH_LONG).show();
            return true;
        } catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
            showHelp("无线调试", "系统没有开放此页面入口。请手动进入开发者选项 → 无线调试，开启后返回助手。搜索不到时可使用手填端口。");
            return false;
        }
    }

    private void beginPairing() {
        restoreAfterSettings = false;
        if (Build.VERSION.SDK_INT < 30) {
            Toast.makeText(this, "本机无线配对需要 Android 11 或更新版本；旧系统可使用 Shizuku 兼容入口", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 702);
            return;
        }
        try {
            LocalAdbController.get(this).startPairingDiscovery();
            openWirelessSettings(true);
        } catch (Exception error) {
            Toast.makeText(this, "无法开始搜索，请使用手填端口入口", Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 702) {
            if (results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) beginPairing();
            else manualEntry(true);
        }
    }

    private void manualEntry(boolean pairing) {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.addView(text(pairing ? "请用分屏保持系统配对码窗口打开。配对端口与无线调试首页的连接端口不同。" :
                "输入系统“无线调试”首页的 IP 地址和端口中的端口号。只连接本机，不需要填写 IP。", 14, Color.GRAY));
        EditText port = new EditText(this); port.setHint(pairing ? "配对端口" : "连接端口"); port.setInputType(2);
        fields.addView(port);
        EditText code = new EditText(this); code.setHint("6 位配对码"); code.setInputType(2);
        if (pairing) fields.addView(code);
        AppDialog dialog = new AppDialog.Builder(this).setTitle(pairing ? "本机手动配对" : "本机手动连接")
                .setView(fields).setNegativeButton("取消", null).setPositiveButton(pairing ? "配对" : "连接", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AppDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int value = Integer.parseInt(port.getText().toString().trim());
                if (value < 1 || value > 65535) throw new IllegalArgumentException();
                String digits = code.getText().toString().trim();
                if (pairing && !digits.matches("[0-9]{6}")) throw new IllegalArgumentException();
                LocalAdbController local = LocalAdbController.get(this);
                if (local.busy()) { Toast.makeText(this, "正在处理，请稍候", Toast.LENGTH_SHORT).show(); return; }
                if (pairing) local.pair(value, digits); else local.connectManual(value);
                dialog.dismiss(); refresh();
            } catch (Exception error) { port.setError("请输入有效端口" + (pairing ? "和 6 位配对码" : "")); }
        }));
        dialog.show();
    }

}
