$ErrorActionPreference = 'Stop'
$gatewayFixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('educore-work-report-' + [guid]::NewGuid().ToString('N'))
$gatewayFixtureFile = Join-Path $gatewayFixtureRoot 'synthetic.xml'
$gatewayGuard = Join-Path $PSScriptRoot 'assert-gateway-work-report.ps1'
$gatewayNames = @(
    'migrationAddsOnlyScopedDurableMetadataAndRetainsDenyByDefaultRuntime',
    'enqueueJoinsReservationTransactionAndRetryReturnsOneOriginalWorkRow',
    'createDispatchIsOneUseAndUnknownLeaseRecoveryCanNeverResetItsEvidence',
    'concurrentClaimsLeaseOneRowAndStaleTokensCannotCompleteAnotherLease',
    'minimalWebhookRetriesDeduplicateButChangedEvidenceAndInvalidRoutingDoNotDisappear',
    'webhookIntakeLeasesRetainImmutableAuthenticatedFieldsAcrossExpiredRecovery',
    'providerReadFactsPreserveSuccessDuringIncompleteDiscoveryAndOutOfOrderFailure',
    'humanCrossTenantAndCrossSchoolProviderScopesCannotOperateGatewayWork',
    'directDatabaseWritesCannotManufactureDispatchSkipVersionsOrEraseOriginalMarkers',
    'mismatchedAndOrphanProviderFactsRemainEvidenceWithoutInventingReceiptEligibility',
    'aLiveLeaseCannotDispatchAnExpiredReservationByBackdatingItsMarker',
    'readRecoveryRequiresTrustedWorkerOnlyAndActualRuntimePrivileges',
    'readRecoveryDeniesHumanCrossTenantAndSiblingSchoolScopes',
    'readRecoveryRejectsStaleTokenCounterAndExpiredLeaseAfterActualLockWait',
    'readRecoveryRejectsInactiveOrChangedFrozenProviderWithoutFallback',
    'readRecoveryPreservesImmutableReturnUriAndDeniesChangedOrigins',
    'readRecoveryPreservesOneUseMarkerWithoutFinancialPosting',
    'newCreateUsesIndependentApprovedCashAndAccrualBooksWithoutPostingFinancialEntries',
    'newCreateDeniedReadinessRollsBackMarkerAndKeepsTheOriginalPendingReservation',
    'newCreateRequiresLiveFamilyButProviderReadRecoverySurvivesRevocation',
    'newCreateCannotRepeatItsCommittedMarkerOrReuseAnotherSchoolBook',
    'newCreateRejectsActualLeaseExpiryWhileWaitingOnAccountingLock',
    'newCreateRejectsAnApprovedClosedFeeDayWithoutReopeningItOrBlockingReadRecovery',
    'newCreateRechecksOrganisationSuspensionDuringActualAccountingLockWait',
    'rawHmacIntakeCommitsOnlyMinimalScopedEvidenceAndAuthenticDelayedRetriesDeduplicate',
    'rawHmacIntakeRejectsForgedMutatedUtf8AndDuplicateEventsWithoutInboxOrFinancialWrites',
    'rawHmacIntakeConcurrentRetriesCommitOneOriginalInboxWithoutResettingItsWork',
    'rawHmacIntakeAcceptsExactInactiveHistoricalProviderButFailsClosedOnCredentialRotation',
    'rawHmacIntakeRejectsVisibleSiblingCheckoutAndRouteReuseWhileKeepingTenantEvidenceIsolated',
    'rawHmacIntakeRetainsFailedDroppedSuccessAndRefundFactsWithoutInventingPaymentOrRefundAuthority',
    'rawHmacIntakeCannotJoinHumanOrForeignSystemTransactionsOrAcknowledgeRolledBackRouting',
    'observationFinalizerPostsOneDispatchedExactSystemReceiptWithoutInventingHumanAuthority',
    'observationFinalizerRetainsPartialFactsAndBindsReceiptOnlyToLaterCompleteDiscovery',
    'observationFinalizerWithoutOneUseDispatchRetainsFactsForReviewButCannotIssueCaptureOrReceipt',
    'observationFinalizerRejectsEveryForgedClaimFieldAndActuallyExpiredLeaseWithoutPosting',
    'observationFinalizerRechecksRealLeaseAfterFinanceLockWaitAndKeepsIndependentFacts',
    'observationFinalizerConcurrentSameEvidenceRetriesCannotDuplicateCaptureReceiptOrConsumeTwice',
    'observationFinalizerApprovedClosedDayPreservesExactHoldAndNeverReopensOrAutomaticallyPosts',
    'observationFinalizerLateConfirmationSurvivesParentRevocationAndActualCheckoutExpiry',
    'observationFinalizerClosedFeeAccountKeepsExactMoneyForReviewWithoutReceiptOrReservationLoss',
    'observationFinalizerRejectsAmbientScopeForeignBindingAndCredentialRotationBeforeFinancialWrites',
    'observationFinalizerPriorCapturedSuccessCannotDisappearBehindTerminalZeroOrIncompleteCurrentDiscovery',
    'observationFinalizerFailedLastCompletionRollsBackFinanceButKeepsIndependentEvidence',
    'exactGatewayTimestampProjectionDemonstratesRealDriverHalfUpInsteadOfAssumingNanosecondStorage',
    'exactGatewayMidnightReadFactRetainsFullSourceAndFlooredOriginalSchoolDayWithoutInventedDispatch',
    'exactGatewayFinalizerRetainsFullPaidAndObservedInstantsWithExplicitFloorAndExactRetryIdentity',
    'exactGatewayDifferentFullPaidInstantsWithOneMicrosecondProjectionCannotMergeOrPost',
    'exactGatewayForwardUpgradePreservesCommittedLegacyProofAndAppendsOnlyANewNamespacedRetry',
    'exactGatewaySqlHelpersRetainNumericNanosecondsValidateCanonicalSourcesAndRunWithoutPrivilegeBypass',
    'exactGatewayInsertGuardsRejectMissingMalformedRoundedFutureAndMixedOriginalSources',
    'providerDiscoveryUsesCommittedSameIdGetOutsideTenantTransactionsAndPostsExactReceipt',
    'providerDiscoveryFailedOrderRetainsKnownMoneyAndLaterCompleteGetPostsOnce',
    'providerDiscoveryActualLeaseExpiryDuringGetRetainsFactsWithoutFinancialPosting',
    'hostedCheckoutForwardUpgradePreservesExactV071HistoryWithoutBackfillAndIsIdempotent',
    'hostedCheckoutEncryptedCreateAndConcurrentGetRetriesPreserveFirstExactSourceWithoutFinancialWrites',
    'hostedCheckoutChangedValidUriAppendsImmutableStickyConflictWithoutChoosingOrPayingEitherLink',
    'hostedCheckoutInvalidMissingDispatchForgedClaimsAndCipherFailuresFailClosedWithoutPrivateErrors',
    'hostedCheckoutSqlRlsIsInitiatingGuardianOnlyAndRechecksLiveOwnChildAuthorization',
    'hostedCheckoutActualFinanceLockWaitRejectsExpiredLeaseButStillRetainsIndependentGetMoney',
    'hostedCheckoutDatabaseGuardsRejectForgedExactSourcesAndProtectHistoryEvenForOwner',
    'gatewayCreateCommandDecryptsOriginalScopedPayerAndCommitsOneUseImmutableTermsWithoutProviderOrFinancialWrites',
    'gatewayCreateCommandRealAesWrongKeyAndWrongAadRollBackTheEntireMarkerAndPreserveOriginalWorkAndAudit',
    'gatewayWorkerConcurrentCreateCommitsBeforeProviderAndFreshGetPostsOnce',
    'gatewayWorkerUnknownCreateAndMissingReadRecoverSameIdentityWithoutAnotherPost',
    'gatewayWorkerUnavailablePrivateLinkStorageStillPostsVerifiedMoneyThroughExistingFinalizer',
    'gatewayWorkerLateConfirmationUsesClosedDayReviewedHoldWithOriginalTime',
    'inboxWorkerLateGetRecoversTerminalOriginalWorkWithoutResettingItsOneUseCreateOrCompletedHistory',
    'inboxWorkerDistinctAndDuplicateConcurrentCallbacksReuseOneExactCaptureAndNumberedReceipt',
    'inboxWorkerAuthenticFailedAndDroppedCallbackMetadataCannotEraseFreshGetVerifiedSuccess',
    'inboxWorkerOnlyUnambiguousPreviouslyRetainedOrderHintCanChooseOriginalSameIdFullGet',
    'inboxWorkerUnknownAndAmbiguousOrderAssociationKeepsUnboundMoneyAndRetriesWithoutGuessingAChild',
    'inboxWorkerExactInactiveProviderSchoolAndOrganisationRetainReadFactsForReviewWithoutActivation',
    'inboxRealLeaseExpiryDuringIndependentGetRetainsOriginalMoneyWithoutPostingOrGuessedCompletion',
    'inboxActualFinanceFenceWaitPastExpiryKeepsCommittedFactsAndRollsBackAllFinancialCompletion',
    'inboxFullClaimScopeSiblingTenantAndCredentialRotationDenyReceiptWithoutRebindingOriginalEvidence',
    'inboxDelayedVerifiedSuccessAfterClosedOriginalDayPreservesActualTimeAndReviewedHoldWithoutReopening',
    'inboxRevokedParentNeverErasesRealMoneyAndClosedAccountRequiresPreservedReviewedHold',
    'inboxLastCompletionFailureRollsBackReceiptCaptureLedgerAndAuditButKeepsCommittedGetFactsForRetry',
    'inboxRefundCallbackRetainsImmutableRoutingForGovernedReviewWithoutPaymentGetOrRefundApproval'
)
function New-GatewayWorkReportFixture {
    [xml]$fixture = '<testsuite name="com.educore.api.finance.GatewayWorkPostgresIT" failures="0" errors="0" skipped="0" />'
    $fixture.testsuite.SetAttribute('tests',[string]$gatewayNames.Count)
    foreach ($name in $gatewayNames) {
        $case = $fixture.CreateElement('testcase')
        $case.SetAttribute('name',$name); $case.SetAttribute('classname','com.educore.api.finance.GatewayWorkPostgresIT')
        $fixture.testsuite.AppendChild($case) | Out-Null
    }
    return ,$fixture
}
function Assert-GatewayWorkReportRejected([xml]$fixture) {
    $fixture.Save($gatewayFixtureFile)
    $rejected = $false
    try { & $gatewayGuard -ReportPath $gatewayFixtureFile *> $null } catch { $rejected = $true }
    if (-not $rejected) { throw 'Malformed synthetic durable gateway report passed.' }
}
try {
    New-Item -ItemType Directory -Path $gatewayFixtureRoot | Out-Null
    $valid = New-GatewayWorkReportFixture; $valid.Save($gatewayFixtureFile)
    & $gatewayGuard -ReportPath $gatewayFixtureFile *> $null
    # Earlier 66-case evidence cannot prove the new callback lane. This is
    # deliberately synthetic malformed XML, not fabricated integration proof.
    $fixture = New-GatewayWorkReportFixture
    foreach ($case in @($fixture.testsuite.testcase | Where-Object { $_.name -clike 'inbox*' })) {
        $fixture.testsuite.RemoveChild($case) | Out-Null
    }
    $fixture.testsuite.SetAttribute('tests',[string](@($fixture.testsuite.testcase).Count))
    Assert-GatewayWorkReportRejected $fixture
    foreach ($attribute in @('tests','failures','errors','skipped')) {
        $fixture = New-GatewayWorkReportFixture; $fixture.testsuite.RemoveAttribute($attribute); Assert-GatewayWorkReportRejected $fixture
        foreach ($invalid in @('-1','11.0','invalid')) {
            $fixture = New-GatewayWorkReportFixture; $fixture.testsuite.SetAttribute($attribute,$invalid); Assert-GatewayWorkReportRejected $fixture
        }
    }
    foreach ($attribute in @('failures','errors','skipped')) {
        $fixture = New-GatewayWorkReportFixture; $fixture.testsuite.SetAttribute($attribute,'1'); Assert-GatewayWorkReportRejected $fixture
    }
    foreach ($name in $gatewayNames) {
        $fixture = New-GatewayWorkReportFixture
        $fixture.testsuite.SelectSingleNode("testcase[@name='$name']").SetAttribute('name','missingRequiredCase'); Assert-GatewayWorkReportRejected $fixture
        foreach ($outcome in @('failure','error','skipped')) {
            $fixture = New-GatewayWorkReportFixture
            $case = $fixture.testsuite.SelectSingleNode("testcase[@name='$name']")
            $case.AppendChild($fixture.CreateElement($outcome)) | Out-Null; Assert-GatewayWorkReportRejected $fixture
        }
        $fixture = New-GatewayWorkReportFixture; $case = $fixture.testsuite.SelectSingleNode("testcase[@name='$name']")
        $fixture.testsuite.AppendChild($case.CloneNode($true)) | Out-Null
        $fixture.testsuite.SetAttribute('tests',[string]($gatewayNames.Count+1)); Assert-GatewayWorkReportRejected $fixture
    }
    $fixture = New-GatewayWorkReportFixture; $fixture.testsuite.SetAttribute('name','WrongClass'); Assert-GatewayWorkReportRejected $fixture
    $fixture = New-GatewayWorkReportFixture; $fixture.testsuite.SetAttribute('tests',[string]($gatewayNames.Count+1)); Assert-GatewayWorkReportRejected $fixture
    $fixture = New-GatewayWorkReportFixture; $fixture.testsuite.testcase[0].SetAttribute('classname','WrongClass'); Assert-GatewayWorkReportRejected $fixture
    Write-Output 'Durable gateway report guard fixtures passed; synthetic XML is not PostgreSQL verification.'
} finally {
    $resolved = [IO.Path]::GetFullPath($gatewayFixtureRoot)
    $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)
    if (-not $resolved.StartsWith($temp+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or
        (Split-Path $resolved -Leaf) -cnotmatch '^educore-work-report-[a-f0-9]{32}$') {
        throw 'Unsafe generated fixture path; refusing cleanup.'
    }
    if (Test-Path -LiteralPath $gatewayFixtureFile) { Remove-Item -LiteralPath $gatewayFixtureFile }
    if (Test-Path -LiteralPath $gatewayFixtureRoot) { Remove-Item -LiteralPath $gatewayFixtureRoot }
}
