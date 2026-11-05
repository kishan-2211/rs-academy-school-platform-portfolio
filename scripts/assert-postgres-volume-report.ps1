param([string]$ReportPath = 'apps/api/target/postgres-volume-report.json', [datetime]$NotBefore = [datetime]::MinValue)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'postgres-volume-common.ps1')
if (-not (Test-Path -LiteralPath $ReportPath -PathType Leaf)) { throw 'Real PostgreSQL persistence report is missing.' }
$repository = Split-Path $PSScriptRoot -Parent
$hash = (Get-FileHash -LiteralPath (Join-Path $repository 'infra/compose.yaml') -Algorithm SHA256).Hash.ToLowerInvariant()
$report = ConvertFrom-PostgresVolumeReport (Get-Content -LiteralPath $ReportPath -Raw)
Assert-PostgresVolumeReport $report $hash $NotBefore
Write-Host 'Verified four real PostgreSQL persistence/legacy/cleanup scenarios for the current Compose source.'
