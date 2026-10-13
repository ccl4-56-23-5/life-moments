param([switch]$Offline)
$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$vendorRoot = Join-Path $repositoryRoot 'desktop/vendor'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Restore-Package($name, $url, $sha256, $directory, $probe) {
    if (Test-Path -LiteralPath (Join-Path $vendorRoot $probe)) { return }
    $archivePath = Join-Path $vendorRoot $name
    New-Item -ItemType Directory -Path $vendorRoot -Force | Out-Null
    New-Item -ItemType Directory -Path (Split-Path -Parent $archivePath) -Force | Out-Null
    if (!(Test-Path -LiteralPath $archivePath)) {
        if ($Offline) { throw "Dependency missing in offline mode: $name" }
        Invoke-WebRequest -Uri $url -OutFile $archivePath -UseBasicParsing
    }
    if ((Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash -ne $sha256) { throw "Dependency checksum mismatch: $name" }
    $destination = [IO.Path]::GetFullPath((Join-Path $vendorRoot $directory)) + [IO.Path]::DirectorySeparatorChar
    $archive = [IO.Compression.ZipFile]::OpenRead($archivePath)
    try {
        foreach ($entry in $archive.Entries) {
            $target = [IO.Path]::GetFullPath((Join-Path $destination $entry.FullName))
            if (!$target.StartsWith($destination, [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid package path' }
            if (!$entry.Name) { New-Item -ItemType Directory -Path $target -Force | Out-Null; continue }
            New-Item -ItemType Directory -Path (Split-Path -Parent $target) -Force | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
        }
    } finally { $archive.Dispose() }
}
Restore-Package 'webview2.nupkg' 'https://api.nuget.org/v3-flatcontainer/microsoft.web.webview2/1.0.4258.31/microsoft.web.webview2.1.0.4258.31.nupkg' '56F7F4B8BF9AEE4B8EFEFBBDD4F67D5F74EBD1B100ED0806DA71BF76AF481AA9' 'webview2' 'webview2/lib/net462/Microsoft.Web.WebView2.Core.dll'
Restore-Package 'magick/magick.net-q8-x64.14.17.2.nupkg' 'https://api.nuget.org/v3-flatcontainer/magick.net-q8-x64/14.17.2/magick.net-q8-x64.14.17.2.nupkg' 'FEFE99147A5F3B664B930A484E2EFE72909DCD1E4EAE306829141447B0302C7A' 'magick/q8' 'magick/q8/lib/netstandard20/Magick.NET-Q8-x64.dll'
Restore-Package 'magick/magick-core-complete.nupkg' 'https://api.nuget.org/v3-flatcontainer/magick.net.core/14.17.2/magick.net.core.14.17.2.nupkg' '016D675549098F126D3566C07E7030D48F9EFB3B2A7F51EDD010386750A54F5C' 'magick/core-complete' 'magick/core-complete/lib/netstandard20/Magick.NET.Core.dll'
$lucidePath = Join-Path $vendorRoot 'lucide.min.js'
if (!(Test-Path -LiteralPath $lucidePath)) {
    if ($Offline) { throw 'Lucide is missing in offline mode.' }
    Invoke-WebRequest -Uri 'https://unpkg.com/lucide@1.8.0/dist/umd/lucide.min.js' -OutFile $lucidePath -UseBasicParsing
}
if ((Get-FileHash -LiteralPath $lucidePath -Algorithm SHA256).Hash -ne 'AB85225ECC1DAA2033607856F03DD2DABCBC08E2D260A5B97E17C690AA929766') { throw 'Lucide checksum mismatch.' }
Write-Output 'Desktop dependencies ready.'
