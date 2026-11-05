package com.educore.api.finance;

import static com.educore.api.accounting.AccountingModuleBridge.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.educore.api.accounting.AccountingModuleBridge;
import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.tenancy.TenantRequestContext;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
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
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Mocked refund historical-proof checks, not actual PostgreSQL, association or settlement acceptance. */
class CompletedFeeRefundPostingProofTest {
    private static final LocalDate DATE = LocalDate.of(2026, 10, 18);
    private static final Instant CREATED = Instant.parse("2026-10-18T06:00:00Z");
    private static final String SOURCE_HASH = "d".repeat(64), BATCH_HASH = "e".repeat(64);
    private static final List<String> OPTIONAL_NULL_SOURCE_FIELDS = List.of(
            "learnerFeeChargeId", "reversesEntryId", "feeHeadId", "feeHeadCode", "feeHeadName");

    @AfterEach void clearOnlySyntheticTransactionMarkers() { TransactionSynchronizationManager.clear(); }

    @Test void historicalProofIsInternalReadOnlyAndDoesNotBecomeABrowserEndpoint() throws Exception {
        var method = FeeAccountingService.class.getDeclaredMethod("verifyCompletedRefundPosting", AuthenticatedUser.class, UUID.class, UUID.class);
        assertThat(method.getAnnotation(Transactional.class).readOnly()).isTrue();
        assertThat(Modifier.isPublic(method.getModifiers())).isFalse();
    }

    @Test void completeCashRefundReturnsOnlyImmutableIdentityWithoutWritingAnything() throws Exception {
        var f = new Fixture("CASH"); f.install();
        try (var ignored = f.human()) {
            var proof = f.verify();
            assertThat(proof.refundPostingId()).isEqualTo(f.completion);
            assertThat(proof.refundId()).isEqualTo(f.refund);
            assertThat(proof.paymentId()).isEqualTo(f.payment);
            assertThat(proof.feeLedgerEntryId()).isEqualTo(f.ledger);
            assertThat(proof.postingItemId()).isEqualTo(f.item);
            assertThat(proof.postingBatchId()).isEqualTo(f.batch);
            assertThat(proof.configurationId()).isEqualTo(f.configuration);
            assertThat(proof.accountingJournalId()).isEqualTo(f.journal);
            assertThat(proof.clearingAccountId()).isEqualTo(f.clearing);
            assertThat(proof.financialYearId()).isEqualTo(f.year);
            assertThat(proof.accountingPeriodId()).isEqualTo(f.period);
            assertThat(proof.postingOn()).isEqualTo(DATE);
            assertThat(proof.accountingBasis()).isEqualTo("CASH");
            assertThat(proof.currencyCode()).isEqualTo("INR");
            assertThat(proof.refundAmount()).isEqualByComparingTo("25.25");
            assertThat(proof.originalPaymentProof()).isSameAs(f.original);
            assertThat(proof.sourceSha256()).isEqualTo(SOURCE_HASH);
            assertThat(proof.batchEvidenceSha256()).isEqualTo(BATCH_HASH);
        }
        verify(f.service).verifyOriginalPaymentPosting(f.user, f.school, f.payment);
        verify(f.accounting).feeJournalEvidence(f.user, f.school, List.of(f.journal));
        f.assertReadOnly();
    }

    @Test void accrualProofKeepsTheExactOriginalLearnerReceivableDimension() throws Exception {
        var f = new Fixture("ACCRUAL"); f.install();
        try (var ignored = f.human()) {
            assertThat(f.verify().accountingBasis()).isEqualTo("ACCRUAL");
            assertThat(f.evidence.lines()).anySatisfy(line -> {
                assertThat(line.accountId()).isEqualTo(f.receivable);
                assertThat(line.learnerFeeAccountId()).isEqualTo(f.account);
                assertThat(line.debitAmount()).isEqualByComparingTo("25.25");
            });
        }
        f.assertReadOnly();
    }

    @Test void closedHistoricalBooksAndInactiveAccountsRemainValidHistoricalEvidence() throws Exception {
        for (String basis : List.of("CASH", "ACCRUAL")) {
            var f = new Fixture(basis); f.install();
            assertThat(f.evidence.financialYearStatus()).isEqualTo("CLOSED");
            assertThat(f.evidence.accountingPeriodStatus()).isEqualTo("CLOSED");
            assertThat(f.evidence.lines()).allMatch(line -> "INACTIVE".equals(line.accountStatus()) && !line.allowDirectPosting());
            try (var ignored = f.human()) { assertThat(f.verify().refundPostingId()).isEqualTo(f.completion); }
            f.assertReadOnly();
        }
    }

    @Test void laterOpenDateRepairPreservesOriginalRefundSourceDateAndExplicitSupersessionSnapshot() throws Exception {
        var f = new Fixture("CASH"); f.install();
        LocalDate later = DATE.plusDays(4);
        UUID previousBatch = UUID.randomUUID(), previousJournal = UUID.randomUUID();
        f.batchRow.put("business_date", later);
        f.batchRow.put("supersedes_refund_batch_id", previousBatch);
        f.batchRow.put("supersedes_refund_journal_id", previousJournal);
        f.batchSnapshot.put("supersedesRefundBatchId", previousBatch);
        f.batchSnapshot.put("supersedesRefundJournalId", previousJournal);
        f.batchSnapshot.put("originalRefundPostingOn", DATE);
        f.refreshBatch();
        f.evidence = f.journal(f.evidence.lines(), "POSTED", f.batch, later, null, null, List.of());
        try (var ignored = f.human()) {
            assertThat(f.verify().postingOn()).isEqualTo(later);
            assertThat(f.sourceSnapshot.get("effectiveOn")).isEqualTo(DATE);
            assertThat(f.batchSnapshot.get("businessDate")).isEqualTo(DATE);
        }
        f.assertReadOnly();
    }

