package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.educore.api.common.api.ApiException;
import com.educore.api.finance.FamilyGatewayPaymentModels.*;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Scoped API orchestration, not proof of actual PostgreSQL rollback or provider/browser acceptance. */
class FamilyGatewayPaymentServiceTest {
    private static final UUID ORG = UUID.fromString("00000000-0000-4000-8000-000000000c01");
    private static final UUID SCHOOL = UUID.fromString("00000000-0000-4000-8000-000000000c02");
    private static final UUID LEARNER = UUID.fromString("00000000-0000-4000-8000-000000000c03");
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-4000-8000-000000000c04");
    private static final UUID USER = UUID.fromString("00000000-0000-4000-8000-000000000c05");
    private static final UUID MEMBERSHIP = UUID.fromString("00000000-0000-4000-8000-000000000c06");
    private static final UUID ATTEMPT = UUID.fromString("00000000-0000-4000-8000-000000000c07");
    private static final UUID CONFIG = UUID.fromString("00000000-0000-4000-8000-000000000c08");
    private static final UUID CHARGE = UUID.fromString("00000000-0000-4000-8000-000000000c09");
    private static final UUID PAYMENT = UUID.fromString("00000000-0000-4000-8000-000000000c0a");
    private static final Instant NOW = Instant.parse("2026-10-14T06:00:00.123456Z");
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final FamilyGatewayAccess access = mock(FamilyGatewayAccess.class);
    private final GatewayReservationService reservations = mock(GatewayReservationService.class);
    private final GatewayWorkQueue queue = mock(GatewayWorkQueue.class);
    private final GatewayProviderRegistry registry = mock(GatewayProviderRegistry.class);
    private final FamilyGatewayCheckoutService checkout = mock(FamilyGatewayCheckoutService.class);
    private final AuthenticatedUser user = new AuthenticatedUser(USER, UUID.randomUUID(), "synthetic-family", "synthetic-family@example.invalid",
            "Synthetic family", false, ORG, Set.of(MEMBERSHIP), Set.of("portal.guardian"), List.of());
    private final FamilyGatewayAccess.Scope scope = new FamilyGatewayAccess.Scope(ORG, SCHOOL, LEARNER, MEMBERSHIP);
    private final FamilyGatewayPaymentService service = new FamilyGatewayPaymentService(jdbc, access, reservations, queue, registry, checkout);
    private final List<String> events = new ArrayList<>();
    private TenantRequestContext.Scope context;
    private boolean databaseScope = true;
    private String state = "RESERVED";
    private Instant expires = NOW.plusSeconds(1800);
    private boolean activeReservation = true;
    private boolean reviewEvidence;
    private boolean additionalReviewEvidence;
    private boolean receipt;
    private UUID initiatedBy = USER;
    private int attemptRows = 1;
    private int providerRows = 1;
    private int selectedRows = 1;
    private UUID existingRequest;
    private GatewayProviderRegistry.Route route = route("TEST", "synthetic:family-credentials");
    private String providerCredentials = "synthetic:family-credentials";

