package com.educore.api.finance;

import static com.educore.api.accounting.AccountingModuleBridge.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.educore.api.accounting.AccountingModuleBridge;
import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.tenancy.TenantRequestContext;
import java.math.BigDecimal;
import java.lang.reflect.Modifier;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/** Mocked source-proof guards only; not real PostgreSQL or money-posting acceptance. */
class OriginalFeePaymentPostingProofTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 18);
    private static final Instant CREATED = Instant.parse("2026-10-18T06:00:00Z");
    private static final String SOURCE_HASH = "a".repeat(64), OTHER_HASH = "b".repeat(64), BATCH_HASH = "c".repeat(64);

    @AfterEach void clearOnlySyntheticTransactionMarkers() {
        TransactionSynchronizationManager.clear();
    }

    @Test void internalProofIsReadOnlyAndNotAPublicBrowserEntryPoint() throws Exception {
        var method = FeeAccountingService.class.getDeclaredMethod("verifyOriginalPaymentPosting",
                AuthenticatedUser.class, UUID.class, UUID.class);
        assertThat(method.getAnnotation(Transactional.class).readOnly()).isTrue();
        assertThat(Modifier.isPublic(method.getModifiers())).isFalse();
    }

    @Test void backgroundProofJoinsAnExistingTransactionAndIsNotAPublicBrowserEntryPoint() throws Exception {
        var method = FeeAccountingService.class.getDeclaredMethod("verifyGatewayOriginalPaymentPosting", UUID.class, UUID.class, UUID.class);
        assertThat(method.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(Modifier.isPublic(method.getModifiers())).isFalse();
    }

    @Test void backgroundCashAndAccrualReuseTheCompleteHumanProofWithoutBorrowingItsAuthority() throws Exception {
        for (String basis : List.of("CASH", "ACCRUAL")) {
            var f = new Fixture(basis); f.install();
            FeeAccountingService.OriginalPaymentPostingProof humanProof;
            try (var ignored = human(f)) { humanProof = f.verify(); }
            try (var ignored = background(f)) {
                for (int retry = 0; retry < 3; retry++) assertThat(f.verifyBackground()).isEqualTo(humanProof);
            }
            verify(f.accounting, org.mockito.Mockito.times(1)).feeJournalEvidence(eq(f.user), eq(f.school), anyList());
            verify(f.accounting, org.mockito.Mockito.times(3)).gatewayOriginalPaymentJournalEvidence(f.org, f.school, f.payment, f.batch, f.journal);
            assertThat(f.reads.stream().filter(read -> read.sql().contains("gateway-original-source-fence")).map(read -> read.parameters().get("lockKey")).toList())
                    .containsExactly(f.school + ":finance", "finance:school:" + f.school,
                            f.school + ":finance", "finance:school:" + f.school, f.school + ":finance", "finance:school:" + f.school);
            assertThat(f.reads.stream().filter(read -> read.sql().contains("gateway-original-source-context")).toList()).hasSize(6);
            assertNoWrites(f);
        }
    }

    @Test void backgroundRejectsHumanForeignMissingAndNullScopeBeforeAnyDatabaseRead() throws Exception {
        var f = new Fixture("CASH"); f.install();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        deniedScope(f::verifyBackground);
        try (var ignored = TenantRequestContext.open(f.userId, f.org)) { deniedScope(f::verifyBackground); }
        try (var ignored = TenantRequestContext.open(null, UUID.randomUUID())) { deniedScope(f::verifyBackground); }
        try (var ignored = background(f)) {
            deniedScope(() -> f.service.verifyGatewayOriginalPaymentPosting(null, f.school, f.payment));
            deniedScope(() -> f.service.verifyGatewayOriginalPaymentPosting(f.org, null, f.payment));
        }
        verifyNoInteractions(f.jdbc, f.accounting, f.audit);
    }

    @Test void backgroundRequiresActualWritableTransactionNotOnlyASystemThreadMarker() throws Exception {
        var f = new Fixture("CASH"); f.install();
        try (var ignored = background(f)) {
            TransactionSynchronizationManager.setActualTransactionActive(false); deniedScope(f::verifyBackground);
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true); deniedScope(f::verifyBackground);
        }
        verifyNoInteractions(f.jdbc, f.accounting, f.audit);
    }

    @Test void backgroundDatabaseContextChecksReadCommittedWritableAndActiveSchoolBeforeLocks() throws Exception {
        var f = new Fixture("CASH"); f.install(); f.databaseContext = false;
        try (var ignored = background(f)) { deniedScope(f::verifyBackground); }
        assertThat(f.reads).singleElement().satisfies(read -> assertThat(read.sql()).contains(
                "current_app_organisation_id()=:organisationId", "current_app_user_id() IS NULL",
                "current_setting('transaction_isolation')='read committed'", "current_setting('transaction_read_only')='off'",
                "school.organisation_id=:organisationId", "school.status='ACTIVE'", "organisation.status='ACTIVE'"));
        verifyNoInteractions(f.accounting, f.audit); assertNoWrites(f);
    }

    @Test void backgroundRechecksDatabaseContextAfterBothFinanceWaitsBeforeReadingSource() throws Exception {
        var f = new Fixture("CASH"); f.install(); f.databaseContextAfterFence = false;
        try (var ignored = background(f)) { deniedScope(f::verifyBackground); }
        assertThat(f.reads).hasSize(4);
        assertThat(f.reads.stream().map(Read::sql).toList()).satisfies(reads -> {
            assertThat(reads.get(0)).contains("gateway-original-source-context");
            assertThat(reads.get(1)).contains("gateway-original-source-fence");
            assertThat(reads.get(2)).contains("gateway-original-source-fence");
            assertThat(reads.get(3)).contains("gateway-original-source-context");
        });
        verifyNoInteractions(f.accounting, f.audit); assertNoWrites(f);
    }

    @Test void backgroundCannotProveOnlyOneReceiptOrIgnoreFrozenAllocationsMappingsAndSubCentChanges() throws Exception {
        for (String defect : List.of("OTHER_RECEIPT", "ALLOCATION", "MAPPING", "SNAPSHOT", "SUB_CENT", "BATCH")) {
            var f = new Fixture("CASH"); f.install();
            switch (defect) {
                case "OTHER_RECEIPT" -> f.items.removeLast();
                case "ALLOCATION" -> f.allocations.getLast().put("amount", new BigDecimal("49.74"));
                case "MAPPING" -> f.headMappings.getFirst().put("income_account_id", UUID.randomUUID());
                case "SNAPSHOT", "SUB_CENT" -> {
                    var snapshot = new LinkedHashMap<>(f.sourceSnapshots.getFirst());
                    snapshot.put("amount", new BigDecimal("SUB_CENT".equals(defect) ? "100.250000000000000001" : "100.24"));
                    f.items.getFirst().put("source_snapshot", Fixture.json(snapshot));
                }
                case "BATCH" -> f.target.put("source_amount", new BigDecimal("149.99"));
                default -> throw new AssertionError(defect);
            }
            try (var ignored = background(f)) { deniedProof(f::verifyBackground); }
            verify(f.accounting, never()).feeJournalEvidence(any(), any(), anyList()); assertNoWrites(f);
        }
    }

    @Test void backgroundCannotNetGrossSidesOrDropJournalVendorLearnerAndSourceDimensions() throws Exception {
        for (String defect : List.of("GROSS", "VENDOR", "LEARNER", "SOURCE", "REVERSAL", "MISSING")) {
            var f = new Fixture("CASH"); f.install();
            var lines = new ArrayList<>(f.evidence.lines()); var first = lines.getFirst();
            if (List.of("GROSS", "VENDOR", "LEARNER").contains(defect)) lines.set(0, new FeeJournalLineEvidence(first.accountId(),
                    "VENDOR".equals(defect) ? UUID.randomUUID() : null, "LEARNER".equals(defect) ? f.account : null,
                    "GROSS".equals(defect) ? new BigDecimal("175.00") : first.debitAmount(),
                    "GROSS".equals(defect) ? new BigDecimal("25.00") : first.creditAmount(),
                    first.accountType(), first.accountCategory(), first.accountStatus(), first.allowDirectPosting()));
            f.evidence = f.evidenceWith(lines, "POSTED", "SOURCE".equals(defect) ? UUID.randomUUID() : f.batch,
                    "REVERSAL".equals(defect) ? UUID.randomUUID() : null, "REVERSAL".equals(defect) ? "SUBMITTED" : null, List.of());
            if ("MISSING".equals(defect)) f.journalPresent = false;
            try (var ignored = background(f)) { deniedProof(f::verifyBackground); }
            verify(f.accounting, never()).feeJournalEvidence(any(), any(), anyList()); assertNoWrites(f);
        }
    }

    @Test void backgroundDoesNotConvertAccountingInfrastructureFailuresIntoAReviewProof() throws Exception {
        var f = new Fixture("CASH"); f.install();
        var failure = new org.springframework.dao.DataAccessResourceFailureException("Synthetic accounting read unavailable");
        when(f.accounting.gatewayOriginalPaymentJournalEvidence(f.org, f.school, f.payment, f.batch, f.journal)).thenThrow(failure);
        try (var ignored = background(f)) { assertThatThrownBy(f::verifyBackground).isSameAs(failure); }
        assertNoWrites(f);
    }

    @Test void closedCashYearProvesTheWholeTwoReceiptBatchButReturnsOnlyTheRequestedReceiptAllocation() throws Exception {
        var f = new Fixture("CASH"); f.install();
        FeeAccountingService.OriginalPaymentPostingProof proof;
        try (var ignored = human(f)) { proof = f.verify(); }
        assertThat(proof.paymentId()).isEqualTo(f.payment);
        assertThat(proof.feeLedgerEntryId()).isEqualTo(f.ledger);
        assertThat(proof.postingItemId()).isEqualTo(f.item);
        assertThat(proof.postingBatchId()).isEqualTo(f.batch);
        assertThat(proof.configurationId()).isEqualTo(f.configuration);
        assertThat(proof.accountingJournalId()).isEqualTo(f.journal);
        assertThat(proof.originalFinancialYearId()).isEqualTo(f.year);
        assertThat(proof.originalAccountingPeriodId()).isEqualTo(f.period);
        assertThat(proof.originalPostingDate()).isEqualTo(DATE);
        assertThat(proof.accountingBasis()).isEqualTo("CASH");
        assertThat(proof.currencyCode()).isEqualTo("INR");
        assertThat(proof.depositAccountId()).isEqualTo(f.clearing);
        assertThat(proof.receivableAccountId()).isNull();
        assertThat(proof.incomeAccounts()).isEqualTo(Map.of(f.head, f.income));
        assertThat(proof.allocations()).hasSize(1);
        var allocation = proof.allocations().getFirst();
        assertThat(allocation.allocationId()).isEqualTo(f.allocation);
        assertThat(allocation.chargeId()).isEqualTo(f.charge);
        assertThat(allocation.feeHeadId()).isEqualTo(f.head);
        assertThat(allocation.amount()).isEqualByComparingTo("100.25");
        assertThat(proof.sourceSha256()).isEqualTo(SOURCE_HASH);
        assertThat(proof.batchEvidenceSha256()).isEqualTo(BATCH_HASH);
        verify(f.accounting).feeJournalEvidence(eq(f.user), eq(f.school), eq(List.of(f.journal)));
        assertNoWrites(f);
    }

    @Test void accrualRequiresBothReceiptsDistinctLearnerDimensionsEvenForAOneReceiptProof() throws Exception {
        var f = new Fixture("ACCRUAL"); f.install();
        try (var ignored = human(f)) {
            var proof = f.verify();
            assertThat(proof.accountingBasis()).isEqualTo("ACCRUAL");
            assertThat(proof.receivableAccountId()).isEqualTo(f.receivable);
            assertThat(proof.allocations()).hasSize(1);
        }
        assertThat(f.evidence.lines()).hasSize(3);
        assertThat(f.evidence.financialYearStatus()).isEqualTo("CLOSED");
        assertThat(f.evidence.accountingPeriodStatus()).isEqualTo("CLOSED");
        assertNoWrites(f);
    }

    @Test void theCallersExistingWritableTransactionCanReadProofWithoutCreatingAnyMoneyOrAuditWrite() throws Exception {
        var f = new Fixture("CASH"); f.install();
        try (var ignored = human(f)) {
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            assertThat(f.verify().paymentId()).isEqualTo(f.payment);
        }
        assertNoWrites(f);
    }

    @Test void absentHumanOrTransactionCannotReadAnyPersistedSource() throws Exception {
        var f = new Fixture("CASH"); f.install();
        deniedScope(f::verify);
        try (var ignored = TenantRequestContext.open(null, f.org)) {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            deniedScope(f::verify);
        }
        try (var ignored = TenantRequestContext.open(f.userId, f.org)) {
            TransactionSynchronizationManager.setActualTransactionActive(false);
            deniedScope(f::verify);
        }
        verifyNoInteractions(f.jdbc, f.accounting, f.audit);
    }

    @Test void threadActorOrOrganisationMismatchCannotBorrowTheHumanArgument() throws Exception {
        var f = new Fixture("CASH"); f.install();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try (var ignored = TenantRequestContext.open(UUID.randomUUID(), f.org)) { deniedScope(f::verify); }
        try (var ignored = TenantRequestContext.open(f.userId, UUID.randomUUID())) { deniedScope(f::verify); }
        verifyNoInteractions(f.jdbc, f.accounting, f.audit);
    }

    @Test void actualDatabaseContextMustMatchBeforeLiveScopeOrReceiptReads() throws Exception {
        var f = new Fixture("CASH"); f.install(); f.databaseContext = false;
        try (var ignored = human(f)) { deniedScope(f::verify); }
        assertThat(f.reads).hasSize(1);
        assertThat(f.reads.getFirst().sql()).contains("current_app_organisation_id()", "current_app_user_id()");
        verifyNoInteractions(f.accounting, f.audit);
        assertNoWrites(f);
    }

    @Test void revokedReadScopeCannotBeReplacedByCachedCapabilities() throws Exception {
        var f = new Fixture("CASH"); f.install(); f.scopeAllowed = false;
        try (var ignored = human(f)) {
            assertThatThrownBy(f::verify).isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.status()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(error.code()).isEqualTo("FEE_ORIGINAL_PAYMENT_PROOF_SCOPE_DENIED");
            });
            f.scopeAllowed = true;
            assertThatThrownBy(() -> f.service.verifyOriginalPaymentPosting(f.user, UUID.randomUUID(), f.payment))
                    .isInstanceOfSatisfying(ApiException.class, error -> {
                        assertThat(error.status()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(error.code()).isEqualTo("FEE_ORIGINAL_PAYMENT_PROOF_SCOPE_DENIED");
                    });
        }
        assertThat(f.reads).noneMatch(read -> read.sql().contains("original-payment-proof-target"));
        verifyNoInteractions(f.accounting, f.audit);
        assertNoWrites(f);
    }

    @Test void knownForbiddenAccountingBridgeScopeDenialsUseTheSameProofAuthorityCode() throws Exception {
        for (String code : List.of("ACCOUNTING_SCOPE_DENIED", "FEE_ACCOUNTING_SCOPE_DENIED")) {
            var f = new Fixture("CASH"); f.install();
            when(f.accounting.feeJournalEvidence(eq(f.user), eq(f.school), anyList()))
                    .thenThrow(new ApiException(HttpStatus.FORBIDDEN, code, "Synthetic unavailable accounting scope"));
            try (var ignored = human(f)) { deniedScope(f::verify); }
            assertNoWrites(f);
        }
    }

    @Test void unrelatedApiFailuresKeepTheirExactIdentityStatusAndCodeInsteadOfBecomingAuthorityDenials() throws Exception {
        for (String location : List.of("LIVE_SCOPE", "ACCOUNTING_BRIDGE")) {
            for (var failure : List.of(
                    new ApiException(HttpStatus.FORBIDDEN, "SYNTHETIC_OTHER_RULE_DENIED", "Synthetic unrelated authorization rule"),
                    new ApiException(HttpStatus.CONFLICT, "ACCOUNTING_SCOPE_DENIED", "Synthetic non-forbidden data conflict"))) {
                var f = new Fixture("CASH"); f.install();
                if ("LIVE_SCOPE".equals(location)) {
                    doThrow(failure).when(f.jdbc).query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class));
                } else {
                    when(f.accounting.feeJournalEvidence(eq(f.user), eq(f.school), anyList())).thenThrow(failure);
                }
                try (var ignored = human(f)) { assertThatThrownBy(f::verify).isSameAs(failure); }
                assertNoWrites(f);
            }
        }
    }

    @Test void actualDatabaseFaultsAreNotSwallowedOrReclassifiedAsProofAuthorityFailures() throws Exception {
        for (String location : List.of("DATABASE_CONTEXT", "ACCOUNTING_BRIDGE")) {
            var f = new Fixture("CASH"); f.install();
            var failure = new org.springframework.dao.DataAccessResourceFailureException("Synthetic unchanged database fault");
            if ("DATABASE_CONTEXT".equals(location)) {
                doThrow(failure).when(f.jdbc).queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Boolean.class));
            } else {
                when(f.accounting.feeJournalEvidence(eq(f.user), eq(f.school), anyList())).thenThrow(failure);
            }
            try (var ignored = human(f)) { assertThatThrownBy(f::verify).isSameAs(failure); }
            assertNoWrites(f);
        }
    }

    @Test void absentOrAmbiguousExactReceiptTargetCannotFallBackToAnotherPayment() throws Exception {
        for (int count : List.of(0, 2)) {
            var f = new Fixture("CASH"); f.install();
            f.targets.clear();
            for (int index = 0; index < count; index++) f.targets.add(new HashMap<>(f.target));
            try (var ignored = human(f)) { deniedProof(f::verify); }
            assertNoWrites(f);
        }
    }

    @Test void missingPartialReleasedOrDuplicateWholeBatchItemsNeverProveASubset() throws Exception {
        for (String defect : List.of("MISSING", "PARTIAL", "RELEASED", "DUPLICATE", "SEQUENCE")) {
            var f = new Fixture("CASH"); f.install();
            switch (defect) {
                case "MISSING" -> f.items.clear();
                case "PARTIAL" -> f.items.removeLast();
                case "RELEASED" -> f.items.getLast().put("item_status", "RELEASED");
                case "DUPLICATE" -> f.items.set(1, new HashMap<>(f.items.getFirst()));
                case "SEQUENCE" -> f.items.getLast().put("sequence_number", 3);
                default -> throw new AssertionError(defect);
            }
            try (var ignored = human(f)) { deniedProof(f::verify); }
            assertNoWrites(f);
        }
    }

    @Test void targetReceiptStatusSourceAmountAndBatchDispositionMustRemainExact() throws Exception {
        for (String field : List.of("receipt_status", "receipt_amount", "source_count", "source_amount", "event_type", "disposition")) {
            var f = new Fixture("CASH"); f.install();
            f.target.put(field, switch (field) {
                case "receipt_status" -> "REVERSED";
                case "receipt_amount" -> new BigDecimal("100.24");
                case "source_count" -> 1;
                case "source_amount" -> new BigDecimal("149.99");
                case "event_type" -> "PAYMENT_REVERSAL";
                case "disposition" -> "NO_GL_EFFECT";
                default -> throw new AssertionError(field);
            });
            try (var ignored = human(f)) { deniedProof(f::verify); }
            assertNoWrites(f);
        }
    }

    @Test void changedSourceSnapshotOrStoredItemDigestCannotBecomeOriginalEvidence() throws Exception {
        for (String defect : List.of("SNAPSHOT", "TARGET_DIGEST", "MALFORMED_DIGEST", "INVALID_JSON")) {
            var f = new Fixture("CASH"); f.install();
            switch (defect) {
                case "SNAPSHOT" -> {
                    var changed = new LinkedHashMap<>(f.sourceSnapshots.getFirst());
                    changed.put("amount", new BigDecimal("100.24"));
                    f.items.getFirst().put("source_snapshot", Fixture.json(changed));
                }
                case "TARGET_DIGEST" -> f.target.put("source_sha256", OTHER_HASH);
                case "MALFORMED_DIGEST" -> f.items.getFirst().put("source_sha256", "not-a-digest");
                case "INVALID_JSON" -> f.items.getFirst().put("source_snapshot", "{not-json}");
                default -> throw new AssertionError(defect);
            }
            try (var ignored = human(f)) { deniedProof(f::verify); }
            assertNoWrites(f);
        }
    }

    @Test void changedBatchSnapshotOrMalformedBatchDigestFailsWithoutRehashingJsonbText() throws Exception {
        for (String defect : List.of("AMOUNT", "MISSING_SOURCE", "INVALID_DIGEST", "INVALID_JSON")) {
            var f = new Fixture("CASH"); f.install();
            var snapshot = new LinkedHashMap<>(f.batchSnapshot);
            switch (defect) {
                case "AMOUNT" -> snapshot.put("sourceAmount", new BigDecimal("149.99"));
                case "MISSING_SOURCE" -> snapshot.put("sources", List.of(f.sourceSnapshots.getFirst()));
                case "INVALID_DIGEST" -> f.target.put("evidence_sha256", "not-a-digest");
                case "INVALID_JSON" -> f.target.put("evidence_snapshot", "[]");
                default -> throw new AssertionError(defect);
            }
            if (!List.of("INVALID_DIGEST", "INVALID_JSON").contains(defect)) f.target.put("evidence_snapshot", Fixture.json(snapshot));
            try (var ignored = human(f)) { deniedProof(f::verify); }
            assertNoWrites(f);
        }
    }

    @Test void aSubCentFrozenJsonTamperCannotRoundIntoEqualityWithTheExactLedgerAmount() throws Exception {
        var f = new Fixture("CASH"); f.install();
        var changed = new LinkedHashMap<>(f.sourceSnapshots.getFirst());
        changed.put("amount", new BigDecimal("100.250000000000000001"));
        f.items.getFirst().put("source_snapshot", Fixture.json(changed));
        try (var ignored = human(f)) { deniedProof(f::verify); }
        assertNoWrites(f);
    }

    @Test void actualAllocationsCannotBeMissingChangedDuplicatedOrMovedToAnotherLearner() throws Exception {
        for (String defect : List.of("MISSING", "AMOUNT", "CHARGE", "HEAD", "LEARNER", "DUPLICATE")) {
            var f = new Fixture("CASH"); f.install();
            switch (defect) {
                case "MISSING" -> f.allocations.removeLast();
                case "AMOUNT" -> f.allocations.getLast().put("amount", new BigDecimal("49.74"));
                case "CHARGE" -> f.allocations.getFirst().put("learner_fee_charge_id", UUID.randomUUID());
                case "HEAD" -> f.allocations.getFirst().put("fee_head_id", UUID.randomUUID());
                case "LEARNER" -> f.allocations.getFirst().put("learner_fee_account_id", f.otherAccount);
                case "DUPLICATE" -> f.allocations.add(new HashMap<>(f.allocations.getFirst()));
                default -> throw new AssertionError(defect);
            }
            try (var ignored = human(f)) { deniedProof(f::verify); }
            assertNoWrites(f);
        }
    }

    @Test void missingOrMismatchedOriginalConfigurationCannotUseANewerMapping() throws Exception {
        for (String defect : List.of("MISSING", "YEAR", "BASIS", "CURRENCY")) {
            var f = new Fixture("CASH"); f.install();
            switch (defect) {
                case "MISSING" -> f.configurations.clear();
                case "YEAR" -> f.configurationRow.put("financial_year_id", UUID.randomUUID());
                case "BASIS" -> f.configurationRow.put("accounting_basis_snapshot", "ACCRUAL");
                case "CURRENCY" -> f.configurationRow.put("currency_code", "USD");
                default -> throw new AssertionError(defect);
            }
            try (var ignored = human(f)) { deniedProof(f::verify); }
            assertNoWrites(f);
        }
    }

    @Test void anotherConfigurationTenderOrIncomeAccountCannotMatchTheOriginalJournal() throws Exception {
        for (String defect : List.of("TENDER", "INCOME", "MISSING_HEAD")) {
            var f = new Fixture("CASH"); f.install();
            switch (defect) {
                case "TENDER" -> f.tenderMappings.getFirst().put("deposit_account_id", UUID.randomUUID());
                case "INCOME" -> f.headMappings.getFirst().put("income_account_id", UUID.randomUUID());
                case "MISSING_HEAD" -> f.headMappings.clear();
                default -> throw new AssertionError(defect);
            }
            try (var ignored = human(f)) { deniedProof(f::verify); }
            assertNoWrites(f);
        }
    }

    @Test void vendorOrLearnerDimensionsCannotBeDiscardedWhenComparingWholeJournalLines() throws Exception {
        for (String defect : List.of("VENDOR", "LEARNER")) {
            var f = new Fixture("CASH"); f.install();
            var lines = new ArrayList<>(f.evidence.lines());
            var original = lines.getFirst();
            lines.set(0, new FeeJournalLineEvidence(original.accountId(), "VENDOR".equals(defect) ? UUID.randomUUID() : null,
                    "LEARNER".equals(defect) ? f.account : null, original.debitAmount(), original.creditAmount(),
                    original.accountType(), original.accountCategory(), original.accountStatus(), original.allowDirectPosting()));
            f.evidence = f.evidenceWith(lines, "POSTED", f.batch, null, null, List.of());
            try (var ignored = human(f)) { deniedProof(f::verify); }
            assertNoWrites(f);
        }
    }

    @Test void extraBalancedGrossDebitAndCreditCannotPassByKeepingTheSameNetAmount() throws Exception {
        var f = new Fixture("CASH"); f.install();
        var lines = new ArrayList<>(f.evidence.lines());
        var original = lines.getFirst();
        lines.set(0, new FeeJournalLineEvidence(original.accountId(), null, null, new BigDecimal("175.00"),
                new BigDecimal("25.00"), original.accountType(), original.accountCategory(), original.accountStatus(), true));
        f.evidence = f.evidenceWith(lines, "POSTED", f.batch, null, null, List.of());
        try (var ignored = human(f)) { deniedProof(f::verify); }
        assertNoWrites(f);
    }

    @Test void missingPendingReversedWrongSourceOrCounterJournalEvidenceCannotClaimPosting() throws Exception {
        for (String defect : List.of("MISSING", "SUBMITTED", "REVERSED", "SOURCE", "REVERSAL")) {
            var f = new Fixture("CASH"); f.install();
            switch (defect) {
                case "MISSING" -> f.journalPresent = false;
                case "SUBMITTED", "REVERSED" -> f.evidence = f.evidenceWith(f.evidence.lines(), defect, f.batch, null, null, List.of());
                case "SOURCE" -> f.evidence = f.evidenceWith(f.evidence.lines(), "POSTED", UUID.randomUUID(), null, null, List.of());
                case "REVERSAL" -> f.evidence = f.evidenceWith(f.evidence.lines(), "POSTED", f.batch, UUID.randomUUID(), "SUBMITTED", f.evidence.lines());
                default -> throw new AssertionError(defect);
            }
            try (var ignored = human(f)) { deniedProof(f::verify); }
            assertNoWrites(f);
        }
    }

    @Test void historicalFrozenLabelsAndSemanticallyEqualReorderedJsonDoNotRequireHistoricalDigestRehashing() throws Exception {
        var f = new Fixture("CASH"); f.install();
        for (int index = 0; index < f.items.size(); index++) {
            f.items.get(index).put("source_snapshot", Fixture.json(reverse(f.sourceSnapshots.get(index))));
            // The allocation query intentionally does not select today's labels.
            f.allocations.get(index).put("fee_head_code", "RENAMED_CURRENT_CODE");
            f.allocations.get(index).put("fee_head_name", "Renamed current display label");
        }
        f.target.put("evidence_snapshot", Fixture.json(reverse(f.batchSnapshot)));
        try (var ignored = human(f)) {
            var proof = f.verify();
            assertThat(proof.sourceSha256()).isEqualTo(SOURCE_HASH);
            assertThat(proof.batchEvidenceSha256()).isEqualTo(BATCH_HASH);
            assertThat(proof.incomeAccounts()).containsEntry(f.head, f.income);
        }
        // These consistent persisted markers intentionally are not SHA256 of the JSON text.
        // Their original immutable DB provenance, not a JSONB reserialization, is the authority.
        assertThat(f.reads.stream().filter(read -> read.sql().contains("original-payment-proof-allocations")).toList())
                .singleElement().satisfies(read -> assertThat(read.sql()).doesNotContain("fee_head_code", "fee_head_name", "display_name"));
        assertNoWrites(f);
    }

    @Test void boundedQueriesAndImmutableRedactedOutputExposeNoWriteOrProviderAuthority() throws Exception {
        var f = new Fixture("CASH"); f.install();
        try (var ignored = human(f)) {
            var proof = f.verify();
            assertThatThrownBy(() -> proof.incomeAccounts().clear()).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> proof.allocations().clear()).isInstanceOf(UnsupportedOperationException.class);
            assertThat(proof.toString()).doesNotContain(f.payment.toString(), f.account.toString(), "100.25", SOURCE_HASH, BATCH_HASH);
            assertThat(proof.allocations().getFirst().toString()).doesNotContain(f.allocation.toString(), f.charge.toString(), "100.25");
        }
        assertThat(f.reads.stream().filter(read -> read.sql().contains("original-payment-proof-target")).toList())
                .singleElement().satisfies(read -> {
                    assertThat(read.sql()).contains("LIMIT 2");
                    assertThat(read.parameters()).containsEntry("organisationId", f.org).containsEntry("schoolId", f.school);
                });
        assertThat(f.reads.stream().filter(read -> read.sql().contains("original-payment-proof-items")).toList())
                .singleElement().satisfies(read -> assertThat(read.sql()).contains("LIMIT 201"));
        assertThat(f.reads.stream().filter(read -> read.sql().contains("original-payment-proof-allocations")).toList())
                .singleElement().satisfies(read -> assertThat(read.sql()).contains("LIMIT 200001"));
        assertThat(f.reads.stream().filter(read -> read.sql().contains("original-payment-proof-configuration")).toList())
                .singleElement().satisfies(read -> assertThat(read.sql()).contains("organisation_id=:organisationId", "school_id=:schoolId"));
        assertNoWrites(f);
    }

    @Test void applicationNonNullMapperStillProvesExistingExplicitNullHistoricalSources() throws Exception {
        var mapper = applicationMapper();
        for (String basis : List.of("CASH", "ACCRUAL")) {
            var f = new Fixture(basis); f.install();
            String before = Fixture.json(f.items), batchBefore = (String) f.target.get("evidence_snapshot");
            assertThat(mapper.readTree(mapper.writeValueAsString(f.sourceSnapshots.getFirst())).has("feeHeadId")).isFalse();
            try (var ignored = human(f)) {
                var expected = f.verify();
                var service = new FeeAccountingService(f.jdbc,f.accounting,f.audit,mapper,Clock.fixed(CREATED,ZoneOffset.UTC));
                assertThat(service.verifyOriginalPaymentPosting(f.user,f.school,f.payment)).isEqualTo(expected);
            }
            assertThat(Fixture.json(f.items)).isEqualTo(before);
            assertThat(f.target.get("evidence_snapshot")).isEqualTo(batchBefore);
            assertNoWrites(f);
        }
    }

    @Test void explicitAndOmittedNullSourcesRemainCompatibleInBothMapperDirectionsAndMixedBatches() throws Exception {
        var mapper = applicationMapper();
        for (String basis : List.of("CASH", "ACCRUAL")) for (int omittedSources : List.of(1, 2)) {
            var f = new Fixture(basis);
            for (int index = 0; index < omittedSources; index++) {
                for (String field : List.of("learnerFeeChargeId", "reversesEntryId", "feeHeadId", "feeHeadCode", "feeHeadName"))
                    f.sourceSnapshots.get(index).remove(field);
                f.items.get(index).put("source_snapshot", Fixture.json(f.sourceSnapshots.get(index)));
            }
            f.target.put("evidence_snapshot", Fixture.json(f.batchSnapshot)); f.install();
            try (var ignored = human(f)) {
                var expected = f.verify();
                var service = new FeeAccountingService(f.jdbc,f.accounting,f.audit,mapper,Clock.fixed(CREATED,ZoneOffset.UTC));
                assertThat(service.verifyOriginalPaymentPosting(f.user,f.school,f.payment)).isEqualTo(expected);
            }
            assertNoWrites(f);
        }
    }

    @Test void applicationMapperStillRejectsUnexpectedNullsRequiredFieldsAndChangedSourceFacts() throws Exception {
        var mapper = applicationMapper();
        for (String defect : List.of("UNKNOWN_NULL", "BATCH_UNKNOWN_NULL", "MISSING_REQUIRED", "NON_NULL_INAPPLICABLE",
                "MISSING_ALLOCATION_FIELD", "ALLOCATION_NULL", "AMOUNT", "TIMESTAMP", "EVENT", "BATCH_SOURCE")) {
            var f = new Fixture("CASH");
            var changed = new LinkedHashMap<>(f.sourceSnapshots.getFirst());
            switch (defect) {
                case "UNKNOWN_NULL" -> changed.put("unknownField", null);
                case "BATCH_UNKNOWN_NULL" -> f.batchSnapshot.put("unknownField", null);
                case "MISSING_REQUIRED" -> changed.remove("description");
                case "NON_NULL_INAPPLICABLE" -> changed.put("feeHeadId", f.head);
                case "MISSING_ALLOCATION_FIELD", "ALLOCATION_NULL" -> {
                    var allocation = new LinkedHashMap<String, Object>(Map.of("learnerFeeChargeId", f.charge, "feeHeadId", f.head,
                            "feeHeadCode", "FROZEN_TUITION", "feeHeadName", "Synthetic frozen tuition label", "amount", new BigDecimal("100.25")));
                    if ("ALLOCATION_NULL".equals(defect)) allocation.put("unknownField", null); else allocation.remove("feeHeadCode");
                    changed.put("allocations", List.of(allocation));
                }
                case "AMOUNT" -> changed.put("amount", new BigDecimal("100.250000000000000001"));
                case "TIMESTAMP" -> changed.put("createdAt", CREATED.plusNanos(1));
                case "EVENT" -> changed.put("eventType", "CHARGE");
                case "BATCH_SOURCE" -> {
                    var other = new LinkedHashMap<>(f.sourceSnapshots.getLast()); other.put("unknownField", null);
                    f.batchSnapshot.put("sources", List.of(f.sourceSnapshots.getFirst(), other));
                }
                default -> throw new AssertionError(defect);
            }
            f.items.getFirst().put("source_snapshot", Fixture.json(changed));
            f.target.put("evidence_snapshot", Fixture.json(f.batchSnapshot)); f.install();
            try (var ignored = human(f)) {
                var service = new FeeAccountingService(f.jdbc,f.accounting,f.audit,mapper,Clock.fixed(CREATED,ZoneOffset.UTC));
                deniedProof(() -> service.verifyOriginalPaymentPosting(f.user,f.school,f.payment));
            }
            assertNoWrites(f);
        }
    }

    private static tools.jackson.databind.ObjectMapper applicationMapper() {
        var result = new java.util.concurrent.atomic.AtomicReference<tools.jackson.databind.ObjectMapper>();
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class))
                .withPropertyValues("spring.jackson.default-property-inclusion=non_null")
                .run(context -> { assertThat(context).hasNotFailed(); result.set(context.getBean(tools.jackson.databind.ObjectMapper.class)); });
        return result.get();
    }

    private static Map<String, Object> reverse(Map<String, Object> source) {
        var keys = new ArrayList<>(source.keySet()); Collections.reverse(keys);
        var result = new LinkedHashMap<String, Object>(); keys.forEach(key -> result.put(key, source.get(key))); return result;
    }

    private static TenantRequestContext.Scope human(Fixture f) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        return TenantRequestContext.open(f.userId, f.org);
    }

    private static TenantRequestContext.Scope background(Fixture f) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        return TenantRequestContext.open(null, f.org);
    }

    private static void deniedProof(Runnable action) {
        denied(action, "FEE_ORIGINAL_PAYMENT_PROOF_INVALID", HttpStatus.CONFLICT);
    }

    private static void deniedScope(Runnable action) {
        denied(action, "FEE_ORIGINAL_PAYMENT_PROOF_SCOPE_DENIED", HttpStatus.FORBIDDEN);
    }

    private static void denied(Runnable action, String code, HttpStatus status) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.code()).isEqualTo(code); assertThat(error.status()).isEqualTo(status);
        });
    }

    private static void assertNoWrites(Fixture f) {
        verify(f.jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
        verifyNoInteractions(f.audit);
        assertThat(f.reads).allMatch(read -> !read.sql().matches("(?is).*\\b(INSERT|UPDATE|DELETE)\\b.*"));
    }

    private record Read(String sql, Map<String, Object> parameters) {}

    private static final class Fixture {
        final UUID org=UUID.randomUUID(),school=UUID.randomUUID(),userId=UUID.randomUUID(),membership=UUID.randomUUID();
        final UUID payment=UUID.randomUUID(),otherPayment=UUID.randomUUID(),ledger=UUID.randomUUID(),otherLedger=UUID.randomUUID();
        final UUID account=UUID.randomUUID(),otherAccount=UUID.randomUUID(),charge=UUID.randomUUID(),otherCharge=UUID.randomUUID();
        final UUID allocation=UUID.randomUUID(),otherAllocation=UUID.randomUUID(),item=UUID.randomUUID(),otherItem=UUID.randomUUID();
        final UUID batch=UUID.randomUUID(),configuration=UUID.randomUUID(),journal=UUID.randomUUID(),year=UUID.randomUUID(),period=UUID.randomUUID();
        final UUID head=UUID.randomUUID(),clearing=UUID.randomUUID(),income=UUID.randomUUID(),receivable=UUID.randomUUID(),concession=UUID.randomUUID();
        final NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);
        final AccountingModuleBridge accounting=mock(AccountingModuleBridge.class);
        final SecurityAuditService audit=mock(SecurityAuditService.class);
        final AuthenticatedUser user=new AuthenticatedUser(userId,UUID.randomUUID(),"synthetic@example.test",null,"Synthetic actor",true,org,
                Set.of(membership),Set.of("fees.read","accounting.read"),List.of());
        final FeeAccountingService service=new FeeAccountingService(jdbc,accounting,audit,JsonMapper.builder().build(),Clock.fixed(CREATED,ZoneOffset.UTC));
        final String basis;
        final Map<String,Object> target=new HashMap<>(),configurationRow=new HashMap<>(),batchSnapshot=new LinkedHashMap<>();
        final List<Map<String,Object>> targets=new ArrayList<>(),items=new ArrayList<>(),allocations=new ArrayList<>(),configurations=new ArrayList<>();
        final List<Map<String,Object>> headMappings=new ArrayList<>(),tenderMappings=new ArrayList<>(),sourceSnapshots=new ArrayList<>();
        final List<Read> reads=new ArrayList<>();
        FeeJournalEvidence evidence;
        boolean databaseContext=true,databaseContextAfterFence=true,scopeAllowed=true,journalPresent=true;
        int backgroundContextReads;

        Fixture(String basis) throws Exception {
            this.basis=basis;
            sourceSnapshots.add(snapshot(ledger,payment,account,charge,"100.25","SYN-000001"));
            sourceSnapshots.add(snapshot(otherLedger,otherPayment,otherAccount,otherCharge,"49.75","SYN-000002"));
            items.add(itemRow(ledger,item,1,SOURCE_HASH,sourceSnapshots.getFirst()));
            items.add(itemRow(otherLedger,otherItem,2,OTHER_HASH,sourceSnapshots.getLast()));
            allocations.add(allocationRow(allocation,payment,charge,account,"100.25"));
            allocations.add(allocationRow(otherAllocation,otherPayment,otherCharge,otherAccount,"49.75"));
            batchSnapshot.put("schemaVersion",1); batchSnapshot.put("configurationId",configuration);
            batchSnapshot.put("financialYearId",year); batchSnapshot.put("accountingPeriodId",period);
            batchSnapshot.put("accountingBasis",basis); batchSnapshot.put("currencyCode","INR");
            batchSnapshot.put("businessDate",DATE); batchSnapshot.put("eventType","PAYMENT");
            batchSnapshot.put("sourceCount",2); batchSnapshot.put("sourceAmount",new BigDecimal("150.00"));
            batchSnapshot.put("sources",sourceSnapshots);
            target.put("payment_id",payment); target.put("fee_ledger_entry_id",ledger); target.put("posting_item_id",item);
            target.put("posting_batch_id",batch); target.put("configuration_id",configuration); target.put("accounting_journal_id",journal);
            target.put("financial_year_id",year); target.put("accounting_period_id",period); target.put("business_date",DATE);
            target.put("event_type","PAYMENT"); target.put("disposition","JOURNAL"); target.put("source_count",2);
            target.put("source_amount",new BigDecimal("150.00")); target.put("evidence_snapshot",json(batchSnapshot));
            target.put("evidence_sha256",BATCH_HASH); target.put("source_sha256",SOURCE_HASH);
            target.put("receipt_status","POSTED"); target.put("payment_method","ONLINE_PROVIDER");
            target.put("receipt_amount",new BigDecimal("100.25")); target.put("receipt_account_id",account); target.put("receipt_currency","INR");
            targets.add(target);
            configurationRow.put("id",configuration); configurationRow.put("financial_year_id",year);
            configurationRow.put("supersedes_configuration_id",null); configurationRow.put("revision_number",1);
            configurationRow.put("effective_from",DATE.minusMonths(6)); configurationRow.put("accounting_basis_snapshot",basis);
            configurationRow.put("currency_code","INR"); configurationRow.put("receivable_account_id","ACCRUAL".equals(basis)?receivable:null);
            configurationRow.put("concession_account_id","ACCRUAL".equals(basis)?concession:null); configurationRow.put("status","APPROVED");
            configurationRow.put("created_by_membership_id",membership); configurationRow.put("row_version",2L);
            configurations.add(configurationRow);
            headMappings.add(new HashMap<>(Map.of("fee_head_id",head,"income_account_id",income)));
            tenderMappings.add(new HashMap<>(Map.of("payment_method","ONLINE_PROVIDER","deposit_account_id",clearing)));
            var lines=new ArrayList<FeeJournalLineEvidence>();
            lines.add(line(clearing,null,"150.00","0.00","ASSET","OTHER"));
            if ("CASH".equals(basis)) lines.add(line(income,null,"0.00","150.00","INCOME","INCOME"));
            else {
                lines.add(line(receivable,account,"0.00","100.25","ASSET","RECEIVABLE"));
                lines.add(line(receivable,otherAccount,"0.00","49.75","ASSET","RECEIVABLE"));
            }
            evidence=evidenceWith(lines,"POSTED",batch,null,null,List.of());
        }

        FeeAccountingService.OriginalPaymentPostingProof verify() { return service.verifyOriginalPaymentPosting(user,school,payment); }
        FeeAccountingService.OriginalPaymentPostingProof verifyBackground() { return service.verifyGatewayOriginalPaymentPosting(org,school,payment); }

        FeeJournalEvidence evidenceWith(List<FeeJournalLineEvidence> lines,String status,UUID source,UUID reversal,String reversalStatus,List<FeeJournalLineEvidence> reversalLines) {
            return new FeeJournalEvidence(journal,source,year,period,DATE,basis,"INR",status,"CLOSED","CLOSED",reversal,reversalStatus,lines,reversalLines);
        }

        private FeeJournalLineEvidence line(UUID accountId,UUID learner,String debit,String credit,String type,String category) {
            return new FeeJournalLineEvidence(accountId,null,learner,new BigDecimal(debit),new BigDecimal(credit),type,category,"ACTIVE",true);
        }

        private Map<String,Object> snapshot(UUID ledgerId,UUID paymentId,UUID accountId,UUID chargeId,String amount,String receipt) {
            var value=new LinkedHashMap<String,Object>();
            value.put("feeLedgerEntryId",ledgerId); value.put("learnerFeeAccountId",accountId); value.put("learnerFeeChargeId",null);
            value.put("eventType","PAYMENT"); value.put("direction","CREDIT"); value.put("amount",new BigDecimal(amount));
            value.put("effectiveOn",DATE); value.put("description","Synthetic captured receipt"); value.put("sourceType","FEE_PAYMENT");
            value.put("sourceId",paymentId); value.put("reversesEntryId",null); value.put("createdAt",CREATED); value.put("currencyCode","INR");
            value.put("feeHeadId",null); value.put("feeHeadCode",null); value.put("feeHeadName",null);
            value.put("paymentMethod","ONLINE_PROVIDER"); value.put("receiptNumber",receipt);
            value.put("allocations",List.of(Map.of("learnerFeeChargeId",chargeId,"feeHeadId",head,"feeHeadCode","FROZEN_TUITION",
                    "feeHeadName","Synthetic frozen tuition label","amount",new BigDecimal(amount))));
            return value;
        }

        private Map<String,Object> itemRow(UUID ledgerId,UUID itemId,int sequence,String hash,Map<String,Object> snapshot) throws Exception {
            var value=new HashMap<String,Object>();
            value.put("posting_item_id",itemId); value.put("sequence_number",sequence); value.put("item_status","ACTIVE");
            value.put("source_snapshot",json(snapshot)); value.put("source_sha256",hash); value.put("id",ledgerId);
            value.put("learner_fee_account_id",snapshot.get("learnerFeeAccountId")); value.put("learner_fee_charge_id",null);
            value.put("entry_type","PAYMENT"); value.put("direction","CREDIT"); value.put("amount",snapshot.get("amount"));
            value.put("effective_on",DATE); value.put("description","Synthetic captured receipt"); value.put("source_type","FEE_PAYMENT");
            value.put("source_id",snapshot.get("sourceId")); value.put("reverses_entry_id",null); value.put("created_at",CREATED);
            value.put("currency_code","INR"); value.put("fee_head_id",null); value.put("fee_head_code",null); value.put("fee_head_name",null);
            value.put("payment_method","ONLINE_PROVIDER"); value.put("receipt_number",snapshot.get("receiptNumber"));
            return value;
        }

        private Map<String,Object> allocationRow(UUID id,UUID paymentId,UUID chargeId,UUID learner,String amount) {
            return new HashMap<>(Map.of("allocation_id",id,"fee_payment_id",paymentId,"learner_fee_charge_id",chargeId,
                    "fee_head_id",head,"learner_fee_account_id",learner,"amount",new BigDecimal(amount)));
        }

        private static String json(Object value) throws Exception { return JsonMapper.builder().build().writeValueAsString(value); }

        @SuppressWarnings({"unchecked","rawtypes"})
        void install() throws Exception {
            when(jdbc.queryForObject(anyString(),any(MapSqlParameterSource.class),eq(Boolean.class))).thenAnswer(call->{
                reads.add(new Read(call.getArgument(0),new LinkedHashMap<>(call.<MapSqlParameterSource>getArgument(1).getValues())));
                if (call.<String>getArgument(0).contains("gateway-original-source-context") && ++backgroundContextReads > 1) return databaseContext && databaseContextAfterFence;
                return databaseContext;
            });
            when(jdbc.queryForObject(anyString(),any(MapSqlParameterSource.class),eq(Integer.class))).thenAnswer(call->{
                if (!call.<String>getArgument(0).contains("gateway-original-source-fence")) throw new AssertionError("Only named original-source fences are allowed.");
                reads.add(new Read(call.getArgument(0),new LinkedHashMap<>(call.<MapSqlParameterSource>getArgument(1).getValues()))); return 1;
            });
            when(jdbc.query(anyString(),any(MapSqlParameterSource.class),any(RowMapper.class))).thenAnswer(call->{
                String sql=call.getArgument(0); var parameters=call.<MapSqlParameterSource>getArgument(1);
                reads.add(new Read(sql,new LinkedHashMap<>(parameters.getValues())));
                List<Map<String,Object>> selected;
                if (sql.contains("FROM memberships membership")) {
                    UUID requestedSchool=(UUID)parameters.getValue("schoolId");
                    if (!scopeAllowed || (requestedSchool!=null&&!school.equals(requestedSchool))) return List.of();
                    selected=List.of(new HashMap<>(Map.of("school_id",school,"display_name","Synthetic school","membership_id",membership,"time_zone","Asia/Kolkata")));
                } else if (sql.contains("original-payment-proof-target")) selected=targets;
                else if (sql.contains("original-payment-proof-items")) selected=items;
                else if (sql.contains("original-payment-proof-allocations")) selected=allocations;
                else if (sql.contains("original-payment-proof-configuration")) selected=configurations;
                else throw new AssertionError("Only explicitly scoped source-proof reads are allowed in this fixture.");
                RowMapper mapper=call.getArgument(2); var result=new ArrayList<>();
                for(var value:selected) result.add(mapper.mapRow(row(value),result.size()));
                return result;
            });
            when(jdbc.query(anyString(),any(MapSqlParameterSource.class),any(ResultSetExtractor.class))).thenAnswer(call->{
                String sql=call.getArgument(0); var parameters=call.<MapSqlParameterSource>getArgument(1);
                reads.add(new Read(sql,new LinkedHashMap<>(parameters.getValues())));
                var values=sql.contains("fee_accounting_head_mappings")?headMappings:
                        sql.contains("fee_accounting_tender_mappings")?tenderMappings:null;
                if(values==null)throw new AssertionError("Only original configuration mapping reads are allowed.");
                int[] index={-1}; var result=rowCursor(values,index);
                return ((ResultSetExtractor)call.getArgument(2)).extractData(result);
            });
            when(accounting.feeJournalEvidence(eq(user),eq(school),anyList())).thenAnswer(call->journalPresent?Map.of(journal,evidence):Map.of());
            when(accounting.gatewayOriginalPaymentJournalEvidence(eq(org),eq(school),eq(payment),eq(batch),eq(journal)))
                    .thenAnswer(call->journalPresent?evidence:null);
        }

        private static ResultSet row(Map<String,Object> values) throws Exception {
            return rowCursor(List.of(values),new int[]{0});
        }

        private static ResultSet rowCursor(List<Map<String,Object>> values,int[] index) throws Exception {
            var result=mock(ResultSet.class);
            when(result.next()).thenAnswer(ignored->++index[0]<values.size());
            when(result.getObject(anyString(),any(Class.class))).thenAnswer(read->values.get(index[0]).get(read.<String>getArgument(0)));
            when(result.getObject(anyString())).thenAnswer(read->values.get(index[0]).get(read.<String>getArgument(0)));
            when(result.getString(anyString())).thenAnswer(read->{Object value=values.get(index[0]).get(read.<String>getArgument(0));return value==null?null:value.toString();});
            when(result.getBigDecimal(anyString())).thenAnswer(read->(BigDecimal)values.get(index[0]).get(read.<String>getArgument(0)));
            when(result.getBoolean(anyString())).thenAnswer(read->Boolean.TRUE.equals(values.get(index[0]).get(read.<String>getArgument(0))));
            when(result.getInt(anyString())).thenAnswer(read->{Object value=values.get(index[0]).get(read.<String>getArgument(0));return value==null?0:((Number)value).intValue();});
            when(result.getLong(anyString())).thenAnswer(read->{Object value=values.get(index[0]).get(read.<String>getArgument(0));return value==null?0L:((Number)value).longValue();});
            when(result.getTimestamp(anyString())).thenAnswer(read->{Instant value=(Instant)values.get(index[0]).get(read.<String>getArgument(0));return value==null?null:Timestamp.from(value);});
            return result;
        }
    }
}
