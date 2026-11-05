package com.educore.api.payroll;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PayrollAdvanceWorkspace(UUID schoolId, String schoolName,
        boolean canPrepare, boolean canApprove,
        int page, int size, long total,
        List<StaffOption> staff, List<FinancialYearOption> financialYears,
        List<AccountOption> accounts, List<AdvanceItem> advances) {
    public PayrollAdvanceWorkspace {
        staff = List.copyOf(staff);
        financialYears = List.copyOf(financialYears);
        accounts = List.copyOf(accounts);
        advances = List.copyOf(advances);
    }

    public record StaffOption(UUID id, String employeeCode, String displayName,
            LocalDate joinedOn, LocalDate endedOn, String status,
            String designation, String department) {}

    public record FinancialYearOption(UUID id, String code, String displayName,
            LocalDate startsOn, LocalDate endsOn, String accountingBasis,
            String currencyCode, String status) {}

    public record AccountOption(UUID id, String code, String displayName,
            String accountType, String category, String status) {}

    public record InstallmentItem(UUID id, int installmentNumber,
            LocalDate dueMonth, BigDecimal plannedAmount) {}

    public record HistoryItem(UUID id, String eventType, String previousStatus,
            String newStatus, String reason, String actorName, Instant occurredAt) {}

    public record PostingItem(UUID id, int sequenceNumber, LocalDate postingDate,
            String paymentMethod, String paymentReference, BigDecimal sourceAmount,
            UUID accountingJournalId, String journalStatus, String operationalStatus,
            String preparerName, Instant preparedAt, long rowVersion) {}

    public record AdvanceItem(UUID id, UUID staffProfileId, String advanceNumber,
            String advanceType, String purpose, UUID financialYearId,
            UUID accountingPeriodId, LocalDate issueDate, String currencyCode,
            BigDecimal principalAmount, LocalDate recoveryStartMonth,
            int installmentCount, UUID receivableAccountId,
            UUID disbursementAccountId, String paymentMethod,
            String paymentReference, String employeeCode, String staffName,
            String status, String creatorName, Instant createdAt,
            String submitterName, Instant submittedAt, String deciderName,
            Instant decidedAt, String decisionReason, String cancellerName,
            Instant cancelledAt, String cancellationReason,
            BigDecimal postedDisbursementAmount, BigDecimal recoveredAmount,
            BigDecimal outstandingAmount, String recoveryStatus,
            String operationalStatus, long rowVersion,
            boolean canEdit, boolean canSubmit, boolean canCancel,
            boolean canDecide, boolean canPrepareDisbursement,
            List<InstallmentItem> installments, List<HistoryItem> history,
            List<PostingItem> postings) {
        public AdvanceItem {
            installments = List.copyOf(installments);
            history = List.copyOf(history);
            postings = List.copyOf(postings);
        }
    }
}
