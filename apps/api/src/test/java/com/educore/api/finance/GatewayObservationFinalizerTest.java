package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class GatewayObservationFinalizerTest {
    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
    private static final String ROUTE = "a".repeat(64), ORDER = "synthetic_order", PAYMENT = "synthetic_payment";
    private static final String ERROR = "Gateway finalization requires trusted scoped facts, a current lease and exact immutable financial proof";
    private final UUID organisation = UUID.randomUUID(), school = UUID.randomUUID(), provider = UUID.randomUUID();
    private final UUID attempt = UUID.randomUUID(), account = UUID.randomUUID(), observation = UUID.randomUUID();
    private final GatewayProviderRegistry registry = mock(GatewayProviderRegistry.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final GatewayWorkQueue queue = mock(GatewayWorkQueue.class);
    private final GatewayAttemptDispatch dispatch = mock(GatewayAttemptDispatch.class);
    private final GatewayInboxDispatch inboxDispatch = mock(GatewayInboxDispatch.class);
    private final FinanceService finance = mock(FinanceService.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final GatewayWorkQueue.ProviderBinding binding = new GatewayWorkQueue.ProviderBinding(organisation, school, provider,
            "CASHFREE", "TEST", "synthetic_merchant", "synthetic:not-enabled");
    private final GatewayWorkQueue.Claim claim = new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.ATTEMPT,
            attempt, binding, UUID.randomUUID(), NOW.minusSeconds(1), NOW.plusSeconds(89), 1, false);
    private final GatewayProviderRegistry.Route route = new GatewayProviderRegistry.Route(ROUTE, organisation, school, provider,
            "CASHFREE", CashfreePaymentProviderAdapter.Environment.TEST, binding.merchantReference(), binding.credentialsReference(),
            "env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_ID", "env:APP_FINANCE_GATEWAY_CASHFREE_TEST_CLIENT_SECRET");
    private final GatewayObservationFinalizer service = new GatewayObservationFinalizer(registry, jdbc, queue, dispatch, finance, manager, inboxDispatch);
    private final GatewayWorkQueue.Claim inboxClaim = new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.WEBHOOK,
            UUID.randomUUID(), binding, UUID.randomUUID(), claim.startedAt(), claim.leaseUntil(), 1, false);
    private final List<String> events = new ArrayList<>();
    private final List<Write> writes = new ArrayList<>();
    private final ArrayDeque<Instant> databaseTimes = new ArrayDeque<>();
    private Instant createdAt = NOW.minusSeconds(60), expiry = NOW.plusSeconds(300), paidAt = NOW.minusSeconds(10);
    private boolean frozenIdentity = true, ownedClaim = true, createMarker = true, conflict = false, otherCapture = false, active = true;
    private UUID existingObservation, existingCapture, close;
    private boolean exactExistingCapture = true, finishResult = true;
    private int transactionNumber;
    private boolean historicalInboxKnown = true, currentInboxKnown = true, currentInboxEnabled = true, currentInboxAvailable = true;
    private String callbackOrder = ORDER, callbackPayment = PAYMENT;

    @AfterEach void cleanupOnlyTestContext() {
        TransactionSynchronizationManager.clear();
        assertThat(TenantRequestContext.current()).isEmpty();
    }

    @Test void exactDbLoadedIntentStagesEvidenceBeforeHonestSystemReceiptAndFinishesLeaseLast() throws Exception {
        ready();
        assertThat(service.finalizeObservation(ROUTE, claim, exact())).isEqualTo(GatewayWorkQueue.Outcome.COMPLETE);
        assertThat(events).containsSubsequence("BEGIN_1", "FENCE", "FACT_BOUND", "CHECKOUT", "COMMIT_1",
                "BEGIN_2", "FENCE", "CLAIM_LOCK", "READ_ADMISSION", "CAPTURE", "FINANCE", "FINISH_COMPLETE", "COMMIT_2");
        assertThat(writes).hasSize(2);
        var checkout = writes.getFirst().parameters();
        assertThat(checkout.getValue("observationId")).isNotNull();
        assertThat(checkout.getValue("complete")).isEqualTo(true);
        assertThat(checkout.getValue("successful")).isEqualTo(1);
        assertThat(checkout.getValue("pending")).isEqualTo(0);
        assertThat(checkout.getValue("observedAt")).isEqualTo(GatewayExactInstant.parameter(NOW));
        assertThat(checkout.getValue("observedAtExact")).isEqualTo(NOW.toString());
        assertThat(writes.getLast().parameters().getValue("classification")).isEqualTo("MATCHED");
        assertThat(writes.getLast().parameters().getValue("closeId")).isNull();
        verify(registry, never()).provider(any());
    }

    @Test void capturePreservesNanosecondSourceAndActualSchoolDayWithoutRoundingOverMidnight() throws Exception {
        ready();
        paidAt = Instant.parse("2026-10-12T18:29:59.999999500Z");
        createdAt = paidAt.minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        expiry = createdAt.plusSeconds(300);
        assertThat(service.finalizeObservation(ROUTE, claim, exact())).isEqualTo(GatewayWorkQueue.Outcome.COMPLETE);
        var capture = writes.getLast().parameters();
        assertThat(capture.getValue("paidAtExact")).isEqualTo("2026-10-12T18:29:59.999999500Z");
        assertThat(capture.getValue("paidAt")).isEqualTo(Instant.parse("2026-10-12T18:29:59.999999Z").atOffset(java.time.ZoneOffset.UTC));
        assertThat(capture.getValue("paidAtMicrosecondExact")).isEqualTo(false);
        assertThat(capture.getValue("originalDate")).isEqualTo(java.time.LocalDate.of(2026, 10, 12));
        assertThat(writes.getLast().sql()).contains("paid_at,paid_at_exact", ":paidAt,:paidAtExact");
    }

    @Test void checkoutWritesExactNanosecondObservedTimeWithoutChangingItsFullSourceProofHash() throws Exception {
        ready();
        Instant observedAt = NOW.minusNanos(500);
        var discovery = new GatewayDiscoveryEvidence.Discovery(binding, exact().checkout(), exact().orderReads(), observedAt);
        assertThat(service.finalizeObservation(ROUTE, claim, discovery)).isEqualTo(GatewayWorkQueue.Outcome.COMPLETE);
        var checkout = writes.getFirst();
        assertThat(checkout.parameters().getValue("observedAtExact")).isEqualTo(observedAt.toString());
        assertThat(checkout.parameters().getValue("observedAt")).isEqualTo(GatewayExactInstant.parameter(observedAt));
        assertThat(checkout.sql()).contains("observed_at,observed_at_exact", ":observedAt,:observedAtExact");
        assertThat(checkout.parameters().getValue("proofHash")).isNotNull();
    }

    @Test void exactRetrySqlKeepsFullSourceIdentityAndOriginalImmutableReceiptProof() throws Exception {
        ready(); existingObservation = observation; existingCapture = UUID.randomUUID();
        paidAt = NOW.minusSeconds(10).plusNanos(123456500);
        assertThat(service.finalizeObservation(ROUTE, claim, exact())).isEqualTo(GatewayWorkQueue.Outcome.COMPLETE);
        verify(finance).postVerifiedGatewayCapture(organisation, school, existingCapture, null, null, null);
        assertThat(writes).isEmpty();
        assertThat(events).doesNotContain("CHECKOUT", "CAPTURE");
    }

    @Test void incompleteDiscoveryKeepsCapturedFactsAndIncompleteSnapshotButNeverCapturesOrCompletes() throws Exception {
        ready();
        var checkout = checkout(List.of(ORDER, "synthetic_unknown_order"));
        var discovery = new GatewayDiscoveryEvidence.Discovery(binding, checkout,
                List.of(GatewayDiscoveryEvidence.OrderRead.complete(ORDER, List.of(payment())),
                        GatewayDiscoveryEvidence.OrderRead.unknown("synthetic_unknown_order")), NOW);
        assertThat(service.finalizeObservation(ROUTE, claim, discovery)).isEqualTo(GatewayWorkQueue.Outcome.RETRY);
        assertThat(events).contains("FACT_BOUND", "FINISH_RETRY").doesNotContain("CAPTURE", "FINANCE");
        assertThat(writes).hasSize(1);
        assertThat(writes.getFirst().parameters().getValue("complete")).isEqualTo(false);
        assertThat(writes.getFirst().parameters().getValue("successful")).isEqualTo(1);
        verifyNoInteractions(finance);
    }

    @Test void missingCheckoutRetainsIndependentUnboundMoneyWithoutInventingAttemptAssociation() throws Exception {
        ready();
        var discovery = new GatewayDiscoveryEvidence.Discovery(binding, null,
                List.of(GatewayDiscoveryEvidence.OrderRead.complete(ORDER, List.of(payment()))), NOW);
        assertThat(service.finalizeObservation(ROUTE, claim, discovery)).isEqualTo(GatewayWorkQueue.Outcome.RETRY);
        verify(queue).retainPaymentObservation(eq(binding), eq(null), eq(payment()));
        assertThat(events).contains("FACT_UNBOUND").doesNotContain("CHECKOUT", "CAPTURE", "FINANCE");
        assertThat(writes).isEmpty();
    }

    @Test void mismatchedCheckoutAndForeignReadMembershipKeepMinimalFactsUnboundWithoutImmutableReviewCapture() throws Exception {
        ready();
        var mismatched = new PaymentProviderAdapter.CheckoutObservation(UUID.randomUUID().toString(), attempt.toString(),
                amount(), "INR", amount(), PaymentProviderAdapter.CheckoutStatus.PAID, expiry, List.of(ORDER));
        var discovery = new GatewayDiscoveryEvidence.Discovery(binding, mismatched,
                List.of(GatewayDiscoveryEvidence.OrderRead.complete("synthetic_other_order", List.of(payment()))), NOW);
        assertThat(service.finalizeObservation(ROUTE, claim, discovery)).isEqualTo(GatewayWorkQueue.Outcome.REVIEW_REQUIRED);
        verify(queue).retainPaymentObservation(eq(binding), eq(null), eq(payment()));
        assertThat(writes).isEmpty();
        verifyNoInteractions(finance);
    }

    @Test void validPendingAndTerminalSnapshotsAreDurableButCannotReleaseReservations() throws Exception {
        ready();
        var pending = new PaymentProviderAdapter.PaymentObservation(ORDER, PAYMENT, amount(), "INR",
                PaymentProviderAdapter.PaymentStatus.PENDING, false, null, "upi");
        var checkout = new PaymentProviderAdapter.CheckoutObservation(attempt.toString(), attempt.toString(), amount(), "INR",
                BigDecimal.ZERO, PaymentProviderAdapter.CheckoutStatus.PENDING, expiry, List.of(ORDER));
        assertThat(service.finalizeObservation(ROUTE, claim, new GatewayDiscoveryEvidence.Discovery(binding, checkout,
                List.of(GatewayDiscoveryEvidence.OrderRead.complete(ORDER, List.of(pending))), NOW))).isEqualTo(GatewayWorkQueue.Outcome.RETRY);
        assertThat(writes.getFirst().parameters().getValue("pending")).isEqualTo(1);
        assertThat(writes.getFirst().parameters().getValue("successful")).isEqualTo(0);
        assertThat(events).doesNotContain("CAPTURE", "FINANCE");
        var terminal = new PaymentProviderAdapter.CheckoutObservation(attempt.toString(), attempt.toString(), amount(), "INR",
                BigDecimal.ZERO, PaymentProviderAdapter.CheckoutStatus.EXPIRED, expiry, List.of(ORDER));
        assertThat(service.finalizeObservation(ROUTE, claim, new GatewayDiscoveryEvidence.Discovery(binding, terminal,
                List.of(GatewayDiscoveryEvidence.OrderRead.complete(ORDER, List.of())), NOW))).isEqualTo(GatewayWorkQueue.Outcome.REVIEW_REQUIRED);
        assertThat(writes).allMatch(write -> write.sql().contains("INSERT INTO fee_gateway_checkout_observations"));
        verifyNoInteractions(finance);
    }

    @Test void priorConflictingCapturedMoneyOverridesApparentlyExactLatestSinglePayment() throws Exception {
        ready(); conflict = true;
        assertThat(service.finalizeObservation(ROUTE, claim, exact())).isEqualTo(GatewayWorkQueue.Outcome.REVIEW_REQUIRED);
        assertThat(events).contains("FACT_BOUND", "CHECKOUT", "FINISH_REVIEW_REQUIRED").doesNotContain("CAPTURE", "FINANCE");
        assertThat(writes).hasSize(1);
        verifyNoInteractions(finance);
    }

    @Test void noAuthoritativeCreateMarkerCannotCreateReceiptEvenWithExactMoneyAndCallerHint() throws Exception {
        ready(); createMarker = false;
        var hint = new GatewayWorkQueue.Claim(claim.kind(), claim.id(), claim.binding(), claim.token(), claim.startedAt(),
                claim.leaseUntil(), claim.attemptNumber(), true);
        assertThat(service.finalizeObservation(ROUTE, hint, exact())).isEqualTo(GatewayWorkQueue.Outcome.REVIEW_REQUIRED);
        assertThat(events).doesNotContain("CAPTURE", "FINANCE");
        verifyNoInteractions(finance);
    }

    @Test void staleOrForgedFullClaimRollsBackOnlyFinancialStageAndKeepsIndependentFactsCommitted() throws Exception {
        ready(); ownedClaim = false;
        rejects(claim, exact());
        assertThat(events).containsSubsequence("FACT_BOUND", "CHECKOUT", "COMMIT_1", "BEGIN_2", "CLAIM_LOCK", "ROLLBACK_2");
        assertThat(events).doesNotContain("CAPTURE", "FINANCE");
        verify(queue, never()).complete(any(), any(), anyString(), any());
    }

    @Test void actualDatabaseClockAfterLockWaitCanExpireLeaseWithoutLosingEarlierFacts() throws Exception {
        ready(); databaseTimes.add(NOW); databaseTimes.add(claim.leaseUntil());
        rejects(claim, exact());
        assertThat(events).containsSubsequence("COMMIT_1", "BEGIN_2", "CLAIM_LOCK", "READ_ADMISSION", "ROLLBACK_2");
        assertThat(events).doesNotContain("CAPTURE", "FINANCE");
        verify(queue, never()).complete(any(), any(), anyString(), any());
    }

    @Test void lastLeaseCompletionFalseOrExceptionNeverAcknowledgesFinancialMutation() throws Exception {
        ready(); finishResult = false;
        rejects(claim, exact());
        assertThat(events).containsSubsequence("COMMIT_1", "CAPTURE", "FINANCE", "FINISH_COMPLETE", "ROLLBACK_2");
        assertThat(events).doesNotContain("COMMIT_2");
        doAnswer(call -> { throw new IllegalStateException("synthetic-private-payment-or-secret"); })
                .when(queue).complete(any(), any(), anyString(), any());
        rejects(claim, exact());
        assertThat(TenantRequestContext.current()).isEmpty();
    }

    @Test void delayedExactPaymentPreservesActualPaidDayAndCloseThenUsesExistingSystemHold() throws Exception {
        ready(); paidAt = NOW.minusSeconds(86400); createdAt = paidAt.minusSeconds(60); expiry = createdAt.plusSeconds(300);
        close = UUID.randomUUID();
        doAnswer(call -> { events.add("FINANCE"); return new FinanceService.GatewayPostingResult("CAPTURED_REVIEW_REQUIRED", null, null); })
                .when(finance).postVerifiedGatewayCapture(eq(organisation), eq(school), any(), eq(null), eq(null), eq(null));
        assertThat(service.finalizeObservation(ROUTE, claim, exact())).isEqualTo(GatewayWorkQueue.Outcome.REVIEW_REQUIRED);
        var capture = writes.getLast().parameters();
        assertThat(capture.getValue("originalDate")).isEqualTo(paidAt.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate());
        assertThat(capture.getValue("paidAt")).isEqualTo(GatewayExactInstant.parameter(paidAt));
        assertThat(capture.getValue("closeId")).isEqualTo(close);
        assertThat(capture.getValue("classification")).isEqualTo("REVIEW_REQUIRED");
        assertThat(events).containsSubsequence("CAPTURE", "FINANCE", "FINISH_REVIEW_REQUIRED", "COMMIT_2");
    }

    @Test void exactRetryUsesOriginalCaptureAndProofWithoutRebindingOrNewCapture() throws Exception {
        ready(); existingObservation = observation; existingCapture = UUID.randomUUID();
        assertThat(service.finalizeObservation(ROUTE, claim, exact())).isEqualTo(GatewayWorkQueue.Outcome.COMPLETE);
        verify(finance).postVerifiedGatewayCapture(organisation, school, existingCapture, null, null, null);
        assertThat(writes).isEmpty();
        assertThat(events).doesNotContain("CHECKOUT", "CAPTURE");
    }

    @Test void conflictingImmutableCaptureOrOtherCaptureNeverRebindsOrPosts() throws Exception {
        ready(); existingCapture = UUID.randomUUID(); exactExistingCapture = false;
        rejects(claim, exact());
        assertThat(events).doesNotContain("CAPTURE", "FINANCE");
        verifyNoInteractions(finance);
        exactExistingCapture = true; otherCapture = true;
        assertThat(service.finalizeObservation(ROUTE, claim, exact())).isEqualTo(GatewayWorkQueue.Outcome.REVIEW_REQUIRED);
        verifyNoInteractions(finance);
    }

    @Test void disabledUnknownCrossBindingWebhookAndLiveNotOptedInCannotStartAnyTransaction() {
        when(registry.workerEnabled()).thenReturn(false); rejects(claim, exact());
        when(registry.workerEnabled()).thenReturn(true); when(registry.findRoute(ROUTE)).thenReturn(Optional.empty()); rejects(claim, exact());
        var live = new GatewayProviderRegistry.Route(ROUTE, organisation, school, provider, "CASHFREE", CashfreePaymentProviderAdapter.Environment.LIVE,
                binding.merchantReference(), binding.credentialsReference(), "env:APP_FINANCE_GATEWAY_CASHFREE_LIVE_CLIENT_ID",
                "env:APP_FINANCE_GATEWAY_CASHFREE_LIVE_CLIENT_SECRET");
        when(registry.findRoute(ROUTE)).thenReturn(Optional.of(live)); rejects(claim, exact());
        when(registry.findRoute(ROUTE)).thenReturn(Optional.of(route));
        var foreign = new GatewayWorkQueue.ProviderBinding(UUID.randomUUID(), school, provider, "CASHFREE", "TEST",
                binding.merchantReference(), binding.credentialsReference());
        rejects(claim, new GatewayDiscoveryEvidence.Discovery(foreign, exact().checkout(), exact().orderReads(), NOW));
        var webhook = new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.WEBHOOK, claim.id(), binding, claim.token(),
                claim.startedAt(), claim.leaseUntil(), claim.attemptNumber(), false);
        rejects(webhook, exact());
        verifyNoInteractions(jdbc, queue, dispatch, finance, manager);
        verify(registry, never()).provider(any());
    }

    @Test void ambientHumanSystemOrTransactionCannotBeReplacedByFinalizerAuthority() {
        for (UUID user : new UUID[] {null, UUID.randomUUID()}) {
            try (var ignored = TenantRequestContext.open(user, UUID.randomUUID())) {
                var before = TenantRequestContext.current(); rejects(claim, exact());
                assertThat(TenantRequestContext.current()).isEqualTo(before);
            }
        }
        TransactionSynchronizationManager.setActualTransactionActive(true); rejects(claim, exact());
        TransactionSynchronizationManager.clear();
        verifyNoInteractions(registry, jdbc, queue, dispatch, finance, manager);
    }

    @Test void replacedFrozenWorkCredentialIdentityFailsBeforeAnyAttemptBoundOrUnboundRetention() throws Exception {
        ready(); frozenIdentity = false;
        rejects(claim, exact());
        assertThat(events).contains("BEGIN_1", "FENCE", "ROLLBACK_1").doesNotContain("COMMIT_1", "BEGIN_2");
        assertThat(writes).isEmpty();
        verifyNoInteractions(queue, dispatch, finance);
    }

    @Test void futureObservationOrWrongDatabaseScopeCannotRetainFactsOrIssueReceipt() throws Exception {
        ready();
        rejects(claim, new GatewayDiscoveryEvidence.Discovery(binding, exact().checkout(), exact().orderReads(), NOW.plusSeconds(1)));
        verifyNoInteractions(queue, dispatch, finance);
        doReturn(false).when(jdbc).queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class));
        rejects(claim, exact());
        verifyNoInteractions(queue, dispatch, finance);
    }

    @Test void inactiveAdmissionAndCommitFailuresNeverClaimSuccessOrLoseContextCleanup() throws Exception {
        ready(); active = false;
        rejects(claim, exact());
        assertThat(events).contains("COMMIT_1", "ROLLBACK_2").doesNotContain("COMMIT_2");
        verify(queue, never()).complete(any(), any(), anyString(), any());
        doAnswer(call -> { throw new IllegalStateException("synthetic-private-commit-data"); }).when(manager).commit(any());
        rejects(claim, exact());
        assertThat(TenantRequestContext.current()).isEmpty();
    }

    @Test void freshInboxLeaseFinishesOnlyItsOwnRecordAndReusesTheSharedReceiptWriter() throws Exception {
        ready();
        assertThat(service.finalizeInboxObservation(ROUTE, inboxClaim, exact())).isEqualTo(GatewayWorkQueue.Outcome.COMPLETE);
        assertThat(events).containsSubsequence("BEGIN_1", "HISTORICAL_INBOX", "FACT_BOUND", "CHECKOUT", "COMMIT_1",
                "BEGIN_2", "CURRENT_INBOX", "CAPTURE", "FINANCE", "FINISH_COMPLETE", "COMMIT_2");
        verify(queue).complete(eq(inboxClaim), eq(GatewayWorkQueue.Outcome.COMPLETE), anyString(), any());
        verify(dispatch, never()).loadForReadRecovery(anyString(), any());
        verify(registry, never()).provider(any());
    }

    @Test void inboxUnknownAssociationRetainsGenuineUnboundMoneyAndRetriesWithoutChoosingALearner() throws Exception {
        ready(); historicalInboxKnown = false; currentInboxKnown = false;
        var read = new GatewayDiscoveryEvidence.Discovery(binding, null,
                List.of(GatewayDiscoveryEvidence.OrderRead.complete(ORDER, List.of(payment()))), NOW);
        assertThat(service.finalizeInboxObservation(ROUTE, inboxClaim, read)).isEqualTo(GatewayWorkQueue.Outcome.RETRY);
        verify(queue).retainPaymentObservation(eq(binding), eq(null), eq(payment()));
        verifyNoInteractions(finance); assertThat(writes).isEmpty();
        verify(queue).complete(eq(inboxClaim), eq(GatewayWorkQueue.Outcome.RETRY), eq("GATEWAY_CALLBACK_ASSOCIATION_UNRESOLVED"), any());
    }

    @Test void unknownInboxCannotRetainAReadOfAnUnrelatedOrder() throws Exception {
        ready(); historicalInboxKnown = false; currentInboxKnown = false;
        rejectsInbox(new GatewayDiscoveryEvidence.Discovery(binding, null,
                List.of(GatewayDiscoveryEvidence.OrderRead.complete("synthetic_unrelated_order", List.of(payment()))), NOW));
        verifyNoInteractions(queue, finance); assertThat(writes).isEmpty();
        assertThat(events).contains("ROLLBACK_1").doesNotContain("COMMIT_1", "CURRENT_INBOX");
    }

    @Test void newlyResolvedInboxAssociationRequiresANewCompleteCheckoutReadBeforePosting() throws Exception {
        ready(); historicalInboxKnown = false;
        var read = new GatewayDiscoveryEvidence.Discovery(binding, null,
                List.of(GatewayDiscoveryEvidence.OrderRead.complete(ORDER, List.of(payment()))), NOW);
        assertThat(service.finalizeInboxObservation(ROUTE, inboxClaim, read)).isEqualTo(GatewayWorkQueue.Outcome.RETRY);
        verifyNoInteractions(finance); assertThat(writes).isEmpty();
        verify(queue).retainPaymentObservation(eq(binding), eq(null), eq(payment()));
    }

    @Test void lostOrAmbiguousInboxAssociationKeepsIndependentFactsAndCannotPostFromOldStaging() throws Exception {
        ready(); currentInboxKnown = false;
        assertThat(service.finalizeInboxObservation(ROUTE, inboxClaim, exact())).isEqualTo(GatewayWorkQueue.Outcome.RETRY);
        assertThat(events).contains("FACT_BOUND", "COMMIT_1", "FINISH_RETRY").doesNotContain("CAPTURE", "FINANCE");
        verifyNoInteractions(finance);
    }

    @Test void exactInactiveInboxBindingRetainsMoneyForReviewWithoutAutomaticActivationOrFinance() throws Exception {
        ready(); currentInboxEnabled = false; active = false;
        assertThat(service.finalizeInboxObservation(ROUTE, inboxClaim, exact())).isEqualTo(GatewayWorkQueue.Outcome.REVIEW_REQUIRED);
        assertThat(events).contains("FACT_BOUND", "COMMIT_1", "FINISH_REVIEW_REQUIRED").doesNotContain("CAPTURE", "FINANCE");
        verifyNoInteractions(finance);
    }

    @Test void inboxWithoutOriginalCreateMarkerKeepsExactFactsButCannotCreateCaptureOrReceipt() throws Exception {
        ready(); createMarker = false; currentInboxEnabled = false;
        assertThat(service.finalizeInboxObservation(ROUTE, inboxClaim, exact())).isEqualTo(GatewayWorkQueue.Outcome.REVIEW_REQUIRED);
        assertThat(events).contains("FACT_BOUND").doesNotContain("CAPTURE", "FINANCE"); verifyNoInteractions(finance);
    }

    @Test void mismatchedCallbackPaymentOrOrderCannotAuthorizeAnOtherwisePaidCheckout() throws Exception {
        ready(); callbackPayment = "synthetic_other_callback_payment";
        assertThat(service.finalizeInboxObservation(ROUTE, inboxClaim, exact())).isEqualTo(GatewayWorkQueue.Outcome.REVIEW_REQUIRED);
        assertThat(events).doesNotContain("CAPTURE", "FINANCE"); verifyNoInteractions(finance);
        callbackPayment = PAYMENT; callbackOrder = "synthetic_other_callback_order";
        assertThat(service.finalizeInboxObservation(ROUTE, inboxClaim, exact())).isEqualTo(GatewayWorkQueue.Outcome.REVIEW_REQUIRED);
        verifyNoInteractions(finance);
    }

    @Test void expiredInboxClaimRollsBackOnlyCompletionAfterIndependentFactsCommit() throws Exception {
        ready(); currentInboxAvailable = false;
        rejectsInbox(exact());
        assertThat(events).containsSubsequence("FACT_BOUND", "CHECKOUT", "COMMIT_1", "BEGIN_2", "CURRENT_INBOX", "ROLLBACK_2");
        verifyNoInteractions(finance); verify(queue, never()).complete(any(), any(), anyString(), any());
    }

    @Test void failedLastInboxCompletionRollsBackFinanceWithoutChangingOriginalAttemptWork() throws Exception {
        ready(); finishResult = false; rejectsInbox(exact());
        assertThat(events).containsSubsequence("COMMIT_1", "CAPTURE", "FINANCE", "FINISH_COMPLETE", "ROLLBACK_2");
        assertThat(events).doesNotContain("COMMIT_2");
        verify(queue).complete(eq(inboxClaim), eq(GatewayWorkQueue.Outcome.COMPLETE), anyString(), any());
        verify(dispatch, never()).loadForReadRecovery(anyString(), any());
    }

    @Test void delayedInboxConfirmationReusesClosedDayHoldWithOriginalExactPaidTime() throws Exception {
        ready(); paidAt = NOW.minusSeconds(86400).plusNanos(500); createdAt = paidAt.minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        expiry = createdAt.plusSeconds(300); close = UUID.randomUUID();
        doReturn(new FinanceService.GatewayPostingResult("CAPTURED_REVIEW_REQUIRED", null, null)).when(finance)
                .postVerifiedGatewayCapture(eq(organisation), eq(school), any(), eq(null), eq(null), eq(null));
        assertThat(service.finalizeInboxObservation(ROUTE, inboxClaim, exact())).isEqualTo(GatewayWorkQueue.Outcome.REVIEW_REQUIRED);
        var capture = writes.getLast().parameters();
        assertThat(capture.getValue("paidAtExact")).isEqualTo(paidAt.toString());
        assertThat(capture.getValue("closeId")).isEqualTo(close);
        assertThat(capture.getValue("originalDate")).isEqualTo(paidAt.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate());
        verify(queue).complete(eq(inboxClaim), eq(GatewayWorkQueue.Outcome.REVIEW_REQUIRED), anyString(), any());
    }

    @Test void inboxRetriesReuseImmutableExistingCaptureAndNeverCreateAnotherReceiptSource() throws Exception {
        ready(); existingObservation = observation; existingCapture = UUID.randomUUID();
        assertThat(service.finalizeInboxObservation(ROUTE, inboxClaim, exact())).isEqualTo(GatewayWorkQueue.Outcome.COMPLETE);
        verify(finance).postVerifiedGatewayCapture(organisation, school, existingCapture, null, null, null);
        assertThat(writes).isEmpty();
    }

    @Test void inboxKindAndAmbientSynchronizationCannotBecomeBackgroundFinancialAuthority() {
        when(registry.workerEnabled()).thenReturn(true); when(registry.findRoute(ROUTE)).thenReturn(Optional.of(route));
        assertThatThrownBy(() -> service.finalizeInboxObservation(ROUTE, claim, exact())).isInstanceOf(IllegalStateException.class).hasNoCause();
        TransactionSynchronizationManager.initSynchronization();
        try { rejectsInbox(exact()); assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue(); }
        finally { TransactionSynchronizationManager.clearSynchronization(); }
        verifyNoInteractions(jdbc, queue, inboxDispatch, finance, manager);
    }

    @Test void interruptedInboxReadRetainsKnownFactsThenLeavesTheLeaseRecoverableWithoutFinance() throws Exception {
        ready(); Thread.currentThread().interrupt();
        try {
            rejectsInbox(exact());
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(events).contains("FACT_BOUND", "CHECKOUT", "COMMIT_1").doesNotContain("BEGIN_2", "CURRENT_INBOX", "CAPTURE", "FINANCE");
            verifyNoInteractions(finance); verify(queue, never()).complete(any(), any(), anyString(), any());
        } finally { Thread.interrupted(); }
    }

    private void rejectsInbox(GatewayDiscoveryEvidence.Discovery discovery) {
        assertThatThrownBy(() -> service.finalizeInboxObservation(ROUTE, inboxClaim, discovery)).isInstanceOf(IllegalStateException.class)
                .hasMessage(ERROR).hasNoCause();
    }

    private GatewayAttemptDispatch.ReadRecovery inboxSnapshot() {
        return new GatewayAttemptDispatch.ReadRecovery(attempt, account, attempt, binding, amount(), "INR", "RESERVED", "ACTIVE",
                "Asia/Kolkata", createdAt.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate(), createdAt, expiry,
                URI.create("https://synthetic-school.example.invalid/family/payments/" + attempt), 0, inboxClaim.leaseUntil(), createMarker);
    }

    private void ready() throws Exception {
        when(registry.workerEnabled()).thenReturn(true); when(registry.findRoute(ROUTE)).thenReturn(Optional.of(route));
        doAnswer(call -> {
            var definition = call.getArgument(0, TransactionDefinition.class);
            assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThat(definition.isReadOnly()).isFalse(); assertThat(definition.getTimeout()).isEqualTo(15);
            assertThat(TenantRequestContext.current().orElseThrow()).isEqualTo(new TenantRequestContext.ScopeValue(null, organisation));
            TransactionSynchronizationManager.setActualTransactionActive(true);
            events.add("BEGIN_" + ++transactionNumber); return new SimpleTransactionStatus();
        }).when(manager).getTransaction(any());
        doAnswer(call -> { events.add("COMMIT_" + transactionNumber); TransactionSynchronizationManager.setActualTransactionActive(false); return null; })
                .when(manager).commit(any());
        doAnswer(call -> { events.add("ROLLBACK_" + transactionNumber); TransactionSynchronizationManager.setActualTransactionActive(false); return null; })
                .when(manager).rollback(any());
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            if (sql.contains("FROM fee_gateway_payment_observations fact")) {
                assertThat(sql).doesNotContain("credentials_secret_reference=", "payment_provider_config_id=", "observed_at>");
                assertThat(sql).contains("fact.provider_paid_at_exact IS NOT NULL", "fact.provider_paid_at_exact IS DISTINCT FROM :paidAtExact",
                        "fact.provider_paid_at_exact IS NULL", "NOT :paidAtMicrosecondExact");
                return conflict;
            }
            if (sql.contains("FROM fee_gateway_captures")) return otherCapture;
            if (sql.contains("provider.status='ACTIVE'")) {
                // Fact/review-only inbox completion deliberately permits an
                // inactive exact binding, never a financial post. Normal receipt
                // completion still requires ACTIVE with requireActive=true.
                return active || Boolean.FALSE.equals(call.getArgument(1, SqlParameterSource.class).getValue("requireActive"));
            }
            return true;
        });
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Timestamp.class))).thenAnswer(
                call -> Timestamp.from(databaseTimes.isEmpty() ? NOW : databaseTimes.removeFirst()));
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(UUID.class))).thenAnswer(call -> close);
        when(jdbc.query(anyString(), any(SqlParameterSource.class), ArgumentMatchers.<ResultSetExtractor<Object>>any())).thenAnswer(call -> {
            String sql = call.getArgument(0); var p = call.getArgument(1, SqlParameterSource.class);
            var row = mock(ResultSet.class);
            if (sql.contains("SELECT attempt.id,attempt.learner_fee_account_id,attempt.amount")) {
                assertThat(sql).contains("FROM fee_gateway_attempt_work work", "provider.credentials_secret_reference=work.credentials_secret_reference",
                        "work.credentials_secret_reference=:credentialsReference").doesNotContain("work.status=", "work.lease_token=", "work.lease_until=", "work.attempt_count=");
                when(row.next()).thenReturn(frozenIdentity); when(row.getObject("id", UUID.class)).thenReturn(attempt);
                when(row.getObject("learner_fee_account_id", UUID.class)).thenReturn(account);
                when(row.getBigDecimal("amount")).thenReturn(amount()); when(row.getString("currency_code")).thenReturn("INR");
                when(row.getTimestamp("created_at")).thenReturn(Timestamp.from(createdAt));
                when(row.getTimestamp("expires_at")).thenReturn(Timestamp.from(expiry));
                when(row.getString("school_time_zone")).thenReturn("Asia/Kolkata");
            } else if (sql.contains("SELECT work.attempt_id")) {
                events.add("CLAIM_LOCK"); assertThat(sql).contains("FOR UPDATE OF work,attempt", "lease_started_at=:leaseStart",
                        "lease_until=:leaseUntil", "attempt_count=:attemptNumber").doesNotContain("create_dispatched_at");
                assertThat(p.getValue("leaseStart")).isEqualTo(Timestamp.from(claim.startedAt()));
                assertThat(p.getValue("leaseUntil")).isEqualTo(Timestamp.from(claim.leaseUntil()));
                assertThat(p.getValue("attemptNumber")).isEqualTo(claim.attemptNumber());
                when(row.next()).thenReturn(ownedClaim); when(row.getObject("attempt_id", UUID.class)).thenReturn(attempt);
            } else if (sql.contains("SELECT capture.id,")) {
                assertThat(sql).contains("capture.paid_at_exact=:paidAtExact", "capture.paid_at_exact IS NULL AND :paidAtMicrosecondExact",
                        "gateway_receipt_evidence_matches(capture.id,:organisationId,:schoolId)");
                assertThat(p.getValue("paidAtExact")).isEqualTo(paidAt.toString());
                assertThat(p.getValue("paidAtMicrosecondExact")).isEqualTo(GatewayExactInstant.floor(paidAt).equals(paidAt));
                when(row.next()).thenReturn(existingCapture != null); when(row.getObject("id", UUID.class)).thenReturn(existingCapture);
                when(row.getBoolean("exact")).thenReturn(exactExistingCapture);
            } else {
                assertThat(sql).contains("FROM fee_gateway_checkout_observations");
                when(row.next()).thenReturn(existingObservation != null); when(row.getObject("id", UUID.class)).thenReturn(existingObservation);
            }
            return call.getArgument(2, ResultSetExtractor.class).extractData(row);
        });
        doAnswer(call -> {
            events.add("FENCE"); assertThat(call.getArgument(1, SqlParameterSource.class).getValue("lockKey")).isEqualTo(school + ":finance");
            return true;
        }).when(jdbc).queryForObject(anyString(), any(SqlParameterSource.class), ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Boolean>>any());
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenAnswer(call -> {
            String sql = call.getArgument(0); var p = call.getArgument(1, SqlParameterSource.class);
            // Copy, because the next SQL intentionally adds to its internal parameter set.
            var saved = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
            for (String name : p.getParameterNames()) saved.addValue(name, p.getValue(name));
            writes.add(new Write(sql, saved)); events.add(sql.contains("fee_gateway_captures") ? "CAPTURE" : "CHECKOUT"); return 1;
        });
        when(queue.retainPaymentObservation(any(), any(), any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TenantRequestContext.current().orElseThrow().userId()).isNull();
            events.add(call.getArgument(1) == null ? "FACT_UNBOUND" : "FACT_BOUND"); return UUID.randomUUID();
        });
        when(dispatch.loadForReadRecovery(anyString(), any())).thenAnswer(call -> {
            events.add("READ_ADMISSION");
            return new GatewayAttemptDispatch.ReadRecovery(attempt, account, attempt, binding, amount(), "INR", "RESERVED", "ACTIVE",
                    "Asia/Kolkata", createdAt.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate(), createdAt, expiry,
                    URI.create("https://synthetic-school.example.invalid/family/payments/" + attempt), 0, claim.leaseUntil(), createMarker);
        });
        when(finance.postVerifiedGatewayCapture(eq(organisation), eq(school), any(), eq(null), eq(null), eq(null))).thenAnswer(call -> {
            events.add("FINANCE"); assertThat(TenantRequestContext.current().orElseThrow().userId()).isNull();
            return new FinanceService.GatewayPostingResult("POSTED", UUID.randomUUID(), "SYNTHETIC/000001");
        });
        when(queue.complete(any(), any(), anyString(), any())).thenAnswer(call -> {
            events.add("FINISH_" + call.getArgument(1));
            GatewayWorkQueue.requireSafeCode(call.getArgument(2));
            GatewayWorkQueue.requireRetryDelay(call.getArgument(3, Duration.class));
            return finishResult;
        });
        when(inboxDispatch.loadHistorical(anyString(), any())).thenAnswer(call -> {
            events.add("HISTORICAL_INBOX");
            return new GatewayInboxDispatch.Admission(historicalInboxKnown ? Optional.of(inboxSnapshot()) : Optional.empty(),
                    "PAYMENT_SUCCESS_WEBHOOK", callbackOrder, callbackPayment, null, false, NOW);
        });
        when(inboxDispatch.loadForReadRecovery(anyString(), any())).thenAnswer(call -> {
            events.add("CURRENT_INBOX");
            if (!currentInboxAvailable) throw new IllegalStateException("synthetic-private-expired-inbox-reference");
            return new GatewayInboxDispatch.Admission(currentInboxKnown ? Optional.of(inboxSnapshot()) : Optional.empty(),
                    "PAYMENT_SUCCESS_WEBHOOK", callbackOrder, callbackPayment, null,
                    currentInboxKnown && currentInboxEnabled && createMarker, NOW);
        });
    }

    private GatewayDiscoveryEvidence.Discovery exact() {
        return new GatewayDiscoveryEvidence.Discovery(binding, checkout(List.of(ORDER)),
                List.of(GatewayDiscoveryEvidence.OrderRead.complete(ORDER, List.of(payment()))), NOW);
    }
    private PaymentProviderAdapter.CheckoutObservation checkout(List<String> orders) {
        return new PaymentProviderAdapter.CheckoutObservation(attempt.toString(), attempt.toString(), amount(), "INR", amount(),
                PaymentProviderAdapter.CheckoutStatus.PAID, expiry, orders);
    }
    private PaymentProviderAdapter.PaymentObservation payment() {
        return new PaymentProviderAdapter.PaymentObservation(ORDER, PAYMENT, amount(), "INR", PaymentProviderAdapter.PaymentStatus.SUCCESS, true, paidAt, "upi");
    }
    private static BigDecimal amount() { return new BigDecimal("750.00"); }
    private void rejects(GatewayWorkQueue.Claim suppliedClaim, GatewayDiscoveryEvidence.Discovery discovery) {
        assertThatThrownBy(() -> service.finalizeObservation(ROUTE, suppliedClaim, discovery)).isInstanceOf(IllegalStateException.class)
                .hasMessage(ERROR).hasNoCause();
    }
    private record Write(String sql, SqlParameterSource parameters) {}
}
