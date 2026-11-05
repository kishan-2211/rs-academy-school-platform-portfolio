package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.educore.api.identity.SensitiveValueCipher;
import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Mocked admission/transaction ordering only; not PostgreSQL, provider or parent acceptance. */
class GatewayCreateCommandAdmissionTest {
    private static final String ROUTE = "d".repeat(64);
    private static final String ERROR = "Gateway CREATE command requires committed trusted one-use admission and a valid scoped private payer";
    private static final Instant NOW = Instant.parse("2026-10-14T08:00:00.123456Z");
    private final UUID organisation = UUID.randomUUID(), school = UUID.randomUUID(), provider = UUID.randomUUID();
    private final UUID attempt = UUID.randomUUID(), account = UUID.randomUUID();
    private final GatewayProviderRegistry registry = mock(GatewayProviderRegistry.class);
    private final GatewayAttemptDispatch dispatch = mock(GatewayAttemptDispatch.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final SensitiveValueCipher cipher = mock(SensitiveValueCipher.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final GatewayCreateCommandAdmission service = new GatewayCreateCommandAdmission(registry, dispatch, jdbc, cipher, manager);
    private final GatewayWorkQueue.ProviderBinding binding = binding(organisation, school, provider, "CASHFREE", "TEST", "synthetic_merchant", "synthetic:disabled");
    private final GatewayWorkQueue.Claim claim = new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.ATTEMPT,
            attempt, binding, UUID.randomUUID(), NOW.minusSeconds(1), NOW.plusSeconds(89), 1, false);
    private final List<String> events = new ArrayList<>();
    private byte[] plaintext = "0000000000".getBytes(StandardCharsets.US_ASCII);
    private String encrypted = "s".repeat(35), nonce = "n".repeat(16);
    private boolean scopeValid = true, readOnly, payerPresent = true, duplicatePayer, marker, committed;
    private Instant databaseNow = NOW.plusNanos(500), createdAt = NOW.minusSeconds(60), expiry = NOW.plusSeconds(300);
    private GatewayAttemptDispatch.CreateAdmission suppliedAdmission;

    @AfterEach void cleanupOnlyTestState() {
        TransactionSynchronizationManager.clear();
        assertThat(TenantRequestContext.current()).isEmpty();
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }

    @Test void returnsOnlyAfterFreshCommitWithOneUseAdmissionScopedPrivatePayerAndZeroedBuffer() throws Exception {
        ready();
        var command = service.admit(ROUTE, claim);
        events.add("RETURN");
        assertThat(events).containsExactly("BEGIN", "SCOPE", "ADMIT_CREATE", "LOAD_PAYER", "DECRYPT", "DB_CLOCK", "COMMIT", "RETURN");
        assertThat(committed).isTrue(); assertThat(marker).isTrue();
        assertThat(command.admission()).isEqualTo(admission(binding, attempt, account, false, "RESERVED", "ACTIVE", claim.leaseUntil()));
        assertThat(command.request().attemptId()).isEqualTo(attempt);
        assertThat(command.request().accountId()).isEqualTo(account);
        assertThat(command.request().amount()).isEqualByComparingTo("750.00");
        assertThat(command.request().currencyCode()).isEqualTo("INR");
        assertThat(command.request().expiresAt()).isEqualTo(expiry);
        assertThat(command.request().returnUri()).isEqualTo(returnUri(attempt));
        assertThat(command.request().payerPhone()).isEqualTo("0000000000");
        assertThat(plaintext).isEqualTo(new byte[plaintext.length]);
        assertThat(TenantRequestContext.current()).isEmpty();
        verify(dispatch).admitCreate(ROUTE, claim);
        verify(registry, never()).provider(any());
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test void redactsCommandAndNestedRequestInsteadOfExposingPayerOrReturnAddress() throws Exception {
        ready(); var command = service.admit(ROUTE, claim);
        for (String printed : List.of(command.toString(), command.request().toString(), command.admission().toString())) {
            assertThat(printed).doesNotContain("0000000000", encrypted, nonce, returnUri(attempt).toString(), attempt.toString(), account.toString());
        }
    }

    @Test void creationAndWorkerFlagsAreBothRequiredBeforeOpeningPersistence() {
        configure("TEST"); when(registry.creationEnabled()).thenReturn(false); rejects(claim);
        when(registry.creationEnabled()).thenReturn(true); when(registry.workerEnabled()).thenReturn(false); rejects(claim);
        verifyNoInteractions(dispatch, jdbc, cipher, manager);
        verify(registry, never()).provider(any());
    }

    @Test void liveRouteRequiresSeparateLiveEnablement() {
        configure("LIVE"); var live = claim(binding(organisation, school, provider, "CASHFREE", "LIVE", "synthetic_merchant", "synthetic:disabled"));
        rejects(live); verifyNoInteractions(dispatch, jdbc, cipher, manager);
    }

    @Test void explicitlyEnabledLiveRouteStillUsesDelegatedAdmissionRatherThanActivatingProvider() throws Exception {
        ready(); configure("LIVE"); when(registry.liveEnabled()).thenReturn(true);
        var live = claim(binding(organisation, school, provider, "CASHFREE", "LIVE", "synthetic_merchant", "synthetic:disabled"));
        var command = service.admit(ROUTE, live);
        assertThat(command.admission().attempt().binding()).isEqualTo(live.binding());
        verify(dispatch).admitCreate(ROUTE, live); verify(registry, never()).provider(any());
    }

    @Test void absentRoutesAndEveryChangedFrozenIdentityFailBeforeOpeningDatabase() {
        configure("TEST"); when(registry.findRoute(ROUTE)).thenReturn(Optional.empty()); rejects(claim);
        configure("TEST"); var other = UUID.randomUUID();
        for (var changed : List.of(
                binding(other, school, provider, "CASHFREE", "TEST", "synthetic_merchant", "synthetic:disabled"),
                binding(organisation, other, provider, "CASHFREE", "TEST", "synthetic_merchant", "synthetic:disabled"),
                binding(organisation, school, other, "CASHFREE", "TEST", "synthetic_merchant", "synthetic:disabled"),
                binding(organisation, school, provider, "OTHER", "TEST", "synthetic_merchant", "synthetic:disabled"),
                binding(organisation, school, provider, "CASHFREE", "LIVE", "synthetic_merchant", "synthetic:disabled"),
                binding(organisation, school, provider, "CASHFREE", "TEST", "synthetic_changed", "synthetic:disabled"),
                binding(organisation, school, provider, "CASHFREE", "TEST", "synthetic_merchant", "synthetic:changed"))) rejects(claim(changed));
        verifyNoInteractions(dispatch, jdbc, cipher, manager);
    }

    @Test void missingWebhookAndAlreadyDispatchedClaimsAreNeverCreateCommands() {
        configure("TEST"); rejects(null);
        rejects(new GatewayWorkQueue.Claim(GatewayWorkQueue.WorkKind.WEBHOOK, attempt, binding, claim.token(), claim.startedAt(), claim.leaseUntil(), 1, false));
        rejects(new GatewayWorkQueue.Claim(claim.kind(), attempt, binding, claim.token(), claim.startedAt(), claim.leaseUntil(), 1, true));
        verifyNoInteractions(dispatch, jdbc, cipher, manager);
    }

    @Test void ambientHumanOrSystemContextsArePreservedAndRejectedBeforeAuthorityReplacement() {
        for (UUID user : new UUID[] {null, UUID.randomUUID()}) {
            try (var ignored = TenantRequestContext.open(user, UUID.randomUUID())) {
                var original = TenantRequestContext.current(); rejects(claim);
                assertThat(TenantRequestContext.current()).isEqualTo(original);
            }
        }
        verifyNoInteractions(registry, dispatch, jdbc, cipher, manager);
    }

    @Test void ambientTransactionsSynchronizationOrBoundResourcesAreRejectedWithoutOpeningAnotherTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true); rejects(claim);
        TransactionSynchronizationManager.clear();
        TransactionSynchronizationManager.initSynchronization(); rejects(claim);
        TransactionSynchronizationManager.clearSynchronization();
        Object resourceKey = new Object(), resource = new Object();
        TransactionSynchronizationManager.bindResource(resourceKey, resource);
        try { rejects(claim); assertThat(TransactionSynchronizationManager.getResource(resourceKey)).isSameAs(resource); }
        finally { TransactionSynchronizationManager.unbindResource(resourceKey); }
        verifyNoInteractions(registry, dispatch, jdbc, cipher, manager);
    }

