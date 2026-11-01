$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Windows.Forms
$identity=[Security.Principal.WindowsIdentity]::GetCurrent()
$admin=([Security.Principal.WindowsPrincipal]$identity).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if(-not $admin) {
    Start-Process powershell.exe -Verb RunAs -WindowStyle Hidden -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',"`"$PSCommandPath`"")
    exit
}
try {
    if([System.Windows.Forms.MessageBox]::Show("Install RoadGuard Windows?`n`nThis installs a background server at Windows boot and opens the dashboard when you sign in. It adds a firewall rule limited to local/private VPN IPv4 networks. Backups start OFF. No public router port is opened.`n`nOnly continue using the Windows account that should own this installation.",'RoadGuard setup','YesNo') -ne 'Yes') {exit}
    $installDir=Join-Path $env:ProgramFiles 'RoadGuard'
    $dataDir=Join-Path $env:ProgramData 'RoadGuard'
    New-Item -ItemType Directory -Path $installDir,$dataDir -Force | Out-Null
    # Restrict credentials before writing them. Server runs as LocalService, not Administrator/SYSTEM.
    & icacls.exe $dataDir /inheritance:r /grant:r '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' '*S-1-5-19:(OI)(CI)M' "*$($identity.User.Value):(OI)(CI)M" | Out-Null
    if($LASTEXITCODE -ne 0){throw 'Could not protect server data directory'}
    Stop-ScheduledTask -TaskName RoadGuardServer -ErrorAction SilentlyContinue
    Get-Process RoadGuardDashboard -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq (Join-Path $installDir 'RoadGuardDashboard.exe') } | Stop-Process
    $deadline=(Get-Date).AddSeconds(10)
    while((Get-ScheduledTask -TaskName RoadGuardServer -ErrorAction SilentlyContinue).State -eq 'Running') {
        if((Get-Date) -gt $deadline){throw 'Existing server did not stop; close it and run setup again'}
        Start-Sleep -Milliseconds 250
    }
    foreach($name in @('node.exe','ffplay.exe','RoadGuardDashboard.exe','server.cjs','map-server.cjs','release.json','RoadGuardAI.apk','README.md','Uninstall.ps1','NODE-LICENSE.txt','FFMPEG-LICENSE.txt','FFMPEG-README.txt','THIRD-PARTY.md')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $installDir $name) -Force
    }
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'live-map') -Destination $installDir -Recurse -Force
    foreach($binary in @('ffmpeg.exe','ffprobe.exe')){Copy-Item -LiteralPath (Join-Path $PSScriptRoot $binary) -Destination $installDir -Force}
    $configPath=Join-Path $dataDir 'config.json'
    if(-not(Test-Path -LiteralPath $configPath)) {
        $rng=[Security.Cryptography.RandomNumberGenerator]::Create()
        function New-Secret { $bytes=New-Object byte[] 32; $rng.GetBytes($bytes); return ([BitConverter]::ToString($bytes)).Replace('-','').ToLowerInvariant() }
        $password=New-Secret
        $certificate=New-SelfSignedCertificate -DnsName RoadGuardLocal -CertStoreLocation Cert:\CurrentUser\My -KeyAlgorithm RSA -KeyLength 2048 -HashAlgorithm SHA256 -KeyExportPolicy Exportable -NotAfter (Get-Date).AddYears(5)
        try {
            Export-PfxCertificate -Cert $certificate -FilePath (Join-Path $dataDir 'server.pfx') -Password (ConvertTo-SecureString $password -AsPlainText -Force) | Out-Null
            Export-Certificate -Cert $certificate -FilePath (Join-Path $dataDir 'server.cer') | Out-Null
        } finally { Remove-Item -LiteralPath ("Cert:\CurrentUser\My\"+$certificate.Thumbprint) }
        @{pfxPassword=$password;adminToken=(New-Secret);port=8944;bind='0.0.0.0';phones=@();backupEnabled=$false;backupGB=10} |
            ConvertTo-Json | Set-Content -LiteralPath $configPath -Encoding Ascii
        $rng.Dispose()
    }
    $action=New-ScheduledTaskAction -Execute (Join-Path $installDir 'node.exe') -Argument "`"$(Join-Path $installDir 'server.cjs')`" `"$dataDir`""
    $settings=New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -RestartCount 10 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero)
    $principal=New-ScheduledTaskPrincipal -UserId 'S-1-5-19' -LogonType ServiceAccount
    Register-ScheduledTask -TaskName RoadGuardServer -Action $action -Trigger (New-ScheduledTaskTrigger -AtStartup) -Settings $settings -Principal $principal -Force | Out-Null
    $ui=New-ScheduledTaskAction -Execute (Join-Path $installDir 'RoadGuardDashboard.exe')
    Register-ScheduledTask -TaskName RoadGuardDashboard -Action $ui -Trigger (New-ScheduledTaskTrigger -AtLogOn -User $identity.Name) -Settings (New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries) -Principal (New-ScheduledTaskPrincipal -UserId $identity.Name -LogonType Interactive -RunLevel Limited) -Force | Out-Null
    if(-not(Get-NetFirewallRule -DisplayName 'RoadGuard private clients' -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule -DisplayName 'RoadGuard private clients' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8944 -RemoteAddress LocalSubnet,100.64.0.0/10 -Program (Join-Path $installDir 'node.exe') | Out-Null
    }
    if(-not(Get-NetFirewallRule -DisplayName 'RoadGuard LAN discovery' -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule -DisplayName 'RoadGuard LAN discovery' -Direction Inbound -Action Allow -Protocol UDP -LocalPort 8944 -RemoteAddress LocalSubnet -Program (Join-Path $installDir 'node.exe') | Out-Null
    }
    $desktop=[Environment]::GetFolderPath('Desktop')
    $shortcut=(New-Object -ComObject WScript.Shell).CreateShortcut((Join-Path $desktop 'RoadGuard.lnk'))
    $shortcut.TargetPath=Join-Path $installDir 'RoadGuardDashboard.exe';$shortcut.Save()
    Start-ScheduledTask -TaskName RoadGuardServer
    Start-ScheduledTask -TaskName RoadGuardDashboard
    [System.Windows.Forms.MessageBox]::Show("Installed. Update the car and phone with RoadGuardAI.apk from this package.`n`nPair the car in the dashboard, then generate a phone code. Enable GPS/private VPN access in car settings as needed.`n`nKeep Windows awake for remote access. The installer does not change sleep settings or install a VPN.",'RoadGuard')
} catch { [System.Windows.Forms.MessageBox]::Show($_.Exception.Message,'RoadGuard installation failed');exit 1 }
