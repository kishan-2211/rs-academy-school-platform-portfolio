package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.educore.api.config.ApplicationProperties;
import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Mocked admission guards only; no real PostgreSQL, callback, merchant or financial acceptance. */
class GatewayInboxDispatchTest {
    private static final Instant NOW = Instant.parse("2026-10-14T06:00:00Z");
    private static final String ROUTE = "a".repeat(64), ORIGIN = "https://synthetic-school.example.test";

    @AfterEach void clearOnlyTestMarkers() {
        TransactionSynchronizationManager.clear();
        assertThat(TenantRequestContext.current()).isEmpty();
    }

    @Test void creationDoesNotEnableInboxReadsWithoutWorkerAndLiveRequiresIndependentOptIn() {
        for (var f : List.of(new Fixture(true, false, false, "TEST"), new Fixture(false, true, false, "LIVE"))) {
            try (var ignored = background(f)) { rejected(() -> f.inbox.loadForReadRecovery(ROUTE, f.claim)); rejected(() -> f.inbox.loadHistorical(ROUTE, f.claim)); }
            verifyNoInteractions(f.jdbc, f.factory, f.readiness);
        }
    }

    @Test void absentHumanForeignOrganisationMissingAndReadOnlyTransactionsDenyBeforeDatabaseAccess() {
        var f = new Fixture();
        rejected(() -> f.inbox.loadForReadRecovery(ROUTE, f.claim));
        try (var ignored = TenantRequestContext.open(UUID.randomUUID(), f.org)) {
            writable(); rejected(() -> f.inbox.loadHistorical(ROUTE, f.claim));
        }
        try (var ignored = TenantRequestContext.open(null, UUID.randomUUID())) { rejected(() -> f.inbox.loadForReadRecovery(ROUTE, f.claim)); }
        try (var ignored = TenantRequestContext.open(null, f.org)) {
            TransactionSynchronizationManager.clear(); rejected(() -> f.inbox.loadHistorical(ROUTE, f.claim));
            writable(); TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            rejected(() -> f.inbox.loadForReadRecovery(ROUTE, f.claim));
        }
        verifyNoInteractions(f.jdbc, f.factory, f.readiness);
    }

