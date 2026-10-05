param([string]$JavaHome = $env:JAVA_HOME, [string]$AdScreenshot = '', [string]$LiveScreenshot = '', [string[]]$SceneScreenshots = @(), [string[]]$BiliNativeTrees = @())
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$testClasses = Join-Path $project '.build/checks'
$sources = Join-Path $project 'app/src/main/java/com/codex/splashskip'
New-Item -ItemType Directory -Path $testClasses -Force | Out-Null
$names = @('TreeVisualLocator','ControlTree','JointControlModel','UpdatePolicy','UpdateSources','UpdateDownload','ParallelUpdateDownload','BilibiliVisualMatcher','NetdiskVisualMatcher','HuyaVisualMatcher','ButtonFeatureMemory','TouchTargetGuard','ClickLearningSession','ButtonStrokeInput','SplashPromptVerifier','AdLabelVerifier','SkipGlyphVerifier','SkipFeatureBank','AdGlyphProposals','TencentVisualMatcher','UniversalSplashMatcher','AdActionRegion','AdButtonClassifier','VisualComponents','UiFeatureSearch','ChinaMobileVisualMatcher','TencentFeedVisualMatcher','ControlTextMatcher','ProfileTextMatcher','UiControlPolicy','OnnxUiText','SceneFramePolicy')
$javaFiles = @($names | ForEach-Object { Join-Path $sources ($_ + '.java') })
$javaFiles += Join-Path $sources 'WindowOwnershipPolicy.java'
$javaFiles += Join-Path $sources 'TreeCandidateRanker.java'
$javaFiles += Join-Path $sources 'BoundedNodeWalker.java'
$javaFiles += Join-Path $sources 'NativeControlPolicy.java'
$javaFiles += Join-Path $sources 'BilibiliNativePolicy.java'
$javaFiles += Join-Path $sources 'NativeTreeObservation.java'
$javaFiles += Join-Path $sources 'NativeTreeBackoff.java'
$javaFiles += Join-Path $sources 'PairingSession.java'
$javaFiles += Join-Path $sources 'NativeBoundsPolicy.java'
$javaFiles += Join-Path $sources 'NativeBoundsInspector.java'
$javaFiles += Join-Path $PSScriptRoot 'DesktopVisualExport.java'
$javaFiles += @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*Check.java' | ForEach-Object FullName)
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -cp (Join-Path $project 'third_party/onnxruntime/classes.jar') -d $testClasses @javaFiles
if ($LASTEXITCODE -ne 0) { throw 'Check compilation failed' }
& (Join-Path $JavaHome 'bin/java.exe') -cp $testClasses com.codex.splashskip.PairingSessionCheck
if ($LASTEXITCODE -ne 0) { throw 'Pairing session checks failed' }
& (Join-Path $JavaHome 'bin/java.exe') -cp $testClasses com.codex.splashskip.UpdatePolicyCheck
if ($LASTEXITCODE -ne 0) { throw 'Update policy checks failed' }
& (Join-Path $JavaHome 'bin/java.exe') -cp $testClasses com.codex.splashskip.UpdateDownloadCheck
if ($LASTEXITCODE -ne 0) { throw 'Download checks failed' }
foreach ($policyCheck in @('NativeControlCheck','NativeTouchObservationCheck','SceneExecutionCheck','NodeTraversalCheck','NativeBoundsArchiveCheck','NativeBoundsInspectorCheck')) {
    & (Join-Path $JavaHome 'bin/java.exe') -cp $testClasses ('com.codex.splashskip.' + $policyCheck)
    if ($LASTEXITCODE -ne 0) { throw "$policyCheck failed" }
}
& (Join-Path $JavaHome 'bin/java.exe') -cp $testClasses com.codex.splashskip.BilibiliNativeCheck @BiliNativeTrees
if ($LASTEXITCODE -ne 0) { throw 'Bilibili native checks failed' }
if ($AdScreenshot -and $LiveScreenshot) {
    & (Join-Path $JavaHome 'bin/java.exe') -cp $testClasses com.codex.splashskip.ScreenshotRuleCheck (Join-Path $project 'app/src/main/res/drawable-nodpi') $AdScreenshot $LiveScreenshot
    if ($LASTEXITCODE -ne 0) { throw 'Screenshot checks failed' }
}
if ($SceneScreenshots.Count -gt 0) {
    & (Join-Path $JavaHome 'bin/java.exe') -cp $testClasses com.codex.splashskip.SceneRuleCheck (Join-Path $project 'app/src/main/res/drawable-nodpi') @SceneScreenshots
    if ($LASTEXITCODE -ne 0) { throw 'Scene screenshot checks failed' }
}
