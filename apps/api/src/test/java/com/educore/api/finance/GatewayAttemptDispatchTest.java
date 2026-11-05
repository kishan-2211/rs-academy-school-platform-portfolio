package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.educore.api.config.ApplicationProperties;
import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
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

/** Focused mocked persistence guards, not actual PostgreSQL/provider acceptance. */
class GatewayAttemptDispatchTest {
    private static final Instant NOW = Instant.parse("2026-10-11T06:00:00Z");
    private static final String ROUTE_KEY = "79f819428fb6492996a750b3e43b30e20ffb9408ad1569be8d19f5a307ca2f86";
    private static final String ORIGIN = "https://synthetic-school.example.test";

    @AfterEach void clearOnlyTestTransactionMarkers() { TransactionSynchronizationManager.clear(); }

    @Test void creationFlagDoesNotEnableRecoveryWithoutTheIndependentWorkerFlag() {
        var f = new Fixture(true, false, false, "TEST");
        try (var ignored = background(f)) {
            assertThatThrownBy(() -> f.dispatch.loadForReadRecovery(ROUTE_KEY, f.claim)).isInstanceOf(IllegalStateException.class);
        }
        verifyNoInteractions(f.jdbc, f.factory);
    }

    @Test void absentHumanWrongOrganisationAndReadOnlyTransactionsFailBeforeDatabaseAccess() {
        var f = fixture();
        assertThatThrownBy(() -> f.dispatch.loadForReadRecovery(ROUTE_KEY, f.claim)).isInstanceOf(IllegalStateException.class);
        try (var ignored = TenantRequestContext.open(UUID.randomUUID(), f.org)) {
            writable();
            assertThatThrownBy(() -> f.dispatch.loadForReadRecovery(ROUTE_KEY, f.claim)).isInstanceOf(IllegalStateException.class);
        }
        try (var ignored = TenantRequestContext.open(null, UUID.randomUUID())) {
            assertThatThrownBy(() -> f.dispatch.loadForReadRecovery(ROUTE_KEY, f.claim)).isInstanceOf(IllegalStateException.class);
        }
        try (var ignored = TenantRequestContext.open(null, f.org)) {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            assertThatThrownBy(() -> f.dispatch.loadForReadRecovery(ROUTE_KEY, f.claim)).isInstanceOf(IllegalStateException.class);
            writable();
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            assertThatThrownBy(() -> f.dispatch.loadForReadRecovery(ROUTE_KEY, f.claim)).isInstanceOf(IllegalStateException.class);
        }
        verifyNoInteractions(f.jdbc, f.factory);
    }

    @Test void unknownRouteOrChangedFrozenMerchantSchoolAndCredentialsNeverReachDatabaseOrProvider() {
        var f = fixture();
        try (var ignored = background(f)) {
            assertThatThrownBy(() -> f.dispatch.loadForReadRecovery("unknown-route", f.claim)).isInstanceOf(IllegalStateException.class);
            for (var changed : List.of(
                    new GatewayWorkQueue.ProviderBinding(f.org, UUID.randomUUID(), f.provider, "CASHFREE", "TEST", "synthetic_merchant", "synthetic:credentials"),
                    new GatewayWorkQueue.ProviderBinding(f.org, f.school, f.provider, "CASHFREE", "TEST", "changed_merchant", "synthetic:credentials"),
                    new GatewayWorkQueue.ProviderBinding(f.org, f.school, f.provider, "CASHFREE", "TEST", "synthetic_merchant", "synthetic:changed"))) {
                var claim = claim(f.attempt, changed, GatewayWorkQueue.WorkKind.ATTEMPT, false);
                assertThatThrownBy(() -> f.dispatch.loadForReadRecovery(ROUTE_KEY, claim)).isInstanceOf(IllegalStateException.class);
            }
        }
        verifyNoInteractions(f.jdbc, f.factory);
    }

    @Test void webhookClaimAndLiveRouteWithoutLiveOptInCannotBecomeAttemptReadPermission() {
        var f = fixture();
        try (var ignored = background(f)) {
            var webhook = claim(f.attempt, f.binding, GatewayWorkQueue.WorkKind.WEBHOOK, false);
            assertThatThrownBy(() -> f.dispatch.loadForReadRecovery(ROUTE_KEY, webhook)).isInstanceOf(IllegalStateException.class);
        }
        var live = new Fixture(false, true, false, "LIVE");
        try (var ignored = background(live)) {
            assertThatThrownBy(() -> live.dispatch.loadForReadRecovery(ROUTE_KEY, live.claim)).isInstanceOf(IllegalStateException.class);
        }
        verifyNoInteractions(f.jdbc, f.factory, live.jdbc, live.factory);
    }

