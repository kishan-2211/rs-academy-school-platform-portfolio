# Validation tests only; no mocks or fixtures count as a real database restore.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'restore-drill-common.ps1')
$rejected = 0
function Assert-Rejected([scriptblock]$Action) {
    $failed = $false
    try { & $Action | Out-Null } catch { $failed = $true }
    if (-not $failed) { throw 'Unsafe or incomplete restore input/evidence was accepted.' }
    $script:rejected++
}
$id = '0123456789abcdef0123456789abcdef'
Assert-RestoreDrillSource 'educore_acceptance_20260923' 'educore_acceptance_20260923' $true
Assert-RestoreDrillSource 'educore_integration' 'educore_integration' $true
Assert-RestoreDrillTarget "educore_restore_drill_$id" 'educore_integration' $id
Assert-Rejected { Assert-RestoreDrillSource 'school_production' 'school_production' $true }
Assert-Rejected { Assert-RestoreDrillSource 'educore_acceptance_20260923' 'postgres' $true }
Assert-Rejected { Assert-RestoreDrillSource 'educore_integration' 'educore_integration' $false }
Assert-Rejected { Assert-RestoreDrillSource 'educore_integration;DROP DATABASE school' 'educore_integration' $true }
Assert-Rejected { Assert-RestoreDrillTarget 'educore_integration' 'educore_integration' $id }
Assert-Rejected { Assert-RestoreDrillTarget "educore_restore_drill_$id" 'educore_integration' 'invalid' }
Assert-Rejected { Assert-RestoreDrillTarget "educore_restore_drill_$id" 'educore_integration' ('a' * 32) }
$project = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Assert-NativeRestoreDirectory (Join-Path $project ".build-tmp/native-restore-$id") $project $id
Assert-Rejected { Assert-NativeRestoreDirectory $project $project $id }
Assert-Rejected { Assert-NativeRestoreDirectory (Join-Path $project '.build-tmp/postgresql-data-installed') $project $id }
Assert-Rejected { Assert-NativeRestoreDirectory (Join-Path $project ".build-tmp/native-restore-$id/..") $project $id }
$tables = @(1..20 | ForEach-Object { @{ schema = 'public'; name = "synthetic_$_"; rows = 2; sha256 = 'a' * 64 } })
$valid = @{ format = 1; migrations = 58; failedMigrations = 0; rlsTables = 2; policies = 3;
    immutableTriggers = 1; catalogSha256 = 'b' * 64; catalogTopologySha256 = 'd' * 64;
    tables = $tables; sequences = @(); locale = @('UTF8','C','C','c',$null) }
