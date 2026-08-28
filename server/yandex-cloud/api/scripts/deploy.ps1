[CmdletBinding()]
param(
  [Parameter(Mandatory)] [string]$FolderId,
  [Parameter(Mandatory)] [string]$FunctionServiceAccountId,
  [Parameter(Mandatory)] [string]$GatewayServiceAccountId,
  [Parameter(Mandatory)] [string]$PlatformLockboxSecretId,
  [Parameter(Mandatory)] [string]$OAuthLockboxSecretId,
  [Parameter(Mandatory)] [string]$YdbEndpoint,
  [Parameter(Mandatory)] [string]$YdbDatabase,
  [Parameter(Mandatory)] [string]$MediaBucket,
  [Parameter(Mandatory)] [string]$GoogleClientId,
  [Parameter(Mandatory)] [string]$ShikimoriClientId,
  [string]$GoogleRedirectUri,
  [string]$FunctionName = 'animix-user-api',
  [string]$GatewayName = 'animix-user-api',
  [string]$AllowedWebOrigin = 'https://animix.app'
)

$ErrorActionPreference = 'Stop'

function Get-YcResource([string[]]$Arguments) {
  # `get` returning "not found" is normal when this is the first deploy.
  # Do not let PowerShell promote that expected yc exit code to a terminating
  # NativeCommandError under the global Stop preference.
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

if (-not (Get-Command yc -ErrorAction SilentlyContinue)) {
  throw 'Yandex Cloud CLI (yc) is not installed. Install and initialize it first: https://yandex.cloud/en/docs/cli/quickstart'
}

$apiRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$artifacts = Join-Path $apiRoot 'artifacts'

$function = Get-YcResource @('serverless', 'function', 'get', '--name', $FunctionName, '--folder-id', $FolderId)
if (-not $function) {
  & yc serverless function create --name $FunctionName --description 'AniMix user API' --folder-id $FolderId | Out-Host
  $function = Get-YcResource @('serverless', 'function', 'get', '--name', $FunctionName, '--folder-id', $FolderId)
}
if (-not $function.id) { throw 'Unable to resolve AniMix Cloud Function ID.' }

# The function remains private; only this gateway service account may invoke it.
# Repeating add-access-binding is harmlessly rejected by YC if it already exists.
& yc serverless function add-access-binding --id $function.id --role functions.functionInvoker --subject "serviceAccount:$GatewayServiceAccountId" --folder-id $FolderId 2>$null
if ($LASTEXITCODE -ne 0) {
  Write-Warning 'Could not add functions.functionInvoker automatically. Ensure the gateway service account has that role on this function.'
}
foreach ($secretId in @($PlatformLockboxSecretId, $OAuthLockboxSecretId)) {
  & yc lockbox secret add-access-binding --id $secretId --role lockbox.payloadViewer --subject "serviceAccount:$FunctionServiceAccountId" --folder-id $FolderId 2>$null
  if ($LASTEXITCODE -ne 0) {
    Write-Warning "Could not add lockbox.payloadViewer automatically for $secretId. Ensure the runtime service account can read it."
  }
}

$gatewayTemplate = Join-Path $apiRoot 'gateway.openapi.tmpl.yaml'
$gatewaySpec = Join-Path $artifacts 'gateway.openapi.yaml'
New-Item -ItemType Directory -Path $artifacts -Force | Out-Null
$spec = Get-Content -Raw -LiteralPath $gatewayTemplate
$spec = $spec.Replace('${FUNCTION_ID}', [string]$function.id)
$spec = $spec.Replace('${GATEWAY_SERVICE_ACCOUNT_ID}', $GatewayServiceAccountId)
Set-Content -LiteralPath $gatewaySpec -Value $spec -Encoding utf8NoBOM

$gateway = Get-YcResource @('serverless', 'api-gateway', 'get', '--name', $GatewayName, '--folder-id', $FolderId)
if (-not $gateway) {
  & yc serverless api-gateway create --name $GatewayName --description 'AniMix public user API' --execution-timeout 15s --spec $gatewaySpec --folder-id $FolderId | Out-Host
} else {
  & yc serverless api-gateway update --id $gateway.id --spec $gatewaySpec --folder-id $FolderId | Out-Host
}
$gateway = Get-YcResource @('serverless', 'api-gateway', 'get', '--name', $GatewayName, '--folder-id', $FolderId)
if (-not $gateway.domain) { throw 'Unable to resolve API Gateway public domain.' }
$apiOrigin = "https://$($gateway.domain)"
$expectedRedirect = "$apiOrigin/v1/auth/google/callback"

if ([string]::IsNullOrWhiteSpace($GoogleRedirectUri)) {
  Write-Host "`nGateway is ready: $apiOrigin" -ForegroundColor Green
  Write-Host "Add this exact Authorized redirect URI in Google Cloud OAuth settings:" -ForegroundColor Yellow
  Write-Host "  $expectedRedirect" -ForegroundColor Cyan
  Write-Host "Then run this script again with -GoogleRedirectUri '$expectedRedirect'. No API version was deployed yet."
  return
}
if ($GoogleRedirectUri.TrimEnd('/') -ne $expectedRedirect) {
  throw "GOOGLE_REDIRECT_URI must exactly equal the deployed gateway URL: $expectedRedirect"
}

& (Join-Path $PSScriptRoot 'package.ps1') -OutputPath (Join-Path $artifacts 'animix-yandex-api.zip')
$package = Join-Path $artifacts 'animix-yandex-api.zip'

$environment = @(
  "YDB_ENDPOINT=$YdbEndpoint",
  "YDB_DATABASE=$YdbDatabase",
  "MEDIA_BUCKET=$MediaBucket",
  "MEDIA_PUBLIC_BASE_URL=https://storage.yandexcloud.net/$MediaBucket",
  "GOOGLE_CLIENT_ID=$GoogleClientId",
  "GOOGLE_REDIRECT_URI=$GoogleRedirectUri",
  "SHIKIMORI_CLIENT_ID=$ShikimoriClientId",
  'ALLOWED_APP_RETURN_URI=animix://oauth/callback',
  "ANIMIX_WEB_ORIGIN=$AllowedWebOrigin"
) -join ','

$secretBindings = @(
  @{ environment = 'GOOGLE_CLIENT_SECRET'; key = 'GOOGLE_CLIENT_SECRET'; id = $OAuthLockboxSecretId },
  @{ environment = 'SHIKIMORI_CLIENT_SECRET'; key = 'SHIKIMORI_CLIENT_SECRET'; id = $OAuthLockboxSecretId },
  @{ environment = 'AUTH_TOKEN_PEPPER'; key = 'AUTH_TOKEN_PEPPER'; id = $PlatformLockboxSecretId },
  @{ environment = 'MEDIA_ACCESS_KEY_ID'; key = 'MEDIA_ACCESS_KEY_ID'; id = $PlatformLockboxSecretId },
  @{ environment = 'MEDIA_SECRET_ACCESS_KEY'; key = 'MEDIA_SECRET_ACCESS_KEY'; id = $PlatformLockboxSecretId }
)

$arguments = @(
  'serverless', 'function', 'version', 'create',
  '--function-id', $function.id,
  '--runtime', 'nodejs22',
  '--entrypoint', 'dist/index.handler',
  '--memory', '256MB',
  '--execution-timeout', '15s',
  '--concurrency', '4',
  '--service-account-id', $FunctionServiceAccountId,
  '--metadata-options', 'gce-http-endpoint=enabled',
  '--source-path', $package,
  '--environment', $environment,
  '--folder-id', $FolderId
)
foreach ($binding in $secretBindings) {
  $arguments += '--secret'
  $arguments += "environment-variable=$($binding.environment),id=$($binding.id),key=$($binding.key)"
}
& yc @arguments | Out-Host

Write-Host "`nAniMix API deployed: $apiOrigin" -ForegroundColor Green
Write-Host "Health check: $apiOrigin/v1/health" -ForegroundColor Green
Write-Host "Set ANIMIX_API_BASE_URL=$apiOrigin in the release build only after the health check returns ok:true." -ForegroundColor Yellow
