$ErrorActionPreference = 'Stop'
$settlementFixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('educore-settlement-report-' + [guid]::NewGuid().ToString('N'))
$settlementFixtureFile = Join-Path $settlementFixtureRoot 'synthetic.xml'
$settlementSourceFixture = Join-Path $settlementFixtureRoot 'synthetic-source.java'
$settlementGuard = Join-Path $PSScriptRoot 'assert-gateway-settlement-report.ps1'
$settlementActualSource = Join-Path $PSScriptRoot '../apps/api/src/test/java/com/educore/api/finance/GatewaySettlementPostgresIT.java'
$settlementClass = 'com.educore.api.finance.GatewaySettlementPostgresIT'
$settlementChecks = 0
$settlementNames = @(
    'migrationPreservesExistingProviderAndRequiresDenyByDefaultNonOwnerScope',
    'exactSummaryPreparationAndReadRetryPreservePrecisionAndDoNotMoveMoney',
    'unknownAndEmptyCompleteAreDistinctAndNeverInventContinuationOrStatement',
    'eventReadsRequireActualScopedSummaryAndRetainRepeatedIdsAcrossKindsAndUnexpectedCurrencies',
    'continuationIsEncryptedExactRetryableAndLongCursorCyclesRemainExplicit',
    'preparedHistoricalWitnessSurvivesProviderDisableAndContradictoryLaterReads',
    'evidenceIsIndependentOfLaterProcessingRollbackAndFailedRetentionNeverOverwrites',
    'missingHumanForeignTenantAndSameOrganisationForeignSchoolCannotUseRetainedSources',
    'databaseRejectsNonMinimalFutureForeignAndManufacturedUnknownFacts',
    'queryAndReadHistoryCannotBeUpdatedDeletedOrReboundEvenByMigrationOwner',
    'privateCursorCannotBeReusedUnderAnotherReadIdentity',
    'connectedHumanRequestCommitsOneAuditAndWorkAndExactReplayEvenAfterProviderDisable',
    'connectedHumanRejectsChangedReplayOverlapFutureTimezoneAndDisabledRouteWithoutLeavingWork',
    'connectedRequestAndAuditRollbackTogetherAndBackgroundCannotInventHumanRequest',
    'connectedWorkerCompletesRealSelectedSummaryAndProjectionWithoutAnyMoneyOrCredentialLeak',
    'connectedUnknownReadIsRetryingNotEmptyCompleteAndLastAttemptIsVisible',
    'connectedCursorChainIsAtomicAndOnlyTerminalChainBecomesComplete',
    'connectedOnDemandEventsRequireSelectedActualSummaryAndDoNotAutomaticallyFanOut',
    'connectedProjectionAndRequestsDenyForeignSchoolRevokedMembershipAndWrongActor',
    'connectedWorkerRechecksRevokedRequesterBeforeProviderIoAndClosesForReview',
    'connectedFinalizationRollbackPreservesObservedFactsButNotSelectionOrContinuation',
    'connectedDatabaseRejectsForgedClaimOrOrphanCompletionAndProtectsHistory',
    'connectedExpiredLeaseCannotSelectAndRecoveryNeverLosesIndependentRead',
    'connectedConcurrentExactHumanRetriesCommitOneRequestOutboxAndAudit',
    'connectedCursorCycleRetainsEvidenceAndStopsAsReviewRatherThanInventingCompleteScan',
    'connectedProviderDisableBetweenObservedReadAndFinishRequiresReviewAndKeepsPrivateFacts'
)
function New-SettlementReportFixture {
    [xml]$fixture = '<testsuite name="com.educore.api.finance.GatewaySettlementPostgresIT" failures="0" errors="0" skipped="0" />'
    $fixture.testsuite.SetAttribute('tests',[string]$settlementNames.Count)
    foreach ($name in $settlementNames) {
        $case = $fixture.CreateElement('testcase')
        $case.SetAttribute('name',$name); $case.SetAttribute('classname',$settlementClass)
        $fixture.testsuite.AppendChild($case) | Out-Null
    }
    return ,$fixture
}
function Assert-SettlementReportRejected([xml]$fixture) {
    $fixture.Save($settlementFixtureFile)
    $rejected = $false
    try { & $settlementGuard -ReportPath $settlementFixtureFile *> $null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Malformed synthetic settlement report passed.' }
    $script:settlementChecks++
}
function Assert-SettlementPathsRejected([string]$report,[string]$source=$settlementActualSource) {
    $rejected = $false
    try { & $settlementGuard -ReportPath $report -SourcePath $source *> $null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Missing, stale or malformed synthetic settlement evidence passed.' }
    $script:settlementChecks++
}
try {
    New-Item -ItemType Directory -Path $settlementFixtureRoot | Out-Null
    $valid = New-SettlementReportFixture; $valid.Save($settlementFixtureFile)
    & $settlementGuard -ReportPath $settlementFixtureFile *> $null
    $settlementChecks++
    Assert-SettlementPathsRejected (Join-Path $settlementFixtureRoot 'missing.xml')
    Assert-SettlementPathsRejected $settlementFixtureFile (Join-Path $settlementFixtureRoot 'missing.java')
    (Get-Item -LiteralPath $settlementFixtureFile).LastWriteTimeUtc = (Get-Item -LiteralPath $settlementActualSource).LastWriteTimeUtc.AddMinutes(-1)
    Assert-SettlementPathsRejected $settlementFixtureFile
    $valid.Save($settlementFixtureFile)
    # Changing the source inventory must require a deliberate guard update.
    $changedSource = (Get-Content -LiteralPath $settlementActualSource -Raw).Replace($settlementNames[0],'syntheticRenamedScenario')
    [IO.File]::WriteAllText($settlementSourceFixture,$changedSource)
    $valid.Save($settlementFixtureFile)
    Assert-SettlementPathsRejected $settlementFixtureFile $settlementSourceFixture
    foreach ($attribute in @('tests','failures','errors','skipped')) {
        $fixture = New-SettlementReportFixture; $fixture.testsuite.RemoveAttribute($attribute); Assert-SettlementReportRejected $fixture
        foreach ($invalid in @('-1','11.0','invalid','01','')) {
            $fixture = New-SettlementReportFixture; $fixture.testsuite.SetAttribute($attribute,$invalid); Assert-SettlementReportRejected $fixture
        }
    }
    foreach ($attribute in @('failures','errors','skipped')) {
        $fixture = New-SettlementReportFixture; $fixture.testsuite.SetAttribute($attribute,'1'); Assert-SettlementReportRejected $fixture
    }
    foreach ($name in $settlementNames) {
        $fixture = New-SettlementReportFixture
        $case = $fixture.testsuite.SelectSingleNode("testcase[@name='$name']")
        $fixture.testsuite.RemoveChild($case) | Out-Null
        $fixture.testsuite.SetAttribute('tests',[string]($settlementNames.Count-1)); Assert-SettlementReportRejected $fixture
        $fixture = New-SettlementReportFixture; $case = $fixture.testsuite.SelectSingleNode("testcase[@name='$name']")
        $case.SetAttribute('name','syntheticMissingRequiredCase'); Assert-SettlementReportRejected $fixture
        foreach ($outcome in @('failure','error','skipped')) {
            $fixture = New-SettlementReportFixture; $case = $fixture.testsuite.SelectSingleNode("testcase[@name='$name']")
            $case.AppendChild($fixture.CreateElement($outcome)) | Out-Null; Assert-SettlementReportRejected $fixture
        }
        $fixture = New-SettlementReportFixture; $case = $fixture.testsuite.SelectSingleNode("testcase[@name='$name']")
        $fixture.testsuite.AppendChild($case.CloneNode($true)) | Out-Null
        $fixture.testsuite.SetAttribute('tests',[string]($settlementNames.Count+1)); Assert-SettlementReportRejected $fixture
    }
    # Same count, duplicated name: a missing different case must not be hidden.
    $fixture = New-SettlementReportFixture; $fixture.testsuite.testcase[0].SetAttribute('name',$settlementNames[1]); Assert-SettlementReportRejected $fixture
    $fixture = New-SettlementReportFixture; $fixture.testsuite.SetAttribute('name','WrongClass'); Assert-SettlementReportRejected $fixture
    $fixture = New-SettlementReportFixture; $fixture.testsuite.SetAttribute('tests',[string]($settlementNames.Count+1)); Assert-SettlementReportRejected $fixture
    $fixture = New-SettlementReportFixture; $fixture.testsuite.testcase[0].SetAttribute('classname','WrongClass'); Assert-SettlementReportRejected $fixture
    $fixture = New-SettlementReportFixture; $case=$fixture.CreateElement('testcase'); $case.SetAttribute('name','syntheticExtraCase'); $case.SetAttribute('classname',$settlementClass)
    $fixture.testsuite.AppendChild($case) | Out-Null; $fixture.testsuite.SetAttribute('tests',[string]($settlementNames.Count+1)); Assert-SettlementReportRejected $fixture
    $fixture = New-SettlementReportFixture; $wrapper=$fixture.CreateElement('properties'); $wrapper.AppendChild($fixture.CreateElement('failure')) | Out-Null
    $fixture.testsuite.AppendChild($wrapper) | Out-Null; Assert-SettlementReportRejected $fixture
    [IO.File]::WriteAllText($settlementFixtureFile,'<testsuites><testsuite name="com.educore.api.finance.GatewaySettlementPostgresIT" tests="0" failures="0" errors="0" skipped="0" /></testsuites>')
    Assert-SettlementPathsRejected $settlementFixtureFile
    [IO.File]::WriteAllText($settlementFixtureFile,'<!DOCTYPE testsuite [<!ENTITY synthetic "not allowed">]><testsuite>&synthetic;</testsuite>')
    Assert-SettlementPathsRejected $settlementFixtureFile
    [IO.File]::WriteAllText($settlementFixtureFile,'<testsuite')
    Assert-SettlementPathsRejected $settlementFixtureFile
    Write-Output "Settlement report guard fixtures passed: $settlementChecks checks; synthetic XML is not PostgreSQL verification."
} finally {
    $resolved = [IO.Path]::GetFullPath($settlementFixtureRoot)
    $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)
    if (-not $resolved.StartsWith($temp+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or
        (Split-Path $resolved -Leaf) -cnotmatch '^educore-settlement-report-[a-f0-9]{32}$') {
        throw 'Unsafe generated settlement fixture path; refusing cleanup.'
    }
    if (Test-Path -LiteralPath $settlementFixtureFile) { Remove-Item -LiteralPath $settlementFixtureFile }
    if (Test-Path -LiteralPath $settlementSourceFixture) { Remove-Item -LiteralPath $settlementSourceFixture }
    if (Test-Path -LiteralPath $settlementFixtureRoot) { Remove-Item -LiteralPath $settlementFixtureRoot }
}
