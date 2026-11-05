package com.educore.api.accounting;

import com.educore.api.identity.AuthenticatedUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Narrow service-to-service boundary for operational modules that post to the
 * common accounting book. Operational domains never query accounting tables
 * directly and browser callers cannot choose a journal source module.
 */
public interface AccountingModuleBridge {
    /** Scoped choices only; all money admission is rechecked in a writable transaction. */
    SettlementAccountingOptions settlementAccountingOptions(AuthenticatedUser user, UUID schoolId);

    /** Finance takes its operation/configuration fences before this accounting fence. */
    FeeBookContext validateSettlementPosting(AuthenticatedUser user, UUID schoolId,
            UUID targetFinancialYearId, LocalDate postingOn, List<SettlementAccountUse> accounts,
            List<UUID> sourceJournalIds);

    AccountingMutationResponse prepareSettlementJournal(AuthenticatedUser user, SettlementJournalRequest request);

    Map<UUID, SettlementJournalStatus> settlementJournalStatuses(AuthenticatedUser user, UUID schoolId,
            List<UUID> preparationIds);

    enum SettlementAccountUseType { BANK, CLEARING, EXPENSE, TAX_ASSET, ADJUSTMENT_DEBIT, ADJUSTMENT_CREDIT }
    record SettlementAccountUse(UUID accountId, SettlementAccountUseType useType) {}
    record SettlementFinancialYear(UUID id, String code, String name, LocalDate startsOn, LocalDate endsOn,
            String accountingBasis, String currencyCode) {}
    record SettlementAccountOption(UUID id, String code, String name, String accountType, String category) {
        @Override public String toString() { return "SettlementAccountOption[private scoped chart identity]"; }
    }
    record SettlementAccountingOptions(boolean canPrepare, List<SettlementFinancialYear> financialYears,
            List<SettlementAccountOption> accounts) {
        public SettlementAccountingOptions { financialYears=List.copyOf(financialYears); accounts=List.copyOf(accounts); }
        @Override public String toString() { return "SettlementAccountingOptions[private scoped accounting choices]"; }
    }
    record SettlementJournalLine(UUID accountId, BigDecimal debitAmount, BigDecimal creditAmount, String memo) {
        @Override public String toString() { return "SettlementJournalLine[private resolved accounting line]"; }
    }
    record SettlementJournalRequest(UUID schoolId, UUID financialYearId, UUID accountingPeriodId,
            LocalDate postingDate, String reference, String narration, UUID preparationId, String idempotencyKey,
            String submissionReason, List<SettlementJournalLine> lines) {
        public SettlementJournalRequest { lines=List.copyOf(lines); }
        @Override public String toString() { return "SettlementJournalRequest[private frozen settlement source]"; }
    }
    record SettlementJournalStatus(UUID journalId, String status, long rowVersion, String voucherNumber,
            UUID reversalId, String reversalStatus) {
        @Override public String toString() { return "SettlementJournalStatus[private scoped journal state]"; }
    }

    /** Active INR bank identities for human non-posting settlement evidence only. */
    List<SettlementBankAccount> settlementBankEvidenceAccounts(AuthenticatedUser user, UUID schoolId);

    record SettlementBankAccount(UUID id, String code, String name, String currencyCode) {
        @Override public String toString() { return "SettlementBankAccount[private scoped accounting identity]"; }
    }

    FeeBookContext validateFeeConfigurationDraft(AuthenticatedUser user, UUID schoolId,
            UUID financialYearId, LocalDate effectiveFrom, List<FeeAccountUse> accounts);

    FeeBookContext validateFeeConfigurationDecision(AuthenticatedUser user, UUID schoolId,
            UUID financialYearId, LocalDate effectiveFrom, List<FeeAccountUse> accounts);

    FeeBookContext resolveFeePostingBook(AuthenticatedUser user, UUID schoolId,
            UUID financialYearId, LocalDate postingDate, boolean requireOpenPeriod);

    /**
     * Internal SYSTEM-only, non-posting readiness check for a trusted gateway CREATE.
     * A null expected year resolves the unique date-covered book; subsequent checks
     * provide that year and the approved fee configuration's account uses.
     * This does not approve configuration, prepare a journal or impersonate a user.
     */
    FeeBookContext validateGatewayCheckoutBook(UUID organisationId, UUID schoolId,
            LocalDate businessDate, UUID expectedFinancialYearId, List<FeeAccountUse> accounts);

