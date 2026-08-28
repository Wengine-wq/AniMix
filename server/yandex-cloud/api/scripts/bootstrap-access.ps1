[CmdletBinding()]
param(
  [Parameter(Mandatory)] [string]$FolderId,
  [string]$RuntimeServiceAccountName = 'animix-api-runtime',
  [string]$GatewayServiceAccountName = 'animix-api-gateway'
)

$ErrorActionPreference = 'Stop'

function Get-YcResource([string[]]$Arguments) {
  # A missing resource is an expected first-run result. PowerShell 7 otherwise
  # turns yc's non-zero exit code into a terminating NativeCommandError because
  # the script deliberately uses ErrorActionPreference=Stop everywhere else.
  $previousPreference = $ErrorActionPreference
  try {
    $ErrorActionPreference = 'Continue'
    $result = & yc @Arguments --format json 2>$null
    $exitCode = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $previousPreference
  }
  if ($exitCode -ne 0 -or [string]::IsNullOrWhiteSpace($result)) { return $null }
  return $result | ConvertFrom-Json
}

function Ensure-ServiceAccount([string]$Name) {
  $account = Get-YcResource @('iam', 'service-account', 'get', '--name', $Name, '--folder-id', $FolderId)
  if (-not $account) {
    & yc iam service-account create --name $Name --folder-id $FolderId | Out-Host
    $account = Get-YcResource @('iam', 'service-account', 'get', '--name', $Name, '--folder-id', $FolderId)
  }
  if (-not $account.id) { throw "Could not resolve service account '$Name'." }
  return $account
}

if (-not (Get-Command yc -ErrorAction SilentlyContinue)) {
  throw 'Yandex Cloud CLI (yc) is not installed. Install and initialize it first: https://yandex.cloud/en/docs/cli/quickstart'
}

$runtime = Ensure-ServiceAccount $RuntimeServiceAccountName
$gateway = Ensure-ServiceAccount $GatewayServiceAccountName

# These broad folder roles get the first controlled release moving. Once the
# smoke test passes, replace storage.editor/ydb.editor with resource-level
# bindings on the AniMix bucket and the AniMix YDB database.
foreach ($role in @('ydb.editor', 'storage.editor')) {
  & yc resource-manager folder add-access-binding $FolderId --role $role --subject "serviceAccount:$($runtime.id)" 2>$null
  if ($LASTEXITCODE -ne 0) { Write-Warning "Could not add $role automatically; grant it to $($runtime.id) manually." }
}

Write-Host "`nRuntime service account ID: $($runtime.id)" -ForegroundColor Green
Write-Host "Gateway service account ID: $($gateway.id)" -ForegroundColor Green
Write-Host "Create one Lockbox secret with GOOGLE_CLIENT_SECRET, SHIKIMORI_CLIENT_SECRET, AUTH_TOKEN_PEPPER, MEDIA_ACCESS_KEY_ID and MEDIA_SECRET_ACCESS_KEY. Then run deploy.ps1 with the IDs above." -ForegroundColor Yellow
