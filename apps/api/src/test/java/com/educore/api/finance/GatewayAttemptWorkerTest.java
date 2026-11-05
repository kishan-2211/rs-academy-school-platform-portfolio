package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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

/** Mocked runner orchestration only: PostgreSQL, merchant and browser acceptance remain separate gates. */
class GatewayAttemptWorkerTest {
    private static final String ROUTE_KEY = "d".repeat(64);
    private static final UUID ORGANISATION = UUID.fromString("00000000-0000-4000-8000-000000000a01");
    private static final UUID SCHOOL = UUID.fromString("00000000-0000-4000-8000-000000000a02");
    private static final UUID PROVIDER_CONFIG = UUID.fromString("00000000-0000-4000-8000-000000000a03");
    private static final UUID ATTEMPT = UUID.fromString("00000000-0000-4000-8000-000000000a04");
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-000000000a05");
    private static final UUID TOKEN = UUID.fromString("00000000-0000-4000-8000-000000000a06");
    private static final Instant NOW = Instant.parse("2026-10-14T06:00:00.123456Z");
    private static final BigDecimal AMOUNT = new BigDecimal("750.00");
    private static final URI RETURN = URI.create("https://synthetic-school.example.invalid/family/payments/" + ATTEMPT);
    private static final URI HOSTED = URI.create("https://payments-test.cashfree.com/links/synthetic_worker_checkout");
    private final GatewayProviderRegistry registry = mock(GatewayProviderRegistry.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final GatewayWorkQueue queue = mock(GatewayWorkQueue.class);
    private final GatewayAttemptDispatch dispatch = mock(GatewayAttemptDispatch.class);
    private final GatewayCreateCommandAdmission commands = mock(GatewayCreateCommandAdmission.class);
    private final GatewayProviderDiscovery discovery = mock(GatewayProviderDiscovery.class);
    private final GatewayHostedCheckoutStore hosted = mock(GatewayHostedCheckoutStore.class);
    private final GatewayObservationFinalizer finalizer = mock(GatewayObservationFinalizer.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final PaymentProviderAdapter provider = mock(PaymentProviderAdapter.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final List<String> events = new CopyOnWriteArrayList<>();
    private GatewayProviderRegistry.Route route = route("TEST");
    private GatewayWorkQueue.ProviderBinding binding = binding("TEST");
    private GatewayWorkQueue.Claim claim = claim(false, NOW.plusSeconds(89));
    private Instant databaseNow = NOW;
    private boolean actualCreateMarker;
    private boolean noDueWork;
    private UUID recovered;
    private boolean scopeValid = true;
    private boolean completeAllowed = true;
    private GatewayWorkQueue.Outcome observedOutcome = GatewayWorkQueue.Outcome.RETRY;
    private final GatewayAttemptWorker worker = new GatewayAttemptWorker(registry, jdbc, queue, dispatch,
            commands, discovery, hosted, finalizer, manager, clock);

    @AfterEach void restoreOnlyTestThreadState() {
        Thread.interrupted();
        TransactionSynchronizationManager.clear();
        assertThat(TenantRequestContext.current()).isEmpty();
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }

    @Test void disabledByDefaultDoesNotAccessPersistenceResolveCredentialsOrCallProviders() {
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.DISABLED);
        verifyNoInteractions(jdbc, queue, dispatch, commands, discovery, hosted, finalizer, manager, provider);
        verify(registry, never()).provider(any());
    }

    @Test void unknownRouteIsUnavailableWithoutResolvingCredentialsOrOpeningPersistence() {
        when(registry.workerEnabled()).thenReturn(true);
        when(registry.findRoute(ROUTE_KEY)).thenReturn(Optional.empty());
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.UNAVAILABLE);
        verifyNoInteractions(jdbc, queue, dispatch, commands, discovery, hosted, finalizer, manager, provider);
        verify(registry, never()).provider(any());
    }

    @Test void liveRouteRequiresIndependentLiveEnablementBeforeAnyWorkOrProviderResolution() {
        when(registry.workerEnabled()).thenReturn(true);
        when(registry.findRoute(ROUTE_KEY)).thenReturn(Optional.of(route("LIVE")));
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.DISABLED);
        verifyNoInteractions(jdbc, queue, dispatch, commands, discovery, hosted, finalizer, manager, provider);
        verify(registry, never()).provider(any());
    }

