# Run elevated. Retains all recordings and credentials for recovery; does not delete user footage.
$ErrorActionPreference='Stop'
Stop-ScheduledTask -TaskName RoadGuardServer -ErrorAction SilentlyContinue
Unregister-ScheduledTask -TaskName RoadGuardServer -Confirm:$false -ErrorAction SilentlyContinue
Unregister-ScheduledTask -TaskName RoadGuardDashboard -Confirm:$false -ErrorAction SilentlyContinue
Get-NetFirewallRule -DisplayName 'RoadGuard private clients' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
Write-Output 'Automatic startup and inbound rule removed. Program files, pairing data and PC recordings are retained. Close the dashboard if it is open.'
