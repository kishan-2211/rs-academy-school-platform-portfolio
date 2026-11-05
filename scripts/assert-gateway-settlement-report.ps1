param(
    [string]$ReportPath = 'apps/api/target/failsafe-reports/TEST-com.educore.api.finance.GatewaySettlementPostgresIT.xml',
    [string]$SourcePath = (Join-Path $PSScriptRoot '../apps/api/src/test/java/com/educore/api/finance/GatewaySettlementPostgresIT.java')
)
$ErrorActionPreference = 'Stop'
$settlementClass = 'com.educore.api.finance.GatewaySettlementPostgresIT'
$settlementRequired = @(
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
if (-not (Test-Path -LiteralPath $SourcePath -PathType Leaf) -or -not (Test-Path -LiteralPath $ReportPath -PathType Leaf)) {
    throw 'Current settlement PostgreSQL source or actual report missing.'
}
$settlementSource = Get-Content -LiteralPath $SourcePath -Raw
$settlementNames = @([regex]::Matches($settlementSource,'(?m)^\s*@Test\s+void\s+(\w+)\s*\(') | ForEach-Object { $_.Groups[1].Value })
if ($settlementNames.Count -ne $settlementRequired.Count -or
    [regex]::Matches($settlementSource,'@Test\b').Count -ne $settlementRequired.Count -or
    $settlementSource -cmatch '@(?:ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b' -or
    @(Compare-Object ($settlementNames | Sort-Object) ($settlementRequired | Sort-Object)).Count) {
    throw 'Settlement PostgreSQL guard inventory is stale or differs from current named source.'
}
if ((Get-Item -LiteralPath $ReportPath).LastWriteTimeUtc -lt (Get-Item -LiteralPath $SourcePath).LastWriteTimeUtc) {
    throw 'Settlement PostgreSQL report predates its current integration-test source.'
}
$settlementReaderSettings = [Xml.XmlReaderSettings]::new()
$settlementReaderSettings.DtdProcessing = [Xml.DtdProcessing]::Prohibit
$settlementReaderSettings.XmlResolver = $null
$settlementReaderSettings.MaxCharactersInDocument = 8388608
$settlementReader = [Xml.XmlReader]::Create([IO.Path]::GetFullPath($ReportPath),$settlementReaderSettings)
try {
    $settlementReport = [Xml.XmlDocument]::new()
    $settlementReport.XmlResolver = $null
    $settlementReport.Load($settlementReader)
} finally { $settlementReader.Dispose() }
$settlementSuite = $settlementReport.DocumentElement
if ($null -eq $settlementSuite -or $settlementSuite.LocalName -cne 'testsuite' -or
    $settlementSuite.NamespaceURI -cne '' -or $settlementSuite.GetAttribute('name') -cne $settlementClass) {
    throw 'Settlement PostgreSQL report requires its one exact suite identity.'
}
foreach ($settlementAttribute in @('tests','failures','errors','skipped')) {
    if ($settlementSuite.GetAttribute($settlementAttribute) -cnotmatch '^(0|[1-9][0-9]*)$') {
        throw 'Settlement PostgreSQL report counts invalid.'
    }
}
$settlementCases = @($settlementSuite.SelectNodes('testcase'))
if ($settlementSuite.GetAttribute('tests') -cne [string]$settlementRequired.Count -or
    $settlementCases.Count -ne $settlementRequired.Count -or
    $settlementSuite.SelectNodes('.//testcase').Count -ne $settlementRequired.Count -or
    $settlementSuite.SelectNodes('.//testsuite|.//failure|.//error|.//skipped').Count -ne 0 -or
    $settlementSuite.GetAttribute('failures') -cne '0' -or $settlementSuite.GetAttribute('errors') -cne '0' -or
    $settlementSuite.GetAttribute('skipped') -cne '0') {
    throw 'Settlement PostgreSQL report failed, skipped, extra or incomplete.'
}
foreach ($settlementCase in $settlementCases) {
    if ($settlementCase.NamespaceURI -cne '' -or $settlementCase.GetAttribute('classname') -cne $settlementClass -or
        $settlementCase.GetAttribute('name') -cnotin $settlementRequired) {
        throw 'Settlement PostgreSQL scenario identity is invalid or unexpected.'
    }
}
foreach ($settlementName in $settlementRequired) {
    if (@($settlementCases | Where-Object { $_.GetAttribute('name') -ceq $settlementName }).Count -ne 1) {
        throw "Required settlement PostgreSQL scenario missing or duplicated: $settlementName"
    }
}
Write-Output "Settlement PostgreSQL gate passed: $($settlementRequired.Count) exact cases. This is not merchant, bank, accountant-browser or production acceptance."
