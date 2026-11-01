param(
    [string]$DestinationDirectory = $PSScriptRoot,
    [string]$ArchivePath = ''
)
$ErrorActionPreference = 'Stop'
# Download the same unmodified provider build used by the tested local package.
$url = 'https://www.gyan.dev/ffmpeg/builds/packages/ffmpeg-9.0.2-essentials_build.zip'
$expected = '60F467265B1E312373DBCD92200C2618A74850F98D3D078E94296BB3FA2047BA'
$temporary = Join-Path ([IO.Path]::GetTempPath()) ('RoadGuard-media-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $temporary | Out-Null
try {
    if (-not $ArchivePath) {
        $ArchivePath = Join-Path $temporary 'media.zip'
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Write-Host 'Downloading the verified FFmpeg media tools from gyan.dev (about 115 MB)...'
        $oldProgress = $ProgressPreference
        try {
            $ProgressPreference = 'SilentlyContinue'
            Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $ArchivePath -TimeoutSec 600
        } finally { $ProgressPreference = $oldProgress }
    }
    if ((Get-FileHash -LiteralPath $ArchivePath -Algorithm SHA256).Hash -ne $expected) {
        throw 'Media download failed its integrity check. No downloaded files were installed. Run Setup.cmd again to retry.'
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $ArchivePath).Path)
    try {
        $files = @{
            'bin/ffplay.exe' = 'ffplay.exe'; 'bin/ffmpeg.exe' = 'ffmpeg.exe';
            'bin/ffprobe.exe' = 'ffprobe.exe'; 'LICENSE' = 'FFMPEG-LICENSE.txt';
            'README.txt' = 'FFMPEG-README.txt'
        }
        # Extract only these known members; never accept arbitrary archive paths.
        foreach ($relative in $files.Keys) {
            $entry = $zip.GetEntry('ffmpeg-9.0.2-essentials_build/' + $relative)
            if ($null -eq $entry) { throw "Media archive is missing $relative" }
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $temporary $files[$relative]), $true)
        }
        New-Item -ItemType Directory -Path $DestinationDirectory -Force | Out-Null
        foreach ($name in $files.Values) {
            Copy-Item -LiteralPath (Join-Path $temporary $name) -Destination (Join-Path $DestinationDirectory $name) -Force
        }
    } finally { $zip.Dispose() }
    Write-Host 'Verified media tools are ready.'
} finally {
    $resolved = [IO.Path]::GetFullPath($temporary)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if ($resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($resolved).StartsWith('RoadGuard-media-')) {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
