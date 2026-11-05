package com.educore.api.people;

import static com.educore.api.people.PeopleController.*;
import static com.educore.api.people.PeopleSnapshot.*;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PeopleService implements PeoplePayrollBridge {
    private static final Set<String> ACTIVE_STATUSES = Set.of("ACTIVE", "INACTIVE");
    private static final Set<String> STAFF_CATEGORIES = Set.of(
            "LEADERSHIP", "TEACHING", "ADMINISTRATION", "FINANCE", "TRANSPORT",
            "OPERATIONS", "SUPPORT", "HEALTH", "OTHER");
    private static final Set<String> CBSE_CATEGORIES = Set.of(
            "PRINCIPAL", "VICE_PRINCIPAL", "HEAD", "PGT", "TGT", "PRT",
            "SPECIAL_EDUCATOR", "COUNSELLOR_WELLNESS", "OTHER_TEACHING", "NON_TEACHING");
    private static final Set<String> EMPLOYMENT_TYPES = Set.of(
            "FULL_TIME", "PART_TIME", "FIXED_TERM", "CONTRACT", "OUTSOURCED", "VOLUNTEER");
    private static final Set<String> EMPLOYERS = Set.of("SCHOOL", "AGENCY");
    private static final Set<String> QUALIFICATION_LEVELS = Set.of(
            "CERTIFICATE", "DIPLOMA", "GRADUATE", "POSTGRADUATE", "DOCTORATE", "PROFESSIONAL", "OTHER");

    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final Clock clock;

    public PeopleService(NamedParameterJdbcTemplate jdbc, SecurityAuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PayrollStaff> payrollStaff(AuthenticatedUser user, UUID requestedSchoolId,
            String requiredCapability) {
        Scope scope = requireScope(user, requestedSchoolId, requiredCapability);
        return jdbc.query("""
                SELECT staff.id,staff.employee_code,staff.display_name,staff.joined_on,staff.ended_on,
                       staff.status,designation.display_name AS designation_name,
                       department.display_name AS department_name
                FROM staff_profiles staff
                LEFT JOIN LATERAL (
                    SELECT assignment.staff_designation_id,assignment.staff_department_id
                    FROM staff_assignments assignment
                    WHERE assignment.staff_profile_id=staff.id
                      AND assignment.organisation_id=staff.organisation_id
                      AND assignment.school_id=staff.school_id
                      AND assignment.primary_assignment
                      AND assignment.status IN ('ACTIVE','PLANNED')
                    ORDER BY CASE assignment.status WHEN 'ACTIVE' THEN 0 ELSE 1 END,
                             assignment.starts_on DESC,assignment.id
                    LIMIT 1
                ) current_assignment ON true
                LEFT JOIN staff_designations designation
                  ON designation.id=current_assignment.staff_designation_id
                LEFT JOIN staff_departments department
                  ON department.id=current_assignment.staff_department_id
                WHERE staff.organisation_id=:organisationId AND staff.school_id=:schoolId
                ORDER BY CASE staff.status WHEN 'ACTIVE' THEN 0 WHEN 'INACTIVE' THEN 1 ELSE 2 END,
                         staff.display_name,staff.id
                """, scope.params(), (r, n) -> new PayrollStaff(r.getObject("id", UUID.class),
                r.getString("employee_code"), r.getString("display_name"),
                r.getObject("joined_on", LocalDate.class), r.getObject("ended_on", LocalDate.class),
                r.getString("status"), r.getString("designation_name"),
                r.getString("department_name")));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, PayrollAssignmentSnapshot> payrollAssignmentSnapshots(AuthenticatedUser user,
            UUID schoolId, LocalDate periodEnd, String requiredCapability) {
        Scope scope = requireScope(user, schoolId, requiredCapability);
        Objects.requireNonNull(periodEnd, "Payroll period end is required");
        return Map.copyOf(jdbc.query("""
                SELECT staff.id,LEAST(:periodEnd,coalesce(staff.ended_on,:periodEnd)) snapshot_date,
                       evidence.match_count,evidence.assignment_id,evidence.designation_name,evidence.department_name
                FROM staff_profiles staff
                LEFT JOIN LATERAL (
                  SELECT count(*) match_count,(array_agg(a.id))[1] assignment_id,
                    (array_agg(d.display_name))[1] designation_name,(array_agg(department.display_name))[1] department_name
                  FROM staff_assignments a
                  JOIN staff_designations d ON d.id=a.staff_designation_id
                    AND d.organisation_id=a.organisation_id AND d.school_id=a.school_id
                  LEFT JOIN staff_departments department ON department.id=a.staff_department_id
                    AND department.organisation_id=a.organisation_id AND department.school_id=a.school_id
                  WHERE a.staff_profile_id=staff.id AND a.organisation_id=staff.organisation_id AND a.school_id=staff.school_id
                    AND a.primary_assignment AND a.status IN ('ACTIVE','ENDED')
                    AND a.starts_on<=LEAST(:periodEnd,coalesce(staff.ended_on,:periodEnd))
                    AND (a.ends_on IS NULL OR a.ends_on>=LEAST(:periodEnd,coalesce(staff.ended_on,:periodEnd)))
                ) evidence ON true
                WHERE staff.organisation_id=:organisationId AND staff.school_id=:schoolId
                  AND staff.joined_on<=:periodEnd
                """, scope.params().addValue("periodEnd", periodEnd), result -> {
            Map<UUID, PayrollAssignmentSnapshot> snapshots = new LinkedHashMap<>();
            while (result.next()) {
                long count = result.getLong("match_count");
                snapshots.put(result.getObject("id", UUID.class), new PayrollAssignmentSnapshot(
                        result.getObject("snapshot_date", LocalDate.class),
                        count == 1 ? "CAPTURED" : count == 0 ? "NO_ASSIGNMENT" : "AMBIGUOUS",
                        count == 1 ? result.getObject("assignment_id", UUID.class) : null,
                        count == 1 ? result.getString("designation_name") : null,
                        count == 1 ? result.getString("department_name") : null));
            }
            return snapshots;
        }));
    }

    @Transactional(readOnly = true)
    public PeopleSnapshot snapshot(AuthenticatedUser user, UUID requestedSchoolId) {
        Scope scope = requireScope(user, requestedSchoolId, "people.read");
        MapSqlParameterSource p = scope.params();
        List<CampusItem> campuses = jdbc.query("""
                SELECT id,display_name,status FROM campuses
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY CASE status WHEN 'ACTIVE' THEN 0 ELSE 1 END,display_name,id
                """, p, (r, n) -> new CampusItem(r.getObject("id", UUID.class),
                r.getString("display_name"), r.getString("status")));
        List<AccountItem> accounts = jdbc.query("""
                SELECT account.id AS user_id,membership.id AS membership_id,account.display_name,
                  account.email_normalized,membership.status,
                  string_agg(role.code,',' ORDER BY role.code) AS roles
                FROM memberships membership
                JOIN user_accounts account ON account.id=membership.user_id
                JOIN membership_roles membership_role ON membership_role.membership_id=membership.id
                JOIN roles role ON role.id=membership_role.role_id
                WHERE membership.organisation_id=:organisationId AND membership.school_id=:schoolId
                  AND account.status IN ('ACTIVE','INVITED')
                GROUP BY account.id,membership.id,account.display_name,account.email_normalized,membership.status
                ORDER BY account.display_name,membership.id
                """, p, (r, n) -> new AccountItem(r.getObject("user_id", UUID.class),
                r.getObject("membership_id", UUID.class), r.getString("display_name"),
                r.getString("email_normalized"), split(r.getString("roles")), r.getString("status")));
        List<DepartmentItem> departments = jdbc.query("""
                SELECT id,code,display_name,description,status,row_version FROM staff_departments
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY CASE status WHEN 'ACTIVE' THEN 0 ELSE 1 END,display_name,id
                """, p, (r, n) -> new DepartmentItem(r.getObject("id", UUID.class), r.getString("code"),
                r.getString("display_name"), r.getString("description"), r.getString("status"),
                r.getLong("row_version")));
        List<DesignationItem> designations = jdbc.query("""
                SELECT id,code,display_name,staff_category,cbse_staff_category,teaching_role,
                  leadership_role,eligible_as_transport_driver,eligible_as_transport_attendant,
                  description,display_order,status,row_version FROM staff_designations
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY CASE status WHEN 'ACTIVE' THEN 0 ELSE 1 END,display_order,display_name,id
                """, p, (r, n) -> new DesignationItem(r.getObject("id", UUID.class), r.getString("code"),
                r.getString("display_name"), r.getString("staff_category"), r.getString("cbse_staff_category"),
                r.getBoolean("teaching_role"), r.getBoolean("leadership_role"),
                r.getBoolean("eligible_as_transport_driver"), r.getBoolean("eligible_as_transport_attendant"),
                r.getString("description"),
                r.getInt("display_order"), r.getString("status"), r.getLong("row_version")));
        List<StaffItem> staff = jdbc.query("""
                SELECT id,employee_code,legal_name,display_name,primary_language,work_email_normalized,
                  work_phone,user_account_id,joined_on,status,ended_on,ending_reason,row_version
                FROM staff_profiles WHERE organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY CASE status WHEN 'ACTIVE' THEN 0 WHEN 'INACTIVE' THEN 1 ELSE 2 END,display_name,id
                """, p, (r, n) -> new StaffItem(r.getObject("id", UUID.class), r.getString("employee_code"),
                r.getString("legal_name"), r.getString("display_name"), r.getString("primary_language"),
                r.getString("work_email_normalized"), r.getString("work_phone"),
                r.getObject("user_account_id", UUID.class), r.getObject("joined_on", LocalDate.class),
                r.getString("status"), r.getObject("ended_on", LocalDate.class), r.getString("ending_reason"),
                r.getLong("row_version")));
        List<AssignmentItem> assignments = jdbc.query("""
                SELECT id,staff_profile_id,staff_designation_id,staff_department_id,campus_id,
                  employment_type,employer_kind,agency_name,primary_assignment,starts_on,ends_on,
                  status,ending_reason,row_version FROM staff_assignments
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY starts_on DESC,id
                """, p, (r, n) -> new AssignmentItem(r.getObject("id", UUID.class),
                r.getObject("staff_profile_id", UUID.class), r.getObject("staff_designation_id", UUID.class),
                r.getObject("staff_department_id", UUID.class), r.getObject("campus_id", UUID.class),
                r.getString("employment_type"), r.getString("employer_kind"), r.getString("agency_name"),
                r.getBoolean("primary_assignment"), r.getObject("starts_on", LocalDate.class),
                r.getObject("ends_on", LocalDate.class), r.getString("status"), r.getString("ending_reason"),
                r.getLong("row_version")));
        List<QualificationItem> qualifications = scope.canCompliance() ? jdbc.query("""
                SELECT id,staff_profile_id,qualification_name,qualification_level,subject_specialisation,
                  institution_name,awarding_body,completed_year,verification_status,public_summary_eligible,
                  verification_reason,row_version FROM staff_qualifications
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY created_at DESC,id
                """, p, (r, n) -> new QualificationItem(r.getObject("id", UUID.class),
                r.getObject("staff_profile_id", UUID.class), r.getString("qualification_name"),
                r.getString("qualification_level"), r.getString("subject_specialisation"),
                r.getString("institution_name"), r.getString("awarding_body"),
                (Integer) r.getObject("completed_year"), r.getString("verification_status"),
                r.getBoolean("public_summary_eligible"), r.getString("verification_reason"),
                r.getLong("row_version"))) : List.of();
        List<ComplianceTypeItem> types = jdbc.query("""
                SELECT id,code,display_name,description,applicable_staff_category,validity_months,
                  required_for_active_service,status,row_version FROM staff_compliance_types
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY CASE status WHEN 'ACTIVE' THEN 0 ELSE 1 END,display_name,id
                """, p, (r, n) -> new ComplianceTypeItem(r.getObject("id", UUID.class),
                r.getString("code"), r.getString("display_name"), r.getString("description"),
                r.getString("applicable_staff_category"), (Integer) r.getObject("validity_months"),
                r.getBoolean("required_for_active_service"), r.getString("status"), r.getLong("row_version")));
        List<ComplianceItem> checks = scope.canCompliance() ? jdbc.query("""
                SELECT id,staff_profile_id,staff_compliance_type_id,status,completed_on,valid_until,
                  safe_reference,restricted_note,decision_reason,row_version FROM staff_compliance_checks
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY created_at DESC,id
                """, p, (r, n) -> new ComplianceItem(r.getObject("id", UUID.class),
                r.getObject("staff_profile_id", UUID.class), r.getObject("staff_compliance_type_id", UUID.class),
                r.getString("status"), r.getObject("completed_on", LocalDate.class),
                r.getObject("valid_until", LocalDate.class), r.getString("safe_reference"),
                r.getString("restricted_note"), r.getString("decision_reason"), r.getLong("row_version"))) : List.of();
        List<PublicProfileItem> publicProfiles = jdbc.query("""
                SELECT profile.*,creator.display_name AS creator_name,decider.display_name AS decider_name
                FROM staff_public_profile_revisions profile
                JOIN memberships creator_membership ON creator_membership.id=profile.created_by_membership_id
                JOIN user_accounts creator ON creator.id=creator_membership.user_id
                LEFT JOIN memberships decider_membership ON decider_membership.id=profile.decided_by_membership_id
                LEFT JOIN user_accounts decider ON decider.id=decider_membership.user_id
                WHERE profile.organisation_id=:organisationId AND profile.school_id=:schoolId
                ORDER BY profile.created_at DESC,profile.id
                """, p, (r, n) -> new PublicProfileItem(r.getObject("id", UUID.class),
                r.getObject("staff_profile_id", UUID.class), r.getInt("revision_number"),
                r.getObject("supersedes_revision_id", UUID.class), r.getString("public_display_name"),
                r.getString("designation_snapshot"), r.getString("qualification_summary"),
                r.getString("short_bio"), r.getString("cbse_staff_category_snapshot"),
                r.getInt("display_order"), r.getString("status"), r.getString("creator_name"),
                r.getString("decider_name"), r.getString("decision_reason"), instant(r, "published_at"),
                r.getLong("row_version")));
        return new PeopleSnapshot(scope.schoolId(), scope.schoolName(), scope.canManage(),
                scope.canCompliance(), scope.canPublish(), campuses, accounts, departments,
                designations, staff, assignments, qualifications, types, checks, publicProfiles);
    }

    @Transactional
    public PeopleMutationResponse createDepartment(AuthenticatedUser user, DepartmentRequest request) {
        Scope s = requireScope(user, request.schoolId(), "people.manage");
        UUID id = UUID.randomUUID(); Instant now = Instant.now(clock);
        safe("DEPARTMENT_EXISTS", () -> jdbc.update("""
                INSERT INTO staff_departments(id,organisation_id,school_id,code,display_name,description,
                  status,created_by_membership_id,created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:code,:name,:description,:status,
                  :actorMembershipId,:now,:now,0)
                """, configParams(s, id, request.code(), request.name(), request.description(), request.status(), now)));
        audit(user, s, "people.department.created", "STAFF_DEPARTMENT", id, clean(request.reason()));
        return new PeopleMutationResponse(id, 0);
    }

    @Transactional
    public PeopleMutationResponse updateDepartment(AuthenticatedUser user, UUID id, DepartmentRequest request) {
        Scope s = requireScope(user, request.schoolId(), "people.manage"); long version = version(request.expectedVersion());
        int changed = safe("DEPARTMENT_EXISTS", () -> jdbc.update("""
                UPDATE staff_departments SET display_name=:name,description=:description,status=:status,
                  updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND code=:code AND row_version=:version
                """, configParams(s, id, request.code(), request.name(), request.description(), request.status(),
                        Instant.now(clock)).addValue("version", version)));
        changed(changed); audit(user, s, "people.department.updated", "STAFF_DEPARTMENT", id, reason(request.reason()));
        return new PeopleMutationResponse(id, version + 1);
    }

    @Transactional
    public PeopleMutationResponse createDesignation(AuthenticatedUser user, DesignationRequest request) {
        Scope s = requireScope(user, request.schoolId(), "people.manage"); UUID id = UUID.randomUUID();
        safe("DESIGNATION_EXISTS", () -> jdbc.update("""
                INSERT INTO staff_designations(id,organisation_id,school_id,code,display_name,staff_category,
                  cbse_staff_category,teaching_role,leadership_role,eligible_as_transport_driver,
                  eligible_as_transport_attendant,description,display_order,status,
                  created_by_membership_id,created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:code,:name,:category,:cbse,:teaching,:leadership,
                  :transportDriver,:transportAttendant,:description,:displayOrder,:status,
                  :actorMembershipId,:now,:now,0)
                """, designationParams(s, id, request, null)));
        audit(user, s, "people.designation.created", "STAFF_DESIGNATION", id, clean(request.reason()));
        return new PeopleMutationResponse(id, 0);
    }

    @Transactional
    public PeopleMutationResponse updateDesignation(AuthenticatedUser user, UUID id, DesignationRequest request) {
        Scope s = requireScope(user, request.schoolId(), "people.manage"); long version = version(request.expectedVersion());
        int changed = safe("DESIGNATION_EXISTS", () -> jdbc.update("""
                UPDATE staff_designations SET display_name=:name,staff_category=:category,cbse_staff_category=:cbse,
                  teaching_role=:teaching,leadership_role=:leadership,
                  eligible_as_transport_driver=:transportDriver,
                  eligible_as_transport_attendant=:transportAttendant,description=:description,
                  display_order=:displayOrder,status=:status,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND code=:code AND row_version=:version
                """, designationParams(s, id, request, version)));
        changed(changed); audit(user, s, "people.designation.updated", "STAFF_DESIGNATION", id, reason(request.reason()));
        return new PeopleMutationResponse(id, version + 1);
    }

    @Transactional
    public PeopleMutationResponse createStaff(AuthenticatedUser user, StaffRequest request) {
        Scope s = requireScope(user, request.schoolId(), "people.manage"); validateAccount(s, request.userAccountId(), null);
        String status = enumValue(request.status(), ACTIVE_STATUSES, "staff status");
        UUID id = UUID.randomUUID(); Instant now = Instant.now(clock);
        safe("STAFF_PROFILE_EXISTS", () -> jdbc.update("""
                INSERT INTO staff_profiles(id,organisation_id,school_id,employee_code,legal_name,display_name,
                  primary_language,work_email_normalized,work_phone,user_account_id,joined_on,status,
                  created_by_membership_id,created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:code,:legalName,:displayName,:language,:email,:phone,
                  :userAccountId,:joinedOn,:status,:actorMembershipId,:now,:now,0)
                """, staffParams(s, id, request, null).addValue("status", status).addValue("now", now)));
        history(s, id, id, "PROFILE_CREATED", null, status, clean(request.reason(), "Staff profile created"));
        audit(user, s, "people.staff.created", "STAFF_PROFILE", id, clean(request.reason()));
        return new PeopleMutationResponse(id, 0);
    }

    @Transactional
    public PeopleMutationResponse updateStaff(AuthenticatedUser user, UUID id, StaffRequest request) {
        Scope s = requireScope(user, request.schoolId(), "people.manage"); long version = version(request.expectedVersion());
        StoredStaff current = staff(s, id, true);
        if (current.version() != version)
            throw conflict("STALE_VERSION", "The staff profile changed. Refresh and try again.");
        if ("ENDED".equals(current.status()))
            throw conflict("STAFF_ALREADY_ENDED", "This staff profile is already ended.");
        validateAccount(s, request.userAccountId(), id);
        String status = enumValue(request.status(), ACTIVE_STATUSES, "staff status");
        if (!Objects.equals(current.userAccountId(), request.userAccountId())
                && current.userAccountId() != null
                && activeTeachingWork(s, current.userAccountId(), LocalDate.now(clock)) > 0)
            throw conflict("STAFF_ACCOUNT_HANDOFF_REQUIRED",
                    "End active or future teaching allocations before changing this staff account link.");
        if (!Objects.equals(current.userAccountId(), request.userAccountId())
                && activeTransportDuties(s, id, LocalDate.now(clock)) > 0)
            throw conflict("STAFF_TRANSPORT_HANDOFF_REQUIRED",
                    "End or replace active transport duties before changing this staff account link.");
        if ("INACTIVE".equals(status) && openAssignments(s, id) > 0)
            throw conflict("STAFF_ASSIGNMENT_HANDOFF_REQUIRED",
                    "End active or planned assignments before making this staff profile inactive.");
        if ("INACTIVE".equals(status) && activeTransportDuties(s, id, LocalDate.now(clock)) > 0)
            throw conflict("STAFF_TRANSPORT_HANDOFF_REQUIRED",
                    "End or replace active transport duties before making this staff profile inactive.");
        Instant now = Instant.now(clock);
        String why = reason(request.reason());
        if ("INACTIVE".equals(status) && !"INACTIVE".equals(current.status()))
            retirePublicProfiles(s, id, now, why);
        int changed = safe("STAFF_PROFILE_EXISTS", () -> jdbc.update("""
                UPDATE staff_profiles SET legal_name=:legalName,display_name=:displayName,primary_language=:language,
                  work_email_normalized=:email,work_phone=:phone,user_account_id=:userAccountId,status=:status,
                  updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND employee_code=:code AND joined_on=:joinedOn AND status<>'ENDED' AND row_version=:version
                """, staffParams(s, id, request, version).addValue("status", status).addValue("now", now)));
        changed(changed);
        UUID revokedAccount = !Objects.equals(current.userAccountId(), request.userAccountId())
                ? current.userAccountId() : ("INACTIVE".equals(status) && !"INACTIVE".equals(current.status())
                    ? current.userAccountId() : null);
        if (revokedAccount != null) {
            offboardAccess(s, revokedAccount, now);
            history(s, id, revokedAccount, "ACCESS_OFFBOARDED", "LINKED", "STAFF_ACCESS_REVOKED", why);
        }
        history(s, id, id, "PROFILE_UPDATED", current.status(), status, why);
        audit(user, s, "people.staff.updated", "STAFF_PROFILE", id, why);
        return new PeopleMutationResponse(id, version + 1);
    }

    @Transactional
    public PeopleMutationResponse createAssignment(AuthenticatedUser user, AssignmentRequest request) {
        Scope s = requireScope(user, request.schoolId(), "people.manage"); validateAssignment(s, request);
        UUID id = UUID.randomUUID(); Instant now = Instant.now(clock);
        safe("STAFF_ASSIGNMENT_CONFLICT", () -> jdbc.update("""
                INSERT INTO staff_assignments(id,organisation_id,school_id,staff_profile_id,staff_designation_id,
                  staff_department_id,campus_id,employment_type,employer_kind,agency_name,primary_assignment,
                  starts_on,status,created_by_membership_id,created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:staffId,:designationId,:departmentId,:campusId,
                  :employmentType,:employerKind,:agencyName,:primaryAssignment,:startsOn,:status,
                  :actorMembershipId,:now,:now,0)
                """, assignmentParams(s, id, request, now)));
        history(s, request.staffProfileId(), id, "ASSIGNMENT_ADDED", null,
                enumValue(request.status(), Set.of("PLANNED", "ACTIVE"), "assignment status"), request.reason());
        audit(user, s, "people.assignment.created", "STAFF_ASSIGNMENT", id, request.reason());
        return new PeopleMutationResponse(id, 0);
    }

    @Transactional
    public PeopleMutationResponse endAssignment(AuthenticatedUser user, UUID id, EndRequest request) {
        Scope s = requireScope(user, request.schoolId(), "people.manage");
        StoredAssignment row = assignment(s, id, true);
        if (row.version() != request.expectedVersion()) throw conflict("STALE_VERSION", "The assignment changed. Refresh and try again.");
        if (!Set.of("PLANNED", "ACTIVE").contains(row.status())) throw conflict("ASSIGNMENT_TERMINAL", "This assignment is already closed.");
        if (request.endDate().isBefore(row.startsOn())) throw bad("ASSIGNMENT_END_INVALID", "The end date cannot be before the assignment start.");
        if (activeTransportDuties(s, row.staffId(), request.endDate()) > 0)
            throw conflict("STAFF_TRANSPORT_HANDOFF_REQUIRED",
                    "End or replace active transport duties before ending this employment assignment.");
        String next = "PLANNED".equals(row.status()) ? "CANCELLED" : "ENDED"; Instant now = Instant.now(clock);
        String why = reason(request.reason());
        if (row.primaryAssignment()) retirePublicProfiles(s, row.staffId(), now, why);
        int changed = jdbc.update("""
                UPDATE staff_assignments SET status=:next,ends_on=:endDate,ending_reason=:reason,
                  ended_by_membership_id=:actorMembershipId,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status=:status AND row_version=:version
                """, s.params().addValue("id", id).addValue("next", next).addValue("endDate", request.endDate())
                .addValue("reason", why).addValue("now", now)
                .addValue("status", row.status()).addValue("version", row.version()));
        changed(changed); history(s, row.staffId(), id, "ASSIGNMENT_ENDED", row.status(), next, why);
        audit(user, s, "people.assignment.ended", "STAFF_ASSIGNMENT", id, why);
        return new PeopleMutationResponse(id, row.version() + 1);
    }

    @Transactional
    public PeopleMutationResponse endStaff(AuthenticatedUser user, UUID id, EndRequest request) {
        Scope s = requireScope(user, request.schoolId(), "people.manage"); StoredStaff row = staff(s, id, true);
        if (row.version() != request.expectedVersion()) throw conflict("STALE_VERSION", "The staff profile changed. Refresh and try again.");
        if ("ENDED".equals(row.status())) throw conflict("STAFF_ALREADY_ENDED", "This staff profile is already ended.");
        if (request.endDate().isBefore(row.joinedOn())) throw bad("STAFF_END_INVALID", "The end date cannot be before the joining date.");
        if (Objects.equals(row.userAccountId(), user.userId())) throw conflict("SELF_OFFBOARDING_DENIED", "A staff authority cannot end their own access profile.");
        if (row.userAccountId() != null && activeTeachingWork(s, row.userAccountId(), request.endDate()) > 0)
            throw conflict("STAFF_OPERATIONAL_HANDOFF_REQUIRED", "End future teaching allocations before offboarding this staff member.");
        if (activeTransportDuties(s, id, request.endDate()) > 0)
            throw conflict("STAFF_TRANSPORT_HANDOFF_REQUIRED",
                    "End or replace active transport duties before offboarding this staff member.");
        Instant now = Instant.now(clock); String why = reason(request.reason());
        retirePublicProfiles(s, id, now, why);
        List<StoredAssignment> assignments = jdbc.query("""
                SELECT id,staff_profile_id,status,starts_on,primary_assignment,row_version FROM staff_assignments
                WHERE staff_profile_id=:staffId AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status IN ('PLANNED','ACTIVE') FOR UPDATE
                """, s.params().addValue("staffId", id), PeopleService::mapAssignment);
        for (StoredAssignment assignment : assignments) {
            String next = "PLANNED".equals(assignment.status()) ? "CANCELLED" : "ENDED";
            LocalDate end = assignment.startsOn().isAfter(request.endDate()) ? assignment.startsOn() : request.endDate();
            jdbc.update("""
                    UPDATE staff_assignments SET status=:next,ends_on=:endDate,ending_reason=:reason,
                      ended_by_membership_id=:actorMembershipId,updated_at=:now,row_version=row_version+1
                    WHERE id=:assignmentId AND row_version=:version
                    """, s.params().addValue("assignmentId", assignment.id()).addValue("next", next)
                    .addValue("endDate", end).addValue("reason", why).addValue("now", now)
                    .addValue("version", assignment.version()));
            history(s, id, assignment.id(), "ASSIGNMENT_ENDED", assignment.status(), next, why);
        }
        int changed = jdbc.update("""
                UPDATE staff_profiles SET status='ENDED',ended_on=:endDate,ending_reason=:reason,
                  ended_by_membership_id=:actorMembershipId,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status<>'ENDED' AND row_version=:version
                """, s.params().addValue("id", id).addValue("endDate", request.endDate())
                .addValue("reason", why).addValue("now", now).addValue("version", row.version()));
        changed(changed); offboardAccess(s, row.userAccountId(), now);
        if (row.userAccountId() != null)
            history(s, id, row.userAccountId(), "ACCESS_OFFBOARDED", "LINKED", "STAFF_ACCESS_REVOKED", why);
        history(s, id, id, "PROFILE_ENDED", row.status(), "ENDED", why);
        audit(user, s, "people.staff.ended", "STAFF_PROFILE", id, why);
        return new PeopleMutationResponse(id, row.version() + 1);
    }

    @Transactional
    public PeopleMutationResponse createQualification(AuthenticatedUser user, QualificationRequest request) {
        Scope s = requireScope(user, request.schoolId(), "people.manage"); requireActiveStaff(s, request.staffProfileId());
        UUID id = UUID.randomUUID(); Instant now = Instant.now(clock);
        jdbc.update("""
                INSERT INTO staff_qualifications(id,organisation_id,school_id,staff_profile_id,
                  qualification_name,qualification_level,subject_specialisation,institution_name,awarding_body,
                  completed_year,verification_status,public_summary_eligible,created_by_membership_id,
                  created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:staffId,:name,:level,:subject,:institution,:awardingBody,
                  :completedYear,'PENDING',false,:actorMembershipId,:now,:now,0)
                """, s.params().addValue("id", id).addValue("staffId", request.staffProfileId())
                .addValue("name", required(request.name())).addValue("level", enumValue(request.level(), QUALIFICATION_LEVELS, "qualification level"))
                .addValue("subject", clean(request.subjectSpecialisation())).addValue("institution", clean(request.institutionName()))
                .addValue("awardingBody", clean(request.awardingBody())).addValue("completedYear", request.completedYear())
                .addValue("now", now));
        history(s, request.staffProfileId(), id, "QUALIFICATION_ADDED", null, "PENDING", request.reason());
        audit(user, s, "people.qualification.created", "STAFF_QUALIFICATION", id, request.reason());
        return new PeopleMutationResponse(id, 0);
    }

    @Transactional
    public PeopleMutationResponse decideQualification(AuthenticatedUser user, UUID id, DecisionRequest request) {
        Scope s = requireScope(user, request.schoolId(), "people.compliance"); String decision = enumValue(request.decision(), Set.of("VERIFIED", "REJECTED"), "decision");
        StoredEvidence row = evidence(s, "staff_qualifications", id, true);
        if (row.version() != request.expectedVersion()) throw conflict("STALE_VERSION", "The qualification changed. Refresh and try again.");
        if (!"PENDING".equals(row.status())) throw conflict("QUALIFICATION_ALREADY_DECIDED", "This qualification already has a decision.");
        if (row.creatorId().equals(s.actorMembershipId())) throw conflict("PEOPLE_MAKER_CHECKER_REQUIRED", "A different authorized membership must verify this evidence.");
        Instant now = Instant.now(clock); String why = reason(request.reason());
        int changed = jdbc.update("""
                UPDATE staff_qualifications SET verification_status=:decision,
                  verified_by_membership_id=:actorMembershipId,verified_at=:now,verification_reason=:reason,
                  public_summary_eligible=:publicEligible,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND verification_status='PENDING' AND row_version=:version
                """, s.params().addValue("id", id).addValue("decision", decision).addValue("now", now)
                .addValue("reason", why).addValue("publicEligible", "VERIFIED".equals(decision))
                .addValue("version", row.version()));
        changed(changed); history(s, row.staffId(), id, "QUALIFICATION_DECIDED", "PENDING", decision, why);
        audit(user, s, "people.qualification." + decision.toLowerCase(Locale.ROOT), "STAFF_QUALIFICATION", id, why);
        return new PeopleMutationResponse(id, row.version() + 1);
    }

    @Transactional
    public PeopleMutationResponse createComplianceType(AuthenticatedUser user, ComplianceTypeRequest request) {
        Scope s = requireScope(user, request.schoolId(), "people.manage"); UUID id = UUID.randomUUID();
        safe("COMPLIANCE_TYPE_EXISTS", () -> jdbc.update("""
                INSERT INTO staff_compliance_types(id,organisation_id,school_id,code,display_name,description,
                  applicable_staff_category,validity_months,required_for_active_service,status,
                  created_by_membership_id,created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:code,:name,:description,:category,:validityMonths,
                  :required,:status,:actorMembershipId,:now,:now,0)
                """, complianceTypeParams(s, id, request, null)));
        audit(user, s, "people.compliance-type.created", "STAFF_COMPLIANCE_TYPE", id, clean(request.reason()));
        return new PeopleMutationResponse(id, 0);
    }

    @Transactional
    public PeopleMutationResponse updateComplianceType(AuthenticatedUser user, UUID id, ComplianceTypeRequest request) {
        Scope s = requireScope(user, request.schoolId(), "people.manage"); long version = version(request.expectedVersion());
        int changed = safe("COMPLIANCE_TYPE_EXISTS", () -> jdbc.update("""
                UPDATE staff_compliance_types SET display_name=:name,description=:description,
                  applicable_staff_category=:category,validity_months=:validityMonths,
                  required_for_active_service=:required,status=:status,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND code=:code AND row_version=:version
                """, complianceTypeParams(s, id, request, version)));
        changed(changed); audit(user, s, "people.compliance-type.updated", "STAFF_COMPLIANCE_TYPE", id, reason(request.reason()));
        return new PeopleMutationResponse(id, version + 1);
    }

    @Transactional
    public PeopleMutationResponse createComplianceCheck(AuthenticatedUser user, ComplianceCheckRequest request) {
        Scope s = requireScope(user, request.schoolId(), "people.compliance"); requireActiveStaff(s, request.staffProfileId());
        validateComplianceApplicability(s, request.staffProfileId(), request.complianceTypeId());
        UUID id = UUID.randomUUID(); Instant now = Instant.now(clock);
        jdbc.update("""
                INSERT INTO staff_compliance_checks(id,organisation_id,school_id,staff_profile_id,
                  staff_compliance_type_id,status,safe_reference,restricted_note,created_by_membership_id,
                  created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:staffId,:typeId,'PENDING',:reference,:note,
                  :actorMembershipId,:now,:now,0)
                """, s.params().addValue("id", id).addValue("staffId", request.staffProfileId())
                .addValue("typeId", request.complianceTypeId()).addValue("reference", clean(request.safeReference()))
                .addValue("note", clean(request.restrictedNote())).addValue("now", now));
        history(s, request.staffProfileId(), id, "COMPLIANCE_ADDED", null, "PENDING", request.reason());
        audit(user, s, "people.compliance.created", "STAFF_COMPLIANCE_CHECK", id, request.reason());
        return new PeopleMutationResponse(id, 0);
    }

    @Transactional
    public PeopleMutationResponse decideComplianceCheck(AuthenticatedUser user, UUID id, ComplianceDecisionRequest request) {
        Scope s = requireScope(user, request.schoolId(), "people.compliance");
        String decision = enumValue(request.decision(), Set.of("VERIFIED", "REJECTED", "WAIVED"), "decision");
        StoredEvidence row = evidence(s, "staff_compliance_checks", id, true);
        if (row.version() != request.expectedVersion()) throw conflict("STALE_VERSION", "The compliance check changed. Refresh and try again.");
        if (!"PENDING".equals(row.status())) throw conflict("COMPLIANCE_ALREADY_DECIDED", "This compliance check already has a decision.");
        if (row.creatorId().equals(s.actorMembershipId())) throw conflict("PEOPLE_MAKER_CHECKER_REQUIRED", "A different authorized membership must verify this evidence.");
        LocalDate completed = "VERIFIED".equals(decision) ? request.completedOn() : null;
        if ("VERIFIED".equals(decision) && completed == null) throw bad("COMPLIANCE_COMPLETION_REQUIRED", "Enter the verified completion date.");
        Integer months = jdbc.queryForObject("""
                SELECT type.validity_months FROM staff_compliance_types type
                JOIN staff_compliance_checks check_record ON check_record.staff_compliance_type_id=type.id
                WHERE check_record.id=:id AND check_record.organisation_id=:organisationId
                  AND check_record.school_id=:schoolId
                """, s.params().addValue("id", id), Integer.class);
        LocalDate validUntil = "VERIFIED".equals(decision)
                ? (months == null ? request.validUntil() : completed.plusMonths(months)) : null;
        if (validUntil != null && validUntil.isBefore(completed)) throw bad("COMPLIANCE_VALIDITY_INVALID", "Validity cannot end before completion.");
        Instant now = Instant.now(clock); String why = reason(request.reason());
        int changed = jdbc.update("""
                UPDATE staff_compliance_checks SET status=:decision,completed_on=:completedOn,
                  valid_until=:validUntil,verified_by_membership_id=:actorMembershipId,verified_at=:now,
                  decision_reason=:reason,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='PENDING' AND row_version=:version
                """, s.params().addValue("id", id).addValue("decision", decision).addValue("completedOn", completed)
                .addValue("validUntil", validUntil).addValue("now", now).addValue("reason", why)
                .addValue("version", row.version()));
        changed(changed); history(s, row.staffId(), id, "COMPLIANCE_DECIDED", "PENDING", decision, why);
        audit(user, s, "people.compliance." + decision.toLowerCase(Locale.ROOT), "STAFF_COMPLIANCE_CHECK", id, why);
        return new PeopleMutationResponse(id, row.version() + 1);
    }

    @Transactional
    public PeopleMutationResponse createPublicProfile(AuthenticatedUser user, PublicProfileRequest request) {
        Scope s = requireScope(user, request.schoolId(), "people.manage");
        validatePublicRevision(s, request, null); UUID id = UUID.randomUUID(); Instant now = Instant.now(clock);
        safe("PUBLIC_PROFILE_REVISION_EXISTS", () -> jdbc.update("""
                INSERT INTO staff_public_profile_revisions(id,organisation_id,school_id,staff_profile_id,
                  revision_number,supersedes_revision_id,public_display_name,designation_snapshot,
                  qualification_summary,short_bio,cbse_staff_category_snapshot,display_order,status,
                  created_by_membership_id,created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:staffId,:revision,:supersedesId,:displayName,
                  :designation,:qualification,:bio,:cbse,:displayOrder,'DRAFT',:actorMembershipId,:now,:now,0)
                """, publicParams(s, id, request, null).addValue("now", now)));
        history(s, request.staffProfileId(), id, "PUBLIC_DRAFT_CREATED", null, "DRAFT",
                clean(request.reason(), "Public profile draft created"));
        audit(user, s, "people.public-profile.created", "STAFF_PUBLIC_PROFILE", id, clean(request.reason()));
        return new PeopleMutationResponse(id, 0);
    }

    @Transactional
    public PeopleMutationResponse updatePublicProfile(AuthenticatedUser user, UUID id, PublicProfileRequest request) {
        Scope s = requireScope(user, request.schoolId(), "people.manage"); long version = version(request.expectedVersion());
        validatePublicRevision(s, request, id);
        int changed = safe("PUBLIC_PROFILE_REVISION_EXISTS", () -> jdbc.update("""
                UPDATE staff_public_profile_revisions SET public_display_name=:displayName,
                  designation_snapshot=:designation,qualification_summary=:qualification,short_bio=:bio,
                  cbse_staff_category_snapshot=:cbse,display_order=:displayOrder,updated_at=:now,
                  row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND staff_profile_id=:staffId AND revision_number=:revision
                  AND supersedes_revision_id IS NOT DISTINCT FROM :supersedesId
                  AND status='DRAFT' AND row_version=:version
                """, publicParams(s, id, request, version).addValue("now", Instant.now(clock))));
        changed(changed); history(s, request.staffProfileId(), id, "PUBLIC_DRAFT_UPDATED", "DRAFT", "DRAFT", reason(request.reason()));
        audit(user, s, "people.public-profile.updated", "STAFF_PUBLIC_PROFILE", id, reason(request.reason()));
        return new PeopleMutationResponse(id, version + 1);
    }

    @Transactional
    public PeopleMutationResponse submitPublicProfile(AuthenticatedUser user, UUID id, VersionedReason request) {
        Scope s = requireScope(user, request.schoolId(), "people.manage"); StoredPublic row = publicProfile(s, id, true);
        if (row.version() != request.expectedVersion()) throw conflict("STALE_VERSION", "The public profile changed. Refresh and try again.");
        if (!"DRAFT".equals(row.status())) throw conflict("PUBLIC_PROFILE_NOT_DRAFT", "Only a draft can be submitted.");
        Instant now = Instant.now(clock); String why = reason(request.reason());
        int changed = jdbc.update("""
                UPDATE staff_public_profile_revisions SET status='PENDING_APPROVAL',
                  submitted_by_membership_id=:actorMembershipId,submitted_at=:now,
                  updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='DRAFT' AND row_version=:version
                """, s.params().addValue("id", id).addValue("now", now).addValue("version", row.version()));
        changed(changed); history(s, row.staffId(), id, "PUBLIC_SUBMITTED", "DRAFT", "PENDING_APPROVAL", why);
        audit(user, s, "people.public-profile.submitted", "STAFF_PUBLIC_PROFILE", id, why);
        return new PeopleMutationResponse(id, row.version() + 1);
    }

    @Transactional
    public PeopleMutationResponse decidePublicProfile(AuthenticatedUser user, UUID id, DecisionRequest request) {
        Scope s = requireScope(user, request.schoolId(), "people.publish"); StoredPublic row = publicProfile(s, id, true);
        if (row.version() != request.expectedVersion()) throw conflict("STALE_VERSION", "The public profile changed. Refresh and try again.");
        if (!"PENDING_APPROVAL".equals(row.status())) throw conflict("PUBLIC_PROFILE_NOT_PENDING", "Only a pending profile can be decided.");
        if (row.creatorId().equals(s.actorMembershipId())) throw conflict("PEOPLE_MAKER_CHECKER_REQUIRED", "A different authorized membership must approve public disclosure.");
        String decision = enumValue(request.decision(), Set.of("PUBLISHED", "REJECTED"), "decision");
        if ("PUBLISHED".equals(decision)) {
            requireActiveStaff(s, row.staffId());
            validatePublicApprovalContext(s, id);
        }
        Instant now = Instant.now(clock); String why = reason(request.reason());
        if ("PUBLISHED".equals(decision)) {
            List<UUID> existing = jdbc.query("""
                    SELECT id FROM staff_public_profile_revisions
                    WHERE staff_profile_id=:staffId AND organisation_id=:organisationId
                      AND school_id=:schoolId AND status='PUBLISHED' FOR UPDATE
                    """, s.params().addValue("staffId", row.staffId()), (r, n) -> r.getObject("id", UUID.class));
            for (UUID previous : existing) {
                jdbc.update("""
                        UPDATE staff_public_profile_revisions SET status='RETIRED',
                          decided_by_membership_id=:actorMembershipId,decided_at=:now,decision_reason=:reason,
                          updated_at=:now,row_version=row_version+1 WHERE id=:previous AND status='PUBLISHED'
                        """, s.params().addValue("previous", previous).addValue("now", now).addValue("reason", why));
                history(s, row.staffId(), previous, "PUBLIC_RETIRED", "PUBLISHED", "RETIRED", why);
            }
        }
        int changed = jdbc.update("""
                UPDATE staff_public_profile_revisions SET status=:decision,
                  decided_by_membership_id=:actorMembershipId,decided_at=:now,decision_reason=:reason,
                  published_at=CASE WHEN :decision='PUBLISHED' THEN :now ELSE NULL END,
                  updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='PENDING_APPROVAL' AND row_version=:version
                """, s.params().addValue("id", id).addValue("decision", decision).addValue("now", now)
                .addValue("reason", why).addValue("version", row.version()));
        changed(changed); history(s, row.staffId(), id,
                "PUBLISHED".equals(decision) ? "PUBLIC_PUBLISHED" : "PUBLIC_REJECTED",
                "PENDING_APPROVAL", decision, why);
        audit(user, s, "people.public-profile." + decision.toLowerCase(Locale.ROOT), "STAFF_PUBLIC_PROFILE", id, why);
        return new PeopleMutationResponse(id, row.version() + 1);
    }

    private Scope requireScope(AuthenticatedUser user, UUID schoolId, String capability) {
        if (user.activeOrganisationId() == null) throw conflict("ORGANISATION_SELECTION_REQUIRED", "Select a school organisation first.");
        List<Scope> rows = jdbc.query("""
                SELECT school.id,school.display_name,membership.id AS membership_id,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='people.manage') can_manage,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='people.compliance') can_compliance,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='people.publish') can_publish
                FROM memberships membership JOIN schools school ON school.id=membership.school_id
                JOIN membership_roles mr ON mr.membership_id=membership.id
                JOIN role_capabilities rc ON rc.role_id=mr.role_id
                WHERE membership.user_id=:userId AND membership.organisation_id=:organisationId
                  AND membership.id IN(:membershipIds) AND (CAST(:schoolId AS uuid) IS NULL OR membership.school_id=:schoolId)
                  AND membership.status='ACTIVE' AND membership.valid_from<=CURRENT_TIMESTAMP
                  AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                  AND rc.capability_code=:capability
                ORDER BY can_publish DESC,can_compliance DESC,can_manage DESC,membership.created_at LIMIT 1
                """, new MapSqlParameterSource().addValue("userId", user.userId())
                .addValue("organisationId", user.activeOrganisationId()).addValue("membershipIds", user.membershipIds())
                .addValue("schoolId", schoolId).addValue("capability", capability), (r, n) -> new Scope(
                user.activeOrganisationId(), r.getObject("id", UUID.class), r.getString("display_name"),
                r.getObject("membership_id", UUID.class), r.getBoolean("can_manage"),
                r.getBoolean("can_compliance"), r.getBoolean("can_publish")));
        if (rows.isEmpty()) throw forbidden("PEOPLE_SCOPE_DENIED", "People access is unavailable for this school.");
        return rows.getFirst();
    }

    private void validateAccount(Scope s, UUID userAccountId, UUID editingStaffId) {
        if (userAccountId == null) return;
        int count = jdbc.queryForObject("""
                SELECT count(*) FROM memberships membership JOIN user_accounts account ON account.id=membership.user_id
                WHERE membership.user_id=:userAccountId AND membership.organisation_id=:organisationId
                  AND membership.school_id=:schoolId AND membership.status='ACTIVE'
                  AND account.status IN ('ACTIVE','INVITED')
                  AND EXISTS (SELECT 1 FROM membership_roles mr JOIN roles role ON role.id=mr.role_id
                    WHERE mr.membership_id=membership.id AND role.code<>'parent_guardian')
                  AND NOT EXISTS (SELECT 1 FROM staff_profiles staff WHERE staff.school_id=:schoolId
                    AND staff.user_account_id=:userAccountId AND (CAST(:editingStaffId AS uuid) IS NULL OR staff.id<>:editingStaffId))
                """, s.params().addValue("userAccountId", userAccountId).addValue("editingStaffId", editingStaffId), Integer.class);
        if (count != 1) throw bad("STAFF_ACCOUNT_LINK_INVALID", "Choose one active same-school staff account that is not linked elsewhere.");
    }

    private void validateAssignment(Scope s, AssignmentRequest request) {
        String status = enumValue(request.status(), Set.of("PLANNED", "ACTIVE"), "assignment status");
        enumValue(request.employmentType(), EMPLOYMENT_TYPES, "employment type");
        String employer = enumValue(request.employerKind(), EMPLOYERS, "employer kind");
        if ("AGENCY".equals(employer) != (clean(request.agencyName()) != null))
            throw bad("STAFF_AGENCY_INVALID", "Agency employment requires an agency name; school employment must not include one.");
        int context = jdbc.queryForObject("""
                SELECT count(*) FROM staff_profiles staff
                JOIN staff_designations designation ON designation.id=:designationId
                  AND designation.organisation_id=staff.organisation_id AND designation.school_id=staff.school_id
                JOIN campuses campus ON campus.id=:campusId AND campus.organisation_id=staff.organisation_id
                  AND campus.school_id=staff.school_id
                LEFT JOIN staff_departments department ON department.id=:departmentId
                  AND department.organisation_id=staff.organisation_id AND department.school_id=staff.school_id
                WHERE staff.id=:staffId AND staff.organisation_id=:organisationId AND staff.school_id=:schoolId
                  AND staff.status='ACTIVE' AND designation.status='ACTIVE' AND campus.status='ACTIVE'
                  AND (CAST(:departmentId AS uuid) IS NULL OR department.status='ACTIVE') AND :startsOn>=staff.joined_on
                """, s.params().addValue("staffId", request.staffProfileId()).addValue("designationId", request.designationId())
                .addValue("departmentId", request.departmentId()).addValue("campusId", request.campusId())
                .addValue("startsOn", request.startsOn()), Integer.class);
        if (context != 1) throw bad("STAFF_ASSIGNMENT_SCOPE_INVALID", "Choose active staff and same-school assignment configuration.");
        if ("ACTIVE".equals(status) && request.startsOn().isAfter(LocalDate.now(clock)))
            throw bad("STAFF_ASSIGNMENT_START_INVALID", "A future assignment must remain planned until its start date.");
    }

    private void validateComplianceApplicability(Scope s, UUID staffId, UUID typeId) {
        int count = jdbc.queryForObject("""
                SELECT count(*) FROM staff_compliance_types type
                WHERE type.id=:typeId AND type.organisation_id=:organisationId AND type.school_id=:schoolId
                  AND type.status='ACTIVE' AND (type.applicable_staff_category IS NULL OR EXISTS (
                    SELECT 1 FROM staff_assignments assignment
                    JOIN staff_designations designation ON designation.id=assignment.staff_designation_id
                    WHERE assignment.staff_profile_id=:staffId AND assignment.status='ACTIVE'
                      AND designation.staff_category=type.applicable_staff_category))
                """, s.params().addValue("typeId", typeId).addValue("staffId", staffId), Integer.class);
        if (count != 1) throw bad("COMPLIANCE_TYPE_NOT_APPLICABLE", "Choose an active compliance type applicable to this staff assignment.");
    }

    private void validatePublicRevision(Scope s, PublicProfileRequest request, UUID editingId) {
        CurrentPublicContext context = jdbc.query("""
                SELECT staff.status,designation.cbse_staff_category,
                  EXISTS(SELECT 1 FROM staff_qualifications qualification
                    WHERE qualification.staff_profile_id=staff.id
                      AND qualification.verification_status='VERIFIED'
                      AND qualification.public_summary_eligible) has_verified_qualification
                FROM staff_profiles staff
                JOIN staff_assignments assignment ON assignment.staff_profile_id=staff.id
                  AND assignment.status='ACTIVE' AND assignment.primary_assignment
                JOIN staff_designations designation ON designation.id=assignment.staff_designation_id
                WHERE staff.id=:staffId AND staff.organisation_id=:organisationId AND staff.school_id=:schoolId
                  AND staff.status='ACTIVE'
                """, s.params().addValue("staffId", request.staffProfileId()), (r, n) -> new CurrentPublicContext(
                r.getString("cbse_staff_category"), r.getBoolean("has_verified_qualification")))
                .stream().findFirst().orElseThrow(() -> bad("PUBLIC_STAFF_CONTEXT_INVALID", "Public profiles require active staff with a primary assignment."));
        String cbse = nullableEnum(request.cbseStaffCategory(), CBSE_CATEGORIES, "CBSE staff category");
        if (!Objects.equals(cbse, context.cbseCategory()))
            throw bad("PUBLIC_STAFF_CATEGORY_MISMATCH", "The public CBSE category must match the active designation.");
        if (clean(request.qualificationSummary()) != null && !context.hasVerifiedQualification())
            throw bad("PUBLIC_QUALIFICATION_UNVERIFIED", "Verify at least one qualification before publishing a qualification summary.");
        if (request.supersedesRevisionId() == null) {
            if (request.revisionNumber() != 1) throw bad("PUBLIC_STAFF_REVISION_INVALID", "A first public profile starts at revision 1.");
            return;
        }
        List<RevisionContext> rows = jdbc.query("""
                SELECT staff_profile_id,revision_number,status FROM staff_public_profile_revisions
                WHERE id=:supersedesId AND organisation_id=:organisationId AND school_id=:schoolId
                """, s.params().addValue("supersedesId", request.supersedesRevisionId()), (r, n) -> new RevisionContext(
                r.getObject("staff_profile_id", UUID.class), r.getInt("revision_number"), r.getString("status")));
        if (rows.isEmpty() || !rows.getFirst().staffId().equals(request.staffProfileId())
                || !Set.of("PUBLISHED", "REJECTED").contains(rows.getFirst().status())
                || request.revisionNumber() != rows.getFirst().revision() + 1
                || (editingId != null && editingId.equals(request.supersedesRevisionId())))
            throw bad("PUBLIC_STAFF_REVISION_INVALID", "A revision must supersede an approved or rejected profile for the same staff member with the next number.");
    }

    private void requireActiveStaff(Scope s, UUID staffId) {
        int count = jdbc.queryForObject("""
                SELECT count(*) FROM staff_profiles staff
                WHERE staff.id=:staffId AND staff.organisation_id=:organisationId
                  AND staff.school_id=:schoolId AND staff.status='ACTIVE'
                """, s.params().addValue("staffId", staffId), Integer.class);
        if (count != 1) throw bad("STAFF_PROFILE_NOT_ACTIVE", "Choose an active staff profile from this school.");
    }

    private void validatePublicApprovalContext(Scope s, UUID publicProfileId) {
        int count = jdbc.queryForObject("""
                SELECT count(*) FROM staff_public_profile_revisions profile
                JOIN staff_profiles staff ON staff.id=profile.staff_profile_id
                  AND staff.organisation_id=profile.organisation_id AND staff.school_id=profile.school_id
                JOIN staff_assignments assignment ON assignment.staff_profile_id=staff.id
                  AND assignment.status='ACTIVE' AND assignment.primary_assignment
                JOIN staff_designations designation ON designation.id=assignment.staff_designation_id
                  AND designation.status='ACTIVE'
                WHERE profile.id=:publicProfileId AND profile.organisation_id=:organisationId
                  AND profile.school_id=:schoolId AND profile.status='PENDING_APPROVAL'
                  AND staff.status='ACTIVE'
                  AND designation.cbse_staff_category IS NOT DISTINCT FROM profile.cbse_staff_category_snapshot
                  AND (profile.qualification_summary IS NULL OR EXISTS (
                    SELECT 1 FROM staff_qualifications qualification
                    WHERE qualification.staff_profile_id=staff.id
                      AND qualification.verification_status='VERIFIED'
                      AND qualification.public_summary_eligible))
                """, s.params().addValue("publicProfileId", publicProfileId), Integer.class);
        if (count != 1) throw conflict("PUBLIC_STAFF_CONTEXT_CHANGED",
                "The active assignment or verified qualification context changed. Reject this draft and create a current revision.");
    }

    private int activeTeachingWork(Scope s, UUID userAccountId, LocalDate endDate) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM teaching_allocations allocation
                JOIN memberships membership ON membership.id=allocation.teacher_membership_id
                WHERE membership.user_id=:userAccountId AND allocation.organisation_id=:organisationId
                  AND allocation.school_id=:schoolId AND allocation.status='ACTIVE'
                  AND (allocation.ends_on IS NULL OR allocation.ends_on>:endDate)
                """, s.params().addValue("userAccountId", userAccountId).addValue("endDate", endDate), Integer.class);
    }

    private int activeTransportDuties(Scope s, UUID staffId, LocalDate endDate) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM transport_duty_assignments duty
                WHERE duty.organisation_id=:organisationId AND duty.school_id=:schoolId
                  AND (duty.driver_staff_profile_id=:staffId OR duty.attendant_staff_profile_id=:staffId)
                  AND duty.status IN ('PLANNED','ACTIVE')
                  AND (duty.ends_on IS NULL OR duty.ends_on>=:endDate)
                """, s.params().addValue("staffId", staffId).addValue("endDate", endDate), Integer.class);
    }

    private int openAssignments(Scope s, UUID staffId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM staff_assignments
                WHERE staff_profile_id=:staffId AND organisation_id=:organisationId
                  AND school_id=:schoolId AND status IN ('PLANNED','ACTIVE')
                """, s.params().addValue("staffId", staffId), Integer.class);
    }

    private void offboardAccess(Scope s, UUID userAccountId, Instant now) {
        if (userAccountId == null) return;
        List<UUID> memberships = jdbc.query("""
                SELECT id FROM memberships WHERE user_id=:userAccountId AND organisation_id=:organisationId
                  AND school_id=:schoolId AND status='ACTIVE' FOR UPDATE
                """, s.params().addValue("userAccountId", userAccountId), (r, n) -> r.getObject("id", UUID.class));
        for (UUID membershipId : memberships) {
            jdbc.update("""
                    DELETE FROM membership_roles membership_role USING roles role
                    WHERE membership_role.membership_id=:membershipId AND role.id=membership_role.role_id
                      AND role.code<>'parent_guardian'
                    """, new MapSqlParameterSource().addValue("membershipId", membershipId));
            Integer remaining = jdbc.queryForObject("SELECT count(*) FROM membership_roles WHERE membership_id=:membershipId",
                    new MapSqlParameterSource().addValue("membershipId", membershipId), Integer.class);
            if (remaining == 0) jdbc.update("""
                    UPDATE memberships SET status='ENDED',valid_until=:now,updated_at=:now,row_version=row_version+1
                    WHERE id=:membershipId AND status='ACTIVE'
                    """, new MapSqlParameterSource().addValue("membershipId", membershipId).addValue("now", now));
        }
        jdbc.update("""
                UPDATE user_sessions SET revoked_at=COALESCE(revoked_at,:now)
                WHERE user_id=:userAccountId AND revoked_at IS NULL
                """, new MapSqlParameterSource().addValue("userAccountId", userAccountId).addValue("now", now));
    }

    private void retirePublicProfiles(Scope s, UUID staffId, Instant now, String why) {
        List<UUID> published = jdbc.query("""
                SELECT id FROM staff_public_profile_revisions
                WHERE staff_profile_id=:staffId AND organisation_id=:organisationId
                  AND school_id=:schoolId AND status='PUBLISHED' FOR UPDATE
                """, s.params().addValue("staffId", staffId), (r, n) -> r.getObject("id", UUID.class));
        for (UUID publicationId : published) {
            jdbc.update("""
                    UPDATE staff_public_profile_revisions SET status='RETIRED',decided_by_membership_id=:actorMembershipId,
                      decided_at=:now,decision_reason=:reason,updated_at=:now,row_version=row_version+1
                    WHERE id=:publicationId AND status='PUBLISHED'
                    """, s.params().addValue("publicationId", publicationId).addValue("now", now).addValue("reason", why));
            history(s, staffId, publicationId, "PUBLIC_RETIRED", "PUBLISHED", "RETIRED", why);
        }
    }

    private StoredStaff staff(Scope s, UUID id, boolean lock) {
        List<StoredStaff> rows = jdbc.query("""
                SELECT id,status,joined_on,user_account_id,row_version FROM staff_profiles
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                """ + (lock ? " FOR UPDATE" : ""), s.params().addValue("id", id), (r, n) -> new StoredStaff(
                r.getObject("id", UUID.class), r.getString("status"), r.getObject("joined_on", LocalDate.class),
                r.getObject("user_account_id", UUID.class), r.getLong("row_version")));
        if (rows.isEmpty()) throw notFound("STAFF_PROFILE_NOT_FOUND", "The staff profile was not found.");
        return rows.getFirst();
    }

    private StoredAssignment assignment(Scope s, UUID id, boolean lock) {
        List<StoredAssignment> rows = jdbc.query("""
                SELECT id,staff_profile_id,status,starts_on,primary_assignment,row_version FROM staff_assignments
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                """ + (lock ? " FOR UPDATE" : ""), s.params().addValue("id", id), PeopleService::mapAssignment);
        if (rows.isEmpty()) throw notFound("STAFF_ASSIGNMENT_NOT_FOUND", "The assignment was not found.");
        return rows.getFirst();
    }

    private StoredEvidence evidence(Scope s, String table, UUID id, boolean lock) {
        String statusColumn = "staff_qualifications".equals(table) ? "verification_status" : "status";
        List<StoredEvidence> rows = jdbc.query("SELECT id,staff_profile_id," + statusColumn + " AS status,created_by_membership_id,row_version FROM "
                + table + " WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId" + (lock ? " FOR UPDATE" : ""),
                s.params().addValue("id", id), (r, n) -> new StoredEvidence(r.getObject("id", UUID.class),
                r.getObject("staff_profile_id", UUID.class), r.getString("status"),
                r.getObject("created_by_membership_id", UUID.class), r.getLong("row_version")));
        if (rows.isEmpty()) throw notFound("STAFF_EVIDENCE_NOT_FOUND", "The staff evidence was not found.");
        return rows.getFirst();
    }

    private StoredPublic publicProfile(Scope s, UUID id, boolean lock) {
        List<StoredPublic> rows = jdbc.query("""
                SELECT id,staff_profile_id,status,created_by_membership_id,row_version
                FROM staff_public_profile_revisions
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                """ + (lock ? " FOR UPDATE" : ""), s.params().addValue("id", id), (r, n) -> new StoredPublic(
                r.getObject("id", UUID.class), r.getObject("staff_profile_id", UUID.class), r.getString("status"),
                r.getObject("created_by_membership_id", UUID.class), r.getLong("row_version")));
        if (rows.isEmpty()) throw notFound("PUBLIC_STAFF_PROFILE_NOT_FOUND", "The public staff profile was not found.");
        return rows.getFirst();
    }

    private MapSqlParameterSource configParams(Scope s, UUID id, String code, String name,
            String description, String status, Instant now) {
        return s.params().addValue("id", id).addValue("code", configCode(code)).addValue("name", required(name))
                .addValue("description", clean(description)).addValue("status", enumValue(status, ACTIVE_STATUSES, "status"))
                .addValue("now", now);
    }

    private MapSqlParameterSource designationParams(Scope s, UUID id, DesignationRequest request, Long version) {
        String cbse = nullableEnum(request.cbseStaffCategory(), CBSE_CATEGORIES, "CBSE staff category");
        if (cbse != null && !"NON_TEACHING".equals(cbse) && !request.teachingRole())
            throw bad("DESIGNATION_TEACHING_INVALID", "A teaching CBSE category must be marked as a teaching role.");
        if (request.eligibleAsTransportDriver()
                && !"TRANSPORT".equals(enumValue(request.staffCategory(), STAFF_CATEGORIES, "staff category")))
            throw bad("DESIGNATION_DRIVER_CATEGORY_INVALID",
                    "Driver eligibility is available only for a transport designation.");
        return s.params().addValue("id", id).addValue("code", configCode(request.code())).addValue("name", required(request.name()))
                .addValue("category", enumValue(request.staffCategory(), STAFF_CATEGORIES, "staff category"))
                .addValue("cbse", cbse).addValue("teaching", request.teachingRole())
                .addValue("leadership", request.leadershipRole())
                .addValue("transportDriver", request.eligibleAsTransportDriver())
                .addValue("transportAttendant", request.eligibleAsTransportAttendant())
                .addValue("description", clean(request.description()))
                .addValue("displayOrder", request.displayOrder()).addValue("status", enumValue(request.status(), ACTIVE_STATUSES, "status"))
                .addValue("version", version).addValue("now", Instant.now(clock));
    }

    private MapSqlParameterSource staffParams(Scope s, UUID id, StaffRequest request, Long version) {
        return s.params().addValue("id", id).addValue("code", employeeCode(request.employeeCode()))
                .addValue("legalName", required(request.legalName())).addValue("displayName", required(request.displayName()))
                .addValue("language", enumValue(request.primaryLanguage(), Set.of("EN", "HI"), "language"))
                .addValue("email", lower(request.workEmail())).addValue("phone", clean(request.workPhone()))
                .addValue("userAccountId", request.userAccountId()).addValue("joinedOn", request.joinedOn())
                .addValue("version", version);
    }

    private MapSqlParameterSource assignmentParams(Scope s, UUID id, AssignmentRequest request, Instant now) {
        return s.params().addValue("id", id).addValue("staffId", request.staffProfileId())
                .addValue("designationId", request.designationId()).addValue("departmentId", request.departmentId())
                .addValue("campusId", request.campusId()).addValue("employmentType", enumValue(request.employmentType(), EMPLOYMENT_TYPES, "employment type"))
                .addValue("employerKind", enumValue(request.employerKind(), EMPLOYERS, "employer kind"))
                .addValue("agencyName", clean(request.agencyName())).addValue("primaryAssignment", request.primaryAssignment())
                .addValue("startsOn", request.startsOn()).addValue("status", enumValue(request.status(), Set.of("PLANNED", "ACTIVE"), "assignment status"))
                .addValue("now", now);
    }

    private MapSqlParameterSource complianceTypeParams(Scope s, UUID id, ComplianceTypeRequest request, Long version) {
        return s.params().addValue("id", id).addValue("code", configCode(request.code())).addValue("name", required(request.name()))
                .addValue("description", clean(request.description())).addValue("category", nullableEnum(request.applicableStaffCategory(), STAFF_CATEGORIES, "staff category"))
                .addValue("validityMonths", request.validityMonths()).addValue("required", request.requiredForActiveService())
                .addValue("status", enumValue(request.status(), ACTIVE_STATUSES, "status"))
                .addValue("version", version).addValue("now", Instant.now(clock));
    }

    private MapSqlParameterSource publicParams(Scope s, UUID id, PublicProfileRequest request, Long version) {
        return s.params().addValue("id", id).addValue("staffId", request.staffProfileId())
                .addValue("revision", request.revisionNumber()).addValue("supersedesId", request.supersedesRevisionId())
                .addValue("displayName", required(request.displayName())).addValue("designation", required(request.designation()))
                .addValue("qualification", clean(request.qualificationSummary())).addValue("bio", clean(request.shortBio()))
                .addValue("cbse", nullableEnum(request.cbseStaffCategory(), CBSE_CATEGORIES, "CBSE staff category"))
                .addValue("displayOrder", request.displayOrder()).addValue("version", version);
    }

    private void history(Scope s, UUID staffId, UUID subjectId, String event, String previous,
            String next, String reason) {
        jdbc.update("""
                INSERT INTO staff_profile_history(id,organisation_id,school_id,staff_profile_id,event_type,
                  subject_id,previous_status,new_status,reason,actor_membership_id,occurred_at)
                VALUES(:id,:organisationId,:schoolId,:staffId,:event,:subjectId,:previous,:next,
                  :reason,:actorMembershipId,:now)
                """, s.params().addValue("id", UUID.randomUUID()).addValue("staffId", staffId)
                .addValue("event", event).addValue("subjectId", subjectId).addValue("previous", previous)
                .addValue("next", next).addValue("reason", reason(reason)).addValue("now", Instant.now(clock)));
    }

    private void audit(AuthenticatedUser user, Scope s, String action, String type, UUID id, String reason) {
        audit.recordScoped(user, s.actorMembershipId(), s.schoolId(), action, type, id.toString(),
                AuditOutcome.SUCCEEDED, clean(reason), Map.of());
    }

    private static StoredAssignment mapAssignment(java.sql.ResultSet r, int n) throws java.sql.SQLException {
        return new StoredAssignment(r.getObject("id", UUID.class), r.getObject("staff_profile_id", UUID.class),
                r.getString("status"), r.getObject("starts_on", LocalDate.class), r.getBoolean("primary_assignment"),
                r.getLong("row_version"));
    }
    private static Instant instant(java.sql.ResultSet r, String name) throws java.sql.SQLException {
        var value = r.getTimestamp(name); return value == null ? null : value.toInstant();
    }
    private static List<String> split(String value) { return value == null || value.isBlank() ? List.of() : List.of(value.split(",")); }
    private static String clean(String value) { return value == null || value.isBlank() ? null : value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ").strip(); }
    private static String clean(String value, String fallback) { String cleaned = clean(value); return cleaned == null ? fallback : cleaned; }
    private static String required(String value) { String cleaned = clean(value); if (cleaned == null) throw bad("REQUIRED_VALUE_MISSING", "Enter the required value."); return cleaned; }
    private static String reason(String value) { String cleaned = clean(value); if (cleaned == null) throw bad("CHANGE_REASON_REQUIRED", "Enter a reason for this change."); return cleaned; }
    private static String configCode(String value) { return required(value).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_"); }
    private static String employeeCode(String value) { return required(value).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_/-]+", "_"); }
    private static String lower(String value) { String cleaned = clean(value); return cleaned == null ? null : cleaned.toLowerCase(Locale.ROOT); }
    private static String enumValue(String value, Set<String> allowed, String label) { String result = required(value).toUpperCase(Locale.ROOT).replace(' ', '_'); if (!allowed.contains(result)) throw bad("INVALID_VALUE", "Choose a supported " + label + "."); return result; }
    private static String nullableEnum(String value, Set<String> allowed, String label) { return clean(value) == null ? null : enumValue(value, allowed, label); }
    private static long version(Long value) { if (value == null) throw bad("EXPECTED_VERSION_REQUIRED", "Refresh the record and include its current version."); return value; }
    private static void changed(int count) { if (count != 1) throw conflict("STALE_VERSION", "The record changed or is no longer editable. Refresh and try again."); }
    private static <T> T safe(String code, java.util.function.Supplier<T> work) { try { return work.get(); } catch (DataIntegrityViolationException error) { throw conflict(code, "A conflicting people record already exists or violates a protected state."); } }
    private static ApiException bad(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
    private static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
    private static ApiException forbidden(String code, String message) { return new ApiException(HttpStatus.FORBIDDEN, code, message); }
    private static ApiException notFound(String code, String message) { return new ApiException(HttpStatus.NOT_FOUND, code, message); }

    private record Scope(UUID organisationId, UUID schoolId, String schoolName, UUID actorMembershipId,
            boolean canManage, boolean canCompliance, boolean canPublish) {
        MapSqlParameterSource params() { return new MapSqlParameterSource().addValue("organisationId", organisationId)
                .addValue("schoolId", schoolId).addValue("actorMembershipId", actorMembershipId); }
    }
    private record StoredStaff(UUID id, String status, LocalDate joinedOn, UUID userAccountId, long version) {}
    private record StoredAssignment(UUID id, UUID staffId, String status, LocalDate startsOn,
            boolean primaryAssignment, long version) {}
    private record StoredEvidence(UUID id, UUID staffId, String status, UUID creatorId, long version) {}
    private record StoredPublic(UUID id, UUID staffId, String status, UUID creatorId, long version) {}
    private record CurrentPublicContext(String cbseCategory, boolean hasVerifiedQualification) {}
    private record RevisionContext(UUID staffId, int revision, String status) {}
}
