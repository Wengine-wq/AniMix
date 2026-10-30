<#
.SYNOPSIS
  Two app installations against the real server: a temporary account with two sessions is created
  on the VPS, AccountSyncLiveTest runs both installations through it, and the account is removed.
  Tokens stay in memory (an environment variable of the test run) and expire in 15 minutes.
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File server\deploy\live-sync-test.ps1 -Ip 193.124.64.116
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory)] [string]$Ip,
  [string]$Key = (Join-Path $env:USERPROFILE '.ssh\animix_vdsina')
)
$ErrorActionPreference = 'Stop'
$ssh = @('-i', $Key, '-o', 'StrictHostKeyChecking=accept-new', '-o', 'ConnectTimeout=20')
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$fixtureScript = Join-Path $repo 'server\scripts\sync-fixture.cjs'

& scp @ssh $fixtureScript "root@${Ip}:/opt/animix/sync-fixture.cjs"
if ($LASTEXITCODE -ne 0) { throw 'Upload failed' }
$prepare = 'cd /opt/animix && docker compose exec -T -u root api mkdir -p /app/scripts && docker compose cp sync-fixture.cjs api:/app/scripts/sync-fixture.cjs >/dev/null'
& ssh @ssh "root@$Ip" $prepare
if ($LASTEXITCODE -ne 0) { throw 'Could not copy the fixture script into the API container' }
$fixture = (& ssh @ssh "root@$Ip" 'cd /opt/animix && docker compose exec -T api node scripts/sync-fixture.cjs') -join ''
if ($LASTEXITCODE -ne 0 -or -not $fixture.StartsWith('{')) { throw 'Could not create the test account' }
$id = ($fixture | ConvertFrom-Json).id
try {
  $env:ANIMIX_SYNC_LIVE_FIXTURE = $fixture
  $env:JAVA_HOME = 'D:\DevTools\jdk-21.0.12.1+1'
  Push-Location $repo
  & .\gradlew.bat :shared:desktopTest --tests 'app.animix.data.account.AccountSyncLiveTest' --rerun -q --console=plain
  $code = $LASTEXITCODE
  Pop-Location
  if ($code -ne 0) { throw "Live sync test failed ($code)" }
  Write-Host 'Live sync test passed' -ForegroundColor Green
} finally {
  Remove-Item Env:ANIMIX_SYNC_LIVE_FIXTURE -ErrorAction SilentlyContinue
  & ssh @ssh "root@$Ip" "cd /opt/animix && docker compose exec -T api node scripts/sync-fixture.cjs cleanup $id"
}
