$ErrorActionPreference = 'Stop'
$guardDirectory = Join-Path ([IO.Path]::GetTempPath()) ('educore-runtime-report-guard-' + [guid]::NewGuid().ToString('N'))
$reportPath = Join-Path $guardDirectory 'fixture.xml'
$gate = Join-Path $PSScriptRoot 'assert-production-runtime-report.ps1'
$class = 'com.educore.api.config.ProductionRuntimePostgresIT'
$required = @('realProductionRuntimeLogsInAndKeepsFamilyReadsObjectScoped',
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
    'realProductionMfaHistoryMigrationPreservesRecordsAndConstraints')
function New-ReportFixture {
    [xml]$fixture = "<testsuite name='$class' tests='$($required.Count)' skipped='0' failures='0' errors='0'/>"
    foreach ($name in $required) {
        $test = $fixture.CreateElement('testcase')
        $test.SetAttribute('name', $name)
        $test.SetAttribute('classname', $class)
        $null = $fixture.testsuite.AppendChild($test)
    }
    return $fixture
}
function Assert-RejectedFixture([xml]$Fixture) {
    $Fixture.Save($reportPath)
    $rejected = $false
    try { & $gate -ReportPath $reportPath | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Invalid synthetic report fixture was accepted by the gate.' }
}
try {
    New-Item -ItemType Directory -Path $guardDirectory | Out-Null
    (New-ReportFixture).Save($reportPath)
    & $gate -ReportPath $reportPath | Out-Null
    foreach ($attribute in @('skipped','failures','errors')) {
        $fixture = New-ReportFixture
        $fixture.testsuite.SetAttribute($attribute, '1')
        Assert-RejectedFixture $fixture
    }
    $fixture = New-ReportFixture
    $null = $fixture.testsuite.RemoveChild($fixture.testsuite.FirstChild)
    Assert-RejectedFixture $fixture
    $fixture = New-ReportFixture
    $startupCase = $fixture.testsuite.SelectSingleNode("testcase[@name='realProductionRuntimeHasNoFallbackUserAndRejectsBasicOrFormLogin']")
    $null = $fixture.testsuite.RemoveChild($startupCase)
    Assert-RejectedFixture $fixture
    foreach ($name in ($required | Select-Object -Last 5)) {
        $fixture = New-ReportFixture
        $null = $fixture.testsuite.RemoveChild($fixture.testsuite.SelectSingleNode("testcase[@name='$name']"))
        Assert-RejectedFixture $fixture
    }
    $fixture = New-ReportFixture
    $null = $fixture.testsuite.FirstChild.AppendChild($fixture.CreateElement('skipped'))
    Assert-RejectedFixture $fixture
    $fixture = New-ReportFixture
    $null = $fixture.testsuite.AppendChild($fixture.testsuite.FirstChild.CloneNode($true))
    Assert-RejectedFixture $fixture
    $fixture = New-ReportFixture
    $fixture.testsuite.FirstChild.SetAttribute('classname', 'UnrelatedSyntheticTest')
    Assert-RejectedFixture $fixture
    Remove-Item -LiteralPath $reportPath
    $rejected = $false
    try { & $gate -ReportPath $reportPath | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Missing real report was accepted.' }
    Write-Output 'Report-gate unit fixtures pass: missing, failed, skipped, omitted, duplicate and wrong-class evidence is rejected. These XML fixtures are not PostgreSQL execution evidence.'
} finally {
    if (Test-Path -LiteralPath $reportPath) { Remove-Item -LiteralPath $reportPath }
    if (Test-Path -LiteralPath $guardDirectory) { Remove-Item -LiteralPath $guardDirectory }
}
