package com.educore.api.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record FamilyFeesResponse(
        UUID learnerId,
        UUID accountId,
        String academicSessionName,
        String currencyCode,
        String onlinePaymentStatus,
        BigDecimal totalCharges,
        BigDecimal totalCredits,
        BigDecimal balance,
        List<FinanceSnapshot.ChargeItem> charges,
        List<ReceiptItem> receipts,
        List<StatementItem> statement,
        List<RefundItem> refunds) {
    public FamilyFeesResponse {
        charges = List.copyOf(charges);
        receipts = List.copyOf(receipts);
        statement = List.copyOf(statement);
        refunds = List.copyOf(refunds);
    }
    public FamilyFeesResponse(UUID learnerId, UUID accountId, String academicSessionName,
            String currencyCode, String onlinePaymentStatus, BigDecimal totalCharges,
            BigDecimal totalCredits, BigDecimal balance, List<FinanceSnapshot.ChargeItem> charges,
            List<ReceiptItem> receipts, List<StatementItem> statement) {
        this(learnerId, accountId, academicSessionName, currencyCode, onlinePaymentStatus,
                totalCharges, totalCredits, balance, charges, receipts, statement, List.of());
    }
    public record ReceiptItem(UUID paymentId, String receiptNumber, String paymentMethod,
            BigDecimal amount, LocalDate receivedOn, String status, Instant issuedAt,
            FinanceSnapshot.ReceiptTiming timing) {}
    public record StatementItem(UUID id, String entryType, String direction, BigDecimal amount,
            LocalDate effectiveOn, String description, Instant createdAt) {}
    /** Completed financial movements only. Original receipt bytes/amount remain unchanged. */
    public record RefundItem(UUID id, UUID paymentId, String receiptNumber, BigDecimal amount,
            String currencyCode, LocalDate postingOn, String processedAtExact, LocalDate processedOn,
            String schoolTimeZone) {
        @Override public String toString() { return "RefundItem[private own-child completed refund]"; }
    }
}
