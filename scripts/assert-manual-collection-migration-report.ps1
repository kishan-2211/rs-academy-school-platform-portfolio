param([string]$ReportPath = 'apps/api/target/failsafe-reports/TEST-com.educore.api.finance.ManualCollectionMigrationPostgresIT.xml')
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $ReportPath)) { throw 'Real legacy manual-collection migration PostgreSQL report missing.' }
[xml]$manualReport = Get-Content -LiteralPath $ReportPath -Raw
$manualSuite = $manualReport.testsuite
$manualClass = 'com.educore.api.finance.ManualCollectionMigrationPostgresIT'
$manualRequired = @(
    'realForwardMigrationPreservesDuplicateAndReversedHistoryWithoutBackfill',
    'exactOldDigitalRequestsRetryOriginalReceiptsWithoutNewAttestationOrWrites',
    'changedOldRetryAndNewCrossMethodReuseCannotOverwriteHistoricalCredits',
    'historicalRowsRemainNonOwnerTenantScopedAndImmutableAfterUpgrade',
    'newVerifiedCreditContinuesTheExistingSequenceWithoutInventingHistoricalEvidence'
)
foreach ($manualAttribute in @('tests','failures','errors','skipped')) {
    if ($null -eq $manualSuite -or $manualSuite.GetAttribute($manualAttribute) -cnotmatch '^(0|[1-9][0-9]*)$') {
        throw 'Legacy manual-collection report counts invalid.'
    }
}
if ($manualSuite.name -cne $manualClass -or [int]$manualSuite.tests -lt $manualRequired.Count -or
    @($manualSuite.testcase).Count -ne [int]$manualSuite.tests -or [int]$manualSuite.failures -ne 0 -or
    [int]$manualSuite.errors -ne 0 -or [int]$manualSuite.skipped -ne 0) {
    throw 'Legacy manual-collection migration report failed, incomplete or skipped.'
}
foreach ($manualCase in $manualSuite.testcase) {
    if ([string]::IsNullOrWhiteSpace($manualCase.name) -or $manualCase.classname -cne $manualClass -or
        $null -ne $manualCase.SelectSingleNode('skipped|failure|error')) {
        throw 'Legacy manual-collection scenario has invalid identity or an uncounted failure/skip.'
    }
}
foreach ($manualName in $manualRequired) {
    if (@($manualSuite.testcase | Where-Object { $_.name -ceq $manualName }).Count -ne 1) {
        throw "Required legacy manual-collection scenario missing or duplicated: $manualName"
    }
}
Write-Output "Real V069-to-V070 manual-collection PostgreSQL upgrade gate passed: $($manualSuite.tests) cases. This is not browser or full payment-provider acceptance."
