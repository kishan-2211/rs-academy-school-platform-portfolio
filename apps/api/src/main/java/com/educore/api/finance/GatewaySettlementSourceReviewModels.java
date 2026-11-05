package com.educore.api.finance;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Historical source identification only: never bank/net reconciliation or posting permission. */
public final class GatewaySettlementSourceReviewModels {
    public static final String STAGE = "SOURCE_IDENTIFICATION_ONLY";
    private GatewaySettlementSourceReviewModels() {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Review(UUID schoolId, UUID requestId, String settlementReference, String reviewStage,
            int pagesRead, long totalRows, long sourceIdentifiedRows, long reviewRequiredRows,
            int page, int pageSize, boolean hasMore, List<Row> items) {
        public Review {
            if (!STAGE.equals(reviewStage) || pagesRead < 1 || pagesRead > 1000 || totalRows < 0 || totalRows > 10000
                    || sourceIdentifiedRows < 0 || reviewRequiredRows < 0
                    || sourceIdentifiedRows + reviewRequiredRows != totalRows) throw invalid();
            GatewaySettlementTerms.page(page, pageSize);
            Objects.requireNonNull(schoolId); Objects.requireNonNull(requestId);
            Objects.requireNonNull(settlementReference);
            items = List.copyOf(items);
            if (items.size() > pageSize) throw invalid();
        }
        @Override public String toString() { return "Review[private source identification only]"; }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Row(UUID sourceReadId, int rowIndex, Instant observedAt, GatewaySettlementModels.Event event,
            String status, List<String> reasons, Source source) {
        public Row {
            Objects.requireNonNull(sourceReadId); Objects.requireNonNull(observedAt); Objects.requireNonNull(event);
            reasons = List.copyOf(reasons);
            if (rowIndex < 0 || rowIndex >= GatewaySettlementProvider.PAGE_SIZE
                    || reasons.stream().distinct().count() != reasons.size()
                    || !GatewaySettlementSourceReviewEvaluator.REASONS.containsAll(reasons)
                    || !("SOURCE_IDENTIFIED".equals(status) && source != null && reasons.isEmpty()
                        || "REVIEW_REQUIRED".equals(status) && source == null && !reasons.isEmpty())) throw invalid();
        }
        @Override public String toString() { return "Row[private reported event and source review]"; }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Source(UUID captureId, UUID paymentId, UUID feeLedgerEntryId, String receiptNumber,
            UUID postingItemId, UUID postingBatchId, UUID configurationId, UUID accountingJournalId,
            UUID clearingAccountId, String clearingAccountCategory, UUID originalFinancialYearId,
            UUID originalAccountingPeriodId, LocalDate originalPostingDate, String accountingBasis,
            String currencyCode, String grossAmount) {
        public Source {
            for (Object required : new Object[]{captureId, paymentId, feeLedgerEntryId, postingItemId, postingBatchId,
                    configurationId, accountingJournalId, clearingAccountId, originalFinancialYearId,
                    originalAccountingPeriodId, originalPostingDate}) Objects.requireNonNull(required);
            if (receiptNumber == null || receiptNumber.isBlank() || !"OTHER".equals(clearingAccountCategory)
                    || !"INR".equals(currencyCode) || !("CASH".equals(accountingBasis) || "ACCRUAL".equals(accountingBasis))
                    || grossAmount == null || !grossAmount.matches("(?:0|[1-9][0-9]*)\\.[0-9]{2}")
                    || new BigDecimal(grossAmount).signum() <= 0
                    || new BigDecimal(grossAmount).compareTo(new BigDecimal("100000000.00")) > 0) throw invalid();
        }
        @Override public String toString() { return "Source[private historical receipt and clearing identity; not posting admission]"; }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Explicit source-identification-only review state required");
    }
}
