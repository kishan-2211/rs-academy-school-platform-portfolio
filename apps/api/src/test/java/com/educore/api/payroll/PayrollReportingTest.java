package com.educore.api.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.educore.api.accounting.AccountingModuleBridge.PayrollJournalEvidence;
import com.educore.api.payroll.PayrollReportReader.BatchSource;
import com.educore.api.payroll.PayrollReportReader.ItemEvidence;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PayrollReportingTest {
    private final UUID batchId = UUID.randomUUID(), runId = UUID.randomUUID(), yearId = UUID.randomUUID(), journalId = UUID.randomUUID();
    private final LocalDate date = LocalDate.of(2025, 8, 31);
    private final BigDecimal amount = new BigDecimal("200.00"), zero = new BigDecimal("0.00");

    private BatchSource source() {
        return new BatchSource(batchId, runId, "SYNTHETIC", yearId, "ACCRUAL", date, "INR",
                2, amount, journalId, 2, amount);
    }
    private PayrollJournalEvidence journal(String status, String reversalStatus, boolean offsets) {
        return new PayrollJournalEvidence(journalId, batchId, yearId, date, "INR", status, amount, amount,
                reversalStatus == null ? null : UUID.randomUUID(), reversalStatus,
                reversalStatus == null ? zero : amount, reversalStatus == null ? zero : amount, offsets);
    }
    private ItemEvidence items() { return new ItemEvidence(2, amount, amount, true); }

    @Test void rejectsInvalidDateRangesPaginationAndSearch() {
        for (int size : new int[]{0,101}) assertThatThrownBy(() -> new PayrollReports.Query(date, date, null, "", 0, size)).hasMessageContaining("page size");
        assertThatThrownBy(() -> new PayrollReports.Query(date, date.minusDays(1), null, "", 0, 25)).hasMessageContaining("ordered");
        assertThatThrownBy(() -> new PayrollReports.Query(date, date.plusYears(11), null, "", 0, 25)).hasMessageContaining("ten years");
        assertThatThrownBy(() -> new PayrollReports.Query(LocalDate.MIN, LocalDate.MIN, null, "", 0, 25)).hasMessageContaining("ordered");
        assertThatThrownBy(() -> new PayrollReports.Query(LocalDate.MAX, LocalDate.MAX, null, "", 0, 25)).hasMessageContaining("ordered");
        assertThatThrownBy(() -> new PayrollReports.Query(date, date, null, "", -1, 25)).hasMessageContaining("page size");
        assertThatThrownBy(() -> new PayrollReports.Query(date, date, null, "a\nb", 0, 25)).hasMessageContaining("printable");
        assertThatThrownBy(() -> new PayrollReports.Query(date, date, null, "a".repeat(101), 0, 25)).hasMessageContaining("100");
        assertThat(new PayrollReports.Query(date, date, null, "  synthetic  ", 0, 100).search()).isEqualTo("synthetic");
        assertThat(PayrollReports.GroupBy.parse(" designation ")).isEqualTo(PayrollReports.GroupBy.DESIGNATION);
        assertThatThrownBy(() -> PayrollReports.GroupBy.parse("salary")).hasMessageContaining("DEPARTMENT");
        assertThatThrownBy(() -> PayrollReports.GroupBy.parse(null)).hasMessageContaining("grouping");
    }
    @Test void exportNeverSilentlyTruncates() {
        PayrollReportReader.exportSize(5000);
        assertThatThrownBy(() -> PayrollReportReader.exportSize(5001)).hasMessageContaining("no rows were silently omitted");
    }
    @Test void matchesPostedAndUnpostedEvidenceWithoutInventingPayments() {
        assertThat(PayrollReportReader.reconcile(source(), items(), journal("POSTED", null, false)).reconciliationStatus()).isEqualTo("MATCHED_POSTED");
        assertThat(PayrollReportReader.reconcile(source(), items(), journal("SUBMITTED", null, false)).reconciliationStatus()).isEqualTo("AWAITING_APPROVAL");
        assertThat(PayrollReportReader.reconcile(source(), items(), journal("REJECTED", null, false)).reconciliationStatus()).isEqualTo("MATCHED_REJECTED");
    }
    @Test void missingOrIncompleteEvidenceIsAVisibleMismatch() {
        var result = PayrollReportReader.reconcile(source(), new ItemEvidence(1, zero, amount, false), null);
        assertThat(result.reconciliationStatus()).isEqualTo("MISMATCH");
        assertThat(result.issues()).contains("SOURCE_COUNT_MISMATCH","SOURCE_AMOUNT_MISMATCH","SOURCE_RUN_MISMATCH","JOURNAL_UNAVAILABLE");
        assertThat(result.journalDebit()).isNull();
        var incomplete = new BatchSource(batchId, runId, "SYNTHETIC", yearId, "ACCRUAL", date, "INR",
                2, amount, journalId, 3, amount);
        assertThat(PayrollReportReader.reconcile(incomplete, items(), journal("POSTED", null, false)).issues()).contains("INCOMPLETE_RUN_ACCRUAL");
    }
    @Test void reversalMustBePostedAndOffsetTheSameAccountDimensions() {
        assertThat(PayrollReportReader.reconcile(source(), items(), journal("REVERSED", "POSTED", true)).reconciliationStatus()).isEqualTo("MATCHED_REVERSED");
        assertThat(PayrollReportReader.reconcile(source(), items(), journal("REVERSED", "POSTED", false)).issues()).contains("REVERSAL_MISMATCH");
        assertThat(PayrollReportReader.reconcile(source(), items(), journal("REVERSED", "SUBMITTED", true)).issues()).contains("REVERSAL_MISMATCH");
        assertThat(PayrollReportReader.reconcile(source(), items(), journal("POSTED", "POSTED", true)).issues()).contains("UNEXPECTED_POSTED_REVERSAL");
    }
    @Test void journalProvenanceAndAmountsCannotBeSubstituted() {
        var wrong = new PayrollJournalEvidence(journalId, UUID.randomUUID(), yearId, date, "USD", "POSTED",
                zero, amount, null, null, zero, zero, false);
        assertThat(PayrollReportReader.reconcile(source(), items(), wrong).issues()).contains("JOURNAL_PROVENANCE_MISMATCH","JOURNAL_AMOUNT_MISMATCH");
    }
    @Test void csvQuotesTextPreservesUnicodeAndNeutralizesSpreadsheetFormulas() {
        StringBuilder csv = new StringBuilder();
        PayrollReportCsv.row(csv, "Synthetic, \"staff\"", "परीक्षण", "=1+1", " \t@SUM(1)", "\uFEFF+1", "-1", amount, null);
        assertThat(csv.toString()).isEqualTo("\"Synthetic, \"\"staff\"\"\",\"परीक्षण\",\"'=1+1\",\"' \t@SUM(1)\",\"'\uFEFF+1\",\"'-1\",\"200.00\",\"\"\r\n");
    }
    @Test void stableReadsAndAuditFacadeKeepSeparateTransactionBoundaries() throws ReflectiveOperationException {
        var method = PayrollReportReader.class.getMethod("register",
                com.educore.api.identity.AuthenticatedUser.class, UUID.class, PayrollReports.Query.class);
        var transaction = method.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(transaction.readOnly()).isTrue();
        assertThat(transaction.isolation()).isEqualTo(org.springframework.transaction.annotation.Isolation.REPEATABLE_READ);
        var facade = PayrollReportingService.class.getMethod("register",
                com.educore.api.identity.AuthenticatedUser.class, UUID.class, PayrollReports.Query.class);
        assertThat(facade.getAnnotation(org.springframework.transaction.annotation.Transactional.class)).isNull();
        var summary = PayrollReportReader.class.getMethod("summary",
                com.educore.api.identity.AuthenticatedUser.class, UUID.class, PayrollReports.Query.class,
                PayrollReports.GroupBy.class);
        var summaryTransaction = summary.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(summaryTransaction.readOnly()).isTrue();
        assertThat(summaryTransaction.isolation()).isEqualTo(org.springframework.transaction.annotation.Isolation.REPEATABLE_READ);
        assertThat(PayrollReportingController.class.getAnnotation(
                org.springframework.security.access.prepost.PreAuthorize.class).value())
                .contains("CAPABILITY_payroll.read", "CAPABILITY_accounting.read");
    }
}
