package com.educore.api.payroll;

import com.educore.api.accounting.AccountingModuleBridge;
import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/** Internal payroll boundary: callers must first resolve live school-scoped payroll authorization. */
@Service
class PayrollRecoveryService {
    private final NamedParameterJdbcTemplate jdbc;
    private final AccountingModuleBridge accounting;

    PayrollRecoveryService(NamedParameterJdbcTemplate jdbc, AccountingModuleBridge accounting) {
        this.jdbc = jdbc;
        this.accounting = accounting;
    }

    Plan plan(AuthenticatedUser user, UUID organisationId, UUID schoolId, LocalDate month,
            String currency, UUID owningRunId, List<UUID> eligibleStaff) {
        if (eligibleStaff.isEmpty()) return new Plan(Map.of(), Map.of(), Map.of());
        List<Candidate> candidates = jdbc.query("""
                SELECT a.id advance_id,a.staff_profile_id,a.advance_number,a.currency_code,
                  a.receivable_account_id,i.id installment_id,i.due_month,i.planned_amount,
                  p.id posting_id,p.accounting_journal_id,owner.payroll_run_id reserved_run_id
                FROM payroll_employee_advances a
                JOIN payroll_employee_advance_installments i ON i.employee_advance_id=a.id
                  AND i.organisation_id=a.organisation_id AND i.school_id=a.school_id
                LEFT JOIN payroll_employee_advance_postings p ON p.employee_advance_id=a.id
                  AND p.organisation_id=a.organisation_id AND p.school_id=a.school_id AND p.status='ACTIVE'
                LEFT JOIN payroll_run_advance_recoveries held ON held.installment_id=i.id
                  AND held.organisation_id=a.organisation_id AND held.school_id=a.school_id
                  AND held.reservation_status='ACTIVE'
                LEFT JOIN payroll_run_staff_results owner ON owner.id=held.payroll_run_staff_result_id
                  AND owner.organisation_id=a.organisation_id AND owner.school_id=a.school_id
                WHERE a.organisation_id=:organisationId AND a.school_id=:schoolId AND a.status='APPROVED'
                  AND a.staff_profile_id IN (:staffIds) AND i.due_month<=:month
                  AND (i.due_month=:month OR held.id IS NULL)
                ORDER BY a.staff_profile_id,a.id,i.due_month,i.id LIMIT 10001
                """, params(organisationId, schoolId).addValue("staffIds", eligibleStaff).addValue("month", month),
                (r, n) -> new Candidate(r.getObject("staff_profile_id", UUID.class),
                        r.getObject("advance_id", UUID.class), r.getString("advance_number"),
                        r.getObject("installment_id", UUID.class), r.getObject("due_month", LocalDate.class),
                        r.getBigDecimal("planned_amount"), r.getString("currency_code"),
                        r.getObject("receivable_account_id", UUID.class), r.getObject("posting_id", UUID.class),
                        r.getObject("accounting_journal_id", UUID.class), r.getObject("reserved_run_id", UUID.class)));
        if (candidates.size()>10000) throw new ApiException(HttpStatus.CONFLICT,
                "PAYROLL_RECOVERY_REVIEW_REQUIRED", "The unresolved advance schedule requires accounting review before payroll.");
        List<UUID> journals = candidates.stream().map(Candidate::journalId).filter(Objects::nonNull).distinct().toList();
        Map<UUID,String> statuses = journals.isEmpty() ? Map.of()
                : accounting.payrollAdvanceJournalStatuses(user, schoolId, journals);
        return calculate(month, currency, owningRunId, candidates, statuses);
    }

    static Plan calculate(LocalDate month, String currency, UUID owningRunId,
            List<Candidate> candidates, Map<UUID,String> journalStatuses) {
        Map<UUID,List<Line>> lines = new LinkedHashMap<>();
        Map<UUID,List<String>> warnings = new LinkedHashMap<>(), blockers = new LinkedHashMap<>();
        Set<UUID> seen = new java.util.HashSet<>();
        for (Candidate c : candidates) {
            if (!seen.add(c.installmentId())) {
                code(blockers,c.staffId(),"ADVANCE_RECOVERY_SOURCE_DUPLICATED");
                continue;
            }
            String status = c.journalId()==null ? null : journalStatuses.get(c.journalId());
            if (c.journalId()!=null && !Set.of("POSTED","SUBMITTED","REJECTED","REVERSED").contains(
                    Objects.requireNonNullElse(status,"UNVERIFIED"))) {
                code(blockers,c.staffId(),"ADVANCE_DISBURSEMENT_UNVERIFIED");
                continue;
            }
            if (!"POSTED".equals(status)) {
                if (c.dueMonth().equals(month)) code(warnings,c.staffId(),"ADVANCE_NOT_DISBURSED");
                continue;
            }
            if (c.dueMonth().isBefore(month)) {
                if (c.reservedRunId()==null) code(warnings,c.staffId(),"ADVANCE_MISSED_INSTALLMENT_REVIEW");
                continue;
            }
            if (!c.dueMonth().equals(month)) continue;
            if (c.reservedRunId()!=null && !c.reservedRunId().equals(owningRunId)) {
                code(blockers,c.staffId(),"ADVANCE_RECOVERY_ALREADY_RESERVED");
                continue;
            }
            if (!currency.equals(c.currency()) || c.amount()==null || c.amount().signum()<=0
                    || c.amount().scale()>2 || c.amount().compareTo(new BigDecimal("1000000000000.00"))>0
                    || c.postingId()==null || c.receivableId()==null) {
                code(blockers,c.staffId(),"ADVANCE_RECOVERY_EVIDENCE_INVALID");
                continue;
            }
            lines.computeIfAbsent(c.staffId(),ignored->new ArrayList<>()).add(new Line(
                    c.installmentId(),c.advanceId(),c.number(),c.installmentId(),c.dueMonth(),
                    c.amount().setScale(2),c.currency(),c.receivableId(),c.postingId(),"ACTIVE"));
        }
        return new Plan(lines,warnings,blockers);
    }

