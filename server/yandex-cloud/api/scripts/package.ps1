[CmdletBinding()]
param(
  [string]$OutputPath = (Join-Path $PSScriptRoot '..\artifacts\animix-yandex-api.zip')
)

$ErrorActionPreference = 'Stop'
$apiRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$stage = Join-Path $apiRoot '.package-stage'

if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
New-Item -ItemType Directory -Path $stage | Out-Null

Push-Location $apiRoot
try {
  npm ci
  npm run build
  Copy-Item package.json, package-lock.json -Destination $stage
  Copy-Item dist -Destination $stage -Recurse
  $parent = Split-Path -Parent $OutputPath
  New-Item -ItemType Directory -Path $parent -Force | Out-Null
  if (Test-Path -LiteralPath $OutputPath) { Remove-Item -LiteralPath $OutputPath -Force }
  Compress-Archive -Path (Join-Path $stage '*') -DestinationPath $OutputPath -CompressionLevel Optimal
  Write-Host "Function package: $OutputPath"
} finally {
  Pop-Location
  if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
}
