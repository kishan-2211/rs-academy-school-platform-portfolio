package com.educore.api.payroll;

import static com.educore.api.accounting.AccountingModuleBridge.*;
import static com.educore.api.payroll.PayrollAccountingController.*;
import static com.educore.api.payroll.PayrollAccountingWorkspace.*;

import com.educore.api.accounting.AccountingModuleBridge;
import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import com.educore.api.common.api.DatabaseConflict;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class PayrollAccountingService {
    private static final Set<String> METHODS = Set.of("CASH", "BANK_TRANSFER", "CHEQUE", "SCHOOL_UPI");
    private static final Set<String> RELEASED = Set.of("REJECTED", "REVERSED");
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private final NamedParameterJdbcTemplate jdbc;
    private final AccountingModuleBridge accounting;
    private final PayrollRecoveryService recovery;
    private final SecurityAuditService audit;
    private final ObjectMapper mapper;
    private final Clock clock;

    public PayrollAccountingService(NamedParameterJdbcTemplate jdbc, AccountingModuleBridge accounting,
            PayrollRecoveryService recovery, SecurityAuditService audit, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.accounting = accounting;
        this.recovery = recovery;
        this.audit = audit;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional
    public PayrollMutationResponse createConfiguration(AuthenticatedUser u, ConfigurationRequest q) {
        Scope s = scope(u, q.schoolId(), "configure");
        lock(s);
        String why = reason(q.reason());
        Mappings mappings = validateMappings(s, q);
        PayrollBookContext book = accounting.validatePayrollConfigurationDraft(u, s.schoolId(),
                q.financialYearId(), q.effectiveFrom(), uses(q.salaryPayableAccountId(), mappings));
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        safe(() -> jdbc.update("""
                INSERT INTO payroll_accounting_configurations(id,organisation_id,school_id,
                  financial_year_id,supersedes_configuration_id,revision_number,effective_from,
                  accounting_basis_snapshot,currency_code,salary_payable_account_id,status,
                  creation_reason,created_by_membership_id,created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:yearId,:supersedesId,:revision,:effectiveFrom,
                  'ACCRUAL',:currency,:salaryPayableId,'DRAFT',:reason,:actorId,:now,:now,0)
                """, s.params().addValue("id", id).addValue("yearId", q.financialYearId())
                .addValue("supersedesId", q.supersedesConfigurationId()).addValue("revision", q.revisionNumber())
                .addValue("effectiveFrom", q.effectiveFrom()).addValue("currency", book.currencyCode())
                .addValue("salaryPayableId", q.salaryPayableAccountId()).addValue("reason", why).addValue("now", now)));
        insertMappings(s, id, mappings, now);
        audit(u, s, "payroll.accounting-configuration.created", "PAYROLL_ACCOUNTING_CONFIGURATION", id, why);
        return new PayrollMutationResponse(id, 0);
    }

    @Transactional
    public PayrollMutationResponse updateConfiguration(AuthenticatedUser u, UUID id, UpdateConfigurationRequest request) {
        ConfigurationRequest q = request.configuration();
        Scope s = scope(u, q.schoolId(), "configure");
        lock(s);
        Configuration c = configuration(s, id, true);
        version(c.version(), request.expectedVersion());
        if (!"DRAFT".equals(c.status()) || !s.actorId().equals(c.creatorId())) {
            throw conflict("PAYROLL_MAPPING_NOT_EDITABLE", "Only the creator can edit this draft mapping.");
        }
        Mappings mappings = validateMappings(s, q);
        accounting.validatePayrollConfigurationDraft(u, s.schoolId(), q.financialYearId(),
                q.effectiveFrom(), uses(q.salaryPayableAccountId(), mappings));
        String why = reason(q.reason());
        Instant now = Instant.now(clock);
        changed(safe(() -> jdbc.update("""
                UPDATE payroll_accounting_configurations SET effective_from=:date,
                  salary_payable_account_id=:payableId,supersedes_configuration_id=:supersedes,
                  updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND financial_year_id=:yearId AND revision_number=:revision
                  AND status='DRAFT' AND row_version=:version
                """, s.params().addValue("id", id).addValue("yearId", q.financialYearId())
                .addValue("revision", q.revisionNumber()).addValue("date", q.effectiveFrom())
                .addValue("payableId", q.salaryPayableAccountId()).addValue("supersedes", q.supersedesConfigurationId())
                .addValue("now", now).addValue("version", c.version()))));
        // Only unpublished, creator-owned draft children are replaceable. Approved evidence is immutable.
        safe(() -> jdbc.update("""
                DELETE FROM payroll_accounting_component_mappings WHERE configuration_id=:id
                  AND organisation_id=:organisationId AND school_id=:schoolId
                """, s.params().addValue("id", id)));
        safe(() -> jdbc.update("""
                DELETE FROM payroll_accounting_tender_mappings WHERE configuration_id=:id
                  AND organisation_id=:organisationId AND school_id=:schoolId
                """, s.params().addValue("id", id)));
        insertMappings(s, id, mappings, now);
        audit(u, s, "payroll.accounting-configuration.updated", "PAYROLL_ACCOUNTING_CONFIGURATION", id, why);
        return new PayrollMutationResponse(id, c.version() + 1);
    }

    @Transactional
    public PayrollMutationResponse submitConfiguration(AuthenticatedUser u, UUID id, VersionedReasonRequest q) {
        Scope s = scope(u, q.schoolId(), "configure");
        lock(s);
        Configuration c = configuration(s, id, true);
        version(c.version(), q.expectedVersion());
        if (!"DRAFT".equals(c.status()) || !s.actorId().equals(c.creatorId())) {
            throw conflict("PAYROLL_MAPPING_NOT_SUBMITTABLE", "Only the creator can submit this draft mapping.");
        }
        accounting.validatePayrollConfigurationDraft(u, s.schoolId(), c.yearId(), c.effectiveFrom(),
                uses(c.salaryPayableId(), mappings(s, id)));
        String why = reason(q.reason());
        changed(safe(() -> jdbc.update("""
                UPDATE payroll_accounting_configurations SET status='SUBMITTED',
                  submitted_by_membership_id=:actorId,submitted_at=:now,submission_reason=:reason,
                  updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='DRAFT' AND row_version=:version
                """, s.params().addValue("id", id).addValue("version", c.version())
                .addValue("reason", why).addValue("now", Instant.now(clock)))));
        audit(u, s, "payroll.accounting-configuration.submitted", "PAYROLL_ACCOUNTING_CONFIGURATION", id, why);
        return new PayrollMutationResponse(id, c.version() + 1);
    }

    @Transactional
    public PayrollMutationResponse decideConfiguration(AuthenticatedUser u, UUID id,
            ConfigurationDecisionRequest q) {
        Scope s = scope(u, q.schoolId(), "approve");
        lock(s);
        Configuration c = configuration(s, id, true);
        version(c.version(), q.expectedVersion());
        if (!"SUBMITTED".equals(c.status())) throw conflict("PAYROLL_MAPPING_NOT_DECIDABLE",
                "Only submitted payroll mappings can receive a decision.");
        if (s.actorId().equals(c.creatorId())) throw problem(HttpStatus.FORBIDDEN,
                "PAYROLL_MAPPING_SELF_APPROVAL_DENIED", "A different authorized person must decide this mapping.");
        String target = "APPROVE".equals(choice(q.decision(), Set.of("APPROVE", "REJECT"),
                "mapping decision")) ? "APPROVED" : "REJECTED";
        String why = reason(q.reason());
        Instant now = Instant.now(clock);
        if ("APPROVED".equals(target)) {
            accounting.validatePayrollConfigurationDecision(u, s.schoolId(), c.yearId(), c.effectiveFrom(),
                    uses(c.salaryPayableId(), mappings(s, id)));
            if (c.supersedesId() != null) changed(safe(() -> jdbc.update("""
                    UPDATE payroll_accounting_configurations SET status='SUPERSEDED',
                      superseded_by_membership_id=:actorId,superseded_at=:now,supersede_reason=:reason,
                      updated_at=:now,row_version=row_version+1
                    WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                      AND financial_year_id=:yearId AND status='APPROVED'
                    """, s.params().addValue("id", c.supersedesId()).addValue("yearId", c.yearId())
                    .addValue("now", now).addValue("reason", why))));
        }
        changed(safe(() -> jdbc.update("""
                UPDATE payroll_accounting_configurations SET status=:status,
                  decided_by_membership_id=:actorId,decided_at=:now,decision_reason=:reason,
                  updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='SUBMITTED' AND row_version=:version
                """, s.params().addValue("id", id).addValue("version", c.version()).addValue("status", target)
                .addValue("reason", why).addValue("now", now))));
        audit(u, s, "payroll.accounting-configuration." + target.toLowerCase(Locale.ROOT),
                "PAYROLL_ACCOUNTING_CONFIGURATION", id, why);
        return new PayrollMutationResponse(id, c.version() + 1);
    }

    private Mappings validateMappings(Scope s, ConfigurationRequest q) {
        Map<UUID, String> active = jdbc.query("""
                SELECT id,component_kind FROM payroll_components
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND status='ACTIVE'
                """, s.params(), r -> {
                    Map<UUID, String> result = new LinkedHashMap<>();
                    while (r.next()) result.put(r.getObject("id", UUID.class), r.getString("component_kind"));
                    return result;
                });
        Set<UUID> ids = q.componentMappings().stream().map(ComponentMappingRequest::payrollComponentId)
                .collect(Collectors.toSet());
        if (active.isEmpty() || ids.size() != q.componentMappings().size() || !ids.equals(active.keySet())) {
            throw bad("PAYROLL_MAPPING_INCOMPLETE", "Map every active payroll component exactly once.");
        }
        List<ComponentMap> components = new ArrayList<>();
        for (ComponentMappingRequest m : q.componentMappings()) {
            String kind = active.get(m.payrollComponentId());
            if (!"DEDUCTION".equals(kind) != (m.expenseAccountId() != null)
                    || !"EARNING".equals(kind) != (m.payableAccountId() != null)) {
                throw bad("PAYROLL_MAPPING_ACCOUNT_INVALID",
                        "Earnings require expense, deductions require payable, and employer contributions require both.");
            }
            components.add(new ComponentMap(m.payrollComponentId(), kind, m.expenseAccountId(), m.payableAccountId()));
        }
        Map<String, UUID> tenders = new LinkedHashMap<>();
        for (TenderMappingRequest tender : q.tenderMappings()) {
            String method = choice(tender.paymentMethod(), METHODS, "payment method");
            if (tenders.put(method, tender.paymentAccountId()) != null) {
                throw bad("PAYROLL_MAPPING_DUPLICATE", "Map each payment method only once.");
            }
        }
        if (tenders.isEmpty()) throw bad("PAYROLL_MAPPING_INCOMPLETE", "Map at least one payment method.");
        return new Mappings(List.copyOf(components), Map.copyOf(tenders));
    }

    private void insertMappings(Scope s, UUID id, Mappings mappings, Instant now) {
        for (ComponentMap m : mappings.components()) safe(() -> jdbc.update("""
                INSERT INTO payroll_accounting_component_mappings(id,organisation_id,school_id,
                  configuration_id,payroll_component_id,component_kind_snapshot,expense_account_id,
                  payable_account_id,created_at)
                VALUES(:id,:organisationId,:schoolId,:configurationId,:componentId,:kind,:expenseId,:payableId,:now)
                """, s.params().addValue("id", UUID.randomUUID()).addValue("configurationId", id)
                .addValue("componentId", m.componentId()).addValue("kind", m.kind())
                .addValue("expenseId", m.expenseId()).addValue("payableId", m.payableId()).addValue("now", now)));
        for (var m : mappings.tenders().entrySet()) safe(() -> jdbc.update("""
                INSERT INTO payroll_accounting_tender_mappings(id,organisation_id,school_id,
                  configuration_id,payment_method,payment_account_id,created_at)
                VALUES(:id,:organisationId,:schoolId,:configurationId,:method,:accountId,:now)
                """, s.params().addValue("id", UUID.randomUUID()).addValue("configurationId", id)
                .addValue("method", m.getKey()).addValue("accountId", m.getValue()).addValue("now", now)));
    }

    private Mappings mappings(Scope s, UUID id) {
        var params = s.params().addValue("id", id);
        List<ComponentMap> components = jdbc.query("""
                SELECT payroll_component_id,component_kind_snapshot,expense_account_id,payable_account_id
                FROM payroll_accounting_component_mappings
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND configuration_id=:id
                ORDER BY payroll_component_id
                """, params, (r, n) -> new ComponentMap(r.getObject("payroll_component_id", UUID.class),
                r.getString("component_kind_snapshot"), r.getObject("expense_account_id", UUID.class),
                r.getObject("payable_account_id", UUID.class)));
        Map<String, UUID> tenders = jdbc.query("""
                SELECT payment_method,payment_account_id FROM payroll_accounting_tender_mappings
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND configuration_id=:id
                ORDER BY payment_method
                """, params, r -> {
                    Map<String, UUID> result = new LinkedHashMap<>();
                    while (r.next()) result.put(r.getString("payment_method"), r.getObject("payment_account_id", UUID.class));
                    return result;
                });
        return new Mappings(components, tenders);
    }

    private static List<PayrollAccountUse> uses(UUID salaryPayable, Mappings mappings) {
        List<PayrollAccountUse> uses = new ArrayList<>();
        uses.add(new PayrollAccountUse(salaryPayable, PayrollAccountUseType.SALARY_PAYABLE));
        for (ComponentMap m : mappings.components()) {
            if (m.expenseId() != null) uses.add(new PayrollAccountUse(m.expenseId(), PayrollAccountUseType.EXPENSE));
            if (m.payableId() != null) uses.add(new PayrollAccountUse(m.payableId(), PayrollAccountUseType.LIABILITY));
        }
        mappings.tenders().forEach((method, id) -> uses.add(new PayrollAccountUse(id,
                "CASH".equals(method) ? PayrollAccountUseType.CASH : PayrollAccountUseType.BANK)));
        return List.copyOf(uses);
    }

    private Configuration configuration(Scope s, UUID id, boolean lock) {
        List<Configuration> rows = jdbc.query("""
                SELECT * FROM payroll_accounting_configurations
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                """ + (lock ? " FOR UPDATE" : ""), s.params().addValue("id", id),
                (r, n) -> configuration(r));
        if (rows.isEmpty()) throw problem(HttpStatus.NOT_FOUND, "PAYROLL_MAPPING_NOT_FOUND",
                "The payroll accounting mapping was not found.");
        return rows.getFirst();
    }
    private static Configuration configuration(ResultSet r) throws SQLException {
        return new Configuration(r.getObject("id", UUID.class), r.getObject("financial_year_id", UUID.class),
                r.getObject("supersedes_configuration_id", UUID.class), r.getObject("effective_from", LocalDate.class),
                r.getString("currency_code"), r.getObject("salary_payable_account_id", UUID.class),
                r.getString("status"), r.getObject("created_by_membership_id", UUID.class), r.getLong("row_version"));
    }

    @Transactional
    public PayrollMutationResponse prepareAccrual(AuthenticatedUser u, UUID runId,
            String idempotencyKey, AccrualRequest q) {
        Scope s = scope(u, q.schoolId(), "prepare");
        String retryKey = key(idempotencyKey), why = reason(q.reason());
        String fingerprint = hash(json(List.of("payroll-accrual-v1", runId, q)));
        lock(s);
        Retry retry = retry(s, retryKey);
        if (retry != null) return retryResult(retry, fingerprint);
        Run run = run(s, runId, true);
        requireFinalized(s, run);
        postingDate(q.postingDate());
        if (!q.postingDate().equals(run.periodEnd())) throw bad("PAYROLL_ACCRUAL_DATE_INVALID",
                "Post the payroll accrual on the finalized payroll period end date.");
        Configuration c = effective(s, q.financialYearId(), q.postingDate());
        PayrollBookContext book = accounting.resolvePayrollPostingBook(u, s.schoolId(), q.financialYearId(), q.postingDate());
        matchingBook(run, c, book);
        List<Batch> existing = batches(s, runId);
        Map<UUID, String> statuses = statuses(u, s, existing);
        releaseSources(s, existing, statuses);
        if (activeItems(s, runId, "ACCRUAL", null)) throw conflict("PAYROLL_ACCRUAL_ALREADY_PREPARED",
                "This finalized payroll run already has an active accrual journal.");
        List<StaffResult> staff = staffResults(s, runId);
        if (staff.size() != run.count()) throw conflict("PAYROLL_EVIDENCE_INCOMPLETE",
                "The finalized payroll staff evidence is incomplete.");
        Mappings mapping = mappings(s, c.id());
        List<PayrollJournalLine> lines = accrualLines(run, c, mapping, staff);
        BigDecimal amount = money(run.gross().add(run.employer()));
        if (amount.signum() <= 0) throw conflict("PAYROLL_ZERO_ACCRUAL",
                "This zero-value payroll has no accounting amount to post.");
        return prepareBatch(u, s, c, run, null, book, "ACCRUAL", q.postingDate(),
                null, null, retryKey, fingerprint, amount, staff, lines, why);
    }

    @Transactional
    public PayrollMutationResponse preparePayment(AuthenticatedUser u, UUID runId,
            String idempotencyKey, PaymentRequest q) {
        Scope s = scope(u, q.schoolId(), "prepare");
        String retryKey = key(idempotencyKey), why = reason(q.reason());
        String method = choice(q.paymentMethod(), METHODS, "payment method");
        String reference = q.paymentReference() == null || q.paymentReference().isBlank() ? null
                : text(q.paymentReference(), 2, 120, "payment reference");
        if (!"CASH".equals(method) && reference == null) throw bad("PAYROLL_PAYMENT_REFERENCE_REQUIRED",
                "Record a bank, cheque, or UPI reference for non-cash salary payment.");
        List<UUID> ids = q.payrollRunStaffResultIds().stream().filter(Objects::nonNull)
                .distinct().sorted(Comparator.comparing(UUID::toString)).toList();
        if (ids.isEmpty() || ids.size() > 500 || ids.size() != q.payrollRunStaffResultIds().size()) {
            throw bad("PAYROLL_PAYMENT_STAFF_INVALID", "Select each staff result once, with no empty selection.");
        }
        PaymentRequest canonical = new PaymentRequest(q.schoolId(), q.financialYearId(),
                q.postingDate(), method, reference, ids, why);
        String fingerprint = hash(json(List.of("payroll-payment-v1", runId, canonical)));
        lock(s);
        Retry retry = retry(s, retryKey);
        if (retry != null) return retryResult(retry, fingerprint);
        Run run = run(s, runId, true);
        requireFinalized(s, run);
        postingDate(q.postingDate());
        List<Batch> existing = batches(s, runId);
        Map<UUID, String> statuses = statuses(u, s, existing);
        Batch accrual = existing.stream().filter(b -> "ACCRUAL".equals(b.type()))
                .filter(b -> "POSTED".equals(statuses.get(b.journalId()))).findFirst()
                .orElseThrow(() -> conflict("PAYROLL_ACCRUAL_NOT_POSTED",
                        "The payroll accrual must receive independent accounting approval before salary payment."));
        if (q.postingDate().isBefore(accrual.date())) throw bad("PAYROLL_PAYMENT_DATE_INVALID",
                "Salary payment cannot precede its accrual posting date.");
        Configuration c = effective(s, q.financialYearId(), q.postingDate());
        Configuration original = configuration(s, accrual.configurationId(), false);
        if (!c.salaryPayableId().equals(original.salaryPayableId())) throw conflict("PAYROLL_PAYABLE_CONTROL_CHANGED",
                "The payment mapping must use the same salary payable control as its accrual.");
        PayrollBookContext book = accounting.resolvePayrollPostingBook(u, s.schoolId(), q.financialYearId(), q.postingDate());
        matchingBook(run, c, book);
        UUID tender = mappings(s, c.id()).tenders().get(method);
        if (tender == null) throw conflict("PAYROLL_PAYMENT_METHOD_NOT_MAPPED",
                "Approve an account mapping for this salary payment method first.");
        releaseSources(s, existing, statuses);
        List<StaffResult> staff = staffResults(s, runId).stream().filter(r -> ids.contains(r.id())).toList();
        if (staff.size() != ids.size()) throw bad("PAYROLL_PAYMENT_STAFF_INVALID",
                "Every selected staff result must belong to this finalized payroll run.");
        if (staff.stream().anyMatch(r -> r.net().signum() <= 0)) throw bad("PAYROLL_PAYMENT_ZERO_NET",
                "Only positive net salaries can be included in payment batches.");
        if (activeItems(s, runId, "PAYMENT", ids)) throw conflict("PAYROLL_PAYMENT_ALREADY_PREPARED",
                "At least one selected salary already has an active payment journal.");
        BigDecimal amount = money(staff.stream().map(StaffResult::net).reduce(ZERO, BigDecimal::add));
        List<PayrollJournalLine> lines = List.of(
                new PayrollJournalLine(c.salaryPayableId(), amount, ZERO, "Salary payable settlement"),
                new PayrollJournalLine(tender, ZERO, amount, "Salary payment by " + method));
        return prepareBatch(u, s, c, run, accrual.id(), book, "PAYMENT", q.postingDate(),
                method, reference, retryKey, fingerprint, amount, staff, lines, why);
    }

    private List<PayrollJournalLine> accrualLines(Run run, Configuration c, Mappings mappings,
            List<StaffResult> staff) {
        Map<UUID, ComponentMap> byComponent = mappings.components().stream()
                .collect(Collectors.toMap(ComponentMap::componentId, m -> m));
        Map<LineKey, BigDecimal> totals = new LinkedHashMap<>();
        BigDecimal gross = ZERO, deductions = ZERO, employer = ZERO, net = ZERO;
        for (StaffResult result : staff) {
            BigDecimal lineGross = ZERO, lineDeduction = ZERO, lineEmployer = ZERO;
            for (Line line : result.lines()) {
                ComponentMap m = byComponent.get(line.componentId());
                if (m == null || !m.kind().equals(line.kind())) throw conflict("PAYROLL_COMPONENT_MAPPING_MISSING",
                        "The effective mapping must cover every frozen payroll component with the same kind.");
                switch (line.kind()) {
                    case "EARNING" -> { addLine(totals, m.expenseId(), true, line.amount()); lineGross = lineGross.add(line.amount()); }
                    case "DEDUCTION" -> { addLine(totals, m.payableId(), false, line.amount()); lineDeduction = lineDeduction.add(line.amount()); }
                    case "EMPLOYER_CONTRIBUTION" -> {
                        addLine(totals, m.expenseId(), true, line.amount());
                        addLine(totals, m.payableId(), false, line.amount());
                        lineEmployer = lineEmployer.add(line.amount());
                    }
                    default -> throw conflict("PAYROLL_COMPONENT_INVALID", "The frozen payroll component kind is invalid.");
                }
            }
            for (PayrollRecoveryService.Line installment : result.recoveries()) {
                if (!"ACTIVE".equals(installment.reservationStatus()) || !run.currency().equals(installment.currency())) {
                    throw conflict("PAYROLL_RECOVERY_EVIDENCE_INVALID", "Payroll recovery reservations must be active and in the run currency.");
                }
                addLine(totals, installment.receivableId(), false, installment.amount());
                lineDeduction = lineDeduction.add(installment.amount());
            }
            if (lineGross.compareTo(result.gross()) != 0 || lineDeduction.compareTo(result.deductions()) != 0
                    || lineEmployer.compareTo(result.employer()) != 0
                    || result.net().compareTo(lineGross.subtract(lineDeduction)) != 0) {
                throw conflict("PAYROLL_EVIDENCE_MISMATCH", "Frozen staff totals do not match their component evidence.");
            }
            gross = gross.add(result.gross()); deductions = deductions.add(result.deductions());
            employer = employer.add(result.employer()); net = net.add(result.net());
        }
        if (gross.compareTo(run.gross()) != 0 || deductions.compareTo(run.deductions()) != 0
                || employer.compareTo(run.employer()) != 0 || net.compareTo(run.net()) != 0) {
            throw conflict("PAYROLL_EVIDENCE_MISMATCH", "The finalized run totals do not match all frozen staff results.");
        }
        addLine(totals, c.salaryPayableId(), false, net);
        return totals.entrySet().stream().filter(e -> e.getValue().signum() > 0).map(e ->
                new PayrollJournalLine(e.getKey().accountId(), e.getKey().debit() ? e.getValue() : ZERO,
                        e.getKey().debit() ? ZERO : e.getValue(), "Finalized payroll accrual")).toList();
    }
    private static void addLine(Map<LineKey, BigDecimal> lines, UUID account, boolean debit, BigDecimal amount) {
        if (account == null) throw conflict("PAYROLL_ACCOUNT_MAPPING_MISSING", "A required payroll account is unmapped.");
        lines.merge(new LineKey(account, debit), amount, BigDecimal::add);
    }
    private void postingDate(LocalDate date) {
        if (date == null || date.isAfter(LocalDate.now(clock))) throw bad("PAYROLL_POSTING_DATE_INVALID",
                "Choose a posting date that is not in the future.");
    }
    private static void matchingBook(Run run, Configuration c, PayrollBookContext book) {
        if (!run.currency().equals(c.currency()) || !run.currency().equals(book.currencyCode())) {
            throw conflict("PAYROLL_CURRENCY_MISMATCH", "Payroll, mappings, and the accounting book must share one currency.");
        }
    }

    private PayrollMutationResponse prepareBatch(AuthenticatedUser u, Scope s, Configuration c,
            Run run, UUID accrualId, PayrollBookContext book, String type, LocalDate date,
            String method, String reference, String retryKey, String fingerprint, BigDecimal amount,
            List<StaffResult> staff, List<PayrollJournalLine> lines, String why) {
        if (amount.signum() <= 0 || amount.compareTo(new BigDecimal("1000000000000")) > 0) {
            throw bad("PAYROLL_POSTING_AMOUNT_INVALID", "The payroll posting amount is outside supported limits.");
        }
        Integer previous = jdbc.queryForObject("""
                SELECT coalesce(max(sequence_number),0) FROM payroll_accounting_posting_batches
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND payroll_run_id=:runId AND posting_type=:type
                """, s.params().addValue("runId", run.id()).addValue("type", type), Integer.class);
        int sequence = Objects.requireNonNullElse(previous, 0) + 1;
        if (sequence > 1000) throw conflict("PAYROLL_POSTING_SEQUENCE_EXHAUSTED", "The protected posting sequence is exhausted.");
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schemaVersion", 1); evidence.put("run", run); evidence.put("configuration", c);
        evidence.put("accountingBook", book); evidence.put("postingType", type);
        evidence.put("postingDate", date); evidence.put("paymentMethod", method);
        evidence.put("paymentReference", reference); evidence.put("accrualBatchId", accrualId);
        evidence.put("staff", staff); evidence.put("journalLines", lines); evidence.put("amount", amount);
        String snapshot = json(evidence);
        var p = s.params().addValue("id", id).addValue("configurationId", c.id()).addValue("runId", run.id())
                .addValue("accrualId", accrualId).addValue("yearId", book.financialYearId())
                .addValue("periodId", book.accountingPeriodId()).addValue("type", type).addValue("date", date)
                .addValue("sequence", sequence).addValue("method", method).addValue("reference", reference)
                .addValue("key", retryKey).addValue("fingerprint", fingerprint).addValue("count", staff.size())
                .addValue("amount", amount).addValue("snapshot", snapshot).addValue("hash", hash(snapshot))
                .addValue("reason", why).addValue("now", now);
        safe(() -> {
            jdbc.queryForObject("SELECT set_config('app.payroll_accounting_prepare','approved',true)",
                    new MapSqlParameterSource(), String.class);
            jdbc.update("""
                    INSERT INTO payroll_accounting_posting_batches(id,organisation_id,school_id,
                      configuration_id,payroll_run_id,accrual_batch_id,financial_year_id,accounting_period_id,
                      posting_type,posting_date,sequence_number,payment_method,payment_reference,
                      idempotency_key,request_fingerprint,source_count,source_amount,evidence_snapshot,
                      evidence_sha256,preparation_reason,prepared_by_membership_id,prepared_at,row_version)
                    VALUES(:id,:organisationId,:schoolId,:configurationId,:runId,:accrualId,:yearId,:periodId,
                      :type,:date,:sequence,:method,:reference,:key,:fingerprint,:count,:amount,
                      CAST(:snapshot AS jsonb),:hash,:reason,:actorId,:now,0)
                    """, p);
            for (int i = 0; i < staff.size(); i++) {
                StaffResult result = staff.get(i);
                String source = json(result);
                jdbc.update("""
                        INSERT INTO payroll_accounting_posting_items(id,organisation_id,school_id,
                          posting_batch_id,posting_type,payroll_run_staff_result_id,sequence_number,
                          source_amount,source_snapshot,source_sha256,status,created_at,updated_at,row_version)
                        VALUES(:itemId,:organisationId,:schoolId,:id,:type,:resultId,:itemSequence,
                          :itemAmount,CAST(:source AS jsonb),:sourceHash,'ACTIVE',:now,:now,0)
                        """, p.addValue("itemId", UUID.randomUUID()).addValue("resultId", result.id())
                        .addValue("itemSequence", i + 1).addValue("itemAmount",
                                "ACCRUAL".equals(type) ? result.gross().add(result.employer()) : result.net())
                        .addValue("source", source).addValue("sourceHash", hash(source)));
            }
            jdbc.query("SELECT validate_complete_payroll_accounting_batch(:id,:organisationId,:schoolId)", p, ignored -> null);
            return 1;
        });
        var journal = accounting.preparePayrollJournal(u, new PayrollJournalRequest(s.schoolId(),
                book.financialYearId(), book.accountingPeriodId(), date,
                "PAYMENT".equals(type) ? "PAYMENT" : "JOURNAL",
                run.code() + "-" + type + "-" + sequence, "Payroll " + type.toLowerCase(Locale.ROOT) + " for " + run.code(),
                id, "payroll-gl-" + id, "Prepared from immutable payroll batch " + id, lines));
        jdbc.queryForObject("SELECT set_config('app.payroll_accounting_journal_link','approved',true)",
                new MapSqlParameterSource(), String.class);
        changed(safe(() -> jdbc.update("""
                UPDATE payroll_accounting_posting_batches SET accounting_journal_id=:journalId,
                  journal_linked_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND accounting_journal_id IS NULL AND row_version=0
                """, s.params().addValue("id", id).addValue("journalId", journal.id()).addValue("now", now))));
        audit(u, s, "payroll." + type.toLowerCase(Locale.ROOT) + ".prepared", "PAYROLL_ACCOUNTING_POSTING_BATCH", id, why);
        return new PayrollMutationResponse(id, 1);
    }

    private Configuration effective(Scope s, UUID yearId, LocalDate date) {
        List<Configuration> rows = jdbc.query("""
                SELECT * FROM payroll_accounting_configurations
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND financial_year_id=:yearId AND effective_from<=:date
                  AND status IN ('APPROVED','SUPERSEDED')
                ORDER BY effective_from DESC,revision_number DESC LIMIT 1
                """, s.params().addValue("yearId", yearId).addValue("date", date), (r, n) -> configuration(r));
        if (rows.isEmpty()) throw conflict("PAYROLL_MAPPING_REQUIRED",
                "Approve a payroll accounting mapping effective for this posting date.");
        return rows.getFirst();
    }
    private Run run(Scope s, UUID id, boolean lock) {
        List<Run> rows = jdbc.query("""
                SELECT * FROM payroll_runs WHERE id=:id
                  AND organisation_id=:organisationId AND school_id=:schoolId
                """ + (lock ? " FOR UPDATE" : ""), s.params().addValue("id", id), (r, n) -> run(r));
        if (rows.isEmpty()) throw problem(HttpStatus.NOT_FOUND, "PAYROLL_RUN_NOT_FOUND", "The payroll run was not found.");
        return rows.getFirst();
    }
    private static Run run(ResultSet r) throws SQLException {
        return new Run(r.getObject("id", UUID.class), r.getString("run_code"),
                r.getObject("payroll_month", LocalDate.class), r.getObject("period_end", LocalDate.class),
                r.getString("currency_code"), r.getString("status"), r.getInt("staff_count"),
                money(r.getBigDecimal("payable_gross_total")), money(r.getBigDecimal("deduction_total")),
                money(r.getBigDecimal("employer_contribution_total")), money(r.getBigDecimal("net_total")));
    }
    private void requireFinalized(Scope s, Run run) {
        if (!"FINALIZED".equals(run.status())) throw conflict("PAYROLL_RUN_NOT_FINALIZED",
                "Only an unchanged finalized payroll run can be posted or paid.");
        Integer pending = jdbc.queryForObject("""
                SELECT count(*) FROM payroll_run_reversal_requests
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND payroll_run_id=:runId AND status='PENDING'
                """, s.params().addValue("runId", run.id()), Integer.class);
        if (pending != null && pending > 0) throw conflict("PAYROLL_REVERSAL_PENDING",
                "Resolve the pending payroll reversal before preparing accounting entries.");
    }
    private List<StaffResult> staffResults(Scope s, UUID runId) {
        Map<UUID, List<PayrollRecoveryService.Line>> recoveries = recovery.forRuns(
                s.organisationId(), s.schoolId(), List.of(runId));
        Map<UUID, List<Line>> lines = jdbc.query("""
                SELECT line.* FROM payroll_run_staff_lines line
                JOIN payroll_run_staff_results result ON result.id=line.payroll_run_staff_result_id
                  AND result.organisation_id=line.organisation_id AND result.school_id=line.school_id
                WHERE result.organisation_id=:organisationId AND result.school_id=:schoolId
                  AND result.payroll_run_id=:runId
                ORDER BY result.id,line.component_kind_snapshot,line.component_code_snapshot,line.id
                """, s.params().addValue("runId", runId), r -> {
                    Map<UUID, List<Line>> result = new LinkedHashMap<>();
                    while (r.next()) result.computeIfAbsent(r.getObject("payroll_run_staff_result_id", UUID.class),
                            ignored -> new ArrayList<>()).add(new Line(r.getObject("payroll_component_id", UUID.class),
                            r.getString("component_code_snapshot"), r.getString("component_name_snapshot"),
                            r.getString("component_kind_snapshot"), money(r.getBigDecimal("payable_amount"))));
                    return result;
                });
        return jdbc.query("""
                SELECT * FROM payroll_run_staff_results
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND payroll_run_id=:runId
                ORDER BY staff_name_snapshot,id
                """, s.params().addValue("runId", runId), (r, n) -> {
                    UUID id = r.getObject("id", UUID.class);
                    return new StaffResult(id, r.getObject("staff_profile_id", UUID.class),
                            r.getString("employee_code_snapshot"), r.getString("staff_name_snapshot"),
                            money(r.getBigDecimal("payable_gross_amount")), money(r.getBigDecimal("deduction_amount")),
                            money(r.getBigDecimal("employer_contribution_amount")), money(r.getBigDecimal("net_amount")),
                            List.copyOf(lines.getOrDefault(id, List.of())), recoveries.getOrDefault(id, List.of()));
                });
    }
    private Retry retry(Scope s, String key) {
        List<Retry> rows = jdbc.query("""
                SELECT id,request_fingerprint,row_version FROM payroll_accounting_posting_batches
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND idempotency_key=:key
                """, s.params().addValue("key", key), (r, n) -> new Retry(r.getObject("id", UUID.class),
                r.getString("request_fingerprint"), r.getLong("row_version")));
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private static PayrollMutationResponse retryResult(Retry retry, String fingerprint) {
        if (!retry.fingerprint().equals(fingerprint)) throw conflict("IDEMPOTENCY_KEY_REUSED",
                "This retry key was already used for different payroll accounting evidence.");
        return new PayrollMutationResponse(retry.id(), retry.version());
    }
    private boolean activeItems(Scope s, UUID runId, String type, List<UUID> ids) {
        var params = s.params().addValue("runId", runId).addValue("type", type);
        if (ids != null) params.addValue("ids", ids);
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM payroll_accounting_posting_items item
                JOIN payroll_accounting_posting_batches batch ON batch.id=item.posting_batch_id
                  AND batch.organisation_id=item.organisation_id AND batch.school_id=item.school_id
                WHERE item.organisation_id=:organisationId AND item.school_id=:schoolId
                  AND batch.payroll_run_id=:runId AND item.posting_type=:type AND item.status='ACTIVE'
                """ + (ids == null ? "" : " AND item.payroll_run_staff_result_id IN (:ids)"), params, Integer.class);
        return count != null && count > 0;
    }
    private Map<UUID, String> statuses(AuthenticatedUser u, Scope s, List<Batch> batches) {
        return accounting.payrollJournalStatuses(u, s.schoolId(), batches.stream()
                .map(Batch::journalId).filter(Objects::nonNull).toList());
    }
    private void releaseSources(Scope s, List<Batch> batches, Map<UUID, String> statuses) {
        List<UUID> ids = batches.stream().filter(b -> b.activeCount() > 0)
                .filter(b -> b.journalId() != null && RELEASED.contains(statuses.getOrDefault(b.journalId(), "")))
                .map(Batch::id).toList();
        if (ids.isEmpty()) return;
        jdbc.queryForObject("SELECT set_config('app.payroll_accounting_item_release','approved',true)",
                new MapSqlParameterSource(), String.class);
        safe(() -> jdbc.update("""
                UPDATE payroll_accounting_posting_items SET status='RELEASED',
                  released_by_membership_id=:actorId,released_at=:now,
                  release_reason='Source released after payroll journal rejection or reversal',
                  updated_at=:now,row_version=row_version+1
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND posting_batch_id IN (:ids) AND status='ACTIVE'
                """, s.params().addValue("ids", ids).addValue("now", Instant.now(clock))));
    }
    private List<Batch> batches(Scope s, UUID runId) {
        return jdbc.query("""
                SELECT b.*,u.display_name preparer_name,
                  (SELECT count(*) FROM payroll_accounting_posting_items i
                   WHERE i.posting_batch_id=b.id AND i.organisation_id=b.organisation_id
                     AND i.school_id=b.school_id AND i.status='ACTIVE') active_count
                FROM payroll_accounting_posting_batches b
                JOIN memberships m ON m.id=b.prepared_by_membership_id
                  AND m.organisation_id=b.organisation_id AND m.school_id=b.school_id
                JOIN user_accounts u ON u.id=m.user_id
                WHERE b.organisation_id=:organisationId AND b.school_id=:schoolId
                  AND (CAST(:runId AS uuid) IS NULL OR b.payroll_run_id=:runId)
                ORDER BY b.posting_date DESC,b.prepared_at DESC,b.id
                """, s.params().addValue("runId", runId), (r, n) -> new Batch(
                r.getObject("id", UUID.class), r.getObject("configuration_id", UUID.class),
                r.getObject("payroll_run_id", UUID.class), r.getObject("accrual_batch_id", UUID.class),
                r.getObject("financial_year_id", UUID.class), r.getObject("accounting_period_id", UUID.class),
                r.getString("posting_type"), r.getObject("posting_date", LocalDate.class), r.getInt("sequence_number"),
                r.getString("payment_method"), r.getString("payment_reference"), r.getInt("source_count"),
                r.getInt("active_count"), money(r.getBigDecimal("source_amount")),
                r.getObject("accounting_journal_id", UUID.class), r.getString("preparer_name"),
                instant(r, "prepared_at"), r.getLong("row_version")));
    }

    @Transactional(readOnly = true)
    public PayrollAccountingWorkspace workspace(AuthenticatedUser u, UUID schoolId) {
        Scope s = scope(u, schoolId, null);
        var options = accounting.payrollAccountingOptions(u, s.schoolId());
        List<ComponentOption> components = jdbc.query("""
                SELECT id,code,display_name,component_kind,status FROM payroll_components
                WHERE organisation_id=:organisationId AND school_id=:schoolId ORDER BY display_order,code,id
                """, s.params(), (r, n) -> new ComponentOption(r.getObject("id", UUID.class),
                r.getString("code"), r.getString("display_name"), r.getString("component_kind"), r.getString("status")));
        Map<UUID, ComponentOption> byId = components.stream().collect(Collectors.toMap(ComponentOption::id, c -> c));
        Map<UUID, List<ComponentMappingItem>> componentMappings = jdbc.query("""
                SELECT * FROM payroll_accounting_component_mappings
                WHERE organisation_id=:organisationId AND school_id=:schoolId ORDER BY configuration_id,payroll_component_id
                """, s.params(), r -> {
                    Map<UUID, List<ComponentMappingItem>> result = new LinkedHashMap<>();
                    while (r.next()) {
                        UUID componentId = r.getObject("payroll_component_id", UUID.class);
                        ComponentOption c = byId.get(componentId);
                        result.computeIfAbsent(r.getObject("configuration_id", UUID.class), ignored -> new ArrayList<>())
                                .add(new ComponentMappingItem(componentId, c.code(), c.displayName(),
                                        r.getString("component_kind_snapshot"), r.getObject("expense_account_id", UUID.class),
                                        r.getObject("payable_account_id", UUID.class)));
                    }
                    return result;
                });
        Map<UUID, List<TenderMappingItem>> tenderMappings = jdbc.query("""
                SELECT * FROM payroll_accounting_tender_mappings
                WHERE organisation_id=:organisationId AND school_id=:schoolId ORDER BY configuration_id,payment_method
                """, s.params(), r -> {
                    Map<UUID, List<TenderMappingItem>> result = new LinkedHashMap<>();
                    while (r.next()) result.computeIfAbsent(r.getObject("configuration_id", UUID.class),
                            ignored -> new ArrayList<>()).add(new TenderMappingItem(r.getString("payment_method"),
                            r.getObject("payment_account_id", UUID.class)));
                    return result;
                });
        List<ConfigurationItem> configurations = jdbc.query("""
                SELECT c.*,creator_user.display_name creator_name,submitter_user.display_name submitter_name,
                       decider_user.display_name decider_name
                FROM payroll_accounting_configurations c
                JOIN memberships creator ON creator.id=c.created_by_membership_id
                  AND creator.organisation_id=c.organisation_id AND creator.school_id=c.school_id
                JOIN user_accounts creator_user ON creator_user.id=creator.user_id
                LEFT JOIN memberships submitter ON submitter.id=c.submitted_by_membership_id
                  AND submitter.organisation_id=c.organisation_id AND submitter.school_id=c.school_id
                LEFT JOIN user_accounts submitter_user ON submitter_user.id=submitter.user_id
                LEFT JOIN memberships decider ON decider.id=c.decided_by_membership_id
                  AND decider.organisation_id=c.organisation_id AND decider.school_id=c.school_id
                LEFT JOIN user_accounts decider_user ON decider_user.id=decider.user_id
                WHERE c.organisation_id=:organisationId AND c.school_id=:schoolId
                ORDER BY c.effective_from DESC,c.revision_number DESC,c.id
                """, s.params(), (r, n) -> {
                    UUID id = r.getObject("id", UUID.class);
                    return new ConfigurationItem(id, r.getObject("financial_year_id", UUID.class),
                            r.getObject("supersedes_configuration_id", UUID.class), r.getInt("revision_number"),
                            r.getObject("effective_from", LocalDate.class), r.getString("accounting_basis_snapshot"),
                            r.getString("currency_code"), r.getObject("salary_payable_account_id", UUID.class),
                            r.getString("status"), r.getString("creator_name"), instant(r, "created_at"),
                            r.getString("submitter_name"), instant(r, "submitted_at"), r.getString("decider_name"),
                            instant(r, "decided_at"), r.getLong("row_version"),
                            s.allowed("configure") && "DRAFT".equals(r.getString("status"))
                                    && s.actorId().equals(r.getObject("created_by_membership_id", UUID.class)),
                            s.allowed("configure") && "DRAFT".equals(r.getString("status"))
                                    && s.actorId().equals(r.getObject("created_by_membership_id", UUID.class)),
                            s.allowed("approve") && "SUBMITTED".equals(r.getString("status"))
                                    && !s.actorId().equals(r.getObject("created_by_membership_id", UUID.class)),
                            componentMappings.getOrDefault(id, List.of()), tenderMappings.getOrDefault(id, List.of()));
                });
        List<Batch> batches = batches(s, null);
        Map<UUID, String> statuses = statuses(u, s, batches);
        List<Run> runs = jdbc.query("""
                SELECT * FROM payroll_runs WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND status IN ('FINALIZED','REVERSED')
                ORDER BY payroll_month DESC,attempt_number DESC,id LIMIT 24
                """, s.params(), (r, n) -> run(r));
        Map<UUID, List<RunStaffOption>> staff = new LinkedHashMap<>();
        if (!runs.isEmpty()) jdbc.query("""
                SELECT result.id,result.payroll_run_id,result.staff_profile_id,result.employee_code_snapshot,
                       result.staff_name_snapshot,result.net_amount,batch.accounting_journal_id
                FROM payroll_run_staff_results result
                LEFT JOIN payroll_accounting_posting_items item ON item.payroll_run_staff_result_id=result.id
                  AND item.organisation_id=result.organisation_id AND item.school_id=result.school_id
                  AND item.posting_type='PAYMENT' AND item.status='ACTIVE'
                LEFT JOIN payroll_accounting_posting_batches batch ON batch.id=item.posting_batch_id
                  AND batch.organisation_id=item.organisation_id AND batch.school_id=item.school_id
                WHERE result.organisation_id=:organisationId AND result.school_id=:schoolId
                  AND result.payroll_run_id IN (:runIds) ORDER BY result.payroll_run_id,result.staff_name_snapshot,result.id
                """, s.params().addValue("runIds", runs.stream().map(Run::id).toList()), r -> {
                    while (r.next()) {
                        UUID journal = r.getObject("accounting_journal_id", UUID.class);
                        String status = journal == null ? "UNPAID" : operational("PAYMENT", statuses.get(journal));
                        if (RELEASED.contains(status)) status = "UNPAID";
                        BigDecimal net = money(r.getBigDecimal("net_amount"));
                        staff.computeIfAbsent(r.getObject("payroll_run_id", UUID.class), ignored -> new ArrayList<>())
                                .add(new RunStaffOption(r.getObject("id", UUID.class), r.getObject("staff_profile_id", UUID.class),
                                        r.getString("employee_code_snapshot"), r.getString("staff_name_snapshot"), net,
                                        net.signum() == 0 ? "NOT_PAYABLE" : status));
                    }
                    return null;
                });
        List<RunOption> runOptions = runs.stream().map(run -> {
            String accrual = batches.stream().filter(b -> b.runId().equals(run.id()) && "ACCRUAL".equals(b.type()))
                    .map(b -> operational("ACCRUAL", statuses.get(b.journalId())))
                    .filter(status -> !RELEASED.contains(status)).findFirst().orElse("NOT_PREPARED");
            return new RunOption(run.id(), run.code(), run.month(), run.periodEnd(), run.currency(), run.status(), run.count(),
                    run.gross(), run.deductions(), run.employer(), run.net(), accrual, staff.getOrDefault(run.id(), List.of()));
        }).toList();
        return new PayrollAccountingWorkspace(s.schoolId(), s.schoolName(), s.allowed("configure"),
                s.allowed("prepare"), s.allowed("approve"),
                options.financialYears().stream().map(y -> new FinancialYearOption(y.id(), y.code(), y.displayName(),
                        y.startsOn(), y.endsOn(), y.accountingBasis(), y.currencyCode(), y.status())).toList(),
                options.accounts().stream().map(a -> new AccountOption(a.id(), a.code(), a.displayName(),
                        a.accountType(), a.category(), a.status())).toList(), components, configurations, runOptions,
                batches.stream().limit(100).map(b -> b.item(statuses.get(b.journalId()))).toList());
    }

    private Scope scope(AuthenticatedUser user, UUID schoolId, String action) {
        if (user.activeOrganisationId() == null || user.membershipIds().isEmpty()) {
            throw problem(HttpStatus.FORBIDDEN, "PAYROLL_ACCOUNTING_SCOPE_DENIED",
                    "Payroll accounting access is unavailable for this school.");
        }
        List<Scope> rows = jdbc.query("""
                SELECT s.id,s.display_name,m.id membership_id,
                  coalesce(array_agg(DISTINCT rc.capability_code)
                    FILTER (WHERE rc.capability_code IS NOT NULL),ARRAY[]::text[]) capabilities
                FROM memberships m JOIN schools s ON s.id=m.school_id AND s.organisation_id=m.organisation_id
                LEFT JOIN membership_roles mr ON mr.membership_id=m.id
                LEFT JOIN role_capabilities rc ON rc.role_id=mr.role_id
                WHERE m.user_id=:userId AND m.organisation_id=:organisationId
                  AND m.id IN (:memberships) AND m.status='ACTIVE'
                  AND m.valid_from<=CURRENT_TIMESTAMP
                  AND (m.valid_until IS NULL OR m.valid_until>CURRENT_TIMESTAMP)
                  AND (CAST(:schoolId AS uuid) IS NULL OR m.school_id=:schoolId)
                GROUP BY s.id,s.display_name,m.id,m.created_at ORDER BY m.created_at,m.id
                """, new MapSqlParameterSource("userId", user.userId())
                .addValue("organisationId", user.activeOrganisationId())
                .addValue("memberships", user.membershipIds()).addValue("schoolId", schoolId),
                (r, n) -> new Scope(user.activeOrganisationId(), r.getObject("id", UUID.class),
                        r.getString("display_name"), r.getObject("membership_id", UUID.class),
                        Set.of((String[]) r.getArray("capabilities").getArray())));
        return rows.stream().filter(s -> s.has("payroll.read", "accounting.read"))
                .filter(s -> action == null || s.allowed(action)).findFirst()
                .orElseThrow(() -> problem(HttpStatus.FORBIDDEN, "PAYROLL_ACCOUNTING_SCOPE_DENIED",
                        "Payroll accounting access is unavailable for this school or action."));
    }

    private void lock(Scope s) {
        // Share the accounting lock before acquiring payroll/run locks to avoid reversed lock ordering.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))",
                s.params().addValue("key", "accounting:school:" + s.schoolId()), ignored -> null);
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))",
                s.params().addValue("key", "payroll-accounting:" + s.schoolId()), ignored -> null);
    }

    private void audit(AuthenticatedUser u, Scope s, String action, String type, UUID id, String why) {
        audit.recordScoped(u, s.actorId(), s.schoolId(), action, type, id.toString(),
                AuditOutcome.SUCCEEDED, why, Map.of());
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JacksonException exception) {
            throw problem(HttpStatus.INTERNAL_SERVER_ERROR, "PAYROLL_ACCOUNTING_EVIDENCE_FAILED",
                    "The immutable payroll accounting evidence could not be prepared.");
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String reason(String value) { return text(value, 3, 500, "reason"); }
    private static String text(String value, int minimum, int maximum, String label) {
        String clean = value == null ? "" : value.strip();
        if (clean.length() < minimum || clean.length() > maximum
                || clean.chars().anyMatch(Character::isISOControl)) {
            throw bad("PAYROLL_ACCOUNTING_INPUT_INVALID", "Enter a valid " + label + ".");
        }
        return clean;
    }
    private static String key(String value) { return text(value, 8, 100, "retry key"); }
    private static String choice(String value, Set<String> allowed, String label) {
        String normalized = text(value, 1, 40, label).toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw bad("PAYROLL_ACCOUNTING_INPUT_INVALID",
                "Choose a supported " + label + ".");
        return normalized;
    }
    private static BigDecimal money(BigDecimal value) { return Objects.requireNonNull(value).setScale(2); }
    private static Instant instant(ResultSet r, String column) throws SQLException {
        var timestamp = r.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
    private static void changed(int count) {
        if (count != 1) throw conflict("STALE_VERSION", "The record changed. Refresh and try again.");
    }
    private static void version(long actual, Long expected) {
        if (expected == null || actual != expected) changed(0);
    }
    private static ApiException bad(String code, String message) {
        return problem(HttpStatus.BAD_REQUEST, code, message);
    }
    private static ApiException conflict(String code, String message) {
        return problem(HttpStatus.CONFLICT, code, message);
    }
    private static ApiException problem(HttpStatus status, String code, String message) {
        return new ApiException(status, code, message);
    }
    private static int safe(java.util.function.IntSupplier work) {
        try { return work.getAsInt(); }
        catch (DataAccessException exception) {
            if (!DatabaseConflict.isIntegrityRejection(exception)) throw exception;
            throw conflict("PAYROLL_ACCOUNTING_CONFLICT",
                    "The operation conflicts with protected payroll accounting history.");
        }
    }
    private static String operational(String type, String status) {
        if (status == null) return "UNVERIFIED";
        return switch (status) {
            case "SUBMITTED" -> "AWAITING_APPROVAL";
            case "POSTED" -> "PAYMENT".equals(type) ? "PAID" : "POSTED";
            default -> status;
        };
    }

    // Package-local reporting reuses the exact live membership/capability checks.
    Scope reportingScope(AuthenticatedUser user, UUID schoolId) {
        return scope(user, schoolId, null);
    }

    record Scope(UUID organisationId, UUID schoolId, String schoolName, UUID actorId,
            Set<String> capabilities) {
        MapSqlParameterSource params() {
            return new MapSqlParameterSource("organisationId", organisationId)
                    .addValue("schoolId", schoolId).addValue("actorId", actorId);
        }
        boolean has(String... required) { return capabilities.containsAll(List.of(required)); }
        boolean allowed(String action) {
            return switch (action) {
                case "configure" -> has("payroll.configure", "accounting.configure");
                case "approve" -> has("payroll.approve", "accounting.post");
                case "prepare" -> has("payroll.run.prepare", "accounting.prepare");
                default -> false;
            };
        }
    }
    private record Configuration(UUID id, UUID yearId, UUID supersedesId, LocalDate effectiveFrom,
            String currency, UUID salaryPayableId, String status, UUID creatorId, long version) {}
    private record ComponentMap(UUID componentId, String kind, UUID expenseId, UUID payableId) {}
    private record Mappings(List<ComponentMap> components, Map<String, UUID> tenders) {}
    private record Run(UUID id, String code, LocalDate month, LocalDate periodEnd, String currency,
            String status, int count, BigDecimal gross, BigDecimal deductions,
            BigDecimal employer, BigDecimal net) {}
    private record Line(UUID componentId, String code, String name, String kind, BigDecimal amount) {}
    private record StaffResult(UUID id, UUID staffId, String employeeCode, String name,
            BigDecimal gross, BigDecimal deductions, BigDecimal employer, BigDecimal net,
            List<Line> lines, List<PayrollRecoveryService.Line> recoveries) {}
    private record Batch(UUID id, UUID configurationId, UUID runId, UUID accrualId, UUID yearId,
            UUID periodId, String type, LocalDate date, int sequence, String method, String reference,
            int count, int activeCount, BigDecimal amount, UUID journalId,
            String preparerName, Instant preparedAt, long version) {
        PostingBatchItem item(String status) {
            return new PostingBatchItem(id, configurationId, runId, accrualId, yearId, periodId,
                    type, date, sequence, method, reference, count, activeCount, amount, journalId,
                    status, operational(type, status), preparerName, preparedAt, version);
        }
    }
    private record Retry(UUID id, String fingerprint, long version) {}
    private record LineKey(UUID accountId, boolean debit) {}
}