    @Test void freshTransactionStillMustBeWritableAndDatabaseNullUserScopeMustMatch() throws Exception {
        ready(); scopeValid = false; rejects(claim);
        assertThat(events).containsExactly("BEGIN", "SCOPE", "ROLLBACK");
        verifyNoInteractions(dispatch, cipher);
        events.clear(); scopeValid = true; readOnly = true; rejects(claim);
        assertThat(events).containsExactly("BEGIN", "ROLLBACK");
        verifyNoInteractions(dispatch, cipher);
    }

    @Test void delegatedCurrentFamilyFeeBookOrFullLeaseRejectionCannotReadOrDecryptPayer() throws Exception {
        ready();
        doThrow(new IllegalStateException("synthetic-private-family-book-reference")).when(dispatch).admitCreate(anyString(), any());
        rejects(claim);
        assertThat(events).containsExactly("BEGIN", "SCOPE", "ROLLBACK");
        verifyNoInteractions(cipher); verify(dispatch).admitCreate(ROUTE, claim);
    }

    @Test void changedAdmissionIdentityOrAlreadyUsedSnapshotCannotProduceCommand() throws Exception {
        ready(); var other = UUID.randomUUID();
        for (var changed : List.of(
                admission(binding, other, account, false, "RESERVED", "ACTIVE", claim.leaseUntil()),
                admission(binding(organisation, other, provider, "CASHFREE", "TEST", "synthetic_merchant", "synthetic:disabled"), attempt, account, false, "RESERVED", "ACTIVE", claim.leaseUntil()),
                admission(binding, attempt, account, true, "RESERVED", "ACTIVE", claim.leaseUntil()),
                admission(binding, attempt, account, false, "CHECKOUT_PENDING", "ACTIVE", claim.leaseUntil()),
                admission(binding, attempt, account, false, "RESERVED", "CLOSED", claim.leaseUntil()),
                admission(binding, attempt, account, false, "RESERVED", "ACTIVE", claim.leaseUntil().plusSeconds(1)))) {
            suppliedAdmission = changed; rejects(claim);
            assertThat(marker).isFalse(); assertThat(committed).isFalse();
        }
        verifyNoInteractions(cipher); assertThat(events).doesNotContain("LOAD_PAYER");
    }