    @BeforeEach void ready() throws Exception {
        context = TenantRequestContext.open(USER, ORG);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class))).thenAnswer(call -> {
            assertThat(call.getArgument(1, SqlParameterSource.class).getValue("organisationId")).isEqualTo(ORG);
            assertThat(call.getArgument(1, SqlParameterSource.class).getValue("actorUserId")).isEqualTo(USER);
            return databaseScope;
        });
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), ArgumentMatchers.<RowMapper<Object>>any())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            if (sql.contains("pg_advisory_xact_lock")) {
                assertThat(call.getArgument(1, SqlParameterSource.class).getValue("lockKey")).isEqualTo(SCHOOL + ":finance"); events.add("FENCE"); return true;
            }
            assertThat(sql).isEqualTo("SELECT clock_timestamp()"); events.add("DB_CLOCK");
            var row = mock(ResultSet.class); when(row.getObject(1, OffsetDateTime.class)).thenReturn(NOW.atOffset(ZoneOffset.UTC));
            return call.getArgument(2, RowMapper.class).mapRow(row, 0);
        });
        when(jdbc.query(anyString(), any(SqlParameterSource.class), ArgumentMatchers.<RowMapper<Object>>any())).thenAnswer(call -> {
            String sql = call.getArgument(0); var mapper = call.getArgument(2, RowMapper.class); var values = new ArrayList<>();
            if (sql.contains("SELECT attempt.id,attempt.organisation_id")) {
                assertThat(sql).contains("current_app_user_id()=:actorUserId", "attempt.organisation_id=:organisationId");
                events.add("ATTEMPTS");
                for (int index = 0; index < attemptRows; index++) values.add(mapper.mapRow(attemptRow(), index));
            } else if (sql.contains("FROM payment_provider_configs")) {
                assertThat(sql).contains("organisation_id=:organisationId", "school_id=:schoolId", "status='ACTIVE'", "LIMIT 2");
                events.add("ROUTE");
                for (int index = 0; index < providerRows; index++) values.add(mapper.mapRow(providerRow(), index));
            } else if (sql.contains("SELECT allocation.learner_fee_charge_id")) {
                assertThat(sql).contains("allocation.organisation_id=:organisationId", "allocation.school_id=:schoolId", "allocation.learner_fee_account_id=:accountId", "LIMIT 101");
                events.add("CHARGES");
                for (int index = 0; index < selectedRows; index++) values.add(mapper.mapRow(chargeRow(), index));
            } else throw new AssertionError("Unexpected synthetic service query");
            return values;
        });
        when(jdbc.query(anyString(), any(SqlParameterSource.class), ArgumentMatchers.<ResultSetExtractor<Object>>any())).thenAnswer(call -> {
            String sql = call.getArgument(0); assertThat(sql).contains("attempt.initiated_by_user_id=:actorUserId", "attempt.client_request_id=:requestId", "account.learner_id=:learnerId");
            events.add("EXISTING"); var row = mock(ResultSet.class); when(row.next()).thenReturn(existingRequest != null);
            when(row.getObject(1, UUID.class)).thenReturn(existingRequest);
            return call.getArgument(2, ResultSetExtractor.class).extractData(row);
        });
        when(access.requireChild(user, LEARNER)).thenAnswer(call -> { events.add("CHILD"); return scope; });
        when(access.requireAccount(user, LEARNER, ACCOUNT)).thenAnswer(call -> { events.add("ACCOUNT");
            return new FamilyGatewayAccess.AccountScope(scope, ACCOUNT, "ACTIVE", "INR", "Synthetic historical session"); });
        when(registry.creationEnabled()).thenReturn(true); when(registry.workerEnabled()).thenReturn(true);
        when(registry.configuredRoutes()).thenAnswer(call -> List.of(route));
        when(reservations.prepare(eq(user), eq(LEARNER), any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            assertThat(TenantRequestContext.current().orElseThrow()).isEqualTo(new TenantRequestContext.ScopeValue(USER, ORG));
            assertThat(events).containsSubsequence("CHILD", "FENCE", "CHILD"); events.add("PREPARE");
            return new GatewayReservationService.PlannedAttempt(ATTEMPT, state, new BigDecimal("750.00"), "INR", LocalDate.parse("2026-10-14"), expires, 0);
        });
        when(reservations.replay(eq(user), eq(LEARNER), eq(ATTEMPT), any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            assertThat(events).containsSubsequence("CHILD", "FENCE", "CHILD", "ACCOUNT"); events.add("REPLAY");
            return new GatewayReservationService.PlannedAttempt(ATTEMPT, state, new BigDecimal("750.00"), "INR", LocalDate.parse("2026-10-14"), expires, 0);
        });
        when(queue.enqueueAttempt(any(), eq(ATTEMPT))).thenAnswer(call -> {
            assertThat(events).containsSubsequence("PREPARE", "ACCOUNT", "ROUTE");
            assertThat(TenantRequestContext.current().orElseThrow()).isEqualTo(new TenantRequestContext.ScopeValue(USER, ORG));
            events.add("ENQUEUE"); return ATTEMPT;
        });
        when(checkout.canContinue(user, LEARNER, ATTEMPT)).thenReturn(true);
    }

    @AfterEach void cleanupOwnTestContext() {
        context.close(); TransactionSynchronizationManager.clear();
        assertThat(TenantRequestContext.current()).isEmpty(); assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }

    @Test void paymentUsesExactHistoricalAccountAndMinimalDtoWithoutLatestEnrolmentOrPrivateProviderFields() {
        var payment = service.payment(user, ATTEMPT);
        assertThat(payment.id()).isEqualTo(ATTEMPT); assertThat(payment.accountId()).isEqualTo(ACCOUNT);
        assertThat(payment.sessionName()).isEqualTo("Synthetic historical session"); assertThat(payment.status()).isEqualTo(Status.READY);
        verify(access).requireAccount(user, LEARNER, ACCOUNT); verify(access, never()).requireChild(any(), any());
        assertThat(java.util.Arrays.stream(Payment.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName)).containsExactly(
                "id", "learnerId", "accountId", "sessionName", "amount", "currencyCode", "status", "createdAt", "expiresAt", "originalBusinessDate",
                "reservationActive", "initiatedByYou", "canContinue", "selectedCharges", "receipt");
        verifyNoInteractions(reservations, queue); verify(registry, never()).provider(any());
    }

    @Test void invalidMissingUserOrSessionMembershipFailsBeforePersistence() {
        assertThatThrownBy(() -> service.payment(null, ATTEMPT)).isInstanceOf(ApiException.class);
        var noMembership = new AuthenticatedUser(USER, user.sessionId(), user.loginIdentifier(), user.email(), user.displayName(), false, ORG, Set.of(), user.capabilities(), List.of());
        assertThatThrownBy(() -> service.payment(noMembership, ATTEMPT)).isInstanceOf(ApiException.class);
        verifyNoInteractions(jdbc, access, reservations, queue, checkout);
    }

    @Test void ambientWrongActorOrOrganisationCannotReadOrReplaceTheContext() {
        for (var pair : List.of(new UUID[]{UUID.randomUUID(), ORG}, new UUID[]{USER, UUID.randomUUID()}, new UUID[]{null, ORG})) {
            try (var ignored = TenantRequestContext.open(pair[0], pair[1])) {
                var before = TenantRequestContext.current(); assertThatThrownBy(() -> service.payment(user, ATTEMPT)).isInstanceOf(ApiException.class);
                assertThat(TenantRequestContext.current()).isEqualTo(before);
            }
        }
        verifyNoInteractions(jdbc, access, reservations, queue, checkout);
    }

    @Test void databaseActorMismatchFailsClosedBeforeAttemptOrChildLookup() {
        databaseScope = false; assertThatThrownBy(() -> service.payment(user, ATTEMPT)).isInstanceOf(ApiException.class);
        verifyNoInteractions(access, reservations, queue, checkout);
        verify(jdbc, never()).query(anyString(), any(SqlParameterSource.class), ArgumentMatchers.<RowMapper<Object>>any());
    }

    @Test void noActualTransactionOrReadonlyInitiationCannotReserveOrEnqueue() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        assertThatThrownBy(() -> service.payment(user, ATTEMPT)).isInstanceOf(ApiException.class);
        TransactionSynchronizationManager.setActualTransactionActive(true); TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatThrownBy(() -> service.initiate(user, LEARNER, request())).isInstanceOf(ApiException.class);
        verifyNoInteractions(jdbc, access, reservations, queue, checkout);
    }

    @Test void unknownOrAmbiguousAttemptNeverGrantsChildAccess() {
        attemptRows = 0; assertThatThrownBy(() -> service.payment(user, ATTEMPT)).isInstanceOf(ApiException.class);
        attemptRows = 2; assertThatThrownBy(() -> service.payment(user, ATTEMPT)).isInstanceOf(ApiException.class);
        verifyNoInteractions(access, reservations, queue, checkout);
    }

    @Test void crossChildRevokedOrOmittedSessionGrantDenialPropagatesWithoutPrivateContinuation() {
        doThrow(missing()).when(access).requireAccount(user, LEARNER, ACCOUNT);
        assertThatThrownBy(() -> service.payment(user, ATTEMPT)).isInstanceOf(ApiException.class).hasMessage("Synthetic family scope unavailable");
        verifyNoInteractions(checkout, reservations, queue);
    }

    @Test void historyReauthorizesEveryHistoricalAccountAndPagesTwentyPlusOne() {
        attemptRows = 21; var result = service.history(user, LEARNER, 2);
        assertThat(result.page()).isEqualTo(2); assertThat(result.pageSize()).isEqualTo(20); assertThat(result.hasMore()).isTrue();
        assertThat(result.payments()).hasSize(20); verify(access, times(20)).requireAccount(user, LEARNER, ACCOUNT);
        var p = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).query(contains("LIMIT :limit OFFSET :offset"), p.capture(), ArgumentMatchers.<RowMapper<Object>>any());
        assertThat(p.getValue().getValue("limit")).isEqualTo(21); assertThat(p.getValue().getValue("offset")).isEqualTo(40);
        verify(registry, never()).provider(any());
    }

    @Test void emptyHistoryStillReturnsHonestDisabledAvailabilityWithoutReadingProviderCredentials() {
        attemptRows = 0; when(registry.creationEnabled()).thenReturn(false);
        var result = service.history(user, LEARNER, 0); assertThat(result.payments()).isEmpty(); assertThat(result.hasMore()).isFalse();
        assertThat(result.availability()).isEqualTo(Availability.DISABLED);
        verify(registry, never()).provider(any()); verifyNoInteractions(checkout, reservations, queue);
    }

    @Test void invalidHistoryPageDoesNotLookupChildOrRunDatasetQuery() {
        for (int page : List.of(-1, 10001)) assertThatThrownBy(() -> service.history(user, LEARNER, page)).isInstanceOf(ApiException.class);
        verifyNoInteractions(access, reservations, queue, checkout);
    }

    @Test void availableRequiresOneExactActiveConfiguredProviderIncludingCredentialsLabel() {
        attemptRows = 0; assertThat(service.history(user, LEARNER, 0).availability()).isEqualTo(Availability.AVAILABLE);
        providerCredentials = "synthetic:rotated-label";
        assertThat(service.history(user, LEARNER, 0).availability()).isEqualTo(Availability.UNAVAILABLE);
        verify(registry, never()).provider(any());
    }

    @Test void missingOrMultipleDatabaseProvidersAndAbsentServerRouteRemainUnavailable() {
        attemptRows = 0;
        for (int count : List.of(0, 2)) { providerRows = count; assertThat(service.history(user, LEARNER, 0).availability()).isEqualTo(Availability.UNAVAILABLE); }
        providerRows = 1; when(registry.configuredRoutes()).thenReturn(List.of());
        assertThat(service.history(user, LEARNER, 0).availability()).isEqualTo(Availability.UNAVAILABLE);
        verify(registry, never()).provider(any());
    }

    @Test void liveRouteRequiresSeparateLiveFlagWhileWorkerAndCreationFlagsRemainIndependent() {
        attemptRows = 0; route = route("LIVE", providerCredentials);
        assertThat(service.history(user, LEARNER, 0).availability()).isEqualTo(Availability.DISABLED);
        when(registry.liveEnabled()).thenReturn(true); assertThat(service.history(user, LEARNER, 0).availability()).isEqualTo(Availability.AVAILABLE);
        when(registry.workerEnabled()).thenReturn(false); assertThat(service.history(user, LEARNER, 0).availability()).isEqualTo(Availability.DISABLED);
    }

    @Test void initiationKeepsRealGuardianScopeRechecksAfterFinanceFenceAndUsesExactAtomicEnqueue() {
        var payment = service.initiate(user, LEARNER, request()); assertThat(payment.id()).isEqualTo(ATTEMPT);
        assertThat(events).containsSubsequence("CHILD", "FENCE", "CHILD", "EXISTING", "ROUTE", "PREPARE", "ACCOUNT", "ROUTE", "ENQUEUE");
        var terms = ArgumentCaptor.forClass(GatewayReservationService.PrepareRequest.class); verify(reservations).prepare(eq(user), eq(LEARNER), terms.capture());
        assertThat(terms.getValue().allocations()).containsExactly(new GatewayReservationService.ChargeSelection(CHARGE, new BigDecimal("750.00")));
        assertThat(terms.getValue().accountId()).isNull();
        assertThat(terms.getValue().accountSelectionMode()).isEqualTo("CURRENT_SESSION");
        verify(queue).enqueueAttempt(new GatewayWorkQueue.ProviderBinding(ORG, SCHOOL, CONFIG, "CASHFREE", "TEST", "synthetic_family_merchant", providerCredentials), ATTEMPT);
        verify(registry, never()).provider(any());
    }

    @Test void explicitNewAccountSelectionReachesReservationAsFrozenTermsWithoutChangingRouteOrEnqueueAuthority() {
        var legacy = request();
        var explicit = new InitiateRequest(legacy.clientRequestId(), legacy.allocations(), legacy.payerPhone(), ACCOUNT);
        assertThat(service.initiate(user, LEARNER, explicit).id()).isEqualTo(ATTEMPT);
        var terms = ArgumentCaptor.forClass(GatewayReservationService.PrepareRequest.class);
        verify(reservations).prepare(eq(user), eq(LEARNER), terms.capture());
        assertThat(terms.getValue().accountId()).isEqualTo(ACCOUNT);
        assertThat(terms.getValue().accountSelectionMode()).isEqualTo("EXPLICIT_ACCOUNT");
        verify(queue).enqueueAttempt(any(), eq(ATTEMPT));
        verify(registry, never()).provider(any());
    }

    @Test void acknowledgedExplicitRetryForwardsOriginalAccountTermsBeforeRouteChecksAndNeverReenqueues() {
        existingRequest = ATTEMPT; when(registry.creationEnabled()).thenReturn(false);
        var legacy = request();
        var explicit = new InitiateRequest(legacy.clientRequestId(), legacy.allocations(), legacy.payerPhone(), ACCOUNT);
        assertThat(service.initiate(user, LEARNER, explicit).id()).isEqualTo(ATTEMPT);
        var terms = ArgumentCaptor.forClass(GatewayReservationService.PrepareRequest.class);
        verify(reservations).replay(eq(user), eq(LEARNER), eq(ATTEMPT), terms.capture());
        assertThat(terms.getValue().accountId()).isEqualTo(ACCOUNT);
        assertThat(terms.getValue().accountSelectionMode()).isEqualTo("EXPLICIT_ACCOUNT");
        verify(reservations, never()).prepare(any(), any(), any());
        verifyNoInteractions(queue); verify(registry, never()).configuredRoutes();
    }

    @Test void disabledOrMismatchedRouteCannotReserveOrEnqueueFreshPayment() {
        when(registry.creationEnabled()).thenReturn(false); assertThatThrownBy(() -> service.initiate(user, LEARNER, request())).isInstanceOf(ApiException.class);
        when(registry.creationEnabled()).thenReturn(true); providerCredentials = "synthetic:changed-label";
        assertThatThrownBy(() -> service.initiate(user, LEARNER, request())).isInstanceOf(ApiException.class);
        verifyNoInteractions(reservations, queue, checkout);
    }

    @Test void relationshipRevokedDuringFinanceLockWaitCannotReachReservation() {
        doReturn(scope).doThrow(missing()).when(access).requireChild(user, LEARNER);
        assertThatThrownBy(() -> service.initiate(user, LEARNER, request())).isInstanceOf(ApiException.class);
        assertThat(events).contains("FENCE"); verifyNoInteractions(reservations, queue, checkout);
    }

    @Test void configurationChangedDuringPreparationCannotEnqueueAnotherMerchantOrCredentialVersion() {
        when(reservations.prepare(eq(user), eq(LEARNER), any())).thenAnswer(call -> {
            providerCredentials = "synthetic:changed-label";
            return new GatewayReservationService.PlannedAttempt(ATTEMPT, state, new BigDecimal("750.00"), "INR", LocalDate.parse("2026-10-14"), expires, 0);
        });
        assertThatThrownBy(() -> service.initiate(user, LEARNER, request())).isInstanceOf(ApiException.class);
        verify(queue, never()).enqueueAttempt(any(), any());
    }

    @Test void enqueueFailureEscapesSharedWritableBoundaryWithoutReturningPaymentOrSwallowingRollbackSignal() throws Exception {
        doThrow(new IllegalStateException("Synthetic enqueue unavailable")).when(queue).enqueueAttempt(any(), any());
        assertThatThrownBy(() -> service.initiate(user, LEARNER, request())).isInstanceOf(IllegalStateException.class);
        var annotation = FamilyGatewayPaymentService.class.getMethod("initiate", AuthenticatedUser.class, UUID.class, InitiateRequest.class).getAnnotation(Transactional.class);
        assertThat(annotation).isNotNull(); assertThat(annotation.readOnly()).isFalse(); verifyNoInteractions(checkout);
    }

    @Test void acknowledgedDuplicateReturnsOriginalTermsEvenAfterCreationDisabledWithoutReenqueueOrRebinding() {
        existingRequest = ATTEMPT; when(registry.creationEnabled()).thenReturn(false);
        var payment = service.initiate(user, LEARNER, request()); assertThat(payment.id()).isEqualTo(ATTEMPT);
        verify(reservations).replay(eq(user), eq(LEARNER), eq(ATTEMPT), any()); verify(reservations, never()).prepare(any(), any(), any()); verifyNoInteractions(queue);
        verify(registry, never()).configuredRoutes(); verify(registry, never()).provider(any());
    }

    @Test void changedStableRequestTermsPropagateOriginalReservationConflictAndCannotCreateAnotherAttempt() {
        existingRequest = ATTEMPT; doThrow(new ApiException(org.springframework.http.HttpStatus.CONFLICT, "ONLINE_REQUEST_REUSED", "Synthetic immutable terms changed"))
                .when(reservations).replay(eq(user), eq(LEARNER), eq(ATTEMPT), any());
        assertThatThrownBy(() -> service.initiate(user, LEARNER, request())).isInstanceOf(ApiException.class).hasMessage("Synthetic immutable terms changed");
        verifyNoInteractions(queue, checkout); verify(registry, never()).configuredRoutes();
    }

    @Test void invalidDuplicateEmptyOrOverprecisionSelectionsCannotReachReservation() {
        var selection = new ChargeSelection(CHARGE, new BigDecimal("750.00"));
        var requests = List.of(new InitiateRequest(UUID.randomUUID(), List.of(selection, selection), "0000000000"),
                new InitiateRequest(UUID.randomUUID(), List.of(), "0000000000"),
                new InitiateRequest(UUID.randomUUID(), List.of(new ChargeSelection(CHARGE, new BigDecimal("1.001"))), "0000000000"),
                new InitiateRequest(UUID.randomUUID(), List.of(selection), "not-a-phone"));
        for (var request : requests) assertThatThrownBy(() -> service.initiate(user, LEARNER, request)).isInstanceOf(ApiException.class);
        verifyNoInteractions(access, reservations, queue, checkout);
    }

    @Test void expiredIntentKeepsReservationVisibleAndNeverSuggestsPaidReleasedOrContinuation() {
        expires = NOW; var payment = service.payment(user, ATTEMPT);
        assertThat(payment.status()).isEqualTo(Status.EXPIRED); assertThat(payment.reservationActive()).isTrue(); assertThat(payment.canContinue()).isFalse();
        assertThat(payment.receipt()).isNull(); verifyNoInteractions(checkout, queue, reservations);
    }

    @Test void exactPostedReceiptOutranksStaleWorkReviewAndExpiryAndNeverOffersContinuation() {
        state = "POSTED"; receipt = true; reviewEvidence = true; expires = NOW.minusSeconds(10); activeReservation = false;
        var payment = service.payment(user, ATTEMPT); assertThat(payment.status()).isEqualTo(Status.PAID);
        assertThat(payment.receipt()).isEqualTo(new Receipt(PAYMENT, "SYNTHETIC/0001", LocalDate.parse("2026-10-14")));
        assertThat(payment.canContinue()).isFalse(); verifyNoInteractions(checkout);
    }

    @Test void purportedPostedStateWithoutExactReceiptRemainsReviewNotSuccess() {
        state = "POSTED"; var payment = service.payment(user, ATTEMPT);
        assertThat(payment.status()).isEqualTo(Status.REVIEW_REQUIRED); assertThat(payment.receipt()).isNull(); assertThat(payment.canContinue()).isFalse();
        verifyNoInteractions(checkout);
    }

    @Test void retainedCapturedOrUnpostedSuccessOutranksExpiryReleaseAndNeverEncouragesRepayment() {
        for (String stored : List.of("RESERVED", "RELEASED", "CAPTURED_REVIEW_REQUIRED")) {
            state = stored; reviewEvidence = true; expires = NOW;
            var payment = service.payment(user, ATTEMPT); assertThat(payment.status()).isEqualTo(Status.REVIEW_REQUIRED); assertThat(payment.canContinue()).isFalse();
        }
        verifyNoInteractions(checkout);
    }

    @Test void additionalHeldMoneyOrRefundRequiredEvidencePreservesOriginalReceiptButRequiresReview() {
        state = "POSTED"; receipt = true; reviewEvidence = true; additionalReviewEvidence = true;
        var payment = service.payment(user, ATTEMPT); assertThat(payment.status()).isEqualTo(Status.REVIEW_REQUIRED);
        assertThat(payment.receipt().paymentId()).isEqualTo(PAYMENT); assertThat(payment.canContinue()).isFalse();
    }

    @Test void unboundRelatedGetMoneyAndOrphanCapturesAreLookedUpThroughExactScopedImmutableCheckoutOrders() {
        reviewEvidence = true;
        var payment = service.payment(user, ATTEMPT);
        assertThat(payment.status()).isEqualTo(Status.REVIEW_REQUIRED); assertThat(payment.canContinue()).isFalse();
        var queries = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).query(queries.capture(), any(SqlParameterSource.class), ArgumentMatchers.<RowMapper<Object>>any());
        assertThat(queries.getAllValues().getFirst()).contains("capture.checkout_reference=attempt.checkout_reference::text",
                "capture.order_reference=ANY(related.order_references)", "observation.order_reference=ANY(related.order_references)",
                "related.provider_checkout_reference=attempt.checkout_reference", "related.attempt_reference=attempt.id",
                "capture.payment_provider_config_id=attempt.payment_provider_config_id", "observation.payment_provider_config_id=attempt.payment_provider_config_id",
                "capture.provider_environment=attempt.provider_environment", "observation.merchant_account_reference=attempt.merchant_account_reference");
        verifyNoInteractions(checkout);
    }

    @Test void sameReferenceContradictoryGetMoneyCannotHideBehindAnOriginalExactPostedReceipt() {
        state = "POSTED"; receipt = true; reviewEvidence = true; additionalReviewEvidence = true;
        assertThat(service.payment(user, ATTEMPT).status()).isEqualTo(Status.REVIEW_REQUIRED);
        var queries = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(2)).query(queries.capture(), any(SqlParameterSource.class), ArgumentMatchers.<RowMapper<Object>>any());
        assertThat(queries.getAllValues().getFirst()).contains("original.order_reference=observation.order_reference", "original.amount=observation.amount",
                "original.currency_code=observation.currency_code", "original.paid_at_exact IS NOT DISTINCT FROM observation.provider_paid_at_exact");
        verifyNoInteractions(checkout);
    }

    @Test void onlyActualReleasedStateDisplaysReleasedAndUnknownReadsRemainPending() {
        state = "RELEASED"; activeReservation = false; assertThat(service.payment(user, ATTEMPT).status()).isEqualTo(Status.RELEASED);
        state = "CHECKOUT_PENDING"; activeReservation = true; when(checkout.canContinue(user, LEARNER, ATTEMPT)).thenReturn(false);
        assertThat(service.payment(user, ATTEMPT).status()).isEqualTo(Status.PENDING);
    }

    @Test void coGuardianMayReadOwnChildStatusButNeverGetsInitiatorsPrivateCheckoutContinuation() {
        initiatedBy = UUID.randomUUID(); var payment = service.payment(user, ATTEMPT);
        assertThat(payment.initiatedByYou()).isFalse(); assertThat(payment.canContinue()).isFalse(); assertThat(payment.status()).isEqualTo(Status.PENDING);
        verifyNoInteractions(checkout);
    }

    @Test void zeroOrUnboundedFrozenSelectionsFailClosedInsteadOfInventingPayableTerms() {
        selectedRows = 0; assertThatThrownBy(() -> service.payment(user, ATTEMPT)).isInstanceOf(ApiException.class);
        selectedRows = 101; assertThatThrownBy(() -> service.payment(user, ATTEMPT)).isInstanceOf(ApiException.class);
        verifyNoInteractions(checkout);
    }

    @Test void continuationDelegatesActualAuthorisedSensitiveBoundaryWithoutFinancialWritesOrProviderLookup() {
        var url = URI.create("https://payments-test.cashfree.com/links/synthetic_family_continue");
        when(checkout.continueCheckout(user, LEARNER, ATTEMPT)).thenReturn(new FamilyGatewayCheckoutService.Continuation(ATTEMPT, url, expires));
        assertThat(service.continueCheckout(user, LEARNER, ATTEMPT)).isEqualTo(new Continuation(ATTEMPT, url, expires));
        verifyNoInteractions(access, reservations, queue, registry);
    }

    private ResultSet attemptRow() throws Exception {
        var row = mock(ResultSet.class);
        when(row.getObject("id", UUID.class)).thenReturn(ATTEMPT); when(row.getObject("organisation_id", UUID.class)).thenReturn(ORG);
        when(row.getObject("school_id", UUID.class)).thenReturn(SCHOOL); when(row.getObject("learner_id", UUID.class)).thenReturn(LEARNER);
        when(row.getObject("learner_fee_account_id", UUID.class)).thenReturn(ACCOUNT); when(row.getString("session_name")).thenReturn("Synthetic historical session");
        when(row.getBigDecimal("amount")).thenReturn(new BigDecimal("750.00")); when(row.getString("currency_code")).thenReturn("INR");
        when(row.getString("status")).thenReturn(state); when(row.getObject("created_at", OffsetDateTime.class)).thenReturn(NOW.minusSeconds(60).atOffset(ZoneOffset.UTC));
        when(row.getObject("expires_at", OffsetDateTime.class)).thenReturn(expires.atOffset(ZoneOffset.UTC));
        when(row.getObject("original_business_date", LocalDate.class)).thenReturn(LocalDate.parse("2026-10-14"));
        when(row.getObject("initiated_by_user_id", UUID.class)).thenReturn(initiatedBy); when(row.getObject("payment_provider_config_id", UUID.class)).thenReturn(CONFIG);
        when(row.getString("provider_code")).thenReturn("CASHFREE"); when(row.getString("provider_environment")).thenReturn(route.environment().name());
        when(row.getString("merchant_account_reference")).thenReturn("synthetic_family_merchant"); when(row.getBoolean("reservation_active")).thenReturn(activeReservation);
        when(row.getBoolean("review_evidence")).thenReturn(reviewEvidence); when(row.getBoolean("additional_review_evidence")).thenReturn(additionalReviewEvidence);
        when(row.getObject("receipt_id", UUID.class)).thenReturn(receipt ? PAYMENT : null); when(row.getString("receipt_number")).thenReturn("SYNTHETIC/0001");
        when(row.getObject("receipt_posting_on", LocalDate.class)).thenReturn(LocalDate.parse("2026-10-14")); return row;
    }
    private ResultSet providerRow() throws Exception {
        var row = mock(ResultSet.class); when(row.getObject("id", UUID.class)).thenReturn(CONFIG); when(row.getString("provider_code")).thenReturn("CASHFREE");
        when(row.getString("environment")).thenReturn(route.environment().name()); when(row.getString("merchant_account_reference")).thenReturn("synthetic_family_merchant");
        when(row.getString("credentials_secret_reference")).thenReturn(providerCredentials); return row;
    }
    private ResultSet chargeRow() throws Exception {
        var row = mock(ResultSet.class); when(row.getObject("learner_fee_charge_id", UUID.class)).thenReturn(CHARGE);
        when(row.getString("label_snapshot")).thenReturn("Synthetic frozen charge"); when(row.getObject("due_on", LocalDate.class)).thenReturn(LocalDate.parse("2026-10-01"));
        when(row.getBigDecimal("amount")).thenReturn(new BigDecimal("750.00")); return row;
    }
    private static GatewayProviderRegistry.Route route(String environment, String credentials) {
        return new GatewayProviderRegistry.Route("f".repeat(64), ORG, SCHOOL, CONFIG, "CASHFREE", CashfreePaymentProviderAdapter.Environment.valueOf(environment),
                "synthetic_family_merchant", credentials, "env:APP_FINANCE_GATEWAY_CASHFREE_" + environment + "_CLIENT_ID",
                "env:APP_FINANCE_GATEWAY_CASHFREE_" + environment + "_CLIENT_SECRET");
    }
    private static InitiateRequest request() {
        return new InitiateRequest(UUID.randomUUID(), List.of(new ChargeSelection(CHARGE, new BigDecimal("750.00"))), "0000000000");
    }
    private static ApiException missing() { return new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "SYNTHETIC_SCOPE_MISSING", "Synthetic family scope unavailable"); }
}
