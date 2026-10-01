param(
    [string]$SdkPath = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$SigningKey = '',
    [string]$KeyAlias = 'androiddebugkey'
)

$ErrorActionPreference = 'Stop'
if (-not $SdkPath -or -not (Test-Path -LiteralPath $SdkPath)) {
    throw 'Set ANDROID_HOME or pass -SdkPath to the Android SDK directory.'
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath $JavaHome)) {
    throw 'Set JAVA_HOME or pass -JavaHome to a JDK 17 directory.'
}
$env:JAVA_HOME = $JavaHome
$tools = Join-Path $SdkPath 'build-tools\36.0.0'
$androidJar = Join-Path $SdkPath 'platforms\android-36\android.jar'
$source = Join-Path $PSScriptRoot 'app\src\main'
$releaseVersion = ConvertFrom-StringData (Get-Content -LiteralPath (Join-Path $PSScriptRoot 'version.properties') -Raw)
if ($releaseVersion.versionCode -notmatch '^[1-9][0-9]*$' -or $releaseVersion.versionName -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$') {
    throw 'Invalid version.properties'
}
$build = Join-Path $PSScriptRoot '.build'
$classes = Join-Path $build 'classes'
$dex = Join-Path $build 'dex'
# Rebuild generated content from scratch; keep the persistent signing key in .build.
$taskBuildRoot = [IO.Path]::GetFullPath($build)
foreach ($generatedName in @('classes', 'dex', 'gen', 'aidl', 'assets', 'lib')) {
    $generatedPath = [IO.Path]::GetFullPath((Join-Path $build $generatedName))
    if (-not $generatedPath.StartsWith($taskBuildRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Generated path escaped the build directory'
    }
    if (Test-Path -LiteralPath $generatedPath) { Remove-Item -LiteralPath $generatedPath -Recurse -Force }
}
$vendor = Join-Path $PSScriptRoot 'third_party\tensorflow-lite'
$vendorJar = Join-Path $vendor 'classes.jar'
$vendorApiJar = Join-Path $vendor 'api-classes.jar'
$shizukuJars = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'third_party\shizuku') -Filter '*.jar' | ForEach-Object FullName)
$localVendor = Join-Path $PSScriptRoot 'third_party\local-adb'
$localJars = @(Get-ChildItem -LiteralPath $localVendor -Filter '*.jar' | ForEach-Object FullName)
$compileClasspath = (@($androidJar, $vendorJar, $vendorApiJar) + $shizukuJars + $localJars) -join ';'
New-Item -ItemType Directory -Path $build, $classes, $dex -Force | Out-Null

& (Join-Path $tools 'aapt2.exe') compile --dir (Join-Path $source 'res') -o (Join-Path $build 'res.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }
& (Join-Path $tools 'aapt2.exe') link -o (Join-Path $build 'unsigned.apk') -I $androidJar --manifest (Join-Path $source 'AndroidManifest.xml') --java (Join-Path $build 'gen') --min-sdk-version 26 --target-sdk-version 35 --version-code $releaseVersion.versionCode --version-name $releaseVersion.versionName (Join-Path $build 'res.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

$aidlSource = Join-Path $source 'aidl'
$aidlGen = Join-Path $build 'aidl'
New-Item -ItemType Directory -Path $aidlGen -Force | Out-Null
& (Join-Path $tools 'aidl.exe') "-I$aidlSource" "-o$aidlGen" (Join-Path $aidlSource 'com\codex\splashskip\ISensorGuard.aidl')
if ($LASTEXITCODE -ne 0) { throw 'aidl failed' }
$javaFiles = @(Get-ChildItem -LiteralPath (Join-Path $source 'java') -Filter '*.java' -Recurse | ForEach-Object FullName)
$javaFiles += @(Get-ChildItem -LiteralPath (Join-Path $localVendor 'src') -Filter '*.java' -Recurse | ForEach-Object FullName)
$javaFiles += @(Get-ChildItem -LiteralPath $aidlGen -Filter '*.java' -Recurse | ForEach-Object FullName)
$javaFiles += @(Get-ChildItem -LiteralPath (Join-Path $build 'gen') -Filter '*.java' -Recurse | ForEach-Object FullName)
& (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 --release 8 -cp $compileClasspath -d $classes @javaFiles
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
& (Join-Path $JavaHome 'bin\jar.exe') cf (Join-Path $build 'classes.jar') -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
& (Join-Path $tools 'd8.bat') --min-api 26 --lib $androidJar --output $dex (Join-Path $build 'classes.jar') $vendorJar $vendorApiJar @shizukuJars @localJars
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }
& (Join-Path $JavaHome 'bin\jar.exe') uf (Join-Path $build 'unsigned.apk') -C $dex .
if ($LASTEXITCODE -ne 0) { throw 'apk update failed' }
$assets = Join-Path $build 'assets'
New-Item -ItemType Directory -Path $assets -Force | Out-Null
Copy-Item -Path (Join-Path $source 'assets\*') -Destination $assets -Force
$lib = Join-Path $build 'lib'
foreach ($arch in Get-ChildItem -LiteralPath (Join-Path $vendor 'jni') -Directory) {
    $target = Join-Path $lib $arch.Name
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $arch.FullName 'libtensorflowlite_jni.so') -Destination (Join-Path $target 'libtensorflowlite_jni.so') -Force
}
foreach ($arch in Get-ChildItem -LiteralPath (Join-Path $localVendor 'jni') -Directory) {
    $target = Join-Path $lib $arch.Name
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    Copy-Item -Path (Join-Path $arch.FullName '*.so') -Destination $target -Force
}
& (Join-Path $JavaHome 'bin\jar.exe') uf (Join-Path $build 'unsigned.apk') -C $build assets -C $build lib
if ($LASTEXITCODE -ne 0) { throw 'asset packaging failed' }
& (Join-Path $tools 'zipalign.exe') -f 4 (Join-Path $build 'unsigned.apk') (Join-Path $build 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

$key = if ($SigningKey) { (Resolve-Path -LiteralPath $SigningKey).Path } else { Join-Path $build 'debug.keystore' }
if (-not $SigningKey -and -not (Test-Path -LiteralPath $key)) {
    & (Join-Path $JavaHome 'bin\keytool.exe') -genkeypair -keystore $key -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=SplashSkip Debug,O=Codex,C=CN' -noprompt
    if ($LASTEXITCODE -ne 0) { throw 'key generation failed' }
}
$apk = Join-Path $PSScriptRoot 'app-debug.apk'
if ($SigningKey) {
    if (-not $env:SPLASHSKIP_STORE_PASSWORD -or -not $env:SPLASHSKIP_KEY_PASSWORD) { throw 'Signing passwords must be set in SPLASHSKIP_STORE_PASSWORD and SPLASHSKIP_KEY_PASSWORD.' }
    & (Join-Path $tools 'apksigner.bat') sign --ks $key --ks-key-alias $KeyAlias --ks-pass env:SPLASHSKIP_STORE_PASSWORD --key-pass env:SPLASHSKIP_KEY_PASSWORD --out $apk (Join-Path $build 'aligned.apk')
} else {
    & (Join-Path $tools 'apksigner.bat') sign --ks $key --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $apk (Join-Path $build 'aligned.apk')
}
if ($LASTEXITCODE -ne 0) { throw 'apk signing failed' }
& (Join-Path $tools 'apksigner.bat') verify $apk
if ($LASTEXITCODE -ne 0) { throw 'apk verification failed' }
Write-Output $apk
