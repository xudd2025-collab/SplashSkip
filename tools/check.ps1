param([string]$JavaHome = $env:JAVA_HOME, [string]$AdScreenshot = '', [string]$LiveScreenshot = '')
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$testClasses = Join-Path $project '.build\checks'
New-Item -ItemType Directory -Path $testClasses -Force | Out-Null
$sources = Join-Path $project 'app\src\main\java\com\codex\splashskip'
& (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 -d $testClasses (Join-Path $sources 'UpdatePolicy.java') (Join-Path $PSScriptRoot 'UpdatePolicyCheck.java') (Join-Path $sources 'BilibiliVisualMatcher.java') (Join-Path $PSScriptRoot 'ScreenshotRuleCheck.java')
if ($LASTEXITCODE -ne 0) { throw 'Check compilation failed' }
& (Join-Path $JavaHome 'bin\java.exe') -cp $testClasses com.codex.splashskip.UpdatePolicyCheck
if ($LASTEXITCODE -ne 0) { throw 'Update policy checks failed' }
if ($AdScreenshot -and $LiveScreenshot) {
    & (Join-Path $JavaHome 'bin\java.exe') -cp $testClasses com.codex.splashskip.ScreenshotRuleCheck (Join-Path $project 'app\src\main\res\drawable-nodpi') $AdScreenshot $LiveScreenshot
    if ($LASTEXITCODE -ne 0) { throw 'Screenshot checks failed' }
}
