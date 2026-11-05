package com.educore.api.payroll;

import static com.educore.api.accounting.AccountingModuleBridge.*;
import static com.educore.api.payroll.PayrollAdvanceController.*;
import static com.educore.api.payroll.PayrollAdvanceWorkspace.*;

import com.educore.api.accounting.AccountingModuleBridge;
import com.educore.api.common.api.ApiException;
import com.educore.api.common.api.DatabaseConflict;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import com.educore.api.people.PeoplePayrollBridge;
import com.educore.api.people.PeoplePayrollBridge.PayrollStaff;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class PayrollAdvanceService {
    private static final Set<String> TYPES = Set.of("ADVANCE", "LOAN");
    private static final Set<String> METHODS = Set.of("CASH", "BANK_TRANSFER", "CHEQUE", "SCHOOL_UPI");
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000000000.00");

    private final NamedParameterJdbcTemplate jdbc;
    private final PeoplePayrollBridge people;
    private final AccountingModuleBridge accounting;
    private final SecurityAuditService audit;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate readTransaction;
    private final TransactionTemplate auditTransaction;

    public PayrollAdvanceService(NamedParameterJdbcTemplate jdbc, PeoplePayrollBridge people,
            AccountingModuleBridge accounting, SecurityAuditService audit,
            ObjectMapper mapper, Clock clock, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.people = people;
        this.accounting = accounting;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
        this.readTransaction = new TransactionTemplate(transactions);
        this.readTransaction.setReadOnly(true);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.auditTransaction = new TransactionTemplate(transactions);
        this.auditTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public PayrollAdvanceWorkspace workspace(AuthenticatedUser user, UUID requestedSchoolId) {
        return workspace(user, requestedSchoolId, new Query(0, 25, "", null, null));
    }

    public PayrollAdvanceWorkspace workspace(AuthenticatedUser user, UUID requestedSchoolId, Query query) {
        PayrollAdvanceWorkspace result = Objects.requireNonNull(readTransaction.execute(
                transaction -> readWorkspace(user, requestedSchoolId, query)));
        auditTransaction.executeWithoutResult(transaction -> {
            Scope scope = scope(user, result.schoolId(), null);
            audit.recordScoped(user, scope.actorId(), scope.schoolId(), "payroll.advance.read",
                    "PAYROLL_EMPLOYEE_ADVANCES", scope.schoolId().toString(), AuditOutcome.SUCCEEDED,
                    null, Map.of("rowCount", result.advances().size()));
        });
        return result;
    }

    private PayrollAdvanceWorkspace readWorkspace(AuthenticatedUser user, UUID requestedSchoolId, Query query) {
        Scope scope = scope(user, requestedSchoolId, null);
        MapSqlParameterSource filters = filters(scope, query);
        Long total = jdbc.queryForObject("""
                SELECT count(*) FROM payroll_employee_advances advance
                WHERE advance.organisation_id=:organisationId AND advance.school_id=:schoolId
                  AND (CAST(:status AS text) IS NULL OR advance.status=:status)
                  AND (CAST(:staffId AS uuid) IS NULL OR advance.staff_profile_id=:staffId)
                  AND (:search='' OR strpos(lower(advance.staff_name_snapshot),:search)>0
                    OR strpos(lower(advance.employee_code_snapshot),:search)>0
                    OR strpos(lower(advance.advance_number),:search)>0)
                """, filters, Long.class);
        PayrollAccountingOptions options = accounting.payrollAccountingOptions(user, scope.schoolId());
        List<StaffOption> staff = people.payrollStaff(user, scope.schoolId(), "payroll.read").stream()
                .map(item -> new StaffOption(item.id(), item.employeeCode(), item.displayName(),
                        item.joinedOn(), item.endedOn(), item.status(), item.designation(), item.department()))
                .toList();
        List<AdvanceRow> rows = jdbc.query("""
                SELECT advance.*,creator_user.display_name creator_name,
                  submitter_user.display_name submitter_name,decider_user.display_name decider_name,
                  canceller_user.display_name canceller_name,
                  EXISTS (
                    SELECT 1 FROM payroll_employee_advance_history h
                    JOIN memberships contributor ON contributor.id=h.actor_membership_id
                      AND contributor.organisation_id=h.organisation_id AND contributor.school_id=h.school_id
                    WHERE h.employee_advance_id=advance.id AND h.organisation_id=advance.organisation_id
                      AND h.school_id=advance.school_id AND contributor.user_id=:userId
                      AND h.event_type IN ('CREATED','UPDATED','SUBMITTED')
                  ) actor_contributed
                FROM payroll_employee_advances advance
                JOIN memberships creator ON creator.id=advance.created_by_membership_id
                  AND creator.organisation_id=advance.organisation_id AND creator.school_id=advance.school_id
                JOIN user_accounts creator_user ON creator_user.id=creator.user_id
                LEFT JOIN memberships submitter ON submitter.id=advance.submitted_by_membership_id
                  AND submitter.organisation_id=advance.organisation_id AND submitter.school_id=advance.school_id
                LEFT JOIN user_accounts submitter_user ON submitter_user.id=submitter.user_id
                LEFT JOIN memberships decider ON decider.id=advance.decided_by_membership_id
                  AND decider.organisation_id=advance.organisation_id AND decider.school_id=advance.school_id
                LEFT JOIN user_accounts decider_user ON decider_user.id=decider.user_id
                LEFT JOIN memberships canceller ON canceller.id=advance.cancelled_by_membership_id
                  AND canceller.organisation_id=advance.organisation_id AND canceller.school_id=advance.school_id
                LEFT JOIN user_accounts canceller_user ON canceller_user.id=canceller.user_id
                WHERE advance.organisation_id=:organisationId AND advance.school_id=:schoolId
                  AND (CAST(:status AS text) IS NULL OR advance.status=:status)
                  AND (CAST(:staffId AS uuid) IS NULL OR advance.staff_profile_id=:staffId)
                  AND (:search='' OR strpos(lower(advance.staff_name_snapshot),:search)>0
                    OR strpos(lower(advance.employee_code_snapshot),:search)>0
                    OR strpos(lower(advance.advance_number),:search)>0)
                ORDER BY CASE advance.status WHEN 'SUBMITTED' THEN 0 WHEN 'DRAFT' THEN 1
                         WHEN 'APPROVED' THEN 2 ELSE 3 END,advance.issue_date DESC,advance.id
                LIMIT :size OFFSET :offset
                """, filters.addValue("userId", user.userId()), (result, row) -> advance(result));
        Map<UUID, List<InstallmentItem>> installments = installments(scope,
                rows.stream().map(AdvanceRow::id).toList());
        Map<UUID, List<HistoryItem>> history = history(scope, rows);
        Map<UUID, List<PostingRow>> postings = postings(scope, rows);
        List<UUID> journalIds = postings.values().stream().flatMap(List::stream)
                .map(PostingRow::journalId).filter(Objects::nonNull).toList();
        Map<UUID, String> journalStatuses = accounting.payrollAdvanceJournalStatuses(
                user, scope.schoolId(), journalIds);
        List<UUID> advanceIds = rows.stream().map(AdvanceRow::id).toList();
        Map<UUID, AccountingModuleBridge.PayrollAdvanceRecoveryEvidence> recoveryEvidence =
                accounting.payrollAdvanceRecoveryEvidence(user, scope.schoolId(), advanceIds);
        List<AdvanceItem> advances = rows.stream().map(row -> item(scope, row,
                installments.getOrDefault(row.id(), List.of()),
                history.getOrDefault(row.id(), List.of()),
                postings.getOrDefault(row.id(), List.of()), journalStatuses,
                recoveryEvidence.get(row.id()))).toList();
        return new PayrollAdvanceWorkspace(scope.schoolId(), scope.schoolName(),
                scope.allowed("prepare"), scope.allowed("approve"), query.page(), query.size(),
                Objects.requireNonNullElse(total, 0L), staff,
                options.financialYears().stream().map(year -> new FinancialYearOption(year.id(),
                        year.code(), year.displayName(), year.startsOn(), year.endsOn(),
                        year.accountingBasis(), year.currencyCode(), year.status())).toList(),
                options.accounts().stream().map(account -> new AccountOption(account.id(), account.code(),
                        account.displayName(), account.accountType(), account.category(), account.status())).toList(),
                advances);
    }

    private Map<UUID, List<InstallmentItem>> installments(Scope scope, List<UUID> advanceIds) {
        if (advanceIds.isEmpty()) return Map.of();
        return jdbc.query("""
                SELECT id,employee_advance_id,installment_number,due_month,planned_amount
                FROM payroll_employee_advance_installments
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND employee_advance_id IN (:ids)
                ORDER BY employee_advance_id,installment_number,id
                """, scope.params().addValue("ids", advanceIds), result -> {
                    Map<UUID, List<InstallmentItem>> grouped = new LinkedHashMap<>();
                    while (result.next()) grouped.computeIfAbsent(
                            result.getObject("employee_advance_id", UUID.class), ignored -> new ArrayList<>())
                            .add(new InstallmentItem(result.getObject("id", UUID.class),
                                    result.getInt("installment_number"),
                                    result.getObject("due_month", LocalDate.class),
                                    result.getBigDecimal("planned_amount")));
                    return grouped;
                });
    }

    private Map<UUID, List<HistoryItem>> history(Scope scope, List<AdvanceRow> advances) {
        if (advances.isEmpty()) return Map.of();
        return jdbc.query("""
                SELECT history.*,actor_user.display_name actor_name
                FROM payroll_employee_advance_history history
                JOIN memberships actor ON actor.id=history.actor_membership_id
                  AND actor.organisation_id=history.organisation_id AND actor.school_id=history.school_id
                JOIN user_accounts actor_user ON actor_user.id=actor.user_id
                WHERE history.organisation_id=:organisationId AND history.school_id=:schoolId
                  AND history.employee_advance_id IN (:ids)
                ORDER BY history.employee_advance_id,history.occurred_at,history.id
                """, scope.params().addValue("ids", advances.stream().map(AdvanceRow::id).toList()), result -> {
                    Map<UUID, List<HistoryItem>> grouped = new LinkedHashMap<>();
                    while (result.next()) grouped.computeIfAbsent(
                            result.getObject("employee_advance_id", UUID.class), ignored -> new ArrayList<>())
                            .add(new HistoryItem(result.getObject("id", UUID.class),
                                    result.getString("event_type"), result.getString("previous_status"),
                                    result.getString("new_status"), result.getString("reason"),
                                    result.getString("actor_name"), result.getTimestamp("occurred_at").toInstant()));
                    return grouped;
                });
    }

    private Map<UUID, List<PostingRow>> postings(Scope scope, List<AdvanceRow> advances) {
        if (advances.isEmpty()) return Map.of();
        return jdbc.query("""
                SELECT posting.*,preparer_user.display_name preparer_name
                FROM payroll_employee_advance_postings posting
                JOIN memberships preparer ON preparer.id=posting.prepared_by_membership_id
                  AND preparer.organisation_id=posting.organisation_id AND preparer.school_id=posting.school_id
                JOIN user_accounts preparer_user ON preparer_user.id=preparer.user_id
                WHERE posting.organisation_id=:organisationId AND posting.school_id=:schoolId
                  AND posting.employee_advance_id IN (:ids)
                ORDER BY posting.employee_advance_id,posting.sequence_number DESC,posting.id
                """, scope.params().addValue("ids", advances.stream().map(AdvanceRow::id).toList()), result -> {
                    Map<UUID, List<PostingRow>> grouped = new LinkedHashMap<>();
                    while (result.next()) {
                        PostingRow row = posting(result);
                        grouped.computeIfAbsent(row.advanceId(), ignored -> new ArrayList<>()).add(row);
                    }
                    return grouped;
                });
    }

    @Transactional
    public PayrollMutationResponse create(AuthenticatedUser user, String idempotencyKey,
            AdvanceRequest request) {
        Scope scope = scope(user, request.schoolId(), "prepare");
        Details details = details(request);
        String key = key(idempotencyKey);
        String fingerprint = hash(json(List.of("employee-advance-v1", request)));
        lock(scope);
        Retry existing = advanceRetry(scope, key);
        if (existing != null) return retry(existing, fingerprint);
        PayrollStaff staff = staff(user, scope, details.staffId(), details.issueDate(),
                "payroll.advance.prepare");
        PayrollBookContext book = validateDraftBook(user, scope, details);
        String number = nextNumber(scope, details.issueDate());
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        safe(() -> {
            setting("app.payroll_advance_write");
            jdbc.update("""
                    INSERT INTO payroll_employee_advances(id,organisation_id,school_id,staff_profile_id,
                      advance_number,advance_type,purpose,financial_year_id,accounting_period_id,
                      issue_date,currency_code,principal_amount,recovery_start_month,installment_count,
                      receivable_account_id,disbursement_account_id,payment_method,payment_reference,
                      employee_code_snapshot,staff_name_snapshot,status,idempotency_key,request_fingerprint,
                      creation_reason,created_by_membership_id,created_at,updated_at,row_version)
                    VALUES(:id,:organisationId,:schoolId,:staffId,:number,:type,:purpose,:yearId,:periodId,
                      :issueDate,:currency,:principal,:recoveryStart,:installments,:receivable,:disbursement,
                      :method,:reference,:employeeCode,:staffName,'DRAFT',:key,:fingerprint,:reason,
                      :actorId,:now,:now,0)
                    """, params(scope, id, details, book, staff).addValue("number", number)
                    .addValue("key", key).addValue("fingerprint", fingerprint).addValue("now", now));
            writeSchedule(scope, id, details, now, false);
            writeHistory(scope, id, "CREATED", null, "DRAFT", details.reason(), now);
            return 1;
        });
        audit(user, scope, "payroll.advance.created", "PAYROLL_EMPLOYEE_ADVANCE", id, details.reason());
        return new PayrollMutationResponse(id, 0);
    }

    @Transactional
    public PayrollMutationResponse update(AuthenticatedUser user, UUID id,
            UpdateAdvanceRequest request) {
        Scope scope = scope(user, request.advance().schoolId(), "prepare");
        Details details = details(request.advance());
        lock(scope);
        StoredAdvance stored = stored(scope, id, true);
        version(stored.version(), request.expectedVersion());
        if (!"DRAFT".equals(stored.status())) {
            throw conflict("PAYROLL_ADVANCE_NOT_EDITABLE", "Only a draft employee advance can be changed.");
        }
        PayrollStaff staff = staff(user, scope, details.staffId(), details.issueDate(),
                "payroll.advance.prepare");
        PayrollBookContext book = validateDraftBook(user, scope, details);
        Instant now = Instant.now(clock);
        safe(() -> {
            setting("app.payroll_advance_write");
            changed(jdbc.update("""
                    UPDATE payroll_employee_advances SET staff_profile_id=:staffId,advance_type=:type,
                      purpose=:purpose,financial_year_id=:yearId,accounting_period_id=:periodId,
                      issue_date=:issueDate,currency_code=:currency,principal_amount=:principal,
                      recovery_start_month=:recoveryStart,installment_count=:installments,
                      receivable_account_id=:receivable,disbursement_account_id=:disbursement,
                      payment_method=:method,payment_reference=:reference,
                      employee_code_snapshot=:employeeCode,staff_name_snapshot=:staffName,
                      creation_reason=:reason,updated_at=:now,row_version=row_version+1
                    WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                      AND status='DRAFT' AND row_version=:expected
                    """, params(scope, id, details, book, staff).addValue("now", now)
                    .addValue("expected", stored.version())));
            writeSchedule(scope, id, details, now, true);
            writeHistory(scope, id, "UPDATED", "DRAFT", "DRAFT", details.reason(), now);
            return 1;
        });
        audit(user, scope, "payroll.advance.updated", "PAYROLL_EMPLOYEE_ADVANCE", id, details.reason());
        return new PayrollMutationResponse(id, stored.version() + 1);
    }

    private void writeSchedule(Scope scope, UUID advanceId, Details details, Instant now,
            boolean replace) {
        setting("app.payroll_advance_schedule_write");
        if (replace) jdbc.update("""
                DELETE FROM payroll_employee_advance_installments
                WHERE employee_advance_id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                """, scope.params().addValue("id", advanceId));
        List<BigDecimal> amounts = schedule(details.principal(), details.installmentCount());
        for (int index = 0; index < amounts.size(); index++) {
            jdbc.update("""
                    INSERT INTO payroll_employee_advance_installments(id,organisation_id,school_id,
                      employee_advance_id,installment_number,due_month,planned_amount,created_at)
                    VALUES(:lineId,:organisationId,:schoolId,:id,:number,:dueMonth,:amount,:now)
                    """, scope.params().addValue("lineId", UUID.randomUUID()).addValue("id", advanceId)
                    .addValue("number", index + 1).addValue("dueMonth", details.recoveryStart().plusMonths(index))
                    .addValue("amount", amounts.get(index)).addValue("now", now));
        }
    }

    @Transactional
    public PayrollMutationResponse submit(AuthenticatedUser user, UUID id,
            VersionedReasonRequest request) {
        Scope scope = scope(user, request.schoolId(), "prepare");
        String why = reason(request.reason());
        lock(scope);
        StoredAdvance stored = stored(scope, id, true);
        version(stored.version(), request.expectedVersion());
        if (!"DRAFT".equals(stored.status())) {
            throw conflict("PAYROLL_ADVANCE_NOT_SUBMITTABLE", "Only a draft employee advance can be submitted.");
        }
        staff(user, scope, stored.staffId(), stored.issueDate(), "payroll.advance.prepare");
        validateStoredBook(user, scope, stored, false);
        validateComplete(scope, id);
        Instant now = Instant.now(clock);
        safe(() -> {
            setting("app.payroll_advance_transition");
            changed(jdbc.update("""
                    UPDATE payroll_employee_advances SET status='SUBMITTED',
                      submitted_by_membership_id=:actorId,submitted_at=:now,submission_reason=:reason,
                      updated_at=:now,row_version=row_version+1
                    WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                      AND status='DRAFT' AND row_version=:expected
                    """, scope.params().addValue("id", id).addValue("reason", why)
                    .addValue("now", now).addValue("expected", stored.version())));
            writeHistory(scope, id, "SUBMITTED", "DRAFT", "SUBMITTED", why, now);
            return 1;
        });
        audit(user, scope, "payroll.advance.submitted", "PAYROLL_EMPLOYEE_ADVANCE", id, why);
        return new PayrollMutationResponse(id, stored.version() + 1);
    }

    @Transactional
    public PayrollMutationResponse decide(AuthenticatedUser user, UUID id,
            DecisionRequest request) {
        Scope scope = scope(user, request.schoolId(), "approve");
        String decision = choice(request.decision(), Set.of("APPROVE", "REJECT"), "decision");
        String next = "APPROVE".equals(decision) ? "APPROVED" : "REJECTED";
        String why = reason(request.reason());
        lock(scope);
        StoredAdvance stored = stored(scope, id, true);
        version(stored.version(), request.expectedVersion());
        if (!"SUBMITTED".equals(stored.status())) {
            throw conflict("PAYROLL_ADVANCE_NOT_DECIDABLE", "Only a submitted employee advance can be decided.");
        }
        if (contributed(scope, id, user.userId())) {
            throw forbidden("PAYROLL_ADVANCE_SELF_APPROVAL_DENIED",
                    "A different authorized person must decide this employee advance.");
        }
        if ("APPROVED".equals(next)) {
            staff(user, scope, stored.staffId(), stored.issueDate(), "payroll.advance.approve");
            validateStoredBook(user, scope, stored, true);
            validateComplete(scope, id);
        }
        Instant now = Instant.now(clock);
        safe(() -> {
            setting("app.payroll_advance_transition");
            changed(jdbc.update("""
                    UPDATE payroll_employee_advances SET status=:next,
                      decided_by_membership_id=:actorId,decided_at=:now,decision_reason=:reason,
                      updated_at=:now,row_version=row_version+1
                    WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                      AND status='SUBMITTED' AND row_version=:expected
                    """, scope.params().addValue("id", id).addValue("next", next)
                    .addValue("reason", why).addValue("now", now).addValue("expected", stored.version())));
            writeHistory(scope, id, next, "SUBMITTED", next, why, now);
            return 1;
        });
        audit(user, scope, "payroll.advance." + next.toLowerCase(Locale.ROOT),
                "PAYROLL_EMPLOYEE_ADVANCE", id, why);
        return new PayrollMutationResponse(id, stored.version() + 1);
    }

    @Transactional
    public PayrollMutationResponse cancel(AuthenticatedUser user, UUID id,
            VersionedReasonRequest request) {
        Scope scope = scope(user, request.schoolId(), "prepare");
        String why = reason(request.reason());
        lock(scope);
        StoredAdvance stored = stored(scope, id, true);
        version(stored.version(), request.expectedVersion());
        if (!"DRAFT".equals(stored.status())) {
            throw conflict("PAYROLL_ADVANCE_NOT_CANCELLABLE", "Only a draft employee advance can be cancelled.");
        }
        Instant now = Instant.now(clock);
        safe(() -> {
            setting("app.payroll_advance_transition");
            changed(jdbc.update("""
                    UPDATE payroll_employee_advances SET status='CANCELLED',
                      cancelled_by_membership_id=:actorId,cancelled_at=:now,cancellation_reason=:reason,
                      updated_at=:now,row_version=row_version+1
                    WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                      AND status='DRAFT' AND row_version=:expected
                    """, scope.params().addValue("id", id).addValue("reason", why)
                    .addValue("now", now).addValue("expected", stored.version())));
            writeHistory(scope, id, "CANCELLED", "DRAFT", "CANCELLED", why, now);
            return 1;
        });
        audit(user, scope, "payroll.advance.cancelled", "PAYROLL_EMPLOYEE_ADVANCE", id, why);
        return new PayrollMutationResponse(id, stored.version() + 1);
    }

    @Transactional
    public PayrollMutationResponse prepareDisbursement(AuthenticatedUser user, UUID advanceId,
            String idempotencyKey, DisbursementRequest request) {
        Scope scope = scope(user, request.schoolId(), "prepare");
        String key = key(idempotencyKey);
        String why = reason(request.reason());
        String fingerprint = hash(json(List.of("employee-advance-disbursement-v1", advanceId, request)));
        lock(scope);
        PostingRetry retry = postingRetry(scope, key);
        if (retry != null) return postingRetry(retry, fingerprint);
        StoredAdvance advance = stored(scope, advanceId, true);
        if (!"APPROVED".equals(advance.status())) {
            throw conflict("PAYROLL_ADVANCE_NOT_APPROVED", "Approve the employee advance before disbursement.");
        }
        PayrollBookContext book = validateStoredBook(user, scope, advance, false);
        if (!book.financialYearId().equals(advance.yearId())
                || !book.accountingPeriodId().equals(advance.periodId())
                || !book.currencyCode().equals(advance.currency())) {
            throw conflict("PAYROLL_ADVANCE_BOOK_CHANGED",
                    "The open accounting book no longer matches the approved employee advance.");
        }
        releaseRejected(scope, user, advanceId);
        if (hasActivePosting(scope, advanceId)) {
            throw conflict("PAYROLL_ADVANCE_DISBURSEMENT_EXISTS",
                    "This employee advance already has an active or reversed disbursement journal.");
        }
        List<InstallmentItem> schedule = installments(scope, List.of(advanceId))
                .getOrDefault(advanceId, List.of());
        validateComplete(scope, advanceId);
        int sequence = nextPostingSequence(scope, advanceId);
        UUID postingId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        List<PayrollJournalLine> lines = List.of(
                new PayrollJournalLine(advance.receivableId(), advance.principal(), ZERO,
                        "Employee advance receivable"),
                new PayrollJournalLine(advance.disbursementId(), ZERO, advance.principal(),
                        "Employee advance disbursement by " + advance.paymentMethod()));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schemaVersion", 1);
        evidence.put("advance", advance.evidence());
        evidence.put("installments", schedule);
        evidence.put("accountingBook", book);
        evidence.put("journalLines", lines);
        String snapshot = json(evidence);
        safe(() -> {
            setting("app.payroll_advance_posting_prepare");
            jdbc.update("""
                    INSERT INTO payroll_employee_advance_postings(id,organisation_id,school_id,
                      employee_advance_id,financial_year_id,accounting_period_id,posting_date,
                      sequence_number,payment_method,payment_reference,idempotency_key,request_fingerprint,
                      source_amount,evidence_snapshot,evidence_sha256,status,preparation_reason,
                      prepared_by_membership_id,prepared_at,row_version)
                    VALUES(:id,:organisationId,:schoolId,:advanceId,:yearId,:periodId,:date,:sequence,
                      :method,:reference,:key,:fingerprint,:amount,CAST(:snapshot AS jsonb),:snapshotHash,
                      'ACTIVE',:reason,:actorId,:now,0)
                    """, scope.params().addValue("id", postingId).addValue("advanceId", advanceId)
                    .addValue("yearId", book.financialYearId()).addValue("periodId", book.accountingPeriodId())
                    .addValue("date", advance.issueDate()).addValue("sequence", sequence)
                    .addValue("method", advance.paymentMethod()).addValue("reference", advance.paymentReference())
                    .addValue("key", key).addValue("fingerprint", fingerprint)
                    .addValue("amount", advance.principal()).addValue("snapshot", snapshot)
                    .addValue("snapshotHash", hash(snapshot)).addValue("reason", why).addValue("now", now));
            return 1;
        });
        var journal = accounting.preparePayrollAdvanceJournal(user, new PayrollAdvanceJournalRequest(
                scope.schoolId(), book.financialYearId(), book.accountingPeriodId(), advance.issueDate(),
                "PAYMENT", advance.number() + "-DISB-" + sequence,
                "Employee " + advance.type().toLowerCase(Locale.ROOT) + " disbursement " + advance.number(),
                postingId, "payroll-advance-gl-" + postingId,
                "Prepared from immutable employee advance posting " + postingId, lines));
        setting("app.payroll_advance_journal_link");
        changed(safe(() -> jdbc.update("""
                UPDATE payroll_employee_advance_postings SET accounting_journal_id=:journalId,
                  journal_linked_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='ACTIVE' AND accounting_journal_id IS NULL AND row_version=0
                """, scope.params().addValue("id", postingId).addValue("journalId", journal.id())
                .addValue("now", now))));
        writeHistory(scope, advanceId, "DISBURSEMENT_PREPARED", "APPROVED", "APPROVED", why, now);
        audit(user, scope, "payroll.advance.disbursement.prepared",
                "PAYROLL_EMPLOYEE_ADVANCE_POSTING", postingId, why);
        return new PayrollMutationResponse(postingId, 1);
    }

    private AdvanceItem item(Scope scope, AdvanceRow row, List<InstallmentItem> installments,
            List<HistoryItem> history, List<PostingRow> postingRows, Map<UUID, String> statuses,
            AccountingModuleBridge.PayrollAdvanceRecoveryEvidence recovery) {
        List<PostingItem> postingItems = postingRows.stream().map(posting -> {
            String status = posting.journalId() == null ? null : statuses.get(posting.journalId());
            return new PostingItem(posting.id(), posting.sequence(), posting.date(), posting.method(),
                    posting.reference(), posting.amount(), posting.journalId(), status,
                    postingOperational(posting.status(), status), posting.preparerName(),
                    posting.preparedAt(), posting.version());
        }).toList();
        PostingRow active = postingRows.stream().filter(item -> "ACTIVE".equals(item.status()))
                .findFirst().orElse(null);
        String journalStatus = active == null || active.journalId() == null
                ? null : statuses.get(active.journalId());
        String operational = advanceOperational(row.status(), active, journalStatus);
        BigDecimal disbursed = "UNVERIFIED".equals(operational) ? null
                : "POSTED".equals(journalStatus) ? row.principal() : ZERO;
        BigDecimal recovered = recovery == null ? ZERO : money(recovery.recoveredAmount());
        BigDecimal outstanding = "POSTED".equals(journalStatus)
                ? row.principal().subtract(recovered).setScale(2) : null;
        String recoveryStatus = outstanding == null ? "NOT_DISBURSED"
                : outstanding.signum() == 0 ? "RECOVERED"
                : recovered.signum() > 0 ? "PARTIALLY_RECOVERED"
                : recovery != null && recovery.activeReservations() > 0 ? "RESERVED_IN_PAYROLL"
                : "NOT_STARTED";
        boolean blocked = active != null && !"REJECTED".equals(journalStatus);
        return new AdvanceItem(row.id(), row.staffId(), row.number(), row.type(), row.purpose(),
                row.yearId(), row.periodId(), row.issueDate(), row.currency(), row.principal(),
                row.recoveryStart(), row.installmentCount(), row.receivableId(), row.disbursementId(),
                row.paymentMethod(), row.paymentReference(), row.employeeCode(), row.staffName(),
                row.status(), row.creatorName(), row.createdAt(), row.submitterName(), row.submittedAt(),
                row.deciderName(), row.decidedAt(), row.decisionReason(), row.cancellerName(),
                row.cancelledAt(), row.cancellationReason(), disbursed, recovered, outstanding,
                recoveryStatus, operational, row.version(),
                scope.allowed("prepare") && "DRAFT".equals(row.status()),
                scope.allowed("prepare") && "DRAFT".equals(row.status()),
                scope.allowed("prepare") && "DRAFT".equals(row.status()),
                scope.allowed("approve") && "SUBMITTED".equals(row.status())
                        && !row.actorContributed(),
                scope.allowed("prepare") && "APPROVED".equals(row.status()) && !blocked,
                installments, history, postingItems);
    }

    private static String advanceOperational(String state, PostingRow posting, String journalStatus) {
        if (!"APPROVED".equals(state)) return state;
        if (posting == null) return "PENDING_DISBURSEMENT";
        return switch (Objects.requireNonNullElse(journalStatus, "UNVERIFIED")) {
            case "SUBMITTED" -> "AWAITING_ACCOUNTING_APPROVAL";
            case "POSTED" -> "ACTIVE";
            case "REJECTED" -> "DISBURSEMENT_REJECTED";
            case "REVERSED" -> "REVERSED";
            default -> "UNVERIFIED";
        };
    }

    private static String postingOperational(String sourceStatus, String journalStatus) {
        if ("RELEASED".equals(sourceStatus)) return "RELEASED";
        return switch (Objects.requireNonNullElse(journalStatus, "UNVERIFIED")) {
            case "SUBMITTED" -> "AWAITING_ACCOUNTING_APPROVAL";
            case "POSTED" -> "DISBURSED";
            default -> Objects.requireNonNullElse(journalStatus, "UNVERIFIED");
        };
    }

    private Scope scope(AuthenticatedUser user, UUID schoolId, String action) {
        if (user.activeOrganisationId() == null || user.membershipIds().isEmpty()) {
            throw forbidden("PAYROLL_ADVANCE_SCOPE_DENIED",
                    "Employee advance access is unavailable for this school.");
        }
        List<Scope> rows = jdbc.query("""
                SELECT school.id,school.display_name,membership.id membership_id,
                  coalesce(array_agg(DISTINCT capability.capability_code)
                    FILTER (WHERE capability.capability_code IS NOT NULL),ARRAY[]::text[]) capabilities
                FROM memberships membership
                JOIN schools school ON school.id=membership.school_id
                  AND school.organisation_id=membership.organisation_id
                LEFT JOIN membership_roles assignment ON assignment.membership_id=membership.id
                LEFT JOIN role_capabilities capability ON capability.role_id=assignment.role_id
                WHERE membership.user_id=:userId AND membership.organisation_id=:organisationId
                  AND membership.id IN (:membershipIds) AND membership.status='ACTIVE'
                  AND membership.valid_from<=CURRENT_TIMESTAMP
                  AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                  AND (CAST(:schoolId AS uuid) IS NULL OR membership.school_id=:schoolId)
                GROUP BY school.id,school.display_name,membership.id,membership.created_at
                ORDER BY membership.created_at,membership.id
                """, new MapSqlParameterSource("userId", user.userId())
                .addValue("organisationId", user.activeOrganisationId())
                .addValue("membershipIds", user.membershipIds()).addValue("schoolId", schoolId),
                (result, row) -> new Scope(user.activeOrganisationId(),
                        result.getObject("id", UUID.class), result.getString("display_name"),
                        result.getObject("membership_id", UUID.class),
                        Set.of((String[]) result.getArray("capabilities").getArray())));
        return rows.stream().filter(candidate -> candidate.has("payroll.read", "accounting.read"))
                .filter(candidate -> action == null || candidate.allowed(action)).findFirst()
                .orElseThrow(() -> forbidden("PAYROLL_ADVANCE_SCOPE_DENIED",
                        "Employee advance access is unavailable for this school or action."));
    }

    private static MapSqlParameterSource filters(Scope scope, Query query) {
        if (query == null || query.page() < 0 || query.page() > 100000
                || query.size() < 1 || query.size() > 100) {
            throw bad("PAYROLL_ADVANCE_QUERY_INVALID", "Choose a supported page and page size.");
        }
        String search = query.search() == null ? "" : query.search().strip();
        if (search.length() > 120 || search.chars().anyMatch(Character::isISOControl)) {
            throw bad("PAYROLL_ADVANCE_QUERY_INVALID", "Use a search of at most 120 characters.");
        }
        String status = query.status() == null || query.status().isBlank() ? null
                : choice(query.status(), Set.of("DRAFT","SUBMITTED","APPROVED","REJECTED","CANCELLED"), "status");
        return scope.params().addValue("status", status).addValue("staffId", query.staffProfileId())
                .addValue("search", search.toLowerCase(Locale.ROOT)).addValue("size", query.size())
                .addValue("offset", (long) query.page() * query.size());
    }

    private boolean contributed(Scope scope, UUID advanceId, UUID userId) {
        Boolean contributed = jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1 FROM payroll_employee_advance_history h
                  JOIN memberships contributor ON contributor.id=h.actor_membership_id
                    AND contributor.organisation_id=h.organisation_id AND contributor.school_id=h.school_id
                  WHERE h.employee_advance_id=:advanceId AND h.organisation_id=:organisationId
                    AND h.school_id=:schoolId AND contributor.user_id=:userId
                    AND h.event_type IN ('CREATED','UPDATED','SUBMITTED')
                )
                """, scope.params().addValue("advanceId", advanceId).addValue("userId", userId), Boolean.class);
        return Boolean.TRUE.equals(contributed);
    }

    private void lock(Scope scope) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))",
                scope.params().addValue("key", "accounting:school:" + scope.schoolId()), ignored -> null);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))",
                scope.params().addValue("key", "payroll-advance:" + scope.schoolId()), ignored -> null);
    }

    private PayrollStaff staff(AuthenticatedUser user, Scope scope, UUID staffId,
            LocalDate issueDate, String capability) {
        PayrollStaff staff = people.payrollStaff(user, scope.schoolId(), capability).stream()
                .filter(item -> item.id().equals(staffId)).findFirst()
                .orElseThrow(() -> bad("PAYROLL_ADVANCE_STAFF_INVALID",
                        "Choose an employee from this school."));
        if (staff.joinedOn().isAfter(issueDate)
                || (staff.endedOn() != null && staff.endedOn().isBefore(issueDate))) {
            throw bad("PAYROLL_ADVANCE_EMPLOYMENT_DATE_INVALID",
                    "The employee must be employed on the advance issue date.");
        }
        return staff;
    }

    private PayrollBookContext validateDraftBook(AuthenticatedUser user, Scope scope, Details details) {
        return accounting.validatePayrollAdvanceDraft(user, scope.schoolId(), details.yearId(),
                details.issueDate(), accountUses(details.receivableId(), details.disbursementId(),
                        details.paymentMethod()));
    }

    private PayrollBookContext validateStoredBook(AuthenticatedUser user, Scope scope,
            StoredAdvance advance, boolean decision) {
        List<PayrollAccountUse> uses = accountUses(advance.receivableId(), advance.disbursementId(),
                advance.paymentMethod());
        return decision
                ? accounting.validatePayrollAdvanceDecision(user, scope.schoolId(), advance.yearId(),
                        advance.issueDate(), uses)
                : accounting.validatePayrollAdvanceDraft(user, scope.schoolId(), advance.yearId(),
                        advance.issueDate(), uses);
    }

    private static List<PayrollAccountUse> accountUses(UUID receivable, UUID payment, String method) {
        return List.of(new PayrollAccountUse(receivable, PayrollAccountUseType.RECEIVABLE),
                new PayrollAccountUse(payment, "CASH".equals(method)
                        ? PayrollAccountUseType.CASH : PayrollAccountUseType.BANK));
    }

    private Details details(AdvanceRequest request) {
        String type = choice(request.advanceType(), TYPES, "advance type");
        String method = choice(request.paymentMethod(), METHODS, "payment method");
        String reference = optional(request.paymentReference(), 120, "payment reference");
        if (!"CASH".equals(method) && reference == null) {
            throw bad("PAYROLL_ADVANCE_REFERENCE_REQUIRED",
                    "Enter a payment reference for a non-cash disbursement.");
        }
        if (request.issueDate() == null || request.issueDate().isAfter(LocalDate.now(clock))) {
            throw bad("PAYROLL_ADVANCE_ISSUE_DATE_INVALID",
                    "Choose an employee advance issue date that is not in the future.");
        }
        LocalDate recoveryStart = request.recoveryStartMonth();
        if (recoveryStart == null || recoveryStart.getDayOfMonth() != 1
                || recoveryStart.isBefore(YearMonth.from(request.issueDate()).atDay(1))) {
            throw bad("PAYROLL_ADVANCE_RECOVERY_MONTH_INVALID",
                    "Choose the first day of the issue month or a later month for recovery.");
        }
        int count = request.installmentCount();
        if (count < 1 || count > 120) {
            throw bad("PAYROLL_ADVANCE_INSTALLMENT_COUNT_INVALID",
                    "Choose between 1 and 120 monthly installments.");
        }
        BigDecimal principal = money(request.principalAmount());
        if (principal.signum() <= 0 || principal.compareTo(MAX_AMOUNT) > 0
                || principal.compareTo(new BigDecimal(count).movePointLeft(2)) < 0) {
            throw bad("PAYROLL_ADVANCE_AMOUNT_INVALID",
                    "Enter a supported amount that can be divided into positive installments.");
        }
        if (request.schoolId() == null || request.staffProfileId() == null
                || request.financialYearId() == null || request.receivableAccountId() == null
                || request.disbursementAccountId() == null
                || request.receivableAccountId().equals(request.disbursementAccountId())) {
            throw bad("PAYROLL_ADVANCE_INPUT_INVALID",
                    "Choose the school, employee, accounting year, and distinct posting accounts.");
        }
        return new Details(request.staffProfileId(), type, text(request.purpose(), 3, 500, "purpose"),
                request.financialYearId(), request.issueDate(), principal, recoveryStart, count,
                request.receivableAccountId(), request.disbursementAccountId(), method, reference,
                reason(request.reason()));
    }

    private MapSqlParameterSource params(Scope scope, UUID id, Details details,
            PayrollBookContext book, PayrollStaff staff) {
        return scope.params().addValue("id", id).addValue("staffId", details.staffId())
                .addValue("type", details.type()).addValue("purpose", details.purpose())
                .addValue("yearId", book.financialYearId()).addValue("periodId", book.accountingPeriodId())
                .addValue("issueDate", details.issueDate()).addValue("currency", book.currencyCode())
                .addValue("principal", details.principal()).addValue("recoveryStart", details.recoveryStart())
                .addValue("installments", details.installmentCount()).addValue("receivable", details.receivableId())
                .addValue("disbursement", details.disbursementId()).addValue("method", details.paymentMethod())
                .addValue("reference", details.paymentReference()).addValue("employeeCode", staff.employeeCode())
                .addValue("staffName", staff.displayName()).addValue("reason", details.reason());
    }

    private String nextNumber(Scope scope, LocalDate issueDate) {
        String month = String.format(Locale.ROOT, "%04d%02d", issueDate.getYear(), issueDate.getMonthValue());
        Integer previous = jdbc.queryForObject("""
                SELECT coalesce(max(substring(advance_number from 12 for 3)::integer),0)
                FROM payroll_employee_advances
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND substring(advance_number from 5 for 6)=:month
                """, scope.params().addValue("month", month), Integer.class);
        int sequence = Objects.requireNonNullElse(previous, 0) + 1;
        if (sequence > 999) {
            throw conflict("PAYROLL_ADVANCE_SEQUENCE_EXHAUSTED",
                    "The monthly employee advance number sequence is exhausted.");
        }
        return "EPA-" + month + "-" + String.format(Locale.ROOT, "%03d", sequence);
    }

    private int nextPostingSequence(Scope scope, UUID advanceId) {
        Integer previous = jdbc.queryForObject("""
                SELECT coalesce(max(sequence_number),0) FROM payroll_employee_advance_postings
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND employee_advance_id=:advanceId
                """, scope.params().addValue("advanceId", advanceId), Integer.class);
        int sequence = Objects.requireNonNullElse(previous, 0) + 1;
        if (sequence > 100) {
            throw conflict("PAYROLL_ADVANCE_POSTING_SEQUENCE_EXHAUSTED",
                    "The protected employee advance posting sequence is exhausted.");
        }
        return sequence;
    }

    static List<BigDecimal> schedule(BigDecimal principal, int count) {
        principal = money(principal);
        if (count < 1 || count > 120 || principal.signum() <= 0 || principal.compareTo(MAX_AMOUNT) > 0
                || principal.compareTo(BigDecimal.valueOf(count, 2)) < 0) {
            throw bad("PAYROLL_ADVANCE_SCHEDULE_INVALID", "Choose a supported amount and number of installments.");
        }
        BigDecimal regular = principal.divide(BigDecimal.valueOf(count), 2, RoundingMode.DOWN);
        List<BigDecimal> amounts = new ArrayList<>(count);
        for (int index = 0; index < count - 1; index++) amounts.add(regular);
        amounts.add(principal.subtract(regular.multiply(BigDecimal.valueOf(count - 1))).setScale(2));
        return List.copyOf(amounts);
    }

    private StoredAdvance stored(Scope scope, UUID id, boolean lock) {
        List<StoredAdvance> rows = jdbc.query("""
                SELECT * FROM payroll_employee_advances
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                """ + (lock ? " FOR UPDATE" : ""), scope.params().addValue("id", id),
                (result, row) -> stored(result));
        if (rows.isEmpty()) throw notFound("PAYROLL_ADVANCE_NOT_FOUND", "The employee advance was not found.");
        return rows.getFirst();
    }

    private Retry advanceRetry(Scope scope, String key) {
        List<Retry> rows = jdbc.query("""
                SELECT id,request_fingerprint,row_version FROM payroll_employee_advances
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND idempotency_key=:key
                """, scope.params().addValue("key", key), (result, row) -> new Retry(
                result.getObject("id", UUID.class), result.getString("request_fingerprint"),
                result.getLong("row_version")));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private PostingRetry postingRetry(Scope scope, String key) {
        List<PostingRetry> rows = jdbc.query("""
                SELECT id,request_fingerprint,row_version FROM payroll_employee_advance_postings
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND idempotency_key=:key
                """, scope.params().addValue("key", key), (result, row) -> new PostingRetry(
                result.getObject("id", UUID.class), result.getString("request_fingerprint"),
                result.getLong("row_version")));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static PayrollMutationResponse retry(Retry retry, String fingerprint) {
        if (!retry.fingerprint().equals(fingerprint)) {
            throw conflict("IDEMPOTENCY_KEY_REUSED",
                    "This retry key was already used for different employee advance evidence.");
        }
        return new PayrollMutationResponse(retry.id(), retry.version());
    }

    private static PayrollMutationResponse postingRetry(PostingRetry retry, String fingerprint) {
        if (!retry.fingerprint().equals(fingerprint)) {
            throw conflict("IDEMPOTENCY_KEY_REUSED",
                    "This retry key was already used for different employee advance posting evidence.");
        }
        return new PayrollMutationResponse(retry.id(), retry.version());
    }

    private void releaseRejected(Scope scope, AuthenticatedUser user, UUID advanceId) {
        List<PostingRow> active = jdbc.query("""
                SELECT posting.*,preparer_user.display_name preparer_name
                FROM payroll_employee_advance_postings posting
                JOIN memberships preparer ON preparer.id=posting.prepared_by_membership_id
                  AND preparer.organisation_id=posting.organisation_id AND preparer.school_id=posting.school_id
                JOIN user_accounts preparer_user ON preparer_user.id=preparer.user_id
                WHERE posting.organisation_id=:organisationId AND posting.school_id=:schoolId
                  AND posting.employee_advance_id=:advanceId AND posting.status='ACTIVE'
                ORDER BY posting.sequence_number DESC
                """, scope.params().addValue("advanceId", advanceId), (result, row) -> posting(result));
        Map<UUID, String> statuses = accounting.payrollAdvanceJournalStatuses(user, scope.schoolId(),
                active.stream().map(PostingRow::journalId).filter(Objects::nonNull).toList());
        List<UUID> rejected = active.stream().filter(item -> item.journalId() != null)
                .filter(item -> "REJECTED".equals(statuses.get(item.journalId())))
                .map(PostingRow::id).toList();
        if (rejected.isEmpty()) return;
        setting("app.payroll_advance_posting_release");
        int updated = safe(() -> jdbc.update("""
                UPDATE payroll_employee_advance_postings SET status='RELEASED',released_at=:now,
                  row_version=row_version+1
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND employee_advance_id=:advanceId AND status='ACTIVE' AND id IN (:ids)
                """, scope.params().addValue("advanceId", advanceId).addValue("ids", rejected)
                .addValue("now", Instant.now(clock))));
        if (updated != rejected.size()) {
            throw conflict("PAYROLL_ADVANCE_POSTING_CHANGED",
                    "The employee advance posting changed. Refresh and try again.");
        }
    }

    private boolean hasActivePosting(Scope scope, UUID advanceId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM payroll_employee_advance_postings
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND employee_advance_id=:advanceId AND status='ACTIVE'
                """, scope.params().addValue("advanceId", advanceId), Integer.class);
        return count != null && count > 0;
    }

    private void validateComplete(Scope scope, UUID advanceId) {
        safe(() -> {
            jdbc.query("SELECT validate_complete_payroll_employee_advance(:id,:organisationId,:schoolId)",
                    scope.params().addValue("id", advanceId), ignored -> null);
            return 1;
        });
    }

    private void writeHistory(Scope scope, UUID advanceId, String event, String previous,
            String next, String why, Instant now) {
        setting("app.payroll_advance_history_write");
        jdbc.update("""
                INSERT INTO payroll_employee_advance_history(id,organisation_id,school_id,
                  employee_advance_id,event_type,previous_status,new_status,reason,
                  actor_membership_id,occurred_at,evidence_snapshot)
                SELECT :historyId,:organisationId,:schoolId,:advanceId,:event,:previous,:next,
                  :reason,:actorId,:now,to_jsonb(advance)
                FROM payroll_employee_advances advance
                WHERE advance.id=:advanceId AND advance.organisation_id=:organisationId
                  AND advance.school_id=:schoolId
                """, scope.params().addValue("historyId", UUID.randomUUID())
                .addValue("advanceId", advanceId).addValue("event", event)
                .addValue("previous", previous).addValue("next", next)
                .addValue("reason", why).addValue("now", now));
    }

    private void audit(AuthenticatedUser user, Scope scope, String action,
            String entityType, UUID id, String why) {
        audit.recordScoped(user, scope.actorId(), scope.schoolId(), action, entityType,
                id.toString(), AuditOutcome.SUCCEEDED, why, Map.of());
    }

    private void setting(String name) {
        jdbc.queryForObject("SELECT set_config(:name,'approved',true)",
                new MapSqlParameterSource("name", name), String.class);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw problem(HttpStatus.INTERNAL_SERVER_ERROR, "PAYROLL_ADVANCE_EVIDENCE_FAILED",
                    "The immutable employee advance evidence could not be prepared.");
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static AdvanceRow advance(ResultSet result) throws SQLException {
        return new AdvanceRow(result.getObject("id", UUID.class),
                result.getObject("staff_profile_id", UUID.class), result.getString("advance_number"),
                result.getString("advance_type"), result.getString("purpose"),
                result.getObject("financial_year_id", UUID.class),
                result.getObject("accounting_period_id", UUID.class),
                result.getObject("issue_date", LocalDate.class), result.getString("currency_code"),
                result.getBigDecimal("principal_amount"),
                result.getObject("recovery_start_month", LocalDate.class),
                result.getInt("installment_count"),
                result.getObject("receivable_account_id", UUID.class),
                result.getObject("disbursement_account_id", UUID.class),
                result.getString("payment_method"), result.getString("payment_reference"),
                result.getString("employee_code_snapshot"), result.getString("staff_name_snapshot"),
                result.getString("status"), result.getObject("created_by_membership_id", UUID.class),
                result.getString("creator_name"), result.getTimestamp("created_at").toInstant(),
                result.getObject("submitted_by_membership_id", UUID.class), result.getString("submitter_name"),
                instant(result.getTimestamp("submitted_at")), result.getString("decider_name"),
                instant(result.getTimestamp("decided_at")), result.getString("decision_reason"),
                result.getString("canceller_name"), instant(result.getTimestamp("cancelled_at")),
                result.getString("cancellation_reason"), result.getLong("row_version"),
                result.getBoolean("actor_contributed"));
    }

    private static StoredAdvance stored(ResultSet result) throws SQLException {
        return new StoredAdvance(result.getObject("id", UUID.class),
                result.getObject("staff_profile_id", UUID.class), result.getString("advance_number"),
                result.getString("advance_type"), result.getString("purpose"),
                result.getObject("financial_year_id", UUID.class),
                result.getObject("accounting_period_id", UUID.class),
                result.getObject("issue_date", LocalDate.class), result.getString("currency_code"),
                result.getBigDecimal("principal_amount"),
                result.getObject("recovery_start_month", LocalDate.class), result.getInt("installment_count"),
                result.getObject("receivable_account_id", UUID.class),
                result.getObject("disbursement_account_id", UUID.class),
                result.getString("payment_method"), result.getString("payment_reference"),
                result.getString("employee_code_snapshot"), result.getString("staff_name_snapshot"),
                result.getString("status"), result.getObject("created_by_membership_id", UUID.class),
                result.getObject("submitted_by_membership_id", UUID.class), result.getLong("row_version"));
    }

    private static PostingRow posting(ResultSet result) throws SQLException {
        return new PostingRow(result.getObject("id", UUID.class),
                result.getObject("employee_advance_id", UUID.class), result.getInt("sequence_number"),
                result.getObject("posting_date", LocalDate.class), result.getString("payment_method"),
                result.getString("payment_reference"), result.getBigDecimal("source_amount"),
                result.getObject("accounting_journal_id", UUID.class), result.getString("status"),
                result.getString("preparer_name"), result.getTimestamp("prepared_at").toInstant(),
                result.getLong("row_version"));
    }

    private static BigDecimal money(BigDecimal value) {
        if (value == null || value.stripTrailingZeros().scale() > 2) {
            throw bad("PAYROLL_ADVANCE_AMOUNT_INVALID", "Use an amount with at most two decimals.");
        }
        return value.setScale(2);
    }

    private static String reason(String value) {
        return text(value, 3, 500, "reason");
    }

    private static String key(String value) {
        return text(value, 8, 100, "retry key");
    }

    private static String optional(String value, int maximum, String label) {
        if (value == null || value.isBlank()) return null;
        return text(value, 2, maximum, label);
    }

    private static String choice(String value, Set<String> allowed, String label) {
        String normalized = text(value, 1, 40, label).toUpperCase(Locale.ROOT).replace(' ', '_');
        if (!allowed.contains(normalized)) {
            throw bad("PAYROLL_ADVANCE_INPUT_INVALID", "Choose a supported " + label + ".");
        }
        return normalized;
    }

    private static String text(String value, int minimum, int maximum, String label) {
        String clean = value == null ? "" : value.strip();
        if (clean.length() < minimum || clean.length() > maximum
                || clean.chars().anyMatch(Character::isISOControl)) {
            throw bad("PAYROLL_ADVANCE_INPUT_INVALID", "Enter a valid " + label + ".");
        }
        return clean;
    }

    private static void version(long actual, Long expected) {
        if (expected == null || actual != expected) changed(0);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static int safe(java.util.function.IntSupplier work) {
        try {
            return work.getAsInt();
        } catch (DataAccessException exception) {
            if (!DatabaseConflict.isIntegrityRejection(exception)) throw exception;
            throw conflict("PAYROLL_ADVANCE_CONFLICT",
                    "The operation conflicts with protected employee advance history.");
        }
    }

    private static void changed(int count) {
        if (count != 1) throw conflict("STALE_VERSION", "The record changed. Refresh and try again.");
    }

    private static ApiException bad(String code, String message) {
        return problem(HttpStatus.BAD_REQUEST, code, message);
    }

    private static ApiException forbidden(String code, String message) {
        return problem(HttpStatus.FORBIDDEN, code, message);
    }

    private static ApiException notFound(String code, String message) {
        return problem(HttpStatus.NOT_FOUND, code, message);
    }

    private static ApiException conflict(String code, String message) {
        return problem(HttpStatus.CONFLICT, code, message);
    }

    private static ApiException problem(HttpStatus status, String code, String message) {
        return new ApiException(status, code, message);
    }

    private record Scope(UUID organisationId, UUID schoolId, String schoolName,
            UUID actorId, Set<String> capabilities) {
        MapSqlParameterSource params() {
            return new MapSqlParameterSource("organisationId", organisationId)
                    .addValue("schoolId", schoolId).addValue("actorId", actorId);
        }

        boolean has(String... required) {
            return capabilities.containsAll(List.of(required));
        }

        boolean allowed(String action) {
            return switch (action) {
                case "prepare" -> has("payroll.advance.prepare", "accounting.prepare");
                case "approve" -> has("payroll.advance.approve", "accounting.post");
                default -> false;
            };
        }
    }

    private record Details(UUID staffId, String type, String purpose, UUID yearId,
            LocalDate issueDate, BigDecimal principal, LocalDate recoveryStart,
            int installmentCount, UUID receivableId, UUID disbursementId,
            String paymentMethod, String paymentReference, String reason) {}

    private record AdvanceRow(UUID id, UUID staffId, String number, String type,
            String purpose, UUID yearId, UUID periodId, LocalDate issueDate,
            String currency, BigDecimal principal, LocalDate recoveryStart,
            int installmentCount, UUID receivableId, UUID disbursementId,
            String paymentMethod, String paymentReference, String employeeCode,
            String staffName, String status, UUID creatorId, String creatorName,
            Instant createdAt, UUID submitterId, String submitterName, Instant submittedAt,
            String deciderName, Instant decidedAt, String decisionReason,
            String cancellerName, Instant cancelledAt, String cancellationReason, long version,
            boolean actorContributed) {}

    private record StoredAdvance(UUID id, UUID staffId, String number, String type,
            String purpose, UUID yearId, UUID periodId, LocalDate issueDate,
            String currency, BigDecimal principal, LocalDate recoveryStart,
            int installmentCount, UUID receivableId, UUID disbursementId,
            String paymentMethod, String paymentReference, String employeeCode,
            String staffName, String status, UUID creatorId, UUID submitterId, long version) {
        AdvanceEvidence evidence() {
            return new AdvanceEvidence(id, staffId, number, type, purpose, yearId, periodId,
                    issueDate, currency, principal, recoveryStart, installmentCount,
                    receivableId, disbursementId, paymentMethod, paymentReference,
                    employeeCode, staffName, status, creatorId, submitterId, version);
        }
    }

    private record AdvanceEvidence(UUID id, UUID staffId, String number, String type,
            String purpose, UUID yearId, UUID periodId, LocalDate issueDate,
            String currency, BigDecimal principal, LocalDate recoveryStart,
            int installmentCount, UUID receivableId, UUID disbursementId,
            String paymentMethod, String paymentReference, String employeeCode,
            String staffName, String status, UUID creatorId, UUID submitterId, long version) {}

    private record PostingRow(UUID id, UUID advanceId, int sequence, LocalDate date,
            String method, String reference, BigDecimal amount, UUID journalId,
            String status, String preparerName, Instant preparedAt, long version) {}

    private record Retry(UUID id, String fingerprint, long version) {}
    private record PostingRetry(UUID id, String fingerprint, long version) {}
}
