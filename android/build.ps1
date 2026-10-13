param(
    [string]$SdkPath = $env:ANDROID_HOME,
    [string]$JdkPath = $env:JAVA_HOME,
    [string]$PythonPath = 'python',
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if (!(Test-Path -LiteralPath (Join-Path $SdkPath 'platforms/android-35/android.jar'))) { throw 'Android SDK 35 is required.' }
if (!(Test-Path -LiteralPath (Join-Path $JdkPath 'bin/java.exe'))) { throw 'JDK 17 or newer is required.' }
$env:JAVA_HOME = $JdkPath
$sdkLine = 'sdk.dir=' + $SdkPath.Replace('\', '/').Replace(':', '\:')
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'local.properties'), $sdkLine, [Text.UTF8Encoding]::new($false))
& $PythonPath (Join-Path $PSScriptRoot 'build-assets.py')
if ($LASTEXITCODE -ne 0) { throw 'Asset build failed.' }
$gradleArguments = @('--console=plain','assembleDebug','lintDebug')
if ($Offline) { $gradleArguments = @('--offline') + $gradleArguments }
& (Join-Path $PSScriptRoot 'gradlew.bat') @gradleArguments
if ($LASTEXITCODE -ne 0) { throw 'Android build or lint failed.' }
$taskArtifacts = Join-Path $PSScriptRoot 'artifacts'
New-Item -ItemType Directory -Path $taskArtifacts -Force | Out-Null
$taskApk = Join-Path $taskArtifacts '生活点滴-安卓-0.1.0-beta.5.apk'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'app/build/outputs/apk/debug/app-debug.apk') -Destination $taskApk -Force
Get-FileHash -LiteralPath $taskApk -Algorithm SHA256
