$ErrorActionPreference='Stop'
$android=Join-Path $PSScriptRoot '../RoadGuardAI'
$release=Get-Content -LiteralPath (Join-Path $android 'release.json') -Raw | ConvertFrom-Json
Copy-Item -LiteralPath (Join-Path $android 'release.json') -Destination (Join-Path $PSScriptRoot 'release.json') -Force
Copy-Item -LiteralPath (Join-Path $android 'app/src/main/assets/live-map') -Destination $PSScriptRoot -Recurse -Force
$compiler=Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
$dashboardSource=Join-Path $PSScriptRoot 'Dashboard.cs'
$dashboardOutput=Join-Path $PSScriptRoot 'RoadGuardDashboard.exe'
& $compiler /nologo /target:winexe "/out:$dashboardOutput" /reference:System.Windows.Forms.dll /reference:System.Drawing.dll /reference:System.Web.Extensions.dll $dashboardSource (Join-Path $PSScriptRoot 'LiveTransport.cs') (Join-Path $PSScriptRoot 'Timeline.cs')
if($LASTEXITCODE -ne 0){throw 'Windows build failed'}
$apk=Join-Path $android 'app/build/outputs/apk/debug/app-debug.apk'
$aapt=Join-Path $PSScriptRoot '../android-toolchain/sdk/build-tools/36.0.0/aapt.exe'
$badging=& $aapt dump badging $apk
if($LASTEXITCODE -ne 0 -or -not ($badging | Select-String -SimpleMatch "versionName='$($release.version)'")){throw 'APK version differs from release.json; build APK first'}
$stage=Join-Path $PSScriptRoot ('../windows-package-'+[guid]::NewGuid())
New-Item -ItemType Directory -Path $stage | Out-Null
foreach($name in @('RoadGuardDashboard.exe','server.cjs','map-server.cjs','release.json','README.md','THIRD-PARTY.md','Setup.cmd','Install.ps1','Uninstall.ps1')){
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $stage
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'live-map') -Destination $stage -Recurse
Copy-Item -LiteralPath (Get-Command node.exe).Source -Destination (Join-Path $stage 'node.exe')
Copy-Item -LiteralPath $apk -Destination (Join-Path $stage 'RoadGuardAI.apk')
$ffmpeg=Join-Path $PSScriptRoot '../media-tools/ffmpeg/ffmpeg-9.0.2-essentials_build'
Copy-Item -LiteralPath (Join-Path $ffmpeg 'bin/ffplay.exe') -Destination $stage
foreach($binary in @('ffmpeg.exe','ffprobe.exe')){Copy-Item -LiteralPath (Join-Path $ffmpeg "bin/$binary") -Destination $stage}
Copy-Item -LiteralPath (Join-Path $ffmpeg 'LICENSE') -Destination (Join-Path $stage 'FFMPEG-LICENSE.txt')
Copy-Item -LiteralPath (Join-Path $ffmpeg 'README.txt') -Destination (Join-Path $stage 'FFMPEG-README.txt')
Invoke-WebRequest -UseBasicParsing -Uri 'https://raw.githubusercontent.com/nodejs/node/v24.16.0/LICENSE' -OutFile (Join-Path $stage 'NODE-LICENSE.txt')
$source=Join-Path $stage 'Windows-source'
New-Item -ItemType Directory -Path $source | Out-Null
foreach($name in @('Dashboard.cs','LiveTransport.cs','LiveTransportHarness.cs','Test-Live.ps1','server.cjs','map-server.cjs','map-tests.cjs','release.json','tests.cjs','Test.ps1','Build-Package.ps1')){
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination $source
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'live-map') -Destination $source -Recurse
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Timeline.cs') -Destination $source
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'TimelineHarness.cs') -Destination $source
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../../outputs/RoadGuard-0.7.0-Guide.md') -Destination $stage
$out=Join-Path $PSScriptRoot "../../outputs/RoadGuard-Windows-$($release.version).zip"
Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $out -Force
Copy-Item -LiteralPath $apk -Destination (Join-Path $PSScriptRoot "../../outputs/RoadGuardAI-$($release.version).apk") -Force
Write-Output "Package: $out"
Write-Output "Staging: $stage"
Get-FileHash -LiteralPath $out
