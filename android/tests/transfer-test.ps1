param(
    [string]$JdkPath = $env:JAVA_HOME,
    [string]$JsonJarPath = '',
    [string]$AndroidJarPath = ''
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($JdkPath)) { throw 'Set JAVA_HOME or pass -JdkPath.' }
if (!(Test-Path -LiteralPath (Join-Path $JdkPath 'bin/javac.exe'))) { throw 'A JDK 17 or newer is required. Pass -JdkPath when needed.' }
$androidRoot = Split-Path -Parent $PSScriptRoot
$qaRoot = Join-Path $androidRoot 'artifacts/qa'
$fixtureRoot = Join-Path $qaRoot 'transfer-jvm'
$classesRoot = Join-Path $fixtureRoot 'classes'
New-Item -ItemType Directory -Path $classesRoot -Force | Out-Null
if ([string]::IsNullOrWhiteSpace($JsonJarPath)) { $JsonJarPath = Join-Path $qaRoot 'warmup-jvm/json-20240303.jar' }
if (!(Test-Path -LiteralPath $JsonJarPath)) {
    $jarFolder = Split-Path -Parent $JsonJarPath
    New-Item -ItemType Directory -Path $jarFolder -Force | Out-Null
    Invoke-WebRequest -UseBasicParsing -Uri 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar' -OutFile $JsonJarPath
}
$expectedHash = '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'
if ((Get-FileHash -LiteralPath $JsonJarPath -Algorithm SHA256).Hash -ne $expectedHash) { throw 'The test JSON support JAR does not match its pinned SHA-256.' }
if ([string]::IsNullOrWhiteSpace($AndroidJarPath)) {
    $sdkRoot = if (![string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) { $env:ANDROID_HOME } elseif (![string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) { $env:ANDROID_SDK_ROOT } else { throw 'Set ANDROID_HOME or pass -AndroidJarPath.' }
    $AndroidJarPath = Join-Path $sdkRoot 'platforms/android-35/android.jar'
}
if (!(Test-Path -LiteralPath $AndroidJarPath)) { throw 'SDK 35 android.jar is required only for class type resolution. Pass -AndroidJarPath when needed.' }
$reportPath = Join-Path $qaRoot 'transfer-results.json'
$sourceRoot = Join-Path $androidRoot 'app/src/main/java/com/lifemoments/reader'
$debugRoot = Join-Path $androidRoot 'app/src/debug/java/com/lifemoments/reader'
$classPath = $classesRoot + ';' + $JsonJarPath + ';' + $AndroidJarPath
function Invoke-Fixture([string]$ClassName, [string]$Label) {
    $fixtureOutput = @(& (Join-Path $JdkPath 'bin/java.exe') -Xmx512m -cp $classPath $ClassName 2>&1)
    if ($LASTEXITCODE -ne 0) { throw ($Label + ' failed: ' + ($fixtureOutput -join [Environment]::NewLine)) }
    $joined = $fixtureOutput -join [Environment]::NewLine
    $match = [regex]::Match($joined, 'PASS: ([0-9]+)')
    if (!$match.Success) { throw ($Label + ' did not report passing checks.') }
    Write-Host ($fixtureOutput | Where-Object { $_ -notmatch '^RESULT:' })
    return @{ checks = [int]$match.Groups[1].Value; output = $fixtureOutput }
}
try {
    $sources = @('Protocol.java','LibraryStore.java','TransferCrypto.java','TransferWire.java','TransferPayload.java') | ForEach-Object { Join-Path $sourceRoot $_ }
    $sources += Join-Path $debugRoot 'TransferWireFixture.java'
    $sources += @('TransferCryptoTest.java','TransferWireTest.java','TransferPayloadTest.java') | ForEach-Object { Join-Path $PSScriptRoot $_ }
    & (Join-Path $JdkPath 'bin/javac.exe') -encoding UTF-8 -cp ($JsonJarPath + ';' + $AndroidJarPath) -d $classesRoot $sources
    if ($LASTEXITCODE -ne 0) { throw 'Production transfer fixture compilation failed.' }
    $crypto = Invoke-Fixture 'com.lifemoments.reader.TransferCryptoTest' 'Cryptographic fixture'
    $wire = Invoke-Fixture 'com.lifemoments.reader.TransferWireTest' 'Production socket fixture'
    $payload = Invoke-Fixture 'com.lifemoments.reader.TransferPayloadTest' 'Production payload and index fixture'
    $payloadLine = $payload.output | Where-Object { $_ -match '^RESULT: ' } | Select-Object -First 1
    $payloadReport = ($payloadLine -replace '^RESULT: ', '') | ConvertFrom-Json
    if ($payloadReport.status -ne 'PASS' -or $payloadReport.checks -ne $payload.checks) { throw 'Payload structured fixture report mismatch.' }
    $report = @{ status = 'PASS'; execution = 'JVM production crypto, socket, payload and index fixtures'; cryptoChecks = $crypto.checks; wireChecks = $wire.checks; payloadChecks = $payload.checks; payloadCases = $payloadReport.cases; androidKeystore = 'Not executed; Android device validation remains pending'; androidNsd = 'Not executed; two-device LAN discovery remains pending'; jsonSupportSha256 = $expectedHash }
    [IO.File]::WriteAllText($reportPath, ($report | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    Write-Output ('Report: ' + $reportPath)
} catch {
    $failure = @{ status = 'FAIL'; execution = 'JVM production transfer fixtures'; error = $_.Exception.Message }
    [IO.File]::WriteAllText($reportPath, ($failure | ConvertTo-Json -Depth 4), [Text.UTF8Encoding]::new($false))
    throw
}