function Copy-Fixture { return ($valid | ConvertTo-Json -Depth 8 | ConvertFrom-Json) }
Assert-RestoreDrillInventory (Copy-Fixture) (Copy-Fixture) 58
$reference = Copy-Fixture
$reference.migrations = 0
foreach ($table in $reference.tables) { $table.rows = 0 }
# Source text may legitimately differ after PostgreSQL reparses the independent
# DDL reference. The restored definitions must match that reference exactly.
$reference.catalogSha256 = 'e' * 64
$restored = Copy-Fixture; $restored.catalogSha256 = $reference.catalogSha256
Assert-RestoreDrillInventory (Copy-Fixture) $restored 58 $reference
$changedReference = $reference | ConvertTo-Json -Depth 8 | ConvertFrom-Json
$changedReference.catalogTopologySha256 = 'f' * 64
Assert-Rejected { Assert-RestoreDrillInventory (Copy-Fixture) $restored 58 $changedReference }
$changedReference = $reference | ConvertTo-Json -Depth 8 | ConvertFrom-Json
$changedReference.tables[0].rows = 1
Assert-Rejected { Assert-RestoreDrillInventory (Copy-Fixture) $restored 58 $changedReference }
$changedReference = $reference | ConvertTo-Json -Depth 8 | ConvertFrom-Json
$changedReference.tables[0].name = 'unexpected'
Assert-Rejected { Assert-RestoreDrillInventory (Copy-Fixture) $restored 58 $changedReference }
$changedReference = $reference | ConvertTo-Json -Depth 8 | ConvertFrom-Json
$changedReference.policies++
Assert-Rejected { Assert-RestoreDrillInventory (Copy-Fixture) $restored 58 $changedReference }
$changedReference = $reference | ConvertTo-Json -Depth 8 | ConvertFrom-Json
$changedReference.migrations = 58
Assert-Rejected { Assert-RestoreDrillInventory (Copy-Fixture) $restored 58 $changedReference }
$changed = Copy-Fixture; $changed.catalogSha256 = 'c' * 64
Assert-Rejected { Assert-RestoreDrillInventory (Copy-Fixture) $changed 58 $reference }
foreach ($field in @('catalogSha256', 'migrations', 'failedMigrations', 'rlsTables', 'policies', 'immutableTriggers')) {
    $changed = Copy-Fixture
    if ($field -eq 'catalogSha256') { $changed.$field = 'c' * 64 } else { $changed.$field++ }
    Assert-Rejected { Assert-RestoreDrillInventory (Copy-Fixture) $changed 58 }
}
$changed = Copy-Fixture; $changed.tables[0].rows++
Assert-Rejected { Assert-RestoreDrillInventory (Copy-Fixture) $changed 58 }
$changed = Copy-Fixture; $changed.tables[0].sha256 = 'c' * 64
Assert-Rejected { Assert-RestoreDrillInventory (Copy-Fixture) $changed 58 }
$changed = Copy-Fixture; $changed.sequences = @(@{name='synthetic_counter';lastValue=8;called=$true})
Assert-Rejected { Assert-RestoreDrillInventory (Copy-Fixture) $changed 58 }
$changed = Copy-Fixture; $changed.locale[1] = 'different'
Assert-Rejected { Assert-RestoreDrillInventory (Copy-Fixture) $changed 58 }
$changed = Copy-Fixture; $changed.tables[0].rows = -1
Assert-Rejected { Assert-RestoreDrillInventory $changed $changed 58 }
$fixture = [IO.Path]::GetTempFileName()
$guard = Join-Path $PSScriptRoot 'assert-database-restore-report.ps1'
try {
    $report = @{ format = 1; invocationId = $id; scope = 'synthetic-database-only'; postgresMajor = 18;
        migrations = @(Get-ChildItem (Join-Path $PSScriptRoot '../apps/api/src/main/resources/db/migration') -File -Filter 'V*__*.sql').Count;
        tables = 20; rows = 40; sequences = 0; rlsTables = 2; policies = 3; immutableTriggers = 1;
        archiveSha256 = 'a' * 64; cleanupSucceeded = $true; productionRpoRtoVerified = $false;
        objectStorageRestoreVerified = $false; elapsedSeconds = 1.2;
        schemaEvidence = @{
            sourceCatalogSha256='a'*64; referenceCatalogSha256='b'*64; restoredCatalogSha256='b'*64
            sourceTopologySha256='c'*64; referenceTopologySha256='c'*64; restoredTopologySha256='c'*64
            sourceTriggerSha256='d'*64; referenceTriggerSha256='e'*64; restoredTriggerSha256='e'*64
        }
        startedAt = [datetime]::UtcNow.AddSeconds(-3).ToString('O'); finishedAt = [datetime]::UtcNow.ToString('O');
        scenarios = @('consistentDataRestore','schemaSecurityRestore','migrationHistoryRestore',
            'sequencePositionRestore','auditMutationDenied','corruptArchiveRejected',
            'restoredTenantIsolation','partialRlsBackupDenied','changedSchemaRejected',
            'weakenedTriggerWhenRejected','removedTriggerWhenRejected','changedTriggerFunctionRejected','disabledTriggerRejected') }
    $report | ConvertTo-Json | Set-Content -LiteralPath $fixture
    & $guard -ReportPath $fixture 6>$null
    & $guard -ReportPath $fixture -NotBefore ([datetime]::UtcNow.AddSeconds(-4)) 6>$null
    foreach ($field in @('cleanupSucceeded','productionRpoRtoVerified','objectStorageRestoreVerified')) {
        $changedReport = $report.Clone(); $changedReport[$field] = -not $report[$field]
        $changedReport | ConvertTo-Json | Set-Content -LiteralPath $fixture
        Assert-Rejected { & $guard -ReportPath $fixture }
    }
    $changedReport = $report.Clone(); $changedReport.scenarios = @($report.scenarios[0]) * 7
    $changedReport | ConvertTo-Json | Set-Content -LiteralPath $fixture
    Assert-Rejected { & $guard -ReportPath $fixture }
    foreach ($scenario in @('weakenedTriggerWhenRejected','removedTriggerWhenRejected','changedTriggerFunctionRejected','disabledTriggerRejected')) {
        $changedReport = $report.Clone(); $changedReport.scenarios = @($report.scenarios | Where-Object { $_ -cne $scenario })
        $changedReport | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $fixture
        Assert-Rejected { & $guard -ReportPath $fixture }
    }
    $changedReport = $report.Clone(); $changedReport.Remove('schemaEvidence')
    $changedReport | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $fixture
    Assert-Rejected { & $guard -ReportPath $fixture }
    foreach ($field in $report.schemaEvidence.Keys) {
        $changedReport = $report.Clone(); $changedReport.schemaEvidence = $report.schemaEvidence.Clone()
        $changedReport.schemaEvidence[$field] = 'malformed'
        $changedReport | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $fixture
        Assert-Rejected { & $guard -ReportPath $fixture }
    }
    foreach ($field in @('restoredCatalogSha256','referenceTopologySha256','restoredTopologySha256','restoredTriggerSha256')) {
        $changedReport = $report.Clone(); $changedReport.schemaEvidence = $report.schemaEvidence.Clone()
        $changedReport.schemaEvidence[$field] = 'f'*64
        $changedReport | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $fixture
        Assert-Rejected { & $guard -ReportPath $fixture }
    }
    $report | ConvertTo-Json | Set-Content -LiteralPath $fixture
    Assert-Rejected { & $guard -ReportPath $fixture -NotBefore ([datetime]::UtcNow.AddMinutes(1)) }
    Assert-Rejected { & $guard -ReportPath ($fixture + '.missing') }
} finally { Remove-Item -LiteralPath $fixture }
$shellExecutable = (Get-Process -Id $PID).Path
$nonzero = Invoke-RestoreDrillProcess $shellExecutable @('-NoProfile','-NonInteractive','-Command','exit 7')
if ($nonzero.exitCode -ne 7) { throw 'A failed real utility process was not reported as failed.' }
Assert-Rejected { Invoke-RestoreDrillProcess $shellExecutable @('-NoProfile','-NonInteractive','-Command','Start-Sleep -Seconds 30') -TimeoutMs 150 }
Write-Host "Restore safety guards passed: valid inputs/evidence accepted; $rejected unsafe/incomplete/stale fixtures rejected. No real restore claimed."