    @Test void nullIdentityOrMissingOrAmbiguousCompletionFailsPrivately() throws Exception {
        var f = new Fixture("CASH"); f.install();
        try (var ignored = f.human()) {
            invalid(() -> f.service.verifyCompletedRefundPosting(f.user, f.school, null));
            f.sources.clear(); invalid(f::verify);
            f.sources.add(f.sourceRow); f.sources.add(new HashMap<>(f.sourceRow)); invalid(f::verify);
        }
        f.assertReadOnly();
    }

    @Test void absentSystemForeignAndNonTransactionalCallersCannotBorrowAHumanArgument() throws Exception {
        var f = new Fixture("CASH"); f.install();
        denied(f::verify);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try (var ignored = TenantRequestContext.open(null, f.org)) { denied(f::verify); }
        try (var ignored = TenantRequestContext.open(UUID.randomUUID(), f.org)) { denied(f::verify); }
        try (var ignored = TenantRequestContext.open(f.userId, UUID.randomUUID())) { denied(f::verify); }
        try (var ignored = f.human()) {
            TransactionSynchronizationManager.setActualTransactionActive(false); denied(f::verify);
        }
        verifyNoInteractions(f.jdbc, f.accounting, f.audit);
    }

    @Test void actualDatabaseContextAndLiveBothReadCapabilitiesAreRequiredBeforeFinancialReads() throws Exception {
        for (String defect : List.of("CONTEXT", "FEES", "ACCOUNTING", "FOREIGN")) {
            var f = new Fixture("CASH"); f.install();
            if ("CONTEXT".equals(defect)) f.databaseContext = false;
            if ("FEES".equals(defect)) f.scopeAllowed = false;
            if ("ACCOUNTING".equals(defect)) f.liveActor = false;
            try (var ignored = f.human()) {
                denied("FOREIGN".equals(defect) ? () -> f.service.verifyCompletedRefundPosting(f.user, UUID.randomUUID(), f.completion) : f::verify);
            }
            assertThat(f.reads).noneMatch(read -> read.sql().contains("reviewed-refund-accounting-source"));
            verifyNoInteractions(f.accounting, f.audit);
            f.assertReadOnly();
        }
    }

    @Test void retainedVerifiedSuccessMustExistAndMustNotHaveContradictorySuccessWitnesses() throws Exception {
        var f = new Fixture("CASH"); f.install(); f.witnessValid = false;
        try (var ignored = f.human()) { invalid(f::verify); }
        verify(f.service, never()).verifyOriginalPaymentPosting(any(), any(), any());
        assertThat(f.reads.stream().filter(read -> read.sql().contains("completed-refund-proof-witness")).toList()).singleElement().satisfies(read ->
                assertThat(read.sql()).contains("witness.outcome='VERIFIED_SUCCESS'", "success.verified_read_id=completion.verified_read_id",
                        "witness.evidence_sha256=success.evidence_sha256", "contradictory.outcome='REVIEW_REQUIRED'", "contradictory.refund_status='SUCCESS'"));
        f.assertReadOnly();
    }

    @Test void corruptedRefundDebitIdentityCurrencyDirectionOrSubCentValueCannotBecomeProof() throws Exception {
        for (String defect : List.of("ID", "ORIGINAL_ITEM", "ACCOUNT", "EVENT", "DIRECTION", "SOURCE", "CHARGE", "REVERSAL", "TIME", "CURRENCY", "METHOD", "AMOUNT")) {
            var f = new Fixture("CASH"); f.install();
            switch (defect) {
                case "ID" -> f.sourceRow.put("refund_posting_id", UUID.randomUUID());
                case "ORIGINAL_ITEM" -> f.sourceRow.put("original_payment_posting_item_id", UUID.randomUUID());
                case "ACCOUNT" -> f.sourceRow.put("learner_fee_account_id", UUID.randomUUID());
                case "EVENT" -> f.sourceRow.put("entry_type", "PAYMENT");
                case "DIRECTION" -> f.sourceRow.put("direction", "CREDIT");
                case "SOURCE" -> f.sourceRow.put("source_id", UUID.randomUUID());
                case "CHARGE" -> f.sourceRow.put("learner_fee_charge_id", f.charge);
                case "REVERSAL" -> f.sourceRow.put("reverses_entry_id", UUID.randomUUID());
                case "TIME" -> f.sourceRow.put("created_at", null);
                case "CURRENCY" -> f.sourceRow.put("currency_code", "USD");
                case "METHOD" -> f.sourceRow.put("payment_method", "CASH");
                case "AMOUNT" -> f.sourceRow.put("amount", new BigDecimal("25.25001"));
                default -> throw new AssertionError(defect);
            }
            try (var ignored = f.human()) { invalid(f::verify); }
            f.assertReadOnly();
        }
    }

    @Test void originalPaymentProofFailureIsNotReplacedWithACurrentConfiguration() throws Exception {
        var f = new Fixture("CASH"); f.install();
        doThrow(new ApiException(HttpStatus.CONFLICT, "FEE_ORIGINAL_PAYMENT_PROOF_INVALID", "Synthetic invalid original proof"))
                .when(f.service).verifyOriginalPaymentPosting(f.user, f.school, f.payment);
        try (var ignored = f.human()) { invalid(f::verify); }
        assertThat(f.reads).noneMatch(read -> read.sql().contains("completed-refund-proof-batch"));
        f.assertReadOnly();
    }

