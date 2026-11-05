package com.educore.api.payroll;

import static com.educore.api.payroll.PayrollAdvanceStatement.*;

import com.educore.api.accounting.AccountingModuleBridge;
import com.educore.api.accounting.AccountingModuleBridge.PayrollJournalEvidence;
import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Reads one immutable advance schedule together with its operational and common-ledger evidence. */
@Component
public class PayrollAdvanceStatementReader {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private final NamedParameterJdbcTemplate jdbc;
    private final PayrollAccountingService payrollAccounting;
    private final AccountingModuleBridge accounting;
    private final Clock clock;

    public PayrollAdvanceStatementReader(NamedParameterJdbcTemplate jdbc,
            PayrollAccountingService payrollAccounting, AccountingModuleBridge accounting, Clock clock) {
        this.jdbc = jdbc;
        this.payrollAccounting = payrollAccounting;
        this.accounting = accounting;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PayrollAdvanceStatement read(AuthenticatedUser user, UUID schoolId, UUID advanceId) {
        var scope = payrollAccounting.reportingScope(user, schoolId);
        if (advanceId == null) throw notFound();
        MapSqlParameterSource parameters = scope.params().addValue("advanceId", advanceId);
        AdvanceRow advance = jdbc.query("""
                SELECT id,staff_profile_id,advance_number,advance_type,purpose,issue_date,
                       currency_code,principal_amount,installment_count,status,employee_code_snapshot,
                       staff_name_snapshot
                FROM payroll_employee_advances
                WHERE id=:advanceId AND organisation_id=:organisationId AND school_id=:schoolId
                """, parameters, result -> result.next() ? advance(result) : null);
        if (advance == null) throw notFound();

        List<DisbursementRow> disbursementRows = jdbc.query("""
                SELECT id,sequence_number,posting_date,payment_method,payment_reference,
                       source_amount,status,accounting_journal_id,prepared_at
                FROM payroll_employee_advance_postings
                WHERE employee_advance_id=:advanceId AND organisation_id=:organisationId
                  AND school_id=:schoolId
                ORDER BY sequence_number,id
                """, parameters, (result, row) -> disbursement(result));
        Map<UUID, String> disbursementStatuses = accounting.payrollAdvanceJournalStatuses(user,
                scope.schoolId(), disbursementRows.stream().map(DisbursementRow::journalId)
                        .filter(Objects::nonNull).toList());
        List<Disbursement> disbursements = disbursementRows.stream().map(row -> {
            String journalStatus = row.journalId() == null ? null : disbursementStatuses.get(row.journalId());
            boolean posted = "ACTIVE".equals(row.sourceStatus()) && "POSTED".equals(journalStatus);
            return new Disbursement(row.id(), row.sequence(), row.date(), row.method(), row.reference(),
                    money(row.amount()), row.sourceStatus(), row.journalId(), journalStatus, posted,
                    row.preparedAt());
        }).toList();

        List<InstallmentRow> installmentRows = jdbc.query("""
                SELECT id,installment_number,due_month,planned_amount
                FROM payroll_employee_advance_installments
                WHERE employee_advance_id=:advanceId AND organisation_id=:organisationId
                  AND school_id=:schoolId
                ORDER BY installment_number,id
                """, parameters, (result, row) -> new InstallmentRow(
                        result.getObject("id", UUID.class), result.getInt("installment_number"),
                        result.getObject("due_month", LocalDate.class),
                        result.getBigDecimal("planned_amount")));

        List<AttemptRow> attempts = jdbc.query("""
                SELECT recovery.id,recovery.installment_id,recovery.recovery_amount,
                       recovery.reservation_status,recovery.created_at,recovery.released_at,
                       recovery.release_reason,releaser_user.display_name released_by,
                       run.id payroll_run_id,run.run_code,run.payroll_month,run.status payroll_run_status
                FROM payroll_run_advance_recoveries recovery
                JOIN payroll_run_staff_results staff_result
                  ON staff_result.id=recovery.payroll_run_staff_result_id
                  AND staff_result.organisation_id=recovery.organisation_id
                  AND staff_result.school_id=recovery.school_id
                JOIN payroll_runs run ON run.id=staff_result.payroll_run_id
                  AND run.organisation_id=staff_result.organisation_id
                  AND run.school_id=staff_result.school_id
                LEFT JOIN memberships releaser ON releaser.id=recovery.released_by_membership_id
                  AND releaser.organisation_id=recovery.organisation_id
                  AND releaser.school_id=recovery.school_id
                LEFT JOIN user_accounts releaser_user ON releaser_user.id=releaser.user_id
                WHERE recovery.employee_advance_id=:advanceId
                  AND recovery.organisation_id=:organisationId AND recovery.school_id=:schoolId
                ORDER BY run.payroll_month,run.attempt_number,recovery.created_at,recovery.id
                """, parameters, (result, row) -> attempt(result));

        List<PostingRow> postingRows = attempts.isEmpty() ? List.of() : jdbc.query("""
                SELECT recovery.id recovery_id,batch.id batch_id,batch.financial_year_id,
                       batch.posting_date,run.currency_code,batch.source_amount,item.status source_status,
                       batch.accounting_journal_id
                FROM payroll_run_advance_recoveries recovery
                JOIN payroll_run_staff_results staff_result
                  ON staff_result.id=recovery.payroll_run_staff_result_id
                  AND staff_result.organisation_id=recovery.organisation_id
                  AND staff_result.school_id=recovery.school_id
                JOIN payroll_runs run ON run.id=staff_result.payroll_run_id
                  AND run.organisation_id=staff_result.organisation_id
                  AND run.school_id=staff_result.school_id
                JOIN payroll_accounting_posting_items item
                  ON item.payroll_run_staff_result_id=recovery.payroll_run_staff_result_id
                  AND item.organisation_id=recovery.organisation_id AND item.school_id=recovery.school_id
                  AND item.posting_type='ACCRUAL'
                JOIN payroll_accounting_posting_batches batch ON batch.id=item.posting_batch_id
                  AND batch.organisation_id=item.organisation_id AND batch.school_id=item.school_id
                  AND batch.posting_type='ACCRUAL'
                WHERE recovery.employee_advance_id=:advanceId
                  AND recovery.organisation_id=:organisationId AND recovery.school_id=:schoolId
                ORDER BY recovery.id,batch.sequence_number,batch.id
                """, parameters, (result, row) -> new PostingRow(
                        result.getObject("recovery_id", UUID.class),
                        result.getObject("batch_id", UUID.class),
                        result.getObject("financial_year_id", UUID.class),
                        result.getObject("posting_date", LocalDate.class),
                        result.getString("currency_code"),
                        result.getBigDecimal("source_amount"),
                        result.getString("source_status"),
                        result.getObject("accounting_journal_id", UUID.class)));
        Map<UUID, PayrollJournalEvidence> journalEvidence = accounting.payrollJournalEvidence(user,
                scope.schoolId(), postingRows.stream().map(PostingRow::journalId)
                        .filter(Objects::nonNull).toList());
        Map<UUID, List<LedgerPosting>> postingsByAttempt = new LinkedHashMap<>();
        for (PostingRow row : postingRows) {
            PayrollJournalEvidence evidence = row.journalId() == null ? null : journalEvidence.get(row.journalId());
            LedgerPosting posting = ledgerPosting(row, evidence);
            postingsByAttempt.computeIfAbsent(row.recoveryId(), ignored -> new ArrayList<>()).add(posting);
        }
        Map<UUID, List<RecoveryAttempt>> attemptsByInstallment = new LinkedHashMap<>();
        for (AttemptRow row : attempts) {
            List<LedgerPosting> rowPostings = postingsByAttempt.getOrDefault(row.id(), List.of());
            String status = attemptStatus(row, rowPostings);
            attemptsByInstallment.computeIfAbsent(row.installmentId(), ignored -> new ArrayList<>())
                    .add(new RecoveryAttempt(row.id(), row.runId(), row.runCode(), row.payrollMonth(),
                            row.runStatus(), money(row.amount()), row.reservationStatus(), row.reservedAt(),
                            row.releasedAt(), row.releaseReason(), row.releasedBy(), status, rowPostings));
        }

        List<Installment> installments = installmentRows.stream().map(row -> {
            List<RecoveryAttempt> rowAttempts = attemptsByInstallment.getOrDefault(row.id(), List.of());
            long recoveredAttempts = rowAttempts.stream().filter(item -> "RECOVERED".equals(item.status())).count();
            BigDecimal recovered = recoveredAttempts == 1 ? money(row.plannedAmount()) : ZERO;
            String status = recoveredAttempts > 1 ? "EVIDENCE_MISMATCH"
                    : recoveredAttempts == 1 ? "RECOVERED"
                    : rowAttempts.stream().anyMatch(item -> "AWAITING_ACCOUNTING_APPROVAL".equals(item.status()))
                            ? "AWAITING_ACCOUNTING_APPROVAL"
                    : rowAttempts.stream().anyMatch(item -> "RESERVED_IN_PAYROLL".equals(item.status()))
                            ? "RESERVED_IN_PAYROLL"
                    : rowAttempts.isEmpty() ? "PLANNED" : "UNRECOVERED";
            return new Installment(row.id(), row.number(), row.dueMonth(), money(row.plannedAmount()),
                    recovered, money(row.plannedAmount().subtract(recovered)), status, rowAttempts);
        }).toList();

        BigDecimal derivedRecovered = installments.stream().map(Installment::recoveredAmount)
                .reduce(ZERO, BigDecimal::add).setScale(2);
        BigDecimal planned = installments.stream().map(Installment::plannedAmount)
                .reduce(ZERO, BigDecimal::add).setScale(2);
        var aggregate = accounting.payrollAdvanceRecoveryEvidence(user, scope.schoolId(), List.of(advance.id()))
                .get(advance.id());
        BigDecimal authoritativeRecovered = aggregate == null ? ZERO : money(aggregate.recoveredAmount());
        List<Disbursement> posted = disbursements.stream().filter(Disbursement::countsAsDisbursed).toList();
        BigDecimal disbursed = posted.size() == 1 ? money(posted.getFirst().sourceAmount()) : ZERO;
        boolean mismatch = installments.size() != advance.installmentCount()
                || planned.compareTo(advance.principal()) != 0 || posted.size() > 1
                || (posted.size() == 1 && posted.getFirst().sourceAmount().compareTo(advance.principal()) != 0)
                || (posted.isEmpty() && authoritativeRecovered.signum() > 0)
                || derivedRecovered.compareTo(authoritativeRecovered) != 0
                || authoritativeRecovered.compareTo(advance.principal()) > 0
                || installments.stream().anyMatch(item -> "EVIDENCE_MISMATCH".equals(item.status()));
        String evidenceStatus = mismatch ? "EVIDENCE_MISMATCH"
                : posted.isEmpty() ? "NOT_DISBURSED"
                : authoritativeRecovered.signum() == 0 ? "DISBURSED"
                : authoritativeRecovered.compareTo(advance.principal()) == 0 ? "FULLY_RECOVERED"
                : "PARTIALLY_RECOVERED";
        BigDecimal outstanding = posted.isEmpty() || mismatch ? null
                : money(advance.principal().subtract(authoritativeRecovered));
        return new PayrollAdvanceStatement(scope.schoolId(), scope.schoolName(), clock.instant(),
                advance.id(), advance.number(), advance.type(), advance.purpose(), advance.staffId(),
                advance.employeeCode(), advance.staffName(), advance.issueDate(), advance.currency(),
                money(advance.principal()), advance.status(), mismatch ? null : disbursed,
                mismatch ? null : authoritativeRecovered, outstanding, evidenceStatus,
                disbursements, installments);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CsvExport export(AuthenticatedUser user, UUID schoolId, UUID advanceId) {
        PayrollAdvanceStatement statement = read(user, schoolId, advanceId);
        StringBuilder csv = new StringBuilder("\uFEFF");
        PayrollReportCsv.row(csv, "Advance number", "Employee code", "Employee", "Currency",
                "Installment", "Due month", "Planned", "Recovered", "Outstanding",
                "Installment status", "Payroll runs", "Payroll status", "Ledger journals",
                "Ledger status");
        for (Installment item : statement.installments()) {
            String runs = item.recoveryAttempts().stream().map(RecoveryAttempt::runCode)
                    .filter(Objects::nonNull).distinct().reduce((left, right) -> left + "; " + right).orElse("");
            String runStates = item.recoveryAttempts().stream().map(RecoveryAttempt::status)
                    .distinct().reduce((left, right) -> left + "; " + right).orElse("");
            String journals = item.recoveryAttempts().stream().flatMap(attempt -> attempt.ledgerPostings().stream())
                    .map(LedgerPosting::accountingJournalId).filter(Objects::nonNull).map(UUID::toString)
                    .distinct().reduce((left, right) -> left + "; " + right).orElse("");
            String journalStates = item.recoveryAttempts().stream().flatMap(attempt -> attempt.ledgerPostings().stream())
                    .map(LedgerPosting::evidenceStatus).distinct()
                    .reduce((left, right) -> left + "; " + right).orElse("");
            PayrollReportCsv.row(csv, statement.advanceNumber(), statement.employeeCode(),
                    statement.staffName(), statement.currencyCode(), item.installmentNumber(), item.dueMonth(),
                    item.plannedAmount(), item.recoveredAmount(), item.outstandingAmount(), item.status(),
                    runs, runStates, journals, journalStates);
        }
        return new CsvExport(statement.schoolId(), statement.advanceNumber(),
                statement.installments().size(), csv.toString());
    }

    static LedgerPosting ledgerPosting(PostingRow row, PayrollJournalEvidence evidence) {
        List<String> issues = new ArrayList<>();
        if (row.journalId() == null || evidence == null) issues.add("JOURNAL_UNAVAILABLE");
        else {
            if (!row.batchId().equals(evidence.sourceId()) || !row.yearId().equals(evidence.financialYearId())
                    || !row.date().equals(evidence.postingDate())
                    || !row.currency().equals(evidence.currencyCode()))
                issues.add("JOURNAL_PROVENANCE_MISMATCH");
            if (evidence.debit().compareTo(row.sourceAmount()) != 0
                    || evidence.credit().compareTo(row.sourceAmount()) != 0)
                issues.add("JOURNAL_AMOUNT_MISMATCH");
            if ("REVERSED".equals(evidence.status()) && !("POSTED".equals(evidence.reversalStatus())
                    && evidence.reversalOffsetsAccounts()
                    && evidence.reversalDebit().compareTo(row.sourceAmount()) == 0
                    && evidence.reversalCredit().compareTo(row.sourceAmount()) == 0))
                issues.add("REVERSAL_MISMATCH");
            else if (!"REVERSED".equals(evidence.status()) && "POSTED".equals(evidence.reversalStatus()))
                issues.add("UNEXPECTED_POSTED_REVERSAL");
        }
        boolean recovered = issues.isEmpty() && "ACTIVE".equals(row.sourceStatus())
                && "POSTED".equals(evidence.status());
        String status = !issues.isEmpty() ? String.join(";", issues)
                : recovered ? "MATCHED_POSTED"
                : "REVERSED".equals(evidence.status()) ? "MATCHED_REVERSED"
                : "SUBMITTED".equals(evidence.status()) ? "AWAITING_APPROVAL"
                : "REJECTED".equals(evidence.status()) ? "MATCHED_REJECTED" : evidence.status();
        return new LedgerPosting(row.batchId(), row.date(), row.sourceStatus(), row.journalId(),
                evidence == null ? null : evidence.status(), evidence == null ? null : evidence.reversalId(),
                evidence == null ? null : evidence.reversalStatus(), status, recovered);
    }

    private static String attemptStatus(AttemptRow row, List<LedgerPosting> postings) {
        long posted = postings.stream().filter(LedgerPosting::countsAsRecovered).count();
        if (posted > 1) return "EVIDENCE_MISMATCH";
        if (posted == 1) return "RECOVERED";
        if (postings.stream().anyMatch(item -> "AWAITING_APPROVAL".equals(item.evidenceStatus())))
            return "AWAITING_ACCOUNTING_APPROVAL";
        if ("ACTIVE".equals(row.reservationStatus())) return "RESERVED_IN_PAYROLL";
        return "RELEASED";
    }

    private static AdvanceRow advance(ResultSet result) throws SQLException {
        return new AdvanceRow(result.getObject("id", UUID.class),
                result.getObject("staff_profile_id", UUID.class), result.getString("advance_number"),
                result.getString("advance_type"), result.getString("purpose"),
                result.getObject("issue_date", LocalDate.class), result.getString("currency_code"),
                result.getBigDecimal("principal_amount"), result.getInt("installment_count"),
                result.getString("status"),
                result.getString("employee_code_snapshot"), result.getString("staff_name_snapshot"));
    }

    private static DisbursementRow disbursement(ResultSet result) throws SQLException {
        return new DisbursementRow(result.getObject("id", UUID.class), result.getInt("sequence_number"),
                result.getObject("posting_date", LocalDate.class), result.getString("payment_method"),
                result.getString("payment_reference"), result.getBigDecimal("source_amount"),
                result.getString("status"), result.getObject("accounting_journal_id", UUID.class),
                result.getTimestamp("prepared_at").toInstant());
    }

    private static AttemptRow attempt(ResultSet result) throws SQLException {
        return new AttemptRow(result.getObject("id", UUID.class), result.getObject("installment_id", UUID.class),
                result.getBigDecimal("recovery_amount"), result.getString("reservation_status"),
                result.getTimestamp("created_at").toInstant(), instant(result.getTimestamp("released_at")),
                result.getString("release_reason"), result.getString("released_by"),
                result.getObject("payroll_run_id", UUID.class), result.getString("run_code"),
                result.getObject("payroll_month", LocalDate.class), result.getString("payroll_run_status"));
    }

    private static BigDecimal money(BigDecimal amount) { return amount == null ? null : amount.setScale(2); }
    private static Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "PAYROLL_ADVANCE_NOT_FOUND",
                "The employee advance is unavailable in this school.");
    }

    public record CsvExport(UUID schoolId, String advanceNumber, int rows, String csv) {}
    record PostingRow(UUID recoveryId, UUID batchId, UUID yearId, LocalDate date,
            String currency, BigDecimal sourceAmount, String sourceStatus, UUID journalId) {}
    private record AdvanceRow(UUID id, UUID staffId, String number, String type, String purpose,
            LocalDate issueDate, String currency, BigDecimal principal, int installmentCount, String status,
            String employeeCode, String staffName) {}
    private record DisbursementRow(UUID id, int sequence, LocalDate date, String method,
            String reference, BigDecimal amount, String sourceStatus, UUID journalId, Instant preparedAt) {}
    private record InstallmentRow(UUID id, int number, LocalDate dueMonth, BigDecimal plannedAmount) {}
    private record AttemptRow(UUID id, UUID installmentId, BigDecimal amount, String reservationStatus,
            Instant reservedAt, Instant releasedAt, String releaseReason, String releasedBy,
            UUID runId, String runCode, LocalDate payrollMonth, String runStatus) {}
}