    void insert(UUID organisationId, UUID schoolId, UUID resultId, List<Line> lines, Instant now) {
        for (Line line : lines) jdbc.update("""
                INSERT INTO payroll_run_advance_recoveries(id,organisation_id,school_id,
                  payroll_run_staff_result_id,employee_advance_id,installment_id,disbursement_posting_id,
                  receivable_account_id,advance_number_snapshot,due_month,currency_code,recovery_amount,
                  reservation_status,created_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:resultId,:advanceId,:installmentId,:postingId,
                  :receivableId,:number,:month,:currency,:amount,'ACTIVE',:now,0)
                """, params(organisationId,schoolId).addValue("id",UUID.randomUUID()).addValue("resultId",resultId)
                .addValue("advanceId",line.advanceId()).addValue("installmentId",line.installmentId())
                .addValue("postingId",line.postingId()).addValue("receivableId",line.receivableId())
                .addValue("number",line.number()).addValue("month",line.dueMonth())
                .addValue("currency",line.currency()).addValue("amount",line.amount()).addValue("now",now));
    }

    Map<UUID,List<Line>> forRuns(UUID organisationId, UUID schoolId, List<UUID> runIds) {
        if (runIds.isEmpty()) return Map.of();
        return jdbc.query("""
                SELECT recovery.* FROM payroll_run_advance_recoveries recovery
                JOIN payroll_run_staff_results result ON result.id=recovery.payroll_run_staff_result_id
                  AND result.organisation_id=recovery.organisation_id AND result.school_id=recovery.school_id
                WHERE recovery.organisation_id=:organisationId AND recovery.school_id=:schoolId
                  AND result.payroll_run_id IN (:runIds)
                ORDER BY recovery.payroll_run_staff_result_id,recovery.employee_advance_id,recovery.installment_id
                """,params(organisationId,schoolId).addValue("runIds",runIds),r->{
                    Map<UUID,List<Line>> byResult = new LinkedHashMap<>();
                    while (r.next()) byResult.computeIfAbsent(r.getObject("payroll_run_staff_result_id",UUID.class),
                            ignored->new ArrayList<>()).add(new Line(r.getObject("id",UUID.class),
                            r.getObject("employee_advance_id",UUID.class),r.getString("advance_number_snapshot"),
                            r.getObject("installment_id",UUID.class),r.getObject("due_month",LocalDate.class),
                            r.getBigDecimal("recovery_amount"),r.getString("currency_code"),
                            r.getObject("receivable_account_id",UUID.class),r.getObject("disbursement_posting_id",UUID.class),
                            r.getString("reservation_status")));
                    return immutable(byResult);
                });
    }

    private static MapSqlParameterSource params(UUID organisationId, UUID schoolId) {
        return new MapSqlParameterSource("organisationId",organisationId).addValue("schoolId",schoolId);
    }
    private static void code(Map<UUID,List<String>> target,UUID staffId,String code) {
        List<String> list = target.computeIfAbsent(staffId,ignored->new ArrayList<>());
        if (!list.contains(code)) list.add(code);
    }
    private static <T> Map<UUID,List<T>> immutable(Map<UUID,List<T>> values) {
        Map<UUID,List<T>> result = new LinkedHashMap<>();
        values.forEach((key,value)->result.put(key,List.copyOf(value)));
        return Map.copyOf(result);
    }
    record Candidate(UUID staffId,UUID advanceId,String number,UUID installmentId,LocalDate dueMonth,
            BigDecimal amount,String currency,UUID receivableId,UUID postingId,UUID journalId,UUID reservedRunId) {}
    record Line(UUID id,UUID advanceId,String number,UUID installmentId,LocalDate dueMonth,
            BigDecimal amount,String currency,UUID receivableId,UUID postingId,String reservationStatus) {
        PayrollRunWorkspace.RunRecoveryItem item() {
            return new PayrollRunWorkspace.RunRecoveryItem(id,advanceId,number,installmentId,dueMonth,amount,reservationStatus);
        }
    }
    record Plan(Map<UUID,List<Line>> byStaff,Map<UUID,List<String>> warnings,Map<UUID,List<String>> blockers) {
        Plan { byStaff=immutable(byStaff); warnings=immutable(warnings); blockers=immutable(blockers); }
    }
}
