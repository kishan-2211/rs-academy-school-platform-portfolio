package com.educore.api.payroll;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A school-scoped, ledger-evidenced employee advance statement. */
public record PayrollAdvanceStatement(UUID schoolId, String schoolName, Instant generatedAt,
        UUID advanceId, String advanceNumber, String advanceType, String purpose,
        UUID staffProfileId, String employeeCode, String staffName,
        LocalDate issueDate, String currencyCode, BigDecimal principalAmount,
        String advanceStatus, BigDecimal postedDisbursementAmount,
        BigDecimal recoveredAmount, BigDecimal outstandingAmount,
        String evidenceStatus, List<Disbursement> disbursements,
        List<Installment> installments) {
    public PayrollAdvanceStatement {
        disbursements = List.copyOf(disbursements);
        installments = List.copyOf(installments);
    }

    public record Disbursement(UUID id, int sequenceNumber, LocalDate postingDate,
            String paymentMethod, String paymentReference, BigDecimal sourceAmount,
            String sourceStatus, UUID accountingJournalId, String journalStatus,
            boolean countsAsDisbursed, Instant preparedAt) {}

    public record Installment(UUID id, int installmentNumber, LocalDate dueMonth,
            BigDecimal plannedAmount, BigDecimal recoveredAmount,
            BigDecimal outstandingAmount, String status,
            List<RecoveryAttempt> recoveryAttempts) {
        public Installment { recoveryAttempts = List.copyOf(recoveryAttempts); }
    }

    public record RecoveryAttempt(UUID id, UUID payrollRunId, String runCode,
            LocalDate payrollMonth, String payrollRunStatus, BigDecimal amount,
            String reservationStatus, Instant reservedAt, Instant releasedAt,
            String releaseReason, String releasedBy, String status,
            List<LedgerPosting> ledgerPostings) {
        public RecoveryAttempt { ledgerPostings = List.copyOf(ledgerPostings); }
    }

    public record LedgerPosting(UUID batchId, LocalDate postingDate,
            String sourceStatus, UUID accountingJournalId, String journalStatus,
            UUID reversalJournalId, String reversalStatus, String evidenceStatus,
            boolean countsAsRecovered) {}
}
