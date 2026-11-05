package com.educore.api.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record FinanceSnapshot(
        UUID schoolId,
        String schoolName,
        String schoolCurrency,
        String schoolTimeZone,
        LocalDate schoolToday,
        LocalDate latestClosedDate,
        boolean canConfigure,
        boolean canCollect,
        boolean canApprove,
        boolean canReverse,
        boolean canProposeDayClose,
        boolean canApproveDayClose,
        String onlinePaymentStatus,
        AgeingSummary ageing,
        DayCloseCandidate dayCloseCandidate,
        List<DayCloseItem> dayCloses,
        List<SessionItem> sessions,
        List<FeeHeadItem> feeHeads,
        List<FeePlanItem> feePlans,
        List<LearnerOption> learners,
        List<AccountItem> accounts,
        List<ConcessionItem> concessions) {

    public FinanceSnapshot {
        dayCloses = List.copyOf(dayCloses);
        sessions = List.copyOf(sessions);
        feeHeads = List.copyOf(feeHeads);
        feePlans = List.copyOf(feePlans);
        learners = List.copyOf(learners);
        accounts = List.copyOf(accounts);
        concessions = List.copyOf(concessions);
    }

    public record SessionItem(UUID id, String name, LocalDate startsOn, LocalDate endsOn, String status) {}
    public record FeeHeadItem(UUID id, String code, String name, String description, String category,
            boolean refundable, String status, long rowVersion) {}
    public record PlanLineItem(UUID id, UUID feeHeadId, String feeHeadName, int sequenceNumber,
            String label, LocalDate dueOn, BigDecimal amount, boolean allowPartial, long rowVersion) {}
    public record FeePlanItem(UUID id, UUID sessionId, UUID supersedesPlanId, String code,
            int revisionNumber, String name, String gradeCode, String currencyCode,
            boolean publicDisclosure, String receiptPrefix, String notes, String status,
            Instant activatedAt, long rowVersion, List<PlanLineItem> items) {
        public FeePlanItem { items = List.copyOf(items); }
    }
    public record LearnerOption(UUID learnerId, UUID enrolmentId, UUID sessionId,
            String learnerName, String admissionNumber, String gradeCode, String sessionName) {}
    public record ChargeItem(UUID id, String feeHeadCode, String feeHeadName, String label,
            LocalDate dueOn, BigDecimal amount, BigDecimal concessionAmount,
            BigDecimal paidAmount, BigDecimal outstandingAmount, boolean allowPartial,
            BigDecimal rteExemptionAmount, BigDecimal reservedAmount) {}
    public record PaymentItem(UUID id, String receiptNumber, String paymentMethod,
            String externalReference, BigDecimal amount, LocalDate receivedOn,
            String notes, String status, Instant createdAt, long rowVersion, ReceiptTiming timing,
            boolean gatewayRefundSource) {}
    /** Public-to-the-authorized-family timing only; never bank evidence, notes or verifier identity. */
    public record ReceiptTiming(LocalDate originalReceivedOn, Instant actualReceivedAt,
            String receivedTimePrecision, String schoolTimeZone) {}
    public record LedgerItem(UUID id, UUID chargeId, String entryType, String direction,
            BigDecimal amount, LocalDate effectiveOn, String description, Instant createdAt) {}
    public record AccountItem(UUID id, UUID learnerId, UUID enrolmentId, UUID sessionId,
            String learnerName, String admissionNumber, String gradeCode, String sessionName,
            String rollNumber, String sectionCode,
            String currencyCode, String status, BigDecimal totalCharges, BigDecimal totalCredits,
            BigDecimal balance, List<ChargeItem> charges, List<PaymentItem> payments,
            List<LedgerItem> ledger) {
        public AccountItem {
            charges = List.copyOf(charges);
            payments = List.copyOf(payments);
            ledger = List.copyOf(ledger);
        }
    }
    public record ConcessionItem(UUID id, UUID accountId, UUID chargeId, String learnerName,
            String feeLabel, String concessionType, BigDecimal amount, String requestReason,
            String status, String requestedBy, String decisionReason, Instant createdAt,
            long rowVersion) {}
    public record AgeingSummary(LocalDate asOf, BigDecimal totalOutstanding,
            int accountCount, int overdueAccountCount, BigDecimal current,
            BigDecimal days1To30, BigDecimal days31To60, BigDecimal days61To90,
            BigDecimal daysOver90, List<AgeingAccountItem> accounts) {
        public AgeingSummary { accounts = List.copyOf(accounts); }
    }
    public record AgeingAccountItem(UUID accountId, String learnerName, String admissionNumber,
            String gradeCode, String sessionName, String currencyCode, BigDecimal current,
            BigDecimal days1To30, BigDecimal days31To60, BigDecimal days61To90,
            BigDecimal daysOver90, BigDecimal totalOutstanding, LocalDate oldestDueOn,
            int maximumDaysOverdue) {}
    public record TenderTotal(String paymentMethod, BigDecimal systemAmount,
            BigDecimal declaredAmount, BigDecimal variance) {}
    public record TenderSystemTotal(String paymentMethod, BigDecimal systemAmount) {}
    public record DayCloseCandidate(LocalDate businessDate, String currencyCode,
            int collectionCount, BigDecimal grossCollections, int reversalCount,
            BigDecimal reversalAmount, BigDecimal netCollections,
            List<TenderSystemTotal> tenderTotals, int refundCount, BigDecimal refundAmount) {
        public DayCloseCandidate { tenderTotals = List.copyOf(tenderTotals); }
    }
    public record DayCloseItem(UUID id, LocalDate businessDate, int revisionNumber,
            UUID supersedesCloseId, String currencyCode, String status, int collectionCount,
            BigDecimal grossCollections, int reversalCount, BigDecimal reversalAmount,
            BigDecimal netCollections, BigDecimal declaredTotal, BigDecimal varianceTotal,
            List<TenderTotal> tenderTotals, String evidenceSha256, String submissionReason,
            String varianceReason, String submittedBy, Instant submittedAt, String decidedBy,
            Instant decidedAt, String decisionReason, long rowVersion, int refundCount, BigDecimal refundAmount) {
        public DayCloseItem { tenderTotals = List.copyOf(tenderTotals); }
    }
}
