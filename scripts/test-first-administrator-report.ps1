$ErrorActionPreference = 'Stop'
$firstAdminRoot = Join-Path ([IO.Path]::GetTempPath()) ('educore-first-admin-report-' + [guid]::NewGuid().ToString('N'))
$firstAdminReport = Join-Path $firstAdminRoot 'synthetic-report.xml'
$firstAdminGuard = Join-Path $PSScriptRoot 'assert-first-administrator-report.ps1'
$firstAdminNames = @(
    'realFirstAdministratorCreatesScopedIdentityWithHonestImmutableAudit',
    'realFirstAdministratorRefusesExistingIdentitiesAndNeverResetsRetry',
    'realFirstAdministratorRejectsWrongScopeRoleOrOperator',
    'realFirstAdministratorRequiresExactFullyMigratedRelease',
    'realFirstAdministratorRollsBackEveryWriteWhenAuditFails',
    'realFirstAdministratorSerializesCompetingToolsAndOrdinaryInserts'
)
function New-FirstAdminFixture {
    [xml]$fixture = '<testsuite name="com.educore.api.identity.FirstAdministratorPostgresIT" tests="6" failures="0" errors="0" skipped="0" />'
    foreach ($name in $firstAdminNames) {
        $case = $fixture.CreateElement('testcase'); $case.SetAttribute('name',$name)
        $case.SetAttribute('classname','com.educore.api.identity.FirstAdministratorPostgresIT')
        $fixture.testsuite.AppendChild($case) | Out-Null
    }
    return ,$fixture
}
function Assert-FirstAdminFixtureRejected([xml]$fixture) {
    $fixture.Save($firstAdminReport)
    $rejected = $false
    try { & $firstAdminGuard -ReportPath $firstAdminReport *> $null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Malformed synthetic first-administrator report unexpectedly passed.' }
}
try {
    New-Item -ItemType Directory -Path $firstAdminRoot | Out-Null
    $valid = New-FirstAdminFixture; $valid.Save($firstAdminReport)
    & $firstAdminGuard -ReportPath $firstAdminReport *> $null
    $missingRejected = $false
    try { & $firstAdminGuard -ReportPath (Join-Path $firstAdminRoot 'missing.xml') *> $null } catch { $missingRejected = $true }
    if (-not $missingRejected) { throw 'Missing synthetic report unexpectedly passed.' }
    foreach ($attribute in @('failures','errors','skipped')) {
        $fixture = New-FirstAdminFixture; $fixture.testsuite.SetAttribute($attribute,'1'); Assert-FirstAdminFixtureRejected $fixture
    }
    foreach ($attribute in @('tests','failures','errors','skipped')) {
        $fixture = New-FirstAdminFixture; $fixture.testsuite.RemoveAttribute($attribute); Assert-FirstAdminFixtureRejected $fixture
        foreach ($invalid in @('-1','6.0','not-a-count')) {
            $fixture = New-FirstAdminFixture; $fixture.testsuite.SetAttribute($attribute,$invalid); Assert-FirstAdminFixtureRejected $fixture
        }
    }
    foreach ($wrongClass in @('WrongClass','com.educore.api.identity.FirstAdministratorPostgresITWrong')) {
        $fixture = New-FirstAdminFixture; $fixture.testsuite.SetAttribute('name',$wrongClass); Assert-FirstAdminFixtureRejected $fixture
    }
    foreach ($name in $firstAdminNames) {
        $fixture = New-FirstAdminFixture
        $fixture.testsuite.SelectSingleNode("testcase[@name='$name']").SetAttribute('name','missingRequiredScenario')
        Assert-FirstAdminFixtureRejected $fixture
        $fixture = New-FirstAdminFixture
        $case = $fixture.testsuite.SelectSingleNode("testcase[@name='$name']"); $case.SetAttribute('classname','WrongClass')
        Assert-FirstAdminFixtureRejected $fixture
        foreach ($outcome in @('skipped','failure','error')) {
            $fixture = New-FirstAdminFixture
            $fixture.testsuite.SelectSingleNode("testcase[@name='$name']").AppendChild($fixture.CreateElement($outcome)) | Out-Null
            Assert-FirstAdminFixtureRejected $fixture
        }
        $fixture = New-FirstAdminFixture
        $case = $fixture.testsuite.SelectSingleNode("testcase[@name='$name']")
        $fixture.testsuite.AppendChild($case.CloneNode($true)) | Out-Null; $fixture.testsuite.SetAttribute('tests','7')
        Assert-FirstAdminFixtureRejected $fixture
    }
    $fixture = New-FirstAdminFixture; $fixture.testsuite.SetAttribute('tests','5'); Assert-FirstAdminFixtureRejected $fixture
    $fixture = New-FirstAdminFixture; $fixture.testsuite.SetAttribute('tests','7'); Assert-FirstAdminFixtureRejected $fixture
    $fixture = New-FirstAdminFixture
    $extra = $fixture.CreateElement('testcase'); $extra.SetAttribute('name','futureExtraScenario')
    $extra.SetAttribute('classname','com.educore.api.identity.FirstAdministratorPostgresIT')
    $extra.AppendChild($fixture.CreateElement('error')) | Out-Null
    $fixture.testsuite.AppendChild($extra) | Out-Null; $fixture.testsuite.SetAttribute('tests','7')
    Assert-FirstAdminFixtureRejected $fixture
    Write-Output 'First-administrator report guard fixtures passed. These synthetic XML fixtures are not PostgreSQL verification.'
} finally {
    $resolvedRoot = [IO.Path]::GetFullPath($firstAdminRoot)
    $resolvedTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)
    if (-not $resolvedRoot.StartsWith($resolvedTemp + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or
        (Split-Path $resolvedRoot -Leaf) -cnotmatch '^educore-first-admin-report-[a-f0-9]{32}$') { throw 'Unexpected generated fixture path; refusing cleanup.' }
    if (Test-Path -LiteralPath $firstAdminReport) { Remove-Item -LiteralPath $firstAdminReport }
    if (Test-Path -LiteralPath $firstAdminRoot) { Remove-Item -LiteralPath $firstAdminRoot }
}
