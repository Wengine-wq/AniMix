param(
    [string]$Adb = 'D:\DevTools\android-sdk\platform-tools\adb.exe',
    [string]$Serial,
    [ValidatePattern('^[a-zA-Z0-9._]+$')][string]$Package = 'com.example.animix.v3dev',
    [string]$KeyFile = (Join-Path $env:LOCALAPPDATA 'AniMix3\private\polza.key')
)
$ErrorActionPreference = 'Stop'
# Runtime-only provisioning. Credentials never become APK resources or command-line arguments.
if (-not (Test-Path -LiteralPath $KeyFile)) { throw 'Create the local runtime key file first; see docs/anipulse.md.' }
$devices = @(& $Adb devices | Select-String '^([^\s]+)\s+device$' | ForEach-Object { $_.Matches[0].Groups[1].Value })
if (-not $Serial) {
    if ($devices.Count -ne 1) { throw 'Connect one unlocked test device, or pass -Serial.' }
    $Serial = $devices[0]
}
if ($Serial -notin $devices) { throw 'Selected device is not connected or authorized.' }
$runtimeKey = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $KeyFile)).Trim()
if ([string]::IsNullOrWhiteSpace($runtimeKey)) { throw 'Runtime credential file is empty.' }
try {
    $runtimeKey | & $Adb -s $Serial shell "run-as $Package sh -c 'umask 077; mkdir -p no_backup; cat > no_backup/polza.key'"
    if ($LASTEXITCODE -ne 0) { throw 'Could not provision access. Install the debug APK first.' }
    Write-Output 'AniPulse preview access configured on the selected device.'
} finally { Remove-Variable runtimeKey -ErrorAction SilentlyContinue }