    @Test void actualDatabaseContextMismatchDeniesEvenWhenThreadContextLooksValid() {
        var f = fixture();
        when(f.jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Boolean.class))).thenReturn(false);
        try (var ignored = background(f)) {
            assertThatThrownBy(() -> f.dispatch.loadForReadRecovery(ROUTE_KEY, f.claim)).isInstanceOf(IllegalStateException.class);
        }
        verifyNoInteractions(f.factory);
    }

    @Test void trustedWorkerLoadsOnlyImmutableScopedSameIdRecoveryWithoutCredentialsPhoneOrNetwork() throws Exception {
        var f = fixture(); f.installRow();
        try (var ignored = background(f)) {
            var value = f.dispatch.loadForReadRecovery(ROUTE_KEY, f.claim);
            assertThat(value.attemptId()).isEqualTo(f.attempt);
            assertThat(value.accountId()).isEqualTo(f.account);
            assertThat(value.checkoutReference()).isEqualTo(f.attempt);
            assertThat(value.binding()).isEqualTo(f.binding);
            assertThat(value.amount()).isEqualByComparingTo("500.00");
            assertThat(value.returnUri()).isEqualTo(URI.create(ORIGIN + "/family/payments/" + f.attempt));
            assertThat(value.leaseUntil()).isEqualTo(f.claim.leaseUntil());
            assertThat(value.createPreviouslyDispatched()).isFalse();
            assertThat(value.toString()).doesNotContain(f.attempt.toString(), f.account.toString(), ORIGIN,
                    "synthetic_merchant", "synthetic:credentials");
        }
        assertThat(f.lastSql).contains("provider.status='ACTIVE'", "work.status='LEASED'",
                "work.lease_token=:leaseToken", "work.lease_started_at=:leaseStartedAt",
                "work.lease_until=:leaseUntil", "work.attempt_count=:attemptNumber",
                "provider.credentials_secret_reference=work.credentials_secret_reference",
                "attempt.merchant_account_reference=work.merchant_account_reference", "FOR SHARE OF work,attempt,provider")
                .doesNotContain("payer_phone", "client_secret", "INSERT", "UPDATE", "DELETE");
        assertThat(f.lastParameters.getValue("organisationId")).isEqualTo(f.org);
        assertThat(f.lastParameters.getValue("schoolId")).isEqualTo(f.school);
        assertThat(f.lastParameters.getValue("providerId")).isEqualTo(f.provider);
        assertThat(f.lastParameters.getValue("attemptId")).isEqualTo(f.attempt);
        assertThat(f.lastParameters.getValue("leaseToken")).isEqualTo(f.claim.token());
        assertThat(Arrays.stream(GatewayAttemptDispatch.ReadRecovery.class.getRecordComponents()).map(c -> c.getName()).toList())
                .noneMatch(name -> name.toLowerCase().contains("phone") || name.toLowerCase().contains("secret")
                        || name.toLowerCase().contains("redirect"));
        verifyNoInteractions(f.factory);
    }

    @Test void inactiveProviderOrChangedWorkAttemptIdentityCannotFallBackToAnotherRow() throws Exception {
        var f = fixture(); f.installRow(); f.rowPresent = false;
        try (var ignored = background(f)) {
            assertThatThrownBy(() -> f.dispatch.loadForReadRecovery(ROUTE_KEY, f.claim)).isInstanceOf(IllegalStateException.class);
        }
        assertThat(f.lastSql).contains("work.provider_code=:providerCode", "work.provider_environment=:environment",
                "work.merchant_account_reference=:merchant", "work.credentials_secret_reference=:credentialsReference",
                "work.payment_provider_config_id=:providerId", "work.organisation_id=:organisationId", "work.school_id=:schoolId");
        verifyNoInteractions(f.factory);
    }

    @Test void leaseExpiryDuringRowLockWaitIsCheckedUsingActualDatabaseTimeAfterTheRead() throws Exception {
        var f = fixture(); f.installRow();
        when(f.jdbc.queryForObject(eq("SELECT clock_timestamp()"), any(MapSqlParameterSource.class), eq(Timestamp.class)))
                .thenReturn(Timestamp.from(f.claim.leaseUntil()));
        try (var ignored = background(f)) {
            assertThatThrownBy(() -> f.dispatch.loadForReadRecovery(ROUTE_KEY, f.claim)).isInstanceOf(IllegalStateException.class);
        }
        verifyNoInteractions(f.factory);
    }

    @Test void staleTokensChangedLeaseTimesAndAttemptCountersAreNotARecoveryFallback() throws Exception {
        var f = fixture(); f.installRow();
        try (var ignored = background(f)) {
            for (var changed : List.of(
                    new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.ATTEMPT, f.attempt, f.binding, UUID.randomUUID(),
                            f.claim.startedAt(), f.claim.leaseUntil(), 1, false),
                    new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.ATTEMPT, f.attempt, f.binding, f.claim.token(),
                            f.claim.startedAt().plusSeconds(1), f.claim.leaseUntil(), 1, false),
                    new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.ATTEMPT, f.attempt, f.binding, f.claim.token(),
                            f.claim.startedAt(), f.claim.leaseUntil().plusSeconds(1), 1, false),
                    new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.ATTEMPT, f.attempt, f.binding, f.claim.token(),
                            f.claim.startedAt(), f.claim.leaseUntil(), 2, false))) {
                assertThatThrownBy(() -> f.dispatch.loadForReadRecovery(ROUTE_KEY, changed)).isInstanceOf(IllegalStateException.class);
            }
        }
        verifyNoInteractions(f.factory);
    }

    @Test void futureClaimAndAbsentDatabaseClockCannotManufactureALiveLease() throws Exception {
        var f = fixture(); f.installRow();
        try (var ignored = background(f)) {
            when(f.jdbc.queryForObject(eq("SELECT clock_timestamp()"), any(MapSqlParameterSource.class), eq(Timestamp.class)))
                    .thenReturn(Timestamp.from(f.claim.startedAt().minusNanos(1)));
            assertThatThrownBy(() -> f.dispatch.loadForReadRecovery(ROUTE_KEY, f.claim)).isInstanceOf(IllegalStateException.class);
            when(f.jdbc.queryForObject(eq("SELECT clock_timestamp()"), any(MapSqlParameterSource.class), eq(Timestamp.class))).thenReturn(null);
            assertThatThrownBy(() -> f.dispatch.loadForReadRecovery(ROUTE_KEY, f.claim)).isInstanceOf(IllegalStateException.class);
        }
        verifyNoInteractions(f.factory);
    }

    @Test void existingCreateMarkerIsPreservedAsEvidenceNotAnotherCreatePermission() throws Exception {
        var f = fixture(); f.installRow(); f.values.put("create_dispatched", true);
        try (var ignored = background(f)) {
            assertThat(f.dispatch.loadForReadRecovery(ROUTE_KEY, f.claim).createPreviouslyDispatched()).isTrue();
        }
        assertThat(Arrays.stream(GatewayAttemptDispatch.class.getDeclaredMethods()).map(m -> m.getName()).toList())
                .doesNotContain("markCreateDispatched", "create", "createHostedCheckout", "loadForCreate");
        verifyNoInteractions(f.factory);
    }

    @Test void closedAccountsHeldCapturesAndExpiredIntentStillPermitReadsWithoutReopeningFinancialDays() throws Exception {
        var f = fixture(); f.installRow();
        f.values.put("account_status", "CLOSED"); f.values.put("attempt_status", "CAPTURED_REVIEW_REQUIRED");
        f.values.put("created_at", Timestamp.from(NOW.minusSeconds(3600)));
        f.values.put("expires_at", Timestamp.from(NOW.minusSeconds(1800)));
        try (var ignored = background(f)) {
            var value = f.dispatch.loadForReadRecovery(ROUTE_KEY, f.claim);
            assertThat(value.accountStatus()).isEqualTo("CLOSED");
            assertThat(value.attemptStatus()).isEqualTo("CAPTURED_REVIEW_REQUIRED");
            assertThat(value.expiresAt()).isBefore(NOW);
        }
        verifyNoInteractions(f.factory);
    }

    @Test void changedOffsiteQueryFragmentAndOtherAttemptReturnAddressesAreDeniedWithoutEchoingThem() throws Exception {
        var f = fixture(); f.installRow();
        try (var ignored = background(f)) {
            for (String address : List.of("https://other.example.test/family/payments/" + f.attempt,
                    ORIGIN + "/family/payments/" + UUID.randomUUID(),
                    ORIGIN + "/family/payments/" + f.attempt + "?customer=synthetic-private",
                    ORIGIN + "/family/payments/" + f.attempt + "#success")) {
                f.values.put("return_uri", address);
                assertThatThrownBy(() -> f.dispatch.loadForReadRecovery(ROUTE_KEY, f.claim))
                        .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(address);
            }
        }
        verifyNoInteractions(f.factory);
    }

    @Test void currentCanonicalOriginMayHaveRootSlashButNotAPathQueryOrUnsafeScheme() throws Exception {
        var f = fixture(); f.installRow();
        try (var ignored = background(f)) {
            var slash = new GatewayAttemptDispatch(f.jdbc, f.registry, properties(ORIGIN + "/"), f.readiness);
            assertThat(slash.loadForReadRecovery(ROUTE_KEY, f.claim).returnUri()).isEqualTo(URI.create(ORIGIN + "/family/payments/" + f.attempt));
            for (String bad : List.of(ORIGIN + "/private", ORIGIN + "?query=private", "ftp://synthetic-school.example.test")) {
                var denied = new GatewayAttemptDispatch(f.jdbc, f.registry, properties(bad), f.readiness);
                assertThatThrownBy(() -> denied.loadForReadRecovery(ROUTE_KEY, f.claim)).isInstanceOf(IllegalStateException.class);
            }
        }
        verifyNoInteractions(f.factory);
    }

    @Test void snapshotRejectsBrokenImmutableCheckoutMoneySessionTimeAndStatus() throws Exception {
        var f = fixture(); f.installRow();
        Map<String, Object> original = Map.copyOf(f.values);
        try (var ignored = background(f)) {
            for (Map.Entry<String, Object> changed : Map.<String, Object>of(
                    "checkout_reference", UUID.randomUUID(), "amount", new BigDecimal("500.001"),
                    "currency_code", "USD", "attempt_status", "FAILED", "account_status", "DELETED",
                    "original_business_date", LocalDate.of(2026, 10, 10), "school_time_zone", "Invalid/Zone",
                    "expires_at", Timestamp.from(NOW.minusSeconds(60))).entrySet()) {
                f.values.clear(); f.values.putAll(original); f.values.put(changed.getKey(), changed.getValue());
                assertThatThrownBy(() -> f.dispatch.loadForReadRecovery(ROUTE_KEY, f.claim)).isInstanceOf(RuntimeException.class);
            }
        }
        verifyNoInteractions(f.factory);
    }

    @Test void newCreateRequiresBothIndependentFlagsAndCannotUseAnAlreadyDispatchedClaim() {
        for (var f : List.of(new Fixture(false, true, false, "TEST"), new Fixture(true, false, false, "TEST"))) {
            try (var ignored = background(f)) {
                assertThatThrownBy(() -> f.dispatch.admitCreate(ROUTE_KEY, f.claim)).isInstanceOf(IllegalStateException.class);
            }
            verifyNoInteractions(f.jdbc, f.readiness, f.factory);
        }
        var f = new Fixture(true, true, false, "TEST");
        try (var ignored = background(f)) {
            var dispatched = new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.ATTEMPT, f.attempt, f.binding,
                    f.claim.token(), f.claim.startedAt(), f.claim.leaseUntil(), f.claim.attemptNumber(), true);
            assertThatThrownBy(() -> f.dispatch.admitCreate(ROUTE_KEY, dispatched)).isInstanceOf(IllegalStateException.class);
        }
        verifyNoInteractions(f.jdbc, f.readiness, f.factory);
    }

    @Test void newCreateNeverTurnsRecoveryOrChangedTrustedIdentityIntoAnotherOrder() throws Exception {
        var f = new Fixture(true, true, false, "TEST"); f.installRow();
        try (var ignored = background(f)) {
            assertThatThrownBy(() -> f.dispatch.admitCreate(ROUTE_KEY, f.claim)).isInstanceOf(IllegalStateException.class);
            f.values.put("attempt_status", "RESERVED"); f.values.put("create_dispatched", true);
            assertThatThrownBy(() -> f.dispatch.admitCreate(ROUTE_KEY, f.claim)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> f.dispatch.admitCreate("unknown-route", f.claim)).isInstanceOf(IllegalStateException.class);
        }
        verify(f.jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
        verifyNoInteractions(f.readiness, f.factory);
    }

    @Test void newCreateDeniesMissingLiveGrantAndUnpayableChargeBeforeReadinessOrMarker() throws Exception {
        var f = createFixture(); f.installCreateRows(); f.liveGrant = false;
        try (var ignored = background(f)) {
            assertThatThrownBy(() -> f.dispatch.admitCreate(ROUTE_KEY, f.claim)).isInstanceOf(IllegalStateException.class);
            f.liveGrant = true; f.reservedAmount = new BigDecimal("1100.00");
            assertThatThrownBy(() -> f.dispatch.admitCreate(ROUTE_KEY, f.claim)).isInstanceOf(IllegalStateException.class);
            f.reservedAmount = new BigDecimal("500.00"); f.partial = false;
            assertThatThrownBy(() -> f.dispatch.admitCreate(ROUTE_KEY, f.claim)).isInstanceOf(IllegalStateException.class);
        }
        verify(f.jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
        verifyNoInteractions(f.readiness, f.factory);
    }

    @Test void deniedAccountingReadinessCannotConsumeTheOneUseCreateMarker() throws Exception {
        var f = createFixture(); f.installCreateRows();
        when(f.readiness.validate(eq(f.org), eq(f.school), eq(f.attempt), eq(f.account), any(LocalDate.class)))
                .thenThrow(new IllegalStateException("Synthetic unapproved accounting readiness"));
        try (var ignored = background(f)) {
            assertThatThrownBy(() -> f.dispatch.admitCreate(ROUTE_KEY, f.claim)).isInstanceOf(IllegalStateException.class);
        }
        verify(f.jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
        verifyNoInteractions(f.factory);
    }

    @Test void createRechecksActualDatabaseTimeAfterReadinessWaitRatherThanBackdatingItsMarker() throws Exception {
        var f = createFixture(); f.installCreateRows();
        when(f.jdbc.queryForObject(eq("SELECT clock_timestamp()"), any(MapSqlParameterSource.class), eq(Timestamp.class)))
                .thenReturn(Timestamp.from(NOW), Timestamp.from(f.claim.leaseUntil()));
        try (var ignored = background(f)) {
            assertThatThrownBy(() -> f.dispatch.admitCreate(ROUTE_KEY, f.claim)).isInstanceOf(IllegalStateException.class);
        }
        verify(f.readiness).validate(eq(f.org), eq(f.school), eq(f.attempt), eq(f.account), eq(LocalDate.of(2026, 10, 11)));
        verify(f.jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
        verifyNoInteractions(f.factory);
    }

    @Test void createRechecksSchoolLocalDayAfterReadinessWaitCrossesMidnightWithinItsLease() throws Exception {
        Instant beforeMidnight = Instant.parse("2026-10-11T18:29:59Z");
        var f = new Fixture(true, true, false, "TEST", beforeMidnight); f.installRow();
        f.values.put("attempt_status", "RESERVED"); f.installCreateRows();
        when(f.jdbc.queryForObject(eq("SELECT clock_timestamp()"), any(MapSqlParameterSource.class), eq(Timestamp.class)))
                .thenReturn(Timestamp.from(beforeMidnight), Timestamp.from(beforeMidnight.plusSeconds(2)));
        try (var ignored = background(f)) {
            assertThatThrownBy(() -> f.dispatch.admitCreate(ROUTE_KEY, f.claim)).isInstanceOf(IllegalStateException.class);
        }
        verify(f.readiness).validate(eq(f.org), eq(f.school), eq(f.attempt), eq(f.account), eq(LocalDate.of(2026, 10, 11)));
        verify(f.jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
        verifyNoInteractions(f.factory);
    }

    @Test void admittedCreateConsumesExactMarkerLastWithoutNetworkPhoneOrFinancialMutation() throws Exception {
        var f = createFixture(); f.installCreateRows();
        try (var ignored = background(f)) {
            var admitted = f.dispatch.admitCreate(ROUTE_KEY, f.claim);
            assertThat(admitted.attempt().checkoutReference()).isEqualTo(f.attempt);
            assertThat(admitted.attempt().amount()).isEqualByComparingTo("500.00");
            assertThat(admitted.dispatchedAt()).isEqualTo(NOW);
            assertThat(admitted.toString()).doesNotContain(f.attempt.toString(), f.binding.merchantReference(), ORIGIN);
        }
        assertThat(f.markerSql).contains("work.attempt_count=:attemptNumber", "work.lease_started_at=:leaseStartedAt",
                "work.lease_until=:leaseUntil", "work.create_dispatched_at IS NULL", "member.valid_until>:observedAt")
                .doesNotContain("fee_payments", "accounting_journals", "payer_phone");
        assertThat(f.markerParameters.getValue("observedAt")).isEqualTo(Timestamp.from(NOW));
        assertThat(f.markerParameters.getValue("leaseToken")).isEqualTo(f.claim.token());
        assertThat(f.markerParameters.getValue("attemptNumber")).isEqualTo(f.claim.attemptNumber());
        verifyNoInteractions(f.factory);
    }

    private static Fixture createFixture() throws Exception {
        var f = new Fixture(true, true, false, "TEST"); f.installRow(); f.values.put("attempt_status", "RESERVED"); return f;
    }

    private static Fixture fixture() { return new Fixture(false, true, false, "TEST"); }
    private static TenantRequestContext.Scope background(Fixture f) {
        writable();
        return TenantRequestContext.open(null, f.org);
    }
    private static void writable() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }
    private static GatewayWorkQueue.Claim claim(UUID id, GatewayWorkQueue.ProviderBinding binding,
            GatewayWorkQueue.WorkKind kind, boolean dispatched) {
        return new GatewayWorkQueue.Claim(kind, id, binding, UUID.randomUUID(), NOW, NOW.plusSeconds(90), 1, dispatched);
    }
    private static ApplicationProperties properties(String origin) {
        return new ApplicationProperties(origin, List.of(), Optional.empty(), Optional.empty(), null, null);
    }

    private static final class Fixture {
        final UUID org = UUID.randomUUID(), school = UUID.randomUUID(), provider = UUID.randomUUID();
        final UUID attempt = UUID.randomUUID(), account = UUID.randomUUID();
        final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        final GatewayProviderRegistry.ProviderFactory factory = mock(GatewayProviderRegistry.ProviderFactory.class);
        final GatewayCheckoutReadiness readiness = mock(GatewayCheckoutReadiness.class);
        final GatewayWorkQueue.ProviderBinding binding;
        final GatewayWorkQueue.Claim claim;
        final GatewayProviderRegistry registry;
        final GatewayAttemptDispatch dispatch;
        final Instant observedStart;
        final Map<String, Object> values = new HashMap<>();
        String lastSql;
        MapSqlParameterSource lastParameters;
        boolean rowPresent = true;
        boolean liveGrant = true, partial = true;
        BigDecimal reservedAmount = new BigDecimal("500.00");
        String markerSql;
        MapSqlParameterSource markerParameters;

        Fixture(boolean creation, boolean worker, boolean live, String environment) {
            this(creation, worker, live, environment, NOW);
        }
        Fixture(boolean creation, boolean worker, boolean live, String environment, Instant observedStart) {
            this.observedStart = observedStart;
            binding = new GatewayWorkQueue.ProviderBinding(org, school, provider, "CASHFREE", environment,
                    "synthetic_merchant", "synthetic:credentials");
            claim = new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.ATTEMPT, attempt, binding, UUID.randomUUID(),
                    observedStart, observedStart.plusSeconds(90), 1, false);
            var route = new GatewayProviderRegistry.Route(ROUTE_KEY, org, school, provider, "CASHFREE",
                    CashfreePaymentProviderAdapter.Environment.valueOf(environment), "synthetic_merchant", "synthetic:credentials",
                    "env:APP_FINANCE_GATEWAY_CASHFREE_" + environment + "_CLIENT_ID",
                    "env:APP_FINANCE_GATEWAY_CASHFREE_" + environment + "_CLIENT_SECRET");
            registry = new GatewayProviderRegistry(new GatewayProviderRegistry.Settings(creation, worker, live, false, List.of(route)),
                    URI.create(ORIGIN), Clock.fixed(observedStart, ZoneOffset.UTC),
                    ignored -> { throw new AssertionError("Read admission must not resolve credentials"); }, factory);
            dispatch = new GatewayAttemptDispatch(jdbc, registry, properties(ORIGIN), readiness);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void installCreateRows() throws Exception {
            when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenAnswer(call -> {
                String sql = call.getArgument(0); RowMapper mapper = call.getArgument(2);
                if (sql.contains("SELECT member.id")) {
                    if (!liveGrant) return List.of();
                    ResultSet row = mock(ResultSet.class); when(row.getObject("id", UUID.class)).thenReturn(UUID.randomUUID());
                    return List.of(mapper.mapRow(row, 0));
                }
                if (sql.contains("SELECT mr.role_id")) {
                    ResultSet row = mock(ResultSet.class); when(row.getObject("role_id", UUID.class)).thenReturn(UUID.randomUUID());
                    return List.of(mapper.mapRow(row, 0));
                }
                if (sql.contains("charge.amount AS charge_amount")) {
                    ResultSet row = mock(ResultSet.class);
                    when(row.getBigDecimal("amount")).thenReturn(new BigDecimal("500.00"));
                    when(row.getBigDecimal("charge_amount")).thenReturn(new BigDecimal("1000.00"));
                    when(row.getBigDecimal("concessions")).thenReturn(BigDecimal.ZERO);
                    when(row.getBigDecimal("paid")).thenReturn(BigDecimal.ZERO);
                    when(row.getBigDecimal("reserved")).thenReturn(reservedAmount);
                    when(row.getBoolean("allow_partial")).thenReturn(partial);
                    return List.of(mapper.mapRow(row, 0));
                }
                throw new AssertionError("Unexpected CREATE read query");
            });
            when(readiness.validate(eq(org), eq(school), eq(attempt), eq(account), any(LocalDate.class)))
                    .thenReturn(new GatewayCheckoutReadiness.ReadyBook(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
            when(jdbc.update(anyString(), any(MapSqlParameterSource.class))).thenAnswer(call -> {
                markerSql = call.getArgument(0); markerParameters = call.getArgument(1); return 1;
            });
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void installRow() throws Exception {
            values.putAll(Map.of("id", attempt, "learner_fee_account_id", account, "checkout_reference", attempt,
                    "amount", new BigDecimal("500.00"), "currency_code", "INR", "attempt_status", "CHECKOUT_PENDING",
                    "account_status", "ACTIVE", "school_time_zone", "Asia/Kolkata", "original_business_date", observedStart.atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate()));
            values.putAll(Map.of("created_at", Timestamp.from(observedStart.minusSeconds(60)), "expires_at", Timestamp.from(observedStart.plusSeconds(1740)),
                    "return_uri", ORIGIN + "/family/payments/" + attempt, "row_version", 1L,
                    "lease_until", Timestamp.from(claim.leaseUntil()), "create_dispatched", false));
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Boolean.class))).thenReturn(true);
            when(jdbc.queryForObject(eq("SELECT clock_timestamp()"), any(MapSqlParameterSource.class), eq(Timestamp.class)))
                    .thenReturn(Timestamp.from(observedStart));
            when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(ResultSetExtractor.class))).thenAnswer(call -> {
                lastSql = call.getArgument(0); lastParameters = call.getArgument(1);
                ResultSet row = mock(ResultSet.class);
                boolean sameLease = attempt.equals(lastParameters.getValue("attemptId"))
                        && claim.token().equals(lastParameters.getValue("leaseToken"))
                        && Timestamp.from(claim.startedAt()).equals(lastParameters.getValue("leaseStartedAt"))
                        && Timestamp.from(claim.leaseUntil()).equals(lastParameters.getValue("leaseUntil"))
                        && Integer.valueOf(claim.attemptNumber()).equals(lastParameters.getValue("attemptNumber"));
                when(row.next()).thenReturn(rowPresent && sameLease);
                when(row.getObject(anyString(), any(Class.class))).thenAnswer(read -> values.get(read.<String>getArgument(0)));
                when(row.getString(anyString())).thenAnswer(read -> (String) values.get(read.<String>getArgument(0)));
                when(row.getBigDecimal(anyString())).thenAnswer(read -> (BigDecimal) values.get(read.<String>getArgument(0)));
                when(row.getTimestamp(anyString())).thenAnswer(read -> (Timestamp) values.get(read.<String>getArgument(0)));
                when(row.getLong(anyString())).thenAnswer(read -> (Long) values.get(read.<String>getArgument(0)));
                when(row.getBoolean(anyString())).thenAnswer(read -> (Boolean) values.get(read.<String>getArgument(0)));
                return ((ResultSetExtractor) call.getArgument(2)).extractData(row);
            });
        }
    }
}
