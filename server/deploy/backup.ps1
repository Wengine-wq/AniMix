<#
.SYNOPSIS
  Copies the nightly PostgreSQL dumps from the AniMix VPS to this PC.
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File backup.ps1 -Ip 185.1.2.3
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory)] [string]$Ip,
  [string]$Key = (Join-Path $env:USERPROFILE '.ssh\animix_vdsina'),
  [string]$Destination = (Join-Path $env:USERPROFILE 'AniMix-backups')
)
$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Path $Destination -Force | Out-Null
& scp -i $Key -o StrictHostKeyChecking=accept-new "root@${Ip}:/opt/animix/backups/*" $Destination
if ($LASTEXITCODE -ne 0) { throw 'Copy failed (no dumps yet? the first one appears a day after setup).' }
Write-Host "Backups are in $Destination"
