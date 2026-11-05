package com.educore.api.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Posting-date movements, not gateway settlements or a replacement general ledger. */
public record CollectionRegisterResponse(UUID schoolId, String currencyCode,
        LocalDate from, LocalDate to, String paymentMethod, String query,
        int page, int pageSize, long total, boolean hasMore, Summary summary,
        List<Tender> tenderTotals, List<Movement> items) {
    public CollectionRegisterResponse {
        tenderTotals = List.copyOf(tenderTotals);
        items = List.copyOf(items);
    }
    public record Summary(long collectionCount, BigDecimal grossCollections,
            long reversalCount, BigDecimal reversalAmount, BigDecimal netCollections,
            long refundCount, BigDecimal refundAmount) {
        public Summary(long collectionCount, BigDecimal grossCollections, long reversalCount,
                BigDecimal reversalAmount, BigDecimal netCollections) {
            this(collectionCount,grossCollections,reversalCount,reversalAmount,netCollections,0,BigDecimal.ZERO.setScale(2));
        }
    }
    public record Tender(String paymentMethod, Summary summary) {}
    public record RefundTiming(LocalDate processedOn, Instant processedAt, String schoolTimeZone) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Movement(UUID id, String kind, UUID paymentId, UUID accountId,
            UUID learnerId, String learnerName, String admissionNumber, String gradeCode,
            String sessionName, String receiptNumber, String paymentMethod, String receiptStatus,
            BigDecimal amount, LocalDate postingOn, Instant recordedAt,
            FinanceSnapshot.ReceiptTiming timing, RefundTiming refundTiming) {
        public Movement(UUID id, String kind, UUID paymentId, UUID accountId, UUID learnerId, String learnerName,
                String admissionNumber, String gradeCode, String sessionName, String receiptNumber, String paymentMethod,
                String receiptStatus, BigDecimal amount, LocalDate postingOn, Instant recordedAt, FinanceSnapshot.ReceiptTiming timing) {
            this(id,kind,paymentId,accountId,learnerId,learnerName,admissionNumber,gradeCode,sessionName,receiptNumber,paymentMethod,
                receiptStatus,amount,postingOn,recordedAt,timing,null);
        }
    }
}