    @Test void humanAndForeignSystemContextsCannotBeReplacedWithWorkerAuthority() {
        when(registry.workerEnabled()).thenReturn(true);
        for (UUID user : java.util.Arrays.asList(TOKEN, null)) {
            try (var ignored = TenantRequestContext.open(user, ACCOUNT)) {
                var before = TenantRequestContext.current();
                rejectsAmbient();
                assertThat(TenantRequestContext.current()).isEqualTo(before);
            }
        }
        verifyNoInteractions(jdbc, queue, dispatch, commands, discovery, hosted, finalizer, manager, provider);
    }

    @Test void ambientTransactionsSynchronizationAndResourcesRemainIntactAndCannotAuthorizeNetwork() {
        when(registry.workerEnabled()).thenReturn(true);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        rejectsAmbient();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        TransactionSynchronizationManager.clear();
        TransactionSynchronizationManager.initSynchronization();
        rejectsAmbient();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
        TransactionSynchronizationManager.clearSynchronization();
        Object key = new Object(), value = new Object();
        TransactionSynchronizationManager.bindResource(key, value);
        try {
            rejectsAmbient();
            assertThat(TransactionSynchronizationManager.getResource(key)).isSameAs(value);
        } finally { TransactionSynchronizationManager.unbindResource(key); }
        verifyNoInteractions(jdbc, queue, dispatch, commands, discovery, hosted, finalizer, manager, provider);
    }