    /**
     * Human reviewed-refund admission inside the caller's writable transaction.
     * Locks the common accounting book, rechecks live authority and the complete
     * original journal's unreversed state, and validates the current open target
     * book against the original economic account uses. It never posts a journal.
     */
    FeeBookContext validateFeeRefundPosting(AuthenticatedUser user, UUID schoolId,
            UUID originalJournalId, UUID targetFinancialYearId, LocalDate postingOn,
            List<FeeAccountUse> originalAccounts);

    AccountingMutationResponse prepareFeeJournal(AuthenticatedUser user,
            FeeJournalRequest request);

    Map<UUID, String> feeJournalStatuses(AuthenticatedUser user, UUID schoolId,
            List<UUID> journalIds);

    /**
     * Minimal refund-workspace projection for a live scoped fees.read actor.
     * Only FEES journals sourced by this school's REFUND batches are visible;
     * this grants no accounting-book, amount, line, account or posting access.
     */
    Map<UUID, String> feeRefundJournalStatuses(AuthenticatedUser user, UUID schoolId,
            List<UUID> journalIds);

    /**
     * Internal, scoped evidence of complete original FEES journals and any reversal.
     * This read does not decide posting readiness or authorize a financial mutation.
     */
    Map<UUID, FeeJournalEvidence> feeJournalEvidence(AuthenticatedUser user, UUID schoolId,
            List<UUID> journalIds);

    /**
     * Internal SYSTEM-only original online receipt evidence in an existing writable
     * read-committed transaction. Caller takes its finance/configuration fences first.
     * Locks the accounting school fence and rejects pending/actual reversal. Closed
     * historical books remain valid evidence; this does not authorize refund dispatch
     * or any journal mutation and is not an arbitrary accounting reader.
     */
    FeeJournalEvidence gatewayOriginalPaymentJournalEvidence(UUID organisationId, UUID schoolId,
            UUID paymentId, UUID postingBatchId, UUID journalId);

    record FeeJournalEvidence(UUID id, UUID sourceId, UUID financialYearId,
            UUID accountingPeriodId, LocalDate postingDate, String accountingBasis,
            String currencyCode, String status, String financialYearStatus,
            String accountingPeriodStatus, UUID reversalId, String reversalStatus,
            List<FeeJournalLineEvidence> lines, List<FeeJournalLineEvidence> reversalLines) {
        public FeeJournalEvidence {
            lines = List.copyOf(lines);
            reversalLines = List.copyOf(reversalLines);
        }

        @Override
        public String toString() {
            return "FeeJournalEvidence[redacted]";
        }
    }

    /** Debit and credit remain separate even when both exist for one dimension. */
    record FeeJournalLineEvidence(UUID accountId, UUID vendorId, UUID learnerFeeAccountId,
            BigDecimal debitAmount, BigDecimal creditAmount, String accountType,
            String accountCategory, String accountStatus, boolean allowDirectPosting) {
        @Override
        public String toString() {
            return "FeeJournalLineEvidence[redacted]";
        }
    }

    PayrollAccountingOptions payrollAccountingOptions(AuthenticatedUser user, UUID schoolId);

    PayrollBookContext validatePayrollConfigurationDraft(AuthenticatedUser user, UUID schoolId,
            UUID financialYearId, LocalDate effectiveFrom, List<PayrollAccountUse> accounts);

    PayrollBookContext validatePayrollConfigurationDecision(AuthenticatedUser user, UUID schoolId,
            UUID financialYearId, LocalDate effectiveFrom, List<PayrollAccountUse> accounts);

    PayrollBookContext resolvePayrollPostingBook(AuthenticatedUser user, UUID schoolId,
            UUID financialYearId, LocalDate postingDate);

    PayrollBookContext validatePayrollAdvanceDraft(AuthenticatedUser user, UUID schoolId,
            UUID financialYearId, LocalDate issueDate, List<PayrollAccountUse> accounts);

    PayrollBookContext validatePayrollAdvanceDecision(AuthenticatedUser user, UUID schoolId,
            UUID financialYearId, LocalDate issueDate, List<PayrollAccountUse> accounts);

    AccountingMutationResponse preparePayrollJournal(AuthenticatedUser user,
            PayrollJournalRequest request);

    AccountingMutationResponse preparePayrollAdvanceJournal(AuthenticatedUser user,
            PayrollAdvanceJournalRequest request);

    Map<UUID, String> payrollJournalStatuses(AuthenticatedUser user, UUID schoolId,
            List<UUID> journalIds);

