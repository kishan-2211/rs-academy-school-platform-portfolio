$ErrorActionPreference = 'Stop'
$manualFixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('educore-manual-upgrade-report-' + [guid]::NewGuid().ToString('N'))
$manualFixtureFile = Join-Path $manualFixtureRoot 'synthetic.xml'
$manualGuard = Join-Path $PSScriptRoot 'assert-manual-collection-migration-report.ps1'
$manualNames = @(
    'realForwardMigrationPreservesDuplicateAndReversedHistoryWithoutBackfill',
    'exactOldDigitalRequestsRetryOriginalReceiptsWithoutNewAttestationOrWrites',
    'changedOldRetryAndNewCrossMethodReuseCannotOverwriteHistoricalCredits',
    'historicalRowsRemainNonOwnerTenantScopedAndImmutableAfterUpgrade',
    'newVerifiedCreditContinuesTheExistingSequenceWithoutInventingHistoricalEvidence'
)
function New-ManualUpgradeReportFixture {
    [xml]$manualFixture = '<testsuite name="com.educore.api.finance.ManualCollectionMigrationPostgresIT" failures="0" errors="0" skipped="0" />'
    $manualFixture.testsuite.SetAttribute('tests',[string]$manualNames.Count)
    foreach ($manualName in $manualNames) {
        $manualCase = $manualFixture.CreateElement('testcase')
        $manualCase.SetAttribute('name',$manualName)
        $manualCase.SetAttribute('classname','com.educore.api.finance.ManualCollectionMigrationPostgresIT')
        $manualFixture.testsuite.AppendChild($manualCase) | Out-Null
    }
    return ,$manualFixture
}
function Assert-ManualUpgradeReportRejected([xml]$manualFixture) {
    $manualFixture.Save($manualFixtureFile)
    $manualRejected = $false
    try { & $manualGuard -ReportPath $manualFixtureFile *> $null } catch { $manualRejected = $true }
    if (-not $manualRejected) { throw 'Malformed synthetic manual-upgrade report passed.' }
}
try {
    New-Item -ItemType Directory -Path $manualFixtureRoot | Out-Null
    $manualValid = New-ManualUpgradeReportFixture; $manualValid.Save($manualFixtureFile)
    & $manualGuard -ReportPath $manualFixtureFile *> $null
    foreach ($manualAttribute in @('tests','failures','errors','skipped')) {
        $manualFixture = New-ManualUpgradeReportFixture; $manualFixture.testsuite.RemoveAttribute($manualAttribute); Assert-ManualUpgradeReportRejected $manualFixture
        foreach ($manualInvalid in @('-1','5.0','invalid')) {
            $manualFixture = New-ManualUpgradeReportFixture; $manualFixture.testsuite.SetAttribute($manualAttribute,$manualInvalid); Assert-ManualUpgradeReportRejected $manualFixture
        }
    }
    foreach ($manualAttribute in @('failures','errors','skipped')) {
        $manualFixture = New-ManualUpgradeReportFixture; $manualFixture.testsuite.SetAttribute($manualAttribute,'1'); Assert-ManualUpgradeReportRejected $manualFixture
    }
    foreach ($manualName in $manualNames) {
        $manualFixture = New-ManualUpgradeReportFixture
        $manualFixture.testsuite.SelectSingleNode("testcase[@name='$manualName']").SetAttribute('name','missingRequiredCase'); Assert-ManualUpgradeReportRejected $manualFixture
        foreach ($manualOutcome in @('failure','error','skipped')) {
            $manualFixture = New-ManualUpgradeReportFixture
            $manualCase = $manualFixture.testsuite.SelectSingleNode("testcase[@name='$manualName']")
            $manualCase.AppendChild($manualFixture.CreateElement($manualOutcome)) | Out-Null; Assert-ManualUpgradeReportRejected $manualFixture
        }
        $manualFixture = New-ManualUpgradeReportFixture
        $manualCase = $manualFixture.testsuite.SelectSingleNode("testcase[@name='$manualName']")
        $manualFixture.testsuite.AppendChild($manualCase.CloneNode($true)) | Out-Null
        $manualFixture.testsuite.SetAttribute('tests',[string]($manualNames.Count+1)); Assert-ManualUpgradeReportRejected $manualFixture
    }
    $manualFixture = New-ManualUpgradeReportFixture; $manualFixture.testsuite.SetAttribute('name','WrongClass'); Assert-ManualUpgradeReportRejected $manualFixture
    $manualFixture = New-ManualUpgradeReportFixture; $manualFixture.testsuite.SetAttribute('tests',[string]($manualNames.Count+1)); Assert-ManualUpgradeReportRejected $manualFixture
    $manualFixture = New-ManualUpgradeReportFixture; $manualFixture.testsuite.testcase[0].SetAttribute('classname','WrongClass'); Assert-ManualUpgradeReportRejected $manualFixture
    Write-Output 'Manual-upgrade report guard fixtures passed; synthetic XML is not PostgreSQL verification.'
} finally {
    $manualResolved = [IO.Path]::GetFullPath($manualFixtureRoot)
    $manualTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)
    if (-not $manualResolved.StartsWith($manualTemp+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or
        (Split-Path $manualResolved -Leaf) -cnotmatch '^educore-manual-upgrade-report-[a-f0-9]{32}$') {
        throw 'Unsafe generated fixture path; refusing cleanup.'
    }
    if (Test-Path -LiteralPath $manualFixtureFile) { Remove-Item -LiteralPath $manualFixtureFile }
    if (Test-Path -LiteralPath $manualFixtureRoot) { Remove-Item -LiteralPath $manualFixtureRoot }
}
