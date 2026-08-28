[CmdletBinding()]
param(
  [Parameter(Mandatory)] [string]$Endpoint,
  [Parameter(Mandatory)] [string]$Database
)

$ErrorActionPreference = 'Stop'

$yc = Get-Command yc -ErrorAction SilentlyContinue
$ydb = Get-Command ydb -ErrorAction SilentlyContinue
if (-not $yc) { throw 'yc CLI is required to mint a short-lived IAM token.' }
if (-not $ydb) { throw 'YDB CLI is required. Install it from https://ydb.tech/docs/en/reference/ydb-cli/install' }

$schemaFile = (Resolve-Path (Join-Path $PSScriptRoot '..\schema\001_initial.yql')).Path
$tokenFile = Join-Path ([System.IO.Path]::GetTempPath()) "animix-ydb-$PID-$([guid]::NewGuid().ToString('N')).tmp"

try {
  # The IAM token is intentionally short-lived and exists only in this private
  # temporary file because YDB CLI accepts a token file, not stdin.
  [System.IO.File]::WriteAllText($tokenFile, ((& $yc.Source iam create-token).Trim()))
  & $ydb.Source --endpoint $Endpoint --database $Database --iam-token-file $tokenFile scripting yql --file $schemaFile
  if ($LASTEXITCODE -ne 0) { throw "YDB schema command failed with exit code $LASTEXITCODE." }
  Write-Host 'AniMix YDB schema applied successfully.' -ForegroundColor Green
} finally {
  if (Test-Path -LiteralPath $tokenFile) { [System.IO.File]::Delete($tokenFile) }
}
