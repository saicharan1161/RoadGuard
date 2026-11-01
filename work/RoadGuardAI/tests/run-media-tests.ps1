param([Parameter(Mandatory=$true)][string]$FfmpegBin,[Parameter(Mandatory=$true)][string]$Scratch)
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
New-Item -ItemType Directory -Path $Scratch -Force | Out-Null
$Scratch=(Resolve-Path -LiteralPath $Scratch).Path
$ffmpeg=Join-Path $FfmpegBin 'ffmpeg.exe'
$ffprobe=Join-Path $FfmpegBin 'ffprobe.exe'
$inputVideo=Join-Path $Scratch 'input.h264'
$normal=Join-Path $Scratch 'normal.mp4'
$interrupted=Join-Path $Scratch 'interrupted.mp4'
$classes=Join-Path $Scratch 'classes'
& $ffmpeg -v error -f lavfi -i 'testsrc2=size=640x480:rate=30' -t 5 -c:v libx264 -profile:v baseline -pix_fmt yuv420p -x264-params 'keyint=30:min-keyint=30:scenecut=0:aud=1' -f h264 -y $inputVideo
if($LASTEXITCODE -ne 0){throw 'Test fixture generation failed'}
& javac -d $classes (Join-Path $project 'libausbc\src\main\java\com\jiangdg\ausbc\encode\muxer\FragmentedMp4Writer.java') (Join-Path $PSScriptRoot 'MediaWriterHarness.java')
if($LASTEXITCODE -ne 0){throw 'Host writer compilation failed'}
& java -cp $classes MediaWriterHarness $inputVideo $normal
if($LASTEXITCODE -ne 0){throw 'Writer/recovery assertions failed'}
& java -cp $classes MediaWriterHarness $inputVideo $interrupted crash
if($LASTEXITCODE -ne 0){throw 'Abrupt-exit simulation failed'}
$cases=@(@{Path=$normal;Frames=150},@{Path=$interrupted;Frames=120},@{Path="$normal.recovered.mp4";Frames=120})
foreach($case in $cases){
    $json=& $ffprobe -v error -count_frames -show_entries 'stream=codec_name,width,height,nb_read_frames,duration' -of json $case.Path
    if($LASTEXITCODE -ne 0){throw "Probe failed: $($case.Path)"}
    $stream=($json|ConvertFrom-Json).streams[0]
    if($stream.codec_name -ne 'h264' -or [int]$stream.nb_read_frames -ne $case.Frames){throw "Wrong decoded frame count: $json"}
    if([math]::Abs([double]$stream.duration-$case.Frames/30.0) -gt 0.05){throw "Stale duration after recovery: $json"}
    & $ffmpeg -v error -xerror -i $case.Path -f null -
    if($LASTEXITCODE -ne 0){throw "Decode failed: $($case.Path)"}
    Write-Output "PASS $([IO.Path]::GetFileName($case.Path)): $($stream.nb_read_frames) frames, $($stream.duration) seconds"
}
$sourceHashes=@(& $ffmpeg -v error -i $inputVideo -f framemd5 - | Where-Object {$_ -notmatch '^#'} | ForEach-Object {($_ -split ',')[-1].Trim()})
if($LASTEXITCODE -ne 0){throw 'Source pixel hashing failed'}
$outputHashes=@(& $ffmpeg -v error -i $normal -f framemd5 - | Where-Object {$_ -notmatch '^#'} | ForEach-Object {($_ -split ',')[-1].Trim()})
if($LASTEXITCODE -ne 0){throw 'Recording pixel hashing failed'}
if(($sourceHashes -join ',') -ne ($outputHashes -join ',')){throw 'Decoded video changed during muxing'}
Write-Output 'PASS all 150 decoded frame hashes match the input video'