    @Test void missingOrMalformedDelegatedAdmissionRollsBackMarkerWithoutPrivateRead() throws Exception {
        ready(); doReturn(null).when(dispatch).admitCreate(anyString(), any()); rejects(claim);
        for (var malformed : List.of(new GatewayAttemptDispatch.CreateAdmission(null, book(), NOW),
                new GatewayAttemptDispatch.CreateAdmission(snapshot(binding, attempt, account, false, "RESERVED", "ACTIVE", claim.leaseUntil()), null, NOW),
                new GatewayAttemptDispatch.CreateAdmission(snapshot(binding, attempt, account, false, "RESERVED", "ACTIVE", claim.leaseUntil()), book(), null))) {
            doReturn(malformed).when(dispatch).admitCreate(anyString(), any()); rejects(claim);
        }
        verifyNoInteractions(cipher); assertThat(events).doesNotContain("LOAD_PAYER");
    }

    @Test void dispatchedTimeMustBelongToTheCurrentUnexpiredLeaseAndIntent() throws Exception {
        ready();
        for (Instant invalid : List.of(claim.startedAt().minusNanos(1), claim.leaseUntil(), expiry)) {
            suppliedAdmission = new GatewayAttemptDispatch.CreateAdmission(snapshot(binding, attempt, account, false, "RESERVED", "ACTIVE", claim.leaseUntil()), book(), invalid);
            rejects(claim); assertThat(marker).isFalse();
        }
        verifyNoInteractions(cipher); assertThat(events).doesNotContain("LOAD_PAYER");
    }

    @Test void exactScopedPayerMustExistOnceAfterOneUseMarkerOrMarkerRollsBack() throws Exception {
        ready(); payerPresent = false; rejects(claim);
        assertThat(events).containsExactly("BEGIN", "SCOPE", "ADMIT_CREATE", "LOAD_PAYER", "ROLLBACK");
        assertThat(marker).isFalse(); assertThat(committed).isFalse(); verifyNoInteractions(cipher);
        events.clear(); payerPresent = true; duplicatePayer = true; rejects(claim);
        assertThat(events).containsExactly("BEGIN", "SCOPE", "ADMIT_CREATE", "LOAD_PAYER", "ROLLBACK");
        assertThat(marker).isFalse(); verifyNoInteractions(cipher);
    }

