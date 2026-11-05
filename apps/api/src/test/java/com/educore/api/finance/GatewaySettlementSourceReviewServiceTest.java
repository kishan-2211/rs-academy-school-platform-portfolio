package com.educore.api.finance;

import static com.educore.api.finance.GatewaySettlementSourceReviewFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.educore.api.accounting.AccountingModuleBridge;
import com.educore.api.accounting.AccountingModuleBridge.FeeJournalEvidence;
import com.educore.api.accounting.AccountingModuleBridge.FeeJournalLineEvidence;
import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Service/JDBC mocks only; no claim of PostgreSQL, RLS, transaction or provider verification. */
class GatewaySettlementSourceReviewServiceTest {
    @Test void exactCaptureReceiptAndOriginalProofIdentifyClosedHistoricalSourceWithoutCurrentAccountFilters() throws Exception {
        var f = new Fixture(); var result = f.review(); var row = result.items().getFirst();
        assertThat(row.status()).isEqualTo("SOURCE_IDENTIFIED"); assertThat(row.source().receiptNumber()).isEqualTo("SYNTHETIC-R-001");
        assertThat(row.source().grossAmount()).isEqualTo("100.00"); assertThat(row.source().clearingAccountId()).isEqualTo(f.clearing);
        assertThat(row.source().originalPostingDate()).isEqualTo(f.date); assertThat(row.source().paymentId()).isEqualTo(f.payment);
        verify(f.fees).verifyOriginalPaymentPosting(f.user, f.scope.school, f.payment);
        verify(f.accounting).feeJournalEvidence(f.user, f.scope.school, List.of(f.journalId));
        assertThat(f.sql).contains("capture.organisation_id=:organisationId", "capture.school_id=:schoolId",
                "capture.payment_provider_config_id=:providerId", "capture.provider_code=:providerCode",
                "capture.provider_environment=:environment", "capture.merchant_account_reference=:merchant",
                "capture.order_reference=:orderReference", "capture.payment_reference=:paymentReference", "resolution.resolution_type='POSTED'", "LIMIT 2")
                .doesNotContain("account.status", "current_session", "accounting_", "FOR UPDATE", "pg_advisory", "INSERT", "UPDATE", "DELETE");
        assertThat(f.params).containsEntry("organisationId", f.scope.org).containsEntry("schoolId", f.scope.school)
                .containsEntry("providerId", f.scope.binding.providerConfigId()).containsEntry("merchant", "synthetic_merchant");
        assertThat(GatewaySettlementSourceReviewService.class.getDeclaredMethod("review", AuthenticatedUser.class, UUID.class, UUID.class, int.class, int.class)
                .getAnnotation(Transactional.class).readOnly()).isTrue();
    }

    @Test void noScopedCaptureOrAmbiguousCaptureCannotBeGuessedFromAmountOrStudent() {
        var f = new Fixture(); f.rows.clear(); assertReason(f.review(), "SOURCE_NOT_FOUND_OR_OUT_OF_SCOPE");
        f.rows.add(f.row()); f.rows.add(f.row()); assertReason(f.review(), "AMBIGUOUS_PAYMENT_SOURCE");
        verifyNoInteractions(f.fees, f.accounting);
    }

    @Test void unresolvedCaptureMissingNumberedReceiptOrWrongResolutionDateRemainReview() {
        for (String field : List.of("payment_id", "ledger_entry_id", "receipt_number", "open_date")) {
            var f = new Fixture(); f.rows.getFirst().remove(field); assertReason(f.review(), "UNRESOLVED_NUMBERED_RECEIPT");
            verifyNoInteractions(f.fees, f.accounting);
        }
        var f = new Fixture(); f.rows.getFirst().put("payment_status", "REVERSED");
        assertReason(f.review(), "UNRESOLVED_NUMBERED_RECEIPT"); verifyNoInteractions(f.fees, f.accounting);
    }

    @Test void captureAndReceiptGrossMustEqualExactReportedGrossNotProviderReportedNet() {
        for (String field : List.of("capture_amount", "receipt_amount")) {
            var f = new Fixture(); f.rows.getFirst().put(field, new BigDecimal("99.99"));
            assertReason(f.review(), "GROSS_MISMATCH"); verifyNoInteractions(f.fees, f.accounting);
        }
        var f = new Fixture(); f.rows.getFirst().put("capture_currency", "USD");
        assertReason(f.review(), "GROSS_MISMATCH"); verifyNoInteractions(f.fees, f.accounting);
    }

