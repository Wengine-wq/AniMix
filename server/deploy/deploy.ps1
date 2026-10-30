<#
.SYNOPSIS
  Deploys the AniMix API to a fresh Ubuntu 24.04 VPS (VDSina or any other) from this PC.

.DESCRIPTION
  Uploads the server source and this folder to /opt/animix and runs setup.sh: swap, firewall,
  Docker, PostgreSQL, HTTPS and nightly backups. The database starts empty: accounts are created
  on this server, and people bring their AniMix 2.x lists from the old app's local data.

  /opt/animix/.env is created once: the Google and Shikimori OAuth clients are read from the
  existing Yandex Cloud Function with the `yc` CLI (never printed); the token pepper and the
  database password are new random values for this server.

  Without a domain the API gets https://<ip-with-dashes>.sslip.io (a free name that points at the IP).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File deploy.ps1 -Ip 185.1.2.3
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory)] [string]$Ip,
  [string]$Domain,
  [string]$Key = (Join-Path $env:USERPROFILE '.ssh\animix_vdsina'),
  [switch]$RefreshSecrets,
  [string]$FunctionName = 'animix-user-api'
)
$ErrorActionPreference = 'Stop'

if (-not (Test-Path $Key)) { throw "SSH key not found: $Key" }
if (-not $Domain) { $Domain = ($Ip -replace '\.', '-') + '.sslip.io' }
$target = "root@$Ip"
# Keepalives: the first image build is silent for minutes and an idle session gets dropped.
$sshOptions = @('-i', $Key, '-o', 'StrictHostKeyChecking=accept-new', '-o', 'ConnectTimeout=20', '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=8')
$here = $PSScriptRoot
$apiSource = (Resolve-Path (Join-Path $here '..')).Path

function Invoke-Remote([string]$Command, [string]$InputText) {
  if ($InputText) { $InputText | & ssh @sshOptions $target $Command } else { & ssh @sshOptions $target $Command }
  if ($LASTEXITCODE -ne 0) { throw "Remote command failed ($LASTEXITCODE): $Command" }
}

function Copy-ToServer([string]$Local, [string]$Remote) {
  & scp @sshOptions $Local "${target}:$Remote"
  if ($LASTEXITCODE -ne 0) { throw "Upload failed: $Local" }
}

Write-Host "== Server $Ip, address https://$Domain"
Invoke-Remote 'mkdir -p /opt/animix && uname -sr'

Write-Host '== Packing the server source'
$stage = Join-Path ([IO.Path]::GetTempPath()) ("animix-deploy-" + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $stage 'api') | Out-Null
try {
  foreach ($item in Get-ChildItem -LiteralPath $apiSource -Force) {
    if (@('node_modules', 'dist', 'deploy', 'backups', 'media', '.env') -contains $item.Name) { continue }
    Copy-Item -LiteralPath $item.FullName -Destination (Join-Path $stage 'api') -Recurse
  }
  foreach ($name in @('docker-compose.yml', 'setup.sh')) { Copy-Item -LiteralPath (Join-Path $here $name) -Destination $stage }
  # Shell scripts must keep Unix line endings.
  $setup = Join-Path $stage 'setup.sh'
  [IO.File]::WriteAllText($setup, ([IO.File]::ReadAllText($setup) -replace "`r`n", "`n"))
  $bundle = Join-Path ([IO.Path]::GetTempPath()) 'animix-bundle.tgz'
  & tar -czf $bundle -C $stage .
  if ($LASTEXITCODE -ne 0) { throw 'tar failed' }
} finally { Remove-Item -LiteralPath $stage -Recurse -Force }
Copy-ToServer $bundle '/opt/animix/bundle.tgz'
Remove-Item -LiteralPath $bundle -Force
Invoke-Remote 'cd /opt/animix && rm -rf api && tar -xzf bundle.tgz && rm bundle.tgz'

