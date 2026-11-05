package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.educore.api.common.api.ApiException;
import com.educore.api.config.ApplicationProperties;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SensitiveValueCipher;
import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Original-account replay only; no new intent, current-session choice or merchant operation. */
class GatewayReservationReplayTest {
    private final UUID org = UUID.randomUUID(), school = UUID.randomUUID(), learner = UUID.randomUUID(),
            account = UUID.randomUUID(), attempt = UUID.randomUUID(), requestId = UUID.randomUUID(), member = UUID.randomUUID(), charge = UUID.randomUUID();
    private final AuthenticatedUser parent = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "synthetic-replay", "synthetic-replay@example.invalid",
            "Synthetic replay parent", false, org, Set.of(member), Set.of("portal.guardian"), List.of());
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final FamilyFinanceService family = mock(FamilyFinanceService.class);
    private final SecurityAuditService audit = mock(SecurityAuditService.class);
    private final SensitiveValueCipher cipher = mock(SensitiveValueCipher.class);
    private final ApplicationProperties properties = mock(ApplicationProperties.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-15T06:00:00Z"), ZoneOffset.UTC);
    private final GatewayReservationService service = new GatewayReservationService(jdbc, family, audit, cipher, properties, clock);
    private final Instant originalExpiry = Instant.parse("2025-12-10T06:30:00.123456Z");
    private TenantRequestContext.Scope context;
    private boolean databaseOrg = true, originalFound = true, linked = true, originalAccountFound = true;
    private UUID storedRequest;
    private String storedFingerprint;
    private String selectionMode;
    private byte[] decrypted;

    @BeforeEach void ready() throws Exception {
        context = TenantRequestContext.open(parent.userId(), org);
        TransactionSynchronizationManager.setActualTransactionActive(true); TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        storedRequest = requestId; selectionMode = "CURRENT_SESSION"; storedFingerprint = fingerprint(account, charge, "750.00"); decrypted = "0000000000".getBytes(StandardCharsets.UTF_8);
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Boolean.class))).thenAnswer(call -> {
            assertThat(call.getArgument(0, String.class)).isEqualTo("SELECT current_app_organisation_id()=:organisationId"); return databaseOrg;
        });
        when(jdbc.query(anyString(), any(SqlParameterSource.class), ArgumentMatchers.<ResultSetExtractor<Object>>any())).thenAnswer(call -> {
            String sql = call.getArgument(0); SqlParameterSource p = call.getArgument(1);
            assertThat(sql).contains("id=:attemptId", "organisation_id=:organisationId", "initiated_by_user_id=:userId",
                    "current_app_organisation_id()=:organisationId", "current_app_user_id()=:userId");
            assertThat(p.getValue("attemptId")).isEqualTo(attempt); assertThat(p.getValue("userId")).isEqualTo(parent.userId());
            var row = mock(ResultSet.class); when(row.next()).thenReturn(originalFound, false);
            when(row.getObject("id", UUID.class)).thenReturn(attempt); when(row.getObject("learner_fee_account_id", UUID.class)).thenReturn(account);
            when(row.getObject("client_request_id", UUID.class)).thenReturn(storedRequest); when(row.getString("status")).thenReturn("CHECKOUT_PENDING");
            when(row.getBigDecimal("amount")).thenReturn(new BigDecimal("750.00")); when(row.getString("currency_code")).thenReturn("INR");
            when(row.getObject("original_business_date", LocalDate.class)).thenReturn(LocalDate.parse("2025-12-10"));
            when(row.getObject("expires_at", OffsetDateTime.class)).thenReturn(originalExpiry.atOffset(ZoneOffset.UTC)); when(row.getLong("row_version")).thenReturn(7L);
            when(row.getString("request_fingerprint")).thenReturn(storedFingerprint); when(row.getString("payer_phone_ciphertext")).thenReturn("SYNTHETIC_PRIVATE_REPLAY_CIPHERTEXT");
            when(row.getString("payer_phone_nonce")).thenReturn("A".repeat(16));
            when(row.getString("account_selection_mode")).thenReturn(selectionMode); return call.getArgument(2, ResultSetExtractor.class).extractData(row);
        });
        when(jdbc.query(anyString(), any(SqlParameterSource.class), ArgumentMatchers.<RowMapper<Object>>any())).thenAnswer(call -> {
            String sql = call.getArgument(0); SqlParameterSource p = call.getArgument(1); var mapper = call.getArgument(2, RowMapper.class);
            var row = mock(ResultSet.class); assertThat(p.getValue("learnerId")).isEqualTo(learner); assertThat(p.getValue("organisationId")).isEqualTo(org);
            if (sql.contains("SELECT membership.school_id")) {
                assertThat(sql).contains("membership.id IN(:membershipIds)", "current_app_user_id()=:userId", "clock_timestamp()", "capability.capability_code='portal.guardian'");
                assertThat(p.getValue("membershipIds")).isEqualTo(parent.membershipIds()); if (!linked) return List.of();
                when(row.getObject("school_id", UUID.class)).thenReturn(school); when(row.getObject("membership_id", UUID.class)).thenReturn(member);
            } else {
                assertThat(sql).contains("account.learner_id=:learnerId", "account.id=:accountId", "account.school_id=:schoolId")
                        .doesNotContain("student_enrolments", "account.status='ACTIVE'");
                assertThat(p.getValue("accountId")).isEqualTo(account); assertThat(p.getValue("schoolId")).isEqualTo(school);
                if (!originalAccountFound) return List.of();
                when(row.getObject("id", UUID.class)).thenReturn(account); when(row.getString("status")).thenReturn("CLOSED");
                when(row.getString("currency_code")).thenReturn("INR"); when(row.getString("session_name")).thenReturn("Synthetic earlier session");
            }
            return List.of(mapper.mapRow(row, 0));
        });
        when(cipher.decrypt(any(), anyString())).thenReturn(decrypted);
    }

    @AfterEach void cleanup() {
        context.close(); TransactionSynchronizationManager.clear(); assertThat(TenantRequestContext.current()).isEmpty();
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }

    @Test void exactHistoricalRetryReturnsOriginalClosedAccountTermsWithoutLatestAccountLookupOrMutation() {
        var result = service.replay(parent, learner, attempt, request(requestId, charge, "750.00", "0000000000"));
        assertThat(result).isEqualTo(new GatewayReservationService.PlannedAttempt(attempt, "CHECKOUT_PENDING", new BigDecimal("750.00"), "INR",
                LocalDate.parse("2025-12-10"), originalExpiry, 7));
        verify(cipher).decrypt(any(), eq(GatewayReservationService.associatedData(org, school, account, attempt)));
        assertThat(decrypted).containsOnly((byte) 0); verifyNoInteractions(family, audit, properties);
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test void retryCannotImpersonateParentFromSystemOrForeignActorContext() {
        for (UUID actor : java.util.Arrays.asList(null, UUID.randomUUID())) {
            try (var ignored = TenantRequestContext.open(actor, org)) {
                assertThatThrownBy(() -> replay()).isInstanceOf(ApiException.class);
                assertThat(TenantRequestContext.current().orElseThrow().userId()).isEqualTo(actor);
            }
        }
        verifyNoInteractions(jdbc, cipher, family, audit, properties);
    }

    @Test void retryRequiresActualWritableTransactionAndSessionMembershipIdentity() {
        TransactionSynchronizationManager.setActualTransactionActive(false); assertThatThrownBy(this::replay).isInstanceOf(ApiException.class);
        TransactionSynchronizationManager.setActualTransactionActive(true); TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThatThrownBy(this::replay).isInstanceOf(IllegalStateException.class);
        var missingMembership = new AuthenticatedUser(parent.userId(), parent.sessionId(), parent.loginIdentifier(), parent.email(), parent.displayName(), false,
                org, Set.of(), parent.capabilities(), List.of());
        assertThatThrownBy(() -> service.replay(missingMembership, learner, attempt, request(requestId, charge, "750.00", "0000000000"))).isInstanceOf(ApiException.class);
        verifyNoInteractions(jdbc, cipher, family, audit, properties);
    }

    @Test void databaseOrganisationMismatchCannotLookupOriginalPrivateEvidence() {
        databaseOrg = false; assertThatThrownBy(this::replay).isInstanceOf(ApiException.class);
        verify(jdbc, never()).query(anyString(), any(SqlParameterSource.class), ArgumentMatchers.<ResultSetExtractor<Object>>any());
        verifyNoInteractions(cipher, family, audit, properties);
    }

    @Test void missingOriginalOrWrongInitiatorCannotExposeOrDecryptHistoricalAttempt() {
        originalFound = false; assertThatThrownBy(this::replay).isInstanceOf(ApiException.class);
        verify(jdbc, never()).query(anyString(), any(SqlParameterSource.class), ArgumentMatchers.<RowMapper<Object>>any());
        verifyNoInteractions(cipher, family, audit, properties);
    }

    @Test void revokedCurrentRelationshipOrSessionGrantDeniesReplayOfOtherwiseValidOldAccount() {
        linked = false; assertThatThrownBy(this::replay).isInstanceOf(ApiException.class); verifyNoInteractions(cipher, family, audit, properties);
    }

    @Test void differentChildOrSchoolAccountDeniedBySharedActualAccountPolicyBeforeDecryption() {
        originalAccountFound = false; assertThatThrownBy(this::replay).isInstanceOf(ApiException.class); verifyNoInteractions(cipher, family, audit, properties);
    }

    @Test void changedRequestIdentityCannotReuseAnotherOriginalAttempt() {
        storedRequest = UUID.randomUUID(); reused(this::replay); verifyNoInteractions(cipher, family, audit, properties);
    }

    @Test void explicitHistoricalRetryKeepsOriginalModeAccountAndTermsWithoutChoosingTodaysAccount() throws Exception {
        selectionMode = "EXPLICIT_ACCOUNT";
        storedFingerprint = explicitFingerprint(account, charge, "750.00");
        var result = service.replay(parent, learner, attempt, new GatewayReservationService.PrepareRequest(requestId,
                List.of(new GatewayReservationService.ChargeSelection(charge, new BigDecimal("750.00"))), "0000000000", account));
        assertThat(result.id()).isEqualTo(attempt);
        assertThat(decrypted).containsOnly((byte) 0);
        verifyNoInteractions(family, audit, properties);
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test void legacyRetryCannotBeReinterpretedAsExplicitEvenWhenTheAccountAndMoneyMatch() {
        reused(() -> service.replay(parent, learner, attempt, new GatewayReservationService.PrepareRequest(requestId,
                List.of(new GatewayReservationService.ChargeSelection(charge, new BigDecimal("750.00"))), "0000000000", account)));
        verifyNoInteractions(cipher, family, audit, properties);
    }

    @Test void explicitRetryCannotOmitOrChangeTheOriginallySelectedAccount() throws Exception {
        selectionMode = "EXPLICIT_ACCOUNT"; storedFingerprint = explicitFingerprint(account, charge, "750.00");
        reused(this::replay);
        reused(() -> service.replay(parent, learner, attempt, new GatewayReservationService.PrepareRequest(requestId,
                List.of(new GatewayReservationService.ChargeSelection(charge, new BigDecimal("750.00"))), "0000000000", UUID.randomUUID())));
        verifyNoInteractions(cipher, family, audit, properties);
    }

    @Test void changedAmountCannotMutateOriginalReservationOrChooseNewCurrentSession() {
        reused(() -> service.replay(parent, learner, attempt, request(requestId, charge, "700.00", "0000000000")));
        verifyNoInteractions(cipher, family, audit, properties); verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test void changedChargeEvenWithSameTotalCannotReplayOriginalFrozenAllocationFingerprint() {
        reused(() -> service.replay(parent, learner, attempt, request(requestId, UUID.randomUUID(), "750.00", "0000000000")));
        verifyNoInteractions(cipher, family, audit, properties);
    }

    @Test void changedPhoneFailsConstantTimeComparisonAndZerosDecodedOriginal() {
        reused(() -> service.replay(parent, learner, attempt, request(requestId, charge, "750.00", "0000000001")));
        assertThat(decrypted).containsOnly((byte) 0); verifyNoInteractions(family, audit, properties);
    }

    @Test void cipherFailureIsSanitizedWithoutCausePrivateDiagnosticsOrFallbackReservation() {
        when(cipher.decrypt(any(), anyString())).thenThrow(new IllegalStateException("Synthetic private key or phone diagnostic"));
        assertThatThrownBy(this::replay).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.code()).isEqualTo("ONLINE_REPLAY_UNAVAILABLE"); assertThat(error.getMessage()).doesNotContain("key", "phone"); assertThat(error.getCause()).isNull();
        });
        verifyNoInteractions(family, audit, properties); verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test void unavailableDecodedValueCannotBecomeSuccessfulReplay() {
        when(cipher.decrypt(any(), anyString())).thenReturn(null); reused(this::replay); verifyNoInteractions(family, audit, properties);
    }

    private GatewayReservationService.PlannedAttempt replay() { return service.replay(parent, learner, attempt, request(requestId, charge, "750.00", "0000000000")); }
    private static GatewayReservationService.PrepareRequest request(UUID id, UUID charge, String amount, String phone) {
        return new GatewayReservationService.PrepareRequest(id, List.of(new GatewayReservationService.ChargeSelection(charge, new BigDecimal(amount))), phone);
    }
    private static void reused(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("ONLINE_REQUEST_REUSED"));
    }
    private static String fingerprint(UUID account, UUID charge, String amount) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((account + "|INR|" + charge + ":" + new BigDecimal(amount).setScale(2).toPlainString()).getBytes(StandardCharsets.UTF_8)));
    }
    private static String explicitFingerprint(UUID account, UUID charge, String amount) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("v2|EXPLICIT_ACCOUNT|" + account + "|INR|" + charge + ":"
                + new BigDecimal(amount).setScale(2).toPlainString()).getBytes(StandardCharsets.UTF_8)));
    }
}