    @Test void onlyExactTrustedRouteAndWebhookClaimCanReachPersistedInbox() {
        var f = new Fixture();
        try (var ignored = background(f)) {
            rejected(() -> f.inbox.loadForReadRecovery("unknown", f.claim));
            rejected(() -> f.inbox.loadHistorical(ROUTE, null));
            rejected(() -> f.inbox.loadForReadRecovery(ROUTE, new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.ATTEMPT,
                    f.inboxId, f.binding, f.claim.token(), NOW, NOW.plusSeconds(90), 1, false)));
            for (var binding : List.of(
                    new GatewayWorkQueue.ProviderBinding(UUID.randomUUID(), f.school, f.provider, "CASHFREE", "TEST", "synthetic_merchant", "synthetic:credentials"),
                    new GatewayWorkQueue.ProviderBinding(f.org, UUID.randomUUID(), f.provider, "CASHFREE", "TEST", "synthetic_merchant", "synthetic:credentials"),
                    new GatewayWorkQueue.ProviderBinding(f.org, f.school, UUID.randomUUID(), "CASHFREE", "TEST", "synthetic_merchant", "synthetic:credentials"),
                    new GatewayWorkQueue.ProviderBinding(f.org, f.school, f.provider, "OTHER", "TEST", "synthetic_merchant", "synthetic:credentials"),
                    new GatewayWorkQueue.ProviderBinding(f.org, f.school, f.provider, "CASHFREE", "LIVE", "synthetic_merchant", "synthetic:credentials"),
                    new GatewayWorkQueue.ProviderBinding(f.org, f.school, f.provider, "CASHFREE", "TEST", "synthetic_other", "synthetic:credentials"),
                    new GatewayWorkQueue.ProviderBinding(f.org, f.school, f.provider, "CASHFREE", "TEST", "synthetic_merchant", "synthetic:rotated"))) {
                rejected(() -> f.inbox.loadHistorical(ROUTE, f.claim(binding, f.claim.token(), NOW, NOW.plusSeconds(90), 1)));
            }
        }
        verifyNoInteractions(f.jdbc, f.factory, f.readiness);
    }

    @Test void actualDatabaseScopeMustMatchThreadScopeWithoutCredentialResolution() throws Exception {
        var f = new Fixture(); f.installRows();
        when(f.jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Boolean.class))).thenReturn(false);
        try (var ignored = background(f)) { rejected(() -> f.inbox.loadForReadRecovery(ROUTE, f.claim)); }
        assertThat(f.queries).isEmpty(); assertNoExternalWrites(f);
    }

    @Test void currentPaymentLoadsOnlyPersistedImmutableRoutingAndOriginalSameIdTermsWithStrictLockAndLeasePredicates() throws Exception {
        var f = new Fixture(); f.installRows();
        try (var ignored = background(f)) {
            var value = f.inbox.loadForReadRecovery(ROUTE, f.claim); var attempt = value.attempt().orElseThrow();
            assertThat(value.eventType()).isEqualTo("PAYMENT_SUCCESS_WEBHOOK"); assertThat(value.orderReference()).isEqualTo("synthetic_order");
            assertThat(value.paymentReference()).isEqualTo("synthetic_payment"); assertThat(value.refundReference()).isNull();
            assertThat(value.financiallyEnabled()).isTrue(); assertThat(value.checkedAt()).isEqualTo(NOW.plusSeconds(1));
            assertThat(attempt.attemptId()).isEqualTo(f.attemptId); assertThat(attempt.binding()).isEqualTo(f.binding);
            assertThat(attempt.leaseUntil()).isEqualTo(f.claim.leaseUntil()); assertThat(attempt.createPreviouslyDispatched()).isTrue();
            assertThat(value.toString()).doesNotContain(f.attemptId.toString(), f.inboxId.toString(), "synthetic_order", "synthetic_payment", "synthetic:credentials", ORIGIN);
        }
        String inboxSql = f.queries.getFirst();
        assertThat(inboxSql).contains("inbox.status='LEASED'", "inbox.lease_token=:leaseToken", "inbox.lease_started_at=:leaseStartedAt",
                "inbox.lease_until=:leaseUntil", "inbox.attempt_count=:attemptNumber", "FOR SHARE OF inbox,provider",
                "provider.credentials_secret_reference=inbox.credentials_secret_reference");
        assertThat(String.join("\n", f.queries)).contains("FOR SHARE OF work,attempt,provider,account")
                .doesNotContain("FOR SHARE OF organisation", "FOR SHARE OF school", "payer_phone", "client_secret", "fee_payments", "INSERT", "UPDATE", "DELETE");
        assertThat(f.queryParameters.getFirst().getValue("inboxId")).isEqualTo(f.inboxId);
        assertNoExternalWrites(f);
    }

    @Test void currentRejectsEveryForgedClaimFieldEvenSubMicrosecondFieldsWhoseSqlProjectionMatches() throws Exception {
        var f = new Fixture(); f.installRows();
        try (var ignored = background(f)) {
            for (var claim : List.of(f.claim(f.binding, UUID.randomUUID(), NOW, NOW.plusSeconds(90), 1),
                    f.claim(f.binding, f.claim.token(), NOW.plusNanos(1), NOW.plusSeconds(90), 1),
                    f.claim(f.binding, f.claim.token(), NOW, NOW.plusSeconds(90).plusNanos(1), 1),
                    f.claim(f.binding, f.claim.token(), NOW, NOW.plusSeconds(90), 2))) {
                rejected(() -> f.inbox.loadForReadRecovery(ROUTE, claim));
            }
            rejected(() -> f.inbox.loadForReadRecovery(ROUTE, new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.WEBHOOK,
                    UUID.randomUUID(), f.binding, f.claim.token(), NOW, NOW.plusSeconds(90), 1, false)));
        }
        assertNoExternalWrites(f);
    }

    @Test void currentUsesActualDatabaseClockAfterAllLocksAndRefusesExpiredFutureOrUnavailableTime() throws Exception {
        var f = new Fixture(); f.installRows();
        try (var ignored = background(f)) {
            for (Instant time : List.of(f.claim.leaseUntil(), f.claim.leaseUntil().plusSeconds(1), NOW.minusNanos(1))) {
                f.checkedAt = time; rejected(() -> f.inbox.loadForReadRecovery(ROUTE, f.claim));
            }
            f.checkedAt = null; rejected(() -> f.inbox.loadForReadRecovery(ROUTE, f.claim));
        }
        assertNoExternalWrites(f);
    }

    @Test void missingInboxChangedPersistedFrozenBindingAndUnclaimedRowsNeverFallBackToOtherMetadata() throws Exception {
        var f = new Fixture(); f.installRows();
        try (var ignored = background(f)) {
            f.inboxPresent = false; rejected(() -> f.inbox.loadHistorical(ROUTE, f.claim)); f.inboxPresent = true;
            f.inboxValues.put("credentials_secret_reference", "synthetic:rotated"); rejected(() -> f.inbox.loadHistorical(ROUTE, f.claim));
            f.inboxValues.put("credentials_secret_reference", "synthetic:credentials");
            f.inboxValues.put("school_id", UUID.randomUUID()); rejected(() -> f.inbox.loadForReadRecovery(ROUTE, f.claim));
            f.inboxValues.put("school_id", f.school); f.inboxValues.put("attempt_count", 0);
            rejected(() -> f.inbox.loadHistorical(ROUTE, f.claim));
        }
        assertNoExternalWrites(f);
    }

    @Test void historicalCompletedInboxRetainsOriginalBindingWithoutInventingFormerTokenOrReopeningWork() throws Exception {
        var f = new Fixture(); f.installRows();
        f.inboxValues.put("inbox_status", "COMPLETE"); f.inboxValues.put("lease_token", null);
        f.inboxValues.put("lease_started_at", null); f.inboxValues.put("lease_until", null);
        f.attemptValues.put("attempt_status", "POSTED");
        f.checkedAt = NOW.plusSeconds(120);
        try (var ignored = background(f)) {
            var historical = f.inbox.loadHistorical(ROUTE, f.claim);
            assertThat(historical.attempt().orElseThrow().leaseUntil()).isEqualTo(f.claim.leaseUntil());
            assertThat(historical.attempt().orElseThrow().createPreviouslyDispatched()).isTrue();
            assertThat(historical.attempt().orElseThrow().attemptStatus()).isEqualTo("POSTED");
            assertThat(historical.financiallyEnabled()).isFalse();
            rejected(() -> f.inbox.loadForReadRecovery(ROUTE, f.claim));
        }
        assertThat(f.queries.getFirst()).doesNotContain("inbox.status='LEASED'", "inbox.lease_token=:leaseToken", "inbox.attempt_count=:attemptNumber");
        assertNoExternalWrites(f);
    }

    @Test void historicalOldClaimCanRetainFactsAcrossNewLeaseAndClosedExpiredAccountWithoutFamilyGrantQueries() throws Exception {
        var f = new Fixture(); f.installRows(); f.inboxValues.put("attempt_count", 2);
        f.inboxValues.put("lease_token", UUID.randomUUID()); f.inboxValues.put("lease_started_at", at(NOW.plusSeconds(100)));
        f.inboxValues.put("lease_until", at(NOW.plusSeconds(190))); f.checkedAt = NOW.plusSeconds(110);
        f.attemptValues.put("account_status", "CLOSED"); f.attemptValues.put("attempt_status", "CAPTURED_REVIEW_REQUIRED");
        f.attemptValues.put("created_at", at(NOW.minusSeconds(3600))); f.attemptValues.put("expires_at", at(NOW.minusSeconds(1800)));
        try (var ignored = background(f)) {
            var historical = f.inbox.loadHistorical(ROUTE, f.claim); var attempt = historical.attempt().orElseThrow();
            assertThat(attempt.leaseUntil()).isEqualTo(NOW.plusSeconds(190));
            assertThat(attempt.accountStatus()).isEqualTo("CLOSED"); assertThat(attempt.expiresAt()).isBefore(NOW);
            assertThat(historical.financiallyEnabled()).isFalse();
            rejected(() -> f.inbox.loadForReadRecovery(ROUTE, f.claim));
        }
        assertThat(String.join("\n", f.queries)).doesNotContain("guardians", "memberships", "student_enrolments", "account.status='ACTIVE'", "attempt.expires_at>");
        assertNoExternalWrites(f);
    }

    @Test void historicalEvenWhileExactLeaseIsCurrentCannotEnableFinancialPosting() throws Exception {
        var f = new Fixture(); f.installRows();
        try (var ignored = background(f)) {
            assertThat(f.inbox.loadHistorical(ROUTE, f.claim).attempt()).isPresent();
            assertThat(f.inbox.loadHistorical(ROUTE, f.claim).financiallyEnabled()).isFalse();
            assertThat(f.inbox.loadForReadRecovery(ROUTE, f.claim).financiallyEnabled()).isTrue();
        }
        assertNoExternalWrites(f);
    }

    @Test void backgroundDatabaseScopeThenFinanceFenceThenMutableRowsThenFreshClockAreObservedInThatOrder() throws Exception {
        var f = new Fixture(); f.installRows();
        try (var ignored = background(f)) { f.inbox.loadForReadRecovery(ROUTE, f.claim); }
        assertThat(f.executed).hasSize(5);
        assertThat(f.executed.get(0)).contains("current_app_organisation_id()=:organisationId", "current_app_user_id() IS NULL");
        assertThat(f.executed.get(1)).contains("pg_advisory_xact_lock");
        assertThat(f.executed.get(2)).contains("FROM fee_gateway_webhook_inbox");
        assertThat(f.executed.get(3)).contains("FROM fee_gateway_attempt_work");
        assertThat(f.executed.get(4)).isEqualTo("SELECT clock_timestamp()");
        assertNoExternalWrites(f);
    }

    @Test void sqlFailuresAndMissingImmutableAttemptRowsRemainPrivateAndNeverGuessAssociation() throws Exception {
        var f = new Fixture(); f.installRows();
        doThrow(new IllegalStateException("synthetic/private-order provider payload", new IllegalArgumentException("synthetic/private-payment")))
                .when(f.jdbc).query(anyString(), any(MapSqlParameterSource.class), any(ResultSetExtractor.class));
        try (var ignored = background(f)) {
            rejected(() -> f.inbox.loadForReadRecovery(ROUTE, f.claim));
            rejected(() -> f.inbox.loadHistorical(ROUTE, f.claim));
        }
        assertNoExternalWrites(f);
    }

    @Test void missingOriginalCreateMarkerCanBeReadButIsNeverManufacturedByInboxAdmission() throws Exception {
        var f = new Fixture(); f.installRows(); f.attemptValues.put("create_dispatched", false);
        try (var ignored = background(f)) {
            var current = f.inbox.loadForReadRecovery(ROUTE, f.claim);
            assertThat(current.attempt().orElseThrow().createPreviouslyDispatched()).isFalse();
            assertThat(current.financiallyEnabled()).isFalse();
            assertThat(f.inbox.loadHistorical(ROUTE, f.claim).attempt().orElseThrow().createPreviouslyDispatched()).isFalse();
            assertThat(f.inbox.loadHistorical(ROUTE, f.claim).financiallyEnabled()).isFalse();
            rejected(() -> new GatewayInboxDispatch.Admission(current.attempt(), current.eventType(), current.orderReference(),
                    current.paymentReference(), current.refundReference(), true, current.checkedAt()));
        }
        assertNoExternalWrites(f);
    }

    @Test void historicalRequiresPlausibleClaimStartAndCountAndNeverAcceptsFutureOrCorruptIntake() throws Exception {
        var f = new Fixture(); f.installRows();
        try (var ignored = background(f)) {
            rejected(() -> f.inbox.loadHistorical(ROUTE, f.claim(f.binding, UUID.randomUUID(), NOW.minusSeconds(11), NOW.plusSeconds(79), 1)));
            rejected(() -> f.inbox.loadHistorical(ROUTE, f.claim(f.binding, UUID.randomUUID(), NOW, NOW.plusSeconds(90), 2)));
            f.checkedAt = NOW.minusSeconds(1); rejected(() -> f.inbox.loadHistorical(ROUTE, f.claim));
            f.checkedAt = NOW.plusSeconds(1); f.inboxValues.put("received_at", null); rejected(() -> f.inbox.loadHistorical(ROUTE, f.claim));
        }
        assertNoExternalWrites(f);
    }

    @Test void checkoutAbsentOnlyOneDistinctPriorScopedOrderAssociationCanLoadAnAttempt() throws Exception {
        var f = new Fixture(); f.installRows(); f.inboxValues.put("checkout_reference", null); f.associations = List.of(f.attemptId);
        try (var ignored = background(f)) {
            assertThat(f.inbox.loadForReadRecovery(ROUTE, f.claim).attempt().orElseThrow().attemptId()).isEqualTo(f.attemptId);
        }
        String sql = f.queries.get(1);
        assertThat(sql).contains("SELECT DISTINCT observation.attempt_id", "LIMIT 2", ":orderReference=ANY(observation.order_references)",
                "attempt.checkout_reference=observation.provider_checkout_reference", "attempt.id=observation.attempt_reference",
                "work.credentials_secret_reference=:credentialsReference", "work.school_id=:schoolId", "work.merchant_account_reference=:merchant");
        assertThat(sql).doesNotContain("amount=", "phone", "guardian"); assertNoExternalWrites(f);
    }

    @Test void unknownOrAmbiguousPriorOrderAssociationRemainsOrphanAndCannotEnableFinances() throws Exception {
        var f = new Fixture(); f.installRows(); f.inboxValues.put("checkout_reference", null);
        try (var ignored = background(f)) {
            for (var candidates : List.<List<UUID>>of(List.of(), List.of(f.attemptId, UUID.randomUUID()))) {
                f.associations = candidates;
                var admission = f.inbox.loadForReadRecovery(ROUTE, f.claim);
                assertThat(admission.attempt()).isEmpty(); assertThat(admission.financiallyEnabled()).isFalse();
                assertThat(admission.orderReference()).isEqualTo("synthetic_order");
            }
        }
        assertNoExternalWrites(f);
    }

    @Test void explicitCheckoutMissingItsOriginalWorkDoesNotUseAnUnrelatedPriorOrderAsFallback() throws Exception {
        var f = new Fixture(); f.installRows(); f.attemptPresent = false; f.associations = List.of(UUID.randomUUID());
        try (var ignored = background(f)) {
            var admission = f.inbox.loadForReadRecovery(ROUTE, f.claim);
            assertThat(admission.attempt()).isEmpty(); assertThat(admission.financiallyEnabled()).isFalse();
        }
        assertThat(String.join("\n", f.queries)).doesNotContain("SELECT DISTINCT observation.attempt_id"); assertNoExternalWrites(f);
    }

    @Test void refundsUsePersistedMinimalRoutingOnlyAndNeverAcquireFinancialAttemptAssociation() throws Exception {
        var f = new Fixture(); f.installRows(); f.inboxValues.put("checkout_reference", null); f.associations = List.of(f.attemptId);
        try (var ignored = background(f)) {
            for (String event : List.of("REFUND_STATUS_WEBHOOK", "AUTO_REFUND_STATUS_WEBHOOK")) {
                f.inboxValues.put("event_type", event); f.inboxValues.put("refund_reference", "synthetic_refund");
                var value = f.inbox.loadForReadRecovery(ROUTE, f.claim);
                assertThat(value.attempt()).isEmpty(); assertThat(value.financiallyEnabled()).isFalse();
                assertThat(value.refundReference()).isEqualTo("synthetic_refund");
            }
            f.inboxValues.put("order_reference", null); f.inboxValues.put("refund_reference", null);
            assertThat(f.inbox.loadHistorical(ROUTE, f.claim).eventType()).isEqualTo("AUTO_REFUND_STATUS_WEBHOOK");
            f.inboxValues.put("checkout_reference", f.attemptId); rejected(() -> f.inbox.loadHistorical(ROUTE, f.claim));
        }
        assertThat(String.join("\n", f.queries)).doesNotContain("fee_gateway_attempt_work", "fee_gateway_checkout_observations"); assertNoExternalWrites(f);
    }

    @Test void exactInactiveProviderSchoolOrOrganisationKeepsReadEvidenceButCannotEnableFinancialPosting() throws Exception {
        var f = new Fixture(); f.installRows();
        try (var ignored = background(f)) {
            for (String field : List.of("provider_status", "school_status", "organisation_status")) {
                f.inboxValues.put(field, "INACTIVE"); var value = f.inbox.loadForReadRecovery(ROUTE, f.claim);
                assertThat(value.attempt()).isPresent(); assertThat(value.financiallyEnabled()).isFalse();
                f.inboxValues.put(field, "ACTIVE");
            }
        }
        assertThat(String.join("\n", f.queries)).doesNotContain("provider.status='ACTIVE'", "school.status='ACTIVE'", "organisation.status='ACTIVE'");
        assertNoExternalWrites(f);
    }

    @Test void malformedPersistedRoutingIsRejectedWithoutEchoingProviderReferencesOrParserCauses() throws Exception {
        var f = new Fixture(); f.installRows(); var original = new HashMap<>(f.inboxValues);
        try (var ignored = background(f)) {
            for (var mutation : Map.<String,Object>of("event_type", "SYNTHETIC_UNSUPPORTED_PRIVATE_EVENT", "order_reference", "synthetic/private-order",
                    "payment_reference", "synthetic/private-payment", "refund_reference", "synthetic_refund").entrySet()) {
                f.inboxValues.clear(); f.inboxValues.putAll(original); f.inboxValues.put(mutation.getKey(), mutation.getValue());
                rejected(() -> f.inbox.loadForReadRecovery(ROUTE, f.claim));
            }
        }
        assertNoExternalWrites(f);
    }

    @Test void originalAttemptRecordValidationAndCanonicalReturnAddressRemainMandatory() throws Exception {
        var f = new Fixture(); f.installRows(); var original = new HashMap<>(f.attemptValues);
        try (var ignored = background(f)) {
            for (var mutation : Map.<String,Object>of("checkout_reference", UUID.randomUUID(), "amount", new BigDecimal("500.001"),
                    "currency_code", "USD", "attempt_status", "UNKNOWN", "account_status", "DELETED", "school_time_zone", "Invalid/Zone",
                    "original_business_date", LocalDate.of(2026, 10, 13), "expires_at", at(NOW.minusSeconds(120)),
                    "return_uri", ORIGIN + "/family/payments/" + f.attemptId + "?synthetic-private=1").entrySet()) {
                f.attemptValues.clear(); f.attemptValues.putAll(original); f.attemptValues.put(mutation.getKey(), mutation.getValue());
                rejected(() -> f.inbox.loadForReadRecovery(ROUTE, f.claim));
            }
        }
        assertNoExternalWrites(f);
    }

    @Test void admissionRecordCannotClaimFinancialAuthorityForOrphansRefundsOrMalformedPrivateReferences() {
        rejected(() -> new GatewayInboxDispatch.Admission(Optional.empty(), "PAYMENT_SUCCESS_WEBHOOK", "synthetic_order", "synthetic_payment", null, true, NOW));
        rejected(() -> new GatewayInboxDispatch.Admission(Optional.empty(), "REFUND_STATUS_WEBHOOK", "synthetic_order", "synthetic_payment", "synthetic_refund", true, NOW));
        rejected(() -> new GatewayInboxDispatch.Admission(Optional.empty(), "PAYMENT_SUCCESS_WEBHOOK", null, "synthetic_payment", null, false, NOW));
        rejected(() -> new GatewayInboxDispatch.Admission(Optional.empty(), "REFUND_STATUS_WEBHOOK", "synthetic_order", "synthetic_payment", null, false, NOW));
        rejected(() -> new GatewayInboxDispatch.Admission(Optional.empty(), "AUTO_REFUND_STATUS_WEBHOOK", null, "synthetic/private-payment", null, false, NOW));
        assertThat(new GatewayInboxDispatch.Admission(Optional.empty(), "AUTO_REFUND_STATUS_WEBHOOK", null, "synthetic_payment", null, false, NOW).toString())
                .doesNotContain("synthetic_payment");
    }

    private static void rejected(java.util.concurrent.Callable<?> action) {
        assertThatThrownBy(action::call).isInstanceOf(IllegalStateException.class).hasNoCause()
                .hasMessageNotContaining("synthetic/private").hasMessageNotContaining("synthetic-private=").hasMessageNotContaining("SYNTHETIC_UNSUPPORTED_PRIVATE_EVENT");
    }
    private static TenantRequestContext.Scope background(Fixture f) { writable(); return TenantRequestContext.open(null, f.org); }
    private static void writable() {
        TransactionSynchronizationManager.setActualTransactionActive(true); TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }
    private static OffsetDateTime at(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
    private static void assertNoExternalWrites(Fixture f) {
        verify(f.jdbc, never()).update(anyString(), any(MapSqlParameterSource.class)); verifyNoInteractions(f.factory, f.readiness);
    }

    private static final class Fixture {
        final UUID org = UUID.randomUUID(), school = UUID.randomUUID(), provider = UUID.randomUUID(), inboxId = UUID.randomUUID();
        final UUID attemptId = UUID.randomUUID(), accountId = UUID.randomUUID();
        final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        final GatewayProviderRegistry.ProviderFactory factory = mock(GatewayProviderRegistry.ProviderFactory.class);
        final GatewayCheckoutReadiness readiness = mock(GatewayCheckoutReadiness.class);
        final GatewayWorkQueue.ProviderBinding binding;
        final GatewayWorkQueue.Claim claim;
        final GatewayProviderRegistry registry;
        final GatewayInboxDispatch inbox;
        final Map<String,Object> inboxValues = new HashMap<>(), attemptValues = new HashMap<>();
        final List<String> queries = new ArrayList<>();
        final List<String> executed = new ArrayList<>();
        final List<MapSqlParameterSource> queryParameters = new ArrayList<>();
        List<UUID> associations = List.of();
        boolean inboxPresent = true, attemptPresent = true;
        Instant checkedAt = NOW.plusSeconds(1);

        Fixture() { this(false, true, false, "TEST"); }
        Fixture(boolean creation, boolean worker, boolean live, String environment) {
            binding = new GatewayWorkQueue.ProviderBinding(org, school, provider, "CASHFREE", environment, "synthetic_merchant", "synthetic:credentials");
            claim = claim(binding, UUID.randomUUID(), NOW, NOW.plusSeconds(90), 1);
            var route = new GatewayProviderRegistry.Route(ROUTE, org, school, provider, "CASHFREE", CashfreePaymentProviderAdapter.Environment.valueOf(environment),
                    "synthetic_merchant", "synthetic:credentials", "env:APP_FINANCE_GATEWAY_CASHFREE_" + environment + "_CLIENT_ID",
                    "env:APP_FINANCE_GATEWAY_CASHFREE_" + environment + "_CLIENT_SECRET");
            registry = new GatewayProviderRegistry(new GatewayProviderRegistry.Settings(creation, worker, live, false, List.of(route)), URI.create(ORIGIN),
                    Clock.fixed(NOW, ZoneOffset.UTC), name -> { throw new AssertionError("Inbox admission must never resolve credentials"); }, factory);
            var application = new ApplicationProperties(ORIGIN, List.of(), Optional.empty(), Optional.empty(), null, null);
            inbox = new GatewayInboxDispatch(jdbc, registry, new GatewayAttemptDispatch(jdbc, registry, application, readiness));
        }
        GatewayWorkQueue.Claim claim(GatewayWorkQueue.ProviderBinding source, UUID token, Instant start, Instant until, int count) {
            return new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.WEBHOOK, inboxId, source, token, start, until, count, false);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void installRows() throws Exception {
            inboxValues.putAll(Map.of("inbox_id", inboxId, "organisation_id", org, "school_id", school, "payment_provider_config_id", provider,
                    "provider_code", binding.providerCode(), "provider_environment", binding.environment(), "merchant_account_reference", binding.merchantReference(),
                    "credentials_secret_reference", binding.credentialsReference(), "event_type", "PAYMENT_SUCCESS_WEBHOOK", "checkout_reference", attemptId));
            inboxValues.put("order_reference", "synthetic_order"); inboxValues.put("payment_reference", "synthetic_payment"); inboxValues.put("refund_reference", null);
            inboxValues.put("received_at", at(NOW.minusSeconds(10))); inboxValues.put("inbox_status", "LEASED"); inboxValues.put("lease_token", claim.token());
            inboxValues.put("lease_started_at", at(claim.startedAt())); inboxValues.put("lease_until", at(claim.leaseUntil())); inboxValues.put("attempt_count", 1);
            inboxValues.put("provider_status", "ACTIVE"); inboxValues.put("school_status", "ACTIVE"); inboxValues.put("organisation_status", "ACTIVE");
            attemptValues.putAll(Map.of("id", attemptId, "learner_fee_account_id", accountId, "checkout_reference", attemptId, "amount", new BigDecimal("500.00"),
                    "currency_code", "INR", "attempt_status", "RESERVED", "account_status", "ACTIVE", "school_time_zone", "Asia/Kolkata",
                    "original_business_date", LocalDate.of(2026, 10, 14), "created_at", at(NOW.minusSeconds(60))));
            attemptValues.put("expires_at", at(NOW.plusSeconds(1800))); attemptValues.put("return_uri", ORIGIN + "/family/payments/" + attemptId);
            attemptValues.put("row_version", 0L); attemptValues.put("create_dispatched", true);
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Boolean.class))).thenAnswer(invocation -> {
                executed.add(invocation.getArgument(0)); return true;
            });
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenAnswer(invocation -> {
                String sql = invocation.getArgument(0); executed.add(sql); return "SELECT clock_timestamp()".equals(sql) ? checkedAt : true;
            });
            when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(ResultSetExtractor.class))).thenAnswer(invocation -> {
                String sql = invocation.getArgument(0); queries.add(sql); executed.add(sql); queryParameters.add(invocation.getArgument(1));
                boolean isInbox = sql.contains("AS inbox_id");
                return ((ResultSetExtractor) invocation.getArgument(2)).extractData(row(isInbox ? inboxValues : attemptValues, isInbox ? inboxPresent : attemptPresent));
            });
            when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenAnswer(invocation -> {
                String sql = invocation.getArgument(0); queries.add(sql); executed.add(sql); queryParameters.add(invocation.getArgument(1));
                var mapped = new ArrayList<UUID>();
                for (UUID candidate : associations) {
                    ResultSet row = mock(ResultSet.class); when(row.getObject(1, UUID.class)).thenReturn(candidate);
                    mapped.add((UUID) ((RowMapper) invocation.getArgument(2)).mapRow(row, mapped.size()));
                }
                return mapped;
            });
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static ResultSet row(Map<String,Object> values, boolean present) throws Exception {
            ResultSet row = mock(ResultSet.class); when(row.next()).thenReturn(present, false);
            when(row.getObject(anyString(), any(Class.class))).thenAnswer(invocation -> values.get(invocation.getArgument(0)));
            when(row.getString(anyString())).thenAnswer(invocation -> values.get(invocation.getArgument(0)));
            when(row.getInt(anyString())).thenAnswer(invocation -> ((Number) values.get(invocation.getArgument(0))).intValue());
            when(row.getLong(anyString())).thenAnswer(invocation -> ((Number) values.get(invocation.getArgument(0))).longValue());
            when(row.getBigDecimal(anyString())).thenAnswer(invocation -> values.get(invocation.getArgument(0)));
            when(row.getBoolean(anyString())).thenAnswer(invocation -> values.get(invocation.getArgument(0)));
            return row;
        }
    }
}
