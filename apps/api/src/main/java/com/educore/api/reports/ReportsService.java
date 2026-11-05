package com.educore.api.reports;

import static com.educore.api.reports.ReportsSnapshot.DataQualityIssue;
import static com.educore.api.reports.ReportsSnapshot.ReportMetric;
import static com.educore.api.reports.ReportsSnapshot.ReportSection;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportsService {
    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final Clock clock;

    public ReportsService(
            NamedParameterJdbcTemplate jdbc,
            SecurityAuditService audit,
            Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public ReportsSnapshot overview(AuthenticatedUser user, UUID requestedSchoolId) {
        Scope scope = requireScope(user, requestedSchoolId);
        LocalDate schoolDate = LocalDate.now(clock.withZone(ZoneId.of(scope.timeZone())));
        MapSqlParameterSource parameters = scope.params().addValue("schoolDate", schoolDate);
        List<ReportSection> sections = List.of(
                admissions(parameters),
                students(parameters),
                academics(parameters, schoolDate),
                attendance(parameters),
                people(parameters, schoolDate),
                finance(parameters),
                communication(parameters),
                transport(parameters));
        List<DataQualityIssue> issues = dataQuality(parameters, schoolDate);
        Instant generatedAt = Instant.now(clock);
        audit.recordScoped(user, scope.actorMembershipId(), scope.schoolId(),
                "reports.overview.generated", "SCHOOL_REPORT", scope.schoolId().toString(),
                AuditOutcome.SUCCEEDED, null,
                Map.of("sectionCount", sections.size(), "issueTypeCount", issues.size()));
        return new ReportsSnapshot(scope.schoolId(), scope.schoolName(), scope.currencyCode(),
                generatedAt, sections, issues);
    }

    private ReportSection admissions(MapSqlParameterSource parameters) {
        return jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM public_enquiries
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND status IN ('NEW','OPEN','QUALIFIED')) AS open_enquiries,
                  (SELECT count(*) FROM admission_applications
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND status IN ('SUBMITTED','UNDER_REVIEW','WAITLISTED','OFFERED','ACCEPTED')) AS active_applications,
                  (SELECT count(*) FROM admission_applications
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND status='ACCEPTED') AS awaiting_enrolment
                """, parameters, (result, row) -> section("admissions", "Admissions pipeline",
                "Current enquiry and application workload; closed, rejected, withdrawn, spam, and enrolled records are excluded from active counts.",
                count("openEnquiries", "Open enquiries", result.getLong("open_enquiries"),
                        "Enquiries in new, open, or qualified state."),
                count("activeApplications", "Applications in progress", result.getLong("active_applications"),
                        "Submitted through accepted applications that are not yet enrolled."),
                count("awaitingEnrolment", "Accepted, awaiting enrolment", result.getLong("awaiting_enrolment"),
                        "Accepted applications that still require the controlled enrolment handoff.")));
    }

    private ReportSection students(MapSqlParameterSource parameters) {
        return jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM learners
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND status='ACTIVE') AS active_learners,
                  (SELECT count(*) FROM student_enrolments
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND status='ACTIVE') AS active_enrolments,
                  (SELECT count(*) FROM student_enrolments
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND status='ACTIVE' AND placement_status='PENDING') AS pending_placements,
                  (SELECT count(*) FROM learner_guardian_relationships
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND portal_access_status='ACTIVE') AS active_guardian_access
                """, parameters, (result, row) -> section("students", "Students and guardians",
                "Canonical learner, enrolment, placement, and active guardian-relationship counts.",
                count("activeLearners", "Active learners", result.getLong("active_learners"),
                        "Learner profiles in active state."),
                count("activeEnrolments", "Active enrolments", result.getLong("active_enrolments"),
                        "Current school enrolments in active state."),
                count("pendingPlacements", "Pending section placement", result.getLong("pending_placements"),
                        "Active enrolments not yet placed in a class section."),
                count("activeGuardianAccess", "Active guardian relationships", result.getLong("active_guardian_access"),
                        "Learner-guardian relationships with active portal access.")));
    }

    private ReportSection academics(MapSqlParameterSource parameters, LocalDate schoolDate) {
        return jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM academic_sessions
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND status IN ('PLANNED','ACTIVE')) AS open_sessions,
                  (SELECT count(*) FROM class_sections
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND status='ACTIVE') AS active_sections,
                  (SELECT count(*) FROM teaching_allocations
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND status='ACTIVE' AND starts_on<=:schoolDate
                      AND (ends_on IS NULL OR ends_on>=:schoolDate)) AS teaching_allocations,
                  (SELECT count(*) FROM curriculum_publications
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND status='PUBLISHED') AS published_curricula
                """, parameters, (result, row) -> section("academics", "Academic structure",
                "Configuration available for the school's current local date, " + schoolDate + ".",
                count("openSessions", "Planned or active sessions", result.getLong("open_sessions"),
                        "Academic sessions that remain planned or active."),
                count("activeSections", "Active class sections", result.getLong("active_sections"),
                        "Class sections available for current operations."),
                count("teachingAllocations", "Effective teaching allocations", result.getLong("teaching_allocations"),
                        "Active teacher allocations effective on the generated school date."),
                count("publishedCurricula", "Published curriculum releases", result.getLong("published_curricula"),
                        "Current immutable curriculum publications across sessions and grades.")));
    }

    private ReportSection attendance(MapSqlParameterSource parameters) {
        return jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM attendance_registers register
                    JOIN school_calendar_days day ON day.id=register.calendar_day_id
                      AND day.organisation_id=register.organisation_id AND day.school_id=register.school_id
                    WHERE register.organisation_id=:organisationId AND register.school_id=:schoolId
                      AND day.calendar_date=:schoolDate) AS registers_today,
                  (SELECT count(*) FROM attendance_registers register
                    JOIN school_calendar_days day ON day.id=register.calendar_day_id
                      AND day.organisation_id=register.organisation_id AND day.school_id=register.school_id
                    WHERE register.organisation_id=:organisationId AND register.school_id=:schoolId
                      AND day.calendar_date=:schoolDate AND register.status='DRAFT') AS draft_today,
                  (SELECT count(*) FROM attendance_registers register
                    JOIN school_calendar_days day ON day.id=register.calendar_day_id
                      AND day.organisation_id=register.organisation_id AND day.school_id=register.school_id
                    WHERE register.organisation_id=:organisationId AND register.school_id=:schoolId
                      AND day.calendar_date=:schoolDate AND register.status IN ('SUBMITTED','LOCKED')) AS submitted_today,
                  (SELECT count(*) FROM attendance_marks mark
                    JOIN attendance_registers register ON register.id=mark.attendance_register_id
                      AND register.organisation_id=mark.organisation_id AND register.school_id=mark.school_id
                    JOIN school_calendar_days day ON day.id=register.calendar_day_id
                      AND day.organisation_id=register.organisation_id AND day.school_id=register.school_id
                    WHERE mark.organisation_id=:organisationId AND mark.school_id=:schoolId
                      AND day.calendar_date=:schoolDate AND mark.state_code='ABSENT') AS absent_marks_today
                """, parameters, (result, row) -> section("attendance", "Attendance today",
                "Register and absence evidence for the school's current local calendar date.",
                count("registersToday", "Registers created", result.getLong("registers_today"),
                        "Daily or period registers created for today."),
                count("draftToday", "Draft registers", result.getLong("draft_today"),
                        "Today's registers that have not been submitted."),
                count("submittedToday", "Submitted or locked", result.getLong("submitted_today"),
                        "Today's registers with submitted evidence."),
                count("absentMarksToday", "Absent marks", result.getLong("absent_marks_today"),
                        "Learner marks explicitly recorded as absent today; this is not a distinct-learner count.")));
    }

    private ReportSection people(MapSqlParameterSource parameters, LocalDate schoolDate) {
        return jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM staff_profiles
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND status='ACTIVE') AS active_staff,
                  (SELECT count(DISTINCT staff.id) FROM staff_profiles staff
                    JOIN staff_assignments assignment ON assignment.staff_profile_id=staff.id
                      AND assignment.organisation_id=staff.organisation_id AND assignment.school_id=staff.school_id
                    JOIN staff_designations designation ON designation.id=assignment.staff_designation_id
                      AND designation.organisation_id=assignment.organisation_id AND designation.school_id=assignment.school_id
                    WHERE staff.organisation_id=:organisationId AND staff.school_id=:schoolId
                      AND staff.status='ACTIVE' AND assignment.status='ACTIVE'
                      AND assignment.starts_on<=:schoolDate
                      AND (assignment.ends_on IS NULL OR assignment.ends_on>=:schoolDate)
                      AND designation.teaching_role) AS active_teaching_staff,
                  (SELECT count(*) FROM staff_profiles
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND status='ACTIVE' AND user_account_id IS NOT NULL) AS staff_portal_accounts
                """, parameters, (result, row) -> section("people", "People operations",
                "Active employment profiles and effective teaching eligibility on " + schoolDate + ".",
                count("activeStaff", "Active staff", result.getLong("active_staff"),
                        "Employment profiles in active state."),
                count("activeTeachingStaff", "Active teaching staff", result.getLong("active_teaching_staff"),
                        "Distinct active staff with an effective active teaching-eligible assignment."),
                count("staffPortalAccounts", "Staff with linked login", result.getLong("staff_portal_accounts"),
                        "Active employment profiles linked to a portal user account.")));
    }

    private ReportSection finance(MapSqlParameterSource parameters) {
        return jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM learner_fee_accounts
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND status='ACTIVE') AS active_accounts,
                  (SELECT coalesce(sum(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END),0)
                    FROM fee_ledger_entries
                    WHERE organisation_id=:organisationId AND school_id=:schoolId) AS outstanding_balance,
                  (SELECT count(*) FROM fee_payments
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND status='POSTED') AS posted_payments
                """, parameters, (result, row) -> section("finance", "Fees and ledger",
                "School-wide append-only ledger totals. A negative balance represents net credit, not missing data.",
                count("activeFeeAccounts", "Active fee accounts", result.getLong("active_accounts"),
                        "Learner fee accounts currently open."),
                amount("outstandingBalance", "Net outstanding balance", result.getBigDecimal("outstanding_balance"),
                        "Total debit ledger entries less total credit entries in the school currency."),
                count("postedPayments", "Posted payments", result.getLong("posted_payments"),
                        "Payments currently posted; reversed payments are excluded.")));
    }

    private ReportSection communication(MapSqlParameterSource parameters) {
        return jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM communication_announcements
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND status='PENDING_APPROVAL') AS pending_approval,
                  (SELECT count(*) FROM communication_announcements
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND status='APPROVED') AS approved,
                  (SELECT count(*) FROM communication_deliveries
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND status='FAILED') AS failed_deliveries,
                  (SELECT count(*) FROM communication_recipients
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND read_at IS NULL AND available_at<=CURRENT_TIMESTAMP) AS unread_recipients
                """, parameters, (result, row) -> section("communication", "Communication delivery",
                "Approval, inbox, and provider-neutral delivery evidence across school communications.",
                count("pendingApproval", "Pending approval", result.getLong("pending_approval"),
                        "Announcement drafts awaiting an independent decision."),
                count("approvedAnnouncements", "Approved announcements", result.getLong("approved"),
                        "Announcements in approved state, including scheduled or expired records."),
                count("failedDeliveries", "Failed deliveries", result.getLong("failed_deliveries"),
                        "Channel deliveries currently in failed state."),
                count("unreadRecipients", "Unread inbox recipients", result.getLong("unread_recipients"),
                        "Available recipient inbox items that have no read timestamp.")));
    }

    private ReportSection transport(MapSqlParameterSource parameters) {
        return jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM transport_route_revisions
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND status='PUBLISHED') AS published_routes,
                  (SELECT count(*) FROM transport_vehicles
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND status='ACTIVE') AS active_vehicles,
                  (SELECT count(*) FROM transport_incidents
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND status='OPEN') AS open_incidents,
                  (SELECT count(*) FROM transport_trips
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND service_date=:schoolDate AND status IN ('PLANNED','BOARDING','IN_PROGRESS')) AS active_trips_today
                """, parameters, (result, row) -> section("transport", "Transport operations",
                "Published route, fleet, incident, and current service-day trip evidence.",
                count("publishedRoutes", "Published route journeys", result.getLong("published_routes"),
                        "Published route revisions; pickup and drop journeys are counted separately."),
                count("activeVehicles", "Active vehicles", result.getLong("active_vehicles"),
                        "Vehicles in active operating state."),
                count("openIncidents", "Open incidents", result.getLong("open_incidents"),
                        "Restricted transport incidents not yet resolved; no incident details are exposed here."),
                count("activeTripsToday", "Trips active today", result.getLong("active_trips_today"),
                        "Planned, boarding, or in-progress trips for the current school date.")));
    }

    private List<DataQualityIssue> dataQuality(
            MapSqlParameterSource parameters,
            LocalDate schoolDate) {
        return jdbc.queryForObject("""
                SELECT
                  (SELECT count(*) FROM student_enrolments
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND status='ACTIVE' AND placement_status='PENDING') AS pending_placements,
                  (SELECT count(*) FROM learners learner
                    WHERE learner.organisation_id=:organisationId AND learner.school_id=:schoolId
                      AND learner.status='ACTIVE' AND NOT EXISTS (
                        SELECT 1 FROM learner_guardian_relationships relationship
                        WHERE relationship.organisation_id=learner.organisation_id
                          AND relationship.school_id=learner.school_id
                          AND relationship.learner_id=learner.id
                          AND relationship.primary_contact)) AS missing_primary_guardian,
                  (SELECT count(*) FROM staff_profiles staff
                    WHERE staff.organisation_id=:organisationId AND staff.school_id=:schoolId
                      AND staff.status='ACTIVE' AND NOT EXISTS (
                        SELECT 1 FROM staff_assignments assignment
                        WHERE assignment.organisation_id=staff.organisation_id
                          AND assignment.school_id=staff.school_id
                          AND assignment.staff_profile_id=staff.id
                          AND assignment.status='ACTIVE' AND assignment.starts_on<=:schoolDate
                          AND (assignment.ends_on IS NULL OR assignment.ends_on>=:schoolDate))) AS staff_without_assignment,
                  (SELECT count(*) FROM communication_deliveries
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND status='FAILED') AS failed_deliveries
                """, parameters, (result, row) -> {
                    List<DataQualityIssue> issues = new ArrayList<>();
                    addIssue(issues, result.getLong("pending_placements"),
                            "pendingPlacements", "WARNING", "Learners awaiting section placement",
                            "Active enrolments need a current class-section placement.", "/workspace/students");
                    addIssue(issues, result.getLong("missing_primary_guardian"),
                            "missingPrimaryGuardian", "WARNING", "Learners without a primary guardian",
                            "Active learner records should have one explicitly designated primary contact.",
                            "/workspace/students");
                    addIssue(issues, result.getLong("staff_without_assignment"),
                            "staffWithoutAssignment", "WARNING", "Active staff without an effective assignment",
                            "Active employment profiles need a current campus and designation assignment.",
                            "/workspace/staff");
                    addIssue(issues, result.getLong("failed_deliveries"),
                            "failedDeliveries", "CRITICAL", "Communication deliveries require review",
                            "Failed provider-neutral deliveries remain visible until the delivery workflow resolves them.",
                            "/workspace/communication");
                    return List.copyOf(issues);
                });
    }

    private Scope requireScope(AuthenticatedUser user, UUID schoolId) {
        if (user == null || user.activeOrganisationId() == null) {
            throw conflict("ORGANISATION_SELECTION_REQUIRED", "Select a school organisation first.");
        }
        if (user.membershipIds() == null || user.membershipIds().isEmpty()) {
            throw forbidden("REPORT_SCOPE_DENIED", "Reporting is unavailable for this school.");
        }
        List<Scope> scopes = jdbc.query("""
                SELECT DISTINCT school.id,school.display_name,school.time_zone,school.currency_code,
                  membership.id AS membership_id
                FROM memberships membership
                JOIN membership_roles membership_role ON membership_role.membership_id=membership.id
                JOIN role_capabilities role_capability ON role_capability.role_id=membership_role.role_id
                JOIN schools school ON school.organisation_id=membership.organisation_id
                  AND (school.id=membership.school_id OR membership.school_id IS NULL)
                WHERE membership.user_id=:userId AND membership.organisation_id=:organisationId
                  AND membership.id IN(:membershipIds)
                  AND (CAST(:schoolId AS uuid) IS NULL OR school.id=:schoolId)
                  AND membership.status='ACTIVE' AND membership.valid_from<=CURRENT_TIMESTAMP
                  AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                  AND role_capability.capability_code='reports.read'
                ORDER BY school.id,membership.id LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("userId", user.userId())
                .addValue("organisationId", user.activeOrganisationId())
                .addValue("membershipIds", user.membershipIds())
                .addValue("schoolId", schoolId), (result, row) -> new Scope(
                user.activeOrganisationId(), result.getObject("id", UUID.class),
                result.getString("display_name"), result.getString("time_zone"),
                result.getString("currency_code"), result.getObject("membership_id", UUID.class)));
        if (scopes.isEmpty()) {
            throw forbidden("REPORT_SCOPE_DENIED", "Reporting is unavailable for this school.");
        }
        return scopes.getFirst();
    }

    private static ReportSection section(
            String code,
            String title,
            String description,
            ReportMetric... metrics) {
        return new ReportSection(code, title, description, List.of(metrics));
    }

    private static ReportMetric count(String code, String label, long value, String definition) {
        return new ReportMetric(code, label, BigDecimal.valueOf(value), "COUNT", definition);
    }

    private static ReportMetric amount(String code, String label, BigDecimal value, String definition) {
        return new ReportMetric(code, label, value == null ? BigDecimal.ZERO : value, "CURRENCY", definition);
    }

    private static void addIssue(
            List<DataQualityIssue> issues,
            long count,
            String code,
            String severity,
            String title,
            String description,
            String destination) {
        if (count > 0) {
            issues.add(new DataQualityIssue(code, severity, count, title, description, destination));
        }
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    private record Scope(
            UUID organisationId,
            UUID schoolId,
            String schoolName,
            String timeZone,
            String currencyCode,
            UUID actorMembershipId) {
        MapSqlParameterSource params() {
            return new MapSqlParameterSource()
                    .addValue("organisationId", organisationId)
                    .addValue("schoolId", schoolId)
                    .addValue("actorMembershipId", actorMembershipId);
        }
    }
}
