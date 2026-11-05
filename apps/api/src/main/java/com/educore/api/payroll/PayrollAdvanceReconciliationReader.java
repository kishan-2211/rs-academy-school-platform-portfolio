package com.educore.api.payroll;

import static com.educore.api.payroll.PayrollReports.*;

import com.educore.api.accounting.AccountingModuleBridge;
import com.educore.api.accounting.AccountingModuleBridge.PayrollAdvanceRecoveryEvidence;
import com.educore.api.accounting.AccountingModuleBridge.PayrollJournalEvidence;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.payroll.PayrollAccountingService.Scope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reconciles approved advance schedules with current disbursement and payroll-ledger evidence.
 * The selected date controls which installments are due; ledger states remain current at read time.
 */
@Component
public class PayrollAdvanceReconciliationReader {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final int EXPORT_LIMIT = 5000;
    private static final String ADVANCE_FROM = """
            FROM payroll_employee_advances advance
            WHERE advance.organisation_id=:organisationId AND advance.school_id=:schoolId
              AND advance.status='APPROVED' AND advance.issue_date<=:dueThrough
              AND (CAST(:staffId AS uuid) IS NULL OR advance.staff_profile_id=:staffId)
              AND (:search='' OR position(lower(:search) in lower(advance.employee_code_snapshot))>0
                OR position(lower(:search) in lower(advance.staff_name_snapshot))>0
                OR position(lower(:search) in lower(advance.advance_number))>0)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final PayrollAccountingService payrollAccounting;
    private final AccountingModuleBridge accounting;
    private final Clock clock;

    public PayrollAdvanceReconciliationReader(NamedParameterJdbcTemplate jdbc,
            PayrollAccountingService payrollAccounting, AccountingModuleBridge accounting, Clock clock) {
        this.jdbc = jdbc;
        this.payrollAccounting = payrollAccounting;
        this.accounting = accounting;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdvancePage<AdvanceReconciliationRow> read(AuthenticatedUser user, UUID schoolId,
            AdvanceQuery query) {
        Scope scope = payrollAccounting.reportingScope(user, schoolId);
        long total = count(scope, query);
        List<AdvanceReconciliationRow> rows = rows(user, scope, query, query.size(),
                (long) query.page() * query.size());
        return new AdvancePage<>(scope.schoolId(), scope.schoolName(), Instant.now(clock),
                query.dueThrough(), query.page(), query.size(), total, rows);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ExportResult export(AuthenticatedUser user, UUID schoolId, AdvanceQuery query) {
        Scope scope = payrollAccounting.reportingScope(user, schoolId);
        long total = count(scope, query);
        if (total > EXPORT_LIMIT) {
            throw invalid("This export exceeds 5000 advances. Narrow the due-through date or employee filter; no rows were silently omitted.");
        }
        List<AdvanceReconciliationRow> rows = rows(user, scope, query, EXPORT_LIMIT, 0);
        StringBuilder csv = new StringBuilder("\uFEFF");
        PayrollReportCsv.row(csv, "Advance number", "Type", "Employee code", "Employee",
                "Issue date", "Due through", "Currency", "Principal", "Advance status",
                "Declared installments", "Actual installments", "Scheduled total",
                "Scheduled due amount", "Disbursement status", "Posted disbursement",
                "Posted recovered", "Outstanding", "Overdue amount",
                "Next unrecovered month", "Pending recovery count", "Pending recovery amount",
                "Reconciliation status", "Issues", "Advance ID");
        for (AdvanceReconciliationRow row : rows) {
            PayrollReportCsv.row(csv, row.advanceNumber(), row.advanceType(), row.employeeCode(),
                    row.staffName(), row.issueDate(), query.dueThrough(), row.currencyCode(),
                    row.principalAmount(), row.advanceStatus(), row.declaredInstallmentCount(),
                    row.actualInstallmentCount(), row.scheduledAmount(), row.scheduledDueAmount(),
                    row.disbursementStatus(), row.postedDisbursementAmount(), row.recoveredAmount(),
                    row.outstandingAmount(), row.overdueAmount(), row.nextUnrecoveredMonth(),
                    row.pendingRecoveryCount(), row.pendingRecoveryAmount(),
                    row.reconciliationStatus(), String.join("; ", row.issues()), row.id());
        }
        return new ExportResult(scope.schoolId(), csv.toString(), rows.size());
    }

    private long count(Scope scope, AdvanceQuery query) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) " + ADVANCE_FROM,
                params(scope, query), Long.class));
    }

    private List<AdvanceReconciliationRow> rows(AuthenticatedUser user, Scope scope,
            AdvanceQuery query, int limit, long offset) {
        List<AdvanceSource> advances = jdbc.query("""
                SELECT advance.id,advance.advance_number,advance.advance_type,
                       advance.staff_profile_id,advance.employee_code_snapshot,
                       advance.staff_name_snapshot,advance.financial_year_id,
                       advance.issue_date,advance.recovery_start_month,advance.currency_code,
                       advance.principal_amount,advance.installment_count,advance.status
                """ + ADVANCE_FROM + """
                ORDER BY advance.issue_date DESC,advance.advance_number DESC,advance.id
                LIMIT :limit OFFSET :offset
                """, params(scope, query).addValue("limit", limit).addValue("offset", offset),
                (result, row) -> new AdvanceSource(result.getObject("id", UUID.class),
                        result.getString("advance_number"), result.getString("advance_type"),
                        result.getObject("staff_profile_id", UUID.class),
                        result.getString("employee_code_snapshot"),
                        result.getString("staff_name_snapshot"),
                        result.getObject("financial_year_id", UUID.class),
                        result.getObject("issue_date", LocalDate.class),
                        result.getObject("recovery_start_month", LocalDate.class),
                        result.getString("currency_code"), result.getBigDecimal("principal_amount"),
                        result.getInt("installment_count"), result.getString("status")));
        if (advances.isEmpty()) return List.of();
        List<UUID> ids = advances.stream().map(AdvanceSource::id).toList();
        Map<UUID, List<InstallmentSource>> schedules = schedules(scope, ids);
        Map<UUID, List<DisbursementSource>> disbursements = disbursements(scope, ids);
        Map<UUID, List<RecoverySource>> recoveries = recoveries(scope, ids);
        Map<UUID, List<RecoveryPostingSource>> recoveryPostings = recoveryPostings(scope, ids);

        List<UUID> issueJournalIds = disbursements.values().stream().flatMap(List::stream)
                .map(DisbursementSource::journalId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, PayrollJournalEvidence> issueJournals = accounting.payrollAdvanceJournalEvidence(
                user, scope.schoolId(), issueJournalIds);
        List<UUID> recoveryJournalIds = recoveryPostings.values().stream().flatMap(List::stream)
                .map(RecoveryPostingSource::journalId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, PayrollJournalEvidence> recoveryJournals = accounting.payrollJournalEvidence(
                user, scope.schoolId(), recoveryJournalIds);
        Map<UUID, PayrollAdvanceRecoveryEvidence> aggregateRecoveries =
                accounting.payrollAdvanceRecoveryEvidence(user, scope.schoolId(), ids);

        return advances.stream().map(advance -> reconcile(advance, query.dueThrough(),
                schedules.getOrDefault(advance.id(), List.of()),
                disbursements.getOrDefault(advance.id(), List.of()), issueJournals,
                recoveries.getOrDefault(advance.id(), List.of()), recoveryPostings,
                recoveryJournals, aggregateRecoveries.get(advance.id()))).toList();
    }

    private Map<UUID, List<InstallmentSource>> schedules(Scope scope, List<UUID> ids) {
        return jdbc.query("""
                SELECT employee_advance_id,id,installment_number,due_month,planned_amount
                FROM payroll_employee_advance_installments
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND employee_advance_id IN (:ids)
                ORDER BY employee_advance_id,installment_number,id
                """, scope.params().addValue("ids", ids), result -> {
                    Map<UUID, List<InstallmentSource>> grouped = new LinkedHashMap<>();
                    while (result.next()) grouped.computeIfAbsent(
                            result.getObject("employee_advance_id", UUID.class), ignored -> new ArrayList<>())
                            .add(new InstallmentSource(result.getObject("id", UUID.class),
                                    result.getInt("installment_number"),
                                    result.getObject("due_month", LocalDate.class),
                                    result.getBigDecimal("planned_amount")));
                    return grouped;
                });
    }

    private Map<UUID, List<DisbursementSource>> disbursements(Scope scope, List<UUID> ids) {
        return jdbc.query("""
                SELECT posting.employee_advance_id,posting.id,posting.financial_year_id,
                       posting.posting_date,advance.currency_code,posting.source_amount,
                       posting.status,posting.accounting_journal_id
                FROM payroll_employee_advance_postings posting
                JOIN payroll_employee_advances advance ON advance.id=posting.employee_advance_id
                  AND advance.organisation_id=posting.organisation_id AND advance.school_id=posting.school_id
                WHERE posting.organisation_id=:organisationId AND posting.school_id=:schoolId
                  AND posting.employee_advance_id IN (:ids)
                ORDER BY posting.employee_advance_id,posting.sequence_number,posting.id
                """, scope.params().addValue("ids", ids), result -> {
                    Map<UUID, List<DisbursementSource>> grouped = new LinkedHashMap<>();
                    while (result.next()) grouped.computeIfAbsent(
                            result.getObject("employee_advance_id", UUID.class), ignored -> new ArrayList<>())
                            .add(new DisbursementSource(result.getObject("id", UUID.class),
                                    result.getObject("financial_year_id", UUID.class),
                                    result.getObject("posting_date", LocalDate.class),
                                    result.getString("currency_code"), result.getBigDecimal("source_amount"),
                                    result.getString("status"),
                                    result.getObject("accounting_journal_id", UUID.class)));
                    return grouped;
                });
    }

    private Map<UUID, List<RecoverySource>> recoveries(Scope scope, List<UUID> ids) {
        return jdbc.query("""
                SELECT employee_advance_id,id,installment_id,recovery_amount,reservation_status
                FROM payroll_run_advance_recoveries
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND employee_advance_id IN (:ids)
                ORDER BY employee_advance_id,due_month,created_at,id
                """, scope.params().addValue("ids", ids), result -> {
                    Map<UUID, List<RecoverySource>> grouped = new LinkedHashMap<>();
                    while (result.next()) grouped.computeIfAbsent(
                            result.getObject("employee_advance_id", UUID.class), ignored -> new ArrayList<>())
                            .add(new RecoverySource(result.getObject("id", UUID.class),
                                    result.getObject("installment_id", UUID.class),
                                    result.getBigDecimal("recovery_amount"),
                                    result.getString("reservation_status")));
                    return grouped;
                });
    }

    private Map<UUID, List<RecoveryPostingSource>> recoveryPostings(Scope scope, List<UUID> ids) {
        return jdbc.query("""
                SELECT recovery.id recovery_id,batch.id batch_id,batch.financial_year_id,
                       batch.posting_date,run.currency_code,batch.source_amount,
                       item.status source_status,batch.accounting_journal_id
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
                WHERE recovery.organisation_id=:organisationId AND recovery.school_id=:schoolId
                  AND recovery.employee_advance_id IN (:ids)
                ORDER BY recovery.id,batch.sequence_number,batch.id
                """, scope.params().addValue("ids", ids), result -> {
                    Map<UUID, List<RecoveryPostingSource>> grouped = new LinkedHashMap<>();
                    while (result.next()) grouped.computeIfAbsent(
                            result.getObject("recovery_id", UUID.class), ignored -> new ArrayList<>())
                            .add(new RecoveryPostingSource(result.getObject("batch_id", UUID.class),
                                    result.getObject("financial_year_id", UUID.class),
                                    result.getObject("posting_date", LocalDate.class),
                                    result.getString("currency_code"), result.getBigDecimal("source_amount"),
                                    result.getString("source_status"),
                                    result.getObject("accounting_journal_id", UUID.class)));
                    return grouped;
                });
    }

    static AdvanceReconciliationRow reconcile(AdvanceSource advance, LocalDate dueThrough,
            List<InstallmentSource> schedule, List<DisbursementSource> disbursements,
            Map<UUID, PayrollJournalEvidence> issueJournals, List<RecoverySource> recoveries,
            Map<UUID, List<RecoveryPostingSource>> recoveryPostings,
            Map<UUID, PayrollJournalEvidence> recoveryJournals,
            PayrollAdvanceRecoveryEvidence aggregateRecovery) {
        List<String> issues = new ArrayList<>();
        BigDecimal scheduled = schedule.stream().map(InstallmentSource::amount)
                .reduce(ZERO, BigDecimal::add).setScale(2);
        if (schedule.size() != advance.installmentCount()) issues.add("SCHEDULE_COUNT_MISMATCH");
        if (scheduled.compareTo(advance.principal()) != 0) issues.add("SCHEDULE_AMOUNT_MISMATCH");
        for (InstallmentSource item : schedule) {
            LocalDate expected = advance.recoveryStartMonth().plusMonths(item.number() - 1L);
            if (item.number() < 1 || item.number() > advance.installmentCount()
                    || !expected.equals(item.dueMonth())) {
                add(issues, "SCHEDULE_SEQUENCE_MISMATCH");
            }
        }

        for (DisbursementSource source : disbursements) {
            if (!advance.financialYearId().equals(source.yearId())
                    || !advance.issueDate().equals(source.date())
                    || advance.principal().compareTo(source.amount()) != 0) {
                add(issues, "DISBURSEMENT_SOURCE_MISMATCH");
            }
        }
        List<DisbursementEvaluation> issueEvaluations = disbursements.stream()
                .map(source -> evaluateDisbursement(source, issueJournals.get(source.journalId())))
                .toList();
        issueEvaluations.forEach(item -> item.issues().forEach(issue -> add(issues, issue)));
        List<DisbursementEvaluation> postedIssues = issueEvaluations.stream()
                .filter(DisbursementEvaluation::countsAsDisbursed).toList();
        if (postedIssues.size() > 1) add(issues, "MULTIPLE_POSTED_DISBURSEMENTS");
        if (postedIssues.size() == 1
                && postedIssues.getFirst().amount().compareTo(advance.principal()) != 0) {
            add(issues, "DISBURSEMENT_AMOUNT_MISMATCH");
        }
        String disbursementStatus = disbursementStatus(issueEvaluations);

        Map<UUID, InstallmentSource> installmentsById = new LinkedHashMap<>();
        schedule.forEach(item -> installmentsById.put(item.id(), item));
        Map<UUID, List<RecoveryEvaluation>> byInstallment = new LinkedHashMap<>();
        int activeRecoveryCount = 0;
        for (RecoverySource source : recoveries) {
            if ("ACTIVE".equals(source.reservationStatus())) activeRecoveryCount++;
            InstallmentSource installment = installmentsById.get(source.installmentId());
            if (installment == null) add(issues, "RECOVERY_INSTALLMENT_MISMATCH");
            else if (source.amount().compareTo(installment.amount()) != 0) {
                add(issues, "RECOVERY_AMOUNT_MISMATCH");
            }
            RecoveryEvaluation evaluation = evaluateRecovery(source,
                    recoveryPostings.getOrDefault(source.id(), List.of()), recoveryJournals);
            evaluation.issues().forEach(issue -> add(issues, issue));
            byInstallment.computeIfAbsent(source.installmentId(), ignored -> new ArrayList<>())
                    .add(evaluation);
        }

        BigDecimal derivedRecovered = ZERO;
        BigDecimal scheduledThrough = ZERO;
        BigDecimal overdue = ZERO;
        BigDecimal pending = ZERO;
        int pendingCount = 0;
        LocalDate nextUnrecovered = null;
        LocalDate cutoffMonth = dueThrough.withDayOfMonth(1);
        for (InstallmentSource installment : schedule) {
            List<RecoveryEvaluation> attempts = byInstallment.getOrDefault(installment.id(), List.of());
            List<RecoveryEvaluation> posted = attempts.stream().filter(RecoveryEvaluation::countsAsRecovered).toList();
            if (posted.size() > 1) add(issues, "MULTIPLE_POSTED_RECOVERIES");
            boolean recovered = posted.size() == 1;
            if (recovered) derivedRecovered = derivedRecovered.add(posted.getFirst().amount());
            if (!installment.dueMonth().isAfter(cutoffMonth)) {
                scheduledThrough = scheduledThrough.add(installment.amount());
                if (!recovered) overdue = overdue.add(installment.amount());
            }
            if (!recovered && (nextUnrecovered == null || installment.dueMonth().isBefore(nextUnrecovered))) {
                nextUnrecovered = installment.dueMonth();
            }
            for (RecoveryEvaluation attempt : attempts) {
                if (!attempt.countsAsRecovered() && ("RESERVED_IN_PAYROLL".equals(attempt.status())
                        || "AWAITING_ACCOUNTING_APPROVAL".equals(attempt.status()))) {
                    pending = pending.add(attempt.amount());
                    pendingCount++;
                }
            }
        }
        derivedRecovered = derivedRecovered.setScale(2);
        scheduledThrough = scheduledThrough.setScale(2);
        overdue = overdue.setScale(2);
        pending = pending.setScale(2);
        BigDecimal aggregateAmount = aggregateRecovery == null ? ZERO
                : money(aggregateRecovery.recoveredAmount());
        int aggregateReservations = aggregateRecovery == null ? 0 : aggregateRecovery.activeReservations();
        if (derivedRecovered.compareTo(aggregateAmount) != 0) add(issues, "RECOVERY_TOTAL_MISMATCH");
        if (activeRecoveryCount != aggregateReservations) add(issues, "RECOVERY_RESERVATION_MISMATCH");
        if (aggregateAmount.compareTo(advance.principal()) > 0) add(issues, "RECOVERY_EXCEEDS_PRINCIPAL");
        if (postedIssues.isEmpty() && aggregateAmount.signum() > 0) add(issues, "RECOVERY_WITHOUT_DISBURSEMENT");

        boolean mismatch = !issues.isEmpty();
        BigDecimal disbursed = mismatch ? null : postedIssues.isEmpty() ? ZERO : money(postedIssues.getFirst().amount());
        BigDecimal recovered = mismatch ? null : aggregateAmount;
        BigDecimal outstanding = mismatch || postedIssues.isEmpty() ? null
                : money(advance.principal().subtract(aggregateAmount));
        BigDecimal trustedOverdue = mismatch || postedIssues.isEmpty() ? null : overdue;
        String status = mismatch ? "EVIDENCE_MISMATCH"
                : postedIssues.isEmpty() ? "NOT_DISBURSED"
                : aggregateAmount.compareTo(advance.principal()) == 0 ? "FULLY_RECOVERED"
                : overdue.signum() > 0 ? "OVERDUE"
                : pendingCount > 0 ? "RECOVERY_PENDING"
                : aggregateAmount.signum() > 0 ? "PARTIALLY_RECOVERED" : "DISBURSED";
        return new AdvanceReconciliationRow(advance.id(), advance.number(), advance.type(),
                advance.staffId(), advance.employeeCode(), advance.staffName(), advance.issueDate(),
                advance.recoveryStartMonth(), advance.currency(), money(advance.principal()),
                advance.status(), advance.installmentCount(), schedule.size(), scheduled,
                scheduledThrough, disbursementStatus, disbursed, recovered, outstanding,
                trustedOverdue, nextUnrecovered, pendingCount, pending, status, issues);
    }

    static DisbursementEvaluation evaluateDisbursement(DisbursementSource source,
            PayrollJournalEvidence journal) {
        List<String> issues = new ArrayList<>();
        if (source.journalId() == null || journal == null) issues.add("DISBURSEMENT_JOURNAL_UNAVAILABLE");
        else {
            if (!source.id().equals(journal.sourceId()) || !source.yearId().equals(journal.financialYearId())
                    || !source.date().equals(journal.postingDate())
                    || !source.currency().equals(journal.currencyCode())) {
                issues.add("DISBURSEMENT_PROVENANCE_MISMATCH");
            }
            if (journal.debit().compareTo(source.amount()) != 0
                    || journal.credit().compareTo(source.amount()) != 0) {
                issues.add("DISBURSEMENT_JOURNAL_AMOUNT_MISMATCH");
            }
            if ("REVERSED".equals(journal.status()) && !("POSTED".equals(journal.reversalStatus())
                    && journal.reversalOffsetsAccounts()
                    && journal.reversalDebit().compareTo(source.amount()) == 0
                    && journal.reversalCredit().compareTo(source.amount()) == 0)) {
                issues.add("DISBURSEMENT_REVERSAL_MISMATCH");
            } else if (!"REVERSED".equals(journal.status())
                    && "POSTED".equals(journal.reversalStatus())) {
                issues.add("DISBURSEMENT_UNEXPECTED_REVERSAL");
            }
            if (("RELEASED".equals(source.sourceStatus()) && !"REJECTED".equals(journal.status()))
                    || ("ACTIVE".equals(source.sourceStatus()) && "REJECTED".equals(journal.status()))) {
                issues.add("DISBURSEMENT_SOURCE_STATUS_MISMATCH");
            }
        }
        boolean posted = issues.isEmpty() && "ACTIVE".equals(source.sourceStatus())
                && "POSTED".equals(journal.status());
        String status = !issues.isEmpty() ? "EVIDENCE_MISMATCH" : posted ? "POSTED"
                : "SUBMITTED".equals(journal.status()) ? "AWAITING_APPROVAL"
                : "REVERSED".equals(journal.status()) ? "REVERSED"
                : "REJECTED".equals(journal.status()) ? "REJECTED" : "UNVERIFIED";
        return new DisbursementEvaluation(source.amount(), posted, status, issues);
    }

    static RecoveryEvaluation evaluateRecovery(RecoverySource source,
            List<RecoveryPostingSource> postings, Map<UUID, PayrollJournalEvidence> journals) {
        List<String> issues = new ArrayList<>();
        int posted = 0;
        boolean awaiting = false;
        for (RecoveryPostingSource posting : postings) {
            PayrollJournalEvidence journal = journals.get(posting.journalId());
            List<String> postingIssues = new ArrayList<>();
            if (posting.journalId() == null || journal == null) postingIssues.add("RECOVERY_JOURNAL_UNAVAILABLE");
            else {
                if (!posting.batchId().equals(journal.sourceId())
                        || !posting.yearId().equals(journal.financialYearId())
                        || !posting.date().equals(journal.postingDate())
                        || !posting.currency().equals(journal.currencyCode())) {
                    postingIssues.add("RECOVERY_JOURNAL_PROVENANCE_MISMATCH");
                }
                if (journal.debit().compareTo(posting.batchAmount()) != 0
                        || journal.credit().compareTo(posting.batchAmount()) != 0) {
                    postingIssues.add("RECOVERY_JOURNAL_AMOUNT_MISMATCH");
                }
                if ("REVERSED".equals(journal.status()) && !("POSTED".equals(journal.reversalStatus())
                        && journal.reversalOffsetsAccounts()
                        && journal.reversalDebit().compareTo(posting.batchAmount()) == 0
                        && journal.reversalCredit().compareTo(posting.batchAmount()) == 0)) {
                    postingIssues.add("RECOVERY_REVERSAL_MISMATCH");
                } else if (!"REVERSED".equals(journal.status())
                        && "POSTED".equals(journal.reversalStatus())) {
                    postingIssues.add("RECOVERY_UNEXPECTED_REVERSAL");
                }
            }
            postingIssues.forEach(issue -> add(issues, issue));
            if (postingIssues.isEmpty() && "ACTIVE".equals(posting.sourceStatus())
                    && "POSTED".equals(journal.status())) posted++;
            if (postingIssues.isEmpty() && "ACTIVE".equals(posting.sourceStatus())
                    && "SUBMITTED".equals(journal.status())) awaiting = true;
        }
        if (posted > 1) add(issues, "RECOVERY_MULTIPLE_POSTED_SOURCES");
        if ("RELEASED".equals(source.reservationStatus()) && posted > 0) {
            add(issues, "RECOVERY_RELEASED_BUT_POSTED");
        }
        boolean recovered = issues.isEmpty() && posted == 1;
        String status = !issues.isEmpty() ? "EVIDENCE_MISMATCH" : recovered ? "RECOVERED"
                : awaiting ? "AWAITING_ACCOUNTING_APPROVAL"
                : "ACTIVE".equals(source.reservationStatus()) ? "RESERVED_IN_PAYROLL" : "RELEASED";
        return new RecoveryEvaluation(source.id(), source.installmentId(), money(source.amount()),
                recovered, status, issues);
    }

    private static String disbursementStatus(List<DisbursementEvaluation> evaluations) {
        if (evaluations.stream().anyMatch(item -> !item.issues().isEmpty())) return "EVIDENCE_MISMATCH";
        if (evaluations.stream().filter(DisbursementEvaluation::countsAsDisbursed).count() > 1)
            return "EVIDENCE_MISMATCH";
        if (evaluations.stream().anyMatch(DisbursementEvaluation::countsAsDisbursed)) return "POSTED";
        if (evaluations.stream().anyMatch(item -> "AWAITING_APPROVAL".equals(item.status())))
            return "AWAITING_APPROVAL";
        if (evaluations.stream().anyMatch(item -> "REVERSED".equals(item.status()))) return "REVERSED";
        if (evaluations.stream().anyMatch(item -> "REJECTED".equals(item.status()))) return "REJECTED";
        return evaluations.isEmpty() ? "NOT_PREPARED" : "UNVERIFIED";
    }

    private static void add(List<String> issues, String issue) {
        if (!issues.contains(issue)) issues.add(issue);
    }
    private static BigDecimal money(BigDecimal amount) { return amount.setScale(2); }
    private static MapSqlParameterSource params(Scope scope, AdvanceQuery query) {
        return scope.params().addValue("dueThrough", query.dueThrough())
                .addValue("staffId", query.staffProfileId()).addValue("search", query.search());
    }

    record ExportResult(UUID schoolId, String csv, int rows) {}
    record AdvanceSource(UUID id, String number, String type, UUID staffId, String employeeCode,
            String staffName, UUID financialYearId, LocalDate issueDate, LocalDate recoveryStartMonth,
            String currency, BigDecimal principal, int installmentCount, String status) {}
    record InstallmentSource(UUID id, int number, LocalDate dueMonth, BigDecimal amount) {}
    record DisbursementSource(UUID id, UUID yearId, LocalDate date, String currency,
            BigDecimal amount, String sourceStatus, UUID journalId) {}
    record DisbursementEvaluation(BigDecimal amount, boolean countsAsDisbursed,
            String status, List<String> issues) {
        DisbursementEvaluation { issues = List.copyOf(issues); }
    }
    record RecoverySource(UUID id, UUID installmentId, BigDecimal amount, String reservationStatus) {}
    record RecoveryPostingSource(UUID batchId, UUID yearId, LocalDate date, String currency,
            BigDecimal batchAmount, String sourceStatus, UUID journalId) {}
    record RecoveryEvaluation(UUID id, UUID installmentId, BigDecimal amount,
            boolean countsAsRecovered, String status, List<String> issues) {
        RecoveryEvaluation { issues = List.copyOf(issues); }
    }
}