    @Test void immutablePayerCipherShapeIsValidatedBeforeDecryption() throws Exception {
        ready();
        for (String invalid : Arrays.asList(null, "", "private encrypted text", "a".repeat(23), "a".repeat(161))) {
            encrypted = invalid; rejects(claim); assertThat(marker).isFalse();
        }
        encrypted = "s".repeat(35);
        for (String invalid : Arrays.asList(null, "", "a".repeat(15), "a".repeat(17), "private nonce!!!")) {
            nonce = invalid; rejects(claim); assertThat(marker).isFalse();
        }
        verifyNoInteractions(cipher); assertThat(committed).isFalse();
    }

    @Test void decryptionFailureRollsBackTheNotYetCommittedMarkerAndSanitizesCipherCause() throws Exception {
        ready(); doThrow(new IllegalStateException("synthetic-private-ciphertext-and-key", new RuntimeException("synthetic-key")))
                .when(cipher).decrypt(any(), anyString());
        rejects(claim);
        assertThat(events).containsExactly("BEGIN", "SCOPE", "ADMIT_CREATE", "LOAD_PAYER", "ROLLBACK");
        assertThat(marker).isFalse(); assertThat(committed).isFalse();
    }

    @Test void exactlyTenAsciiDigitsAreRequiredAndEveryReturnedInvalidByteBufferIsZeroed() throws Exception {
        ready();
        for (byte[] invalid : List.of(new byte[0], "000000000".getBytes(StandardCharsets.US_ASCII),
                "00000000000".getBytes(StandardCharsets.US_ASCII), "000000000 ".getBytes(StandardCharsets.US_ASCII),
                "000000000+".getBytes(StandardCharsets.US_ASCII), "000000000\n".getBytes(StandardCharsets.US_ASCII),
                "００００００００００".getBytes(StandardCharsets.UTF_8), new byte[] {48,48,48,48,48,48,48,48,48,(byte) 255})) {
            plaintext = invalid; rejects(claim); assertThat(invalid).isEqualTo(new byte[invalid.length]);
            assertThat(marker).isFalse(); assertThat(committed).isFalse();
        }
        plaintext = null; rejects(claim); assertThat(marker).isFalse();
        assertThat(events).doesNotContain("DB_CLOCK", "COMMIT");
    }

    @Test void postDecryptionActualDatabaseLeaseExpiryRollsBackMarkerAndClearsBuffer() throws Exception {
        ready(); databaseNow = claim.leaseUntil(); rejects(claim);
        assertThat(events).containsExactly("BEGIN", "SCOPE", "ADMIT_CREATE", "LOAD_PAYER", "DECRYPT", "DB_CLOCK", "ROLLBACK");
        assertThat(marker).isFalse(); assertThat(committed).isFalse(); assertThat(plaintext).isEqualTo(new byte[plaintext.length]);
    }

    @Test void intentExpiryAndClockBeforeDispatchCannotBeOverriddenByCallerLease() throws Exception {
        ready(); expiry = NOW.plusSeconds(1); databaseNow = expiry; rejects(claim);
        assertThat(marker).isFalse(); assertThat(plaintext).isEqualTo(new byte[plaintext.length]);
        plaintext = "0000000000".getBytes(StandardCharsets.US_ASCII); databaseNow = NOW.minusNanos(1); rejects(claim);
        assertThat(marker).isFalse(); assertThat(plaintext).isEqualTo(new byte[plaintext.length]);
    }

    @Test void sqlFailureAfterAdmissionRollsBackMarkerAndNeverLeaksDatabasePrivateText() throws Exception {
        ready(); doThrow(new IllegalStateException("synthetic-private-sql-payer-reference")).when(jdbc)
                .query(anyString(), any(SqlParameterSource.class), ArgumentMatchers.<ResultSetExtractor<Object>>any());
        rejects(claim); assertThat(marker).isFalse(); assertThat(committed).isFalse(); verifyNoInteractions(cipher);
    }

    @Test void databaseClockFailureAfterDecryptionStillZerosBufferAndRollsBackMarker() throws Exception {
        ready(); doThrow(new IllegalStateException("synthetic-private-clock-query")).when(jdbc)
                .queryForObject(anyString(), any(SqlParameterSource.class), ArgumentMatchers.<RowMapper<Instant>>any());
        rejects(claim); assertThat(marker).isFalse(); assertThat(plaintext).isEqualTo(new byte[plaintext.length]);
    }

