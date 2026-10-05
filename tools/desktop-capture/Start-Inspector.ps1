param(
    [string]$Package = 'com.qiyi.video',
    [string]$Serial = '',
    [string]$AdbPath = '',
    [string]$OutputDirectory = '',
    [int]$Seconds = 12,
    [switch]$Launch,
    [switch]$NoVisual,
    [switch]$TreeOnly,
    [string]$PythonPath = 'python'
)
$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
if (-not $AdbPath) {
    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCommand) { $AdbPath = $adbCommand.Source }
    elseif ($env:ANDROID_HOME) { $AdbPath = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe' }
    elseif ($env:ANDROID_SDK_ROOT) { $AdbPath = Join-Path $env:ANDROID_SDK_ROOT 'platform-tools/adb.exe' }
}
if (-not $AdbPath -or -not (Test-Path -LiteralPath $AdbPath)) { throw '请用 -AdbPath 指定 adb.exe 的路径。' }
if (-not $TreeOnly -and -not (Test-Path -LiteralPath (Join-Path $PSScriptRoot '.build/collector.jar'))) {
    throw '尚未构建临时采集桥，请先运行本目录 build.ps1。'
}
if (-not $Serial) {
    $devices = @(& $AdbPath devices | Where-Object { $_ -match '^([^\s]+)\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
    if ($devices.Count -ne 1) { throw '需要一台已授权的手机；多台设备时用 -Serial 指定。' }
    $Serial = $devices[0]
}
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $project ('captures/capture-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
}
$captureArguments = @((Join-Path $PSScriptRoot 'capture_stream.py'), '--adb', $AdbPath, '--serial', $Serial,
    '--package', $Package, '--output', $OutputDirectory, '--seconds', $Seconds)
if ($Launch) { $captureArguments += '--launch' }
if ($TreeOnly) {
    if ($Launch) { throw '纯控件检查直接读取已打开的页面，不使用 -Launch。' }
    $captureArguments += '--tree-only'
    Write-Host '正在抓取当前原生控件边框和父子关系；不请求截图。'
} else { Write-Host '正在从电脑采集；默认不重启应用。截图和父子控件保存在电脑。' }
& $PythonPath @captureArguments
if ($LASTEXITCODE -ne 0) { throw '采集未完成，查看输出目录中的 session.json / stderr.txt。' }
if (-not $NoVisual -and -not $TreeOnly) {
    Write-Host '正在电脑上补充视觉节点；视觉包含关系会与原生父子关系分开显示。'
    & $PythonPath (Join-Path $PSScriptRoot 'augment_visual.py') $OutputDirectory
    if ($LASTEXITCODE -ne 0) { Write-Warning '视觉补充未完成，原始控件树仍可查看。' }
}
$page = Join-Path ([IO.Path]::GetFullPath($OutputDirectory)) 'inspect.html'
Write-Host ('已完成：' + $page)
Start-Process -FilePath $page
