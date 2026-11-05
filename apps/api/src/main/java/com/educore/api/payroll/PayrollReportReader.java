package com.educore.api.payroll;

import static com.educore.api.payroll.PayrollReports.*;

import com.educore.api.accounting.AccountingModuleBridge;
import com.educore.api.accounting.AccountingModuleBridge.PayrollJournalEvidence;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.payroll.PayrollAccountingService.Scope;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Reports read immutable payroll evidence and use the common accounting boundary for book evidence. */
@Service
public class PayrollReportReader {
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final int EXPORT_LIMIT = 5000;
    private final NamedParameterJdbcTemplate jdbc;
    private final PayrollAccountingService payrollAccounting;
    private final AccountingModuleBridge accounting;
    private final Clock clock;

    public PayrollReportReader(NamedParameterJdbcTemplate jdbc, PayrollAccountingService payrollAccounting,
            AccountingModuleBridge accounting, Clock clock) {
        this.jdbc = jdbc; this.payrollAccounting = payrollAccounting; this.accounting = accounting;
        this.clock = clock;
    }

    private static final String REGISTER_FROM = """
            FROM payroll_run_staff_results s
            JOIN payroll_runs r ON r.id=s.payroll_run_id
              AND r.organisation_id=s.organisation_id AND r.school_id=s.school_id
            WHERE s.organisation_id=:organisationId AND s.school_id=:schoolId
              AND r.status IN ('FINALIZED','REVERSED') AND r.payroll_month BETWEEN :fromDate AND :toDate
              AND (CAST(:staffId AS uuid) IS NULL OR s.staff_profile_id=:staffId)
              AND (:search='' OR position(lower(:search) in lower(s.employee_code_snapshot))>0
                OR position(lower(:search) in lower(s.staff_name_snapshot))>0)
            """;
    private static final String BATCH_FROM = """
            FROM payroll_accounting_posting_batches b
            JOIN payroll_runs r ON r.id=b.payroll_run_id
              AND r.organisation_id=b.organisation_id AND r.school_id=b.school_id
            WHERE b.organisation_id=:organisationId AND b.school_id=:schoolId
              AND b.posting_date BETWEEN :fromDate AND :toDate
              AND ((CAST(:staffId AS uuid) IS NULL AND :search='') OR EXISTS (
                SELECT 1 FROM payroll_accounting_posting_items i
                JOIN payroll_run_staff_results s ON s.id=i.payroll_run_staff_result_id
                  AND s.organisation_id=i.organisation_id AND s.school_id=i.school_id
                WHERE i.posting_batch_id=b.id AND i.organisation_id=b.organisation_id AND i.school_id=b.school_id
                  AND (CAST(:staffId AS uuid) IS NULL OR s.staff_profile_id=:staffId)
                  AND (:search='' OR position(lower(:search) in lower(s.employee_code_snapshot))>0
                    OR position(lower(:search) in lower(s.staff_name_snapshot))>0)))
            """;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<RegisterRow> register(AuthenticatedUser user, UUID schoolId, Query query) {
        Scope scope = payrollAccounting.reportingScope(user, schoolId);
        monthly(query);
        long total = count(REGISTER_FROM, scope, query);
        List<RegisterRow> rows = registerRows(user, scope, query, query.size(), (long) query.page() * query.size());
        return page(scope, query, total, rows);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<ReconciliationRow> reconciliation(AuthenticatedUser user, UUID schoolId, Query query) {
        Scope scope = payrollAccounting.reportingScope(user, schoolId);
        long total = count(BATCH_FROM, scope, query);
        List<ReconciliationRow> rows = reconciliationRows(user, scope, query, query.size(), (long) query.page() * query.size());
        return page(scope, query, total, rows);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<SummaryRow> summary(AuthenticatedUser user, UUID schoolId, Query query, GroupBy groupBy) {
        Scope scope = payrollAccounting.reportingScope(user, schoolId);
        monthly(query);
        String definition = summaryDefinition(groupBy);
        long total = summaryCount(definition, scope, query);
        List<SummaryRow> rows = summaryRows(definition, groupBy, scope, query,
                query.size(), (long) query.page() * query.size());
        return page(scope, query, total, rows);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ExportResult exportRegister(AuthenticatedUser user, UUID schoolId, Query query) {
        Scope scope = payrollAccounting.reportingScope(user, schoolId);
        monthly(query);
        exportSize(count(REGISTER_FROM, scope, query));
        List<RegisterRow> rows = registerRows(user, scope, query, EXPORT_LIMIT, 0);
        StringBuilder csv = new StringBuilder("\uFEFF");
        PayrollReportCsv.row(csv, "Payroll month", "Run", "Run status", "Employee code", "Staff name", "Currency",
                "Frozen gross", "Frozen deductions", "Frozen employer contributions", "Frozen net",
                "Accrual status", "Payment status", "Posted paid", "Pending approval", "Unsettled net",
                "Staff result ID", "Accrual journal ID", "Payment journal ID",
                "Assignment snapshot status", "Assignment effective date", "Designation", "Department");
        for (RegisterRow row : rows) PayrollReportCsv.row(csv, row.payrollMonth(), row.runCode(), row.runStatus(),
                row.employeeCode(), row.staffName(), row.currencyCode(), row.gross(), row.deductions(),
                row.employerContributions(), row.net(), row.accrualStatus(), row.paymentStatus(),
                row.paid(), row.pending(), row.unsettled(), row.id(), row.accrualJournalId(), row.paymentJournalId(),
                row.assignmentSnapshotStatus(), row.assignmentSnapshotDate(), row.designationName(), row.departmentName());
        return new ExportResult(scope.schoolId(), csv.toString(), rows.size());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ExportResult exportReconciliation(AuthenticatedUser user, UUID schoolId, Query query) {
        Scope scope = payrollAccounting.reportingScope(user, schoolId);
        exportSize(count(BATCH_FROM, scope, query));
        List<ReconciliationRow> rows = reconciliationRows(user, scope, query, EXPORT_LIMIT, 0);
        StringBuilder csv = new StringBuilder("\uFEFF");
        PayrollReportCsv.row(csv, "Batch ID", "Run", "Type", "Posting date", "Currency", "Declared sources",
                "Declared amount", "Actual sources", "Source item amount", "Frozen payroll amount",
                "Journal ID", "Journal status", "Journal debit", "Journal credit", "Reversal journal ID",
                "Reversal status", "Reconciliation status", "Issues");
        for (ReconciliationRow row : rows) PayrollReportCsv.row(csv, row.id(), row.runCode(), row.postingType(),
                row.postingDate(), row.currencyCode(), row.sourceCount(), row.sourceAmount(),
                row.actualSourceCount(), row.actualSourceAmount(), row.frozenPayrollAmount(), row.journalId(),
                row.journalStatus(), row.journalDebit(), row.journalCredit(), row.reversalJournalId(),
                row.reversalStatus(), row.reconciliationStatus(), String.join("; ", row.issues()));
        return new ExportResult(scope.schoolId(), csv.toString(), rows.size());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ExportResult exportSummary(AuthenticatedUser user, UUID schoolId, Query query, GroupBy groupBy) {
        Scope scope = payrollAccounting.reportingScope(user, schoolId);
        monthly(query);
        String definition = summaryDefinition(groupBy);
        exportSize(summaryCount(definition, scope, query));
        List<SummaryRow> rows = summaryRows(definition, groupBy, scope, query, EXPORT_LIMIT, 0);
        StringBuilder csv = new StringBuilder("\uFEFF");
        PayrollReportCsv.row(csv, "Grouping", "Dimension status", "Dimension code", "Dimension label",
                "Run status", "Currency", "Payroll runs", "Staff results", "Frozen gross",
                "Frozen deductions", "Frozen employer contributions", "Frozen net");
        for (SummaryRow row : rows) PayrollReportCsv.row(csv, row.groupBy(), row.dimensionStatus(),
                row.dimensionCode(), row.dimensionLabel(), row.runStatus(), row.currencyCode(),
                row.payrollRunCount(), row.staffResultCount(), row.gross(), row.deductions(),
                row.employerContributions(), row.net());
        return new ExportResult(scope.schoolId(), csv.toString(), rows.size());
    }

    private List<SummaryRow> summaryRows(String definition, GroupBy groupBy, Scope scope, Query query,
            int limit, long offset) {
        return jdbc.query(definition + """
                SELECT dimension_status,dimension_code,dimension_label,run_status,currency_code,
                       payroll_run_count,staff_result_count,gross,deductions,employer_contributions,net
                FROM summary
                ORDER BY run_status,currency_code,dimension_status,dimension_label,dimension_code
                LIMIT :limit OFFSET :offset
                """, params(scope, query).addValue("limit", limit).addValue("offset", offset), (r, n) ->
                new SummaryRow(groupBy.name(), r.getString("dimension_status"), r.getString("dimension_code"),
                        r.getString("dimension_label"), r.getString("run_status"), r.getString("currency_code"),
                        r.getLong("payroll_run_count"), r.getLong("staff_result_count"),
                        r.getBigDecimal("gross"), r.getBigDecimal("deductions"),
                        r.getBigDecimal("employer_contributions"), r.getBigDecimal("net")));
    }

    private long summaryCount(String definition, Scope scope, Query query) {
        return Objects.requireNonNull(jdbc.queryForObject(definition + " SELECT count(*) FROM summary",
                params(scope, query), Long.class));
    }

    private static String summaryDefinition(GroupBy groupBy) {
        if (groupBy == null) throw invalid("Choose a payroll summary grouping.");
        if (groupBy == GroupBy.DEDUCTION_COMPONENT) return """
                WITH source AS (
                  SELECT r.id run_id,r.status run_status,r.currency_code,s.id result_id,
                         line.component_code_snapshot dimension_code,
                         line.component_name_snapshot dimension_label,line.payable_amount
                  FROM payroll_run_staff_lines line
                  JOIN payroll_run_staff_results s ON s.id=line.payroll_run_staff_result_id
                    AND s.organisation_id=line.organisation_id AND s.school_id=line.school_id
                  JOIN payroll_runs r ON r.id=s.payroll_run_id
                    AND r.organisation_id=s.organisation_id AND r.school_id=s.school_id
                  WHERE line.organisation_id=:organisationId AND line.school_id=:schoolId
                    AND line.component_kind_snapshot='DEDUCTION'
                    AND r.status IN ('FINALIZED','REVERSED') AND r.payroll_month BETWEEN :fromDate AND :toDate
                    AND (CAST(:staffId AS uuid) IS NULL OR s.staff_profile_id=:staffId)
                    AND (:search='' OR position(lower(:search) in lower(s.employee_code_snapshot))>0
                      OR position(lower(:search) in lower(s.staff_name_snapshot))>0)
                  UNION ALL
                  SELECT r.id,r.status,r.currency_code,s.id,
                         'ADVANCE_RECOVERY:' || recovery.employee_advance_id::text,
                         'Advance recovery ' || recovery.advance_number_snapshot,recovery.recovery_amount
                  FROM payroll_run_advance_recoveries recovery
                  JOIN payroll_run_staff_results s ON s.id=recovery.payroll_run_staff_result_id
                    AND s.organisation_id=recovery.organisation_id AND s.school_id=recovery.school_id
                  JOIN payroll_runs r ON r.id=s.payroll_run_id
                    AND r.organisation_id=s.organisation_id AND r.school_id=s.school_id
                  WHERE recovery.organisation_id=:organisationId AND recovery.school_id=:schoolId
                    AND r.status IN ('FINALIZED','REVERSED') AND r.payroll_month BETWEEN :fromDate AND :toDate
                    AND (CAST(:staffId AS uuid) IS NULL OR s.staff_profile_id=:staffId)
                    AND (:search='' OR position(lower(:search) in lower(s.employee_code_snapshot))>0
                      OR position(lower(:search) in lower(s.staff_name_snapshot))>0)
                ), summary AS (
                  SELECT 'CAPTURED'::varchar dimension_status,dimension_code,dimension_label,run_status,currency_code,
                         count(DISTINCT run_id) payroll_run_count,count(DISTINCT result_id) staff_result_count,
                         NULL::numeric gross,sum(payable_amount) deductions,
                         NULL::numeric employer_contributions,NULL::numeric net
                  FROM source
                  GROUP BY dimension_code,dimension_label,run_status,currency_code
                )
                """;
        String statusExpression = groupBy == GroupBy.DEPARTMENT
                ? "CASE WHEN s.assignment_snapshot_status='CAPTURED' AND s.department_name_snapshot IS NULL "
                    + "THEN 'NO_DEPARTMENT' ELSE s.assignment_snapshot_status END"
                : "s.assignment_snapshot_status";
        String labelExpression = groupBy == GroupBy.DEPARTMENT
                ? "CASE WHEN s.assignment_snapshot_status='CAPTURED' AND s.department_name_snapshot IS NOT NULL "
                    + "THEN s.department_name_snapshot WHEN s.assignment_snapshot_status='CAPTURED' "
                    + "THEN 'No department recorded' WHEN s.assignment_snapshot_status='NOT_CAPTURED' "
                    + "THEN 'Historical classification not captured' WHEN s.assignment_snapshot_status='NO_ASSIGNMENT' "
                    + "THEN 'No effective primary assignment' ELSE 'Ambiguous effective assignment' END"
                : "CASE WHEN s.assignment_snapshot_status='CAPTURED' THEN s.designation_name_snapshot "
                    + "WHEN s.assignment_snapshot_status='NOT_CAPTURED' THEN 'Historical classification not captured' "
                    + "WHEN s.assignment_snapshot_status='NO_ASSIGNMENT' THEN 'No effective primary assignment' "
                    + "ELSE 'Ambiguous effective assignment' END";
        return """
                WITH source AS (
                  SELECT r.id run_id,r.status run_status,r.currency_code,s.id result_id,
                         %s dimension_status,CAST(NULL AS varchar) dimension_code,
                         %s dimension_label,s.payable_gross_amount gross,s.deduction_amount deductions,
                         s.employer_contribution_amount employer_contributions,s.net_amount net
                """.formatted(statusExpression, labelExpression) + REGISTER_FROM + """
                ), summary AS (
                  SELECT dimension_status,dimension_code,dimension_label,run_status,currency_code,
                         count(DISTINCT run_id) payroll_run_count,count(*) staff_result_count,
                         sum(gross) gross,sum(deductions) deductions,
                         sum(employer_contributions) employer_contributions,sum(net) net
                  FROM source
                  GROUP BY dimension_status,dimension_code,dimension_label,run_status,currency_code
                )
                """;
    }

    private List<RegisterRow> registerRows(AuthenticatedUser user, Scope scope, Query query, int limit, long offset) {
        List<RegisterSource> sources = jdbc.query("""
                SELECT s.id,r.id run_id,r.run_code,r.payroll_month,r.status run_status,s.staff_profile_id,
                       s.employee_code_snapshot,s.staff_name_snapshot,r.currency_code,
                       s.payable_gross_amount,s.deduction_amount,s.employer_contribution_amount,s.net_amount,
                       s.assignment_snapshot_status,s.assignment_snapshot_date,s.assignment_id_snapshot,
                       s.designation_name_snapshot,s.department_name_snapshot
                """ + REGISTER_FROM + """
                ORDER BY r.payroll_month DESC,r.attempt_number DESC,r.id,s.staff_name_snapshot,s.id
                LIMIT :limit OFFSET :offset
                """, params(scope, query).addValue("limit", limit).addValue("offset", offset), (r, n) ->
                new RegisterSource(r.getObject("id", UUID.class), r.getObject("run_id", UUID.class),
                        r.getString("run_code"), r.getObject("payroll_month", LocalDate.class), r.getString("run_status"),
                        r.getObject("staff_profile_id", UUID.class), r.getString("employee_code_snapshot"),
                        r.getString("staff_name_snapshot"), r.getString("currency_code"),
                        r.getBigDecimal("payable_gross_amount"), r.getBigDecimal("deduction_amount"),
                        r.getBigDecimal("employer_contribution_amount"), r.getBigDecimal("net_amount"),
                        r.getString("assignment_snapshot_status"), r.getObject("assignment_snapshot_date", LocalDate.class),
                        r.getObject("assignment_id_snapshot", UUID.class), r.getString("designation_name_snapshot"),
                        r.getString("department_name_snapshot")));
        if (sources.isEmpty()) return List.of();
        List<Claim> claims = jdbc.query("""
                SELECT i.payroll_run_staff_result_id,i.posting_type,b.accounting_journal_id
                FROM payroll_accounting_posting_items i
                JOIN payroll_accounting_posting_batches b ON b.id=i.posting_batch_id
                  AND b.organisation_id=i.organisation_id AND b.school_id=i.school_id
                WHERE i.organisation_id=:organisationId AND i.school_id=:schoolId AND i.status='ACTIVE'
                  AND i.payroll_run_staff_result_id IN (:ids)
                """, scope.params().addValue("ids", sources.stream().map(RegisterSource::id).toList()),
                (r, n) -> new Claim(r.getObject("payroll_run_staff_result_id", UUID.class),
                        r.getString("posting_type"), r.getObject("accounting_journal_id", UUID.class)));
        Map<UUID, String> statuses = accounting.payrollJournalStatuses(user, scope.schoolId(),
                claims.stream().map(Claim::journalId).filter(Objects::nonNull).distinct().toList());
        Map<UUID, List<Claim>> byResult = claims.stream().collect(java.util.stream.Collectors.groupingBy(Claim::resultId));
        return sources.stream().map(source -> {
            List<Claim> own = byResult.getOrDefault(source.id(), List.of());
            Claim accrual = own.stream().filter(c -> "ACCRUAL".equals(c.type())).findFirst().orElse(null);
            Claim payment = own.stream().filter(c -> "PAYMENT".equals(c.type())).findFirst().orElse(null);
            String accrualStatus = claimStatus(accrual, statuses, false);
            String paymentStatus = claimStatus(payment, statuses, true);
            if (source.net().signum() == 0) paymentStatus = "NOT_PAYABLE";
            boolean unknown = "UNVERIFIED".equals(paymentStatus);
            BigDecimal paid = unknown ? null : "PAID".equals(paymentStatus) ? source.net() : ZERO;
            BigDecimal pending = unknown ? null : "AWAITING_APPROVAL".equals(paymentStatus) ? source.net() : ZERO;
            BigDecimal unsettled = unknown ? null : source.net().subtract(paid);
            if ("REVERSED".equals(source.status())) {
                boolean conflict = "PAID".equals(paymentStatus) || "AWAITING_APPROVAL".equals(paymentStatus)
                        || "POSTED".equals(accrualStatus) || "AWAITING_APPROVAL".equals(accrualStatus)
                        || "UNVERIFIED".equals(accrualStatus) || unknown;
                paymentStatus = conflict ? "INCONSISTENT" : "REVERSED";
                paid = conflict ? null : ZERO; pending = conflict ? null : ZERO; unsettled = conflict ? null : ZERO;
            }
            return new RegisterRow(source.id(), source.runId(), source.code(), source.month(), source.status(),
                    source.staffId(), source.employeeCode(), source.name(), source.currency(), source.gross(),
                    source.deductions(), source.employer(), source.net(), accrualStatus, paymentStatus,
                    paid, pending, unsettled, accrual == null ? null : accrual.journalId(), payment == null ? null : payment.journalId(),
                    source.assignmentStatus(), source.assignmentDate(), source.assignmentId(), source.designation(), source.department());
        }).toList();
    }

    private List<ReconciliationRow> reconciliationRows(AuthenticatedUser user, Scope scope, Query query, int limit, long offset) {
        List<BatchSource> sources = jdbc.query("""
                SELECT b.id,b.payroll_run_id,r.run_code,b.financial_year_id,b.posting_type,b.posting_date,
                       r.currency_code,b.source_count,b.source_amount,b.accounting_journal_id,
                       r.staff_count run_staff_count,r.payable_gross_total+r.employer_contribution_total run_accrual_amount
                """ + BATCH_FROM + " ORDER BY b.posting_date DESC,b.id LIMIT :limit OFFSET :offset",
                params(scope, query).addValue("limit", limit).addValue("offset", offset), (r, n) ->
                new BatchSource(r.getObject("id", UUID.class), r.getObject("payroll_run_id", UUID.class),
                        r.getString("run_code"), r.getObject("financial_year_id", UUID.class), r.getString("posting_type"),
                        r.getObject("posting_date", LocalDate.class), r.getString("currency_code"),
                        r.getInt("source_count"), r.getBigDecimal("source_amount"), r.getObject("accounting_journal_id", UUID.class),
                        r.getInt("run_staff_count"), r.getBigDecimal("run_accrual_amount")));
        if (sources.isEmpty()) return List.of();
        Map<UUID, ItemEvidence> items = jdbc.query("""
                SELECT i.posting_batch_id,count(*) source_count,sum(i.source_amount) source_amount,
                       sum(CASE i.posting_type WHEN 'ACCRUAL' THEN s.payable_gross_amount+s.employer_contribution_amount
                         ELSE s.net_amount END) frozen_amount,
                       bool_and(i.posting_type=b.posting_type AND s.payroll_run_id=b.payroll_run_id) correct_run
                FROM payroll_accounting_posting_items i
                JOIN payroll_run_staff_results s ON s.id=i.payroll_run_staff_result_id
                  AND s.organisation_id=i.organisation_id AND s.school_id=i.school_id
                JOIN payroll_accounting_posting_batches b ON b.id=i.posting_batch_id
                  AND b.organisation_id=i.organisation_id AND b.school_id=i.school_id
                WHERE i.organisation_id=:organisationId AND i.school_id=:schoolId AND i.posting_batch_id IN (:ids)
                GROUP BY i.posting_batch_id
                """, scope.params().addValue("ids", sources.stream().map(BatchSource::id).toList()), r -> {
                    Map<UUID, ItemEvidence> result = new java.util.LinkedHashMap<>();
                    while (r.next()) result.put(r.getObject("posting_batch_id", UUID.class), new ItemEvidence(
                            r.getInt("source_count"), r.getBigDecimal("source_amount"),
                            r.getBigDecimal("frozen_amount"), r.getBoolean("correct_run")));
                    return result;
                });
        Map<UUID, PayrollJournalEvidence> journals = accounting.payrollJournalEvidence(user, scope.schoolId(),
                sources.stream().map(BatchSource::journalId).filter(Objects::nonNull).distinct().toList());
        return sources.stream().map(source -> reconcile(source,
                items.getOrDefault(source.id(), new ItemEvidence(0, ZERO, ZERO, false)),
                journals.get(source.journalId()))).toList();
    }

    static ReconciliationRow reconcile(BatchSource source, ItemEvidence items, PayrollJournalEvidence journal) {
        List<String> issues = new ArrayList<>();
        if (items.count() != source.count()) issues.add("SOURCE_COUNT_MISMATCH");
        if (!items.correctRun()) issues.add("SOURCE_RUN_MISMATCH");
        if (items.amount().compareTo(source.amount()) != 0) issues.add("SOURCE_AMOUNT_MISMATCH");
        if (items.frozenAmount().compareTo(source.amount()) != 0) issues.add("FROZEN_PAYROLL_MISMATCH");
        if ("ACCRUAL".equals(source.type()) && (source.count() != source.runStaffCount()
                || source.amount().compareTo(source.runAccrualAmount()) != 0)) issues.add("INCOMPLETE_RUN_ACCRUAL");
        if (journal == null) issues.add("JOURNAL_UNAVAILABLE");
        else {
            if (!source.id().equals(journal.sourceId()) || !source.yearId().equals(journal.financialYearId())
                    || !source.date().equals(journal.postingDate()) || !source.currency().equals(journal.currencyCode())) {
                issues.add("JOURNAL_PROVENANCE_MISMATCH");
            }
            if (journal.debit().compareTo(source.amount()) != 0 || journal.credit().compareTo(source.amount()) != 0) {
                issues.add("JOURNAL_AMOUNT_MISMATCH");
            }
            if ("REVERSED".equals(journal.status())) {
                if (!"POSTED".equals(journal.reversalStatus()) || !journal.reversalOffsetsAccounts()
                        || journal.reversalDebit().compareTo(source.amount()) != 0
                        || journal.reversalCredit().compareTo(source.amount()) != 0) issues.add("REVERSAL_MISMATCH");
            } else if ("POSTED".equals(journal.reversalStatus())) issues.add("UNEXPECTED_POSTED_REVERSAL");
        }
        String status = !issues.isEmpty() ? "MISMATCH" : switch (journal.status()) {
            case "POSTED" -> "MATCHED_POSTED";
            case "REVERSED" -> "MATCHED_REVERSED";
            case "REJECTED" -> "MATCHED_REJECTED";
            case "SUBMITTED" -> "AWAITING_APPROVAL";
            default -> "UNVERIFIED";
        };
        return new ReconciliationRow(source.id(), source.runId(), source.code(), source.type(), source.date(),
                source.currency(), source.count(), source.amount(), items.count(), items.amount(), items.frozenAmount(),
                source.journalId(), journal == null ? "UNVERIFIED" : journal.status(),
                journal == null ? null : journal.debit(), journal == null ? null : journal.credit(),
                journal == null ? null : journal.reversalId(), journal == null ? null : journal.reversalStatus(), status, issues);
    }

    private static String claimStatus(Claim claim, Map<UUID, String> statuses, boolean payment) {
        String absent = payment ? "UNPAID" : "NOT_PREPARED";
        if (claim == null) return absent;
        String status = statuses.get(claim.journalId());
        if (status == null) return "UNVERIFIED";
        return switch (status) {
            case "POSTED" -> payment ? "PAID" : "POSTED";
            case "SUBMITTED" -> "AWAITING_APPROVAL";
            case "REJECTED", "REVERSED" -> absent;
            default -> "UNVERIFIED";
        };
    }
    private long count(String from, Scope scope, Query query) {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) " + from, params(scope, query), Long.class));
    }
    private static MapSqlParameterSource params(Scope scope, Query query) {
        return scope.params().addValue("fromDate", query.from()).addValue("toDate", query.to())
                .addValue("staffId", query.staffProfileId()).addValue("search", query.search());
    }
    private <T> Page<T> page(Scope scope, Query query, long total, List<T> items) {
        return new Page<>(scope.schoolId(), scope.schoolName(), Instant.now(clock), query.from(), query.to(),
                query.page(), query.size(), total, items);
    }
    private static void monthly(Query query) {
        if (query.from().getDayOfMonth() != 1 || query.to().getDayOfMonth() != 1) {
            throw invalid("Use the first day of each payroll month for the salary register.");
        }
    }
    static void exportSize(long total) {
        if (total > EXPORT_LIMIT) throw invalid("This export exceeds 5000 rows. Narrow the date range or employee filter; no rows were silently omitted.");
    }
    record ExportResult(UUID schoolId, String csv, int rows) {}
    record RegisterSource(UUID id, UUID runId, String code, LocalDate month, String status, UUID staffId,
            String employeeCode, String name, String currency, BigDecimal gross, BigDecimal deductions,
            BigDecimal employer, BigDecimal net, String assignmentStatus, LocalDate assignmentDate,
            UUID assignmentId, String designation, String department) {}
    record Claim(UUID resultId, String type, UUID journalId) {}
    record BatchSource(UUID id, UUID runId, String code, UUID yearId, String type, LocalDate date,
            String currency, int count, BigDecimal amount, UUID journalId, int runStaffCount, BigDecimal runAccrualAmount) {}
    record ItemEvidence(int count, BigDecimal amount, BigDecimal frozenAmount, boolean correctRun) {}
}