    @Test void exactlyOneCompleteActiveRefundBatchAndOneMatchingItemAreRequired() throws Exception {
        for (String defect : List.of("NO_BATCH", "TWO_BATCHES", "NO_ITEM", "EXTRA_ITEM", "RELEASED", "WRONG_LEDGER", "SEQUENCE")) {
            var f = new Fixture("CASH"); f.install();
            switch (defect) {
                case "NO_BATCH" -> f.batches.clear();
                case "TWO_BATCHES" -> f.batches.add(new HashMap<>(f.batchRow));
                case "NO_ITEM" -> f.items.clear();
                case "EXTRA_ITEM" -> f.items.add(new HashMap<>(f.itemRow));
                case "RELEASED" -> f.itemRow.put("status", "RELEASED");
                case "WRONG_LEDGER" -> f.itemRow.put("fee_ledger_entry_id", UUID.randomUUID());
                case "SEQUENCE" -> f.itemRow.put("sequence_number", 2);
                default -> throw new AssertionError(defect);
            }
            try (var ignored = f.human()) { invalid(f::verify); }
            f.assertReadOnly();
        }
    }

    @Test void wrongBatchKindConfigurationTotalsCountsDigestsOrRepairShapeCannotPass() throws Exception {
        for (String defect : List.of("EVENT", "DISPOSITION", "CONFIGURATION", "COUNT", "AMOUNT", "SOURCE_HASH", "BATCH_HASH", "REPAIR", "DATE")) {
            var f = new Fixture("CASH"); f.install();
            switch (defect) {
                case "EVENT" -> f.batchRow.put("event_type", "PAYMENT");
                case "DISPOSITION" -> f.batchRow.put("disposition", "NO_JOURNAL");
                case "CONFIGURATION" -> f.batchRow.put("configuration_id", UUID.randomUUID());
                case "COUNT" -> f.batchRow.put("source_count", 2);
                case "AMOUNT" -> f.batchRow.put("source_amount", new BigDecimal("25.24"));
                case "SOURCE_HASH" -> f.batchRow.put("source_sha256", "not-a-digest");
                case "BATCH_HASH" -> f.batchRow.put("evidence_sha256", null);
                case "REPAIR" -> f.batchRow.put("supersedes_refund_batch_id", UUID.randomUUID());
                case "DATE" -> f.batchRow.put("business_date", DATE.plusDays(1));
                default -> throw new AssertionError(defect);
            }
            try (var ignored = f.human()) { invalid(f::verify); }
            f.assertReadOnly();
        }
    }

    @Test void malformedExtraMissingAndSubCentItemOrBatchSnapshotsCannotMatch() throws Exception {
        for (String defect : List.of("MALFORMED", "ITEM_EXTRA", "ITEM_MISSING", "ITEM_PRECISION", "BATCH_EXTRA", "BATCH_SOURCE", "BATCH_ORIGINAL")) {
            var f = new Fixture("CASH"); f.install();
            switch (defect) {
                case "MALFORMED" -> f.itemRow.put("source_snapshot", "{");
                case "ITEM_EXTRA" -> { var s = new LinkedHashMap<>(f.sourceSnapshot); s.put("unexpected", true); f.itemRow.put("source_snapshot", json(s)); }
                case "ITEM_MISSING" -> { var s = new LinkedHashMap<>(f.sourceSnapshot); s.remove("description"); f.itemRow.put("source_snapshot", json(s)); }
                case "ITEM_PRECISION" -> { var s = new LinkedHashMap<>(f.sourceSnapshot); s.put("amount", new BigDecimal("25.250000000000001")); f.itemRow.put("source_snapshot", json(s)); }
                case "BATCH_EXTRA" -> { f.batchSnapshot.put("unexpected", true); f.refreshBatch(); }
                case "BATCH_SOURCE" -> { f.batchSnapshot.put("sourceAmount", new BigDecimal("25.24")); f.refreshBatch(); }
                case "BATCH_ORIGINAL" -> { f.batchSnapshot.put("originalPaymentJournalId", UUID.randomUUID()); f.refreshBatch(); }
                default -> throw new AssertionError(defect);
            }
            try (var ignored = f.human()) { invalid(f::verify); }
            f.assertReadOnly();
        }
    }

    @Test void actualRefundAllocationsCannotBeMissingDuplicatedChangedOrLargerThanOriginal() throws Exception {
        for (String defect : List.of("NONE", "DUPLICATE", "ORIGINAL", "CHARGE", "HEAD", "AMOUNT", "SUB_CENT")) {
            var f = new Fixture("CASH"); f.install();
            switch (defect) {
                case "NONE" -> f.allocations.clear();
                case "DUPLICATE" -> f.allocations.add(new HashMap<>(f.allocationRow));
                case "ORIGINAL" -> f.allocationRow.put("original_payment_allocation_id", UUID.randomUUID());
                case "CHARGE" -> f.allocationRow.put("learner_fee_charge_id", UUID.randomUUID());
                case "HEAD" -> f.allocationRow.put("fee_head_id", UUID.randomUUID());
                case "AMOUNT" -> f.allocationRow.put("amount", new BigDecimal("100.26"));
                case "SUB_CENT" -> f.allocationRow.put("amount", new BigDecimal("25.25001"));
                default -> throw new AssertionError(defect);
            }
            try (var ignored = f.human()) { invalid(f::verify); }
            f.assertReadOnly();
        }
    }

