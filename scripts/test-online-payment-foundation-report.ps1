$ErrorActionPreference = 'Stop'
$gatewayFixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('educore-online-report-' + [guid]::NewGuid().ToString('N'))
$gatewayFixtureFile = Join-Path $gatewayFixtureRoot 'synthetic.xml'
$gatewayGuard = Join-Path $PSScriptRoot 'assert-online-payment-foundation-report.ps1'
$gatewayNames = @(
    'applicationMapperReadsBothHistoricalNullSchemasWithoutChangingPostgresFinancialHistory',
    'settlementPreparationPopulatedV083UpgradePreservesHistoryAndAddsNoConsumption',
    'settlementPreparationPostsExactCommonJournalOnceWithoutChangingStudentMoney',
    'settlementPreparationCashAndAccrualRefundNetsPreserveGrossSides',
    'settlementPreparationCrossFinancialYearRefundUsesClosedOriginalConfigurationAndCurrentCompatibleBook',
    'settlementPreparationEqualPartialRefundsRequireDistinctPhysicalChoices',
    'settlementPreparationRequiresDifferentActualHumanEvenAcrossMemberships',
    'settlementReversalRequiresDifferentActualHumanEvenAcrossMemberships',
    'settlementReversalRejectsIneligibleDatesAndBooksButKeepsIndependentRejectionAvailable',
    'settlementReversalRechecksTheFinancialCloseCommittedWhileWaitingForAccountingFence',
    'settlementPreparationRepairCannotPredateItsApprovedPostedCounterVoucher',
    'settlementPreparationRejectsForgedScopeReviewPaiseAndUnbalancedTerms',
    'settlementPreparationRefusesPendingAndActuallyReversedSources',
    'settlementPreparationProtectsConsumedBankAndSourceReversals',
    'settlementPreparationRepairsRejectedAndReversedJournalsWithoutReleasingPostedMoney',
    'settlementPreparationRejectsClosedBooksRevokedAuthorityAndStaleIsolation',
    'settlementPreparationRollsBackAuditAndDatabaseCouplingFailures',
    'settlementPreparationConcurrentRetriesAndCompetingRequestsCannotDuplicateSources',
    'settlementRefundCandidatesReadExactPostedHistoryWithoutAssociationOrFinancialWrites',
    'settlementSourceReviewExpectedProofConflictSurvivesActualSpringTransactionProxy',
    'settlementSourceReviewIdentifiesActualHistoricalReceiptWithoutMovingMoneyOrReopeningAccount',
    'settlementSourceReviewFindsDuplicateEconomicSourcesAcrossAllPagesBeforeResponsePagination',
    'settlementSourceReviewPreservesUnsupportedRefundAndFractionalPaiseEvidenceWithoutGuessingSources',
    'settlementSourceReviewRejectsForeignGuardianRevokedAndIncompleteSourcesWithoutFinancialWrites',
    'settlementSourceReviewRejectsActuallyReversedOriginalJournalButRetainsReceiptAndDues',
    'settlementRefundCandidatesKeepUnpostedAndActuallyReversedAccountingVisibleForReview',
    'settlementRefundCandidatesPaginateAmbiguousEqualAmountsAndKeepPhysicalReportRowsDistinct',
    'settlementRefundCandidatesDenyForeignFamilyRevokedAndSubstitutedEvidenceWithoutFinancialWrites',
    'completedRefundProofReadsExactPostedCashAndAccrualWithoutFinancialWrites',
    'completedRefundProofFollowsPostedRepairAndDistinguishesPendingFromActualReversal',
    'completedRefundProofRetainsClosedRenamedAndInactiveHistory',
    'completedRefundProofDeniesUnpostedForeignFamilyRevokedAndMissingContext',
    'settlementBankPopulatedV082UpgradePreservesAllExistingRowsAndAddsNoAttestation',
    'settlementBankRecordsExactEvidenceOnceWithoutMovingMoneyAndRetainsReviewedSummary',
    'settlementBankHistoricalReplaySurvivesInactiveBankAndChangedSchoolZoneButNewCaptureDoesNot',
    'settlementBankIndependentRejectionRetainsHistoryReleasesOnlyItsReservationAndCannotReactivate',
    'settlementBankAndManualUpiBankShareReferenceHistoryIncludingReversedReceipts',
    'settlementBankRejectsForeignGuardianRevokedIncompleteAndInvalidEvidence',
    'settlementBankDatabaseProtectsScopedExactPaiseAndImmutableEvidence',
    'settlementBankOppositeOrderLockWaitsPreventDoubleAdmissionWithManualCollection',
    'refundProcessingReadTracksRealWorkerEvidenceAndRestoredDuesWithoutChangingFinancialHistory',
    'refundProcessingReadKeepsScopeConfigurationAndIndependentReviewDenialsWithoutProviderIo',
    'refundWorkerPostsOnceThenIndependentlyConfirmsAndConnectsReviewedDuesAndAccounting',
    'refundWorkerPriorProviderWitnessUsesGetOnlyEvenWithNewRefundsDisabled',
    'refundWorkerProcessLossAfterCommittedAdmissionRecoversOnlyTheSameProviderIdentity',
    'refundWorkerLostClaimDuringGetKeepsCommittedWitnessButCannotRecognizeHold',
    'refundWorkerFailedFinalCompletionRollsBackHoldButNotPreviouslyCommittedGet',
    'refundWorkerConcurrentReplicasCannotDispatchTheSameApprovedRefundTwice',
    'refundDispatchPopulatedV079UpgradePreservesHistoryWithoutBackfillOrProviderActivation',
    'refundDispatchApprovalQueuesOnceAndAdmitsExactClosedAccountSourceWithoutChangingMoney',
    'refundDispatchRollbackDoesNotConsumeMarkerOrAnchorOriginalSource',
    'refundDispatchConcurrentSameClaimConsumesOneMarkerAndPreventsLaterSourceReversal',
    'refundDispatchRejectsHumanForeignStolenAndChangedClaimsWithoutConsumingAnything',
    'refundDispatchEarlierIndependentReadPreventsPostButKeepsSameIdRecoveryAvailable',
    'refundDispatchAccountingWaitRechecksCommittedPendingReversalBeforeMarker',
    'refundDispatchLeaseExpiryDuringActualAccountingWaitCannotReturnPostPermission',
    'refundDispatchRecoveryCannotResetTheMarkerOrReturnAnotherCreateClaim',
    'refundDispatchDatabaseGuardsRejectForgedSourceAndImmutableMarkerChanges',
    'refundDispatchCommittedMarkerSurvivesActualExpiredLeaseRecoveryAfterProcessLoss',
    'refundDispatchProviderRowWaitMustRecheckRealLeaseTimeAfterEverySourceLock',
    'gatewayOriginalSourceWaitsForAccountingFenceThenRefusesTheCommittedPendingReversal',
    'gatewayOriginalSourceRefusesAnActualStaleRepeatableReadSnapshotBeforeReadingMoney',
    'gatewayOriginalJournalSystemReadsExactClosedHistoryWithoutFinancialMutation',
    'gatewayOriginalJournalDeniesHumanForeignMissingAndActualManualPaymentSources',
    'gatewayOriginalJournalRefusesPendingAndActualReversalWithoutChangingTheFeeReceipt',
    'gatewayOriginalJournalWaitsForAccountingFenceAndRechecksCommittedReversal',
    'gatewayOriginalJournalRejectsARealStaleRepeatableReadSnapshotAfterReversalCommit',
    'refundInboxRecoversApprovedSuccessThroughIndependentGetWithoutChangingEconomicRecords',
    'refundInboxRetainsContradictionForReviewWithoutReplacingAnEarlierVerifiedAnchor',
    'refundInboxCannotAssociateForeignOrUnapprovedIntentOrReadUnrelatedProviderMoney',
    'refundInboxFailedCompletionRollsBackNewHoldButPreservesTheAlreadyCommittedRead',
    'refundJournalRepairPopulatedV078UpgradePreservesOriginalFinancialHistory',
    'refundJournalRepairClosedOriginalPeriodUsesOpenDateAndExactRetrySurvivesTargetClose',
    'refundJournalRepairRejectsChangedTermsStaleSelectionForeignFamilyAndRevokedAuthority',
    'refundJournalRepairAuditFailureRollsBackReleaseReplacementAndJournal',
    'refundJournalRepairConcurrentExactAndDifferentCommandsCannotForkOnePredecessor',
    'refundPostingConcurrentRetriesCannotDuplicateCompletionLedgerOrVoucher',
    'refundPostingAccountingRejectionAndRepairPreserveDuesAndPreventOriginalSourceReversal',
    'refundPostingPopulatedV077UpgradePreservesMoneyAndRefusesHistoricalWriters',
    'refundPostingPartialCashAndAccrualRestoreExactDuesOnceWithIndependentCommonJournal',
    'refundPostingPendingConflictingClosedForeignAndParentAuthorityCannotCreateMoney',
    'refundPostingAuditFailureRollsBackDuesCompletionAndSubmittedJournalTogether',
    'refundPostingRestoredHistoricalDuesCanBeCollectedManuallyOrReservedOnlineWithoutAcademicReactivation',
    'refundPostingRefundOnlyDayRegisterExportAndSignedIndependentCloseAgree',
    'historicalFamilyPopulatedV076UpgradePreservesLegacyIntentAndMoney',
    'historicalFamilyCurrentDefaultAndClosedHistoricalStatementsRetainActualBalanceWithoutRefundHold',
    'historicalFamilyAccountPaginationAndLiveOwnChildScopeAreBoundedAndPrivate',
    'historicalFamilyExplicitOldAccountIntentRetainsModeAndOriginalRetryAfterRollover',
    'historicalFamilyChangedModeAccountOrClosedSourceCannotDuplicateCheckout',
    'historicalFamilySqlSelectionModeAndActualLiveActorCannotBeForged',
    'historicalFamilyExplicitDispatchUsesCurrentApprovedBookWithoutReactivatingOldAcademics',
    'refundReopenPopulatedV075UpgradeRetainsClosedAccountHoldAndAllFinancialHistory',
    'refundReopenIndependentApprovalPreservesOriginalClosureAndExactRetryNeverReappliesAfterReclosure',
    'refundReopenRequiresApprovedRetainedNonconflictingHoldAndClosedOriginalAccount',
    'refundReopenStaleReviewCannotApproveButIndependentRejectAndMakerCancellationPreserveHistory',
    'refundReopenScopesLiveHumanAuthorityQueueAndPrivateHistoryWithoutChangingMoney',
    'refundReopenAuditFailureRollsBackRequestDecisionAndAccountTransitionTogether',
    'refundReopenDirectSqlCannotForgeReviewMutateEvidenceOrReplayAConsumedTransition',
    'refundReopenConcurrentRequestsCannotDuplicatePendingReviewUnderReadCommittedRepeatableOrSerializable',
    'refundReopenStaleApprovalCannotMissContradictorySuccessAtRepeatableOrSerializableSnapshots',
    'originalPaymentProofRequiresTheWholeIndependentlyPostedCashBatchWithoutChangingMoney',
    'originalPaymentProofPreservesAccrualSourceMappingAfterIndependentSupersession',
    'originalPaymentProofRejectsActualCommonJournalReversalWithoutReversingTheFeeReceipt',
    'feeJournalCashWholePaymentBatchPreservesTwoReceiptsAcrossRealIndependentPosting',
    'feeJournalAccrualPreservesLearnerDimensionsAndCurrentAccountClassification',
    'feeJournalScopedEvidenceIncludesRealReversalAndOmitsManualForeignAndReversalIds',
    'collectionRegisterRetainsHistoricalCollectionsAndDatesReversalsSeparately',
    'collectionRegisterScopesFiltersPaginationCurrencyAndRetriesWithoutWritingMoney',
    'collectionRegisterIncludesOnlyPostedGatewayReceiptsWithExactReceivedEvidence',
    'collectionExportReturnsEveryFilteredMovementInOneReadAndAuditsWithoutPersonalSearchText',
    'collectionExportDeniesForeignRevokedAndGuardianAuthorityAndAuditFailureCannotServeAReport',
    'refundMigrationForwardFromPopulatedV072PreservesReceiptsBalancesAndFinancialHistory',
    'refundPartialIntentAndIndependentApprovalPreserveMoneyAndExactRetriesWithoutClaimingExecution',
    'refundCumulativeBudgetsReserveApprovedIntentAndReleaseOnlyRejectedOrMakerCancelledIntent',
    'refundConcurrentRequestsCannotOversubscribeCaptureOrOriginalAllocationUnderReadCommittedOrRepeatableRead',
    'refundReviewRequiresDifferentHumanAndRechecksConfiguredRefundPolicy',
    'refundScopeRejectsGuardianForeignWrongActorRevokedGrantAndInvalidInputs',
    'refundAuditFailureAndTransactionRollbackLeaveNoPartialRequestsOrDecisions',
    'refundOriginalPostedReceiptRemainsEligibleAfterAccountClosureAndLaterExtraCapture',
    'refundActualFinanceLockWaitRechecksExpiredMembershipAndRevokedAuthority',
    'refundDirectWritesCannotForgeSourceOmitAllocationsExceedDeferredBudgetsOrMutateReviewedEvidence',
    'refundPerAllocationBudgetCannotBorrowAnotherPaidChargeEvenWhenCaptureHasRemainingPrincipal',
    'refundReadPopulatedV073UpgradePreservesApprovalAndRequiresExactWritableBackgroundScope',
    'refundReadSuccessPreservesOriginalNanosecondsFrozenApprovalFloorAndSchoolDayWithoutPosting',
    'refundReadExactIdentityReplaysOriginalTimeWhileFreshReadsRemainIndependentAndConflictingFactsFail',
    'refundReadPendingFailedHeldCancelledAndAbsentFactsRemainTruthfulWithoutBudgetOrFinancialMutation',
    'refundReadFuturePreApprovalWrongReferenceAndAmountMismatchRemainReviewWithoutSuccess',
    'refundReadDirectRuntimeScopeForeignApprovalAndAppendOnlyGuardsPreservePersistedWitness',
    'refundReadDirectMalformedExactFloorSchoolDateHashAndOutcomeProofCannotForgeSuccess',
    'refundReadWholeTransactionRollbackAndMalformedTypedFactsLeaveNoWitnessOrFinancialPartialState',
    'refundReadSchoolDateUsesJavaIsoOffsetSemanticsAndNamedZones',
    'refundSuccessPopulatedV074UpgradePreservesReadAndFinancialHistory',
    'refundSuccessHoldRetainsExactApprovedSourceWithoutChangingDuesOrAccounting',
    'refundSuccessClosedOriginalDayPreservesItsApprovedCloseAndNeverReopensHistory',
    'refundSuccessExactFreshReplayKeepsFirstReadRecognitionTimeAndClose',
    'refundSuccessUnknownPendingFailedReviewAndUnauthorizedSourcesCannotClaimHold',
    'refundSuccessConflictingSuccessfulReadsRemainDurableAndBlockRecognitionAndReplay',
    'refundSuccessDirectSqlWrongSourceAndAppendOnlyGuardsCannotForgeOrReplaceHold',
    'refundSuccessRollbackLeavesNoAnchorOrFinancialPartialState',
    'refundSuccessConcurrentRecognitionsAndStaleSnapshotsCannotDuplicateOrMissConflictingReads',
    'migrationPreservesRuntimeIsolationAndDoesNotActivateOrPostAnything','exactIntentRetryDoesNotDuplicateReserveOrPretendTheFeeIsPaid',
    'manualAccountLookupUsesOnlyEffectiveScopedPlacementAndPreservesTheLedger',
    'manualAccountLookupDoesNotBorrowAnEndedFutureOrDifferentSessionRoll',
    'familyFeeStatementsRejectStaleUserSchoolAndOrganisationAuthority',
    'newWholeSecondExpiryRetainsCreationPrecisionAndRetryIdentityWithoutWeakeningProviderOrFamilyChecks',
    'ownChildLiveRelationshipAndUnreservedAmountsAreRequired','partialRulesAndImmutableIdentitySurviveDirectDatabaseWrites',
    'manualCollectionsAndConcessionApprovalCannotSpendActiveOnlineReservations','concurrentDifferentRequestsSerializeAndCannotReserveTheSameMoneyTwice',
    'closedDaySuccessRetainsEvidenceAndRequiresReviewWithoutChangingTheClosedDay','extraVerifiedCapturesAreRetainedForReviewButExactRetriesCannotDuplicateThem',
    'gatewayClearingUsesTheCommonApprovedBookWithoutWeakeningManualBankMappings',
    'verifiedCapturePostsOneCommonNumberedReceiptWithHonestSystemProvenance',
    'delayedClosedDayCaptureIsHeldThenReviewedOnAnOpenDateWithoutChangingHistory',
    'concurrentCaptureRetriesIssueOnlyOneReceiptAndCrossSchoolPostingIsDenied',
    'interruptedPostingRollsBackReceiptSequenceLedgerAndReservationDisposition',
    'nullableSystemActorsCannotForgeHumanDispositionOrCommitAnUnpairedFeeCredit',
    'staffCaptureQueueIsScopedSearchablePagedAndContainsOnlyMinimalLearnerEvidence',
    'captureQueueAndReviewedPostingRejectUnauthorizedAndCrossScopeActors',
    'queueEligibilityRefusesMismatchedExtraCaptureAndInactiveAccountEvidence',
    'queueAndPostingRecheckLiveActorOrganisationSchoolMembershipAndRoleOwnership',
    'reviewedPostingApiUsesSafeOpenDateAndRetriesOneHumanReceiptWithoutChangingClosedHistory',
    'captureQueueRejectsUnboundedOrInvalidFilterInputs',
    'manualDateOnlyCashPreservesHonestPrecisionAndTheCommonFamilyReceipt',
    'manualVerifiedCashUpiAndBankShareExactAllocationsOneLedgerAndSequentialReceipts',
    'manualDigitalReferenceOrScreenshotWithoutActualBankCreditCannotPost',
    'manualBankCreditIdentitySurvivesCrossMethodRetriesConcurrencyAndReversal',
    'manualCollectionsRespectOnlineReservationsPartialRulesAndRejectExcess',
    'manualClosedOriginalDayRequiresReviewedOpenPostingAndPreservesActualTimeAndClose',
    'manualCollectionsDenyWrongHumanCrossTenantAndRevokedLiveAuthority',
    'manualCollectionRollbackAndDeferredProvenanceProtectReceiptsEvidenceAllocationsAndSequence',
    'familyGatewayOwnChildHistoricalHistoryAndStatusRemainMinimalWithoutRequiringCurrentFeeAccount',
    'familyGatewayCrossChildSiblingSchoolAndOtherOrganisationAreDeniedWithoutExposingPaymentIdentifiers',
    'familyGatewayCachedCapabilityCannotReplaceLiveSessionMembershipRelationshipOrActorAuthority',
    'familyGatewayNewInitiationNeedsEnabledWorkerCreationAndExactConfiguredServerRouteWithoutAnyWrites',
    'familyGatewayInitiationCommitsExactParentReservationAndQueueAtomicallyAndNeverMarksBrowserIntentPaid',
    'familyGatewayConcurrentSameRequestReturnsOneWorkAndDifferentRequestsCannotReserveObligationsTwice',
    'familyGatewayFailedEnqueueRollsBackPreparedIntentAllocationsAndAuditRatherThanAcknowledgingPartialSuccess',
    'familyGatewayPrivateContinuationRequiresInitiatingGuardianWhileCoGuardianMayReadMinimalHistory',
    'familyGatewayKnownSuccessAndConflictingPrivateLinksBlockContinuationWithoutRewritingEvidenceOrPosting',
    'familyGatewayPrivateCipherKeyAssociatedDataAndUrlHashFailuresDenyDisclosureWithoutSensitiveCauses',
    'familyGatewayActualFinanceLockWaitRechecksRealExpiryAndRevokedGrantBeforeReturningAnyPrivateUrl'
)
function New-GatewayReportFixture {
    [xml]$gatewayFixture = '<testsuite name="com.educore.api.finance.OnlinePaymentFoundationPostgresIT" failures="0" errors="0" skipped="0" />'
    $gatewayFixture.testsuite.SetAttribute('tests',[string]$gatewayNames.Count)
    foreach ($gatewayName in $gatewayNames) {
        $gatewayCase = $gatewayFixture.CreateElement('testcase')
        $gatewayCase.SetAttribute('name',$gatewayName); $gatewayCase.SetAttribute('classname','com.educore.api.finance.OnlinePaymentFoundationPostgresIT')
        $gatewayFixture.testsuite.AppendChild($gatewayCase) | Out-Null
    }
    return ,$gatewayFixture
}
function Assert-GatewayReportRejected([xml]$gatewayFixture) {
    $gatewayFixture.Save($gatewayFixtureFile)
    $gatewayRejected = $false
    try { & $gatewayGuard -ReportPath $gatewayFixtureFile *> $null } catch { $gatewayRejected = $true }
    if (-not $gatewayRejected) { throw 'Malformed synthetic online-payment report passed.' }
}
try {
    New-Item -ItemType Directory -Path $gatewayFixtureRoot | Out-Null
    $gatewayValid = New-GatewayReportFixture; $gatewayValid.Save($gatewayFixtureFile)
    & $gatewayGuard -ReportPath $gatewayFixtureFile *> $null
    # Earlier all-green evidence cannot prove actual settlement preparation or
    # actual-person reversal review. These fixtures test the verifier only.
    $gatewaySettlementNames = @($gatewayNames | Where-Object { $_ -clike 'settlementPreparation*' -or $_ -clike 'settlementReversal*' })
    $gatewayFixture = New-GatewayReportFixture
    foreach ($gatewayCase in @($gatewayFixture.testsuite.testcase | Where-Object { $_.name -cin $gatewaySettlementNames })) {
        [void]$gatewayFixture.testsuite.RemoveChild($gatewayCase)
    }
    $gatewayFixture.testsuite.SetAttribute('tests',[string](@($gatewayFixture.testsuite.testcase).Count))
    Assert-GatewayReportRejected $gatewayFixture
    # Padding an old report with unrelated green cases cannot satisfy the named
    # monetary/independence gates even when its total matches the new checkpoint.
    $gatewayFixture = New-GatewayReportFixture
    foreach ($gatewayName in $gatewaySettlementNames) {
        $gatewayFixture.testsuite.SelectSingleNode("testcase[@name='$gatewayName']").SetAttribute('name','unrelatedGreenCase_'+$gatewayName)
    }
    Assert-GatewayReportRejected $gatewayFixture
    # Every new case below also traverses the existing individual missing,
    # uncounted failure/error/skipped and duplicate-case negative fixtures.
    # Source-review evidence is also required by exact name. An equal-count old
    # report padded with unrelated green cases cannot stand in for these checks.
    $gatewayFixture = New-GatewayReportFixture
    foreach ($gatewayName in @($gatewayNames | Where-Object { $_ -clike 'settlementSourceReview*' })) {
        $gatewayFixture.testsuite.SelectSingleNode("testcase[@name='$gatewayName']").SetAttribute('name','unrelatedGreenCase_'+$gatewayName)
    }
    Assert-GatewayReportRejected $gatewayFixture
    # A prior 141-case worker report cannot prove the scoped processing projections.
    $gatewayFixture = New-GatewayReportFixture
    foreach ($gatewayCase in @($gatewayFixture.testsuite.testcase | Where-Object { $_.name -clike 'refundProcessingRead*' })) {
        [void]$gatewayFixture.testsuite.RemoveChild($gatewayCase)
    }
    $gatewayFixture.testsuite.SetAttribute('tests',[string](@($gatewayFixture.testsuite.testcase).Count))
    Assert-GatewayReportRejected $gatewayFixture
    # A prior 135-case admission report cannot prove connected POST/GET orchestration.
    $gatewayFixture = New-GatewayReportFixture
    foreach ($gatewayCase in @($gatewayFixture.testsuite.testcase | Where-Object { $_.name -clike 'refundWorker*' })) {
        [void]$gatewayFixture.testsuite.RemoveChild($gatewayCase)
    }
    $gatewayFixture.testsuite.SetAttribute('tests',[string](@($gatewayFixture.testsuite.testcase).Count))
    Assert-GatewayReportRejected $gatewayFixture
    # A prior 107-case report cannot prove journal-only correction or closed-date replay.
    $gatewayFixture = New-GatewayReportFixture
    foreach ($gatewayCase in @($gatewayFixture.testsuite.testcase | Where-Object { $_.name -clike 'refundJournalRepair*' })) {
        $gatewayFixture.testsuite.RemoveChild($gatewayCase) | Out-Null
    }
    $gatewayFixture.testsuite.SetAttribute('tests',[string](@($gatewayFixture.testsuite.testcase).Count))
    Assert-GatewayReportRejected $gatewayFixture
    # A prior 99-case history checkpoint cannot prove actual refund money posting.
    $gatewayFixture = New-GatewayReportFixture
    foreach ($gatewayCase in @($gatewayFixture.testsuite.testcase | Where-Object { $_.name -clike 'refundPosting*' })) {
        $gatewayFixture.testsuite.RemoveChild($gatewayCase) | Out-Null
    }
    $gatewayFixture.testsuite.SetAttribute('tests',[string](@($gatewayFixture.testsuite.testcase).Count))
    Assert-GatewayReportRejected $gatewayFixture
    # The prior 92-case checkpoint cannot prove historical account selection.
    $gatewayFixture = New-GatewayReportFixture
    foreach ($gatewayCase in @($gatewayFixture.testsuite.testcase | Where-Object { $_.name -clike 'historicalFamily*' })) {
        $gatewayFixture.testsuite.RemoveChild($gatewayCase) | Out-Null
    }
    $gatewayFixture.testsuite.SetAttribute('tests',[string](@($gatewayFixture.testsuite.testcase).Count))
    Assert-GatewayReportRejected $gatewayFixture
    # The prior 83-case checkpoint cannot prove reviewed original-account reopening.
    $gatewayFixture = New-GatewayReportFixture
    foreach ($gatewayCase in @($gatewayFixture.testsuite.testcase | Where-Object { $_.name -clike 'historicalFamily*' -or $_.name -clike 'refundReopen*' })) {
        $gatewayFixture.testsuite.RemoveChild($gatewayCase) | Out-Null
    }
    $gatewayFixture.testsuite.SetAttribute('tests',[string](@($gatewayFixture.testsuite.testcase).Count))
    Assert-GatewayReportRejected $gatewayFixture
    # The prior 80-case checkpoint cannot prove complete original receipt source validation.
    $gatewayFixture = New-GatewayReportFixture
    foreach ($gatewayCase in @($gatewayFixture.testsuite.testcase | Where-Object { $_.name -clike 'historicalFamily*' -or $_.name -clike 'refundReopen*' -or $_.name -clike 'originalPaymentProof*' })) {
        $gatewayFixture.testsuite.RemoveChild($gatewayCase) | Out-Null
    }
    $gatewayFixture.testsuite.SetAttribute('tests',[string](@($gatewayFixture.testsuite.testcase).Count))
    Assert-GatewayReportRejected $gatewayFixture
    # The prior 77-case checkpoint cannot prove whole original FEES journal evidence.
    $gatewayFixture = New-GatewayReportFixture
    foreach ($gatewayCase in @($gatewayFixture.testsuite.testcase | Where-Object { $_.name -clike 'historicalFamily*' -or $_.name -clike 'refundReopen*' -or $_.name -clike 'originalPaymentProof*' -or $_.name -clike 'feeJournal*' })) {
        $gatewayFixture.testsuite.RemoveChild($gatewayCase) | Out-Null
    }
    $gatewayFixture.testsuite.SetAttribute('tests',[string](@($gatewayFixture.testsuite.testcase).Count))
    Assert-GatewayReportRejected $gatewayFixture
    # The prior 68-case checkpoint cannot prove immutable recognized-success HOLDs.
    $gatewayFixture = New-GatewayReportFixture
    foreach ($gatewayCase in @($gatewayFixture.testsuite.testcase | Where-Object { $_.name -clike 'historicalFamily*' -or $_.name -clike 'refundReopen*' -or $_.name -clike 'originalPaymentProof*' -or $_.name -clike 'feeJournal*' -or $_.name -clike 'refundSuccess*' })) {
        $gatewayFixture.testsuite.RemoveChild($gatewayCase) | Out-Null
    }
    $gatewayFixture.testsuite.SetAttribute('tests',[string](@($gatewayFixture.testsuite.testcase).Count))
    Assert-GatewayReportRejected $gatewayFixture
    # The prior 59-case checkpoint cannot prove durable independent refund GET reads.
    $gatewayFixture = New-GatewayReportFixture
    foreach ($gatewayCase in @($gatewayFixture.testsuite.testcase | Where-Object { $_.name -clike 'historicalFamily*' -or $_.name -clike 'refundReopen*' -or $_.name -clike 'originalPaymentProof*' -or $_.name -clike 'feeJournal*' -or $_.name -clike 'refundRead*' -or $_.name -clike 'refundSuccess*' })) {
        $gatewayFixture.testsuite.RemoveChild($gatewayCase) | Out-Null
    }
    $gatewayFixture.testsuite.SetAttribute('tests',[string](@($gatewayFixture.testsuite.testcase).Count))
    Assert-GatewayReportRejected $gatewayFixture
    # The prior 134-case report cannot prove post-provider-lock lease revalidation.
    $gatewayFixture = New-GatewayReportFixture
    foreach ($gatewayCase in @($gatewayFixture.testsuite.testcase | Where-Object { $_.name -ceq 'refundDispatchProviderRowWaitMustRecheckRealLeaseTimeAfterEverySourceLock' })) {
        [void]$gatewayFixture.testsuite.RemoveChild($gatewayCase)
    }
    $gatewayFixture.testsuite.SetAttribute('tests',[string](@($gatewayFixture.testsuite.testcase).Count))
    Assert-GatewayReportRejected $gatewayFixture
    # The prior 133-case report cannot prove a committed marker after actual lease expiry.
    $gatewayFixture = New-GatewayReportFixture
    foreach ($gatewayCase in @($gatewayFixture.testsuite.testcase | Where-Object { $_.name -ceq 'refundDispatchCommittedMarkerSurvivesActualExpiredLeaseRecoveryAfterProcessLoss' })) {
        [void]$gatewayFixture.testsuite.RemoveChild($gatewayCase)
    }
    $gatewayFixture.testsuite.SetAttribute('tests',[string](@($gatewayFixture.testsuite.testcase).Count))
    Assert-GatewayReportRejected $gatewayFixture
    # The prior 123-case checkpoint cannot prove durable refund POST admission.
    $gatewayFixture = New-GatewayReportFixture
    foreach ($gatewayCase in @($gatewayFixture.testsuite.testcase | Where-Object { $_.name -clike 'refundDispatch*' })) {
        [void]$gatewayFixture.testsuite.RemoveChild($gatewayCase)
    }
    $gatewayFixture.testsuite.SetAttribute('tests',[string](@($gatewayFixture.testsuite.testcase).Count))
    Assert-GatewayReportRejected $gatewayFixture
    # The prior 121-case checkpoint cannot prove complete background source verification.
    $gatewayFixture = New-GatewayReportFixture
    foreach ($gatewayCase in @($gatewayFixture.testsuite.testcase | Where-Object { $_.name -clike 'gatewayOriginalSource*' })) {
        [void]$gatewayFixture.testsuite.RemoveChild($gatewayCase)
    }
    $gatewayFixture.testsuite.SetAttribute('tests',[string](@($gatewayFixture.testsuite.testcase).Count))
    Assert-GatewayReportRejected $gatewayFixture
    # The prior 116-case checkpoint cannot prove scoped background original-journal evidence.
    $gatewayFixture = New-GatewayReportFixture
    foreach ($gatewayCase in @($gatewayFixture.testsuite.testcase | Where-Object { $_.name -clike 'gatewayOriginalJournal*' -or $_.name -clike 'gatewayOriginalSource*' })) {
        [void]$gatewayFixture.testsuite.RemoveChild($gatewayCase)
    }
    $gatewayFixture.testsuite.SetAttribute('tests',[string](@($gatewayFixture.testsuite.testcase).Count))
    Assert-GatewayReportRejected $gatewayFixture
    # The prior 112-case checkpoint cannot prove connected refund inbox recovery.
    $gatewayFixture = New-GatewayReportFixture
    foreach ($gatewayCase in @($gatewayFixture.testsuite.testcase | Where-Object { $_.name -clike 'refundInbox*' -or $_.name -clike 'gatewayOriginalJournal*' -or $_.name -clike 'gatewayOriginalSource*' })) {
        $gatewayFixture.testsuite.RemoveChild($gatewayCase) | Out-Null
    }
    $gatewayFixture.testsuite.SetAttribute('tests',[string](@($gatewayFixture.testsuite.testcase).Count))
    Assert-GatewayReportRejected $gatewayFixture
    # The prior 48-case checkpoint cannot prove refund request/approval integrity.
    $gatewayFixture = New-GatewayReportFixture
    foreach ($gatewayCase in @($gatewayFixture.testsuite.testcase | Where-Object { $_.name -clike 'historicalFamily*' -or $_.name -clike 'originalPaymentProof*' -or $_.name -clike 'feeJournal*' -or $_.name -clike 'refund*' })) {
        $gatewayFixture.testsuite.RemoveChild($gatewayCase) | Out-Null
    }
    $gatewayFixture.testsuite.SetAttribute('tests',[string](@($gatewayFixture.testsuite.testcase).Count))
    Assert-GatewayReportRejected $gatewayFixture
    # An older all-green report cannot prove the historical completed-refund
    # accounting proof. This synthetic XML is not database evidence.
    $gatewayFixture = New-GatewayReportFixture
    foreach ($gatewayCase in @($gatewayFixture.testsuite.testcase | Where-Object { $_.name -clike 'completedRefundProof*' })) {
        $gatewayFixture.testsuite.RemoveChild($gatewayCase) | Out-Null
    }
    $gatewayFixture.testsuite.SetAttribute('tests',[string](@($gatewayFixture.testsuite.testcase).Count))
    Assert-GatewayReportRejected $gatewayFixture
    # An older all-green payment report cannot substitute for bank evidence and
    # the shared manual-reference fence. This XML is not database evidence.
    $gatewayFixture = New-GatewayReportFixture
    foreach ($gatewayCase in @($gatewayFixture.testsuite.testcase | Where-Object { $_.name -clike 'settlementBank*' })) {
        $gatewayFixture.testsuite.RemoveChild($gatewayCase) | Out-Null
    }
    $gatewayFixture.testsuite.SetAttribute('tests',[string](@($gatewayFixture.testsuite.testcase).Count))
    Assert-GatewayReportRejected $gatewayFixture
    # The earlier checkpoint cannot prove the full-range export.
    $gatewayFixture = New-GatewayReportFixture
    foreach ($gatewayCase in @($gatewayFixture.testsuite.testcase | Where-Object { $_.name -clike 'collectionExport*' })) {
        $gatewayFixture.testsuite.RemoveChild($gatewayCase) | Out-Null
    }
    $gatewayFixture.testsuite.SetAttribute('tests',[string](@($gatewayFixture.testsuite.testcase).Count))
    Assert-GatewayReportRejected $gatewayFixture
    # The prior reader checkpoint is not proof of the collection register.
    $gatewayFixture = New-GatewayReportFixture
    foreach ($gatewayCase in @($gatewayFixture.testsuite.testcase | Where-Object { $_.name -clike 'collectionRegister*' })) {
        $gatewayFixture.testsuite.RemoveChild($gatewayCase) | Out-Null
    }
    $gatewayFixture.testsuite.SetAttribute('tests',[string](@($gatewayFixture.testsuite.testcase).Count))
    Assert-GatewayReportRejected $gatewayFixture
    # The earlier foundation checkpoint cannot prove the new family
    # gateway boundary. This malformed fixture is not PostgreSQL evidence.
    $gatewayFixture = New-GatewayReportFixture
    foreach ($gatewayCase in @($gatewayFixture.testsuite.testcase | Where-Object { $_.name -clike 'familyGateway*' })) {
        $gatewayFixture.testsuite.RemoveChild($gatewayCase) | Out-Null
    }
    $gatewayFixture.testsuite.SetAttribute('tests',[string](@($gatewayFixture.testsuite.testcase).Count))
    Assert-GatewayReportRejected $gatewayFixture
    foreach ($gatewayAttribute in @('tests','failures','errors','skipped')) {
        $gatewayFixture = New-GatewayReportFixture; $gatewayFixture.testsuite.RemoveAttribute($gatewayAttribute); Assert-GatewayReportRejected $gatewayFixture
        foreach ($gatewayInvalid in @('-1','8.0','invalid')) {
            $gatewayFixture = New-GatewayReportFixture; $gatewayFixture.testsuite.SetAttribute($gatewayAttribute,$gatewayInvalid); Assert-GatewayReportRejected $gatewayFixture
        }
    }
    foreach ($gatewayAttribute in @('failures','errors','skipped')) {
        $gatewayFixture = New-GatewayReportFixture; $gatewayFixture.testsuite.SetAttribute($gatewayAttribute,'1'); Assert-GatewayReportRejected $gatewayFixture
    }
    foreach ($gatewayName in $gatewayNames) {
        $gatewayFixture = New-GatewayReportFixture
        $gatewayFixture.testsuite.SelectSingleNode("testcase[@name='$gatewayName']").SetAttribute('name','missingRequiredCase'); Assert-GatewayReportRejected $gatewayFixture
        foreach ($gatewayOutcome in @('failure','error','skipped')) {
            $gatewayFixture = New-GatewayReportFixture
            $gatewayCase = $gatewayFixture.testsuite.SelectSingleNode("testcase[@name='$gatewayName']")
            $gatewayCase.AppendChild($gatewayFixture.CreateElement($gatewayOutcome)) | Out-Null; Assert-GatewayReportRejected $gatewayFixture
        }
        $gatewayFixture = New-GatewayReportFixture
        $gatewayCase = $gatewayFixture.testsuite.SelectSingleNode("testcase[@name='$gatewayName']")
        $gatewayFixture.testsuite.AppendChild($gatewayCase.CloneNode($true)) | Out-Null
        $gatewayFixture.testsuite.SetAttribute('tests',[string]($gatewayNames.Count+1)); Assert-GatewayReportRejected $gatewayFixture
    }
    $gatewayFixture = New-GatewayReportFixture; $gatewayFixture.testsuite.SetAttribute('name','WrongClass'); Assert-GatewayReportRejected $gatewayFixture
    $gatewayFixture = New-GatewayReportFixture; $gatewayFixture.testsuite.SetAttribute('tests',[string]($gatewayNames.Count+1)); Assert-GatewayReportRejected $gatewayFixture
    $gatewayFixture = New-GatewayReportFixture; $gatewayFixture.testsuite.testcase[0].SetAttribute('classname','WrongClass'); Assert-GatewayReportRejected $gatewayFixture
    Write-Output 'Online-payment report guard fixtures passed; synthetic XML is not PostgreSQL verification.'
} finally {
    $gatewayResolved = [IO.Path]::GetFullPath($gatewayFixtureRoot)
    $gatewayTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar)
    if (-not $gatewayResolved.StartsWith($gatewayTemp+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or
        (Split-Path $gatewayResolved -Leaf) -cnotmatch '^educore-online-report-[a-f0-9]{32}$') { throw 'Unsafe generated fixture path; refusing cleanup.' }
    if (Test-Path -LiteralPath $gatewayFixtureFile) { Remove-Item -LiteralPath $gatewayFixtureFile }
    if (Test-Path -LiteralPath $gatewayFixtureRoot) { Remove-Item -LiteralPath $gatewayFixtureRoot }
}