    Map<UUID, String> payrollAdvanceJournalStatuses(AuthenticatedUser user, UUID schoolId,
            List<UUID> journalIds);

    Map<UUID, PayrollAdvanceRecoveryEvidence> payrollAdvanceRecoveryEvidence(
            AuthenticatedUser user, UUID schoolId, List<UUID> advanceIds);

    Map<UUID, PayrollJournalEvidence> payrollJournalEvidence(AuthenticatedUser user, UUID schoolId,
            List<UUID> journalIds);

    Map<UUID, PayrollJournalEvidence> payrollAdvanceJournalEvidence(AuthenticatedUser user,
            UUID schoolId, List<UUID> journalIds);

    record PayrollJournalEvidence(UUID id, UUID sourceId, UUID financialYearId,
            LocalDate postingDate, String currencyCode, String status,
            BigDecimal debit, BigDecimal credit, UUID reversalId, String reversalStatus,
            BigDecimal reversalDebit, BigDecimal reversalCredit, boolean reversalOffsetsAccounts) {}

    record PayrollAdvanceRecoveryEvidence(BigDecimal recoveredAmount, int activeReservations) {}

    enum FeeAccountUseType {
        RECEIVABLE,
        CONCESSION,
        INCOME,
        CASH,
        BANK,
        /** Existing bank mappings remain valid; hosted receipts can use a separate clearing asset. */
        ONLINE_TENDER,
        /** New gateway CREATE requires a genuine unsettled clearing asset, not a bank receipt. */
        ONLINE_CLEARING
    }

    record FeeAccountUse(UUID accountId, FeeAccountUseType useType) {}

    record FeeBookContext(UUID financialYearId, UUID accountingPeriodId,
            LocalDate startsOn, LocalDate endsOn, String accountingBasis,
            String currencyCode, String financialYearStatus, String accountingPeriodStatus) {}

    record FeeJournalLine(UUID accountId, UUID learnerFeeAccountId,
            BigDecimal debitAmount, BigDecimal creditAmount, String memo) {}

    record FeeJournalRequest(UUID schoolId, UUID financialYearId,
            UUID accountingPeriodId, LocalDate postingDate, String voucherType,
            String reference, String narration, UUID feePostingBatchId,
            String idempotencyKey, String submissionReason, List<FeeJournalLine> lines) {
        public FeeJournalRequest {
            lines = List.copyOf(lines);
        }
    }

    enum PayrollAccountUseType {
        SALARY_PAYABLE,
        EXPENSE,
        LIABILITY,
        RECEIVABLE,
        CASH,
        BANK
    }

    record PayrollAccountUse(UUID accountId, PayrollAccountUseType useType) {}

    record PayrollFinancialYearOption(UUID id, String code, String displayName,
            LocalDate startsOn, LocalDate endsOn, String accountingBasis,
            String currencyCode, String status) {}

    record PayrollAccountOption(UUID id, String code, String displayName,
            String accountType, String category, String status) {}

    record PayrollAccountingOptions(boolean canConfigure, boolean canPrepare, boolean canApprove,
            List<PayrollFinancialYearOption> financialYears,
            List<PayrollAccountOption> accounts) {
        public PayrollAccountingOptions {
            financialYears = List.copyOf(financialYears);
            accounts = List.copyOf(accounts);
        }
    }

    record PayrollBookContext(UUID financialYearId, UUID accountingPeriodId,
            LocalDate startsOn, LocalDate endsOn, String accountingBasis,
            String currencyCode, String financialYearStatus, String accountingPeriodStatus) {}

    record PayrollJournalLine(UUID accountId, BigDecimal debitAmount,
            BigDecimal creditAmount, String memo) {}

    record PayrollJournalRequest(UUID schoolId, UUID financialYearId,
            UUID accountingPeriodId, LocalDate postingDate, String voucherType,
            String reference, String narration, UUID payrollPostingBatchId,
            String idempotencyKey, String submissionReason, List<PayrollJournalLine> lines) {
        public PayrollJournalRequest {
            lines = List.copyOf(lines);
        }
    }

    record PayrollAdvanceJournalRequest(UUID schoolId, UUID financialYearId,
            UUID accountingPeriodId, LocalDate postingDate, String voucherType,
            String reference, String narration, UUID advancePostingId,
            String idempotencyKey, String submissionReason, List<PayrollJournalLine> lines) {
        public PayrollAdvanceJournalRequest {
            lines = List.copyOf(lines);
        }
    }
}