    @Test void idlePollUsesFreshWritableSystemTransactionAndFinanceFenceWithoutResolvingCredentials() throws Exception {
        ready(); noDueWork = true;
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.IDLE);
        assertThat(events).containsSubsequence("BEGIN", "FENCE", "RECOVER", "CLAIM", "COMMIT");
        verify(registry, never()).provider(any());
        verifyNoInteractions(commands, discovery, hosted, finalizer, provider);
        assertOutsidePersistence();
    }

    @Test void expiredLeaseRecoveryIsBoundedAndDoesNotDispatchAnotherCreate() throws Exception {
        ready(); recovered = ATTEMPT; noDueWork = true;
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.RECOVERED);
        assertThat(events).containsSubsequence("BEGIN", "FENCE", "RECOVER", "COMMIT");
        verifyNoInteractions(commands, discovery, hosted, finalizer, provider);
        verify(registry, never()).provider(any());
    }

    @Test void committedCreateCommandPrecedesExactlyOneHttpCallOutsideAllPersistenceAndThenReadRetry() throws Exception {
        ready();
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        assertThat(events).containsSubsequence("FENCE", "RECOVER", "CLAIM", "LOAD", "DB_CLOCK", "COMMIT",
                "PROVIDER", "COMMAND_COMMITTED", "HTTP_CREATE", "STORE_CREATE", "FENCE", "LOAD", "DB_CLOCK", "FINISH", "COMMIT");
        verify(provider, times(1)).createHostedCheckout(any(PaymentProviderAdapter.CheckoutRequest.class));
        verify(commands, times(1)).admit(ROUTE_KEY, claim);
        verify(queue, times(1)).complete(eq(claim), eq(GatewayWorkQueue.Outcome.RETRY), anyString(), any(Duration.class));
        verifyNoInteractions(discovery, finalizer);
        assertThat(actualCreateMarker).isTrue();
        assertOutsidePersistence();
    }

    @Test void unknownPostPreservesCommittedMarkerAndNextAttemptUsesGetOnlyWithoutResolvingProviderAgain() throws Exception {
        ready();
        doAnswer(call -> {
            assertOutsidePersistence(); events.add("HTTP_CREATE_UNKNOWN");
            throw new IllegalStateException("synthetic-private-provider-response");
        }).when(provider).createHostedCheckout(any(PaymentProviderAdapter.CheckoutRequest.class));
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        assertThat(actualCreateMarker).isTrue();
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        verify(commands, times(1)).admit(ROUTE_KEY, claim);
        verify(provider, times(1)).createHostedCheckout(any(PaymentProviderAdapter.CheckoutRequest.class));
        verify(registry, times(1)).provider(route);
        verify(discovery, times(1)).read(eq(provider), any(), any(Duration.class));
        verify(finalizer, times(1)).finalizeObservation(eq(ROUTE_KEY), eq(claim), any());
        verify(queue, times(1)).complete(eq(claim), eq(GatewayWorkQueue.Outcome.RETRY), anyString(), any(Duration.class));
    }

    @Test void actualCommittedMarkerOverridesFalseClaimHintAndNeverRecreatesCheckout() throws Exception {
        ready(); actualCreateMarker = true;
        assertThat(claim.createDispatched()).isFalse();
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        verifyNoInteractions(commands);
        verify(provider, never()).createHostedCheckout(any(PaymentProviderAdapter.CheckoutRequest.class));
        verify(discovery).read(eq(provider), any(), any(Duration.class));
        verify(finalizer).finalizeObservation(eq(ROUTE_KEY), eq(claim), any());
        verify(queue, never()).complete(any(), any(), anyString(), any());
    }

    @Test void getUrlFailureStillFinalizesGenuineMoneyAndDoesNotDoubleFinishTheLease() throws Exception {
        ready(); actualCreateMarker = true; observedOutcome = GatewayWorkQueue.Outcome.COMPLETE;
        doThrow(new IllegalStateException("synthetic-private-bearer-url")).when(hosted).retainGet(anyString(), any(), any());
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.COMPLETE);
        verify(finalizer).finalizeObservation(eq(ROUTE_KEY), eq(claim), any());
        verify(queue, never()).complete(any(), any(), anyString(), any());
        verifyNoInteractions(commands);
        assertOutsidePersistence();
    }

    @Test void createUrlFailureStillSchedulesGetRecoveryAndCannotAuthorizeAnotherCreate() throws Exception {
        ready(); doThrow(new IllegalStateException("synthetic-private-bearer-url")).when(hosted)
                .retainCreate(anyString(), any(), any(), any());
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        assertThat(actualCreateMarker).isTrue();
        verify(queue).complete(eq(claim), eq(GatewayWorkQueue.Outcome.RETRY), anyString(), any(Duration.class));
        verifyNoInteractions(discovery, finalizer);
    }

    @Test void verifiedReviewHoldMapsToReviewWithoutRepostingOrFinishingTwice() throws Exception {
        ready(); actualCreateMarker = true; observedOutcome = GatewayWorkQueue.Outcome.REVIEW_REQUIRED;
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.REVIEW_REQUIRED);
        verify(finalizer).finalizeObservation(eq(ROUTE_KEY), eq(claim), any());
        verify(queue, never()).complete(any(), any(), anyString(), any());
        verifyNoInteractions(commands);
    }

    @Test void failedFinancialFinalizerLeavesUnknownLeaseForRecoveryRatherThanAcknowledgingCompletion() throws Exception {
        ready(); actualCreateMarker = true;
        doThrow(new IllegalStateException("synthetic-private-financial-reference")).when(finalizer)
                .finalizeObservation(anyString(), any(), any());
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.FENCED_OUT);
        verify(queue, never()).complete(any(), any(), anyString(), any());
        assertOutsidePersistence();
    }

    @Test void missingCredentialsAfterClaimCannotReachCreateOrProviderReadsAndUsesRevalidatedRetry() throws Exception {
        ready(); doThrow(new IllegalStateException("synthetic-private-client-secret")).when(registry).provider(route);
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        assertThat(events).containsSubsequence("COMMIT", "BEGIN", "FENCE", "LOAD", "DB_CLOCK", "FINISH", "COMMIT");
        verifyNoInteractions(commands, discovery, hosted, finalizer, provider);
    }

    @Test void commandAdmissionFailureNeverPostsAndRequiresCurrentClaimBeforeRetry() throws Exception {
        ready(); doThrow(new IllegalStateException("synthetic-private-accounting-reference")).when(commands).admit(ROUTE_KEY, claim);
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        verify(provider, never()).createHostedCheckout(any(PaymentProviderAdapter.CheckoutRequest.class));
        assertThat(events).containsSubsequence("COMMIT", "PROVIDER", "BEGIN", "FENCE", "LOAD", "DB_CLOCK", "FINISH", "COMMIT");
        verifyNoInteractions(discovery, hosted, finalizer);
    }

    @Test void disablingNewCheckoutsDoesNotPreventRecoveryOfAnAlreadyDispatchedPayment() throws Exception {
        ready(); actualCreateMarker = true; when(registry.creationEnabled()).thenReturn(false);
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        verifyNoInteractions(commands);
        verify(provider, never()).createHostedCheckout(any(PaymentProviderAdapter.CheckoutRequest.class));
        verify(discovery).read(eq(provider), any(), any(Duration.class));
        verify(finalizer).finalizeObservation(eq(ROUTE_KEY), eq(claim), any());
        verify(queue, never()).complete(any(), any(), anyString(), any());
    }

    @Test void disablingNewCheckoutsDoesNotConsumeFreshMarkerOrSendAnHttpCreate() throws Exception {
        ready(); when(registry.creationEnabled()).thenReturn(false);
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        assertThat(actualCreateMarker).isFalse();
        verifyNoInteractions(commands, discovery, hosted, finalizer, provider);
        verify(queue).complete(eq(claim), eq(GatewayWorkQueue.Outcome.RETRY), eq("GATEWAY_NEW_CREATE_DISABLED"), any(Duration.class));
    }

    @Test void failedReadHasNoInventedEmptyPaymentsAndRequiresRevalidatedRetry() throws Exception {
        ready(); actualCreateMarker = true;
        doThrow(new IllegalStateException("synthetic-private-get-response")).when(discovery).read(any(), any(), any());
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        verifyNoInteractions(commands, hosted, finalizer);
        verify(queue).complete(eq(claim), eq(GatewayWorkQueue.Outcome.RETRY), eq("GATEWAY_READ_OUTCOME_UNKNOWN"), any(Duration.class));
        assertThat(events).containsSubsequence("COMMIT", "PROVIDER", "BEGIN", "FENCE", "LOAD", "DB_CLOCK", "FINISH", "COMMIT");
    }

    @Test void changedFullClaimAfterPostCannotBeFinishedThroughOnlyATokenCheck() throws Exception {
        ready();
        java.util.concurrent.atomic.AtomicInteger loads = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            assertSystemTransaction();
            assertThat(call.getArgument(1, GatewayWorkQueue.Claim.class)).isEqualTo(claim);
            if (loads.incrementAndGet() > 1) { events.add("LOAD_REJECTED"); throw new IllegalStateException("synthetic-private-changed-claim"); }
            events.add("LOAD"); return snapshot(actualCreateMarker);
        }).when(dispatch).loadForReadRecovery(anyString(), any());
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.FENCED_OUT);
        assertThat(actualCreateMarker).isTrue();
        assertThat(events).containsSubsequence("HTTP_CREATE", "BEGIN", "FENCE", "LOAD_REJECTED", "ROLLBACK");
        verify(queue, never()).complete(any(), any(), anyString(), any());
        verifyNoInteractions(discovery, finalizer);
    }

    @Test void providerResolutionInterruptionDoesNotConsumeMarkerOrPerformAnyProviderIo() throws Exception {
        ready();
        doAnswer(call -> { assertOutsidePersistence(); Thread.currentThread().interrupt(); return provider; })
                .when(registry).provider(route);
        try {
            assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.FENCED_OUT);
            assertThat(Thread.currentThread().isInterrupted()).isTrue(); assertThat(actualCreateMarker).isFalse();
            verifyNoInteractions(commands, discovery, hosted, finalizer, provider);
            verify(queue, never()).complete(any(), any(), anyString(), any());
        } finally { Thread.interrupted(); }
    }

    @Test void expiredDatabaseLeaseCannotBeExtendedByFixedApplicationClockAndCannotPerformNetwork() throws Exception {
        ready(); actualCreateMarker = true; databaseNow = claim.leaseUntil();
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.FENCED_OUT);
        verifyNoInteractions(commands, discovery, hosted, finalizer, provider);
        verify(registry, never()).provider(any());
        verify(queue, never()).complete(any(), any(), anyString(), any());
    }

    @Test void insufficientLeaseBudgetDoesNotStartGetOrCreateButLeavesAControlledRetry() throws Exception {
        ready(); actualCreateMarker = true; claim = claim(false, NOW.plusSeconds(44));
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        verifyNoInteractions(commands, discovery, hosted, finalizer, provider);
        verify(queue).complete(eq(claim), eq(GatewayWorkQueue.Outcome.RETRY), anyString(), any(Duration.class));
    }

    @Test void getBudgetIsPositiveBoundedAndReservesPersistenceTimeFromActualDatabaseLease() throws Exception {
        ready(); actualCreateMarker = true; claim = claim(false, NOW.plusSeconds(70));
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.RETRY);
        var budget = ArgumentCaptor.forClass(Duration.class);
        verify(discovery).read(eq(provider), any(), budget.capture());
        assertThat(budget.getValue().compareTo(Duration.ZERO)).isPositive();
        assertThat(budget.getValue().compareTo(Duration.ofSeconds(25))).isLessThanOrEqualTo(0);
        assertThat(budget.getValue().compareTo(Duration.ofSeconds(40))).isLessThanOrEqualTo(0);
        verify(queue, never()).complete(any(), any(), anyString(), any());
    }

    @Test void queueFinishFalseIsFencedOutAndCannotBecomeSuccessAcknowledgement() throws Exception {
        ready(); completeAllowed = false;
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.FENCED_OUT);
        assertThat(actualCreateMarker).isTrue();
        assertThat(events).contains("FINISH", "ROLLBACK");
        verifyNoInteractions(discovery, finalizer);
    }

    @Test void interruptedCallerDoesNotClaimResolveCredentialsOrPerformNetwork() {
        when(registry.workerEnabled()).thenReturn(true);
        Thread.currentThread().interrupt();
        try {
            assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.FENCED_OUT);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verifyNoInteractions(jdbc, queue, dispatch, commands, discovery, hosted, finalizer, manager, provider);
            verify(registry, never()).provider(any());
        } finally { Thread.interrupted(); }
    }

    @Test void wrongDatabaseSystemScopeCannotClaimOrResolveProviderAndIsSanitized() throws Exception {
        ready(); scopeValid = false;
        assertThat(worker.runNext(ROUTE_KEY)).isEqualTo(GatewayAttemptWorker.Step.FENCED_OUT);
        verifyNoInteractions(commands, discovery, hosted, finalizer, provider);
        verify(registry, never()).provider(any());
        verify(queue, never()).claimNextAttempt(any());
    }

    private void ready() throws Exception {
        when(registry.workerEnabled()).thenReturn(true);
        when(registry.creationEnabled()).thenReturn(true);
        when(registry.findRoute(ROUTE_KEY)).thenReturn(Optional.of(route));
        when(registry.provider(any())).thenAnswer(call -> {
            assertOutsidePersistence(); assertThat(call.getArgument(0, GatewayProviderRegistry.Route.class)).isEqualTo(route);
            events.add("PROVIDER"); return provider;
        });
        when(provider.providerCode()).thenReturn("CASHFREE");
        doAnswer(call -> {
            var definition = call.getArgument(0, TransactionDefinition.class);
            assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThat(definition.getTimeout()).isEqualTo(15); assertThat(definition.isReadOnly()).isFalse();
            assertThat(TenantRequestContext.current().orElseThrow()).isEqualTo(new TenantRequestContext.ScopeValue(null, ORGANISATION));
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
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
            var row = mock(ResultSet.class);
            when(row.getObject(1, OffsetDateTime.class)).thenReturn(databaseNow.atOffset(ZoneOffset.UTC));
            when(row.getTimestamp(1)).thenReturn(Timestamp.from(databaseNow));
            return call.getArgument(2, RowMapper.class).mapRow(row, 0);
        });
        when(queue.recoverExpiredAttemptLease(any())).thenAnswer(call -> {
            assertSystemTransaction(); assertThat(events).contains("FENCE");
            assertThat(call.getArgument(0, GatewayWorkQueue.ProviderBinding.class)).isEqualTo(binding); events.add("RECOVER"); return recovered;
        });
        when(queue.claimNextAttempt(any())).thenAnswer(call -> {
            assertSystemTransaction(); assertThat(events).containsSubsequence("FENCE", "RECOVER");
            assertThat(call.getArgument(0, GatewayWorkQueue.ProviderBinding.class)).isEqualTo(binding); events.add("CLAIM"); return noDueWork ? null : claim;
        });
        when(dispatch.loadForReadRecovery(anyString(), any())).thenAnswer(call -> {
            assertSystemTransaction(); assertThat(events).contains("FENCE");
            assertThat(call.getArgument(0, String.class)).isEqualTo(ROUTE_KEY);
            assertThat(call.getArgument(1, GatewayWorkQueue.Claim.class)).isEqualTo(claim);
            events.add("LOAD"); return snapshot(actualCreateMarker);
        });
        when(commands.admit(anyString(), any())).thenAnswer(call -> {
            assertOutsidePersistence(); assertThat(call.getArgument(0, String.class)).isEqualTo(ROUTE_KEY);
            assertThat(call.getArgument(1, GatewayWorkQueue.Claim.class)).isEqualTo(claim);
            // This mock honors the admission helper's tested commit-before-return contract.
            var admitted = new GatewayAttemptDispatch.CreateAdmission(snapshot(false),
                    new GatewayCheckoutReadiness.ReadyBook(PROVIDER_CONFIG, ORGANISATION, SCHOOL), databaseNow);
            actualCreateMarker = true; events.add("COMMAND_COMMITTED");
            return new GatewayCreateCommandAdmission.Command(admitted, new PaymentProviderAdapter.CheckoutRequest(
                    ATTEMPT, ACCOUNT, AMOUNT, "INR", admitted.attempt().expiresAt(), RETURN, "0000000000"));
        });
        when(provider.createHostedCheckout(any(PaymentProviderAdapter.CheckoutRequest.class))).thenAnswer(call -> {
            assertOutsidePersistence(); assertThat(events).contains("COMMAND_COMMITTED");
            var request = call.getArgument(0, PaymentProviderAdapter.CheckoutRequest.class);
            assertThat(request.attemptId()).isEqualTo(ATTEMPT); assertThat(request.accountId()).isEqualTo(ACCOUNT);
            assertThat(request.amount()).isEqualByComparingTo(AMOUNT); assertThat(request.returnUri()).isEqualTo(RETURN);
            events.add("HTTP_CREATE"); return new PaymentProviderAdapter.HostedCheckout(ATTEMPT.toString(), HOSTED);
        });
        when(hosted.retainCreate(anyString(), any(), any(), any())).thenAnswer(call -> {
            assertOutsidePersistence(); events.add("STORE_CREATE"); return new GatewayHostedCheckoutStore.Retained(TOKEN, false);
        });
        when(discovery.read(any(), any(), any(Duration.class))).thenAnswer(call -> {
            assertOutsidePersistence(); events.add("HTTP_GET");
            assertThat(call.getArgument(0, PaymentProviderAdapter.class)).isSameAs(provider);
            assertThat(call.getArgument(1, GatewayAttemptDispatch.ReadRecovery.class).createPreviouslyDispatched()).isTrue();
            return new GatewayProviderDiscovery.ReadResult(new GatewayDiscoveryEvidence.Discovery(binding, null, List.of(), NOW), Optional.empty());
        });
        when(hosted.retainGet(anyString(), any(), any())).thenAnswer(call -> {
            assertOutsidePersistence(); events.add("STORE_GET"); return Optional.empty();
        });
        when(finalizer.finalizeObservation(anyString(), any(), any())).thenAnswer(call -> {
            assertOutsidePersistence(); assertThat(events).contains("HTTP_GET"); events.add("FINALIZE"); return observedOutcome;
        });
        when(queue.complete(any(), any(), anyString(), any())).thenAnswer(call -> {
            assertSystemTransaction(); assertThat(events).containsSubsequence("FENCE", "LOAD", "DB_CLOCK");
            assertThat(call.getArgument(0, GatewayWorkQueue.Claim.class)).isEqualTo(claim);
            assertThat(call.getArgument(1, GatewayWorkQueue.Outcome.class)).isEqualTo(GatewayWorkQueue.Outcome.RETRY);
            assertThat(call.getArgument(2, String.class)).matches("[A-Z][A-Z0-9_]{2,99}");
            assertThat(call.getArgument(3, Duration.class).compareTo(Duration.ZERO)).isPositive();
            assertThat(call.getArgument(3, Duration.class).compareTo(Duration.ofDays(1))).isLessThanOrEqualTo(0);
            events.add("FINISH"); return completeAllowed;
        });
    }

    private GatewayAttemptDispatch.ReadRecovery snapshot(boolean marker) {
        Instant created = NOW.minusSeconds(60);
        return new GatewayAttemptDispatch.ReadRecovery(ATTEMPT, ACCOUNT, ATTEMPT, binding, AMOUNT, "INR", "RESERVED", "ACTIVE",
                "Asia/Kolkata", created.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate(), created, NOW.plusSeconds(1740), RETURN,
                0, claim.leaseUntil(), marker);
    }
    private GatewayWorkQueue.Claim claim(boolean hint, Instant leaseUntil) {
        return new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.ATTEMPT, ATTEMPT, binding, TOKEN, NOW.minusSeconds(1), leaseUntil, 1, hint);
    }
    private static GatewayProviderRegistry.Route route(String environment) {
        return new GatewayProviderRegistry.Route(ROUTE_KEY, ORGANISATION, SCHOOL, PROVIDER_CONFIG, "CASHFREE",
                CashfreePaymentProviderAdapter.Environment.valueOf(environment), "synthetic_merchant", "synthetic:worker-credentials",
                "env:APP_FINANCE_GATEWAY_CASHFREE_" + environment + "_CLIENT_ID", "env:APP_FINANCE_GATEWAY_CASHFREE_" + environment + "_CLIENT_SECRET");
    }
    private static GatewayWorkQueue.ProviderBinding binding(String environment) {
        return new GatewayWorkQueue.ProviderBinding(ORGANISATION, SCHOOL, PROVIDER_CONFIG, "CASHFREE", environment,
                "synthetic_merchant", "synthetic:worker-credentials");
    }
    private static void assertSystemTransaction() {
        assertThat(TenantRequestContext.current().orElseThrow()).isEqualTo(new TenantRequestContext.ScopeValue(null, ORGANISATION));
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
    }
    private static void assertOutsidePersistence() {
        assertThat(TenantRequestContext.current()).isEmpty();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }
    private void rejectsAmbient() {
        assertThatThrownBy(() -> worker.runNext(ROUTE_KEY)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Gateway worker requires trusted background admission outside caller persistence").hasNoCause();
    }
}
