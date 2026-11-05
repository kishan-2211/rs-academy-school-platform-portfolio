package com.educore.api.accounting;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record AccountingSnapshot(
        UUID schoolId,
        String schoolName,
        boolean canConfigure,
        boolean canPrepare,
        boolean canPost,
        boolean canClose,
        boolean canRequestReversal,
        boolean canApproveReversal,
        UUID selectedFinancialYearId,
        LocalDate from,
        LocalDate to,
        List<FinancialYearItem> financialYears,
        List<AccountItem> accounts,
        List<VendorItem> vendors,
        List<JournalItem> journals,
        List<ReversalRequestItem> reversalRequests,
        List<TrialBalanceItem> trialBalance,
        List<LedgerLineItem> generalLedger,
        StatementSummary statements) {

    public AccountingSnapshot {
        financialYears = List.copyOf(financialYears);
        accounts = List.copyOf(accounts);
        vendors = List.copyOf(vendors);
        journals = List.copyOf(journals);
        reversalRequests = List.copyOf(reversalRequests);
        trialBalance = List.copyOf(trialBalance);
        generalLedger = List.copyOf(generalLedger);
    }

    public record FinancialYearItem(UUID id, String code, String name, LocalDate startsOn,
            LocalDate endsOn, String accountingBasis, String currencyCode, String status,
            String creatorName, String openerName, Instant openedAt, String closerName,
            Instant closedAt, long rowVersion, List<PeriodItem> periods) {
        public FinancialYearItem {
            periods = List.copyOf(periods);
        }
    }

    public record PeriodItem(UUID id, int periodNumber, String code, String name,
            LocalDate startsOn, LocalDate endsOn, String status, long rowVersion) {}

    public record AccountItem(UUID id, UUID parentAccountId, String code, String name,
            String description, String accountType, String normalBalance, String category,
            boolean allowDirectPosting, String status, long rowVersion) {}

    public record VendorItem(UUID id, String code, String name, String contactName,
            String phone, String email, String address, String status, long rowVersion) {}

    public record JournalItem(UUID id, UUID financialYearId, UUID accountingPeriodId,
            String voucherType, String voucherNumber, LocalDate postingDate, String reference,
            String narration, String sourceModule, UUID sourceId, UUID reversalOfJournalId,
            String status, Integer lineCount, BigDecimal totalAmount, String creatorName,
            String submitterName, Instant submittedAt, String deciderName, Instant decidedAt,
            long rowVersion, List<JournalLineItem> lines, List<JournalHistoryItem> history) {
        public JournalItem {
            lines = List.copyOf(lines);
            history = List.copyOf(history);
        }
    }

    public record JournalLineItem(UUID id, int lineNumber, UUID accountId, String accountCode,
            String accountName, String accountType, UUID vendorId, UUID learnerFeeAccountId,
            BigDecimal debitAmount, BigDecimal creditAmount, String memo) {}

    public record JournalHistoryItem(String fromStatus, String toStatus, String reason,
            String actorName, Instant occurredAt) {}

    public record ReversalRequestItem(UUID id, UUID accountingJournalId,
            UUID requestedAccountingPeriodId, LocalDate requestedPostingDate,
            String requestReason, String status, String requesterName, String deciderName,
            String decisionReason, UUID reversalJournalId, Instant createdAt, long rowVersion) {}

    public record TrialBalanceItem(UUID accountId, String accountCode, String accountName,
            String accountType, String category, BigDecimal debitMovements,
            BigDecimal creditMovements, BigDecimal debitBalance, BigDecimal creditBalance) {}

    public record LedgerLineItem(UUID journalId, String voucherNumber, String voucherType,
            LocalDate postingDate, UUID accountId, String accountCode, String accountName,
            UUID vendorId, String vendorName, UUID learnerFeeAccountId,
            BigDecimal debitAmount, BigDecimal creditAmount, String narration, String memo) {}

    public record StatementSummary(BigDecimal trialDebit, BigDecimal trialCredit,
            BigDecimal assets, BigDecimal liabilities, BigDecimal fundBalance,
            BigDecimal income, BigDecimal expenses, BigDecimal periodSurplus,
            BigDecimal cash, BigDecimal bank, BigDecimal receivables, BigDecimal payables) {}
}
