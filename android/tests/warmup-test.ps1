param(
    [string]$JdkPath = $env:JAVA_HOME,
    [string]$JsonJarPath = ''
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($JdkPath)) { throw 'Set JAVA_HOME or pass -JdkPath.' }
if (!(Test-Path -LiteralPath (Join-Path $JdkPath 'bin/javac.exe'))) { throw 'A JDK 17 or newer is required. Pass -JdkPath when needed.' }
$androidRoot = Split-Path -Parent $PSScriptRoot
$qaRoot = Join-Path $androidRoot 'artifacts/qa'
$fixtureRoot = Join-Path $qaRoot 'warmup-jvm'
$classesRoot = Join-Path $fixtureRoot 'classes'
New-Item -ItemType Directory -Path $classesRoot -Force | Out-Null
if ([string]::IsNullOrWhiteSpace($JsonJarPath)) { $JsonJarPath = Join-Path $fixtureRoot 'json-20240303.jar' }
if (!(Test-Path -LiteralPath $JsonJarPath)) {
    Invoke-WebRequest -UseBasicParsing -Uri 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar' -OutFile $JsonJarPath
}
# Test-only JSON support. A pinned hash protects the first download and allows offline reuse.
$expectedHash = '3CF6CD6892E32E2B4C1C39E0F52F5248A2F5B37646FDFBB79A66B46B618414ED'
if ((Get-FileHash -LiteralPath $JsonJarPath -Algorithm SHA256).Hash -ne $expectedHash) { throw 'The test JSON support JAR does not match its pinned SHA-256.' }
$reportPath = Join-Path $qaRoot 'warmup-results.json'
try {
    & (Join-Path $JdkPath 'bin/javac.exe') -encoding UTF-8 -cp $JsonJarPath -d $classesRoot (Join-Path $androidRoot 'app/src/main/java/com/lifemoments/reader/ThumbnailWarmup.java') (Join-Path $PSScriptRoot 'WarmupTest.java')
    if ($LASTEXITCODE -ne 0) { throw 'Scheduler fixture compilation failed.' }
    & (Join-Path $JdkPath 'bin/java.exe') -cp ($classesRoot + ';' + $JsonJarPath) com.lifemoments.reader.WarmupTest $reportPath
    if ($LASTEXITCODE -ne 0) { throw 'Scheduler fixture tests failed.' }
    Write-Output ('Report: ' + $reportPath)
} catch {
    $failure = @{ status = 'FAIL'; execution = 'JVM isolated scheduler fixture'; error = $_.Exception.Message }
    [IO.File]::WriteAllText($reportPath, ($failure | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
    throw
}
