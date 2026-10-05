# 电脑控件检查器

从电脑通过已授权 ADB 采集。类似 Auto.js 的布局检查流程：截图选控件、查看父子层级、搜索文字 / ID / 类名、标注目标、导出标注供电脑训练。采集和标注按钮不会点击手机。

## 纯控件诊断（v0.6.33）

可通过只读 ADB 桥检查手机服务的实际控件快照，不请求截图或录屏：

```powershell
adb shell content call --uri content://com.codex.splashskip.capture --method status --arg com.codex.splashskip
adb shell content call --uri content://com.codex.splashskip.capture --method native_frame --arg com.tencent.qqlive
```

返回的 `data` 是 Base64 编码的 Gzip JSON。`status` 包含视觉补充开关、OCR 初始化状态、服务运行状态以及本次服务运行以来的截图请求计数。`native_frame` 读取当前场景最近一次生产扫描的快照，包含父子索引、语义角色、节点边界、完整性、筛选结果、快照年龄及已核验的同父重复边。快照年龄用于诊断，返回快照不会授权点击，也不会重新触发控件扫描；尚无当前场景快照时返回 `waiting`。

相关短标签在控件捕获时冻结，最长 96 个字符，避免点击前后的节点混用。原生身份为哈希值。独立深读 `tree_deep` 采用另一遍历适配器，其重复节点保护更保守；分析生产完整性应读取 `native_frame`。原始诊断仍可能包含页面文字，应保留在本地。

## 使用

直接检查已经打开的页面，只抓原生控件边框及父子层级：

```powershell
.\Start-Inspector.ps1 -Package tv.danmaku.bili -TreeOnly -Seconds 3 -AdbPath C:\path\adb.exe
```

`-TreeOnly` 不需要临时截图桥或桌面 OCR，检查器显示当前控件边框。可用 `-PythonPath` 指定 Python。先确认当前关闭标签及小型可点击父容器，再用修正版测试这个控件；抓取和离线标注本身不发送手机点击。

需要电脑 Python 3、ADB，以及手机上已安装 v0.6.32 或更高版本并开启助手无障碍。先运行本目录 `build.ps1 -SdkPath C:\path\android-sdk -JavaHome C:\path\jdk-17` 构建临时桥；已有最新版 `.build/collector.jar` 时无需重建。

电脑视觉补充需先运行项目 `tools/check.ps1` 编译检查器，设置 `JAVA_HOME`，并将 `SPLASHSKIP_DESKTOP_ONNX` 设为桌面版 ONNX Runtime 1.20.0 JAR 的完整路径（不是项目随附的 Android classes.jar）。可从 Maven Central 的 `com.microsoft.onnxruntime:onnxruntime:1.20.0` 获取。也可向 `augment_visual.py` 传 `--java` 和 `--runtime`。识别使用本地模型，无需云端接口。