    @Test void invalidUnpostedOrReversedOriginalProofRemainsReviewButAuthorityAndInfrastructureFailuresPropagate() {
        var f = new Fixture(); doThrow(new ApiException(HttpStatus.CONFLICT, "FEE_ORIGINAL_PAYMENT_PROOF_INVALID", "Synthetic invalid proof"))
                .when(f.fees).verifyOriginalPaymentPosting(f.user, f.scope.school, f.payment);
        assertReason(f.review(), "ORIGINAL_POSTING_INVALID"); verifyNoInteractions(f.accounting);
        var denied = new ApiException(HttpStatus.FORBIDDEN, "FEE_ORIGINAL_PAYMENT_PROOF_SCOPE_DENIED", "Synthetic revoked scope");
        doThrow(denied).when(f.fees).verifyOriginalPaymentPosting(f.user, f.scope.school, f.payment);
        assertThatThrownBy(f::review).isSameAs(denied);
        var outage = new DataAccessResourceFailureException("Synthetic outage");
        doThrow(outage).when(f.fees).verifyOriginalPaymentPosting(f.user, f.scope.school, f.payment);
        assertThatThrownBy(f::review).isSameAs(outage);
    }

    @Test void sourceAllocationGrossAndPaymentIdentityMustMatchTheExactVerifiedReceipt() {
        var f = new Fixture(); var proof = f.proof();
        var altered = new FeeAccountingService.OriginalPaymentPostingProof(UUID.randomUUID(), proof.feeLedgerEntryId(), proof.postingItemId(),
                proof.postingBatchId(), proof.configurationId(), proof.accountingJournalId(), proof.originalFinancialYearId(), proof.originalAccountingPeriodId(),
                proof.originalPostingDate(), proof.accountingBasis(), proof.currencyCode(), proof.depositAccountId(), proof.receivableAccountId(), proof.incomeAccounts(), proof.allocations(), "a".repeat(64), "b".repeat(64));
        when(f.fees.verifyOriginalPaymentPosting(f.user, f.scope.school, f.payment)).thenReturn(altered);
        assertReason(f.review(), "ORIGINAL_POSTING_INVALID"); verifyNoInteractions(f.accounting);
    }

    @Test void legacyBankClearingIsExplicitReviewAndNeverDoubleCountedAsASecondBankReceipt() {
        var f = new Fixture(); f.category = "BANK"; assertReason(f.review(), "LEGACY_BANK_CLEARING_REVIEW");
        assertThat(f.review().items().getFirst().source()).isNull();
    }

    @Test void wrongClearingTypeCategoryOrDimensionsAreNotIdentifiedAsGatewayClearing() {
        var f = new Fixture(); f.category = "CASH"; assertReason(f.review(), "INVALID_CLEARING_IDENTITY");
        f.category = "OTHER"; f.accountType = "LIABILITY"; assertReason(f.review(), "INVALID_CLEARING_IDENTITY");
        f.accountType = "ASSET"; f.clearingLearner = UUID.randomUUID(); assertReason(f.review(), "INVALID_CLEARING_IDENTITY");
    }

    @Test void missingClearingEvidenceAndActualReversalAfterOriginalProofRemainReview() {
        var f = new Fixture(); f.journalMissing = true; assertReason(f.review(), "CLEARING_EVIDENCE_UNAVAILABLE");
        f.journalMissing = false; f.reversal = UUID.randomUUID(); assertReason(f.review(), "ORIGINAL_POSTING_INVALID");
        var denied = new ApiException(HttpStatus.FORBIDDEN, "ACCOUNTING_SCOPE_DENIED", "Synthetic revoked accounting scope");
        when(f.accounting.feeJournalEvidence(f.user, f.scope.school, List.of(f.journalId))).thenThrow(denied);
        assertThatThrownBy(f::review).isSameAs(denied);
    }

    @Test void fullChainFailureIsNotAnEmptySuccessfulReviewAndCannotReachSourceQueries() {
        var f = new Fixture(); var unavailable = GatewaySettlementSourceReviewEvidence.unavailable();
        when(f.settlements.completeEvents(f.user, f.scope.school, f.scope.request)).thenThrow(unavailable);
        assertThatThrownBy(f::review).isSameAs(unavailable); verifyNoInteractions(f.jdbc, f.fees, f.accounting);
    }

    @Test void duplicateAndUnsupportedRowsNeverReachCandidateProofAccountingOrProviderLookup() {
        var f = new Fixture(); when(f.settlements.completeEvents(f.user, f.scope.school, f.scope.request)).thenReturn(f.scope.chain(List.of(
                List.of(payment("synthetic_one", "synthetic_payment")), List.of(payment("synthetic_two", "synthetic_payment")))));
        assertThat(f.review().reviewRequiredRows()).isEqualTo(2); verifyNoInteractions(f.jdbc, f.fees, f.accounting);
    }