    @Test void commitFailureReturnsNoCommandAndClosesScopeWithoutExposingPrivateFailure() throws Exception {
        ready(); doAnswer(call -> { events.add("COMMIT_UNKNOWN"); TransactionSynchronizationManager.clear();
            throw new IllegalStateException("synthetic-private-commit-key"); }).when(manager).commit(any());
        rejects(claim);
        assertThat(events).contains("COMMIT_UNKNOWN").doesNotContain("COMMIT");
        assertThat(committed).isFalse(); assertThat(plaintext).isEqualTo(new byte[plaintext.length]);
        assertThat(TenantRequestContext.current()).isEmpty();
    }

    @Test void transactionStartFailureCannotLeaveBackgroundScopeOrReturnPrivateCommand() throws Exception {
        configure("TEST"); when(manager.getTransaction(any())).thenThrow(new IllegalStateException("synthetic-private-manager"));
        rejects(claim); verifyNoInteractions(dispatch, jdbc, cipher);
        assertThat(TenantRequestContext.current()).isEmpty();
    }

    private void ready() throws Exception {
        configure("TEST");
        doAnswer(call -> {
            var definition = call.getArgument(0, TransactionDefinition.class);
            assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThat(definition.getTimeout()).isEqualTo(15); assertThat(definition.isReadOnly()).isFalse();
            assertThat(TenantRequestContext.current().orElseThrow()).isEqualTo(new TenantRequestContext.ScopeValue(null, organisation));
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);
            events.add("BEGIN"); return new SimpleTransactionStatus();
        }).when(manager).getTransaction(any());
        doAnswer(call -> {
            assertThat(plaintext).isEqualTo(new byte[plaintext.length]); assertThat(marker).isTrue();
            events.add("COMMIT"); committed = true; TransactionSynchronizationManager.clear(); return null;
        }).when(manager).commit(any());
        doAnswer(call -> { events.add("ROLLBACK"); marker = false; TransactionSynchronizationManager.clear(); return null; }).when(manager).rollback(any());
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class))).thenAnswer(call -> {
            assertThat(call.getArgument(0, String.class)).contains("current_app_organisation_id()=:organisationId", "current_app_user_id() IS NULL");
            assertThat(call.getArgument(1, SqlParameterSource.class).getValue("organisationId")).isEqualTo(organisation);
            events.add("SCOPE"); return scopeValid;
        });
        when(dispatch.admitCreate(anyString(), any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
            assertThat(TenantRequestContext.current().orElseThrow()).isEqualTo(new TenantRequestContext.ScopeValue(null, organisation));
            var current = call.getArgument(1, GatewayWorkQueue.Claim.class);
            events.add("ADMIT_CREATE"); marker = true;
            return suppliedAdmission == null ? admission(current.binding(), current.id(), account, false, "RESERVED", "ACTIVE", current.leaseUntil()) : suppliedAdmission;
        });
        when(jdbc.query(anyString(), any(SqlParameterSource.class), ArgumentMatchers.<ResultSetExtractor<Object>>any())).thenAnswer(call -> {
            assertThat(marker).isTrue(); events.add("LOAD_PAYER");
            String sql = call.getArgument(0); var p = call.getArgument(1, SqlParameterSource.class);
            assertThat(sql).contains("work.organisation_id=:organisationId", "work.school_id=:schoolId", "work.payment_provider_config_id=:providerId",
                    "work.provider_code=:providerCode", "work.provider_environment=:environment", "work.merchant_account_reference=:merchant",
                    "work.credentials_secret_reference=:credentialsReference", "work.status='LEASED'", "work.lease_token=:leaseToken",
                    "work.lease_started_at=:leaseStart", "work.lease_until=:leaseUntil", "work.attempt_count=:attemptNumber",
                    "work.create_dispatched_by_lease_token=:leaseToken", "work.create_dispatched_at=:dispatchedAt",
                    "attempt.learner_fee_account_id=:accountId", "attempt.checkout_reference=:attemptId", "attempt.amount=:amount",
                    "attempt.currency_code=:currency", "attempt.return_uri=:returnUri", "attempt.created_at=:createdAt",
                    "attempt.expires_at=:expiresAt", "attempt.row_version=:attemptVersion", "FOR SHARE OF work,attempt");
            assertThat(p.getValue("organisationId")).isEqualTo(organisation); assertThat(p.getValue("schoolId")).isEqualTo(school);
            assertThat(p.getValue("providerId")).isEqualTo(provider); assertThat(p.getValue("attemptId")).isEqualTo(attempt);
            assertThat(p.getValue("accountId")).isEqualTo(account); assertThat(p.getValue("leaseToken")).isEqualTo(claim.token());
            assertThat(p.getValue("leaseStart")).isEqualTo(GatewayExactInstant.parameter(claim.startedAt()));
            assertThat(p.getValue("leaseUntil")).isEqualTo(GatewayExactInstant.parameter(claim.leaseUntil()));
            assertThat(p.getValue("attemptNumber")).isEqualTo(1); assertThat(p.getValue("dispatchedAt")).isEqualTo(GatewayExactInstant.parameter(NOW));
            var row = mock(ResultSet.class); when(row.next()).thenReturn(payerPresent, duplicatePayer);
            when(row.getString("payer_phone_ciphertext")).thenReturn(encrypted); when(row.getString("payer_phone_nonce")).thenReturn(nonce);
            return call.getArgument(2, ResultSetExtractor.class).extractData(row);
        });
        when(cipher.decrypt(any(), anyString())).thenAnswer(call -> {
            assertThat(marker).isTrue(); assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(call.getArgument(0, SensitiveValueCipher.EncryptedValue.class)).isEqualTo(new SensitiveValueCipher.EncryptedValue(encrypted, nonce));
            assertThat(call.getArgument(1, String.class)).isEqualTo(GatewayReservationService.associatedData(organisation, school, account, attempt));
            events.add("DECRYPT"); return plaintext;
        });
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), ArgumentMatchers.<RowMapper<Instant>>any())).thenAnswer(call -> {
            assertThat(call.getArgument(0, String.class)).isEqualTo("SELECT clock_timestamp()"); events.add("DB_CLOCK");
            var row = mock(ResultSet.class); when(row.getObject(1, OffsetDateTime.class)).thenReturn(databaseNow.atOffset(java.time.ZoneOffset.UTC));
            return call.getArgument(2, RowMapper.class).mapRow(row, 0);
        });
    }

    private void configure(String environment) {
        when(registry.creationEnabled()).thenReturn(true); when(registry.workerEnabled()).thenReturn(true);
        when(registry.findRoute(ROUTE)).thenReturn(Optional.of(new GatewayProviderRegistry.Route(ROUTE, organisation, school, provider,
                "CASHFREE", CashfreePaymentProviderAdapter.Environment.valueOf(environment), "synthetic_merchant", "synthetic:disabled",
                "env:APP_FINANCE_GATEWAY_CASHFREE_" + environment + "_CLIENT_ID", "env:APP_FINANCE_GATEWAY_CASHFREE_" + environment + "_CLIENT_SECRET")));
    }
    private GatewayAttemptDispatch.CreateAdmission admission(GatewayWorkQueue.ProviderBinding currentBinding, UUID id, UUID feeAccount,
            boolean dispatched, String status, String accountStatus, Instant leaseUntil) {
        return new GatewayAttemptDispatch.CreateAdmission(snapshot(currentBinding, id, feeAccount, dispatched, status, accountStatus, leaseUntil), book(), NOW);
    }
    private GatewayAttemptDispatch.ReadRecovery snapshot(GatewayWorkQueue.ProviderBinding currentBinding, UUID id, UUID feeAccount,
            boolean dispatched, String status, String accountStatus, Instant leaseUntil) {
        return new GatewayAttemptDispatch.ReadRecovery(id, feeAccount, id, currentBinding, new BigDecimal("750.00"), "INR", status, accountStatus,
                "Asia/Kolkata", createdAt.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate(), createdAt, expiry, returnUri(id), 0, leaseUntil, dispatched);
    }
    private GatewayCheckoutReadiness.ReadyBook book() { return new GatewayCheckoutReadiness.ReadyBook(provider, organisation, school); }
    private GatewayWorkQueue.Claim claim(GatewayWorkQueue.ProviderBinding currentBinding) {
        return new GatewayWorkQueue.Claim(claim.kind(), attempt, currentBinding, claim.token(), claim.startedAt(), claim.leaseUntil(), claim.attemptNumber(), false);
    }
    private static GatewayWorkQueue.ProviderBinding binding(UUID organisation, UUID school, UUID provider, String code,
            String environment, String merchant, String credentialReference) {
        return new GatewayWorkQueue.ProviderBinding(organisation, school, provider, code, environment, merchant, credentialReference);
    }
    private static URI returnUri(UUID id) { return URI.create("https://synthetic-school.example.invalid/family/payments/" + id); }
    private void rejects(GatewayWorkQueue.Claim supplied) {
        assertThatThrownBy(() -> service.admit(ROUTE, supplied)).isInstanceOf(IllegalStateException.class).hasMessage(ERROR).hasNoCause();
    }
}
