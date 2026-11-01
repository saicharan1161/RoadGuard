param([Parameter(Mandatory=$true)][string]$FfmpegBin,[Parameter(Mandatory=$true)][string]$Scratch)
$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Path $Scratch -Force | Out-Null
$Scratch=(Resolve-Path -LiteralPath $Scratch).Path
$ffmpeg=Join-Path $FfmpegBin 'ffmpeg.exe'
$ffprobe=Join-Path $FfmpegBin 'ffprobe.exe'
$fixture=Join-Path $Scratch 'input.h264'
& $ffmpeg -v error -f lavfi -i 'testsrc2=size=640x480:rate=30' -t 5 -c:v libx264 -profile:v baseline -pix_fmt yuv420p -x264-params 'keyint=30:min-keyint=30:scenecut=0:aud=1' -f h264 -y $fixture
if($LASTEXITCODE -ne 0){throw 'Fixture generation failed'}
$compiler=Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
$harness=Join-Path $Scratch 'LiveTransportHarness.exe'
& $compiler /nologo /target:exe "/out:$harness" (Join-Path $PSScriptRoot 'LiveTransport.cs') (Join-Path $PSScriptRoot 'LiveTransportHarness.cs')
if($LASTEXITCODE -ne 0){throw 'Transport harness compile failed'}
$original=@(& $ffmpeg -v error -i $fixture -fps_mode passthrough -f framemd5 - | Where-Object {$_ -notmatch '^#'} | ForEach-Object {($_ -split ',')[-1].Trim()})
if($LASTEXITCODE -ne 0){throw 'Source hashing failed'}
foreach($rate in @(15,30)) {
    $target=Join-Path $Scratch "live-$rate.ts"
    & $harness $fixture $target $rate
    if($LASTEXITCODE -ne 0){throw 'Transport harness failed'}
    $probe=& $ffprobe -v error -count_frames -show_entries 'stream=nb_read_frames,duration' -of json $target
    if($LASTEXITCODE -ne 0){throw 'TS probe failed'}
    $stream=($probe|ConvertFrom-Json).streams[0]
    if([int]$stream.nb_read_frames -ne 150 -or [math]::Abs([double]$stream.duration-(150.0/$rate)) -gt 0.02){throw 'Wrong frame count or timestamps'}
    $decoded=@(& $ffmpeg -v error -xerror -i $target -fps_mode passthrough -f framemd5 - | Where-Object {$_ -notmatch '^#'} | ForEach-Object {($_ -split ',')[-1].Trim()})
    if($LASTEXITCODE -ne 0 -or ($original -join ',') -ne ($decoded -join ',')){throw 'TS decoded pixels differ'}
    Write-Output "PASS $rate FPS: 150 frames, expected duration, every decoded pixel hash unchanged"
}
