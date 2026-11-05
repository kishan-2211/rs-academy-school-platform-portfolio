param(
    [string]$ReportPath = (Join-Path $PSScriptRoot '../apps/api/target/restore-drill-report.json'),
    [datetime]$NotBefore = [datetime]::MinValue
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
if (-not (Test-Path -LiteralPath $ReportPath -PathType Leaf)) { throw 'Real database restore report is missing.' }
$report = Get-Content -LiteralPath $ReportPath -Raw | ConvertFrom-Json
$required = @('consistentDataRestore', 'schemaSecurityRestore', 'migrationHistoryRestore',
              'sequencePositionRestore', 'auditMutationDenied', 'corruptArchiveRejected',
              'restoredTenantIsolation', 'partialRlsBackupDenied', 'changedSchemaRejected',
              'weakenedTriggerWhenRejected', 'removedTriggerWhenRejected', 'changedTriggerFunctionRejected', 'disabledTriggerRejected')
$expectedMigrations = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot '../apps/api/src/main/resources/db/migration') -File -Filter 'V*__*.sql').Count
if ($report.format -ne 1 -or $report.invocationId -notmatch '^[a-f0-9]{32}$' -or
    $report.scope -cne 'synthetic-database-only' -or $report.postgresMajor -ne 18 -or
    $report.migrations -ne $expectedMigrations -or $report.tables -lt 20 -or $report.rows -lt 1 -or
    $report.sequences -lt 0 -or $report.rlsTables -lt 1 -or $report.policies -lt 1 -or
    $report.immutableTriggers -lt 1 -or $report.archiveSha256 -notmatch '^[a-f0-9]{64}$' -or
    $report.cleanupSucceeded -isnot [bool] -or -not $report.cleanupSucceeded -or
    $report.productionRpoRtoVerified -isnot [bool] -or $report.productionRpoRtoVerified -or
    $report.objectStorageRestoreVerified -isnot [bool] -or $report.objectStorageRestoreVerified -or
    $report.scenarios.Count -ne $required.Count -or $report.elapsedSeconds -le 0) {
    throw 'Real restore evidence is incomplete, failed or overstated.'
}
foreach ($scenario in $required) {
    if (@($report.scenarios | Where-Object { $_ -ceq $scenario }).Count -ne 1) {
        throw "Required actual restore scenario missing or duplicated: $scenario"
    }
}
$schemaEvidenceFields = @('sourceCatalogSha256', 'referenceCatalogSha256', 'restoredCatalogSha256',
                         'sourceTopologySha256', 'referenceTopologySha256', 'restoredTopologySha256',
                         'sourceTriggerSha256', 'referenceTriggerSha256', 'restoredTriggerSha256')
if (-not $report.PSObject.Properties['schemaEvidence']) { throw 'Complete source/reference/restored schema evidence is missing.' }
foreach ($field in $schemaEvidenceFields) {
    if (-not $report.schemaEvidence.PSObject.Properties[$field] -or $report.schemaEvidence.$field -notmatch '^[a-f0-9]{64}$') {
        throw 'A source/reference/restored schema digest is missing or malformed.'
    }
}
if ($report.schemaEvidence.referenceCatalogSha256 -cne $report.schemaEvidence.restoredCatalogSha256 -or
    $report.schemaEvidence.sourceTopologySha256 -cne $report.schemaEvidence.referenceTopologySha256 -or
    $report.schemaEvidence.referenceTopologySha256 -cne $report.schemaEvidence.restoredTopologySha256 -or
    $report.schemaEvidence.referenceTriggerSha256 -cne $report.schemaEvidence.restoredTriggerSha256) {
    throw 'Recorded full schema/trigger definitions or source/reference/restored topology do not agree.'
}
function Convert-ReportUtc($Value) {
    if ($Value -is [datetime]) { return $Value.ToUniversalTime() }
    if ($Value -is [datetimeoffset]) { return $Value.UtcDateTime }
    return [datetimeoffset]::Parse([string]$Value).UtcDateTime
}
$start = Convert-ReportUtc $report.startedAt
$finish = Convert-ReportUtc $report.finishedAt
if ($start -lt $NotBefore.ToUniversalTime() -or $finish -lt $start -or
    $finish -gt [datetime]::UtcNow.AddMinutes(1)) { throw 'Restore evidence is stale or has invalid timestamps.' }
Write-Host "Verified real synthetic restore: $($report.migrations) migrations, $($report.tables) tables, $($required.Count) completed scenarios, exact schema/trigger evidence, successful isolated cleanup. Production/object restore remains unverified."
