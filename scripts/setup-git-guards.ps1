$ErrorActionPreference = 'Stop'
Push-Location "$PSScriptRoot/.."
try {
    & git rev-parse --show-toplevel | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Run inside the AniMix Git checkout.' }
    & git config --local core.hooksPath .githooks
    if ($LASTEXITCODE -ne 0) { throw 'Could not configure Git hooks.' }
    Write-Output 'Playback guards enabled for commit and push in this checkout.'
} finally { Pop-Location }
