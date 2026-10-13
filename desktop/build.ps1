param([string]$PythonPath = 'python', [switch]$Offline)
$ErrorActionPreference = 'Stop'
$desktopRoot = $PSScriptRoot
$compiler = 'C:/Windows/Microsoft.NET/Framework64/v4.0.30319/csc.exe'
$python = $PythonPath
& (Join-Path (Split-Path -Parent $desktopRoot) 'scripts/restore-dependencies.ps1') -Offline:$Offline
& $python (Join-Path $desktopRoot 'build_frontend.py')
if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed' }
$out = Join-Path $desktopRoot 'out'
New-Item -ItemType Directory -Path $out -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $desktopRoot 'vendor/webview2/lib/net462/Microsoft.Web.WebView2.Core.dll') -Destination $out
Copy-Item -LiteralPath (Join-Path $desktopRoot 'vendor/webview2/lib/net462/Microsoft.Web.WebView2.WinForms.dll') -Destination $out
Copy-Item -LiteralPath (Join-Path $desktopRoot 'vendor/webview2/runtimes/win-x64/native/WebView2Loader.dll') -Destination $out
Copy-Item -LiteralPath (Join-Path $desktopRoot 'vendor/magick/q8/lib/netstandard20/Magick.NET-Q8-x64.dll') -Destination $out
Copy-Item -LiteralPath (Join-Path $desktopRoot 'vendor/magick/core-complete/lib/netstandard20/Magick.NET.Core.dll') -Destination $out
Copy-Item -LiteralPath (Join-Path $desktopRoot 'vendor/magick/q8/runtimes/win-x64/native/Magick.Native-Q8-x64.dll') -Destination $out
Copy-Item -LiteralPath (Join-Path $desktopRoot 'vendor/magick/q8/Notice.txt') -Destination (Join-Path $desktopRoot 'app/licenses/Magick-NOTICE.txt')
Copy-Item -LiteralPath (Join-Path $desktopRoot 'app/app.ico') -Destination $out
Copy-Item -LiteralPath (Join-Path $desktopRoot 'app') -Destination $out -Recurse -Force
$appArgs = @('/nologo','/target:winexe','/platform:x64','/optimize+','/utf8output',
    ('/out:' + (Join-Path $out '生活点滴.exe')),
    ('/win32icon:' + (Join-Path $desktopRoot 'app/app.ico')),
    '/r:System.dll','/r:System.Core.dll','/r:System.Drawing.dll','/r:System.Windows.Forms.dll','/r:System.Web.Extensions.dll','/r:System.Net.Http.dll','/r:System.Security.dll',
    ('/r:' + (Join-Path $out 'Microsoft.Web.WebView2.Core.dll')),('/r:' + (Join-Path $out 'Microsoft.Web.WebView2.WinForms.dll')),
    ('/r:' + (Join-Path $out 'Magick.NET-Q8-x64.dll')),('/r:' + (Join-Path $out 'Magick.NET.Core.dll')),'/r:C:/Windows/Microsoft.NET/Framework64/v4.0.30319/netstandard.dll',
    (Join-Path $desktopRoot 'DesktopApp.cs'),(Join-Path $desktopRoot 'LoadingScreen.cs'),(Join-Path $desktopRoot 'PhotoLibrary.cs'),(Join-Path $desktopRoot 'JpegMetadata.cs'),(Join-Path $desktopRoot 'HeifDecoder.cs'),(Join-Path $desktopRoot 'BaiduSync.cs'),(Join-Path $desktopRoot 'BaiduAuthorizationTests.cs'),(Join-Path $desktopRoot 'BaiduUploadResponseTests.cs'),(Join-Path $desktopRoot 'DesktopTests.cs'),(Join-Path $desktopRoot 'DesktopPerformanceTests.cs'),(Join-Path $desktopRoot 'DesktopMotionTests.cs'),(Join-Path $desktopRoot 'DesktopTagTests.cs'))
& $compiler @appArgs
if ($LASTEXITCODE -ne 0) { throw 'Desktop compile failed' }
Copy-Item -LiteralPath (Join-Path $desktopRoot 'app.config') -Destination (Join-Path $out '生活点滴.exe.config')
Copy-Item -LiteralPath (Join-Path $desktopRoot '使用说明.txt') -Destination $out
$payload = Join-Path $desktopRoot 'artifacts/payload.zip'
New-Item -ItemType Directory -Path (Split-Path -Parent $payload) -Force | Out-Null
$payloadNames = @('app','app.ico','使用说明.txt','生活点滴.exe','生活点滴.exe.config',
    'Microsoft.Web.WebView2.Core.dll','Microsoft.Web.WebView2.WinForms.dll','WebView2Loader.dll',
    'Magick.NET-Q8-x64.dll','Magick.NET.Core.dll','Magick.Native-Q8-x64.dll')
Compress-Archive -LiteralPath @($payloadNames | ForEach-Object { Join-Path $out $_ }) -DestinationPath $payload -Force
$setupArgs = @('/nologo','/target:winexe','/platform:x64','/optimize+','/utf8output',
    ('/out:' + (Join-Path $desktopRoot 'artifacts/生活点滴-安装程序-0.3.0-beta.9.exe')),
    ('/win32icon:' + (Join-Path $desktopRoot 'app/app.ico')),
    ('/resource:' + $payload + ',payload.zip'),
    '/r:System.dll','/r:System.Core.dll','/r:System.Drawing.dll','/r:System.Windows.Forms.dll','/r:System.Web.Extensions.dll',
    '/r:System.IO.Compression.dll','/r:System.IO.Compression.FileSystem.dll',
    (Join-Path $desktopRoot 'Installer.cs'))
& $compiler @setupArgs
if ($LASTEXITCODE -ne 0) { throw 'Installer compile failed' }
Get-FileHash -LiteralPath (Join-Path $desktopRoot 'artifacts/生活点滴-安装程序-0.3.0-beta.9.exe') -Algorithm SHA256