$hasEnv = (& ssh @sshOptions $target 'test -f /opt/animix/.env && echo yes') -eq 'yes'
if (-not $hasEnv -or $RefreshSecrets) {
  Write-Host '== OAuth clients from the Yandex Cloud Function (not shown), new pepper and database password'
  if (-not (Get-Command yc -ErrorAction SilentlyContinue)) { throw 'Yandex Cloud CLI (yc) is required to read the current settings.' }
  $version = ((& yc serverless function version get-by-tag --function-name $FunctionName --tag '$latest' --format json) -join "`n") | ConvertFrom-Json
  # Only what this server needs: the OAuth clients. No YDB, no media storage keys.
  $wanted = @('GOOGLE_CLIENT_ID', 'GOOGLE_CLIENT_SECRET', 'SHIKIMORI_CLIENT_ID', 'SHIKIMORI_CLIENT_SECRET', 'ALLOWED_APP_RETURN_URI', 'ANIMIX_WEB_ORIGIN')
  $values = [ordered]@{}
  foreach ($property in $version.environment.PSObject.Properties) {
    if ($wanted -contains $property.Name) { $values[$property.Name] = [string]$property.Value }
  }
  foreach ($group in ($version.secrets | Where-Object { $wanted -contains $_.environment_variable } | Group-Object id)) {
    $payload = ((& yc lockbox payload get --id $group.Name --format json) -join "`n") | ConvertFrom-Json
    foreach ($secret in $group.Group) {
      $entry = $payload.entries | Where-Object { $_.key -eq $secret.key } | Select-Object -First 1
      if (-not $entry) { throw "Lockbox entry missing: $($secret.key)" }
      $values[$secret.environment_variable] = [string]$entry.text_value
    }
  }
  function New-Secret { $bytes = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes); ([Convert]::ToBase64String($bytes) -replace '[^A-Za-z0-9]', '') }
  function Get-Existing([string]$Name) { if ($hasEnv) { & ssh @sshOptions $target "grep '^${Name}=' /opt/animix/.env | cut -d= -f2-" } }
  # Kept across -RefreshSecrets: changing them would lock out the database or every session.
  $password = Get-Existing 'POSTGRES_PASSWORD'; if (-not $password) { $password = New-Secret }
  $pepper = Get-Existing 'AUTH_TOKEN_PEPPER'; if (-not $pepper) { $pepper = New-Secret }
  $values['API_DOMAIN'] = $Domain
  $values['POSTGRES_PASSWORD'] = $password
  $values['AUTH_TOKEN_PEPPER'] = $pepper
  $values['GOOGLE_REDIRECT_URI'] = "https://$Domain/v1/auth/google/callback"
  $lines = foreach ($name in $values.Keys) { "$name=$($values[$name])" }
  $envFile = Join-Path ([IO.Path]::GetTempPath()) ('animix-env-' + [Guid]::NewGuid().ToString('N'))
  try {
    [IO.File]::WriteAllText($envFile, (($lines -join "`n") + "`n"))
    Copy-ToServer $envFile '/opt/animix/.env'
  } finally { Remove-Item -LiteralPath $envFile -Force -ErrorAction SilentlyContinue }
  Invoke-Remote 'chmod 600 /opt/animix/.env'
} else {
  Write-Host '== Keeping the existing /opt/animix/.env (use -RefreshSecrets to rebuild it)'
  Invoke-Remote "sed -i 's|^API_DOMAIN=.*|API_DOMAIN=$Domain|; s|^GOOGLE_REDIRECT_URI=.*|GOOGLE_REDIRECT_URI=https://$Domain/v1/auth/google/callback|' /opt/animix/.env"
}

Write-Host '== Setup on the server (first run takes a few minutes)'
Invoke-Remote 'cd /opt/animix && bash setup.sh'

Write-Host '== Checking https://' -NoNewline; Write-Host "$Domain/health"
$ok = $false
for ($i = 0; $i -lt 20 -and -not $ok; $i++) {
  try {
    $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 10 -Uri "https://$Domain/health"
    $ok = $response.StatusCode -eq 200
  } catch { Start-Sleep -Seconds 6 }
}
if ($ok) { Write-Host "API is live: https://$Domain" -ForegroundColor Green }
else { Write-Warning "HTTPS is not answering yet. Certificates can take a minute; check: ssh -i $Key $target 'cd /opt/animix && docker compose logs caddy api'" }

Write-Host ''
Write-Host 'Next steps (once):'
Write-Host "  1. Google Cloud Console -> APIs & Services -> Credentials -> AniMix OAuth client -> Authorized redirect URIs: add https://$Domain/v1/auth/google/callback"
Write-Host "  2. AniMix New Horizons uses this address as its account server (AniMixServer.BASE_URL)."