    @Test void frozenLabelsAndJsonKeyOrderRemainValidWithoutReadingCurrentHeadNamesOrRehashingJsonb() throws Exception {
        var f = new Fixture("CASH"); f.install();
        f.allocationRow.put("fee_head_name", "Renamed synthetic head");
        f.allocationRow.put("fee_head_code", "RENAMED");
        f.itemRow.put("source_snapshot", json(reverse(f.sourceSnapshot)));
        f.batchRow.put("evidence_snapshot", json(reverse(f.batchSnapshot)));
        try (var ignored = f.human()) { assertThat(f.verify().sourceSha256()).isEqualTo(SOURCE_HASH); }
        assertThat(f.reads.stream().filter(read -> read.sql().contains("completed-refund-proof-allocations")).toList()).singleElement().satisfies(read ->
                assertThat(read.sql()).doesNotContain("JOIN fee_heads", "display_name", "fee_head_name", "fee_head_code"));
        f.assertReadOnly();
    }

    @Test void applicationNonNullMapperProvesExplicitNullRefundSnapshotsWithoutRewritingHistory() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration.class))
                .withPropertyValues("spring.jackson.default-property-inclusion=non_null")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var mapper = context.getBean(ObjectMapper.class);
                    for (String basis : List.of("CASH", "ACCRUAL")) {
                        var f = new Fixture(basis, mapper); f.install();
                        var applicationShape = mapper.readTree(mapper.writeValueAsString(f.sourceSnapshot));
                        assertThat(OPTIONAL_NULL_SOURCE_FIELDS).allMatch(field -> !applicationShape.has(field));
                        assertFrozenHistoryReadOnly(f, () -> {
                            var proof = f.verify();
                            assertThat(proof.refundPostingId()).isEqualTo(f.completion);
                            assertThat(proof.sourceSha256()).isEqualTo(SOURCE_HASH);
                            assertThat(proof.batchEvidenceSha256()).isEqualTo(BATCH_HASH);
                        });
                    }
                });
    }

    @Test void plainMapperProvesOmittedAndMixedKnownNullRefundFieldsInItemAndBatch() throws Exception {
        for (String basis : List.of("CASH", "ACCRUAL")) {
            for (String shape : List.of("OMITTED", "ITEM_OMITTED", "BATCH_OMITTED", "MIXED")) {
                var f = new Fixture(basis); f.install();
                var itemSource = new LinkedHashMap<>(f.sourceSnapshot);
                var batchSource = new LinkedHashMap<>(f.sourceSnapshot);
                for (int index = 0; index < OPTIONAL_NULL_SOURCE_FIELDS.size(); index++) {
                    String field = OPTIONAL_NULL_SOURCE_FIELDS.get(index);
                    if (Set.of("OMITTED", "ITEM_OMITTED").contains(shape) || "MIXED".equals(shape) && index % 2 == 0)
                        itemSource.remove(field);
                    if (Set.of("OMITTED", "BATCH_OMITTED").contains(shape) || "MIXED".equals(shape) && index % 2 != 0)
                        batchSource.remove(field);
                }
                frozenSources(f, itemSource, batchSource);
                assertFrozenHistoryReadOnly(f, () -> {
                    var proof = f.verify();
                    assertThat(proof.refundPostingId()).isEqualTo(f.completion);
                    assertThat(proof.refundAmount()).isEqualByComparingTo("25.25");
                    assertThat(proof.accountingBasis()).isEqualTo(basis);
                });
            }
        }
    }

    @Test void nullCompatibilityRejectsUnknownNullFieldsInRefundItemBatchAndAllocations() throws Exception {
        for (String location : List.of("ITEM", "BATCH_SOURCE", "BATCH", "ITEM_ALLOCATION", "BATCH_ALLOCATION")) {
            var f = new Fixture("CASH"); f.install();
            var itemSource = new LinkedHashMap<>(f.sourceSnapshot);
            var batchSource = new LinkedHashMap<>(f.sourceSnapshot);
            if ("ITEM".equals(location)) itemSource.put("unexpected", null);
            else if ("BATCH_SOURCE".equals(location)) batchSource.put("unexpected", null);
            else if ("BATCH".equals(location)) f.batchSnapshot.put("unexpected", null);
            else {
                var source = "ITEM_ALLOCATION".equals(location) ? itemSource : batchSource;
                var allocation = copiedAllocation(source); allocation.put("unexpected", null);
                source.put("allocations", List.of(allocation));
            }
            frozenSources(f, itemSource, batchSource);
            assertFrozenHistoryReadOnly(f, () -> invalid(f::verify));
        }
    }

    @Test void nullCompatibilityDoesNotMakeRequiredRefundSourceFieldsOptional() throws Exception {
        var requiredFields = new Fixture("CASH").sourceSnapshot.keySet().stream()
                .filter(field -> !OPTIONAL_NULL_SOURCE_FIELDS.contains(field)).toList();
        for (String location : List.of("ITEM", "BATCH_SOURCE")) for (String field : requiredFields) {
            var f = new Fixture("CASH"); f.install();
            var itemSource = new LinkedHashMap<>(f.sourceSnapshot);
            var batchSource = new LinkedHashMap<>(f.sourceSnapshot);
            ("ITEM".equals(location) ? itemSource : batchSource).remove(field);
            frozenSources(f, itemSource, batchSource);
            assertFrozenHistoryReadOnly(f, () -> invalid(f::verify));
        }
    }

    @Test void nullCompatibilityRejectsNonNullValuesInKnownNullRefundFields() throws Exception {
        for (String location : List.of("ITEM", "BATCH_SOURCE")) for (String field : OPTIONAL_NULL_SOURCE_FIELDS) {
            var f = new Fixture("CASH"); f.install();
            var itemSource = new LinkedHashMap<>(f.sourceSnapshot);
            var batchSource = new LinkedHashMap<>(f.sourceSnapshot);
            ("ITEM".equals(location) ? itemSource : batchSource).put(field,
                    field.endsWith("Id") ? UUID.randomUUID() : "Unexpected synthetic value");
            frozenSources(f, itemSource, batchSource);
            assertFrozenHistoryReadOnly(f, () -> invalid(f::verify));
        }
    }

    @Test void nullCompatibilityNeverAppliesToRequiredNestedRefundAllocationFields() throws Exception {
        for (String location : List.of("ITEM", "BATCH_SOURCE")) {
            for (String field : List.of("learnerFeeChargeId", "feeHeadId", "feeHeadCode", "feeHeadName", "amount")) {
                var f = new Fixture("CASH"); f.install();
                var itemSource = new LinkedHashMap<>(f.sourceSnapshot);
                var batchSource = new LinkedHashMap<>(f.sourceSnapshot);
                var source = "ITEM".equals(location) ? itemSource : batchSource;
                var allocation = copiedAllocation(source); allocation.put(field, null);
                source.put("allocations", List.of(allocation));
                frozenSources(f, itemSource, batchSource);
                assertFrozenHistoryReadOnly(f, () -> invalid(f::verify));
            }
        }
    }

    @Test void compatibleNullShapesStillRejectRefundAmountAndTimestampTampering() throws Exception {
        for (String location : List.of("ITEM", "BATCH_SOURCE")) {
            for (String defect : List.of("SUB_CENT", "AMOUNT", "CREATED_AT", "EFFECTIVE_ON")) {
                var f = new Fixture("CASH"); f.install();
                var itemSource = new LinkedHashMap<>(f.sourceSnapshot);
                var batchSource = new LinkedHashMap<>(f.sourceSnapshot);
                OPTIONAL_NULL_SOURCE_FIELDS.forEach(itemSource::remove);
                OPTIONAL_NULL_SOURCE_FIELDS.forEach(batchSource::remove);
                var source = "ITEM".equals(location) ? itemSource : batchSource;
                switch (defect) {
                    case "SUB_CENT" -> source.put("amount", new BigDecimal("25.250000000000001"));
                    case "AMOUNT" -> source.put("amount", new BigDecimal("25.26"));
                    case "CREATED_AT" -> source.put("createdAt", CREATED.plusNanos(1));
                    case "EFFECTIVE_ON" -> source.put("effectiveOn", DATE.plusDays(1));
                    default -> throw new AssertionError(defect);
                }
                frozenSources(f, itemSource, batchSource);
                assertFrozenHistoryReadOnly(f, () -> invalid(f::verify));
            }
        }
    }

    private static void frozenSources(Fixture f, Map<String, Object> itemSource, Map<String, Object> batchSource) {
        f.itemRow.put("source_snapshot", json(itemSource));
        var batch = new LinkedHashMap<>(f.batchSnapshot); batch.put("sources", List.of(batchSource));
        f.batchRow.put("evidence_snapshot", json(batch));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copiedAllocation(Map<String, Object> source) {
        return new LinkedHashMap<>(((List<Map<String, Object>>) source.get("allocations")).getFirst());
    }

    private static void assertFrozenHistoryReadOnly(Fixture f, Runnable assertion) {
        Object itemHistory = f.itemRow.get("source_snapshot"), batchHistory = f.batchRow.get("evidence_snapshot");
        try (var ignored = f.human()) { assertion.run(); }
        assertThat(f.itemRow.get("source_snapshot")).isEqualTo(itemHistory);
        assertThat(f.batchRow.get("evidence_snapshot")).isEqualTo(batchHistory);
        assertThat(f.itemRow.get("source_sha256")).isEqualTo(SOURCE_HASH);
        assertThat(f.batchRow.get("source_sha256")).isEqualTo(SOURCE_HASH);
        assertThat(f.batchRow.get("evidence_sha256")).isEqualTo(BATCH_HASH);
        f.assertReadOnly();
    }

    @Test void missingSubmittedReversedOrCounterJournalCannotClaimAnIndependentlyPostedRefund() throws Exception {
        for (String defect : List.of("MISSING", "SUBMITTED", "REVERSED", "COUNTER", "SOURCE", "DATE", "CURRENCY", "YEAR", "PERIOD")) {
            var f = new Fixture("CASH"); f.install();
            if ("MISSING".equals(defect)) f.journalPresent = false;
            else f.evidence = new FeeJournalEvidence(f.journal, "SOURCE".equals(defect) ? UUID.randomUUID() : f.batch,
                    "YEAR".equals(defect) ? UUID.randomUUID() : f.year, "PERIOD".equals(defect) ? UUID.randomUUID() : f.period,
                    "DATE".equals(defect) ? DATE.plusDays(1) : DATE, f.basis, "CURRENCY".equals(defect) ? "USD" : "INR",
                    List.of("SUBMITTED", "REVERSED").contains(defect) ? defect : "POSTED", "CLOSED", "CLOSED",
                    "COUNTER".equals(defect) ? UUID.randomUUID() : null, "COUNTER".equals(defect) ? "POSTED" : null,
                    f.evidence.lines(), "COUNTER".equals(defect) ? f.evidence.lines() : List.of());
            try (var ignored = f.human()) { invalid(f::verify); }
            f.assertReadOnly();
        }
    }

    @Test void journalAmountsAndGrossSidesAndVendorOrLearnerDimensionsMustMatchExactly() throws Exception {
        for (String basis : List.of("CASH", "ACCRUAL")) for (String defect : List.of("AMOUNT", "GROSS", "VENDOR", "LEARNER", "ACCOUNT", "EXTRA")) {
            var f = new Fixture(basis); f.install();
            var lines = new ArrayList<>(f.evidence.lines()); var line = lines.getFirst();
            lines.set(0, new FeeJournalLineEvidence("ACCOUNT".equals(defect) ? UUID.randomUUID() : line.accountId(),
                    "VENDOR".equals(defect) ? UUID.randomUUID() : line.vendorId(),
                    "LEARNER".equals(defect) ? UUID.randomUUID() : line.learnerFeeAccountId(),
                    "AMOUNT".equals(defect) ? new BigDecimal("25.24") : "GROSS".equals(defect) ? new BigDecimal("30.25") : line.debitAmount(),
                    "GROSS".equals(defect) ? new BigDecimal("5.00") : line.creditAmount(),
                    line.accountType(), line.accountCategory(), line.accountStatus(), line.allowDirectPosting()));
            if ("EXTRA".equals(defect)) lines.add(line);
            f.evidence = f.journal(lines, "POSTED", f.batch, DATE, null, null, List.of());
            try (var ignored = f.human()) { invalid(f::verify); }
            f.assertReadOnly();
        }
    }

    @Test void knownAuthorityFailuresArePrivateButOutagesAndUnrelatedErrorsAreNotSwallowed() throws Exception {
        for (String code : List.of("ACCOUNTING_SCOPE_DENIED", "FEE_ACCOUNTING_SCOPE_DENIED")) {
            var f = new Fixture("CASH"); f.install();
            when(f.accounting.feeJournalEvidence(any(), any(), anyList())).thenThrow(new ApiException(HttpStatus.FORBIDDEN, code, "Synthetic denied scope"));
            try (var ignored = f.human()) { denied(f::verify); }
            f.assertReadOnly();
        }
        for (RuntimeException failure : List.of(new org.springframework.dao.DataAccessResourceFailureException("Synthetic unavailable read"),
                new ApiException(HttpStatus.CONFLICT, "SYNTHETIC_OTHER_RULE", "Synthetic unrelated conflict"))) {
            var f = new Fixture("CASH"); f.install();
            when(f.accounting.feeJournalEvidence(any(), any(), anyList())).thenThrow(failure);
            try (var ignored = f.human()) { assertThatThrownBy(f::verify).isSameAs(failure); }
            f.assertReadOnly();
        }
    }

    @Test void noAdmissionLocksProviderOrAccountingQueriesAreSmuggledIntoTheHistoricalProof() throws Exception {
        var f = new Fixture("CASH"); f.install();
        try (var ignored = f.human()) {
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
            var proof = f.verify();
            assertThat(proof.toString()).doesNotContain(f.completion.toString(), f.payment.toString(), "25.25", SOURCE_HASH, BATCH_HASH);
        }
        assertThat(f.reads).noneMatch(read -> read.sql().contains("accounting_journals") || read.sql().contains("accounting_reversal_requests")
                || read.sql().contains("pg_advisory") || read.sql().contains("touch_") || read.sql().contains("set_config"));
        assertThat(f.reads.stream().filter(read -> read.sql().contains("completed-refund-proof-batch")).toList()).singleElement().satisfies(read -> {
            assertThat(read.sql()).contains("LIMIT 2");
            assertThat(read.parameters()).containsEntry("organisationId", f.org).containsEntry("schoolId", f.school);
        });
        f.assertReadOnly();
    }

    private static void invalid(Runnable action) { error(action, "FEE_COMPLETED_REFUND_PROOF_INVALID", HttpStatus.CONFLICT); }
    private static void denied(Runnable action) { error(action, "FEE_COMPLETED_REFUND_PROOF_SCOPE_DENIED", HttpStatus.FORBIDDEN); }
    private static void error(Runnable action, String code, HttpStatus status) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, failure -> {
            assertThat(failure.code()).isEqualTo(code); assertThat(failure.status()).isEqualTo(status);
        });
    }
    private static String json(Object value) { return JsonMapper.builder().build().writeValueAsString(value); }
    private static Map<String, Object> reverse(Map<String, Object> value) {
        var keys = new ArrayList<>(value.keySet()); Collections.reverse(keys);
        var result = new LinkedHashMap<String, Object>(); keys.forEach(key -> result.put(key, value.get(key))); return result;
    }
    private record Read(String sql, Map<String, Object> parameters) {}

    private static final class Fixture {
        final UUID org = UUID.randomUUID(), school = UUID.randomUUID(), userId = UUID.randomUUID(), membership = UUID.randomUUID();
        final UUID completion = UUID.randomUUID(), refund = UUID.randomUUID(), payment = UUID.randomUUID(), ledger = UUID.randomUUID();
        final UUID item = UUID.randomUUID(), batch = UUID.randomUUID(), journal = UUID.randomUUID(), configuration = UUID.randomUUID();
        final UUID originalItem = UUID.randomUUID(), originalBatch = UUID.randomUUID(), originalJournal = UUID.randomUUID(), originalLedger = UUID.randomUUID();
        final UUID year = UUID.randomUUID(), period = UUID.randomUUID(), originalYear = UUID.randomUUID(), originalPeriod = UUID.randomUUID();
        final UUID account = UUID.randomUUID(), clearing = UUID.randomUUID(), income = UUID.randomUUID(), receivable = UUID.randomUUID();
        final UUID charge = UUID.randomUUID(), head = UUID.randomUUID(), originalAllocation = UUID.randomUUID();
        final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        final AccountingModuleBridge accounting = mock(AccountingModuleBridge.class);
        final SecurityAuditService audit = mock(SecurityAuditService.class);
        final AuthenticatedUser user = new AuthenticatedUser(userId, UUID.randomUUID(), "synthetic@example.test", null, "Synthetic actor", true,
                org, Set.of(membership), Set.of("fees.read", "accounting.read"), List.of());
        final FeeAccountingService service;
        final String basis;
        final FeeAccountingService.OriginalPaymentPostingProof original;
        final Map<String, Object> sourceRow = new HashMap<>(), batchRow = new HashMap<>(), itemRow = new HashMap<>(), allocationRow = new HashMap<>();
        final Map<String, Object> sourceSnapshot = new LinkedHashMap<>(), batchSnapshot = new LinkedHashMap<>();
        final List<Map<String, Object>> sources = new ArrayList<>(), batches = new ArrayList<>(), items = new ArrayList<>(), allocations = new ArrayList<>();
        final List<Read> reads = new ArrayList<>();
        FeeJournalEvidence evidence;
        boolean databaseContext = true, scopeAllowed = true, liveActor = true, witnessValid = true, journalPresent = true;

        Fixture(String basis) {
            this(basis, JsonMapper.builder().build());
        }

        Fixture(String basis, ObjectMapper mapper) {
            this.basis = basis;
            service = spy(new FeeAccountingService(jdbc, accounting, audit, mapper, Clock.fixed(CREATED, ZoneOffset.UTC)));
            original = new FeeAccountingService.OriginalPaymentPostingProof(payment, originalLedger, originalItem, originalBatch,
                    configuration, originalJournal, originalYear, originalPeriod, DATE.minusDays(3), basis, "INR", clearing,
                    "ACCRUAL".equals(basis) ? receivable : null, Map.of(head, income),
                    List.of(new FeeAccountingService.OriginalPaymentAllocation(originalAllocation, charge, head, new BigDecimal("100.25"))),
                    "a".repeat(64), "b".repeat(64));
            sourceSnapshot.put("feeLedgerEntryId", ledger); sourceSnapshot.put("learnerFeeAccountId", account); sourceSnapshot.put("learnerFeeChargeId", null);
            sourceSnapshot.put("eventType", "REFUND"); sourceSnapshot.put("direction", "DEBIT"); sourceSnapshot.put("amount", new BigDecimal("25.25"));
            sourceSnapshot.put("effectiveOn", DATE); sourceSnapshot.put("description", "Synthetic verified refund"); sourceSnapshot.put("sourceType", "GATEWAY_REFUND_POSTING");
            sourceSnapshot.put("sourceId", completion); sourceSnapshot.put("reversesEntryId", null); sourceSnapshot.put("createdAt", CREATED); sourceSnapshot.put("currencyCode", "INR");
            sourceSnapshot.put("feeHeadId", null); sourceSnapshot.put("feeHeadCode", null); sourceSnapshot.put("feeHeadName", null);
            sourceSnapshot.put("paymentMethod", "ONLINE_PROVIDER"); sourceSnapshot.put("receiptNumber", "SYN-000001");
            sourceSnapshot.put("allocations", List.of(Map.of("learnerFeeChargeId", charge, "feeHeadId", head, "feeHeadCode", "FROZEN_TUITION",
                    "feeHeadName", "Synthetic frozen tuition label", "amount", new BigDecimal("25.25"))));
            sourceRow.put("refund_posting_id", completion); sourceRow.put("refund_request_id", refund); sourceRow.put("fee_payment_id", payment);
            sourceRow.put("completion_account_id", account); sourceRow.put("original_payment_posting_item_id", originalItem); sourceRow.put("posted_on", DATE);
            sourceRow.put("id", ledger); sourceRow.put("learner_fee_account_id", account); sourceRow.put("learner_fee_charge_id", null);
            sourceRow.put("entry_type", "REFUND"); sourceRow.put("direction", "DEBIT"); sourceRow.put("amount", new BigDecimal("25.25"));
            sourceRow.put("effective_on", DATE); sourceRow.put("description", "Synthetic verified refund"); sourceRow.put("source_type", "GATEWAY_REFUND_POSTING");
            sourceRow.put("source_id", completion); sourceRow.put("reverses_entry_id", null); sourceRow.put("created_at", CREATED); sourceRow.put("currency_code", "INR");
            sourceRow.put("payment_method", "ONLINE_PROVIDER"); sourceRow.put("receipt_number", "SYN-000001"); sources.add(sourceRow);
            batchSnapshot.put("schemaVersion", 1); batchSnapshot.put("configurationId", configuration); batchSnapshot.put("financialYearId", year);
            batchSnapshot.put("accountingPeriodId", period); batchSnapshot.put("accountingBasis", basis); batchSnapshot.put("currencyCode", "INR");
            batchSnapshot.put("businessDate", DATE); batchSnapshot.put("eventType", "REFUND"); batchSnapshot.put("sourceCount", 1);
            batchSnapshot.put("sourceAmount", new BigDecimal("25.25")); batchSnapshot.put("sources", List.of(sourceSnapshot));
            batchSnapshot.put("refundPostingId", completion); batchSnapshot.put("originalPaymentPostingItemId", originalItem);
            batchSnapshot.put("originalPaymentPostingBatchId", originalBatch); batchSnapshot.put("originalPaymentJournalId", originalJournal);
            batchSnapshot.put("originalSourceSha256", "a".repeat(64)); batchSnapshot.put("originalBatchEvidenceSha256", "b".repeat(64));
            batchRow.put("id", batch); batchRow.put("item_id", item); batchRow.put("configuration_id", configuration); batchRow.put("financial_year_id", year);
            batchRow.put("accounting_period_id", period); batchRow.put("business_date", DATE); batchRow.put("event_type", "REFUND"); batchRow.put("disposition", "JOURNAL");
            batchRow.put("source_count", 1); batchRow.put("source_amount", new BigDecimal("25.25")); batchRow.put("accounting_journal_id", journal);
            batchRow.put("evidence_sha256", BATCH_HASH); batchRow.put("refund_posting_id", completion); batchRow.put("original_payment_posting_item_id", originalItem);
            batchRow.put("source_sha256", SOURCE_HASH); refreshBatch(); batches.add(batchRow);
            itemRow.put("id", item); itemRow.put("fee_ledger_entry_id", ledger); itemRow.put("sequence_number", 1); itemRow.put("status", "ACTIVE");
            itemRow.put("source_snapshot", json(sourceSnapshot)); itemRow.put("source_sha256", SOURCE_HASH); items.add(itemRow);
            allocationRow.put("original_payment_allocation_id", originalAllocation); allocationRow.put("learner_fee_charge_id", charge);
            allocationRow.put("fee_head_id", head); allocationRow.put("amount", new BigDecimal("25.25")); allocations.add(allocationRow);
            var lines = List.of(line("ACCRUAL".equals(basis) ? receivable : income, "ACCRUAL".equals(basis) ? account : null,
                    "25.25", "0.00", "ACCRUAL".equals(basis) ? "ASSET" : "INCOME", "ACCRUAL".equals(basis) ? "RECEIVABLE" : "INCOME"),
                    line(clearing, null, "0.00", "25.25", "ASSET", "OTHER"));
            evidence = journal(lines, "POSTED", batch, DATE, null, null, List.of());
        }

        void refreshBatch() { batchRow.put("evidence_snapshot", json(batchSnapshot)); }
        FeeAccountingService.CompletedRefundPostingProof verify() { return service.verifyCompletedRefundPosting(user, school, completion); }
        TenantRequestContext.Scope human() {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
            return TenantRequestContext.open(userId, org);
        }
        FeeJournalEvidence journal(List<FeeJournalLineEvidence> lines, String status, UUID source, LocalDate postingOn,
                UUID reversal, String reversalStatus, List<FeeJournalLineEvidence> reversed) {
            return new FeeJournalEvidence(journal, source, year, period, postingOn, basis, "INR", status, "CLOSED", "CLOSED", reversal, reversalStatus, lines, reversed);
        }
        private FeeJournalLineEvidence line(UUID id, UUID learner, String debit, String credit, String type, String category) {
            return new FeeJournalLineEvidence(id, null, learner, new BigDecimal(debit), new BigDecimal(credit), type, category, "INACTIVE", false);
        }
        void assertReadOnly() {
            Mockito.verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
            verifyNoInteractions(audit);
            assertThat(reads).noneMatch(read -> read.sql().matches("(?is).*\\b(INSERT|UPDATE|DELETE)\\b.*"));
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        void install() throws Exception {
            doReturn(original).when(service).verifyOriginalPaymentPosting(user, school, payment);
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Boolean.class))).thenAnswer(call -> {
                String sql = call.getArgument(0); record(sql, call.getArgument(1));
                if (sql.contains("completed-refund-proof-live-actor")) return liveActor;
                if (sql.contains("completed-refund-proof-witness")) return witnessValid;
                return databaseContext;
            });
            when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class))).thenAnswer(call -> {
                String sql = call.getArgument(0); MapSqlParameterSource params = call.getArgument(1); record(sql, params);
                List<Map<String, Object>> selected;
                if (sql.contains("FROM memberships membership")) {
                    if (!scopeAllowed || !school.equals(params.getValue("schoolId"))) return List.of();
                    selected = List.of(Map.of("school_id", school, "display_name", "Synthetic school", "membership_id", membership, "time_zone", "Asia/Kolkata"));
                } else if (sql.contains("reviewed-refund-accounting-source")) selected = sources;
                else if (sql.contains("completed-refund-proof-batch")) selected = batches;
                else if (sql.contains("completed-refund-proof-items")) selected = items;
                else if (sql.contains("completed-refund-proof-allocations")) selected = allocations;
                else throw new AssertionError("Only named scoped refund-proof reads are allowed");
                RowMapper mapper = call.getArgument(2); var result = new ArrayList<>();
                for (var value : selected) result.add(mapper.mapRow(row(value), result.size()));
                return result;
            });
            when(accounting.feeJournalEvidence(eq(user), eq(school), anyList())).thenAnswer(call -> journalPresent ? Map.of(journal, evidence) : Map.of());
        }
        private void record(String sql, MapSqlParameterSource params) { reads.add(new Read(sql, new LinkedHashMap<>(params.getValues()))); }
        private static ResultSet row(Map<String, Object> values) throws Exception {
            var row = mock(ResultSet.class);
            when(row.getObject(anyString(), any(Class.class))).thenAnswer(call -> values.get(call.<String>getArgument(0)));
            when(row.getString(anyString())).thenAnswer(call -> { var value = values.get(call.<String>getArgument(0)); return value == null ? null : value.toString(); });
            when(row.getBigDecimal(anyString())).thenAnswer(call -> values.get(call.<String>getArgument(0)));
            when(row.getBoolean(anyString())).thenAnswer(call -> Boolean.TRUE.equals(values.get(call.<String>getArgument(0))));
            when(row.getInt(anyString())).thenAnswer(call -> { var value = values.get(call.<String>getArgument(0)); return value == null ? 0 : ((Number) value).intValue(); });
            when(row.getTimestamp(anyString())).thenAnswer(call -> { var value = (Instant) values.get(call.<String>getArgument(0)); return value == null ? null : Timestamp.from(value); });
            return row;
        }
    }
}