在 PowerShell 中运行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\Start-Inspector.ps1 -Package com.qiyi.video
```

默认深度记录当前页面 12 秒，在电脑离线补充视觉节点后打开检查器。加 `-Launch` 会先重启指定应用；不要用于有未保存内容的页面。`-AdbPath` 可指定 ADB，`-Serial` 可指定多台手机中的一台，`-OutputDirectory` 指定一个新的采集目录。`-NoVisual` 跳过电脑视觉处理；已有采集也可运行 `python augment_visual.py 采集目录` 补充。

ADB 默认从 PATH、ANDROID_HOME 或 ANDROID_SDK_ROOT 查找；采集默认保存到项目的 `captures/`，已由 Git 忽略。源码包不包含本机 SDK、桌面运行库或个人采集数据。

## 自动边框历史（v0.6.40）

在手机“记录”页开启“自动记录控件边框”，打开其他应用后可回到助手查看控件图、点选边框或导出 JSON。记录保留 7 天、最多 64 条、约 1.5 MiB，原生树可能不完整或仅包含独立广告区域；这些状态分别标注。历史记录不能用于点击。

v0.6.42 的手机预览可全屏查看、双指缩放、筛选可点击或关闭/跳过节点，并通过编号列表、父子节点和重叠框导航选择目标。绿色为有效可点击、紫色为动作候选、橙色为广告线索，选中为红色，可点击父级为蓝色虚线；候选和系统可点击属性不等同于自动关闭已通过校验。详情另显示原始系统可点击、启用、可见属性及中文扫描结果，旧记录未保存的字段标为未知。

导出节点增加 `skip_countdown`、可空的 `enabled` / `declared_clickable` / `on_screen`。这些属性与原生扫描同次冻结，不额外深读。`visible` / `clickable` 仍是包含启用条件的原有效状态，保留旧格式含义；分析原始系统声明属性时使用新字段。完整独立广告区域不能让部分全页变成完整树。

电脑可直接取回指定应用的历史，不必重现已经消失的广告：

```powershell
python export_bounds.py --package com.duowan.kiwi --adb C:\path\adb.exe --serial DEVICE_SERIAL --output C:\path\huya-bounds.json
```

只读已保存的记录，不重启目标应用、不截图。导出可能含应用包名、资源 ID 和少量广告动作标签，文件由使用者管理。

v0.6.43 归档节点追加 `explicit_skip_ad` 与 `navigation_disclosure`，分别表示同次读取到明确“跳过广告”整句和详情页/第三方跳转提示。只有当前文本或描述各自独立匹配时才置真；旧记录无这两字段时保持未知，不能由普通 `prompt` 角色反推披露类别。

## 点击测速：只连续截图（v0.6.38）

需要对照“跳过”实际出现时间与助手日志时，使用仅截图模式：

```powershell
python capture_stream.py --package com.duowan.kiwi --seconds 9 --screens-only --adb C:\path\adb.exe --serial DEVICE_SERIAL --output C:\path\capture-session
```

此模式保存全部截图和每张图的手机 `wall_time`，不额外读取原生控件树；`frame_count=0` 表示没有配对树帧，截图数量在 `screen_count`。采集器不点击，保留助手无障碍服务。截图仍有采样间隔，应人工对照连续画面和生产触摸日志，分别计算首次可见按钮到输入完成、到广告画面消失的时间。

深度控件采集每次可能占用 1.5 秒，会与助手争用节点读取和缓存；在该模式下测到的点击时间包含额外采集负载。先用深度模式诊断控件，再用仅截图模式复测速度。仅截图模式也有截图开销，结果属于该设备本轮样本。

## 深度采集（v0.6.28）

请求包含“不重要”节点；遍历可读取的同应用窗口，保留不可见父容器并继续读取其子节点。深度模式每次原生遍历最多 1.5 秒、1,536 节点、48 层；优先横向展开，空子节点重试一次。数据中明确记录超时、节点/层数上限、无法返回的子节点、重复/循环与窗口信息。默认截图短边上限 1,600 像素（小于上限的手机保留原分辨率），JPEG 质量 96。传输采用有解压上限的 Gzip；历史联合记录分页导出，避免大树挤满 Android 传输缓冲区。

原生遍历完成仅表示当时系统可访问的节点已遍历，不代表每个可见元素都有原生节点。动画仍可能让截图与树错位，不能用跨帧旧坐标补树。

手机不必进入助手导出页面。截图流来自临时 shell 进程；父子树通过助手已有无障碍的只读 ADB 桥读取。桥只允许 ADB shell，结束后不留下另一个常驻应用。该 vivo 上独立 UiAutomation 布局采集会被系统终止，因此默认 `capture_stream.py`，不要用实验性 `capture.py` 作为日常入口。

## 检查与标注

- 左边切换帧、查看控件框，点击截图选最小控件；右边可查看父节点和直接子节点。
- “标为关闭目标”表示这一帧的控件身份，不等同于点击成功；“标为非目标”用来记录普通导航、推广跳转等反例。
- “导出标注”下载 JSON。树和截图可能有时间差；先核对可见画面，不能把启动动画对应的广告控件标成正例。
- `tree_complete=false` 表示抓取达到时间或节点上限；自绘画面也可能没有可访问子节点。
- 原生节点与视觉补充可切换查看。紫色虚线为 OCR 视觉观测；`visual_nodes` 单独保存，`parent=-1`，`anchor_native_index` 只表示像素上被某个原生容器包含，不能当成真实父子关系。视觉项没有 `ACTION_CLICK` 能力，不会混入原生父子训练。
- OCR 当前识别文字，未给纯图标或每个装饰图形都构造虚拟节点；视觉结果也可能漏字或误读。手机现有视觉点击仍需独立场景与当前画面核验。
- 默认所有文件仅在本地。树可能包含页面文字、账号信息和资源 ID，勿直接提交 Git 或分享整个目录。

## 训练

`train_candidates.py` 使用人工复核清单，通过生产 Java 特征提取器生成 12 个父子结构特征和 8×8 局部灰度特征。按布局分组留出，隐藏语义标签输入，不学习广告正文、绝对位置或资源 ID。手机只用模型排序当前已经找到的候选，仍需文字、场景、时效与遮挡验证。

这与 `../train_joint_controls.py` 的点击结果训练分开；未知点击结果不自动转为成功。当前候选模型只在爱奇艺两个布局上验证，不能视作全应用训练完成。

## 系统限制

纯控件只读 `native_frame` 可附带独立 `ad_scope`。`global_read=false` 时，全局 `tree_nodes` 为空且 `tree_complete=false`；广告容器自己的完整性另行记录。输入回执或节点消失都不自动标记为广告画面已关闭。

电脑能更方便地反复抓取、对照和训练，但不能读取 Android 没有暴露的控件。此时依靠当前截图的视觉识别，不能恢复不存在的父子节点。采集耗时也不是手机自动跳过耗时，开启采集会增加设备负载。
