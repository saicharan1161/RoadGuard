$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$release = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'release.json') -Raw | ConvertFrom-Json
$version = $release.version
$output = Join-Path $root 'outputs'
$stage = Join-Path $root ('work/public-release-' + [guid]::NewGuid())
$windows = Join-Path $stage 'Windows'
$usb = Join-Path $stage 'RoadGuard-USB'
New-Item -ItemType Directory -Path $windows,$usb | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$localBundle = Join-Path $output "RoadGuard-Windows-$version.zip"
$zip = [IO.Compression.ZipFile]::OpenRead($localBundle)
try {
    foreach ($entry in $zip.Entries) {
        if ($entry.Name -in @('ffmpeg.exe','ffplay.exe','ffprobe.exe','FFMPEG-LICENSE.txt','FFMPEG-README.txt')) { continue }
        $target = [IO.Path]::GetFullPath((Join-Path $windows $entry.FullName))
        if (-not $target.StartsWith($windows + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid package path' }
        if (-not $entry.Name) { New-Item -ItemType Directory -Path $target -Force | Out-Null; continue }
        New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($target)) -Force | Out-Null
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$target,$true)
    }
} finally { $zip.Dispose() }
foreach ($name in @('Install.ps1','Get-MediaTools.ps1')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $windows -Force
}
foreach ($name in @('LICENSE','NOTICE')) {
    Copy-Item -LiteralPath (Join-Path $root $name) -Destination $windows
    Copy-Item -LiteralPath (Join-Path $root $name) -Destination $usb
}
@'
# RoadGuard public Windows package

Extract the entire ZIP into a writable folder, then run Setup.cmd. Setup asks for
administrator access and installs the background server and dashboard.

Internet is needed during first installation: Setup downloads about 115 MB of
FFmpeg media tools directly from gyan.dev and verifies a fixed SHA-256 hash before
extracting them. If the download fails, run Setup.cmd again. No FFmpeg executable
is redistributed in this public ZIP. After setup, local viewing does not require
this download again. Do not upload the expanded folder with downloaded media tools.

Install the matching RoadGuardAI.apk on the car and phone. First-time Android
permissions, storage selection, pairing and any private VPN setup still apply.
For different-network access configure a private VPN on the participating devices.
Use the included 0.7.1 guide for the connection and recording steps.

This is an alpha build, signed with the existing development key for compatibility
with earlier test installations. Test it parked before relying on recordings.

Source and third-party source/licenses:
https://github.com/saicharan1161/RoadGuard
Download RoadGuard-source-0.7.1-alpha.zip from the same GitHub release for the
corresponding project source, including native Android library sources/build files.

Node.js is included with NODE-LICENSE.txt. Leaflet's license is in live-map/.
The existing THIRD-PARTY.md describes the local development bundle; its FFmpeg
redistribution warning is why this public package downloads media tools separately.
'@ | Set-Content -LiteralPath (Join-Path $windows 'START-HERE.txt') -Encoding UTF8
$windowsZip = Join-Path $output "RoadGuard-Windows-$version-Public.zip"
Compress-Archive -Path (Join-Path $windows '*') -DestinationPath $windowsZip -Force
Copy-Item -LiteralPath (Join-Path $output "RoadGuardAI-$version.apk") -Destination $usb
Copy-Item -LiteralPath $windowsZip -Destination $usb
Copy-Item -LiteralPath (Join-Path $output 'RoadGuard-0.7.1-Guide.md') -Destination $usb
Copy-Item -LiteralPath (Join-Path $windows 'START-HERE.txt') -Destination (Join-Path $usb 'WINDOWS-SETUP.txt')
@'
RoadGuard 0.7.1-alpha USB starter

Extract this ZIP onto your USB drive. In Android's file manager, open
RoadGuardAI-0.7.1-alpha.apk and install it on the CAR and PHONE.
An APK cannot autorun or install silently from USB. Complete the first-time
permissions, choose CAR / PHONE, select recording storage and pair the devices.

For Windows, extract the nested Windows-Public ZIP onto the PC and run Setup.cmd.
The Windows installer downloads media tools the first time; internet is required.
Read WINDOWS-SETUP.txt and RoadGuard-0.7.1-Guide.md for details.

This starter contains application files only: no recordings, personal locations,
pairing credentials or VPN accounts. It does not format or erase your USB drive.
Source and licenses: https://github.com/saicharan1161/RoadGuard
'@ | Set-Content -LiteralPath (Join-Path $usb 'START-HERE.txt') -Encoding UTF8
$usbZip = Join-Path $output "RoadGuard-USB-$version.zip"
Compress-Archive -Path $usb -DestinationPath $usbZip -Force
Write-Output "Public Windows: $windowsZip"
Write-Output "USB starter: $usbZip"
Write-Output "Stage: $stage"
