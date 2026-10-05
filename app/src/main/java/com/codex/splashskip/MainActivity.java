package com.codex.splashskip;

import android.app.Activity;
import android.accessibilityservice.AccessibilityServiceInfo;

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
import android.view.accessibility.AccessibilityManager;
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
import android.widget.ProgressBar;
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
    private TextView status, accessBadge, guardSummary, connectionBadge;
    private TextView connectionStatus, connectButton, autoWifiSummary, recentSummary, lastAction, lastVisual, lastTap;
    private TextView updateSummary, downloadSummary;
    private final TextView[] versionChips = new TextView[3];
    private final TextView[] updateBadges = new TextView[3];
    private UpdateChecker.Result availableUpdate;
    private UpdateChecker updateChecker;
    private ApkUpdater apkUpdater;
    private AppDialog downloadDialog;
    private AppDialog usageDialog;
    private AppDialog updateDialog;
    private Switch updateReminderSwitch;
    private UpdateChecker.Result pendingUpdate;
    private boolean pendingUpdateManual;
    private String promptedUpdateVersion="";
    private TextView downloadStatus;
    private ProgressBar downloadProgress;
    private boolean installAfterSettings, downloadShown, downloadCompletionPresented;
    private final ScrollView[] pages = new ScrollView[3];
    private final LinearLayout[] tabViews = new LinearLayout[3];
    private final TextView[] tabLabels = new TextView[3];
    private final ImageView[] tabIcons = new ImageView[3];
    private int currentTab;
    private boolean restoreAfterSettings, resumed;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable updateStatus = new Runnable() {
        @Override public void run() { refresh(); presentPendingUpdate(); handler.postDelayed(this, 1000); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        restoreAfterSettings = saved != null && saved.getBoolean("restoreAfterSettings", false);
        updateChecker = new UpdateChecker(this);
        apkUpdater=ApkUpdater.get(this);
        installAfterSettings=saved!=null && saved.getBoolean("installAfterSettings");
        downloadShown=saved!=null && saved.getBoolean("downloadShown");
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception error) { version = "0.6.0"; }
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
        LinearLayout versionArea = column(); versionArea.setGravity(Gravity.END);
        TextView versionChip = chip("v" + version, ACCENT, SOFT);
        versionChip.setContentDescription("版本 " + version + "，点击查看关于");
        versionChip.setOnClickListener(v -> openHeaderUpdate());
        versionArea.addView(versionChip, new LinearLayout.LayoutParams(-2, dp(40)));
        TextView updateBadge = chip("有更新", Color.WHITE, ACCENT);
        updateBadge.setTextSize(10); updateBadge.setPadding(dp(8),0,dp(8),0);
        updateBadge.setVisibility(View.GONE); updateBadge.setOnClickListener(v -> openHeaderUpdate());
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2,dp(22)); badgeParams.topMargin=dp(4);
        versionArea.addView(updateBadge,badgeParams);
        versionChips[index]=versionChip; updateBadges[index]=updateBadge;
        header.addView(versionArea, new LinearLayout.LayoutParams(-2,-2));
        content.addView(header, new LinearLayout.LayoutParams(-1, -2));
        return content;
    }

    private void buildHome() {
        LinearLayout content = page(0, "开屏助手", "让开屏，更轻快");
        LinearLayout access = card(content);
        access.setPadding(dp(16),dp(8),dp(16),dp(10));
        access.setBackground(ripple(Color.WHITE,22,BORDER));access.setFocusable(true);
        access.setContentDescription("管理无障碍服务");
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        LinearLayout accessTitle = row();
        accessTitle.addView(labelWithHelp("无障碍服务", () -> "开启后，助手才能读取跳过控件和父子关系，并通过系统无障碍执行点击。\n\n此权限用于跳过开屏广告和处理你启用的应用规则。系统关闭服务时，请在设置中重新开启。"), new LinearLayout.LayoutParams(0, -2, 1));
        accessBadge = chip("", GREEN, Color.rgb(234, 247, 241)); accessTitle.addView(accessBadge);
        TextView accessArrow=text("›",24,MUTED);accessArrow.setGravity(Gravity.CENTER);
        accessTitle.addView(accessArrow,new LinearLayout.LayoutParams(dp(20),dp(48)));
        access.addView(accessTitle);
        status = text("", 12, MUTED);status.setSingleLine(true);status.setEllipsize(TextUtils.TruncateAt.END);
        status.setPadding(0,0,0,dp(2));access.addView(status);

        LinearLayout features = card(content, false);
        TextView featureTitle=text("功能设置",12,MUTED);featureTitle.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        featureTitle.setPadding(dp(16),dp(12),dp(16),dp(4));features.addView(featureTitle);
        feature(features, "自动跳过", null, () -> "应用打开后的短时间内，寻找真正的“跳过”控件并点击。\n\n普通应用使用无障碍控件识别；开启 AI 强化模式后，也会检测未加入特征库的应用开屏。无法确认时会放弃点击。",
                prefs.getBoolean("enabled", true), value -> prefs.edit().putBoolean("enabled", value).apply(), true,true);
        feature(features, "严格识别", null, () -> "优先选择名称、计时和广告语境明确的跳过控件，减少误点。\n\n开启后，文字或控件信息不明确的广告可能会被放过。",
                prefs.getBoolean("strict", true), value -> prefs.edit().putBoolean("strict", value).apply(), true,true);
        feature(features, "AI 强化模式", "父子控件 · 应用规则 " + AppProfiles.all(this).size() + " 款", this::recognitionHelp,
                AppProfiles.enabled(this), value -> prefs.edit().putBoolean("ai_enhanced", value).apply(), true,true,this::adaptedApps);
        feature(features, "防摇一摇", null, () -> "每次进入普通第三方应用，包括从后台切回，临时暂停该应用的运动传感器。返回同一应用会重新开始 6 秒计时，到期自动恢复。\n\n需要在“连接”页完成本机连接，或使用已授权的 Shizuku。期间该应用的重力感应、指南针也会暂停。\n\n这个开关与自动跳过独立。\n\n当前状态：" + prefs.getString("sensor_status", "尚未连接"),
                SensorGuardController.get(this).enabled(), value -> { SensorGuardController.get(this).setEnabled(value); refresh(); }, false,true);
        guardSummary = text("", 11, MUTED);guardSummary.setSingleLine(true);guardSummary.setEllipsize(TextUtils.TruncateAt.END);
        guardSummary.setPadding(dp(16), 0, dp(16), dp(12)); features.addView(guardSummary);

        LinearLayout appRules = card(content, false);
        actionRow(appRules,"应用规则","识别设置与独立开关",() -> "这里可设置“视觉补充识别”，默认关闭。关闭时只读取父子控件，不截图；开启后控件不足时补充视觉判断。\n\n应用规则与独立开关统一放在这里，功能名称后的问号提供说明。",this::appRulesSettings,false);
        LinearLayout footer=row();footer.setGravity(Gravity.CENTER);footer.setPadding(0,dp(4),0,0);
        footer.addView(text("本机处理 · 不上传屏幕",11,MUTED));
        TextView notice=text("使用须知",11,ACCENT);notice.setGravity(Gravity.CENTER);
        notice.setPadding(dp(12),0,dp(4),0);notice.setMinHeight(dp(48));notice.setFocusable(true);
        notice.setContentDescription("查看使用须知");notice.setBackground(ripple(Color.TRANSPARENT,12,0));
        notice.setOnClickListener(v -> showUsageNotice(false));footer.addView(notice);content.addView(footer);
    }

    private void buildConnection() {
        LinearLayout content = page(1, "本机连接", "授权保存，随时恢复");
        LinearLayout connection = card(content);
        LinearLayout title = row();
        title.addView(labelWithHelp("一键连接", () -> "首次使用先完成无线配对，以后可使用保存的授权恢复连接。\n\n启用 Wi-Fi 自动连接并完成一次授权后，一键恢复会在助手内开启无线调试并重连。首次配对码、新 Wi-Fi 的系统确认或撤销过授权时，仍需在系统页面确认。\n\n连接只在本机进行，无需保持 USB 连接。"), new LinearLayout.LayoutParams(0, -2, 1));
        connectionBadge = chip("", ACCENT, SOFT); title.addView(connectionBadge); connection.addView(title);
        connectionStatus = text("", 13, MUTED); connectionStatus.setMaxLines(5); connectionStatus.setEllipsize(TextUtils.TruncateAt.END);
        connectionStatus.setLineSpacing(dp(2), 1); connectionStatus.setPadding(0, dp(2), 0, dp(18)); connection.addView(connectionStatus);
        connectButton = actionButton("连接 / 恢复", true, () -> {
            LocalAdbController local = LocalAdbController.get(this);
            if (!local.paired()) beginPairing(); else beginRestore();
            refresh();
        });
        connection.addView(connectButton, new LinearLayout.LayoutParams(-1, dp(48)));

        section(content,"自动连接");
        LinearLayout automatic=card(content,false);
        if(Build.VERSION.SDK_INT>=30)feature(automatic,"Wi-Fi 自动连接","自动开启无线调试 · 保存授权重连",() -> "开启后，接入 Wi-Fi 时自动开启无线调试并使用保存的授权连接本机。\n\n需要先完成首次配对。助手会通过已授权的本机连接，申请一次系统设置写入权限（WRITE_SECURE_SETTINGS），用于开启无线调试；通常覆盖更新会保留权限。\n\n新 Wi-Fi 上系统可能要求确认允许调试，这次确认需你亲自完成。手动关闭无线调试后，下次接入 Wi-Fi 或点一键连接才会再次开启。\n\n后台监听依赖助手进程或无障碍服务存活，厂商强制结束应用时需重新打开。关闭此选项后停止自动开启。",
                LocalAdbController.get(this).automaticWifi(),value -> {LocalAdbController.get(this).setAutomaticWifi(value);refresh();},false);
        else automatic.addView(labelWithHelp("Wi-Fi 自动连接",() -> "Android 11 及更新版本支持自动开启无线调试。旧系统可使用已授权的 Shizuku 兼容入口。"));
        autoWifiSummary=text("",12,MUTED);autoWifiSummary.setLineSpacing(dp(2),1);
        autoWifiSummary.setMaxLines(2);autoWifiSummary.setEllipsize(TextUtils.TruncateAt.END);
        autoWifiSummary.setPadding(dp(16),0,dp(16),dp(14));automatic.addView(autoWifiSummary);

        section(content, "连接方式");
        LinearLayout methods = card(content, false);
        actionRow(methods,"手动开启无线调试","自动失败时使用",() -> "自动开启或重连失败时，点此入口进入系统无线调试。\n\n手动打开开关，允许系统显示的网络确认，然后返回助手，会立即尝试恢复保存的授权。首次配对仍需输入系统配对码。",this::manualWirelessDebugging,true);
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
        recentSummary=text("",13,INK);recentSummary.setLineSpacing(dp(3),1);latest.addView(recentSummary);
        lastAction = text("", 13, MUTED); lastAction.setLineSpacing(dp(3), 1); lastAction.setPadding(0, dp(4), 0, dp(12)); latest.addView(lastAction);
        divider(latest, 0);
        latest.addView(labelWithHelp("识别结果", () -> "显示当前识别模式和最近判断。控件模式直接读取父子关系；启用视觉补充后也会记录模型或特征匹配结果。\n\n只有符合识别条件的候选才会尝试点击。提交点击或按钮消失不能单独证明广告已关闭。"));
        lastVisual = text("", 13, MUTED); lastVisual.setLineSpacing(dp(4), 1); lastVisual.setPadding(0, dp(4), 0, dp(4)); latest.addView(lastVisual);
        section(content, "诊断工具");
        LinearLayout bounds=card(content,false);
        feature(bounds,"自动记录控件边框","打开其他应用时记录 · 保存在本机",() -> "打开其他应用时自动保存当前控件边框、父子关系、类名、资源 ID 和识别结果，回到助手后仍可查看。\n\n只记录控件图，不截图、不上传；不保存输入框内容或整页正文。最多 64 条、保留 7 天，总容量约 1.5 MB。复杂页面可能只记录部分控件，会明确标注。\n\n关闭自动跳过后仍可在应用启动时只读记录。历史边框仅供诊断，不用于点击。",prefs.getBoolean("native_bounds_auto",true),value -> prefs.edit().putBoolean("native_bounds_auto",value).apply(),true);
        actionRow(bounds,"查看自动边框","点选边框 · 查看控件 · 导出记录",null,this::boundsSettings,false);
        LinearLayout tools = card(content, false);
        actionRow(tools, "复制日志", "保存在手机本地", () -> "复制本应用的诊断日志，包含扫描、识别、点击结果与连接状态，方便分析无法识别或无法点击的样式。\n\n复制到剪贴板，不会自动发送或上传。", () -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("开屏助手诊断", Diagnostics.read(this)));
            Toast.makeText(this, "诊断日志已复制", Toast.LENGTH_SHORT).show();
        }, true);
        actionRow(tools, "记录手动点击", "等待下一次点击 · 两分钟内有效", () -> "开启后，在目标应用中亲手点一次跳过按钮。暂缓自动点击，尝试关联刚刚观察到的父子控件；只有启用视觉补充时才记录按钮局部视觉特征。\n\n请在特征库里确认对应结果。没有控件事件、点击前特征或目标不明确时只记诊断。两分钟后自动恢复，再次点击也可取消。", () -> {
            boolean capturing = prefs.getBoolean("capture_click", false);
            prefs.edit().putBoolean("capture_click", !capturing).putLong("capture_until",System.currentTimeMillis()+120000).apply(); refresh();
            Toast.makeText(this, capturing ? "已取消记录" : "请在目标应用中手动点一次跳过", Toast.LENGTH_SHORT).show();
        }, false);
        actionRow(tools,"控件与视觉特征库","父子结构 · 点击结果 · 训练记录",() -> "按当前控件树寻找候选，记录父子关系和点击结果，供统一训练。开启视觉补充时也会记录按钮局部特征。\n\n不按历史位置点击。可在这里纠正结果、导出或清除。",this::buttonMemorySettings,true);
        LinearLayout taps = card(content);
        taps.addView(labelWithHelp("手动点击记录", () -> "显示等待状态或最近收到的控件事件。\n\n当前已适配：" + AppProfiles.labels(this) + "。事件内容仅在本机记录。"));
        lastTap = text("", 13, MUTED); lastTap.setLineSpacing(dp(3), 1); lastTap.setPadding(0, dp(3), 0, 0); taps.addView(lastTap);

        section(content, "版本与更新");
        LinearLayout updates = card(content);
        updates.addView(labelWithHelp("应用内更新", () -> "发现新版本后，点“立即更新”，助手会直接下载安装包并显示进度；校验通过后点“立即安装”，打开系统安装确认。整个更新流程无需打开网页。\n\n读取官方 GitHub 发布的版本和安装包。开启“更新加速”后优先使用内置公共线路，失败时自动切换；支持分段下载、断点续传和速度显示。安装前校验文件哈希、版本和应用签名。\n\n不上传屏幕或诊断日志，不支持自定义更新源。"));
        updateSummary = text("", 12, MUTED); updateSummary.setLineSpacing(dp(3), 1); updates.addView(updateSummary);
        TextView check = actionButton("检查更新 · v" + version, true, () -> {if(apkUpdater.busy())showDownloadDialog();else checkUpdates(true);});
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(-1, dp(44)); checkParams.topMargin = dp(14); updates.addView(check, checkParams);
        LinearLayout updateOptions = card(content, false);
        downloadSummary=actionRow(updateOptions,"下载与安装","暂无下载任务",() -> "查看下载进度、失败后重试，或打开已下载安装包的系统安装界面。下载完成后无需再次访问 GitHub 即可安装。",() -> {
            if(apkUpdater.busy() || apkUpdater.ready() || !apkUpdater.message().isEmpty())showDownloadDialog();
            else checkUpdates(true);
        },true);
        actionRow(updateOptions, "查看源码", ProjectNotice.CAPTION, () -> ProjectNotice.SUMMARY, () -> openWeb(ProjectNotice.SOURCE_URL), true);
        feature(updateOptions, "更新加速", "内置下载线路 · 默认开启", () -> "用于本应用的版本检查和安装包下载。开启后优先使用两条公共下载线路，失败时回退到官方直连，无需另装加速器。\n\n公共线路提供者会收到连接的 IP 地址和公开版本请求；不发送屏幕、诊断日志或配对密钥。关闭后仅使用官方直连。\n\n公共线路可能限速或暂时失效，下载速度取决于当前网络。",
                prefs.getBoolean("update_acceleration", true), value -> prefs.edit().putBoolean("update_acceleration", value).apply(), true);
        feature(updateOptions, "前台检查更新", "打开或返回助手时检查", () -> "开启后，在应用启动或回到前台时从官方 GitHub 仓库检查更新。30 秒内重复返回时不重复请求；检查失败后，下次返回前台可重试。\n\n发现新版本后，右上角会显示“有更新”。关闭自动检查后仍可手动检查。", prefs.getBoolean("update_on_start", true),
                value -> prefs.edit().putBoolean("update_on_start", value).apply(), true);
        updateReminderSwitch=feature(updateOptions,"新版本弹窗提醒","发现更新自动提示，可随时关闭",() -> "开启后，助手在前台发现新版本时会弹出更新提示，已缓存的新版本也会在打开助手时提醒。\n\n弹窗中的“再也不提示”会关闭后续自动更新弹窗，覆盖更新后也保留。这里可重新开启；关闭提醒后右上角仍会显示“有更新”，可手动查看、下载和安装。",
                prefs.getBoolean("update_prompt",true),value -> prefs.edit().putBoolean("update_prompt",value).apply(),false);
    }

    private void boundsSettings() {
        LinearLayout options=column();
        actionRow(options,"最近的控件边框","按时间查看其他应用的记录",null,this::reviewBounds,true);
        actionRow(options,"导出边框记录","保存 JSON，便于分析漏识别原因",null,() -> {
            Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"SplashSkip-bounds.json");
            try{startActivityForResult(intent,624);}catch(Exception error){Toast.makeText(this,"未找到文件保存入口",Toast.LENGTH_SHORT).show();}
        },true);
        actionRow(options,"清除边框记录","删除手机上保存的控件图",null,() ->
            new AppDialog.Builder(this).setTitle("清除自动边框").setMessage("删除手机上保存的全部控件边框记录？")
                .setNegativeButton("取消",null).setPositiveButton("清除",(d,w) -> new Thread(() -> {
                    String message;
                    try{NativeBoundsArchive.get(this).clear();message="边框记录已清除";}catch(RuntimeException error){message="清除失败，请稍后重试";}
                    final String status=message;runOnUiThread(() -> {if(!isFinishing()&&!isDestroyed())Toast.makeText(this,status,Toast.LENGTH_SHORT).show();});
                },"bounds-clear").start()).show(),false);
        new AppDialog.Builder(this).setTitle("自动控件边框").setView(options).setPositiveButton("完成",null).show();
    }
    private void reviewBounds() {
        new Thread(() -> {
            try {
                java.util.List<org.json.JSONObject> records=NativeBoundsArchive.get(this).recent();
                runOnUiThread(() -> {
                    if(isFinishing()||isDestroyed())return;
                    if(records.isEmpty()){showHelp("自动控件边框","还没有记录。保持无障碍开启，打开其他应用后再回到这里查看。");return;}
                    LinearLayout rows=column();
                    TextView hint=text("点记录查看边框与点击关系。绿色可点击，紫色关闭/跳过；支持放大、筛选和父子节点查看。",12,MUTED);
                    hint.setPadding(dp(12),dp(6),dp(12),dp(10));rows.addView(hint);
                    for(org.json.JSONObject record:records) {
                        String time=new java.text.SimpleDateFormat("MM-dd HH:mm:ss",java.util.Locale.getDefault()).format(new java.util.Date(record.optLong("captured_wall_time")));
                        String title=time+" · "+boundsAppName(record.optString("package"));
                        String detail=record.optInt("width")+"×"+record.optInt("height")+" · "+record.optInt("node_count")+" 个控件 · "+boundsCompleteness(record);
                        actionRow(rows,title,detail,null,() -> showBounds(record.optString("id")),true);
                    }
                    new AppDialog.Builder(this).setTitle("最近 "+records.size()+" 条边框").setView(rows).setPositiveButton("返回",null).show();
                });
            }catch(RuntimeException error){runOnUiThread(() -> {if(!isFinishing()&&!isDestroyed())showHelp("读取失败","暂时无法读取边框记录，请稍后重试。");});}
        },"bounds-list").start();
    }
    private String boundsAppName(String pkg) {
        try{return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg,0)).toString();}
        catch(Exception ignored){return pkg;}
    }
    private String boundsCompleteness(org.json.JSONObject frame) {
        return frame.optBoolean("scope_only")?"仅广告区域 · "+(frame.optBoolean("scope_complete")?"完整":"部分"):frame.optBoolean("tree_complete")?"全页完整":"部分控件";
    }
    private void showBounds(String id) {
        new Thread(() -> {
            try {
                org.json.JSONObject frame=NativeBoundsArchive.get(this).frame(id);
                runOnUiThread(() -> {
                    if(isFinishing()||isDestroyed())return;
                    if(frame==null){showHelp("记录已过期","这条记录已被清除或轮换，请查看其他记录。");return;}
                    new NativeBoundsReview(this,frame,boundsAppName(frame.optString("package"))).show();
                });
            }catch(RuntimeException error){runOnUiThread(() -> {if(!isFinishing()&&!isDestroyed())showHelp("读取失败","暂时无法读取这条记录，请稍后重试。");});}
        },"bounds-preview").start();
    }

    private void buttonMemorySettings() {
        JointLearningStore store=JointLearningStore.get(this);
        LinearLayout options=column();
        TextView summary=text(store.summary(),13,INK);summary.setPadding(dp(16),dp(8),dp(16),dp(12));options.addView(summary);
        feature(options,"记录联合特征","父子控件 · 点击结果",() -> "记录当前按钮的父子层级、可点击关系与控件标识摘要。只有开启视觉补充时才添加按钮局部灰度特征。记录只保存在手机本地，最多 256 条、保留 30 天；旧坐标不会用于后续点击。\n\n提交点击只代表尝试，请根据实际结果纠正记录。结果未知不进入训练。关闭后停止新增样本。",prefs.getBoolean("joint_learning",true),value -> prefs.edit().putBoolean("joint_learning",value).apply(),true);
        actionRow(options,"查看与纠正结果","成功 / 无效 / 误触 / 待确认",() -> "按时间、应用和动作找到对应记录，纠正自动判断。不要把没有看到结果的记录标为成功。",this::reviewJointRecords,true);
        actionRow(options,"导出训练记录","保存到你选择的本地文件",() -> "导出 JSON，供电脑统一训练候选排序模型。文件包含应用包名、时间和控件摘要；不会自动发送。防误触判断仍按当前页面重新进行。",() -> {
            Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"SplashSkip-controls.json");
            try{startActivityForResult(save,623);}catch(Exception error){Toast.makeText(this,"未找到文件保存入口",Toast.LENGTH_SHORT).show();}
        },true);
        actionRow(options,"清除本地特征","联合记录与近期按钮记忆",() -> "删除手机里的记录与记忆，已手动导出的文件需在文件管理器中删除。",() -> {
            new AppDialog.Builder(this).setTitle("清除特征记录").setMessage("删除手机上所有联合记录和近期按钮记忆？")
                .setNegativeButton("取消",null).setPositiveButton("清除",(d,w)->{store.clear();RecentButtonMemory.get(this).clear();Toast.makeText(this,"本地特征已清除",Toast.LENGTH_SHORT).show();}).show();
        },false);
        new AppDialog.Builder(this).setTitle("控件与视觉特征库").setView(options).setPositiveButton("完成",null).show();
    }
    private void reviewJointRecords() {
        LinearLayout rows=column();java.util.List<org.json.JSONObject> records=JointLearningStore.get(this).recent();
        if(records.isEmpty()){showHelp("联合记录","还没有采集到点击样本。以后识别并提交动作时会在本机记录，不需要重复提供随机广告截图。");return;}
        int count=0;
        for(org.json.JSONObject r:records) {
            if(++count>40)break;
            String time=new java.text.SimpleDateFormat("MM-dd HH:mm:ss",java.util.Locale.getDefault()).format(new java.util.Date(r.optLong("time")));
            String action=UiControlPolicy.SKIP.equals(r.optString("action"))?"跳过":UiControlPolicy.CLOSE.equals(r.optString("action"))?"关闭":"关闭广告";
            String title=time+" · "+action;
            actionRow(rows,title,r.optString("package")+" · "+jointLabel(r.optString("label")),null,() -> correctJointRecord(r,title),true);
        }
        new AppDialog.Builder(this).setTitle("最近 "+Math.min(40,records.size())+" 条记录").setView(rows).setPositiveButton("返回",null).show();
    }
    private String jointLabel(String label) {
        return label.equals("success")?"已确认消失/成功":label.equals("no_effect")?"无效":label.equals("mistouch")?"误触":"待确认";
    }
    private void correctJointRecord(org.json.JSONObject record,String title) {
        if(!record.optBoolean("attempted",true)){showHelp(title,"这是未点击的控件候选，仅供分析漏识别原因，不能标为点击成功。可使用“记录手动点击”补充真实点击结果。");return;}
        LinearLayout choices=column();String[] labels={"success","no_effect","mistouch","unknown"};
        for(String label:labels)actionRow(choices,jointLabel(label),"",null,() -> {
            new AppDialog.Builder(this).setTitle("确认记录结果").setMessage(title+"\n"+record.optString("package")+"\n\n将这条记录标为“"+jointLabel(label)+"”？")
                .setNegativeButton("取消",null).setPositiveButton("确认",(d,w)->{JointLearningStore.get(this).outcome(record.optString("id"),label,"user");Toast.makeText(this,"已更新这条记录",Toast.LENGTH_SHORT).show();}).show();
        },true);
        new AppDialog.Builder(this).setTitle(title).setView(choices).setPositiveButton("返回",null).show();
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if((request!=623 && request!=624) || result!=RESULT_OK || data==null || data.getData()==null)return;
        Uri destination=data.getData();
        new Thread(()->{
            String message;
            try(java.io.OutputStream out=getContentResolver().openOutputStream(destination,"wt")) {
                if(out==null)throw new java.io.IOException("No output");
                out.write(request==624?NativeBoundsArchive.get(this).exportBytes():JointLearningStore.get(this).exportBytes());
                message=request==624?"边框记录已保存":"训练记录已保存";
            }catch(Exception error){message="导出失败，请重新选择保存位置";}
            final String status=message;runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())Toast.makeText(this,status,Toast.LENGTH_LONG).show();});
        },"joint-export").start();
    }
    private void appRulesSettings() {
        LinearLayout options=column();
        feature(options,"视觉补充识别","控件不足时补充 · 默认关闭",() -> "关闭时，只读取当前界面的父子控件并点击，不截图，也不启动视觉模型。\n\n开启时仍优先直接读取控件，控件不足时才截图，在本机识别“跳过／关闭”文字和按钮线索。自绘按钮或没有文本的“×”可能需要视觉补充；目标被遮挡或无法确认时会放过。\n\n不依赖广告宣传图片或历史坐标，画面不上传。",RecognitionMode.visuals(this),value -> {RecognitionMode.setVisuals(this,value);refresh();},true);
        feature(options,"通用页面广告关闭","跨应用识别明确的关闭广告按钮",() -> "从当前控件文本、父子关系和广告语境中寻找“关闭广告”按钮，不匹配宣传图片或固定位置。\n\n普通推荐叉号、静音图标和没有明确关闭目标的页面不点击。"+recognitionRuleNote(),prefs.getBoolean("generic_ad_close",true),value -> prefs.edit().putBoolean("generic_ad_close",value).apply(),true);
        addProfileRows(options);
        new AppDialog.Builder(this).setTitle("应用规则").setView(options).setPositiveButton("完成",null).show();
    }
    private void adaptedApps() {
        LinearLayout options=column();
        addProfileRows(options);
        new AppDialog.Builder(this).setTitle("适配应用").setView(options).setPositiveButton("完成",null).show();
    }
    private void addProfileRows(LinearLayout options) {
        int count=AppProfiles.all(this).size(),index=0;
        TextView summary=text("已适配 "+count+" 款应用",12,MUTED);summary.setPadding(dp(16),0,dp(16),dp(8));options.addView(summary);
        for(AppProfiles.Profile profile:AppProfiles.all(this).values()) {
            String caption="bilibili".equals(profile.inAppRules)?"广告卡片 · 自动进入直播间":
                    "netdisk".equals(profile.inAppRules)?"优惠券弹窗 · 截图客服卡片":
                    "huya".equals(profile.inAppRules)?"开屏跳过 · 横屏推广 · 浮层关闭":
                    "tencent".equals(profile.inAppRules)?"开屏跳过 · 推广弹窗 · 视频广告":"开屏跳过 · 强化识别";
            actionRow(options,profile.label,caption,() -> ruleDescription(profile),() -> profileSettings(profile),++index<count);
        }
        if(count==0) {
            TextView empty=text("适配库暂为空，普通应用仍可使用控件识别。",13,MUTED);
            empty.setPadding(dp(16),dp(8),dp(16),dp(8));options.addView(empty);
        }
    }
    private String ruleDescription(AppProfiles.Profile profile) {
        String rules;
        if("bilibili".equals(profile.inAppRules))rules="广告卡片关闭受自动跳过和 AI 强化模式控制。取消自动进入直播间有独立开关，默认关闭，正常观看直播不受影响。";
        else if("netdisk".equals(profile.inAppRules))rules="分别控制优惠券弹窗和截图后出现的客服卡片。需要自动跳过和 AI 强化模式。";
        else if("huya".equals(profile.inAppRules))rules="处理开屏跳过、推广卡片与已确认的浮层关闭。按当前控件和页面线索重新定位，两个开关独立设置。需要自动跳过和 AI 强化模式。";
        else if("tencent".equals(profile.inAppRules))rules="分别设置开屏跳过、预约推广、页内视频与信息流广告。电视剧推荐、追剧卡片及没有明确关闭按钮的广告不点击。需要自动跳过和 AI 强化模式。";
        else if("mobile".equals(profile.inAppRules))rules="处理已确认的轮播推广弹窗，重新寻找当前关闭目标，不匹配广告正文或固定位置。需要自动跳过和 AI 强化模式。";
        else rules="强化识别跳过按钮"+(profile.popup!=null?"和已确认的活动弹窗":"")+"，随首页的自动跳过和 AI 强化模式开关启用。";
        return rules+recognitionRuleNote();
    }

    private String recognitionHelp() {
        String mode=RecognitionMode.visuals(this)?
                "当前已开启视觉补充：优先读取父子控件，控件不足时才截图，在本机识别“跳过／关闭”文字和按钮线索。":
                "当前只读取父子控件，不截图。结合按钮文字、可点击父节点和同组广告线索，直接点击当前控件；无需等待视觉模型。";
        return mode+"\n\n不按广告宣传图片、历史位置或节点编号点击。普通推荐、静音叉号、被遮挡或无法确认的目标会放过。\n\n自绘按钮、没有文本的“×”可能未暴露控件，可在“应用规则”开启视觉补充。特殊规则与独立开关也放在那里。";
    }

    private String recognitionRuleNote() {
        return RecognitionMode.visuals(this)?
                "\n\n优先读取父子控件，控件不足时才使用本机视觉补充；每次重新定位，无法确认时放过。":
                "\n\n当前仅使用父子控件，不截图。应用未暴露关闭控件时会放过；自绘按钮或没有文本的“×”可能需要开启视觉补充。";
    }
    private void profileSettings(AppProfiles.Profile profile) {
        if("bilibili".equals(profile.inAppRules))bilibiliSettings();
        else if("netdisk".equals(profile.inAppRules))netdiskSettings();
        else if("huya".equals(profile.inAppRules))sceneSettings(profile.label,new String[]{"跳过开屏与关闭推广卡片","关闭右侧鱼种浮层"},new String[]{"huya_ads","huya_fish"});
        else if("tencent".equals(profile.inAppRules))sceneSettings(profile.label,new String[]{"跳过开屏互动广告","关闭预约推广弹窗","关闭页内视频广告","关闭信息流广告"},new String[]{"tencent_splash","tencent_banner","tencent_video","tencent_feed"});
        else if("mobile".equals(profile.inAppRules))sceneSettings(profile.label,new String[]{"关闭轮播推广弹窗"},new String[]{"mobile_promo"});
        else showHelp(profile.label+"适配",ruleDescription(profile));
    }
    private void bilibiliSettings() {
        LinearLayout options = column(); options.setPadding(dp(8), 0, dp(8), dp(12));
        feature(options, "关闭广告卡片", "处理视频下方弹出的推广卡片", () -> "确认当前推广卡片和真实关闭目标，优先直接点击应用暴露的关闭控件。\n\n需要开启“自动跳过”和“AI 强化模式”。"+recognitionRuleNote(), prefs.getBoolean("bili_close_ads", true),
                value -> { prefs.edit().putBoolean("bili_close_ads", value).apply(); refresh(); }, true);
        feature(options, "取消自动进直播", "独立开关，默认关闭", () -> "同时识别“自动进入直播间”和“取消”目标时，点击取消。\n\n此开关独立于“自动跳过”和“AI 强化模式”。手动进入的正常直播间不会因为这个开关被退出。"+recognitionRuleNote(), prefs.getBoolean("bili_cancel_live", false),
                value -> { prefs.edit().putBoolean("bili_cancel_live", value).apply(); refresh(); }, false);
        new AppDialog.Builder(this).setTitle("哔哩哔哩规则").setView(options).setPositiveButton("完成", null).show();
    }
    private void netdiskSettings() {
        sceneSettings("百度网盘",new String[]{"关闭优惠券弹窗","关闭截图客服卡片"},new String[]{"netdisk_promo","netdisk_screenshot"});
    }
    private void sceneSettings(String title,String[] labels,String[] keys) {
        LinearLayout options=column();
        for(int i=0;i<labels.length;i++) {
            final String key=keys[i];
            feature(options,labels[i],null,() -> "只关闭对应应用中已确认的场景。优先直接点击当前可访问的控件，提交点击只代表尝试。需要自动跳过和 AI 强化模式。"+recognitionRuleNote(),
                    prefs.getBoolean(key,true),value -> prefs.edit().putBoolean(key,value).apply(),i<labels.length-1);
        }
        new AppDialog.Builder(this).setTitle(title+"规则").setView(options).setPositiveButton("完成",null).show();
    }

    private void checkUpdates(boolean manual) {
        if (updateChecker.busy()) { if (manual) Toast.makeText(this, "正在检查更新", Toast.LENGTH_SHORT).show(); return; }
        updateChecker.check(version, result -> {
            if (isFinishing() || isDestroyed()) return;
            refresh();
            if(!resumed) {pendingUpdate=result;pendingUpdateManual=manual;return;}
            presentUpdate(result,manual);
        });
        refresh();
    }
    private void openHeaderUpdate() {
        UpdateChecker.Result update=updateChecker.cached(version);
        if(update!=null && update.newer) {
            if(apkUpdater.busy() || apkUpdater.ready())showDownloadDialog();
            else presentUpdate(update,true);
        } else about();
    }
    private void refreshUpdateBadges() {
        availableUpdate=updateChecker.cached(version);
        boolean newer=availableUpdate!=null && availableUpdate.newer;
        for(int i=0;i<updateBadges.length;i++) {
            if(updateBadges[i]==null || versionChips[i]==null)continue;
            updateBadges[i].setVisibility(newer?View.VISIBLE:View.GONE);
            updateBadges[i].setContentDescription(newer?"有更新，最新版本 "+availableUpdate.version+"，点击查看更新":"");
            versionChips[i].setContentDescription("版本 "+version+(newer?"，有更新，点击查看更新":"，点击查看关于"));
        }
    }
    private void disableUpdatePrompts() {
        prefs.edit().putBoolean("update_prompt",false).apply();
        if(updateReminderSwitch!=null)updateReminderSwitch.setChecked(false);
        Toast.makeText(this,"已关闭更新弹窗，仍可手动检查更新",Toast.LENGTH_SHORT).show();
    }
    private void presentUpdate(UpdateChecker.Result result,boolean manual) {
            if(isFinishing() || isDestroyed() || !resumed)return;
            if (!manual && (!result.newer || !prefs.getBoolean("update_prompt",true) || result.version.equals(promptedUpdateVersion))) return;
            if((updateDialog!=null && updateDialog.isShowing()) || (!manual && (apkUpdater.busy() ||
                    downloadDialog!=null && downloadDialog.isShowing() || usageDialog!=null && usageDialog.isShowing() || installAfterSettings))) {
                pendingUpdate=result;pendingUpdateManual=manual;return;
            }
            if (result.version.isEmpty()) { showHelp("应用内更新", result.message); return; }
            if (!result.newer) {
                showHelp("应用内更新", result.message + "\n\n发现新版本后，会在本应用中下载并打开系统安装确认。");
                return;
            }
            if (result.apkUrl.isEmpty() || !result.sha256.matches("[a-f0-9]{64}") || result.size <= 0 || result.size > 100L*1024*1024) {
                updateDialog=new AppDialog.Builder(this).setTitle("新版本安装包暂未就绪")
                        .setMessage("检测到 " + result.version + "，但官方尚未提供完整、可验证的安装包信息。请稍后重新检查；更新会在助手中下载。")
                        .setNegativeButton("稍后", null).setNeutralButton("再也不提示",(d,which) -> disableUpdatePrompts())
                        .setPositiveButton("重新检查", (d, which) -> checkUpdates(true)).create();
                promptedUpdateVersion=result.version;updateDialog.show();
                return;
            }
            AppDialog.Builder dialog = new AppDialog.Builder(this).setTitle(result.message)
                    .setMessage(result.notes.replaceAll("(?m)^#{1,6}\\s*","").replace("`","") + "\n\n点立即更新，在助手中下载并安装。")
                    .setNegativeButton("稍后", null)
                    .setNeutralButton("再也不提示",(d,which) -> disableUpdatePrompts());
            dialog.setPositiveButton("立即更新", (d, which) -> {
                downloadCompletionPresented=false;apkUpdater.start(result);showDownloadDialog();
            });
            promptedUpdateVersion=result.version;updateDialog=dialog.create();updateDialog.show();
    }
    private void presentPendingUpdate() {
        if(pendingUpdate==null || !resumed || apkUpdater.busy() || installAfterSettings ||
                downloadDialog!=null && downloadDialog.isShowing() || updateDialog!=null && updateDialog.isShowing() ||
                usageDialog!=null && usageDialog.isShowing())return;
        boolean manual=pendingUpdateManual;
        UpdateChecker.Result waiting=manual?pendingUpdate:updateChecker.cached(version);
        pendingUpdate=null;
        if(waiting!=null)presentUpdate(waiting,manual);
    }

    private void showDownloadDialog() {
        if(isFinishing() || isDestroyed())return;
        if(downloadDialog!=null && downloadDialog.isShowing()){updateDownloadUI();return;}
        downloadShown=true;
        LinearLayout content=column();
        downloadStatus=text(apkUpdater.message(),14,MUTED);downloadStatus.setPadding(0,dp(8),0,dp(16));content.addView(downloadStatus);
        downloadProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);downloadProgress.setMax(100);
        downloadProgress.setProgressTintList(ColorStateList.valueOf(ACCENT));content.addView(downloadProgress,new LinearLayout.LayoutParams(-1,dp(8)));
        TextView hint=text("应用内下载 · 可续传 · 校验后安装",12,MUTED);hint.setPadding(0,dp(16),0,dp(8));content.addView(hint);
        downloadDialog=new AppDialog.Builder(this).setTitle("下载安装更新").setView(content).setNegativeButton("关闭",null)
                .setNeutralButton("取消下载",null).setPositiveButton("",null).create();
        downloadDialog.setOnShowListener(ignored -> {
            downloadDialog.getButton(AppDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if(apkUpdater.ready())installUpdate();else if(!apkUpdater.busy()){downloadCompletionPresented=false;apkUpdater.retry();}
            });
            downloadDialog.getButton(AppDialog.BUTTON_NEUTRAL).setOnClickListener(v -> apkUpdater.cancel());
            updateDownloadUI();
        });
        downloadDialog.setOnDismissListener(ignored -> downloadShown=false);
        downloadDialog.show();
    }
    private void updateDownloadUI() {
        if(!resumed || isFinishing() || isDestroyed())return;
        if(apkUpdater.ready() && !downloadCompletionPresented) {
            downloadCompletionPresented=true;
            if(downloadDialog==null || !downloadDialog.isShowing()){showDownloadDialog();return;}
        }
        if(downloadDialog==null || !downloadDialog.isShowing())return;
        downloadStatus.setText(apkUpdater.message());downloadProgress.setProgress(apkUpdater.percent());
        TextView action=downloadDialog.getButton(AppDialog.BUTTON_POSITIVE),cancel=downloadDialog.getButton(AppDialog.BUTTON_NEUTRAL);
        action.setText(apkUpdater.ready()?"立即安装":apkUpdater.busy()?"下载中…":"重试下载");
        action.setEnabled(!apkUpdater.busy());action.setAlpha(apkUpdater.busy()?.55f:1);
        cancel.setEnabled(apkUpdater.busy());cancel.setAlpha(apkUpdater.busy()?1:.45f);
    }
    private void installUpdate() {
        if(!apkUpdater.ready())return;
        if(!getPackageManager().canRequestPackageInstalls()) {
            new AppDialog.Builder(this).setTitle("允许安装更新").setMessage("系统需要你允许“开屏助手”安装应用，才能打开 APK 安装界面。开启后返回这里继续安装。")
                    .setNegativeButton("稍后",null).setPositiveButton("打开设置",(d,w) -> {
                        installAfterSettings=true;
                        try {startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())));}
                        catch(android.content.ActivityNotFoundException error){installAfterSettings=false;showHelp("安装更新","系统没有提供授权入口，请在系统应用设置中允许安装应用后重试。");}
                    }).show();return;
        }
        try {
            Uri uri=apkUpdater.uri();
            Intent installer=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            installer.setClipData(ClipData.newRawUri("开屏助手更新",uri));startActivity(installer);
        } catch(Exception error){showHelp("安装更新","无法打开系统安装器，请确认安装权限后重试。");}
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
        state.putBoolean("installAfterSettings",installAfterSettings);state.putBoolean("downloadShown",downloadShown);
        super.onSaveInstanceState(state);
    }
    @Override protected void onResume() {
        super.onResume(); resumed = true;
        apkUpdater.listen(this::updateDownloadUI);
        if(!prefs.getBoolean(UsageNotice.ACKNOWLEDGED,false)){showUsageNotice(true);return;}
        resumeActivityActions();
    }
    private void resumeActivityActions() {
        if(!resumed || isFinishing() || isDestroyed())return;
        if(downloadShown || apkUpdater.busy() || (apkUpdater.ready() && !downloadCompletionPresented))showDownloadDialog();
        if(installAfterSettings) {
            installAfterSettings=false;
            if(getPackageManager().canRequestPackageInstalls()){installUpdate();return;}
            else showHelp("安装更新","尚未允许安装应用。下载的 APK 已保留，允许后可再点立即安装。");
        }
        if (!restoreAfterSettings) LocalAdbController.get(this).reconnect(false);
        handler.removeCallbacks(updateStatus); handler.post(updateStatus);
        if(pendingUpdate!=null) {
            UpdateChecker.Result waiting=pendingUpdate;pendingUpdate=null;presentUpdate(waiting,pendingUpdateManual);
        } else if(prefs.getBoolean("update_on_start",true)) {
            UpdateChecker.Result cached=updateChecker.cached(version);if(cached!=null)presentUpdate(cached,false);
        }
        if (UpdatePolicy.checkOnForeground(prefs.getBoolean("update_on_start",true),updateChecker.busy(),
                System.currentTimeMillis(),prefs.getLong("update_attempted_at",0)))checkUpdates(false);
    }
    @Override protected void onPause() { resumed = false; apkUpdater.listen(null); handler.removeCallbacks(updateStatus); super.onPause(); }
    @Override protected void onDestroy() { handler.removeCallbacks(updateStatus); if(downloadDialog!=null)downloadDialog.dismiss(); if(usageDialog!=null)usageDialog.dismiss(); if(updateDialog!=null)updateDialog.dismiss(); if (updateChecker != null) updateChecker.close(); super.onDestroy(); }

    private void refresh() {
        if (status == null || connectionStatus == null || lastTap == null) return;
        boolean serviceRunning=SkipService.running();
        boolean accessibility=serviceRunning || accessibilityConfigured();
        status.setText(serviceRunning?(!AppProfiles.enabled(this)?"服务运行中 · AI 强化已关闭":!RecognitionMode.visuals(this)?"服务运行中 · 父子控件识别":SkipService.ocrReady()?"服务运行中 · 控件 + 视觉补充":"服务运行中 · 控件就绪，视觉准备中"):accessibility?"无障碍已开启，等待服务连接":"开启后，助手才能自动点击跳过");
        accessBadge.setText(serviceRunning ? "运行中" : accessibility?"已开启":"未开启");
        tintChip(accessBadge, serviceRunning ? GREEN : AMBER, serviceRunning ? Color.rgb(234,247,241) : Color.rgb(255,246,231));
        LocalAdbController local = LocalAdbController.get(this);
        // Wait for any in-flight health check, then restore immediately after returning from Settings.
        if (resumed && restoreAfterSettings && !local.busy()) {
            restoreAfterSettings = false;
            local.reconnect(true);
        }
        connectionBadge.setText(local.ready() ? "已连接" : local.busy() ? "连接中" : local.paired() ? "待恢复" : "待配对");
        tintChip(connectionBadge, local.ready() ? GREEN : ACCENT, local.ready() ? Color.rgb(234,247,241) : SOFT);
        connectionStatus.setText((Build.VERSION.SDK_INT>=30?local.wirelessSummary()+"\n":"")+prefs.getString("local_adb_status", "首次使用请完成本机配对"));
        if(autoWifiSummary!=null)autoWifiSummary.setText(Build.VERSION.SDK_INT<30?"自动无线调试需要 Android 11 或更新版本":
                !local.automaticWifi()?"已关闭 · 可随时启用":
                !local.paired()?"先完成首次配对，之后自动连接":
                !local.wifiConnected()?"等待接入 Wi-Fi":prefs.getString("local_adb_auto_status",local.canEnableWireless()?"已授权 · 接入 Wi-Fi 后自动恢复":"先手动开启无线调试并连接一次"));
        connectButton.setEnabled(!local.busy()); connectButton.setAlpha(local.busy() ? .55f : 1f);
        connectButton.setText(local.busy() ? "正在连接…" : local.ready() ? "检查 / 恢复连接" : local.paired() ? "一键恢复连接" : "开始首次配对");
        String guard = prefs.getString("sensor_status", "尚未连接");
        boolean protectedNow = guard.contains("已暂停") || guard.contains("保护中");
        guardSummary.setText(!SensorGuardController.get(this).enabled() ? "6 秒保护 · 已关闭" : protectedNow ? "保护中 · 6 秒后自动恢复" :
                (local.ready() || guard.contains("已就绪")) ? "6 秒保护 · 已就绪" : "6 秒保护 · 需在“连接”页授权");
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
        lastVisual.setText(RecognitionMode.visuals(this)?"最近画面判断\n"+prefs.getString("last_visual","暂无")+"\n\n点击后检查\n"+prefs.getString("last_result","暂无"):
                "当前仅使用控件 · 不截图\n"+prefs.getString("last_control","等待下一次控件识别")+"\n\n点击后检查\n"+prefs.getString("last_result","暂无"));
        lastTap.setText(prefs.getBoolean("capture_click", false) ? "等待强化应用中的一次手动点击…" : prefs.getString("last_tap", "暂无手动点击记录"));
        if (updateSummary != null) updateSummary.setText(updateChecker.busy() ? "正在读取官方 GitHub Releases…" :
                prefs.getString("update_status", "点击检查最新版本") + "\n" + updateChecker.repository());
        refreshUpdateBadges();
        if(downloadSummary!=null)downloadSummary.setText(apkUpdater.message().isEmpty()?"暂无下载任务":apkUpdater.message());
    }
    private boolean accessibilityConfigured() {
        ComponentName expected=new ComponentName(this,SkipService.class);
        try {
            String services=Settings.Secure.getString(getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if(services!=null)for(String service:services.split(":")) {
                if(expected.equals(ComponentName.unflattenFromString(service.trim())))return true;
            }
        } catch(SecurityException ignored) { }
        AccessibilityManager manager=(AccessibilityManager)getSystemService(ACCESSIBILITY_SERVICE);
        if(manager!=null)try {
            for(AccessibilityServiceInfo enabled:manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                android.content.pm.ResolveInfo resolve=enabled.getResolveInfo();
                if(resolve!=null && resolve.serviceInfo!=null && expected.equals(new ComponentName(
                        resolve.serviceInfo.packageName,resolve.serviceInfo.name)))return true;
            }
        } catch(SecurityException ignored) { }
        return false;
    }

    private void about() {
        String mode=RecognitionMode.visuals(this)?"当前：控件优先，视觉补充已开启。控件不足时才截图，本机模型读取按钮文字和线索，画面不上传。":"当前：仅父子控件识别，不截图。自绘广告可能无法取得按钮，可在“应用规则”开启视觉补充。";
        new AppDialog.Builder(this).setTitle("开屏助手 · v" + version).setMessage("让开屏，更轻快。\n\n"+mode+"\n\n网络用于本机无线调试，以及启用的 GitHub 更新检查。")
                .setNegativeButton("源码与许可", (dialog, which) -> showProjectLicense())
                .setPositiveButton("知道了", null).setNeutralButton("检查更新", (dialog, which) -> checkUpdates(true)).show();
    }
    private void showProjectLicense() {
        new AppDialog.Builder(this).setTitle("源码与许可").setMessage(ProjectNotice.license(this))
                .setNeutralButton("查看源码",(dialog,which)->openWeb(ProjectNotice.SOURCE_URL))
                .setPositiveButton("知道了",null).show();
    }
    private void showHelp(String title, String message) {
        AppDialog dialog = new AppDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("知道了", null).create();
        dialog.show();
    }
    private void showUsageNotice(boolean firstUse) {
        if(isFinishing() || isDestroyed() || (usageDialog!=null && usageDialog.isShowing()))return;
        AppDialog.Builder builder=new AppDialog.Builder(this).setTitle("使用须知").setMessage(UsageNotice.TEXT);
        if(firstUse)builder.setCancelable(false).setNegativeButton("退出",(d,w) -> finish())
                .setPositiveButton("我已阅读并知悉",(d,w) -> {
                    if(prefs.edit().putBoolean(UsageNotice.ACKNOWLEDGED,true).commit())resumeActivityActions();
                    else {Toast.makeText(this,"确认状态未能保存，请重试",Toast.LENGTH_LONG).show();showUsageNotice(true);}
                });
        else builder.setPositiveButton("知道了",null);
        usageDialog=builder.create();usageDialog.show();
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
        if(help==null)return label;
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
    private Switch feature(LinearLayout parent, String title, String caption, Supplier<String> help,
                         boolean initial, Consumer<Boolean> listener, boolean separator) {
        return feature(parent,title,caption,help,initial,listener,separator,false);
    }
    private Switch feature(LinearLayout parent, String title, String caption, Supplier<String> help,
                         boolean initial, Consumer<Boolean> listener, boolean separator,boolean compact) {
        return feature(parent,title,caption,help,initial,listener,separator,compact,null);
    }
    private Switch feature(LinearLayout parent, String title, String caption, Supplier<String> help,
                         boolean initial, Consumer<Boolean> listener, boolean separator,boolean compact,Runnable captionAction) {
        LinearLayout feature = row(); feature.setPadding(dp(16), dp(2), dp(16), dp(2)); feature.setMinimumHeight(dp(caption == null ? (compact?52:60) : (compact?70:76)));
        LinearLayout description = column(); description.addView(labelWithHelp(title, help));
        if (caption != null) {
            TextView sub = text(caption, 11, MUTED); sub.setPadding(0, 0, 0, dp(compact?4:8));
            if(captionAction!=null) {
                android.text.SpannableString link=new android.text.SpannableString(caption);
                int start=caption.indexOf("应用规则");
                if(start>=0) {
                    link.setSpan(new android.text.style.ForegroundColorSpan(ACCENT),start,caption.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    link.setSpan(new android.text.style.UnderlineSpan(),start,caption.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                sub.setText(link);sub.setMinHeight(dp(32));sub.setGravity(Gravity.CENTER_VERTICAL);
                sub.setFocusable(true);sub.setContentDescription("查看适配应用");
                sub.setOnClickListener(v -> captionAction.run());
                sub.setBackground(ripple(Color.TRANSPARENT,8,0));
            }
            if(compact){sub.setSingleLine(true);sub.setEllipsize(TextUtils.TruncateAt.END);}description.addView(sub);
        }
        feature.addView(description, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(this); toggle.setShowText(false); toggle.setSwitchMinWidth(dp(44));
        toggle.setContentDescription(title + "开关"); toggle.setChecked(initial);
        toggle.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{ACCENT,Color.rgb(154,162,184)}));
        toggle.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{Color.rgb(206,193,251),Color.rgb(220,224,235)}));
        toggle.setOnCheckedChangeListener((button, checked) -> listener.accept(checked));
        feature.addView(toggle, new LinearLayout.LayoutParams(dp(52), dp(48))); parent.addView(feature);
        if (separator) divider(parent, 16);
        return toggle;
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
        LocalAdbController local=LocalAdbController.get(this);
        if(local.canEnableWireless() || local.wirelessEnabled()) {
            restoreAfterSettings=false;local.reconnect(true);return;
        }
        manualWirelessDebugging();
    }
    private void manualWirelessDebugging() {
        if(Build.VERSION.SDK_INT<30){showHelp("无线调试","本机无线调试需要 Android 11 或更新版本；旧系统请使用已授权的 Shizuku 兼容入口。");return;}
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
            boolean developerPage=false;
            try { startActivity(new Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")); }
            catch (android.content.ActivityNotFoundException | SecurityException directUnavailable) {
                developerPage=true;
                Intent developer = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
                developer.putExtra(":settings:fragment_args_key", "toggle_adb_wireless");
                Bundle arguments = new Bundle();
                arguments.putString(":settings:fragment_args_key", "toggle_adb_wireless");
                developer.putExtra(":settings:show_fragment_args", arguments);
                startActivity(developer);
            }
            Toast.makeText(this,LocalAdbController.get(this).wirelessSettingsHint(pairing,developerPage),Toast.LENGTH_LONG).show();
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
