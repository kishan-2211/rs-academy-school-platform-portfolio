package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Isolated runner orchestration only; real PostgreSQL/provider/browser acceptance is separate. */
class GatewayWebhookWorkerTest {
    private static final String ROUTE_KEY = "e".repeat(64);
    private static final UUID ORGANISATION = UUID.fromString("00000000-0000-4000-8000-000000000b01");
    private static final UUID SCHOOL = UUID.fromString("00000000-0000-4000-8000-000000000b02");
    private static final UUID CONFIG = UUID.fromString("00000000-0000-4000-8000-000000000b03");
    private static final UUID INBOX = UUID.fromString("00000000-0000-4000-8000-000000000b04");
    private static final UUID ATTEMPT = UUID.fromString("00000000-0000-4000-8000-000000000b05");
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-000000000b06");
    private static final UUID TOKEN = UUID.fromString("00000000-0000-4000-8000-000000000b07");
    private static final Instant NOW = Instant.parse("2026-10-14T06:00:00.123456Z");
    private static final String ORDER = "synthetic_callback_order";
    private final GatewayProviderRegistry registry = mock(GatewayProviderRegistry.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final GatewayWorkQueue queue = mock(GatewayWorkQueue.class);
    private final GatewayInboxDispatch dispatch = mock(GatewayInboxDispatch.class);
    private final GatewayProviderDiscovery discovery = mock(GatewayProviderDiscovery.class);
    private final GatewayObservationFinalizer finalizer = mock(GatewayObservationFinalizer.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final PaymentProviderAdapter provider = mock(PaymentProviderAdapter.class);
    // Deliberately unrelated application clock: lease authority remains DB time.
    private final Clock clock = Clock.fixed(NOW.minus(Duration.ofDays(100)), ZoneOffset.UTC);
    private final List<String> events = new ArrayList<>();
    private final GatewayProviderRegistry.Route route = route("TEST");
    private final GatewayWorkQueue.ProviderBinding binding = binding("TEST");
    private GatewayWorkQueue.Claim claim = claim(NOW.plusSeconds(89));
    private Instant databaseNow = NOW;
    private boolean knownAttempt = true;
    private boolean financiallyEnabled = true;
    private String eventType = "PAYMENT_SUCCESS_WEBHOOK";
    private String refundReference = "synthetic_callback_refund";
    private boolean scopeValid = true;
    private boolean noDueWork;
    private UUID recovered;
    private boolean completeAllowed = true;
    private GatewayWorkQueue.Outcome outcome = GatewayWorkQueue.Outcome.COMPLETE;
    private final GatewayRefundInboxRecovery refundRecovery = mock(GatewayRefundInboxRecovery.class);
    private final GatewayWebhookWorker worker = new GatewayWebhookWorker(registry, jdbc, queue, dispatch,
            discovery, finalizer, refundRecovery, manager, clock);

    @AfterEach void restoreOnlyTestThreadState() {
        Thread.interrupted(); TransactionSynchronizationManager.clear();
        assertThat(TenantRequestContext.current()).isEmpty();
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }

    @Test void disabledByDefaultDoesNotAccessPersistenceCredentialsOrProvider() {
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.DISABLED);
        verifyNoInteractions(jdbc, queue, dispatch, discovery, finalizer, manager, provider);
        verify(registry, never()).provider(any());
    }

    @Test void unknownRouteCannotOpenPersistenceOrResolveCredentials() {
        when(registry.workerEnabled()).thenReturn(true); when(registry.findRoute(ROUTE_KEY)).thenReturn(Optional.empty());
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.UNAVAILABLE);
        verifyNoInteractions(jdbc, queue, dispatch, discovery, finalizer, manager, provider);
        verify(registry, never()).provider(any());
    }

