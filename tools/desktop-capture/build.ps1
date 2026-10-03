param([Parameter(Mandatory=$true)][string]$SdkPath,[Parameter(Mandatory=$true)][string]$JavaHome)
$ErrorActionPreference='Stop'
$env:JAVA_HOME=$JavaHome
$build=Join-Path $PSScriptRoot '.build'
New-Item -ItemType Directory -Force (Join-Path $build 'classes'),(Join-Path $build 'dex') | Out-Null
$android=Join-Path $SdkPath 'platforms/android-36/android.jar'
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 --release 8 -cp $android -d (Join-Path $build 'classes') (Join-Path $PSScriptRoot 'DeviceCollector.java') (Join-Path $PSScriptRoot 'DeviceScreens.java')
if($LASTEXITCODE -ne 0){throw 'Collector compile failed'}
& (Join-Path $JavaHome 'bin/jar.exe') cf (Join-Path $build 'classes.jar') -C (Join-Path $build 'classes') .
if($LASTEXITCODE -ne 0){throw 'Collector jar failed'}
& (Join-Path $SdkPath 'build-tools/36.0.0/d8.bat') --min-api 26 --lib $android --output (Join-Path $build 'dex') (Join-Path $build 'classes.jar')
if($LASTEXITCODE -ne 0){throw 'Collector dex failed'}
& (Join-Path $JavaHome 'bin/jar.exe') cf (Join-Path $build 'collector.jar') -C (Join-Path $build 'dex') classes.dex
if($LASTEXITCODE -ne 0){throw 'Collector package failed'}
Write-Output (Join-Path $build 'collector.jar')
