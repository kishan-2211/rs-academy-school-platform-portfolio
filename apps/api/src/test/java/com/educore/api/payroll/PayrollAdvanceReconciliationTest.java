package com.educore.api.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.educore.api.accounting.AccountingModuleBridge.PayrollAdvanceRecoveryEvidence;
import com.educore.api.accounting.AccountingModuleBridge.PayrollJournalEvidence;
import com.educore.api.payroll.PayrollAdvanceReconciliationReader.AdvanceSource;
import com.educore.api.payroll.PayrollAdvanceReconciliationReader.DisbursementSource;
import com.educore.api.payroll.PayrollAdvanceReconciliationReader.InstallmentSource;
import com.educore.api.payroll.PayrollAdvanceReconciliationReader.RecoveryPostingSource;
import com.educore.api.payroll.PayrollAdvanceReconciliationReader.RecoverySource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PayrollAdvanceReconciliationTest {
    private final UUID advanceId = UUID.randomUUID(), staffId = UUID.randomUUID();
    private final UUID yearId = UUID.randomUUID(), issueId = UUID.randomUUID();
    private final UUID issueJournalId = UUID.randomUUID(), firstInstallmentId = UUID.randomUUID();
    private final UUID secondInstallmentId = UUID.randomUUID(), recoveryId = UUID.randomUUID();
    private final UUID batchId = UUID.randomUUID(), recoveryJournalId = UUID.randomUUID();
    private final LocalDate issueDate = LocalDate.of(2025, 8, 15);
    private final BigDecimal principal = new BigDecimal("200.00");

    private AdvanceSource advance() {
        return new AdvanceSource(advanceId, "EPA-202508-001", "ADVANCE", staffId,
                "EMP-001", "Synthetic employee", yearId, issueDate,
                LocalDate.of(2025, 9, 1), "INR", principal, 2, "APPROVED");
    }
    private List<InstallmentSource> schedule() {
        return List.of(new InstallmentSource(firstInstallmentId, 1,
                        LocalDate.of(2025, 9, 1), new BigDecimal("100.00")),
                new InstallmentSource(secondInstallmentId, 2,
                        LocalDate.of(2025, 10, 1), new BigDecimal("100.00")));
    }
    private DisbursementSource issue() {
        return new DisbursementSource(issueId, yearId, issueDate, "INR", principal,
                "ACTIVE", issueJournalId);
    }
    private PayrollJournalEvidence journal(UUID id, UUID sourceId, BigDecimal amount,
            String status, String currency) {
        return new PayrollJournalEvidence(id, sourceId, yearId,
                id.equals(issueJournalId) ? issueDate : LocalDate.of(2025, 9, 30),
                currency, status, amount, amount, null, null,
                new BigDecimal("0.00"), new BigDecimal("0.00"), false);
    }
    private RecoveryPostingSource recoveryPosting() {
        return new RecoveryPostingSource(batchId, yearId, LocalDate.of(2025, 9, 30),
                "INR", new BigDecimal("500.00"), "ACTIVE", recoveryJournalId);
    }

    @Test void queryRejectsInvalidDatesPaginationAndSearch() {
        assertThatThrownBy(() -> new PayrollReports.AdvanceQuery(null, null, "", 0, 25))
                .hasMessageContaining("due-through");
        assertThatThrownBy(() -> new PayrollReports.AdvanceQuery(LocalDate.MAX, null, "", 0, 25))
                .hasMessageContaining("due-through");
        assertThatThrownBy(() -> new PayrollReports.AdvanceQuery(issueDate, null, "", -1, 25))
                .hasMessageContaining("page size");
        assertThatThrownBy(() -> new PayrollReports.AdvanceQuery(issueDate, null, "a\nb", 0, 25))
                .hasMessageContaining("printable");
        assertThat(new PayrollReports.AdvanceQuery(issueDate, null, "  EPA-001  ", 0, 100).search())
                .isEqualTo("EPA-001");
    }

    @Test void reconcilesPostedIssueAndRecoveryWithoutInventingFutureDues() {
        RecoverySource recovery = new RecoverySource(recoveryId, firstInstallmentId,
                new BigDecimal("100.00"), "ACTIVE");
        var row = PayrollAdvanceReconciliationReader.reconcile(advance(),
                LocalDate.of(2025, 9, 30), schedule(), List.of(issue()),
                Map.of(issueJournalId, journal(issueJournalId, issueId, principal, "POSTED", "INR")),
                List.of(recovery), Map.of(recoveryId, List.of(recoveryPosting())),
                Map.of(recoveryJournalId, journal(recoveryJournalId, batchId,
                        new BigDecimal("500.00"), "POSTED", "INR")),
                new PayrollAdvanceRecoveryEvidence(new BigDecimal("100.00"), 1));
        assertThat(row.reconciliationStatus()).isEqualTo("PARTIALLY_RECOVERED");
        assertThat(row.postedDisbursementAmount()).isEqualByComparingTo("200.00");
        assertThat(row.recoveredAmount()).isEqualByComparingTo("100.00");
        assertThat(row.outstandingAmount()).isEqualByComparingTo("100.00");
        assertThat(row.scheduledDueAmount()).isEqualByComparingTo("100.00");
        assertThat(row.overdueAmount()).isEqualByComparingTo("0.00");
        assertThat(row.nextUnrecoveredMonth()).isEqualTo(LocalDate.of(2025, 10, 1));
        assertThat(row.issues()).isEmpty();
    }

    @Test void dueInstallmentWithoutPostedRecoveryIsVisibleAsOverdue() {
        var row = PayrollAdvanceReconciliationReader.reconcile(advance(),
                LocalDate.of(2025, 9, 30), schedule(), List.of(issue()),
                Map.of(issueJournalId, journal(issueJournalId, issueId, principal, "POSTED", "INR")),
                List.of(), Map.of(), Map.of(),
                new PayrollAdvanceRecoveryEvidence(new BigDecimal("0.00"), 0));
        assertThat(row.reconciliationStatus()).isEqualTo("OVERDUE");
        assertThat(row.overdueAmount()).isEqualByComparingTo("100.00");
        assertThat(row.nextUnrecoveredMonth()).isEqualTo(LocalDate.of(2025, 9, 1));
    }

    @Test void mismatchWithholdsLedgerDerivedBalances() {
        var row = PayrollAdvanceReconciliationReader.reconcile(advance(),
                LocalDate.of(2025, 9, 30), schedule(), List.of(issue()),
                Map.of(issueJournalId, journal(issueJournalId, issueId, principal, "POSTED", "USD")),
                List.of(), Map.of(), Map.of(),
                new PayrollAdvanceRecoveryEvidence(new BigDecimal("0.00"), 0));
        assertThat(row.reconciliationStatus()).isEqualTo("EVIDENCE_MISMATCH");
        assertThat(row.issues()).contains("DISBURSEMENT_PROVENANCE_MISMATCH");
        assertThat(row.postedDisbursementAmount()).isNull();
        assertThat(row.recoveredAmount()).isNull();
        assertThat(row.outstandingAmount()).isNull();
        assertThat(row.overdueAmount()).isNull();
    }

    @Test void frozenDisbursementSourceMustMatchItsApprovedAdvance() {
        DisbursementSource wrongSource = new DisbursementSource(issueId, UUID.randomUUID(),
                issueDate.plusDays(1), "INR", new BigDecimal("199.00"), "ACTIVE", issueJournalId);
        var row = PayrollAdvanceReconciliationReader.reconcile(advance(),
                LocalDate.of(2025, 9, 30), schedule(), List.of(wrongSource),
                Map.of(issueJournalId, journal(issueJournalId, issueId,
                        new BigDecimal("199.00"), "POSTED", "INR")),
                List.of(), Map.of(), Map.of(),
                new PayrollAdvanceRecoveryEvidence(new BigDecimal("0.00"), 0));
        assertThat(row.issues()).contains("DISBURSEMENT_SOURCE_MISMATCH");
        assertThat(row.reconciliationStatus()).isEqualTo("EVIDENCE_MISMATCH");
        assertThat(row.postedDisbursementAmount()).isNull();
    }

    @Test void malformedRecoveryAndReversalEvidenceCannotCountAsRecovered() {
        RecoverySource recovery = new RecoverySource(recoveryId, firstInstallmentId,
                new BigDecimal("99.00"), "RELEASED");
        PayrollJournalEvidence malformed = new PayrollJournalEvidence(recoveryJournalId, batchId,
                yearId, LocalDate.of(2025, 9, 30), "INR", "REVERSED",
                new BigDecimal("500.00"), new BigDecimal("500.00"), UUID.randomUUID(),
                "SUBMITTED", new BigDecimal("500.00"), new BigDecimal("500.00"), true);
        var row = PayrollAdvanceReconciliationReader.reconcile(advance(),
                LocalDate.of(2025, 9, 30), schedule(), List.of(issue()),
                Map.of(issueJournalId, journal(issueJournalId, issueId, principal, "POSTED", "INR")),
                List.of(recovery), Map.of(recoveryId, List.of(recoveryPosting())),
                Map.of(recoveryJournalId, malformed),
                new PayrollAdvanceRecoveryEvidence(new BigDecimal("0.00"), 0));
        assertThat(row.issues()).contains("RECOVERY_AMOUNT_MISMATCH", "RECOVERY_REVERSAL_MISMATCH");
        assertThat(row.reconciliationStatus()).isEqualTo("EVIDENCE_MISMATCH");
        assertThat(row.recoveredAmount()).isNull();
    }

    @Test void readerUsesStableEvidenceSnapshot() throws ReflectiveOperationException {
        var method = PayrollAdvanceReconciliationReader.class.getMethod("read",
                com.educore.api.identity.AuthenticatedUser.class, UUID.class,
                PayrollReports.AdvanceQuery.class);
        var transaction = method.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(transaction.readOnly()).isTrue();
        assertThat(transaction.isolation())
                .isEqualTo(org.springframework.transaction.annotation.Isolation.REPEATABLE_READ);
    }
}