    @Test void liveRouteRequiresItsSeparateOperatorEnablement() {
        when(registry.workerEnabled()).thenReturn(true); when(registry.findRoute(ROUTE_KEY)).thenReturn(Optional.of(route("LIVE")));
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.DISABLED);
        verifyNoInteractions(jdbc, queue, dispatch, discovery, finalizer, manager, provider);
    }

    @Test void humanOrOtherSystemContextCannotBeReplacedByCallbackWorkerAuthority() {
        for (UUID user : java.util.Arrays.asList(TOKEN, null)) {
            try (var ignored = TenantRequestContext.open(user, ACCOUNT)) {
                var before = TenantRequestContext.current(); rejectsAmbient();
                assertThat(TenantRequestContext.current()).isEqualTo(before);
            }
        }
        verifyNoInteractions(registry, jdbc, queue, dispatch, discovery, finalizer, manager, provider);
    }

    @Test void ambientTransactionSynchronizationAndResourcesAreRejectedAndPreserved() {
        TransactionSynchronizationManager.setActualTransactionActive(true); rejectsAmbient();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue(); TransactionSynchronizationManager.clear();
        TransactionSynchronizationManager.initSynchronization(); rejectsAmbient();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue(); TransactionSynchronizationManager.clearSynchronization();
        Object key = new Object(), value = new Object(); TransactionSynchronizationManager.bindResource(key, value);
        try { rejectsAmbient(); assertThat(TransactionSynchronizationManager.getResource(key)).isSameAs(value); }
        finally { TransactionSynchronizationManager.unbindResource(key); }
        verifyNoInteractions(registry, jdbc, queue, dispatch, discovery, finalizer, manager, provider);
    }

    @Test void idlePollUsesFreshWritableSystemScopeAndFinanceFenceThenCommitsWithoutCredentials() throws Exception {
        ready(); noDueWork = true;
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.IDLE);
        assertThat(events).containsSubsequence("BEGIN", "FENCE", "RECOVER", "CLAIM", "COMMIT");
        verify(registry, never()).provider(any()); verifyNoInteractions(discovery, finalizer, provider);
        assertOutsidePersistence();
    }

    @Test void expiredInboxRecoveryIsBoundedAndNoWorkMeansNoProviderIo() throws Exception {
        ready(); recovered = INBOX; noDueWork = true;
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.RECOVERED);
        verify(queue).recoverExpiredWebhookLease(binding); verify(queue).claimNextWebhook(binding);
        verify(queue, never()).recoverExpiredAttemptLease(any()); verify(queue, never()).claimNextAttempt(any());
        verify(registry, never()).provider(any()); verifyNoInteractions(discovery, finalizer, provider);
    }

    @Test void knownAttemptAdmissionCommitsBeforeCredentialResolutionGetAndSingleFinalizer() throws Exception {
        ready();
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        assertThat(events).containsSubsequence("BEGIN", "FENCE", "RECOVER", "CLAIM", "LOAD", "COMMIT", "PROVIDER", "GET_ATTEMPT", "FINALIZE");
        verify(discovery).read(eq(provider), eq(snapshot()), any(Duration.class));
        verify(discovery, never()).readOrder(any(), any(), anyString(), any());
        verify(finalizer).finalizeInboxObservation(eq(ROUTE_KEY), eq(claim), any());
        verify(queue, never()).complete(any(), any(), anyString(), any());
        verify(provider, never()).createHostedCheckout(any());
        assertOutsidePersistence();
    }

    @Test void unassociatedCallbackReadsOnlyItsOriginalOrderWithoutGuessingAttemptOrCreatingCheckout() throws Exception {
        ready(); knownAttempt = false;
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        verify(discovery).readOrder(eq(provider), eq(binding), eq(ORDER), any(Duration.class));
        verify(discovery, never()).read(any(), any(), any());
        verify(finalizer).finalizeInboxObservation(eq(ROUTE_KEY), eq(claim), any());
        verify(provider, never()).lookupHostedCheckout(anyString()); verify(provider, never()).createHostedCheckout(any());
        verify(queue, never()).complete(any(), any(), anyString(), any());
    }

    @Test void inactiveButExactHistoricalBindingStillUsesGetAndFinalizerControlledReview() throws Exception {
        ready(); financiallyEnabled = false; outcome = GatewayWorkQueue.Outcome.REVIEW_REQUIRED;
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.REVIEW_REQUIRED);
        verify(discovery).read(eq(provider), eq(snapshot()), any(Duration.class));
        verify(finalizer).finalizeInboxObservation(eq(ROUTE_KEY), eq(claim), any());
        verify(queue, never()).complete(any(), any(), anyString(), any());
    }

    @Test void callbackFailureOrDroppedMetadataDoesNotOverrideIndependentGetMoney() throws Exception {
        ready(); eventType = "PAYMENT_FAILED_WEBHOOK";
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        eventType = "PAYMENT_USER_DROPPED_WEBHOOK";
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        verify(discovery, times(2)).read(eq(provider), any(), any());
        verify(finalizer, times(2)).finalizeInboxObservation(eq(ROUTE_KEY), eq(claim), any());
        verify(queue, never()).complete(any(), any(), anyString(), any());
    }

    @Test void finalizerRetryMapsDirectlyWithoutAnotherQueueCompletion() throws Exception {
        ready(); outcome = GatewayWorkQueue.Outcome.RETRY;
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        verify(finalizer).finalizeInboxObservation(eq(ROUTE_KEY), eq(claim), any());
        verify(queue, never()).complete(any(), any(), anyString(), any());
    }

    @Test void refundMetadataIsPreservedForFencedGovernedReviewWithoutPaymentGetOrCredentials() throws Exception {
        ready(); knownAttempt = false; financiallyEnabled = false; eventType = "REFUND_STATUS_WEBHOOK";
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.REVIEW_REQUIRED);
        verify(queue).complete(eq(claim), eq(GatewayWorkQueue.Outcome.REVIEW_REQUIRED), eq("GATEWAY_REFUND_RECONCILIATION_REQUIRED"), any());
        assertThat(events).containsSubsequence("COMMIT", "BEGIN", "FENCE", "LOAD", "DB_CLOCK", "FINISH", "COMMIT");
        verify(registry, never()).provider(any()); verifyNoInteractions(discovery, finalizer, provider);
    }

    @Test void autoRefundMetadataNeverMasqueradesAsRefundConfirmationOrPaymentObservation() throws Exception {
        ready(); knownAttempt = false; financiallyEnabled = false; eventType = "AUTO_REFUND_STATUS_WEBHOOK";
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.REVIEW_REQUIRED);
        verify(queue).complete(eq(claim), eq(GatewayWorkQueue.Outcome.REVIEW_REQUIRED), eq("GATEWAY_REFUND_RECONCILIATION_REQUIRED"), any());
        verify(registry, never()).provider(any()); verifyNoInteractions(discovery, finalizer, provider);
    }

    @Test void canonicalStandardRefundDelegatesToTheSameBoundedWorkerOutsidePersistence() throws Exception {
        ready();knownAttempt=false;financiallyEnabled=false;eventType="REFUND_STATUS_WEBHOOK";
        refundReference="83000000-0000-4000-8000-000000000001";
        when(refundRecovery.recover(eq(provider),eq(ROUTE_KEY),eq(claim),any(),anyLong())).thenAnswer(call->{
            assertThat(TenantRequestContext.current()).isEmpty();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(call.getArgument(3,GatewayInboxDispatch.Admission.class).refundReference()).isEqualTo(refundReference);
            return GatewayAttemptWorker.Step.COMPLETE;
        });
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        verify(registry).provider(route);verifyNoInteractions(discovery,finalizer,provider);
        verify(queue,never()).complete(any(),any(),anyString(),any());
    }

    @Test void unavailableRefundCredentialsRetryWithoutRunningRecoveryOrAnotherProviderApi() throws Exception {
        ready();knownAttempt=false;financiallyEnabled=false;eventType="REFUND_STATUS_WEBHOOK";
        refundReference="83000000-0000-4000-8000-000000000001";
        doThrow(new IllegalStateException("synthetic_private_credentials")).when(registry).provider(route);
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        verify(queue).complete(eq(claim),eq(GatewayWorkQueue.Outcome.RETRY),eq("GATEWAY_PROVIDER_UNAVAILABLE"),any());
        verifyNoInteractions(refundRecovery,discovery,finalizer,provider);
    }

    @Test void unavailableCredentialsRetryOnlyAfterExactFreshClaimAndDatabaseTimeRevalidation() throws Exception {
        ready(); doThrow(new IllegalStateException("synthetic_private_credentials")).when(registry).provider(route);
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        verify(queue).complete(eq(claim), eq(GatewayWorkQueue.Outcome.RETRY), eq("GATEWAY_PROVIDER_UNAVAILABLE"), any());
        assertThat(events).containsSubsequence("COMMIT", "BEGIN", "FENCE", "LOAD", "DB_CLOCK", "FINISH", "COMMIT");
        verifyNoInteractions(discovery, finalizer, provider); assertOutsidePersistence();
    }

    @Test void unexpectedAssociatedReadFailureProducesBoundedRetryNotSyntheticEmptyPayments() throws Exception {
        ready(); doThrow(new IllegalStateException("synthetic_private_get_response")).when(discovery).read(any(), any(), any());
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        verify(queue).complete(eq(claim), eq(GatewayWorkQueue.Outcome.RETRY), eq("GATEWAY_INBOX_READ_OUTCOME_UNKNOWN"), any());
        verifyNoInteractions(finalizer); verify(discovery, never()).readOrder(any(), any(), anyString(), any());
    }

    @Test void unexpectedOrphanReadFailureCannotBeAcknowledgedAsCompleteDiscovery() throws Exception {
        ready(); knownAttempt = false; doThrow(new IllegalStateException("synthetic_private_order_response")).when(discovery).readOrder(any(), any(), anyString(), any());
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        verify(queue).complete(eq(claim), eq(GatewayWorkQueue.Outcome.RETRY), eq("GATEWAY_INBOX_READ_OUTCOME_UNKNOWN"), any());
        verifyNoInteractions(finalizer); verify(discovery, never()).read(any(), any(), any());
    }

    @Test void failedFinalizerCannotOverwriteCommittedEvidenceWithGuessedCompletion() throws Exception {
        ready(); doThrow(new IllegalStateException("synthetic_private_finance_fact")).when(finalizer).finalizeInboxObservation(anyString(), any(), any());
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.FENCED_OUT);
        verify(queue, never()).complete(any(), any(), anyString(), any()); assertOutsidePersistence();
    }

    @Test void exactClaimChangeBeforeRetryRollsBackWithoutTokenOnlyCompletion() throws Exception {
        ready(); doThrow(new IllegalStateException("synthetic_provider_unavailable")).when(registry).provider(route);
        var loads = new AtomicInteger(); doAnswer(call -> {
            assertSystemTransaction(); events.add("LOAD");
            if (loads.incrementAndGet() > 1) throw new IllegalStateException("synthetic_changed_full_claim");
            return admission();
        }).when(dispatch).loadForReadRecovery(anyString(), any());
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.FENCED_OUT);
        assertThat(events).contains("ROLLBACK"); verify(queue, never()).complete(any(), any(), anyString(), any());
    }

    @Test void expiredAdmissionCannotBeExtendedByEarlierFixedApplicationClock() throws Exception {
        ready(); databaseNow = claim.leaseUntil();
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.FENCED_OUT);
        assertThat(events).contains("ROLLBACK"); verify(registry, never()).provider(any());
        verifyNoInteractions(discovery, finalizer, provider); verify(queue, never()).complete(any(), any(), anyString(), any());
    }

    @Test void admissionBeforeActualLeaseStartIsFencedOutBeforeProviderResolution() throws Exception {
        ready(); databaseNow = claim.startedAt().minusNanos(1);
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.FENCED_OUT);
        verify(registry, never()).provider(any()); verifyNoInteractions(discovery, finalizer, provider);
    }

    @Test void insufficientLeaseBudgetReservesFinalizationAndRetriesWithoutCredentialsOrGet() throws Exception {
        ready(); claim = claim(NOW.plusSeconds(44));
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        verify(queue).complete(eq(claim), eq(GatewayWorkQueue.Outcome.RETRY), eq("GATEWAY_INBOX_READ_BUDGET_UNAVAILABLE"), any());
        verify(registry, never()).provider(any()); verifyNoInteractions(discovery, finalizer, provider);
    }

    @Test void getBudgetIsBoundedByLeaseLessPersistenceReserveAndActualAdmissionLatency() throws Exception {
        ready(); claim = claim(NOW.plusSeconds(70));
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        var captured = ArgumentCaptor.forClass(Duration.class); verify(discovery).read(eq(provider), any(), captured.capture());
        assertThat(captured.getValue().compareTo(Duration.ZERO)).isPositive();
        assertThat(captured.getValue().compareTo(Duration.ofSeconds(25))).isLessThanOrEqualTo(0);
        assertThat(captured.getValue().compareTo(Duration.ofSeconds(40))).isLessThanOrEqualTo(0);
    }

    @Test void maximumLongLeaseStillCapsWholeGetAtFortySeconds() throws Exception {
        ready(); claim = claim(NOW.plusSeconds(119));
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        var captured = ArgumentCaptor.forClass(Duration.class); verify(discovery).read(eq(provider), any(), captured.capture());
        assertThat(captured.getValue()).isEqualTo(Duration.ofSeconds(40));
    }

    @Test void refusedRetryCompletionRollsBackAndCannotBecomeSuccess() throws Exception {
        ready(); completeAllowed = false; doThrow(new IllegalStateException("synthetic_provider_unavailable")).when(registry).provider(route);
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.FENCED_OUT);
        assertThat(events).containsSubsequence("FINISH", "ROLLBACK"); verifyNoInteractions(discovery, finalizer, provider);
    }

    @Test void refusedRefundReviewCompletionAlsoRollsBackWithoutInventingRefundFacts() throws Exception {
        ready(); completeAllowed = false; knownAttempt = false; financiallyEnabled = false; eventType = "REFUND_STATUS_WEBHOOK";
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.FENCED_OUT);
        assertThat(events).containsSubsequence("FINISH", "ROLLBACK"); verifyNoInteractions(discovery, finalizer, provider);
    }

    @Test void databaseScopeMismatchCannotClaimAnyInboxOrCallProvider() throws Exception {
        ready(); scopeValid = false;
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.FENCED_OUT);
        verify(queue, never()).claimNextWebhook(any()); verify(registry, never()).provider(any());
        verifyNoInteractions(dispatch, discovery, finalizer, provider);
    }

    @Test void failedAdmissionCommitCannotResolveCredentialsOrStartProviderReads() throws Exception {
        ready(); doAnswer(call -> { events.add("COMMIT_UNKNOWN"); TransactionSynchronizationManager.clear();
            throw new IllegalStateException("Synthetic unknown database commit"); }).when(manager).commit(any());
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.FENCED_OUT);
        verify(registry, never()).provider(any()); verifyNoInteractions(discovery, finalizer, provider);
        verify(queue, never()).complete(any(), any(), anyString(), any()); assertOutsidePersistence();
    }

    @Test void expiredDatabaseTimeAfterReadFailureCannotCompleteTheOriginalLease() throws Exception {
        ready(); doAnswer(call -> { assertOutsidePersistence(); databaseNow = claim.leaseUntil();
            throw new IllegalStateException("Synthetic unknown GET"); }).when(discovery).read(any(), any(), any());
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.FENCED_OUT);
        assertThat(events).contains("ROLLBACK"); verify(queue, never()).complete(any(), any(), anyString(), any());
        verifyNoInteractions(finalizer);
    }

    @Test void refundReviewRevalidatesActualDatabaseTimeAfterFullClaimAndCannotFinishExpiredLease() throws Exception {
        ready(); knownAttempt = false; financiallyEnabled = false; eventType = "REFUND_STATUS_WEBHOOK";
        var loads = new AtomicInteger(); doAnswer(call -> {
            assertSystemTransaction(); events.add("LOAD");
            if (loads.incrementAndGet() > 1) databaseNow = claim.leaseUntil();
            return admission();
        }).when(dispatch).loadForReadRecovery(anyString(), any());
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.FENCED_OUT);
        assertThat(events).contains("ROLLBACK"); verify(queue, never()).complete(any(), any(), anyString(), any());
        verify(registry, never()).provider(any()); verifyNoInteractions(discovery, finalizer, provider);
    }

    @Test void interruptedCallerLeavesInboxUnclaimedAndInterruptIntact() {
        when(registry.workerEnabled()).thenReturn(true); Thread.currentThread().interrupt();
        try {
            assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.FENCED_OUT);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verifyNoInteractions(jdbc, queue, dispatch, discovery, finalizer, manager, provider);
        } finally { Thread.interrupted(); }
    }

    @Test void providerResolutionInterruptionNeverReachesReadOrQueueCompletion() throws Exception {
        ready(); doAnswer(call -> { assertOutsidePersistence(); Thread.currentThread().interrupt(); return provider; }).when(registry).provider(route);
        try {
            assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.FENCED_OUT);
            assertThat(Thread.currentThread().isInterrupted()).isTrue(); verifyNoInteractions(discovery, finalizer, provider);
            verify(queue, never()).complete(any(), any(), anyString(), any());
        } finally { Thread.interrupted(); }
    }

    @Test void interruptedReadStillPassesKnownFactsToTheBoundaryWithoutGuessingCompletion() throws Exception {
        ready(); doAnswer(call -> { assertOutsidePersistence(); Thread.currentThread().interrupt(); return read(); }).when(discovery).read(any(), any(), any());
        try {
            assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.FENCED_OUT);
            assertThat(Thread.currentThread().isInterrupted()).isTrue(); verify(finalizer).finalizeInboxObservation(eq(ROUTE_KEY), eq(claim), any());
            verify(queue, never()).complete(any(), any(), anyString(), any());
        } finally { Thread.interrupted(); }
    }

    @Test void immutableRouteReusesOneProviderButRechecksEachActualInboxAdmission() throws Exception {
        ready(); assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        verify(registry, times(1)).provider(route); verify(dispatch, times(2)).loadForReadRecovery(ROUTE_KEY, claim);
        verify(discovery, times(2)).read(eq(provider), any(), any());
    }

    @Test void disablingNewCheckoutCreationDoesNotDisableAuthenticatedCallbackRecovery() throws Exception {
        ready(); when(registry.creationEnabled()).thenReturn(false);
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        verify(registry, never()).creationEnabled(); verify(provider, never()).createHostedCheckout(any());
        verify(finalizer).finalizeInboxObservation(eq(ROUTE_KEY), eq(claim), any());
    }

    private void ready() throws Exception {
        when(registry.workerEnabled()).thenReturn(true); when(registry.findRoute(ROUTE_KEY)).thenReturn(Optional.of(route));
        when(registry.provider(any())).thenAnswer(call -> {
            assertOutsidePersistence(); assertThat(call.getArgument(0, GatewayProviderRegistry.Route.class)).isEqualTo(route);
            assertThat(events).contains("COMMIT"); events.add("PROVIDER"); return provider;
        });
        doAnswer(call -> {
            var definition = call.getArgument(0, TransactionDefinition.class);
            assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThat(definition.getTimeout()).isEqualTo(15); assertThat(definition.isReadOnly()).isFalse();
            assertThat(TenantRequestContext.current().orElseThrow()).isEqualTo(new TenantRequestContext.ScopeValue(null, ORGANISATION));
            TransactionSynchronizationManager.setActualTransactionActive(true); TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            events.add("BEGIN"); return new SimpleTransactionStatus();
        }).when(manager).getTransaction(any());
        doAnswer(call -> { events.add("COMMIT"); TransactionSynchronizationManager.clear(); return null; }).when(manager).commit(any());
        doAnswer(call -> { events.add("ROLLBACK"); TransactionSynchronizationManager.clear(); return null; }).when(manager).rollback(any());
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class))).thenAnswer(call -> {
            assertSystemTransaction(); events.add("SCOPE"); return scopeValid;
        });
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), ArgumentMatchers.<RowMapper<Object>>any())).thenAnswer(call -> {
            assertSystemTransaction(); String sql = call.getArgument(0);
            if (sql.contains("pg_advisory_xact_lock")) {
                assertThat(call.getArgument(1, SqlParameterSource.class).getValue("lockKey")).isEqualTo(SCHOOL + ":finance");
                events.add("FENCE"); return true;
            }
            assertThat(sql).isEqualTo("SELECT clock_timestamp()"); events.add("DB_CLOCK");
            var row = mock(ResultSet.class); when(row.getObject(1, OffsetDateTime.class)).thenReturn(databaseNow.atOffset(ZoneOffset.UTC));
            return call.getArgument(2, RowMapper.class).mapRow(row, 0);
        });
        when(queue.recoverExpiredWebhookLease(any())).thenAnswer(call -> {
            assertSystemTransaction(); assertThat(events).contains("FENCE");
            assertThat(call.getArgument(0, GatewayWorkQueue.ProviderBinding.class)).isEqualTo(binding); events.add("RECOVER"); return recovered;
        });
        when(queue.claimNextWebhook(any())).thenAnswer(call -> {
            assertSystemTransaction(); assertThat(events).containsSubsequence("FENCE", "RECOVER");
            assertThat(call.getArgument(0, GatewayWorkQueue.ProviderBinding.class)).isEqualTo(binding); events.add("CLAIM"); return noDueWork ? null : claim;
        });
        when(dispatch.loadForReadRecovery(anyString(), any())).thenAnswer(call -> {
            assertSystemTransaction(); assertThat(events).contains("FENCE");
            assertThat(call.getArgument(0, String.class)).isEqualTo(ROUTE_KEY); assertThat(call.getArgument(1, GatewayWorkQueue.Claim.class)).isEqualTo(claim);
            events.add("LOAD"); return admission();
        });
        when(discovery.read(any(), any(), any())).thenAnswer(call -> {
            assertOutsidePersistence(); assertThat(call.getArgument(0, PaymentProviderAdapter.class)).isSameAs(provider);
            assertThat(call.getArgument(1, GatewayAttemptDispatch.ReadRecovery.class)).isEqualTo(snapshot()); events.add("GET_ATTEMPT"); return read();
        });
        when(discovery.readOrder(any(), any(), anyString(), any())).thenAnswer(call -> {
            assertOutsidePersistence(); assertThat(call.getArgument(0, PaymentProviderAdapter.class)).isSameAs(provider);
            assertThat(call.getArgument(1, GatewayWorkQueue.ProviderBinding.class)).isEqualTo(binding);
            assertThat(call.getArgument(2, String.class)).isEqualTo(ORDER); events.add("GET_ORDER"); return read();
        });
        when(finalizer.finalizeInboxObservation(anyString(), any(), any())).thenAnswer(call -> {
            assertOutsidePersistence(); assertThat(events).contains("COMMIT");
            assertThat(call.getArgument(0, String.class)).isEqualTo(ROUTE_KEY); assertThat(call.getArgument(1, GatewayWorkQueue.Claim.class)).isEqualTo(claim);
            events.add("FINALIZE"); return outcome;
        });
        when(queue.complete(any(), any(), anyString(), any())).thenAnswer(call -> {
            assertSystemTransaction(); assertThat(events).containsSubsequence("FENCE", "LOAD", "DB_CLOCK");
            assertThat(call.getArgument(0, GatewayWorkQueue.Claim.class)).isEqualTo(claim);
            assertThat(call.getArgument(1, GatewayWorkQueue.Outcome.class)).isIn(GatewayWorkQueue.Outcome.RETRY, GatewayWorkQueue.Outcome.REVIEW_REQUIRED);
            assertThat(call.getArgument(2, String.class)).matches("[A-Z][A-Z0-9_]{2,99}");
            var retry = call.getArgument(3, Duration.class);
            assertThat(retry).isEqualTo(Duration.ofSeconds(GatewayWorkQueue.retryDelay(claim.attemptNumber(), claim.token())));
            events.add("FINISH"); return completeAllowed;
        });
    }

    private GatewayInboxDispatch.Admission admission() {
        boolean refund = eventType.contains("REFUND");
        return new GatewayInboxDispatch.Admission(knownAttempt ? Optional.of(snapshot()) : Optional.empty(), eventType,
                "AUTO_REFUND_STATUS_WEBHOOK".equals(eventType) ? null : ORDER, "synthetic_callback_payment",
                refund ? refundReference : null, financiallyEnabled && knownAttempt, databaseNow);
    }
    private GatewayProviderDiscovery.ReadResult read() {
        return new GatewayProviderDiscovery.ReadResult(new GatewayDiscoveryEvidence.Discovery(binding, null,
                List.of(GatewayDiscoveryEvidence.OrderRead.unknown(ORDER)), NOW), Optional.empty());
    }
    private GatewayAttemptDispatch.ReadRecovery snapshot() {
        Instant created = NOW.minusSeconds(60);
        return new GatewayAttemptDispatch.ReadRecovery(ATTEMPT, ACCOUNT, ATTEMPT, binding, new BigDecimal("750.00"), "INR", "RESERVED", "ACTIVE",
                "Asia/Kolkata", created.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate(), created, NOW.plusSeconds(1740),
                URI.create("https://synthetic-school.example.invalid/family/payments/" + ATTEMPT), 0, claim.leaseUntil(), true);
    }
    private GatewayWorkQueue.Claim claim(Instant until) {
        return new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.WEBHOOK, INBOX, binding, TOKEN, NOW.minusSeconds(1), until, 1, false);
    }
    private static GatewayProviderRegistry.Route route(String environment) {
        return new GatewayProviderRegistry.Route(ROUTE_KEY, ORGANISATION, SCHOOL, CONFIG, "CASHFREE", CashfreePaymentProviderAdapter.Environment.valueOf(environment),
                "synthetic_callback_merchant", "synthetic:callback-credentials", "env:APP_FINANCE_GATEWAY_CASHFREE_" + environment + "_CLIENT_ID",
                "env:APP_FINANCE_GATEWAY_CASHFREE_" + environment + "_CLIENT_SECRET");
    }
    private static GatewayWorkQueue.ProviderBinding binding(String environment) {
        return new GatewayWorkQueue.ProviderBinding(ORGANISATION, SCHOOL, CONFIG, "CASHFREE", environment,
                "synthetic_callback_merchant", "synthetic:callback-credentials");
    }
    private static void assertSystemTransaction() {
        assertThat(TenantRequestContext.current().orElseThrow()).isEqualTo(new TenantRequestContext.ScopeValue(null, ORGANISATION));
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
    }
    private static void assertOutsidePersistence() {
        assertThat(TenantRequestContext.current()).isEmpty(); assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse(); assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }
    private void rejectsAmbient() {
        assertThatThrownBy(() -> worker.runNext(ROUTE_KEY)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Gateway inbox worker requires trusted background admission outside caller persistence").hasNoCause();
    }
}
