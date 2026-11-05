param([Parameter(Mandatory)][ValidateSet('web','api')][string]$Service)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'runtime-image-security-common.ps1')
$repository = (Resolve-Path (Split-Path $PSScriptRoot -Parent)).Path
$path = Join-Path $repository ".build-tmp/image-security-$Service-report.json"
if (-not (Test-Path -LiteralPath $path)) { throw 'Actual image scan report is missing; no security pass is claimed.' }
$commit = (& git -C $repository rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot verify the source commit.' }
$hash = (Get-FileHash -LiteralPath (Join-Path $repository "apps/$Service/Dockerfile") -Algorithm SHA256).Hash.ToLowerInvariant()
$report = ConvertFrom-ImageSecurityJson ([IO.File]::ReadAllText($path))
Assert-ImageSecurityReport $report $Service $commit $hash -RequireClean
Write-Output "Actual $Service runtime image security gate passed: current source, validated fresh database, no HIGH/CRITICAL, unknown-severity or distribution-lifecycle findings. This is not penetration testing or deployment approval."