    private static void assertReason(GatewaySettlementSourceReviewModels.Review result, String reason) {
        assertThat(result.items().getFirst().status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.items().getFirst().reasons()).containsExactly(reason); assertThat(result.items().getFirst().source()).isNull();
    }

    private static final class Fixture {
        final GatewaySettlementSourceReviewFixtures scope = new GatewaySettlementSourceReviewFixtures();
        final GatewaySettlementService settlements = mock(GatewaySettlementService.class);
        final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        final FeeAccountingService fees = mock(FeeAccountingService.class); final AccountingModuleBridge accounting = mock(AccountingModuleBridge.class);
        final GatewaySettlementSourceReviewService service = new GatewaySettlementSourceReviewService(settlements, jdbc, fees, accounting);
        final UUID payment = UUID.randomUUID(), capture = UUID.randomUUID(), ledger = UUID.randomUUID(), clearing = UUID.randomUUID(), journalId = UUID.randomUUID(),
                batch = UUID.randomUUID(), item = UUID.randomUUID(), configuration = UUID.randomUUID(), year = UUID.randomUUID(), period = UUID.randomUUID();
        final AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "synthetic@example.test", null,
                "Synthetic reviewer", false, scope.org, Set.of(UUID.randomUUID()), Set.of("fees.read", "accounting.read"), List.of());
        final LocalDate date = LocalDate.of(2025, 4, 1); final List<Map<String, Object>> rows = new ArrayList<>();
        String sql, category = "OTHER", accountType = "ASSET"; Map<String, Object> params; UUID clearingLearner, reversal; boolean journalMissing;
        @SuppressWarnings({"rawtypes", "unchecked"}) Fixture() {
            when(settlements.completeEvents(user, scope.school, scope.request)).thenReturn(scope.chain(List.of(List.of(payment("synthetic_event", "synthetic_payment")))));
            rows.add(row()); when(fees.verifyOriginalPaymentPosting(user, scope.school, payment)).thenReturn(proof());
            when(accounting.feeJournalEvidence(user, scope.school, List.of(journalId))).thenAnswer(call -> journalMissing ? Map.of() : Map.of(journalId, journal()));
            when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenAnswer(call -> {
                sql = call.getArgument(0); params = new HashMap<>(((MapSqlParameterSource) call.getArgument(1)).getValues());
                var result = new ArrayList<>(); RowMapper mapper = call.getArgument(2);
                for (int index = 0; index < rows.size(); index++) {
                    var values = rows.get(index); var rs = mock(ResultSet.class);
                    when(rs.getObject(anyString(), any(Class.class))).thenAnswer(value -> values.get(value.getArgument(0, String.class)));
                    when(rs.getString(anyString())).thenAnswer(value -> values.get(value.getArgument(0, String.class)));
                    when(rs.getBigDecimal(anyString())).thenAnswer(value -> values.get(value.getArgument(0, String.class)));
                    result.add(mapper.mapRow(rs, index));
                }
                return result;
            });
        }
        Map<String, Object> row() {
            var values = new HashMap<String, Object>(); values.put("capture_id", capture); values.put("capture_amount", new BigDecimal("100.00"));
            values.put("capture_currency", "INR"); values.put("payment_id", payment); values.put("ledger_entry_id", ledger);
            values.put("receipt_number", "SYNTHETIC-R-001"); values.put("payment_status", "POSTED"); values.put("payment_method", "ONLINE_PROVIDER");
            values.put("receipt_amount", new BigDecimal("100.00")); values.put("receipt_currency", "INR"); values.put("received_on", date); values.put("open_date", date); return values;
        }
        FeeAccountingService.OriginalPaymentPostingProof proof() {
            return new FeeAccountingService.OriginalPaymentPostingProof(payment, ledger, item, batch, configuration, journalId, year, period, date,
                    "ACCRUAL", "INR", clearing, UUID.randomUUID(), Map.of(), List.of(new FeeAccountingService.OriginalPaymentAllocation(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("100.00"))), "a".repeat(64), "b".repeat(64));
        }
        FeeJournalEvidence journal() {
            return new FeeJournalEvidence(journalId, batch, year, period, date, "ACCRUAL", "INR", "POSTED", "CLOSED", "CLOSED", reversal,
                    reversal == null ? null : "POSTED", List.of(new FeeJournalLineEvidence(clearing, null, clearingLearner, new BigDecimal("100.00"),
                    BigDecimal.ZERO, accountType, category, "INACTIVE", false)), List.of());
        }
        GatewaySettlementSourceReviewModels.Review review() { return service.review(user, scope.school, scope.request, 0, 25); }
    }
}
