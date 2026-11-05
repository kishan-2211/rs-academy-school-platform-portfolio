param(
    [string]$ReportPath = 'apps/api/target/failsafe-reports/TEST-com.educore.api.config.ProductionRuntimePostgresIT.xml'
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $ReportPath)) { throw 'Real production-runtime PostgreSQL report is missing.' }
[xml]$report = Get-Content -LiteralPath $ReportPath -Raw
$suite = $report.testsuite
$required = @(
    'realProductionRuntimeLogsInAndKeepsFamilyReadsObjectScoped',
    'realProductionRuntimeHasNoFallbackUserAndRejectsBasicOrFormLogin',
    'realRuntimePoolClearsScopeAndDeniesUnscopedForeignAndDdlAccess',
    'realRoleGuardRejectsOwnerInheritedOwnerBypassAndDdlGrants',
    'realMigrationToolRetriesWithoutNewMigrationsAndRejectsOwnerOrMissingRuntime',
    'realRoleGuardRejectsDisabledRlsTruncateAndMigrationHistoryPrivileges',
    'realRuntimeReadsOnlyInvokerNotificationProofViewsWithoutWritePrivileges',
    'realProductionRestrictedMfaGatePreservesExactSelfServiceAndCsrfBoundaries',
    'realProductionMfaEnrollmentDoesNotElevateOldSessionsAndRequiresVerifiedFactorChanges',
    'realProductionMfaGateUsesLiveGrantsSelectedOrganisationAndSameOrganisationRoles',
    'realProductionEnrollmentWaitsForAccountLockAndRechecksConcurrentActivation',
    'realProductionMfaHistoryMigrationPreservesRecordsAndConstraints'
)
if ($null -eq $suite -or $suite.name -cne 'com.educore.api.config.ProductionRuntimePostgresIT' -or
    [int]$suite.tests -lt $required.Count -or [int]$suite.skipped -ne 0 -or
    [int]$suite.errors -ne 0 -or [int]$suite.failures -ne 0) {
    throw 'Real production-runtime PostgreSQL report is incomplete, failed or skipped.'
}
foreach ($name in $required) {
    $cases = @($suite.testcase | Where-Object { $_.name -ceq $name })
    if ($cases.Count -ne 1 -or $cases[0].classname -cne 'com.educore.api.config.ProductionRuntimePostgresIT' -or
        $null -ne $cases[0].SelectSingleNode('skipped|failure|error')) {
        throw "Required real runtime scenario did not pass exactly once: $name"
    }
}
Write-Output "Real production-runtime PostgreSQL gate passed: $($suite.tests) scenarios, no failures/errors/skips."
