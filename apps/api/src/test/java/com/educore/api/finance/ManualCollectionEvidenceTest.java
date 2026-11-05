package com.educore.api.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;

import com.educore.api.common.api.ApiException;
import com.educore.api.tenancy.TenantRequestContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ManualCollectionEvidenceTest {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final ManualCollectionEvidence service = new ManualCollectionEvidence(jdbc);
    private final ManualCollectionEvidence.ValueScope scope = new ManualCollectionEvidence.ValueScope(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Asia/Kolkata");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 10);
    private static final Instant ACTUAL = Instant.parse("2026-10-10T04:31:00Z");

    @AfterEach void clearTransactionState() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    @Test void legacyCashRetainsKnownDayWithoutInventingActualReceivedMidnight() {
        var evidence = service.prepare(scope, "CASH", null, input(null, DAY, null, null, null));
        assertThat(evidence.actualReceivedAt()).isNull();
        assertThat(evidence.receivedTimePrecision()).isEqualTo("DATE_ONLY");
        assertThat(evidence.originalReceivedOn()).isEqualTo(DAY);
        assertThat(evidence.postingOn()).isEqualTo(DAY);
        assertThat(evidence.verificationKind()).isEqualTo("CASH_COUNTED");
        assertThat(evidence.bankCreditReference()).isNull();
        verifyNoInteractions(jdbc);
    }

    @Test void modernCashRetainsActualInstantAndSeparateReviewedPostingDay() {
        var evidence = service.prepare(scope, "CASH", "Synthetic office reference",
                input(ACTUAL, DAY, DAY.plusDays(1), null, "Synthetic reviewed open-date posting"));
        assertThat(evidence.actualReceivedAt()).isEqualTo(ACTUAL);
        assertThat(evidence.receivedTimePrecision()).isEqualTo("INSTANT");
        assertThat(evidence.originalReceivedOn()).isEqualTo(DAY);
        assertThat(evidence.postingOn()).isEqualTo(DAY.plusDays(1));
        assertThat(evidence.receiptReference()).isEqualTo("Synthetic office reference");
        verifyNoInteractions(jdbc);
    }

    @Test void bankAndUpiRequireExplicitCreditSourceActualTimeAndPositiveAttestation() {
        for (String method : List.of("SCHOOL_UPI", "BANK_TRANSFER")) {
            for (var bad : List.of(
                    input(ACTUAL, DAY, DAY, null, null),
                    input(null, DAY, DAY, credit("BANK_APP_CREDIT", true, "SYN_CREDIT_001"), null),
                    input(ACTUAL, DAY, DAY, credit("BANK_APP_CREDIT", false, "SYN_CREDIT_001"), null),
                    input(ACTUAL, DAY, DAY, credit("PAYER_SCREENSHOT", true, "SYN_CREDIT_001"), null),
                    input(ACTUAL, DAY, DAY, credit("BROWSER_SUCCESS", true, "SYN_CREDIT_001"), null))) {
                assertThatThrownBy(() -> service.prepare(scope, method, "SYN_CREDIT_001", bad))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
        verifyNoInteractions(jdbc);
    }

    @Test void actualCreditedEntryCanReplaceMissingUtrButCannotBeAnUnidentifiedCredit() {
        var evidence = service.prepare(scope, "SCHOOL_UPI", null,
                input(ACTUAL, DAY, DAY, credit("BANK_STATEMENT_CREDIT", true, "SYN_STATEMENT_ENTRY_001"), null));
        assertThat(evidence.receiptReference()).isEqualTo("SYN_STATEMENT_ENTRY_001");
        assertThat(evidence.normalizedBankReference()).isEqualTo("SYN_STATEMENT_ENTRY_001");
        assertThatThrownBy(() -> service.prepare(scope, "SCHOOL_UPI", null,
                input(ACTUAL, DAY, DAY, credit("BANK_APP_CREDIT", true, null), null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("credited bank entry");
        verifyNoInteractions(jdbc);
    }

    @Test void missingCreditedReferenceFallsBackToTheSameActualPaymentReference() {
        var evidence = service.prepare(scope, "BANK_TRANSFER", "syn_credit_001",
                input(ACTUAL, DAY, DAY, credit("BANK_APP_CREDIT", true, null), null));
        assertThat(evidence.bankCreditReference()).isEqualTo("syn_credit_001");
        assertThat(evidence.normalizedBankReference()).isEqualTo("SYN_CREDIT_001");
    }

    @Test void crossMethodReferenceCanonicalizationCannotLaunderASecondIdentity() {
        var evidence = service.prepare(scope, "SCHOOL_UPI", "syn credit_001",
                input(ACTUAL, DAY, DAY, credit("BANK_APP_CREDIT", true, "SYN\tCREDIT_001"), null));
        assertThat(evidence.normalizedBankReference()).isEqualTo("SYNCREDIT_001");
        assertThatThrownBy(() -> service.prepare(scope, "BANK_TRANSFER", "SYN_CREDIT_001",
                input(ACTUAL, DAY, DAY, credit("BANK_STATEMENT_CREDIT", true, "SYN_CREDIT_002"), null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("same credited entry");
    }

    @Test void exactAsciiNormalizationPreservesMeaningfulPunctuationAndNulls() {
        assertThat(ManualCollectionEvidence.normalizeBankReference(" syn\tbank\n_01\r/ab\f-cd\u000B:ef "))
                .isEqualTo("SYNBANK_01/AB-CD:EF");
        assertThat(ManualCollectionEvidence.normalizeBankReference("syn-01"))
                .isNotEqualTo(ManualCollectionEvidence.normalizeBankReference("syn01"));
        assertThat(ManualCollectionEvidence.normalizeBankReference("  \t\r\n")).isNull();
        assertThat(ManualCollectionEvidence.normalizeBankReference(null)).isNull();
    }

    @Test void nonAsciiHomoglyphsControlsUnboundedAndScreenshotReferenceTextAreRejected() {
        for (String reference : List.of("SYN_СREDIT_001", "SYN\u0000CREDIT", "SYN\nCREDIT", "SYN_CREDIT_" + "x".repeat(120),
                "https://payer.test/screenshot", " ht tp://payer.test/screenshot ", "SYN<credit>")) {
            assertThatThrownBy(() -> service.prepare(scope, "BANK_TRANSFER", reference,
                    input(ACTUAL, DAY, DAY, credit("BANK_APP_CREDIT", true, null), null)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void schoolLocalReceivedDayIsNotUtcOrCurrentPostingDay() {
        Instant priorUtcDay = Instant.parse("2026-10-09T20:30:00Z");
        var evidence = service.prepare(scope, "CASH", null, input(priorUtcDay, DAY, DAY, null, null));
        assertThat(evidence.originalReceivedOn()).isEqualTo(DAY);
        assertThatThrownBy(() -> service.prepare(scope, "CASH", null,
                input(priorUtcDay, DAY.minusDays(1), DAY, null, "Synthetic invalid received day")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("school-local day");
    }

    @Test void differentPostingDayRequiresBoundedSafeExplicitReviewReason() {
        for (String reason : new String[] { null, " ", "ab", "x".repeat(241), "Synthetic\nunsafe reason" }) {
            assertThatThrownBy(() -> service.prepare(scope, "CASH", null,
                    input(ACTUAL, DAY, DAY.plusDays(1), null, reason)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.prepare(scope, "CASH", null,
                input(ACTUAL, DAY, DAY.minusDays(1), null, "Synthetic invalid backdated posting")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void chequeAndGatewayNeverReceiveAnInventedManualCashOrBankAttestation() {
        for (String method : List.of("CHEQUE", "ONLINE_PROVIDER", "", "cash")) {
            assertThatThrownBy(() -> service.prepare(scope, method, null, input(ACTUAL, DAY, DAY, null, null)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.prepare(scope, "CASH", null,
                input(ACTUAL, DAY, DAY, credit("BANK_APP_CREDIT", true, "SYN_CREDIT_001"), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void fingerprintIncludesReviewedSourceTermsWithoutRecordingTimeOrDelimiterAmbiguity() {
        var one = service.prepare(scope, "CASH", null, input(ACTUAL, DAY, DAY, null, null));
        var equal = service.prepare(scope, "CASH", null, input(ACTUAL, DAY, DAY, null, null));
        var moved = service.prepare(scope, "CASH", null,
                input(ACTUAL, DAY, DAY.plusDays(1), null, "Synthetic reviewed posting"));
        assertThat(ManualCollectionEvidence.fingerprintMaterial(one)).isEqualTo(ManualCollectionEvidence.fingerprintMaterial(equal));
        assertThat(ManualCollectionEvidence.fingerprintMaterial(one)).isNotEqualTo(ManualCollectionEvidence.fingerprintMaterial(moved));
        assertThat(ManualCollectionEvidence.fingerprintMaterial(one)).contains("2026-10-10T04:31:00Z", "CASH_COUNTED");
    }

    @Test void privateInputsPreparedValuesAndActorScopeDoNotExposeReferencesInDefaultText() {
        var credit = credit("BANK_APP_CREDIT", true, "SYN_PRIVATE_CREDIT");
        var input = input(ACTUAL, DAY, DAY, credit, null);
        var prepared = service.prepare(scope, "SCHOOL_UPI", "SYN_PRIVATE_CREDIT", input);
        for (Object value : List.of(credit, input, prepared, scope)) {
            assertThat(value.toString()).doesNotContain("SYN_PRIVATE_CREDIT", scope.membershipId().toString(), ACTUAL.toString());
        }
    }

    @Test void wrongMissingBackgroundOrReadOnlyTenantContextFailsBeforeDatabaseAccess() {
        var prepared = service.prepare(scope, "CASH", null, input(null, DAY, DAY, null, null));
        assertDenied(() -> service.assertEligible(scope, prepared));
        try (var ignored = TenantRequestContext.open(UUID.randomUUID(), UUID.randomUUID())) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertDenied(() -> service.assertEligible(scope, prepared));
        }
        try (var ignored = TenantRequestContext.open(null, scope.organisationId())) {
            assertDenied(() -> service.assertEligible(scope, prepared));
        }
        try (var ignored = TenantRequestContext.open(UUID.randomUUID(), scope.organisationId())) {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            assertDenied(() -> service.assertEligible(scope, prepared));
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            assertDenied(() -> service.record(scope, UUID.randomUUID(), prepared));
        }
        verifyNoInteractions(jdbc);
    }

    @Test void fabricatedPreparedPrecisionCannotBypassCanonicalizationBeforeDatabaseAccess() {
        var forged = new ManualCollectionEvidence.Prepared("CASH", null, null, DAY, DAY,
                "INSTANT", "CASH_COUNTED", null, null, null);
        assertThatThrownBy(() -> service.assertEligible(scope, forged)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @Test void settlementReservedBankCreditIsRejectedBeforeTheCommonReceiptWriterForBothManualMethods() {
        for (String method : List.of("BANK_TRANSFER", "SCHOOL_UPI")) {
            var prepared = service.prepare(scope, method, "SYN_SETTLEMENT_RESERVED",
                    input(ACTUAL, DAY, DAY, credit("BANK_APP_CREDIT", true, null), null));
            mockEligibleBankActor(true);
            try (var ignored = TenantRequestContext.open(UUID.randomUUID(), scope.organisationId())) {
                TransactionSynchronizationManager.setActualTransactionActive(true);
                assertThatThrownBy(() -> service.assertEligible(scope, prepared)).isInstanceOfSatisfying(ApiException.class,
                        error -> {
                            assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT);
                            assertThat(error.code()).isEqualTo("MANUAL_BANK_CREDIT_RESERVED_FOR_SETTLEMENT");
                            assertThat(error.getMessage()).doesNotContain("SYN_SETTLEMENT_RESERVED");
                        });
            }
        }
    }

    @Test void independentlyRejectedSettlementEvidenceDoesNotBlockOtherwiseEligibleManualCredit() {
        var prepared = service.prepare(scope, "BANK_TRANSFER", "SYN_RELEASED_EVIDENCE",
                input(ACTUAL, DAY, DAY, credit("BANK_STATEMENT_CREDIT", true, null), null));
        mockEligibleBankActor(false);
        try (var ignored = TenantRequestContext.open(UUID.randomUUID(), scope.organisationId())) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            service.assertEligible(scope, prepared);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void mockEligibleBankActor(boolean reserved) {
        when(jdbc.queryForObject(contains("current_app_organisation_id"), any(SqlParameterSource.class), eq(Boolean.class))).thenReturn(true);
        when(jdbc.query(contains("FROM memberships membership"), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(scope.membershipId()));
        when(jdbc.queryForObject(contains("SELECT clock_timestamp()"), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(ACTUAL.plusSeconds(60));
        when(jdbc.queryForObject(contains("SELECT EXISTS(SELECT 1 FROM fee_day_closes"), any(SqlParameterSource.class), eq(Boolean.class))).thenReturn(false);
        when(jdbc.queryForObject(contains("SELECT EXISTS(SELECT 1 FROM fee_payments"), any(SqlParameterSource.class), eq(Boolean.class))).thenReturn(false);
        when(jdbc.queryForObject(contains("manual-bank-settlement-reference"), any(SqlParameterSource.class), eq(Boolean.class))).thenReturn(reserved);
    }

    private static ManualCollectionEvidence.EvidenceInput input(Instant actual, LocalDate original, LocalDate posting,
            ManualCollectionEvidence.BankCreditVerification credit, String reason) {
        return new ManualCollectionEvidence.EvidenceInput(actual, original, posting, credit, reason);
    }
    private static ManualCollectionEvidence.BankCreditVerification credit(String kind, boolean confirmed, String reference) {
        return new ManualCollectionEvidence.BankCreditVerification(kind, confirmed, reference);
    }
    private static void assertDenied(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
                failure -> assertThat(failure.status()).isEqualTo(HttpStatus.FORBIDDEN));
    }
}
