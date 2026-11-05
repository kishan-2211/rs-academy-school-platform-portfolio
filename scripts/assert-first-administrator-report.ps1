param([string]$ReportPath = 'apps/api/target/failsafe-reports/TEST-com.educore.api.identity.FirstAdministratorPostgresIT.xml')
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $ReportPath)) { throw 'Real first-administrator PostgreSQL report is missing.' }
[xml]$report = Get-Content -LiteralPath $ReportPath -Raw
$suite = $report.testsuite
$expectedClass = 'com.educore.api.identity.FirstAdministratorPostgresIT'
$required = @(
    'realFirstAdministratorCreatesScopedIdentityWithHonestImmutableAudit',
    'realFirstAdministratorRefusesExistingIdentitiesAndNeverResetsRetry',
    'realFirstAdministratorRejectsWrongScopeRoleOrOperator',
    'realFirstAdministratorRequiresExactFullyMigratedRelease',
    'realFirstAdministratorRollsBackEveryWriteWhenAuditFails',
    'realFirstAdministratorSerializesCompetingToolsAndOrdinaryInserts'
)
foreach ($attribute in @('tests','skipped','errors','failures')) {
    if ($null -eq $suite -or $suite.GetAttribute($attribute) -cnotmatch '^(0|[1-9][0-9]*)$') {
        throw 'Real first-administrator PostgreSQL report has missing or invalid counts.'
    }
}
if ($null -eq $suite -or $suite.name -cne $expectedClass -or [int]$suite.tests -lt $required.Count -or
    [int]$suite.skipped -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.failures -ne 0 -or
    @($suite.testcase).Count -ne [int]$suite.tests) {
    throw 'Real first-administrator PostgreSQL report is incomplete, failed or skipped.'
}
foreach ($case in $suite.testcase) {
    if ([string]::IsNullOrWhiteSpace($case.name) -or $case.classname -cne $expectedClass -or
        $null -ne $case.SelectSingleNode('skipped|failure|error')) {
        throw 'A real first-administrator scenario has missing identity, failure or uncounted skip/error evidence.'
    }
}
foreach ($name in $required) {
    $cases = @($suite.testcase | Where-Object { $_.name -ceq $name })
    if ($cases.Count -ne 1 -or $cases[0].classname -cne $expectedClass -or
        $null -ne $cases[0].SelectSingleNode('skipped|failure|error')) {
        throw "Required real first-administrator scenario did not pass exactly once: $name"
    }
}
Write-Output "Real first-administrator PostgreSQL gate passed: $($suite.tests) scenarios, no failures/errors/skips."
