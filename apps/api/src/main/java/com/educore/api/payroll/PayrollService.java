package com.educore.api.payroll;

import static com.educore.api.payroll.PayrollController.*;
import static com.educore.api.payroll.PayrollSnapshot.*;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import com.educore.api.people.PeoplePayrollBridge;
import com.educore.api.people.PeoplePayrollBridge.PayrollStaff;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PayrollService {
    private static final Set<String> KINDS = Set.of("EARNING", "DEDUCTION", "EMPLOYER_CONTRIBUTION");
    private static final Set<String> METHODS = Set.of("FIXED", "PERCENT_OF_BASIC");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");
    private final NamedParameterJdbcTemplate jdbc;
    private final PeoplePayrollBridge people;
    private final SecurityAuditService audit;
    private final Clock clock;

    public PayrollService(NamedParameterJdbcTemplate jdbc, PeoplePayrollBridge people,
            SecurityAuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.people = people;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PayrollSnapshot snapshot(AuthenticatedUser user, UUID requestedSchoolId) {
        Scope scope = requireScope(user, requestedSchoolId);
        List<StaffItem> staff = people.payrollStaff(user, scope.schoolId(), "payroll.read").stream()
                .map(item -> new StaffItem(item.id(), item.employeeCode(), item.displayName(),
                        item.joinedOn(), item.endedOn(), item.status(), item.designation(), item.department()))
                .toList();
        List<ComponentItem> components = jdbc.query("""
                SELECT id,code,display_name,component_kind,calculation_method,is_basic,
                       attendance_prorated,statutory,description,display_order,status,row_version
                FROM payroll_components
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY CASE status WHEN 'ACTIVE' THEN 0 ELSE 1 END,display_order,display_name,id
                """, scope.params(), (r, n) -> new ComponentItem(r.getObject("id", UUID.class),
                r.getString("code"), r.getString("display_name"), r.getString("component_kind"),
                r.getString("calculation_method"), r.getBoolean("is_basic"),
                r.getBoolean("attendance_prorated"), r.getBoolean("statutory"),
                r.getString("description"), r.getInt("display_order"), r.getString("status"),
                r.getLong("row_version")));
        List<ProfileHeader> headers = jdbc.query("""
                SELECT profile.*,creator_user.display_name AS creator_name,
                       submitter_user.display_name AS submitter_name,
                       decider_user.display_name AS decider_name
                FROM staff_salary_profiles profile
                JOIN memberships creator ON creator.id=profile.created_by_membership_id
                  AND creator.organisation_id=profile.organisation_id
                  AND creator.school_id=profile.school_id
                JOIN user_accounts creator_user ON creator_user.id=creator.user_id
                LEFT JOIN memberships submitter ON submitter.id=profile.submitted_by_membership_id
                  AND submitter.organisation_id=profile.organisation_id
                  AND submitter.school_id=profile.school_id
                LEFT JOIN user_accounts submitter_user ON submitter_user.id=submitter.user_id
                LEFT JOIN memberships decider ON decider.id=profile.decided_by_membership_id
                  AND decider.organisation_id=profile.organisation_id
                  AND decider.school_id=profile.school_id
                LEFT JOIN user_accounts decider_user ON decider_user.id=decider.user_id
                WHERE profile.organisation_id=:organisationId AND profile.school_id=:schoolId
                ORDER BY CASE profile.status WHEN 'SUBMITTED' THEN 0 WHEN 'DRAFT' THEN 1
                         WHEN 'APPROVED' THEN 2 ELSE 3 END,profile.effective_from DESC,
                         profile.created_at DESC,profile.id
                """, scope.params(), (r, n) -> new ProfileHeader(r.getObject("id", UUID.class),
                r.getObject("staff_profile_id", UUID.class),
                r.getObject("supersedes_profile_id", UUID.class), r.getInt("revision_number"),
                r.getObject("effective_from", LocalDate.class), r.getString("currency_code"),
                r.getString("pay_frequency"), r.getString("status"), r.getString("creation_reason"),
                r.getString("creator_name"), r.getString("submitter_name"),
                instant(r.getTimestamp("submitted_at")), r.getString("decider_name"),
                instant(r.getTimestamp("decided_at")), r.getString("decision_reason"),
                r.getLong("row_version")));
        if (headers.isEmpty()) {
            return new PayrollSnapshot(scope.schoolId(), scope.schoolName(), scope.canConfigure(),
                    scope.canApprove(), staff, components, List.of());
        }
        List<UUID> profileIds = headers.stream().map(ProfileHeader::id).toList();
        Map<UUID, List<RawLine>> lines = loadLines(scope, profileIds);
        Map<UUID, List<HistoryItem>> history = loadHistory(scope, profileIds);
        List<SalaryProfileItem> profiles = headers.stream().map(header -> header.item(
                lines.getOrDefault(header.id(), List.of()), history.getOrDefault(header.id(), List.of())))
                .toList();
        return new PayrollSnapshot(scope.schoolId(), scope.schoolName(), scope.canConfigure(),
                scope.canApprove(), staff, components, profiles);
    }

    private Map<UUID, List<RawLine>> loadLines(Scope scope, List<UUID> profileIds) {
        return jdbc.query("""
                SELECT salary_profile_id,id,payroll_component_id,component_code_snapshot,
                       component_name_snapshot,component_kind_snapshot,calculation_method_snapshot,
                       is_basic_snapshot,attendance_prorated_snapshot,statutory_snapshot,
                       fixed_amount,percentage_rate
                FROM staff_salary_profile_lines
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND salary_profile_id IN (:profileIds)
                ORDER BY component_kind_snapshot,component_code_snapshot,id
                """, scope.params().addValue("profileIds", profileIds), result -> {
                    Map<UUID, List<RawLine>> grouped = new LinkedHashMap<>();
                    while (result.next()) {
                        UUID profileId = result.getObject("salary_profile_id", UUID.class);
                        grouped.computeIfAbsent(profileId, ignored -> new ArrayList<>()).add(new RawLine(
                                result.getObject("id", UUID.class),
                                result.getObject("payroll_component_id", UUID.class),
                                result.getString("component_code_snapshot"),
                                result.getString("component_name_snapshot"),
                                result.getString("component_kind_snapshot"),
                                result.getString("calculation_method_snapshot"),
                                result.getBoolean("is_basic_snapshot"),
                                result.getBoolean("attendance_prorated_snapshot"),
                                result.getBoolean("statutory_snapshot"), result.getBigDecimal("fixed_amount"),
                                result.getBigDecimal("percentage_rate")));
                    }
                    return grouped;
                });
    }

    private Map<UUID, List<HistoryItem>> loadHistory(Scope scope, List<UUID> profileIds) {
        return jdbc.query("""
                SELECT history.id,history.salary_profile_id,history.event_type,history.previous_status,
                       history.new_status,history.reason,actor_user.display_name AS actor_name,
                       history.occurred_at
                FROM staff_salary_profile_history history
                JOIN memberships actor ON actor.id=history.actor_membership_id
                  AND actor.organisation_id=history.organisation_id
                  AND actor.school_id=history.school_id
                JOIN user_accounts actor_user ON actor_user.id=actor.user_id
                WHERE history.organisation_id=:organisationId AND history.school_id=:schoolId
                  AND history.salary_profile_id IN (:profileIds)
                ORDER BY history.occurred_at,history.id
                """, scope.params().addValue("profileIds", profileIds), result -> {
                    Map<UUID, List<HistoryItem>> grouped = new LinkedHashMap<>();
                    while (result.next()) {
                        UUID profileId = result.getObject("salary_profile_id", UUID.class);
                        grouped.computeIfAbsent(profileId, ignored -> new ArrayList<>()).add(new HistoryItem(
                                result.getObject("id", UUID.class), result.getString("event_type"),
                                result.getString("previous_status"), result.getString("new_status"),
                                result.getString("reason"), result.getString("actor_name"),
                                result.getTimestamp("occurred_at").toInstant()));
                    }
                    return grouped;
                });
    }

    @Transactional
    public PayrollMutationResponse createComponent(AuthenticatedUser user, ComponentRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "payroll.configure");
        ComponentEvidence component = component(request);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        safe("PAYROLL_COMPONENT_CONFLICT", () -> jdbc.update("""
                INSERT INTO payroll_components(id,organisation_id,school_id,code,display_name,
                  component_kind,calculation_method,is_basic,attendance_prorated,statutory,
                  description,display_order,status,created_by_membership_id,created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:code,:name,:kind,:method,:basic,:prorated,
                  :statutory,:description,:displayOrder,:status,:actorMembershipId,:now,:now,0)
                """, scope.params().addValue("id", id).addValue("code", component.code())
                .addValue("name", component.name()).addValue("kind", component.kind())
                .addValue("method", component.method()).addValue("basic", component.basic())
                .addValue("prorated", component.prorated()).addValue("statutory", component.statutory())
                .addValue("description", component.description()).addValue("displayOrder", component.order())
                .addValue("status", component.status()).addValue("now", now)));
        audit(user, scope, "payroll.component.created", "PAYROLL_COMPONENT", id, request.reason());
        return new PayrollMutationResponse(id, 0);
    }

    @Transactional
    public PayrollMutationResponse updateComponent(AuthenticatedUser user, UUID id,
            ComponentRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "payroll.configure");
        ComponentEvidence component = component(request);
        long expected = version(request.expectedVersion());
        int updated = safe("PAYROLL_COMPONENT_CONFLICT", () -> jdbc.update("""
                UPDATE payroll_components SET code=:code,display_name=:name,component_kind=:kind,
                  calculation_method=:method,is_basic=:basic,attendance_prorated=:prorated,
                  statutory=:statutory,description=:description,display_order=:displayOrder,status=:status,
                  updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND row_version=:expected
                """, scope.params().addValue("id", id).addValue("code", component.code())
                .addValue("name", component.name()).addValue("kind", component.kind())
                .addValue("method", component.method()).addValue("basic", component.basic())
                .addValue("prorated", component.prorated()).addValue("statutory", component.statutory())
                .addValue("description", component.description()).addValue("displayOrder", component.order())
                .addValue("status", component.status()).addValue("expected", expected)
                .addValue("now", Instant.now(clock))));
        changed(updated);
        audit(user, scope, "payroll.component.updated", "PAYROLL_COMPONENT", id, request.reason());
        return new PayrollMutationResponse(id, expected + 1);
    }

    @Transactional
    public PayrollMutationResponse createSalaryProfile(AuthenticatedUser user,
            SalaryProfileRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "payroll.configure");
        requireEligibleStaff(user, scope, request.staffProfileId(), "payroll.configure",
                request.effectiveFrom());
        validateRevision(scope, request, null);
        List<LineEvidence> lines = validateLines(scope, request.lines());
        validateEstimate(lines);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        safe("SALARY_PROFILE_CONFLICT", () -> {
            jdbc.update("""
                    INSERT INTO staff_salary_profiles(id,organisation_id,school_id,staff_profile_id,
                      supersedes_profile_id,revision_number,effective_from,currency_code,pay_frequency,
                      status,creation_reason,created_by_membership_id,created_at,updated_at,row_version)
                    VALUES(:id,:organisationId,:schoolId,:staffId,:supersedesId,:revision,:effectiveFrom,
                      :currency,'MONTHLY','DRAFT',:reason,:actorMembershipId,:now,:now,0)
                    """, scope.params().addValue("id", id).addValue("staffId", request.staffProfileId())
                    .addValue("supersedesId", request.supersedesProfileId())
                    .addValue("revision", request.revisionNumber())
                    .addValue("effectiveFrom", request.effectiveFrom())
                    .addValue("currency", currency(request.currencyCode()))
                    .addValue("reason", reason(request.reason())).addValue("now", now));
            insertLines(scope, id, lines, now);
            return 1;
        });
        history(scope, id, "CREATED", null, "DRAFT", request.reason());
        audit(user, scope, "payroll.salary-profile.created", "STAFF_SALARY_PROFILE", id, request.reason());
        return new PayrollMutationResponse(id, 0);
    }

    @Transactional
    public PayrollMutationResponse updateSalaryProfile(AuthenticatedUser user, UUID id,
            SalaryProfileRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "payroll.configure");
        StoredProfile stored = lockProfile(scope, id);
        long expected = version(request.expectedVersion());
        if (!"DRAFT".equals(stored.status()) || !stored.creatorId().equals(scope.actorMembershipId())) {
            throw conflict("SALARY_PROFILE_NOT_EDITABLE", "Only the maker can edit this draft salary profile.");
        }
        if (stored.version() != expected || !stored.staffId().equals(request.staffProfileId())
                || stored.revision() != request.revisionNumber()
                || !Objects.equals(stored.supersedesId(), request.supersedesProfileId())) {
            throw conflict("STALE_VERSION", "The salary profile changed. Refresh and try again.");
        }
        requireEligibleStaff(user, scope, request.staffProfileId(), "payroll.configure",
                request.effectiveFrom());
        validateRevision(scope, request, id);
        List<LineEvidence> lines = validateLines(scope, request.lines());
        validateEstimate(lines);
        Instant now = Instant.now(clock);
        int count = safe("SALARY_PROFILE_CONFLICT", () -> {
            int changed = jdbc.update("""
                    UPDATE staff_salary_profiles SET effective_from=:effectiveFrom,currency_code=:currency,
                      pay_frequency='MONTHLY',creation_reason=:reason,updated_at=:now,row_version=row_version+1
                    WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                      AND status='DRAFT' AND row_version=:expected
                    """, scope.params().addValue("id", id).addValue("effectiveFrom", request.effectiveFrom())
                    .addValue("currency", currency(request.currencyCode())).addValue("reason", reason(request.reason()))
                    .addValue("now", now).addValue("expected", expected));
            if (changed == 1) {
                jdbc.update("""
                        DELETE FROM staff_salary_profile_lines
                        WHERE salary_profile_id=:id AND organisation_id=:organisationId
                          AND school_id=:schoolId
                        """,
                        scope.params().addValue("id", id));
                insertLines(scope, id, lines, now);
            }
            return changed;
        });
        changed(count);
        history(scope, id, "DRAFT_UPDATED", "DRAFT", "DRAFT", request.reason());
        audit(user, scope, "payroll.salary-profile.updated", "STAFF_SALARY_PROFILE", id, request.reason());
        return new PayrollMutationResponse(id, expected + 1);
    }

    @Transactional
    public PayrollMutationResponse submitSalaryProfile(AuthenticatedUser user, UUID id,
            VersionedReasonRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "payroll.configure");
        long expected = version(request.expectedVersion());
        StoredProfile stored = lockProfile(scope, id);
        if (!"DRAFT".equals(stored.status()) || !stored.creatorId().equals(scope.actorMembershipId())) {
            throw conflict("SALARY_PROFILE_NOT_SUBMITTABLE", "Only the maker can submit this draft.");
        }
        changed(jdbc.update("""
                UPDATE staff_salary_profiles SET status='SUBMITTED',submitted_by_membership_id=:actorMembershipId,
                  submitted_at=:now,submission_reason=:reason,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='DRAFT' AND row_version=:expected
                """, scope.params().addValue("id", id).addValue("now", Instant.now(clock))
                .addValue("reason", reason(request.reason())).addValue("expected", expected)));
        history(scope, id, "SUBMITTED", "DRAFT", "SUBMITTED", request.reason());
        audit(user, scope, "payroll.salary-profile.submitted", "STAFF_SALARY_PROFILE", id, request.reason());
        return new PayrollMutationResponse(id, expected + 1);
    }

    @Transactional
    public PayrollMutationResponse decideSalaryProfile(AuthenticatedUser user, UUID id,
            DecisionRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "payroll.approve");
        String decision = enumValue(request.decision(), Set.of("APPROVE", "REJECT"), "salary decision");
        long expected = version(request.expectedVersion());
        StoredProfile stored = lockProfile(scope, id);
        if (!"SUBMITTED".equals(stored.status()) || stored.version() != expected) {
            throw conflict("SALARY_PROFILE_NOT_DECIDABLE", "Refresh the submitted salary profile before deciding.");
        }
        if (stored.creatorId().equals(scope.actorMembershipId())) {
            throw forbidden("PAYROLL_MAKER_CHECKER_REQUIRED", "A different authorized person must decide this salary profile.");
        }
        Instant now = Instant.now(clock);
        if ("APPROVE".equals(decision) && stored.supersedesId() != null) {
            int superseded = jdbc.update("""
                    UPDATE staff_salary_profiles SET status='SUPERSEDED',updated_at=:now,row_version=row_version+1
                    WHERE id=:previousId AND organisation_id=:organisationId AND school_id=:schoolId
                      AND status='APPROVED'
                    """, scope.params().addValue("previousId", stored.supersedesId()).addValue("now", now));
            changed(superseded);
            history(scope, stored.supersedesId(), "SUPERSEDED", "APPROVED", "SUPERSEDED",
                    "Superseded by approved salary revision " + stored.revision() + ". " + reason(request.reason()));
        }
        String next = "APPROVE".equals(decision) ? "APPROVED" : "REJECTED";
        int updated = safe("SALARY_PROFILE_DECISION_CONFLICT", () -> jdbc.update("""
                UPDATE staff_salary_profiles SET status=:next,decided_by_membership_id=:actorMembershipId,
                  decided_at=:now,decision_reason=:reason,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='SUBMITTED' AND row_version=:expected
                """, scope.params().addValue("id", id).addValue("next", next).addValue("now", now)
                .addValue("reason", reason(request.reason())).addValue("expected", expected)));
        changed(updated);
        history(scope, id, next, "SUBMITTED", next, request.reason());
        audit(user, scope, "payroll.salary-profile." + next.toLowerCase(Locale.ROOT),
                "STAFF_SALARY_PROFILE", id, request.reason());
        return new PayrollMutationResponse(id, expected + 1);
    }

    private List<LineEvidence> validateLines(Scope scope, List<SalaryLineRequest> requested) {
        if (requested == null || requested.isEmpty() || requested.size() > 100) {
            throw bad("SALARY_LINES_INVALID", "Use between 1 and 100 salary components.");
        }
        Set<UUID> ids = requested.stream().map(SalaryLineRequest::payrollComponentId)
                .collect(Collectors.toSet());
        if (ids.size() != requested.size()) {
            throw bad("SALARY_COMPONENT_DUPLICATE", "Each component can appear only once in a salary profile.");
        }
        Map<UUID, ComponentEvidence> components = jdbc.query("""
                SELECT id,code,display_name,component_kind,calculation_method,is_basic,
                       attendance_prorated,statutory,description,display_order,status
                FROM payroll_components
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND id IN (:ids)
                """, scope.params().addValue("ids", ids), result -> {
                    Map<UUID, ComponentEvidence> values = new LinkedHashMap<>();
                    while (result.next()) {
                        UUID componentId = result.getObject("id", UUID.class);
                        values.put(componentId, new ComponentEvidence(componentId, result.getString("code"),
                                result.getString("display_name"), result.getString("component_kind"),
                                result.getString("calculation_method"), result.getBoolean("is_basic"),
                                result.getBoolean("attendance_prorated"), result.getBoolean("statutory"),
                                result.getString("description"), result.getInt("display_order"),
                                result.getString("status")));
                    }
                    return values;
                });
        if (components.size() != ids.size()) {
            throw bad("SALARY_COMPONENT_SCOPE_INVALID", "Choose payroll components from this school.");
        }
        List<LineEvidence> lines = new ArrayList<>();
        for (SalaryLineRequest input : requested) {
            ComponentEvidence component = components.get(input.payrollComponentId());
            if (!"ACTIVE".equals(component.status())) {
                throw bad("SALARY_COMPONENT_INACTIVE", "Salary profiles require active components.");
            }
            BigDecimal amount = input.fixedAmount();
            BigDecimal rate = input.percentageRate();
            if ("FIXED".equals(component.method())) {
                amount = moneyInput(amount, "fixed amount");
                if (rate != null) throw bad("SALARY_LINE_INPUT_INVALID", "Fixed components cannot include a percentage.");
            } else {
                if (amount != null || rate == null || rate.signum() <= 0
                        || rate.compareTo(new BigDecimal("1000")) > 0 || rate.scale() > 4) {
                    throw bad("SALARY_LINE_INPUT_INVALID", "Percentage components require a valid rate only.");
                }
                rate = rate.setScale(4, RoundingMode.UNNECESSARY);
            }
            lines.add(new LineEvidence(component, amount, rate));
        }
        return List.copyOf(lines);
    }

    private void validateEstimate(List<LineEvidence> lines) {
        long basics = lines.stream().filter(line -> line.component().basic()).count();
        if (basics != 1) throw bad("SALARY_BASIC_REQUIRED", "Choose exactly one active basic earning component.");
        BigDecimal basic = lines.stream().filter(line -> line.component().basic()).findFirst()
                .orElseThrow().amount();
        BigDecimal gross = total(lines, "EARNING", basic);
        BigDecimal deductions = total(lines, "DEDUCTION", basic);
        if (gross.signum() <= 0 || deductions.compareTo(gross) > 0) {
            throw bad("SALARY_NET_INVALID", "Configured deductions cannot exceed earnings.");
        }
    }

    private static BigDecimal total(List<LineEvidence> lines, String kind, BigDecimal basic) {
        return lines.stream().filter(line -> kind.equals(line.component().kind()))
                .map(line -> line.estimate(basic)).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
    }

    private void insertLines(Scope scope, UUID profileId, List<LineEvidence> lines, Instant now) {
        for (LineEvidence line : lines) {
            ComponentEvidence component = line.component();
            jdbc.update("""
                    INSERT INTO staff_salary_profile_lines(id,organisation_id,school_id,salary_profile_id,
                      payroll_component_id,component_code_snapshot,component_name_snapshot,
                      component_kind_snapshot,calculation_method_snapshot,is_basic_snapshot,
                      attendance_prorated_snapshot,statutory_snapshot,fixed_amount,percentage_rate,created_at)
                    VALUES(:id,:organisationId,:schoolId,:profileId,:componentId,:code,:name,:kind,
                      :method,:basic,:prorated,:statutory,:amount,:rate,:now)
                    """, scope.params().addValue("id", UUID.randomUUID()).addValue("profileId", profileId)
                    .addValue("componentId", component.id()).addValue("code", component.code())
                    .addValue("name", component.name()).addValue("kind", component.kind())
                    .addValue("method", component.method()).addValue("basic", component.basic())
                    .addValue("prorated", component.prorated()).addValue("statutory", component.statutory())
                    .addValue("amount", line.amount()).addValue("rate", line.rate()).addValue("now", now));
        }
    }

    private void validateRevision(Scope scope, SalaryProfileRequest request, UUID editingId) {
        enumValue(request.payFrequency(), Set.of("MONTHLY"), "pay frequency");
        if (request.supersedesProfileId() == null) {
            Integer count = jdbc.queryForObject("""
                    SELECT count(*) FROM staff_salary_profiles WHERE staff_profile_id=:staffId
                      AND organisation_id=:organisationId AND school_id=:schoolId
                      AND status<>'REJECTED'
                      AND (CAST(:editingId AS uuid) IS NULL OR id<>:editingId)
                    """, scope.params().addValue("staffId", request.staffProfileId())
                    .addValue("editingId", editingId), Integer.class);
            if (request.revisionNumber() != 1 || count != 0) {
                throw bad("SALARY_REVISION_INVALID", "A first salary profile must be revision 1 with no predecessor.");
            }
            return;
        }
        List<PreviousProfile> rows = jdbc.query("""
                SELECT staff_profile_id,revision_number,effective_from,status
                FROM staff_salary_profiles WHERE id=:previousId AND organisation_id=:organisationId
                  AND school_id=:schoolId
                """, scope.params().addValue("previousId", request.supersedesProfileId()),
                (r, n) -> new PreviousProfile(r.getObject("staff_profile_id", UUID.class),
                        r.getInt("revision_number"), r.getObject("effective_from", LocalDate.class),
                        r.getString("status")));
        if (rows.isEmpty()) throw bad("SALARY_REVISION_INVALID", "Choose the current approved salary profile.");
        PreviousProfile previous = rows.getFirst();
        if (!previous.staffId().equals(request.staffProfileId())
                || !"APPROVED".equals(previous.status())
                || request.revisionNumber() != previous.revision() + 1
                || !request.effectiveFrom().isAfter(previous.effectiveFrom())) {
            throw bad("SALARY_REVISION_INVALID", "A revision must follow the current approved profile and effective date.");
        }
    }

    private PayrollStaff requireEligibleStaff(AuthenticatedUser user, Scope scope, UUID staffId,
            String capability, LocalDate effectiveFrom) {
        PayrollStaff staff = people.payrollStaff(user, scope.schoolId(), capability).stream()
                .filter(item -> item.id().equals(staffId)).findFirst()
                .orElseThrow(() -> bad("PAYROLL_STAFF_SCOPE_INVALID", "Choose staff from this school."));
        if ("ENDED".equals(staff.status()) || effectiveFrom.isBefore(staff.joinedOn())
                || (staff.endedOn() != null && effectiveFrom.isAfter(staff.endedOn()))) {
            throw bad("PAYROLL_STAFF_NOT_ELIGIBLE", "The effective date must fall within the staff employment period.");
        }
        return staff;
    }

    private StoredProfile lockProfile(Scope scope, UUID id) {
        List<StoredProfile> rows = jdbc.query("""
                SELECT id,staff_profile_id,supersedes_profile_id,revision_number,status,
                       created_by_membership_id,row_version
                FROM staff_salary_profiles WHERE id=:id AND organisation_id=:organisationId
                  AND school_id=:schoolId FOR UPDATE
                """, scope.params().addValue("id", id), (r, n) -> new StoredProfile(
                r.getObject("id", UUID.class), r.getObject("staff_profile_id", UUID.class),
                r.getObject("supersedes_profile_id", UUID.class), r.getInt("revision_number"),
                r.getString("status"), r.getObject("created_by_membership_id", UUID.class),
                r.getLong("row_version")));
        if (rows.isEmpty()) throw notFound("SALARY_PROFILE_NOT_FOUND", "The salary profile was not found.");
        return rows.getFirst();
    }

    private Scope requireScope(AuthenticatedUser user, UUID requestedSchoolId) {
        if (user.activeOrganisationId() == null) {
            throw conflict("ORGANISATION_SELECTION_REQUIRED", "Select a school organisation first.");
        }
        List<Scope> rows = jdbc.query("""
                SELECT school.id AS school_id,school.display_name,membership.id AS membership_id,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='payroll.configure') can_configure,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='payroll.approve') can_approve
                FROM memberships membership JOIN schools school ON school.id=membership.school_id
                WHERE membership.user_id=:userId AND membership.organisation_id=:organisationId
                  AND membership.id IN (:membershipIds) AND membership.status='ACTIVE'
                  AND membership.valid_from<=CURRENT_TIMESTAMP
                  AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                  AND (CAST(:schoolId AS uuid) IS NULL OR membership.school_id=:schoolId)
                  AND EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='payroll.read')
                ORDER BY membership.created_at,membership.id LIMIT 1
                """, new MapSqlParameterSource().addValue("userId", user.userId())
                .addValue("organisationId", user.activeOrganisationId())
                .addValue("membershipIds", user.membershipIds()).addValue("schoolId", requestedSchoolId),
                (r, n) -> new Scope(user.activeOrganisationId(), r.getObject("school_id", UUID.class),
                        r.getString("display_name"), r.getObject("membership_id", UUID.class),
                        r.getBoolean("can_configure"), r.getBoolean("can_approve")));
        if (rows.isEmpty()) throw forbidden("PAYROLL_SCOPE_DENIED", "Payroll access is unavailable for this school.");
        return rows.getFirst();
    }

    private Scope requireCapability(AuthenticatedUser user, UUID schoolId, String capability) {
        Scope scope = requireScope(user, schoolId);
        if (!scope.has(capability)) {
            throw forbidden("PAYROLL_CAPABILITY_DENIED", "This payroll action is outside your authorized role.");
        }
        return scope;
    }

    private void history(Scope scope, UUID profileId, String event, String previous,
            String next, String changeReason) {
        jdbc.update("""
                INSERT INTO staff_salary_profile_history(id,organisation_id,school_id,salary_profile_id,
                  event_type,previous_status,new_status,reason,actor_membership_id,occurred_at)
                VALUES(:id,:organisationId,:schoolId,:profileId,:event,:previous,:next,:reason,
                  :actorMembershipId,:now)
                """, scope.params().addValue("id", UUID.randomUUID()).addValue("profileId", profileId)
                .addValue("event", event).addValue("previous", previous).addValue("next", next)
                .addValue("reason", reason(changeReason)).addValue("now", Instant.now(clock)));
    }

    private void audit(AuthenticatedUser user, Scope scope, String action, String type,
            UUID id, String changeReason) {
        audit.recordScoped(user, scope.actorMembershipId(), scope.schoolId(), action, type,
                id.toString(), AuditOutcome.SUCCEEDED, clean(changeReason), Map.of());
    }

    private static ComponentEvidence component(ComponentRequest request) {
        String kind = enumValue(request.componentKind(), KINDS, "component kind");
        String method = enumValue(request.calculationMethod(), METHODS, "calculation method");
        String status = enumValue(request.status(), STATUSES, "component status");
        if (request.basic() && (!"EARNING".equals(kind) || !"FIXED".equals(method) || request.statutory())) {
            throw bad("PAYROLL_BASIC_INVALID", "Basic salary must be a fixed, non-statutory earning.");
        }
        if (request.attendanceProrated() && !"EARNING".equals(kind)) {
            throw bad("PAYROLL_PRORATION_INVALID", "Only earning components can be attendance-prorated.");
        }
        return new ComponentEvidence(null, code(request.code()), required(request.displayName()), kind,
                method, request.basic(), request.attendanceProrated(), request.statutory(),
                clean(request.description()), request.displayOrder(), status);
    }

    private static String code(String value) {
        String normalized = required(value).toUpperCase(Locale.ROOT);
        if (!normalized.matches("^[A-Z][A-Z0-9_]{1,39}$")) {
            throw bad("PAYROLL_CODE_INVALID", "Use letters, numbers, and underscores, starting with a letter.");
        }
        return normalized;
    }

    private static String currency(String value) {
        String normalized = required(value).toUpperCase(Locale.ROOT);
        if (!normalized.matches("^[A-Z]{3}$")) {
            throw bad("PAYROLL_CURRENCY_INVALID", "Use a three-letter currency code.");
        }
        return normalized;
    }

    private static BigDecimal moneyInput(BigDecimal value, String label) {
        if (value == null || value.signum() <= 0 || value.compareTo(new BigDecimal("1000000000000")) > 0
                || value.stripTrailingZeros().scale() > 2) {
            throw bad("SALARY_LINE_INPUT_INVALID",
                    "Enter a valid positive " + label + " with at most two decimals.");
        }
        return value.setScale(2);
    }

    private static String enumValue(String value, Set<String> allowed, String label) {
        String normalized = required(value).toUpperCase(Locale.ROOT).replace(' ', '_');
        if (!allowed.contains(normalized)) throw bad("INVALID_VALUE", "Choose a supported " + label + ".");
        return normalized;
    }

    private static String required(String value) {
        String cleaned = clean(value);
        if (cleaned == null) throw bad("REQUIRED_VALUE", "Complete all required payroll fields.");
        return cleaned;
    }

    private static String reason(String value) {
        String why = required(value);
        if (why.length() < 3 || why.length() > 500) {
            throw bad("PAYROLL_REASON_INVALID", "Enter a reason between 3 and 500 characters.");
        }
        return why;
    }

    private static String clean(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static long version(Long value) {
        if (value == null || value < 0) throw bad("EXPECTED_VERSION_REQUIRED", "Refresh and try again.");
        return value;
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static <T> T safe(String code, Work<T> work) {
        try {
            return work.run();
        } catch (DataIntegrityViolationException exception) {
            throw conflict(code, "The payroll change conflicts with protected salary history.");
        }
    }

    private static void changed(int count) {
        if (count != 1) throw conflict("STALE_VERSION", "The record changed. Refresh and try again.");
    }

    private static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    private static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private record Scope(UUID organisationId, UUID schoolId, String schoolName,
            UUID actorMembershipId, boolean canConfigure, boolean canApprove) {
        MapSqlParameterSource params() {
            return new MapSqlParameterSource().addValue("organisationId", organisationId)
                    .addValue("schoolId", schoolId).addValue("actorMembershipId", actorMembershipId);
        }

        boolean has(String capability) {
            return switch (capability) {
                case "payroll.configure" -> canConfigure;
                case "payroll.approve" -> canApprove;
                default -> false;
            };
        }
    }

    private record ComponentEvidence(UUID id, String code, String name, String kind, String method,
            boolean basic, boolean prorated, boolean statutory, String description, int order,
            String status) {}

    private record LineEvidence(ComponentEvidence component, BigDecimal amount, BigDecimal rate) {
        BigDecimal estimate(BigDecimal basic) {
            return "FIXED".equals(component.method()) ? amount
                    : basic.multiply(rate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        }
    }

    private record RawLine(UUID id, UUID componentId, String code, String name, String kind,
            String method, boolean basic, boolean prorated, boolean statutory,
            BigDecimal amount, BigDecimal rate) {
        SalaryLineItem item(BigDecimal basicAmount) {
            BigDecimal estimate = "FIXED".equals(method) ? amount
                    : basicAmount.multiply(rate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            return new SalaryLineItem(id, componentId, code, name, kind, method, basic, prorated,
                    statutory, amount, rate, estimate);
        }
    }

    private record ProfileHeader(UUID id, UUID staffId, UUID supersedesId, int revision,
            LocalDate effectiveFrom, String currency, String frequency, String status,
            String creationReason, String creatorName, String submitterName, Instant submittedAt,
            String deciderName, Instant decidedAt, String decisionReason, long version) {
        SalaryProfileItem item(List<RawLine> rawLines, List<HistoryItem> history) {
            BigDecimal basic = rawLines.stream().filter(RawLine::basic).findFirst()
                    .map(RawLine::amount).orElse(BigDecimal.ZERO);
            List<SalaryLineItem> lines = rawLines.stream().map(line -> line.item(basic)).toList();
            BigDecimal gross = lines.stream().filter(line -> "EARNING".equals(line.componentKind()))
                    .map(SalaryLineItem::estimatedAmount).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
            BigDecimal deductions = lines.stream().filter(line -> "DEDUCTION".equals(line.componentKind()))
                    .map(SalaryLineItem::estimatedAmount).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
            BigDecimal employer = lines.stream()
                    .filter(line -> "EMPLOYER_CONTRIBUTION".equals(line.componentKind()))
                    .map(SalaryLineItem::estimatedAmount).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
            return new SalaryProfileItem(id, staffId, supersedesId, revision, effectiveFrom, currency,
                    frequency, status, creationReason, creatorName, submitterName, submittedAt,
                    deciderName, decidedAt, decisionReason, gross, deductions, employer,
                    gross.subtract(deductions).setScale(2), version, lines, history);
        }
    }

    private record PreviousProfile(UUID staffId, int revision, LocalDate effectiveFrom, String status) {}
    private record StoredProfile(UUID id, UUID staffId, UUID supersedesId, int revision,
            String status, UUID creatorId, long version) {}

    @FunctionalInterface
    private interface Work<T> {
        T run();
    }
}
