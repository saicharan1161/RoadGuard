$ErrorActionPreference='Stop'
& node (Join-Path $PSScriptRoot 'map-tests.cjs')
if($LASTEXITCODE -ne 0){throw 'Map tests failed'}
$scratch=Join-Path $env:TEMP ('roadguard-server-test-'+[guid]::NewGuid())
New-Item -ItemType Directory -Path $scratch | Out-Null
$cert=New-SelfSignedCertificate -DnsName RoadGuardTest -CertStoreLocation Cert:\CurrentUser\My -KeyAlgorithm RSA -KeyLength 2048 -KeyExportPolicy Exportable -NotAfter (Get-Date).AddDays(2)
try {
    Export-PfxCertificate -Cert $cert -FilePath (Join-Path $scratch 'server.pfx') -Password (ConvertTo-SecureString 'test-only-password' -AsPlainText -Force) | Out-Null
    Export-Certificate -Cert $cert -FilePath (Join-Path $scratch 'server.cer') | Out-Null
} finally {Remove-Item -LiteralPath ('Cert:\CurrentUser\My\'+$cert.Thumbprint)}
& node (Join-Path $PSScriptRoot 'tests.cjs') $scratch
if($LASTEXITCODE -ne 0){throw 'Server tests failed'}
Write-Output "Isolated test fixtures: $scratch"
