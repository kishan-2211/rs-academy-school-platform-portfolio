param([string]$ReportPath = 'apps/api/target/failsafe-reports/TEST-com.educore.api.finance.GatewayWorkPostgresIT.xml')
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $ReportPath)) { throw 'Real durable gateway PostgreSQL report missing.' }
[xml]$gatewayReport = Get-Content -LiteralPath $ReportPath -Raw
$gatewaySuite = $gatewayReport.testsuite
$gatewayClass = 'com.educore.api.finance.GatewayWorkPostgresIT'
$gatewayRequired = @(
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
foreach ($gatewayAttribute in @('tests','failures','errors','skipped')) {
    if ($null -eq $gatewaySuite -or $gatewaySuite.GetAttribute($gatewayAttribute) -cnotmatch '^(0|[1-9][0-9]*)$') {
        throw 'Durable gateway report counts invalid.'
    }
}
if ($gatewaySuite.name -cne $gatewayClass -or [int]$gatewaySuite.tests -lt $gatewayRequired.Count -or
    @($gatewaySuite.testcase).Count -ne [int]$gatewaySuite.tests -or [int]$gatewaySuite.failures -ne 0 -or
    [int]$gatewaySuite.errors -ne 0 -or [int]$gatewaySuite.skipped -ne 0) {
    throw 'Durable gateway report failed, incomplete or skipped.'
}
foreach ($gatewayCase in $gatewaySuite.testcase) {
    if ([string]::IsNullOrWhiteSpace($gatewayCase.name) -or $gatewayCase.classname -cne $gatewayClass -or
        $null -ne $gatewayCase.SelectSingleNode('skipped|failure|error')) {
        throw 'Durable gateway scenario has invalid identity or an uncounted failure/skip.'
    }
}
foreach ($gatewayName in $gatewayRequired) {
    if (@($gatewaySuite.testcase | Where-Object { $_.name -ceq $gatewayName }).Count -ne 1) {
        throw "Required durable gateway scenario missing or duplicated: $gatewayName"
    }
}
Write-Output "Durable gateway PostgreSQL gate passed: $($gatewaySuite.tests) cases. This is not merchant or end-to-end family checkout acceptance."
