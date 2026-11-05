package com.educore.api.finance;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Private historical context only; a candidate never associates a provider event or admits posting. */
public final class GatewaySettlementRefundCandidateModels {
    public static final String STAGE = "HISTORICAL_REFUND_CANDIDATES_ONLY";
    static final Set<String> REASONS = Set.of("UNSUPPORTED_PROVIDER", "REFUND_EVENT_REQUIRED", "UNSUPPORTED_DIRECTION",
            "UNSUCCESSFUL_EVENT", "UNSUPPORTED_CURRENCY", "FRACTIONAL_PAISE", "NON_POSITIVE_AMOUNT",
            "MISSING_PAYMENT_REFERENCES", "ORIGINAL_RECEIPT_UNRESOLVED", "ORIGINAL_PAYMENT_SOURCE_AMBIGUOUS");
    private GatewaySettlementRefundCandidateModels() {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Review(UUID schoolId, UUID requestId, UUID sourceReadId, int rowIndex, String settlementReference,
            String reviewStage, String state, String eventReference, String reportedAmount, String currencyCode,
            String receiptNumber, List<String> reviewReasons, int page, int pageSize, boolean hasMore, List<Candidate> items) {
        public Review {
            Objects.requireNonNull(schoolId); Objects.requireNonNull(requestId); Objects.requireNonNull(sourceReadId);
            Objects.requireNonNull(settlementReference); Objects.requireNonNull(eventReference); Objects.requireNonNull(reportedAmount); Objects.requireNonNull(currencyCode);
            GatewaySettlementTerms.page(page, pageSize);
            reviewReasons = List.copyOf(reviewReasons); items = List.copyOf(items);
            if (!STAGE.equals(reviewStage) || rowIndex < 0 || rowIndex >= GatewaySettlementProvider.PAGE_SIZE
                    || reviewReasons.stream().distinct().count() != reviewReasons.size() || !REASONS.containsAll(reviewReasons)
                    || items.size() > pageSize || hasMore && items.size() != pageSize
                    || !("CANDIDATE_CONTEXT".equals(state) && reviewReasons.isEmpty() && receiptNumber != null && !receiptNumber.isBlank()
                        || "REVIEW_REQUIRED".equals(state) && receiptNumber == null && !reviewReasons.isEmpty() && items.isEmpty() && !hasMore)) throw invalid();
        }
        @Override public String toString() { return "Review[private historical refund candidates; not association or posting admission]"; }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Candidate(UUID refundPostingId, UUID refundId, LocalDate postedOn, String amount, String currencyCode,
            String processedAt, String accountingState, UUID accountingJournalId, String referenceMention) {
        public Candidate {
            Objects.requireNonNull(refundPostingId); Objects.requireNonNull(refundId); Objects.requireNonNull(postedOn);
            if (!"INR".equals(currencyCode) || amount == null || !amount.matches("(?:0|[1-9][0-9]*)\\.[0-9]{2}")
                    || new BigDecimal(amount).signum() <= 0 || new BigDecimal(amount).compareTo(new BigDecimal("100000000.00")) > 0
                    || !("POSTED_SOURCE_PROVEN".equals(accountingState) && accountingJournalId != null
                        || "ACCOUNTING_REVIEW_REQUIRED".equals(accountingState) && accountingJournalId == null)
                    || referenceMention == null || !Set.of("MENTIONED", "NOT_MENTIONED", "NOT_REPORTED").contains(referenceMention)) throw invalid();
            if (processedAt != null) {
                if (processedAt.length() > 30 || !processedAt.endsWith("Z")) throw invalid();
                try { Instant.parse(processedAt); } catch (RuntimeException malformed) { throw invalid(); }
            }
        }
        @Override public String toString() { return "Candidate[private historical completion; not an associated settlement source]"; }
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Explicit historical refund candidate context required"); }
}
