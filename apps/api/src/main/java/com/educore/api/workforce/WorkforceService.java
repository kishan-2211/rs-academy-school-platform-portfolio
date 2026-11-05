package com.educore.api.workforce;

import static com.educore.api.workforce.WorkforceController.*;
import static com.educore.api.workforce.WorkforceSnapshot.*;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import com.educore.api.workforce.WorkforcePayrollBridge.PayrollAttendanceDay;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class WorkforceService implements WorkforcePayrollBridge {
    private static final Set<String> STATES = Set.of(
            "PRESENT", "ABSENT", "LATE", "LEAVE", "HALF_DAY", "OFFICIAL_DUTY");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");
    private static final Set<String> DURATIONS = Set.of("FULL_DAY", "FIRST_HALF", "SECOND_HALF");
    private static final Set<String> BALANCE_KINDS = Set.of("OPENING", "ACCRUAL", "ADJUSTMENT", "REVERSAL");
    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public WorkforceService(NamedParameterJdbcTemplate jdbc, SecurityAuditService audit,
            Clock clock, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PayrollAttendanceDay> payrollAttendanceDays(AuthenticatedUser user,
            UUID requestedSchoolId, LocalDate periodStart, LocalDate periodEnd,
            String requiredCapability) {
        PayrollBridgeScope scope = requirePayrollBridgeScope(user, requestedSchoolId,
                requiredCapability);
        if (periodStart == null || periodEnd == null || periodEnd.isBefore(periodStart)
                || periodStart.plusMonths(2).isBefore(periodEnd)) {
            throw bad("PAYROLL_ATTENDANCE_RANGE_INVALID",
                    "Choose one valid payroll period before resolving attendance.");
        }
        return jdbc.query("""
                WITH expected_days AS (
                  SELECT DISTINCT ON (staff.id,day.calendar_date)
                    staff.id AS staff_profile_id,assignment.campus_id,day.id AS calendar_day_id,
                    day.calendar_date
                  FROM staff_profiles staff
                  JOIN staff_assignments assignment
                    ON assignment.staff_profile_id=staff.id
                   AND assignment.organisation_id=staff.organisation_id
                   AND assignment.school_id=staff.school_id
                   AND assignment.primary_assignment
                   AND assignment.status IN ('ACTIVE','ENDED')
                  JOIN school_calendar_days day
                    ON day.organisation_id=staff.organisation_id AND day.school_id=staff.school_id
                   AND day.campus_id=assignment.campus_id AND day.attendance_required
                   AND day.status='ACTIVE' AND day.calendar_date BETWEEN :periodStart AND :periodEnd
                   AND day.calendar_date>=assignment.starts_on
                   AND (assignment.ends_on IS NULL OR day.calendar_date<=assignment.ends_on)
                  WHERE staff.organisation_id=:organisationId AND staff.school_id=:schoolId
                    AND staff.status IN ('ACTIVE','INACTIVE','ENDED')
                    AND day.calendar_date>=staff.joined_on
                    AND (staff.ended_on IS NULL OR day.calendar_date<=staff.ended_on)
                  ORDER BY staff.id,day.calendar_date,assignment.starts_on DESC,assignment.id
                )
                SELECT expected.staff_profile_id,expected.campus_id,expected.calendar_day_id,
                       expected.calendar_date,register.id AS register_id,
                       register.row_version AS register_version,mark.id AS mark_id,
                       mark.row_version AS mark_version,mark.state_code,
                       CASE WHEN mark.id IS NULL THEN 0
                         ELSE COALESCE((register.policy_snapshot->'stateWeights'->>mark.state_code)::numeric,0)
                       END AS policy_credit,
                       leave_request.id AS leave_request_id,
                       leave_request.leave_type_snapshot->>'code' AS leave_type_code,
                       CASE WHEN leave_request.id IS NULL THEN NULL
                         ELSE (leave_request.leave_type_snapshot->>'paid')::boolean END AS paid_leave,
                       CASE WHEN leave_request.id IS NULL THEN NULL
                         WHEN leave_request.duration='FULL_DAY' THEN 1.0 ELSE 0.5 END AS leave_fraction,
                       CASE
                         WHEN register.status NOT IN ('SUBMITTED','LOCKED') OR mark.id IS NULL THEN 0
                         WHEN mark.state_code='LEAVE' AND leave_request.id IS NULL THEN 0
                         WHEN mark.state_code='LEAVE'
                           AND (leave_request.leave_type_snapshot->>'paid')::boolean THEN 1.0
                         WHEN mark.state_code='LEAVE' AND leave_request.duration='FULL_DAY' THEN 0
                         WHEN mark.state_code='LEAVE' THEN 0.5
                         ELSE COALESCE((register.policy_snapshot->'stateWeights'->>mark.state_code)::numeric,0)
                       END AS payroll_credit,
                       (register.status IN ('SUBMITTED','LOCKED') AND mark.id IS NOT NULL
                         AND (mark.state_code<>'LEAVE' OR leave_request.id IS NOT NULL)) AS complete
                FROM expected_days expected
                LEFT JOIN staff_attendance_registers register
                  ON register.organisation_id=:organisationId AND register.school_id=:schoolId
                 AND register.campus_id=expected.campus_id
                 AND register.calendar_day_id=expected.calendar_day_id
                LEFT JOIN staff_attendance_marks mark
                  ON mark.organisation_id=:organisationId AND mark.school_id=:schoolId
                 AND mark.staff_attendance_register_id=register.id
                 AND mark.staff_profile_id=expected.staff_profile_id
                LEFT JOIN LATERAL (
                  SELECT request.id,request.duration,request.leave_type_snapshot
                  FROM staff_leave_requests request
                  WHERE request.organisation_id=:organisationId AND request.school_id=:schoolId
                    AND request.staff_profile_id=expected.staff_profile_id
                    AND request.status='APPROVED' AND request.starts_on<=expected.calendar_date
                    AND request.ends_on>=expected.calendar_date
                  ORDER BY request.decided_at DESC,request.id LIMIT 1
                ) leave_request ON mark.state_code='LEAVE'
                ORDER BY expected.staff_profile_id,expected.calendar_date,expected.calendar_day_id
                """, scope.params().addValue("periodStart", periodStart)
                .addValue("periodEnd", periodEnd), (r, n) -> new PayrollAttendanceDay(
                r.getObject("staff_profile_id", UUID.class), r.getObject("campus_id", UUID.class),
                r.getObject("calendar_day_id", UUID.class),
                r.getObject("calendar_date", LocalDate.class), r.getObject("register_id", UUID.class),
                (Long) r.getObject("register_version"), r.getObject("mark_id", UUID.class),
                (Long) r.getObject("mark_version"), r.getString("state_code"),
                r.getBigDecimal("policy_credit"), r.getObject("leave_request_id", UUID.class),
                r.getString("leave_type_code"), (Boolean) r.getObject("paid_leave"),
                r.getBigDecimal("leave_fraction"), r.getBigDecimal("payroll_credit"),
                r.getBoolean("complete")));
    }

    @Transactional
    public WorkforceSnapshot snapshot(AuthenticatedUser user, UUID requestedSchoolId, LocalDate requestedDate) {
        Scope scope = requireScope(user, requestedSchoolId);
        LocalDate date = requestedDate == null ? LocalDate.now(clock) : requestedDate;
        MapSqlParameterSource parameters = scope.params()
                .addValue("date", date)
                .addValue("fromDate", date.minusDays(31))
                .addValue("toDate", date.plusDays(120));

        jdbc.update("""
                UPDATE staff_attendance_registers
                SET status='LOCKED', updated_at=:now, row_version=row_version+1
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND status='SUBMITTED'
                  AND submitted_at + ((policy_snapshot->>'lockAfterHours')::integer * INTERVAL '1 hour') <= :now
                """, parameters.addValue("now", Instant.now(clock)));

        List<SessionItem> sessions = jdbc.query("""
                SELECT id,name,starts_on,ends_on,status FROM academic_sessions
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY starts_on DESC,id
                """, parameters, (r, n) -> new SessionItem(r.getObject("id", UUID.class),
                r.getString("name"), r.getObject("starts_on", LocalDate.class),
                r.getObject("ends_on", LocalDate.class), r.getString("status")));
        List<CampusItem> campuses = jdbc.query("""
                SELECT id,display_name,time_zone FROM campuses
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND status='ACTIVE'
                ORDER BY display_name,id
                """, parameters, (r, n) -> new CampusItem(r.getObject("id", UUID.class),
                r.getString("display_name"), r.getString("time_zone")));
        List<StaffItem> staff = jdbc.query("""
                SELECT profile.id,profile.employee_code,profile.display_name,profile.joined_on,profile.status,
                       current_assignment.campus_id,current_assignment.designation
                FROM staff_profiles profile
                LEFT JOIN LATERAL (
                    SELECT assignment.campus_id,designation.display_name AS designation
                    FROM staff_assignments assignment
                    JOIN staff_designations designation ON designation.id=assignment.staff_designation_id
                    WHERE assignment.staff_profile_id=profile.id
                      AND assignment.status='ACTIVE' AND assignment.starts_on<=:date
                      AND (assignment.ends_on IS NULL OR assignment.ends_on>=:date)
                    ORDER BY assignment.primary_assignment DESC,assignment.starts_on DESC,assignment.id
                    LIMIT 1
                ) current_assignment ON TRUE
                WHERE profile.organisation_id=:organisationId AND profile.school_id=:schoolId
                  AND (:canReadAll OR profile.id=:selfStaffId)
                ORDER BY profile.display_name,profile.id
                """, parameters, (r, n) -> new StaffItem(r.getObject("id", UUID.class),
                r.getString("employee_code"), r.getString("display_name"),
                r.getObject("campus_id", UUID.class), r.getString("designation"),
                r.getObject("joined_on", LocalDate.class), r.getString("status")));
        List<CalendarItem> calendarDays = jdbc.query("""
                SELECT id,campus_id,academic_session_id,calendar_date,day_type,display_name,
                       attendance_required,status
                FROM school_calendar_days
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND calendar_date BETWEEN :fromDate AND :toDate
                ORDER BY calendar_date,campus_id
                """, parameters, (r, n) -> new CalendarItem(r.getObject("id", UUID.class),
                r.getObject("campus_id", UUID.class), r.getObject("academic_session_id", UUID.class),
                r.getObject("calendar_date", LocalDate.class), r.getString("day_type"),
                r.getString("display_name"), r.getBoolean("attendance_required"), r.getString("status")));
        List<PolicyItem> policies = jdbc.query("""
                SELECT * FROM workforce_attendance_policies
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY status,policy_name,id
                """, parameters, (r, n) -> new PolicyItem(r.getObject("id", UUID.class),
                r.getObject("academic_session_id", UUID.class), r.getObject("campus_id", UUID.class),
                r.getString("policy_name"), arrayStrings(r.getArray("enabled_state_codes")),
                stateWeights(r.getString("state_weights")), r.getInt("lock_after_hours"),
                r.getBigDecimal("warning_threshold_percent"), r.getInt("consecutive_absence_threshold"),
                r.getString("status"), r.getLong("row_version")));
        List<LeaveTypeItem> leaveTypes = jdbc.query("""
                SELECT * FROM staff_leave_types
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY status,display_name,id
                """, parameters, (r, n) -> new LeaveTypeItem(r.getObject("id", UUID.class),
                r.getString("code"), r.getString("display_name"), r.getBoolean("paid"),
                r.getBoolean("balance_required"), r.getBigDecimal("max_days_per_request"),
                r.getString("status"), r.getLong("row_version")));
        List<LeaveBalanceItem> balances = jdbc.query("""
                SELECT entry.staff_profile_id,entry.leave_type_id,entry.academic_session_id,
                       COALESCE(sum(entry.quantity),0) AS available_days
                FROM staff_leave_balance_entries entry
                WHERE entry.organisation_id=:organisationId AND entry.school_id=:schoolId
                  AND (:canReadAll OR entry.staff_profile_id=:selfStaffId)
                GROUP BY entry.staff_profile_id,entry.leave_type_id,entry.academic_session_id
                ORDER BY entry.staff_profile_id,entry.leave_type_id,entry.academic_session_id
                """, parameters, (r, n) -> new LeaveBalanceItem(r.getObject("staff_profile_id", UUID.class),
                r.getObject("leave_type_id", UUID.class), r.getObject("academic_session_id", UUID.class),
                r.getBigDecimal("available_days")));
        List<LeaveRequestItem> leaveRequests = jdbc.query("""
                SELECT request.*,requester_user.display_name AS requester_name,
                       decider_user.display_name AS decider_name
                FROM staff_leave_requests request
                JOIN memberships requester ON requester.id=request.requested_by_membership_id
                JOIN user_accounts requester_user ON requester_user.id=requester.user_id
                LEFT JOIN memberships decider ON decider.id=request.decided_by_membership_id
                LEFT JOIN user_accounts decider_user ON decider_user.id=decider.user_id
                WHERE request.organisation_id=:organisationId AND request.school_id=:schoolId
                  AND (:canReadAll OR request.staff_profile_id=:selfStaffId)
                ORDER BY CASE request.status WHEN 'SUBMITTED' THEN 0 ELSE 1 END,
                         request.created_at DESC,request.id
                LIMIT 250
                """, parameters, (r, n) -> new LeaveRequestItem(r.getObject("id", UUID.class),
                r.getObject("staff_profile_id", UUID.class), r.getObject("leave_type_id", UUID.class),
                r.getObject("academic_session_id", UUID.class), r.getObject("starts_on", LocalDate.class),
                r.getObject("ends_on", LocalDate.class), r.getString("duration"),
                r.getBigDecimal("requested_day_count"), r.getString("request_reason"),
                r.getString("status"), r.getString("requester_name"), r.getString("decider_name"),
                r.getString("decision_reason"), r.getTimestamp("created_at").toInstant(),
                r.getLong("row_version")));

        List<RegisterItem> registers = loadRegisters(scope, parameters, date);
        List<CorrectionItem> corrections = loadCorrections(scope, parameters);
        List<AttendanceSummaryItem> summaries = loadSummaries(scope, parameters);
        return new WorkforceSnapshot(scope.schoolId(), scope.schoolName(), scope.canReadAll(),
                scope.canRecord(), scope.canCorrect(), scope.canApproveLeave(), scope.canConfigure(),
                scope.selfStaffId(), sessions, campuses, staff, calendarDays, policies, leaveTypes,
                balances, leaveRequests, registers, corrections, summaries);
    }

    @Transactional
    public WorkforceMutationResponse createPolicy(AuthenticatedUser user, PolicyRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "workforce.configure");
        PolicyEvidence evidence = validatePolicy(scope, request);
        UUID id = UUID.randomUUID();
        safe("WORKFORCE_POLICY_EXISTS", () -> jdbc.update("""
                INSERT INTO workforce_attendance_policies(
                  id,organisation_id,school_id,academic_session_id,campus_id,policy_name,
                  enabled_state_codes,state_weights,lock_after_hours,warning_threshold_percent,
                  consecutive_absence_threshold,status,created_by_membership_id,created_at,updated_at)
                VALUES(:id,:organisationId,:schoolId,:sessionId,:campusId,:name,:states,
                  CAST(:weights AS jsonb),:lockHours,:warning,:consecutive,:status,
                  :actorMembershipId,:now,:now)
                """, policyParams(scope, id, request, evidence, null)));
        audit(user, scope, "workforce.attendance.policy.created", "WORKFORCE_ATTENDANCE_POLICY", id,
                request.reason());
        return new WorkforceMutationResponse(id, 0);
    }

    @Transactional
    public WorkforceMutationResponse updatePolicy(AuthenticatedUser user, UUID id, PolicyRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "workforce.configure");
        PolicyEvidence evidence = validatePolicy(scope, request);
        long version = version(request.expectedVersion());
        int changed = safe("WORKFORCE_POLICY_EXISTS", () -> jdbc.update("""
                UPDATE workforce_attendance_policies SET policy_name=:name,enabled_state_codes=:states,
                  state_weights=CAST(:weights AS jsonb),lock_after_hours=:lockHours,
                  warning_threshold_percent=:warning,consecutive_absence_threshold=:consecutive,
                  status=:status,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND academic_session_id=:sessionId AND campus_id=:campusId AND row_version=:version
                """, policyParams(scope, id, request, evidence, version)));
        changed(changed);
        audit(user, scope, "workforce.attendance.policy.updated", "WORKFORCE_ATTENDANCE_POLICY", id,
                reason(request.reason()));
        return new WorkforceMutationResponse(id, version + 1);
    }

    @Transactional
    public WorkforceMutationResponse createLeaveType(AuthenticatedUser user, LeaveTypeRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "workforce.configure");
        UUID id = UUID.randomUUID();
        safe("LEAVE_TYPE_EXISTS", () -> jdbc.update("""
                INSERT INTO staff_leave_types(id,organisation_id,school_id,code,display_name,paid,
                  balance_required,max_days_per_request,status,created_by_membership_id,created_at,updated_at)
                VALUES(:id,:organisationId,:schoolId,:code,:name,:paid,:balanceRequired,:maxDays,
                  :status,:actorMembershipId,:now,:now)
                """, leaveTypeParams(scope, id, request, null)));
        audit(user, scope, "workforce.leave.type.created", "STAFF_LEAVE_TYPE", id, request.reason());
        return new WorkforceMutationResponse(id, 0);
    }

    @Transactional
    public WorkforceMutationResponse updateLeaveType(AuthenticatedUser user, UUID id,
            LeaveTypeRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "workforce.configure");
        long version = version(request.expectedVersion());
        int changed = safe("LEAVE_TYPE_EXISTS", () -> jdbc.update("""
                UPDATE staff_leave_types SET display_name=:name,paid=:paid,
                  balance_required=:balanceRequired,max_days_per_request=:maxDays,status=:status,
                  updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND code=:code AND row_version=:version
                """, leaveTypeParams(scope, id, request, version)));
        changed(changed);
        audit(user, scope, "workforce.leave.type.updated", "STAFF_LEAVE_TYPE", id,
                reason(request.reason()));
        return new WorkforceMutationResponse(id, version + 1);
    }

    @Transactional
    public WorkforceMutationResponse adjustLeaveBalance(AuthenticatedUser user,
            LeaveBalanceRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "workforce.configure");
        String kind = enumValue(request.entryKind(), BALANCE_KINDS, "balance entry kind");
        BigDecimal quantity = request.quantity();
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) == 0) {
            throw bad("LEAVE_BALANCE_QUANTITY_REQUIRED", "Enter a non-zero leave balance adjustment.");
        }
        validateStaffLeaveContext(scope, request.sessionId(), request.staffProfileId(),
                request.leaveTypeId(), false);
        String key = idempotencyKey(request.idempotencyKey());
        List<StoredBalanceEntry> retries = jdbc.query("""
                SELECT id,academic_session_id,staff_profile_id,leave_type_id,entry_kind,quantity,reason
                FROM staff_leave_balance_entries
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND idempotency_key=:key
                """, scope.params().addValue("key", key), (r, n) -> new StoredBalanceEntry(
                r.getObject("id", UUID.class), r.getObject("academic_session_id", UUID.class),
                r.getObject("staff_profile_id", UUID.class), r.getObject("leave_type_id", UUID.class),
                r.getString("entry_kind"), r.getBigDecimal("quantity"), r.getString("reason")));
        String why = reason(request.reason());
        if (!retries.isEmpty()) {
            StoredBalanceEntry prior = retries.getFirst();
            if (!prior.matches(request.sessionId(), request.staffProfileId(), request.leaveTypeId(),
                    kind, quantity, why)) {
                throw conflict("IDEMPOTENCY_KEY_REUSED",
                        "This retry key was already used for a different leave balance adjustment.");
            }
            return new WorkforceMutationResponse(prior.id(), 0);
        }
        UUID id = UUID.randomUUID();
        safe("LEAVE_BALANCE_RETRY_CONFLICT", () -> jdbc.update("""
                INSERT INTO staff_leave_balance_entries(id,organisation_id,school_id,
                  academic_session_id,staff_profile_id,leave_type_id,entry_kind,quantity,
                  idempotency_key,reason,created_by_membership_id,created_at)
                VALUES(:id,:organisationId,:schoolId,:sessionId,:staffId,:typeId,:kind,:quantity,
                  :key,:reason,:actorMembershipId,:now)
                """, scope.params().addValue("id", id).addValue("sessionId", request.sessionId())
                .addValue("staffId", request.staffProfileId()).addValue("typeId", request.leaveTypeId())
                .addValue("kind", kind).addValue("quantity", quantity).addValue("key", key)
                .addValue("reason", why).addValue("now", Instant.now(clock))));
        audit(user, scope, "workforce.leave.balance.adjusted", "STAFF_LEAVE_BALANCE_ENTRY", id, why);
        return new WorkforceMutationResponse(id, 0);
    }

    @Transactional
    public WorkforceMutationResponse openRegister(AuthenticatedUser user, OpenRegisterRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "workforce.attendance.record");
        DayContext day = dayContext(scope, request.calendarDayId());
        LocalDate localToday = LocalDate.now(clock.withZone(java.time.ZoneId.of(day.timeZone())));
        if (day.date().isAfter(localToday)) {
            throw bad("FUTURE_STAFF_ATTENDANCE_NOT_ALLOWED",
                    "A staff attendance register cannot be opened before the school day.");
        }
        if (!day.attendanceRequired() || !"ACTIVE".equals(day.status())) {
            throw conflict("STAFF_ATTENDANCE_NOT_REQUIRED",
                    "The governed school calendar does not require attendance on this day.");
        }
        ActivePolicy policy = activePolicy(scope, day.sessionId(), day.campusId());
        List<ExistingRegister> existing = jdbc.query("""
                SELECT id,row_version FROM staff_attendance_registers
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND campus_id=:campusId AND calendar_day_id=:dayId
                """, scope.params().addValue("campusId", day.campusId())
                .addValue("dayId", request.calendarDayId()), (r, n) -> new ExistingRegister(
                r.getObject("id", UUID.class), r.getLong("row_version")));
        if (!existing.isEmpty()) {
            return new WorkforceMutationResponse(existing.getFirst().id(), existing.getFirst().version());
        }
        int expected = jdbc.queryForObject("""
                SELECT count(DISTINCT profile.id)
                FROM staff_profiles profile
                JOIN staff_assignments assignment ON assignment.staff_profile_id=profile.id
                WHERE profile.organisation_id=:organisationId AND profile.school_id=:schoolId
                  AND profile.status='ACTIVE' AND profile.joined_on<=:date
                  AND assignment.campus_id=:campusId AND assignment.status='ACTIVE'
                  AND assignment.starts_on<=:date
                  AND (assignment.ends_on IS NULL OR assignment.ends_on>=:date)
                """, scope.params().addValue("date", day.date()).addValue("campusId", day.campusId()),
                Integer.class);
        UUID id = UUID.randomUUID();
        safe("STAFF_ATTENDANCE_REGISTER_EXISTS", () -> jdbc.update("""
                INSERT INTO staff_attendance_registers(id,organisation_id,school_id,
                  academic_session_id,campus_id,calendar_day_id,workforce_attendance_policy_id,
                  policy_snapshot,status,expected_staff_count,created_by_membership_id,created_at,updated_at)
                VALUES(:id,:organisationId,:schoolId,:sessionId,:campusId,:dayId,:policyId,
                  CAST(:snapshot AS jsonb),'DRAFT',:expected,:actorMembershipId,:now,:now)
                """, scope.params().addValue("id", id).addValue("sessionId", day.sessionId())
                .addValue("campusId", day.campusId()).addValue("dayId", request.calendarDayId())
                .addValue("policyId", policy.id()).addValue("snapshot", json(policy.evidence()))
                .addValue("expected", expected).addValue("now", Instant.now(clock))));
        seedApprovedLeave(scope, id, day);
        audit(user, scope, "workforce.attendance.register.opened", "STAFF_ATTENDANCE_REGISTER", id,
                "Staff attendance register opened for " + day.date());
        return new WorkforceMutationResponse(id, 0);
    }

    @Transactional
    public WorkforceMutationResponse recordMarks(AuthenticatedUser user, UUID registerId,
            RecordMarksRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "workforce.attendance.record");
        StoredRegister register = lockRegister(scope, registerId, request.expectedRegisterVersion());
        PolicyEvidence policy = policyEvidence(register.policySnapshot());
        Set<UUID> uniqueStaff = new LinkedHashSet<>();
        String why = reason(request.reason());
        for (MarkInput mark : request.marks()) {
            if (!uniqueStaff.add(mark.staffProfileId())) {
                throw bad("DUPLICATE_STAFF_MARK", "Each staff member may appear only once in a mark batch.");
            }
            String state = enumValue(mark.stateCode(), new LinkedHashSet<>(policy.enabledStates()),
                    "staff attendance state");
            validateTimes(mark.checkInTime(), mark.checkOutTime());
            requireRosterStaff(scope, register, mark.staffProfileId());
            enforceApprovedLeave(scope, register, mark.staffProfileId(), state);
            List<StoredMark> existing = jdbc.query("""
                    SELECT id,state_code,check_in_time,check_out_time,note,row_version
                    FROM staff_attendance_marks
                    WHERE staff_attendance_register_id=:registerId AND staff_profile_id=:staffId
                    """, scope.params().addValue("registerId", registerId)
                    .addValue("staffId", mark.staffProfileId()), (r, n) -> new StoredMark(
                    r.getObject("id", UUID.class), r.getString("state_code"),
                    r.getObject("check_in_time", LocalTime.class), r.getObject("check_out_time", LocalTime.class),
                    r.getString("note"), r.getLong("row_version")));
            if (existing.isEmpty()) {
                UUID markId = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO staff_attendance_marks(id,organisation_id,school_id,
                          staff_attendance_register_id,staff_profile_id,state_code,check_in_time,
                          check_out_time,note,recorded_by_membership_id,created_at,updated_at)
                        VALUES(:id,:organisationId,:schoolId,:registerId,:staffId,:state,:checkIn,
                          :checkOut,:note,:actorMembershipId,:now,:now)
                        """, markParams(scope, markId, registerId, mark.staffProfileId(), state,
                        mark.checkInTime(), mark.checkOutTime(), mark.note(), null));
                markHistory(scope, markId, null, state, null, mark.checkInTime(), null,
                        mark.checkOutTime(), "INITIAL", null);
            } else {
                StoredMark stored = existing.getFirst();
                if (mark.expectedVersion() == null || stored.version() != mark.expectedVersion()) {
                    throw conflict("STALE_STAFF_ATTENDANCE_MARK",
                            "A staff attendance mark changed. Refresh before editing it.");
                }
                int changed = jdbc.update("""
                        UPDATE staff_attendance_marks SET state_code=:state,check_in_time=:checkIn,
                          check_out_time=:checkOut,note=:note,recorded_by_membership_id=:actorMembershipId,
                          updated_at=:now,row_version=row_version+1
                        WHERE id=:id AND row_version=:version
                        """, markParams(scope, stored.id(), registerId, mark.staffProfileId(), state,
                        mark.checkInTime(), mark.checkOutTime(), mark.note(), stored.version()));
                changed(changed);
                markHistory(scope, stored.id(), stored.state(), state, stored.checkIn(), mark.checkInTime(),
                        stored.checkOut(), mark.checkOutTime(), "DRAFT_EDIT", why);
            }
        }
        long nextVersion = touchRegister(scope, registerId, register.version());
        audit(user, scope, "workforce.attendance.marks.recorded", "STAFF_ATTENDANCE_REGISTER",
                registerId, why);
        return new WorkforceMutationResponse(registerId, nextVersion);
    }

    @Transactional
    public WorkforceMutationResponse submitRegister(AuthenticatedUser user, UUID registerId,
            VersionedReasonRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "workforce.attendance.record");
        StoredRegister register = lockRegister(scope, registerId, request.expectedVersion());
        int marked = jdbc.queryForObject("""
                SELECT count(*) FROM staff_attendance_marks
                WHERE staff_attendance_register_id=:registerId
                """, scope.params().addValue("registerId", registerId), Integer.class);
        if (marked != register.expected()) {
            throw conflict("STAFF_ATTENDANCE_INCOMPLETE",
                    "Record every rostered staff member before submitting this register.");
        }
        int changed = jdbc.update("""
                UPDATE staff_attendance_registers SET status='SUBMITTED',
                  submitted_by_membership_id=:actorMembershipId,submitted_at=:now,
                  updated_at=:now,row_version=row_version+1
                WHERE id=:id AND row_version=:version AND status='DRAFT'
                """, scope.params().addValue("id", registerId).addValue("version", register.version())
                .addValue("now", Instant.now(clock)));
        changed(changed);
        audit(user, scope, "workforce.attendance.register.submitted", "STAFF_ATTENDANCE_REGISTER",
                registerId, reason(request.reason()));
        return new WorkforceMutationResponse(registerId, register.version() + 1);
    }

    @Transactional
    public WorkforceMutationResponse requestCorrection(AuthenticatedUser user, CorrectionRequest request) {
        Scope scope = requireScope(user, request.schoolId());
        if (!scope.canRecord() && scope.selfStaffId() == null) {
            throw forbidden("STAFF_PROFILE_LINK_REQUIRED",
                    "Your login is not linked to an active staff profile.");
        }
        StoredMarkContext mark = markContext(scope, request.markId());
        if (!scope.canRecord() && !mark.staffId().equals(scope.selfStaffId())) {
            throw forbidden("STAFF_ATTENDANCE_SCOPE_DENIED",
                    "You may request a correction only for your own attendance.");
        }
        if ("DRAFT".equals(mark.registerStatus())) {
            throw conflict("STAFF_REGISTER_NOT_SUBMITTED",
                    "The attendance recorder can edit this mark directly before submission.");
        }
        PolicyEvidence policy = policyEvidence(mark.policySnapshot());
        String state = enumValue(request.requestedState(), new LinkedHashSet<>(policy.enabledStates()),
                "staff attendance state");
        validateTimes(request.requestedCheckInTime(), request.requestedCheckOutTime());
        UUID id = UUID.randomUUID();
        safe("STAFF_CORRECTION_ALREADY_PENDING", () -> jdbc.update("""
                INSERT INTO staff_attendance_correction_requests(id,organisation_id,school_id,
                  staff_attendance_mark_id,requested_state_code,requested_check_in_time,
                  requested_check_out_time,request_reason,status,requested_by_membership_id,
                  created_at,updated_at)
                VALUES(:id,:organisationId,:schoolId,:markId,:state,:checkIn,:checkOut,:reason,
                  'PENDING',:actorMembershipId,:now,:now)
                """, scope.params().addValue("id", id).addValue("markId", request.markId())
                .addValue("state", state).addValue("checkIn", request.requestedCheckInTime())
                .addValue("checkOut", request.requestedCheckOutTime())
                .addValue("reason", reason(request.reason())).addValue("now", Instant.now(clock))));
        audit(user, scope, "workforce.attendance.correction.requested",
                "STAFF_ATTENDANCE_CORRECTION", id, request.reason());
        return new WorkforceMutationResponse(id, 0);
    }

    @Transactional
    public WorkforceMutationResponse decideCorrection(AuthenticatedUser user, UUID id,
            DecisionRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "workforce.attendance.correct");
        List<StoredCorrection> rows = jdbc.query("""
                SELECT correction.staff_attendance_mark_id,correction.requested_state_code,
                       correction.requested_check_in_time,correction.requested_check_out_time,
                       correction.status,correction.requested_by_membership_id,correction.row_version,
                       mark.state_code,mark.check_in_time,mark.check_out_time
                FROM staff_attendance_correction_requests correction
                JOIN staff_attendance_marks mark ON mark.id=correction.staff_attendance_mark_id
                WHERE correction.id=:id AND correction.organisation_id=:organisationId
                  AND correction.school_id=:schoolId FOR UPDATE OF correction
                """, scope.params().addValue("id", id), (r, n) -> new StoredCorrection(
                r.getObject("staff_attendance_mark_id", UUID.class), r.getString("requested_state_code"),
                r.getObject("requested_check_in_time", LocalTime.class),
                r.getObject("requested_check_out_time", LocalTime.class), r.getString("status"),
                r.getObject("requested_by_membership_id", UUID.class), r.getLong("row_version"),
                r.getString("state_code"), r.getObject("check_in_time", LocalTime.class),
                r.getObject("check_out_time", LocalTime.class)));
        if (rows.isEmpty()) {
            throw notFound("STAFF_CORRECTION_NOT_FOUND", "The staff attendance correction was not found.");
        }
        StoredCorrection correction = rows.getFirst();
        if (correction.version() != request.expectedVersion() || !"PENDING".equals(correction.status())) {
            throw conflict("STALE_STAFF_CORRECTION", "This correction has already changed.");
        }
        if (correction.requesterMembershipId().equals(scope.actorMembershipId())) {
            throw forbidden("INDEPENDENT_STAFF_CORRECTION_REQUIRED",
                    "A different authorized person must decide this correction.");
        }
        String decision = enumValue(request.decision(), Set.of("APPROVED", "REJECTED"), "decision");
        String why = reason(request.reason());
        if ("APPROVED".equals(decision)) {
            jdbc.queryForObject("SELECT set_config('app.staff_attendance_correction','approved',true)",
                    new MapSqlParameterSource(), String.class);
            jdbc.update("""
                    UPDATE staff_attendance_marks SET state_code=:state,check_in_time=:checkIn,
                      check_out_time=:checkOut,recorded_by_membership_id=:actorMembershipId,
                      updated_at=:now,row_version=row_version+1 WHERE id=:markId
                    """, scope.params().addValue("state", correction.requestedState())
                    .addValue("checkIn", correction.requestedCheckIn())
                    .addValue("checkOut", correction.requestedCheckOut())
                    .addValue("markId", correction.markId()).addValue("now", Instant.now(clock)));
            markHistory(scope, correction.markId(), correction.currentState(), correction.requestedState(),
                    correction.currentCheckIn(), correction.requestedCheckIn(), correction.currentCheckOut(),
                    correction.requestedCheckOut(), "CORRECTION", why);
        }
        int changed = jdbc.update("""
                UPDATE staff_attendance_correction_requests SET status=:decision,
                  decided_by_membership_id=:actorMembershipId,decision_reason=:reason,decided_at=:now,
                  updated_at=:now,row_version=row_version+1
                WHERE id=:id AND row_version=:version AND status='PENDING'
                """, scope.params().addValue("decision", decision).addValue("reason", why)
                .addValue("now", Instant.now(clock)).addValue("id", id)
                .addValue("version", correction.version()));
        changed(changed);
        audit(user, scope, "workforce.attendance.correction.decided",
                "STAFF_ATTENDANCE_CORRECTION", id, why);
        return new WorkforceMutationResponse(id, correction.version() + 1);
    }

    @Transactional
    public WorkforceMutationResponse requestLeave(AuthenticatedUser user, LeaveRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "workforce.self");
        if (scope.selfStaffId() == null) {
            throw forbidden("STAFF_PROFILE_LINK_REQUIRED",
                    "Your login must be linked to an active staff profile before requesting leave.");
        }
        String duration = enumValue(request.duration(), DURATIONS, "leave duration");
        if (request.endsOn().isBefore(request.startsOn())) {
            throw bad("LEAVE_DATE_RANGE_INVALID", "Leave end date cannot be before its start date.");
        }
        if (!"FULL_DAY".equals(duration) && !request.startsOn().equals(request.endsOn())) {
            throw bad("HALF_DAY_RANGE_INVALID", "Half-day leave must start and end on the same date.");
        }
        LeaveContext context = validateStaffLeaveContext(scope, request.sessionId(), scope.selfStaffId(),
                request.leaveTypeId(), true);
        if (request.startsOn().isBefore(context.sessionStarts()) || request.endsOn().isAfter(context.sessionEnds())) {
            throw bad("LEAVE_OUTSIDE_SESSION", "Choose leave dates inside the selected academic session.");
        }
        BigDecimal requestedDays = calculateLeaveDays(scope, scope.selfStaffId(), request.sessionId(),
                request.startsOn(), request.endsOn(), duration);
        if (requestedDays.compareTo(BigDecimal.ZERO) <= 0) {
            throw conflict("NO_WORKING_LEAVE_DAYS",
                    "This request contains no attendance-required working day for the staff assignment.");
        }
        if (requestedDays.compareTo(context.maxDays()) > 0) {
            throw bad("LEAVE_REQUEST_LIMIT_EXCEEDED",
                    "This leave type allows at most " + context.maxDays() + " days per request.");
        }
        String key = idempotencyKey(request.idempotencyKey());
        List<StoredLeaveRetry> retries = jdbc.query("""
                SELECT id,academic_session_id,leave_type_id,starts_on,ends_on,duration,request_reason,row_version
                FROM staff_leave_requests
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND idempotency_key=:key
                """, scope.params().addValue("key", key), (r, n) -> new StoredLeaveRetry(
                r.getObject("id", UUID.class), r.getObject("academic_session_id", UUID.class),
                r.getObject("leave_type_id", UUID.class), r.getObject("starts_on", LocalDate.class),
                r.getObject("ends_on", LocalDate.class), r.getString("duration"),
                r.getString("request_reason"), r.getLong("row_version")));
        String why = reason(request.reason());
        if (!retries.isEmpty()) {
            StoredLeaveRetry prior = retries.getFirst();
            if (!prior.matches(request.sessionId(), request.leaveTypeId(), request.startsOn(),
                    request.endsOn(), duration, why)) {
                throw conflict("IDEMPOTENCY_KEY_REUSED",
                        "This retry key was already used for a different leave request.");
            }
            return new WorkforceMutationResponse(prior.id(), prior.version());
        }
        int overlap = jdbc.queryForObject("""
                SELECT count(*) FROM staff_leave_requests
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND staff_profile_id=:staffId AND status IN ('SUBMITTED','APPROVED')
                  AND starts_on<=:endsOn AND ends_on>=:startsOn
                """, scope.params().addValue("staffId", scope.selfStaffId())
                .addValue("startsOn", request.startsOn()).addValue("endsOn", request.endsOn()), Integer.class);
        if (overlap > 0) {
            throw conflict("OVERLAPPING_LEAVE_REQUEST",
                    "An open or approved leave request already overlaps these dates.");
        }
        UUID id = UUID.randomUUID();
        safe("LEAVE_REQUEST_RETRY_CONFLICT", () -> jdbc.update("""
                INSERT INTO staff_leave_requests(id,organisation_id,school_id,academic_session_id,
                  staff_profile_id,leave_type_id,leave_type_snapshot,starts_on,ends_on,duration,
                  requested_day_count,request_reason,idempotency_key,status,requested_by_membership_id,
                  created_at,updated_at)
                VALUES(:id,:organisationId,:schoolId,:sessionId,:staffId,:typeId,CAST(:snapshot AS jsonb),
                  :startsOn,:endsOn,:duration,:days,:reason,:key,'SUBMITTED',:actorMembershipId,:now,:now)
                """, scope.params().addValue("id", id).addValue("sessionId", request.sessionId())
                .addValue("staffId", scope.selfStaffId()).addValue("typeId", request.leaveTypeId())
                .addValue("snapshot", json(context.snapshot())).addValue("startsOn", request.startsOn())
                .addValue("endsOn", request.endsOn()).addValue("duration", duration)
                .addValue("days", requestedDays).addValue("reason", why).addValue("key", key)
                .addValue("now", Instant.now(clock))));
        leaveHistory(scope, id, null, "SUBMITTED", why);
        audit(user, scope, "workforce.leave.requested", "STAFF_LEAVE_REQUEST", id, why);
        return new WorkforceMutationResponse(id, 0);
    }

    @Transactional
    public WorkforceMutationResponse decideLeave(AuthenticatedUser user, UUID id, DecisionRequest request) {
        Scope scope = requireCapability(user, request.schoolId(), "workforce.leave.approve");
        List<StoredLeave> rows = jdbc.query("""
                SELECT request.*,type.balance_required
                FROM staff_leave_requests request
                JOIN staff_leave_types type ON type.id=request.leave_type_id
                WHERE request.id=:id AND request.organisation_id=:organisationId
                  AND request.school_id=:schoolId FOR UPDATE OF request
                """, scope.params().addValue("id", id), (r, n) -> new StoredLeave(
                r.getObject("id", UUID.class), r.getObject("academic_session_id", UUID.class),
                r.getObject("staff_profile_id", UUID.class), r.getObject("leave_type_id", UUID.class),
                r.getObject("starts_on", LocalDate.class), r.getObject("ends_on", LocalDate.class),
                r.getString("duration"), r.getBigDecimal("requested_day_count"), r.getString("status"),
                r.getObject("requested_by_membership_id", UUID.class), r.getBoolean("balance_required"),
                r.getLong("row_version")));
        if (rows.isEmpty()) {
            throw notFound("LEAVE_REQUEST_NOT_FOUND", "The staff leave request was not found.");
        }
        StoredLeave leave = rows.getFirst();
        if (leave.version() != request.expectedVersion() || !"SUBMITTED".equals(leave.status())) {
            throw conflict("STALE_LEAVE_REQUEST", "This leave request has already changed.");
        }
        if (leave.requesterMembershipId().equals(scope.actorMembershipId())) {
            throw forbidden("INDEPENDENT_LEAVE_APPROVAL_REQUIRED",
                    "A different authorized person must decide this leave request.");
        }
        String decision = enumValue(request.decision(), Set.of("APPROVED", "REJECTED"), "decision");
        String why = reason(request.reason());
        if ("APPROVED".equals(decision) && leave.balanceRequired()) {
            BigDecimal available = jdbc.queryForObject("""
                    SELECT COALESCE(sum(quantity),0) FROM staff_leave_balance_entries
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND academic_session_id=:sessionId AND staff_profile_id=:staffId
                      AND leave_type_id=:typeId
                    """, scope.params().addValue("sessionId", leave.sessionId())
                    .addValue("staffId", leave.staffId()).addValue("typeId", leave.leaveTypeId()),
                    BigDecimal.class);
            if (available.compareTo(leave.days()) < 0) {
                throw conflict("INSUFFICIENT_LEAVE_BALANCE",
                        "The available leave balance is lower than this request.");
            }
        }
        int changed = jdbc.update("""
                UPDATE staff_leave_requests SET status=:decision,decided_by_membership_id=:actorMembershipId,
                  decision_reason=:reason,decided_at=:now,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND row_version=:version AND status='SUBMITTED'
                """, scope.params().addValue("decision", decision).addValue("reason", why)
                .addValue("now", Instant.now(clock)).addValue("id", id)
                .addValue("version", leave.version()));
        changed(changed);
        leaveHistory(scope, id, "SUBMITTED", decision, why);
        if ("APPROVED".equals(decision)) {
            if (leave.balanceRequired()) {
                jdbc.update("""
                        INSERT INTO staff_leave_balance_entries(id,organisation_id,school_id,
                          academic_session_id,staff_profile_id,leave_type_id,source_leave_request_id,
                          entry_kind,quantity,idempotency_key,reason,created_by_membership_id,created_at)
                        VALUES(:entryId,:organisationId,:schoolId,:sessionId,:staffId,:typeId,:requestId,
                          'APPROVED_LEAVE',:quantity,:key,:reason,:actorMembershipId,:now)
                        """, scope.params().addValue("entryId", UUID.randomUUID())
                        .addValue("sessionId", leave.sessionId()).addValue("staffId", leave.staffId())
                        .addValue("typeId", leave.leaveTypeId()).addValue("requestId", id)
                        .addValue("quantity", leave.days().negate())
                        .addValue("key", "leave-approved-" + id).addValue("reason", why)
                        .addValue("now", Instant.now(clock)));
            }
            syncApprovedLeave(scope, leave, why);
        }
        audit(user, scope, "workforce.leave.decided", "STAFF_LEAVE_REQUEST", id, why);
        return new WorkforceMutationResponse(id, leave.version() + 1);
    }

    private List<RegisterItem> loadRegisters(Scope scope, MapSqlParameterSource parameters, LocalDate date) {
        List<RegisterItem> base = jdbc.query("""
                SELECT register.id,register.campus_id,register.academic_session_id,
                       register.calendar_day_id,day.calendar_date,day.display_name,
                       register.policy_snapshot,register.status,register.expected_staff_count,
                       count(mark.id) AS recorded_count,
                       count(mark.id) FILTER (WHERE mark.state_code='ABSENT') AS absent_count,
                       register.submitted_at,register.row_version
                FROM staff_attendance_registers register
                JOIN school_calendar_days day ON day.id=register.calendar_day_id
                LEFT JOIN staff_attendance_marks mark ON mark.staff_attendance_register_id=register.id
                WHERE register.organisation_id=:organisationId AND register.school_id=:schoolId
                  AND day.calendar_date=:date
                  AND (:canReadAll OR EXISTS (
                    SELECT 1 FROM staff_assignments assignment
                    WHERE assignment.staff_profile_id=:selfStaffId
                      AND assignment.campus_id=register.campus_id AND assignment.status='ACTIVE'
                      AND assignment.starts_on<=day.calendar_date
                      AND (assignment.ends_on IS NULL OR assignment.ends_on>=day.calendar_date)))
                GROUP BY register.id,day.calendar_date,day.display_name
                ORDER BY register.campus_id,register.id
                """, parameters, (r, n) -> new RegisterItem(r.getObject("id", UUID.class),
                r.getObject("campus_id", UUID.class), r.getObject("academic_session_id", UUID.class),
                r.getObject("calendar_day_id", UUID.class), r.getObject("calendar_date", LocalDate.class),
                r.getString("display_name"), policyEvidence(r.getString("policy_snapshot")).enabledStates(),
                r.getString("status"), r.getInt("expected_staff_count"), r.getInt("recorded_count"),
                r.getInt("absent_count"), r.getTimestamp("submitted_at") == null ? null
                        : r.getTimestamp("submitted_at").toInstant(),
                r.getLong("row_version"), List.of()));
        List<RegisterItem> result = new ArrayList<>();
        for (RegisterItem register : base) {
            List<MarkItem> marks = jdbc.query("""
                    SELECT mark.id,mark.staff_profile_id,profile.display_name,profile.employee_code,
                           mark.state_code,mark.check_in_time,mark.check_out_time,mark.note,mark.row_version
                    FROM staff_attendance_marks mark
                    JOIN staff_profiles profile ON profile.id=mark.staff_profile_id
                    WHERE mark.staff_attendance_register_id=:registerId
                      AND mark.organisation_id=:organisationId AND mark.school_id=:schoolId
                      AND (:canReadAll OR mark.staff_profile_id=:selfStaffId)
                    ORDER BY profile.display_name,profile.id
                    """, scope.params().addValue("registerId", register.id()), (r, n) -> new MarkItem(
                    r.getObject("id", UUID.class), r.getObject("staff_profile_id", UUID.class),
                    r.getString("display_name"), r.getString("employee_code"), r.getString("state_code"),
                    r.getObject("check_in_time", LocalTime.class), r.getObject("check_out_time", LocalTime.class),
                    r.getString("note"), r.getLong("row_version")));
            result.add(new RegisterItem(register.id(), register.campusId(), register.sessionId(),
                    register.calendarDayId(), register.date(), register.dayName(), register.enabledStates(),
                    register.status(), register.expectedStaffCount(), register.recordedCount(),
                    register.absentCount(), register.submittedAt(), register.rowVersion(), marks));
        }
        return List.copyOf(result);
    }

    private List<CorrectionItem> loadCorrections(Scope scope, MapSqlParameterSource parameters) {
        return jdbc.query("""
                SELECT correction.id,correction.staff_attendance_mark_id,mark.staff_profile_id,
                       profile.display_name,mark.state_code,correction.requested_state_code,
                       correction.requested_check_in_time,correction.requested_check_out_time,
                       correction.request_reason,correction.status,
                       requester_user.display_name AS requester_name,
                       correction.created_at,correction.row_version
                FROM staff_attendance_correction_requests correction
                JOIN staff_attendance_marks mark ON mark.id=correction.staff_attendance_mark_id
                JOIN staff_profiles profile ON profile.id=mark.staff_profile_id
                JOIN memberships requester ON requester.id=correction.requested_by_membership_id
                JOIN user_accounts requester_user ON requester_user.id=requester.user_id
                WHERE correction.organisation_id=:organisationId AND correction.school_id=:schoolId
                  AND (:canReadAll OR mark.staff_profile_id=:selfStaffId)
                ORDER BY CASE correction.status WHEN 'PENDING' THEN 0 ELSE 1 END,
                         correction.created_at DESC,correction.id
                LIMIT 200
                """, parameters, (r, n) -> new CorrectionItem(r.getObject("id", UUID.class),
                r.getObject("staff_attendance_mark_id", UUID.class),
                r.getObject("staff_profile_id", UUID.class), r.getString("display_name"),
                r.getString("state_code"), r.getString("requested_state_code"),
                r.getObject("requested_check_in_time", LocalTime.class),
                r.getObject("requested_check_out_time", LocalTime.class),
                r.getString("request_reason"), r.getString("status"), r.getString("requester_name"),
                r.getTimestamp("created_at").toInstant(), r.getLong("row_version")));
    }

    private List<AttendanceSummaryItem> loadSummaries(Scope scope, MapSqlParameterSource parameters) {
        List<SummaryMark> marks = jdbc.query("""
                SELECT mark.staff_profile_id,register.academic_session_id,day.calendar_date,
                       mark.state_code,register.policy_snapshot
                FROM staff_attendance_marks mark
                JOIN staff_attendance_registers register ON register.id=mark.staff_attendance_register_id
                JOIN school_calendar_days day ON day.id=register.calendar_day_id
                WHERE mark.organisation_id=:organisationId AND mark.school_id=:schoolId
                  AND register.status IN ('SUBMITTED','LOCKED')
                  AND (:canReadAll OR mark.staff_profile_id=:selfStaffId)
                ORDER BY mark.staff_profile_id,register.academic_session_id,day.calendar_date DESC
                """, parameters, (r, n) -> new SummaryMark(r.getObject("staff_profile_id", UUID.class),
                r.getObject("academic_session_id", UUID.class), r.getObject("calendar_date", LocalDate.class),
                r.getString("state_code"), policyEvidence(r.getString("policy_snapshot"))));
        Map<SummaryKey, List<SummaryMark>> grouped = new LinkedHashMap<>();
        for (SummaryMark mark : marks) {
            grouped.computeIfAbsent(new SummaryKey(mark.staffId(), mark.sessionId()), ignored -> new ArrayList<>())
                    .add(mark);
        }
        List<AttendanceSummaryItem> result = new ArrayList<>();
        grouped.forEach((key, values) -> {
            BigDecimal credit = values.stream().map(value -> value.policy().stateWeights()
                    .getOrDefault(value.state(), BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal percent = credit.multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
            int consecutive = 0;
            for (SummaryMark value : values) {
                if (!"ABSENT".equals(value.state())) break;
                consecutive++;
            }
            PolicyEvidence latestPolicy = values.getFirst().policy();
            result.add(new AttendanceSummaryItem(key.staffId(), key.sessionId(), values.size(), percent,
                    consecutive, percent.compareTo(latestPolicy.warningThresholdPercent()) < 0,
                    consecutive >= latestPolicy.consecutiveAbsenceThreshold()));
        });
        return List.copyOf(result);
    }

    private Scope requireScope(AuthenticatedUser user, UUID requestedSchoolId) {
        if (user.activeOrganisationId() == null) {
            throw conflict("ORGANISATION_SELECTION_REQUIRED", "Select a school organisation first.");
        }
        List<Scope> rows = jdbc.query("""
                SELECT school.id AS school_id,school.display_name,membership.id AS membership_id,
                  self_profile.id AS self_staff_id,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='workforce.self') can_self,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='workforce.read') can_read_all,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='workforce.attendance.record') can_record,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='workforce.attendance.correct') can_correct,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='workforce.leave.approve') can_approve_leave,
                  EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='workforce.configure') can_configure
                FROM memberships membership
                JOIN schools school ON school.id=membership.school_id
                LEFT JOIN staff_profiles self_profile ON self_profile.school_id=membership.school_id
                  AND self_profile.user_account_id=membership.user_id AND self_profile.status='ACTIVE'
                WHERE membership.user_id=:userId AND membership.organisation_id=:organisationId
                  AND membership.id IN(:membershipIds) AND membership.status='ACTIVE'
                  AND (CAST(:schoolId AS uuid) IS NULL OR membership.school_id=:schoolId)
                  AND (EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                        WHERE mr.membership_id=membership.id AND rc.capability_code='workforce.self')
                    OR EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                        WHERE mr.membership_id=membership.id AND rc.capability_code='workforce.read'))
                ORDER BY can_read_all DESC,membership.created_at LIMIT 1
                """, new MapSqlParameterSource().addValue("userId", user.userId())
                .addValue("organisationId", user.activeOrganisationId())
                .addValue("membershipIds", user.membershipIds()).addValue("schoolId", requestedSchoolId),
                (r, n) -> new Scope(user.activeOrganisationId(), r.getObject("school_id", UUID.class),
                r.getString("display_name"), r.getObject("membership_id", UUID.class),
                r.getObject("self_staff_id", UUID.class), r.getBoolean("can_self"),
                r.getBoolean("can_read_all"), r.getBoolean("can_record"), r.getBoolean("can_correct"),
                r.getBoolean("can_approve_leave"), r.getBoolean("can_configure")));
        if (rows.isEmpty()) {
            throw forbidden("WORKFORCE_SCOPE_DENIED",
                    "Staff attendance and leave access is unavailable for this school.");
        }
        Scope scope = rows.getFirst();
        if (!scope.canReadAll() && scope.selfStaffId() == null) {
            throw forbidden("STAFF_PROFILE_LINK_REQUIRED",
                    "Your login is not linked to an active staff profile.");
        }
        return scope;
    }

    private PayrollBridgeScope requirePayrollBridgeScope(AuthenticatedUser user,
            UUID requestedSchoolId, String requiredCapability) {
        if (user.activeOrganisationId() == null) {
            throw conflict("ORGANISATION_SELECTION_REQUIRED", "Select a school organisation first.");
        }
        List<PayrollBridgeScope> rows = jdbc.query("""
                SELECT school.id AS school_id,membership.id AS membership_id
                FROM memberships membership
                JOIN schools school ON school.id=membership.school_id
                  AND school.organisation_id=membership.organisation_id
                JOIN membership_roles mr ON mr.membership_id=membership.id
                JOIN role_capabilities rc ON rc.role_id=mr.role_id
                WHERE membership.user_id=:userId AND membership.organisation_id=:organisationId
                  AND membership.id IN (:membershipIds) AND membership.status='ACTIVE'
                  AND membership.valid_from<=CURRENT_TIMESTAMP
                  AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                  AND (CAST(:schoolId AS uuid) IS NULL OR membership.school_id=:schoolId)
                  AND rc.capability_code=:capability
                ORDER BY membership.created_at,membership.id LIMIT 1
                """, new MapSqlParameterSource().addValue("userId", user.userId())
                .addValue("organisationId", user.activeOrganisationId())
                .addValue("membershipIds", user.membershipIds())
                .addValue("schoolId", requestedSchoolId)
                .addValue("capability", requiredCapability), (r, n) -> new PayrollBridgeScope(
                user.activeOrganisationId(), r.getObject("school_id", UUID.class),
                r.getObject("membership_id", UUID.class)));
        if (rows.isEmpty()) {
            throw forbidden("PAYROLL_WORKFORCE_SCOPE_DENIED",
                    "Payroll attendance evidence is unavailable for this school.");
        }
        return rows.getFirst();
    }

    private Scope requireCapability(AuthenticatedUser user, UUID schoolId, String capability) {
        Scope scope = requireScope(user, schoolId);
        if (!scope.has(capability)) {
            throw forbidden("WORKFORCE_CAPABILITY_DENIED",
                    "This workforce action is outside your authorized role.");
        }
        return scope;
    }

    private PolicyEvidence validatePolicy(Scope scope, PolicyRequest request) {
        int context = jdbc.queryForObject("""
                SELECT count(*) FROM academic_sessions session
                JOIN campuses campus ON campus.school_id=session.school_id
                  AND campus.organisation_id=session.organisation_id
                WHERE session.id=:sessionId AND campus.id=:campusId
                  AND session.organisation_id=:organisationId AND session.school_id=:schoolId
                  AND campus.status='ACTIVE'
                """, scope.params().addValue("sessionId", request.sessionId())
                .addValue("campusId", request.campusId()), Integer.class);
        if (context != 1) {
            throw bad("WORKFORCE_POLICY_SCOPE_INVALID",
                    "Choose a matching academic session and active campus.");
        }
        LinkedHashSet<String> states = new LinkedHashSet<>();
        request.enabledStates().forEach(value -> states.add(enumValue(value, STATES,
                "staff attendance state")));
        if (!states.containsAll(Set.of("PRESENT", "ABSENT", "LEAVE"))) {
            throw bad("REQUIRED_WORKFORCE_STATES",
                    "Present, absent, and leave must remain enabled.");
        }
        Map<String, BigDecimal> weights = new TreeMap<>();
        request.stateWeights().forEach((key, value) -> {
            String state = enumValue(key, STATES, "staff attendance state weight");
            if (value == null || value.compareTo(BigDecimal.ZERO) < 0
                    || value.compareTo(BigDecimal.ONE) > 0) {
                throw bad("WORKFORCE_WEIGHT_INVALID",
                        "Every enabled state needs a credit weight from zero to one.");
            }
            weights.put(state, value);
        });
        if (!weights.keySet().equals(states)) {
            throw bad("WORKFORCE_WEIGHT_MISMATCH",
                    "Provide exactly one credit weight for every enabled state.");
        }
        String status = enumValue(request.status(), STATUSES, "policy status");
        return new PolicyEvidence(required(request.name()), List.copyOf(states), Map.copyOf(weights),
                request.lockAfterHours(), request.warningThresholdPercent(),
                request.consecutiveAbsenceThreshold(), status);
    }

    private MapSqlParameterSource policyParams(Scope scope, UUID id, PolicyRequest request,
            PolicyEvidence evidence, Long version) {
        return scope.params().addValue("id", id).addValue("sessionId", request.sessionId())
                .addValue("campusId", request.campusId()).addValue("name", evidence.name())
                .addValue("states", evidence.enabledStates().toArray(String[]::new))
                .addValue("weights", json(evidence.stateWeights()))
                .addValue("lockHours", evidence.lockAfterHours())
                .addValue("warning", evidence.warningThresholdPercent())
                .addValue("consecutive", evidence.consecutiveAbsenceThreshold())
                .addValue("status", evidence.status()).addValue("version", version)
                .addValue("now", Instant.now(clock));
    }

    private MapSqlParameterSource leaveTypeParams(Scope scope, UUID id, LeaveTypeRequest request,
            Long version) {
        return scope.params().addValue("id", id).addValue("code", code(request.code()))
                .addValue("name", required(request.name())).addValue("paid", request.paid())
                .addValue("balanceRequired", request.balanceRequired())
                .addValue("maxDays", request.maxDaysPerRequest())
                .addValue("status", enumValue(request.status(), STATUSES, "leave type status"))
                .addValue("version", version).addValue("now", Instant.now(clock));
    }

    private LeaveContext validateStaffLeaveContext(Scope scope, UUID sessionId, UUID staffId,
            UUID leaveTypeId, boolean activeTypeRequired) {
        List<LeaveContext> rows = jdbc.query("""
                SELECT session.starts_on,session.ends_on,type.code,type.display_name,type.paid,
                       type.balance_required,type.max_days_per_request,type.status
                FROM academic_sessions session
                JOIN staff_profiles profile ON profile.school_id=session.school_id
                  AND profile.organisation_id=session.organisation_id AND profile.id=:staffId
                JOIN staff_leave_types type ON type.school_id=session.school_id
                  AND type.organisation_id=session.organisation_id AND type.id=:typeId
                WHERE session.id=:sessionId AND session.organisation_id=:organisationId
                  AND session.school_id=:schoolId AND profile.status='ACTIVE'
                  AND (:activeTypeRequired=FALSE OR type.status='ACTIVE')
                """, scope.params().addValue("sessionId", sessionId).addValue("staffId", staffId)
                .addValue("typeId", leaveTypeId).addValue("activeTypeRequired", activeTypeRequired),
                (r, n) -> new LeaveContext(r.getObject("starts_on", LocalDate.class),
                r.getObject("ends_on", LocalDate.class), r.getBigDecimal("max_days_per_request"),
                new LeaveTypeEvidence(r.getString("code"), r.getString("display_name"),
                        r.getBoolean("paid"), r.getBoolean("balance_required"),
                        r.getBigDecimal("max_days_per_request"))));
        if (rows.isEmpty()) {
            throw bad("STAFF_LEAVE_CONTEXT_INVALID",
                    "Choose a matching active staff profile, session, and leave type.");
        }
        return rows.getFirst();
    }

    private DayContext dayContext(Scope scope, UUID calendarDayId) {
        List<DayContext> rows = jdbc.query("""
                SELECT day.academic_session_id,day.campus_id,day.calendar_date,
                       day.attendance_required,day.status,campus.time_zone
                FROM school_calendar_days day
                JOIN campuses campus ON campus.id=day.campus_id
                WHERE day.id=:dayId AND day.organisation_id=:organisationId AND day.school_id=:schoolId
                """, scope.params().addValue("dayId", calendarDayId), (r, n) -> new DayContext(
                r.getObject("academic_session_id", UUID.class), r.getObject("campus_id", UUID.class),
                r.getObject("calendar_date", LocalDate.class), r.getBoolean("attendance_required"),
                r.getString("status"), r.getString("time_zone")));
        if (rows.isEmpty()) {
            throw notFound("SCHOOL_CALENDAR_DAY_NOT_FOUND", "The governed school day was not found.");
        }
        return rows.getFirst();
    }

    private ActivePolicy activePolicy(Scope scope, UUID sessionId, UUID campusId) {
        List<ActivePolicy> rows = jdbc.query("""
                SELECT id,policy_name,enabled_state_codes,state_weights,lock_after_hours,
                       warning_threshold_percent,consecutive_absence_threshold,status
                FROM workforce_attendance_policies
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND academic_session_id=:sessionId AND campus_id=:campusId AND status='ACTIVE'
                """, scope.params().addValue("sessionId", sessionId).addValue("campusId", campusId),
                (r, n) -> new ActivePolicy(r.getObject("id", UUID.class), new PolicyEvidence(
                r.getString("policy_name"), arrayStrings(r.getArray("enabled_state_codes")),
                stateWeights(r.getString("state_weights")), r.getInt("lock_after_hours"),
                r.getBigDecimal("warning_threshold_percent"),
                r.getInt("consecutive_absence_threshold"), r.getString("status"))));
        if (rows.isEmpty()) {
            throw conflict("WORKFORCE_ATTENDANCE_POLICY_REQUIRED",
                    "Configure an active staff attendance policy for this campus and session first.");
        }
        return rows.getFirst();
    }

    private StoredRegister lockRegister(Scope scope, UUID id, long expectedVersion) {
        List<StoredRegister> rows = jdbc.query("""
                SELECT register.id,register.academic_session_id,register.campus_id,
                       day.calendar_date,register.status,register.expected_staff_count,
                       register.policy_snapshot,register.row_version
                FROM staff_attendance_registers register
                JOIN school_calendar_days day ON day.id=register.calendar_day_id
                WHERE register.id=:id AND register.organisation_id=:organisationId
                  AND register.school_id=:schoolId FOR UPDATE OF register
                """, scope.params().addValue("id", id), (r, n) -> new StoredRegister(
                r.getObject("id", UUID.class), r.getObject("academic_session_id", UUID.class),
                r.getObject("campus_id", UUID.class), r.getObject("calendar_date", LocalDate.class),
                r.getString("status"), r.getInt("expected_staff_count"),
                r.getString("policy_snapshot"), r.getLong("row_version")));
        if (rows.isEmpty()) {
            throw notFound("STAFF_ATTENDANCE_REGISTER_NOT_FOUND",
                    "The staff attendance register was not found.");
        }
        StoredRegister register = rows.getFirst();
        if (!"DRAFT".equals(register.status())) {
            throw conflict("STAFF_ATTENDANCE_REGISTER_IMMUTABLE",
                    "Submitted registers require a correction request.");
        }
        if (register.version() != expectedVersion) {
            throw conflict("STALE_STAFF_ATTENDANCE_REGISTER",
                    "The staff attendance register changed. Refresh and try again.");
        }
        return register;
    }

    private void requireRosterStaff(Scope scope, StoredRegister register, UUID staffId) {
        int count = jdbc.queryForObject("""
                SELECT count(*) FROM staff_profiles profile
                WHERE profile.id=:staffId AND profile.organisation_id=:organisationId
                  AND profile.school_id=:schoolId AND profile.status='ACTIVE' AND profile.joined_on<=:date
                  AND EXISTS(SELECT 1 FROM staff_assignments assignment
                    WHERE assignment.staff_profile_id=profile.id AND assignment.campus_id=:campusId
                      AND assignment.status='ACTIVE' AND assignment.starts_on<=:date
                      AND (assignment.ends_on IS NULL OR assignment.ends_on>=:date))
                """, scope.params().addValue("staffId", staffId).addValue("date", register.date())
                .addValue("campusId", register.campusId()), Integer.class);
        if (count != 1) {
            throw bad("STAFF_OUTSIDE_ATTENDANCE_ROSTER",
                    "A staff member is outside this register's dated campus roster.");
        }
    }

    private void enforceApprovedLeave(Scope scope, StoredRegister register, UUID staffId, String state) {
        List<String> durations = jdbc.query("""
                SELECT duration FROM staff_leave_requests
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND staff_profile_id=:staffId AND academic_session_id=:sessionId
                  AND status='APPROVED' AND starts_on<=:date AND ends_on>=:date
                """, scope.params().addValue("staffId", staffId).addValue("sessionId", register.sessionId())
                .addValue("date", register.date()), (r, n) -> r.getString("duration"));
        if (durations.isEmpty()) return;
        String requiredState = "FULL_DAY".equals(durations.getFirst()) ? "LEAVE" : "HALF_DAY";
        if (!requiredState.equals(state)) {
            throw conflict("APPROVED_LEAVE_ATTENDANCE_CONFLICT",
                    "Approved leave requires this attendance mark to remain " + requiredState.toLowerCase(Locale.ROOT)
                            .replace('_', ' ') + ".");
        }
    }

    private StoredMarkContext markContext(Scope scope, UUID markId) {
        List<StoredMarkContext> rows = jdbc.query("""
                SELECT mark.staff_profile_id,register.status,register.policy_snapshot
                FROM staff_attendance_marks mark
                JOIN staff_attendance_registers register ON register.id=mark.staff_attendance_register_id
                WHERE mark.id=:markId AND mark.organisation_id=:organisationId AND mark.school_id=:schoolId
                """, scope.params().addValue("markId", markId), (r, n) -> new StoredMarkContext(
                r.getObject("staff_profile_id", UUID.class), r.getString("status"),
                r.getString("policy_snapshot")));
        if (rows.isEmpty()) {
            throw notFound("STAFF_ATTENDANCE_MARK_NOT_FOUND", "The staff attendance mark was not found.");
        }
        return rows.getFirst();
    }

    private long touchRegister(Scope scope, UUID id, long version) {
        int changed = jdbc.update("""
                UPDATE staff_attendance_registers SET updated_at=:now,row_version=row_version+1
                WHERE id=:id AND row_version=:version AND status='DRAFT'
                """, scope.params().addValue("id", id).addValue("version", version)
                .addValue("now", Instant.now(clock)));
        changed(changed);
        return version + 1;
    }

    private MapSqlParameterSource markParams(Scope scope, UUID id, UUID registerId, UUID staffId,
            String state, LocalTime checkIn, LocalTime checkOut, String note, Long version) {
        return scope.params().addValue("id", id).addValue("registerId", registerId)
                .addValue("staffId", staffId).addValue("state", state).addValue("checkIn", checkIn)
                .addValue("checkOut", checkOut).addValue("note", clean(note)).addValue("version", version)
                .addValue("now", Instant.now(clock));
    }

    private void seedApprovedLeave(Scope scope, UUID registerId, DayContext day) {
        List<LeaveSeed> leaves = jdbc.query("""
                SELECT DISTINCT request.staff_profile_id,request.duration
                FROM staff_leave_requests request
                WHERE request.organisation_id=:organisationId AND request.school_id=:schoolId
                  AND request.academic_session_id=:sessionId AND request.status='APPROVED'
                  AND request.starts_on<=:date AND request.ends_on>=:date
                  AND EXISTS(SELECT 1 FROM staff_assignments assignment
                    WHERE assignment.staff_profile_id=request.staff_profile_id
                      AND assignment.campus_id=:campusId AND assignment.status='ACTIVE'
                      AND assignment.starts_on<=:date
                      AND (assignment.ends_on IS NULL OR assignment.ends_on>=:date))
                """, scope.params().addValue("sessionId", day.sessionId()).addValue("date", day.date())
                .addValue("campusId", day.campusId()), (r, n) -> new LeaveSeed(
                r.getObject("staff_profile_id", UUID.class), r.getString("duration")));
        for (LeaveSeed leave : leaves) {
            UUID markId = UUID.randomUUID();
            String state = "FULL_DAY".equals(leave.duration()) ? "LEAVE" : "HALF_DAY";
            jdbc.update("""
                    INSERT INTO staff_attendance_marks(id,organisation_id,school_id,
                      staff_attendance_register_id,staff_profile_id,state_code,note,
                      recorded_by_membership_id,created_at,updated_at)
                    VALUES(:id,:organisationId,:schoolId,:registerId,:staffId,:state,:note,
                      :actorMembershipId,:now,:now)
                    ON CONFLICT (staff_attendance_register_id,staff_profile_id) DO NOTHING
                    """, scope.params().addValue("id", markId).addValue("registerId", registerId)
                    .addValue("staffId", leave.staffId()).addValue("state", state)
                    .addValue("note", "Approved leave synchronized from the staff leave workflow")
                    .addValue("now", Instant.now(clock)));
            int exists = jdbc.queryForObject("SELECT count(*) FROM staff_attendance_marks WHERE id=:id",
                    scope.params().addValue("id", markId), Integer.class);
            if (exists == 1) {
                markHistory(scope, markId, null, state, null, null, null, null,
                        "LEAVE_SYNC", "Approved leave synchronized when the register opened");
            }
        }
    }

    private BigDecimal calculateLeaveDays(Scope scope, UUID staffId, UUID sessionId,
            LocalDate startsOn, LocalDate endsOn, String duration) {
        int workingDays = jdbc.queryForObject("""
                SELECT count(DISTINCT day.calendar_date)
                FROM school_calendar_days day
                WHERE day.organisation_id=:organisationId AND day.school_id=:schoolId
                  AND day.academic_session_id=:sessionId AND day.status='ACTIVE'
                  AND day.attendance_required AND day.calendar_date BETWEEN :startsOn AND :endsOn
                  AND EXISTS(SELECT 1 FROM staff_assignments assignment
                    WHERE assignment.staff_profile_id=:staffId AND assignment.campus_id=day.campus_id
                      AND assignment.status='ACTIVE' AND assignment.starts_on<=day.calendar_date
                      AND (assignment.ends_on IS NULL OR assignment.ends_on>=day.calendar_date))
                """, scope.params().addValue("staffId", staffId).addValue("sessionId", sessionId)
                .addValue("startsOn", startsOn).addValue("endsOn", endsOn), Integer.class);
        if (!"FULL_DAY".equals(duration)) {
            if (workingDays > 0) ensureHalfDayEnabled(scope, staffId, sessionId, startsOn);
            return workingDays > 0 ? new BigDecimal("0.50") : BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(workingDays).setScale(2);
    }

    private void ensureHalfDayEnabled(Scope scope, UUID staffId, UUID sessionId, LocalDate date) {
        int enabled = jdbc.queryForObject("""
                SELECT count(*) FROM workforce_attendance_policies policy
                WHERE policy.organisation_id=:organisationId AND policy.school_id=:schoolId
                  AND policy.academic_session_id=:sessionId AND policy.status='ACTIVE'
                  AND 'HALF_DAY'=ANY(policy.enabled_state_codes)
                  AND EXISTS(SELECT 1 FROM staff_assignments assignment
                    WHERE assignment.staff_profile_id=:staffId AND assignment.campus_id=policy.campus_id
                      AND assignment.status='ACTIVE' AND assignment.starts_on<=:date
                      AND (assignment.ends_on IS NULL OR assignment.ends_on>=:date))
                """, scope.params().addValue("staffId", staffId).addValue("sessionId", sessionId)
                .addValue("date", date), Integer.class);
        if (enabled == 0) {
            throw conflict("HALF_DAY_ATTENDANCE_NOT_CONFIGURED",
                    "The staff attendance policy must enable half day before this request can be submitted.");
        }
    }

    private void syncApprovedLeave(Scope scope, StoredLeave leave, String approvalReason) {
        List<RegisterSync> registers = jdbc.query("""
                SELECT register.id,register.status,day.calendar_date
                FROM staff_attendance_registers register
                JOIN school_calendar_days day ON day.id=register.calendar_day_id
                WHERE register.organisation_id=:organisationId AND register.school_id=:schoolId
                  AND register.academic_session_id=:sessionId
                  AND day.calendar_date BETWEEN :startsOn AND :endsOn
                  AND EXISTS(SELECT 1 FROM staff_assignments assignment
                    WHERE assignment.staff_profile_id=:staffId AND assignment.campus_id=register.campus_id
                      AND assignment.status='ACTIVE' AND assignment.starts_on<=day.calendar_date
                      AND (assignment.ends_on IS NULL OR assignment.ends_on>=day.calendar_date))
                ORDER BY day.calendar_date,register.id
                """, scope.params().addValue("sessionId", leave.sessionId())
                .addValue("staffId", leave.staffId()).addValue("startsOn", leave.startsOn())
                .addValue("endsOn", leave.endsOn()), (r, n) -> new RegisterSync(
                r.getObject("id", UUID.class), r.getString("status"),
                r.getObject("calendar_date", LocalDate.class)));
        String state = "FULL_DAY".equals(leave.duration()) ? "LEAVE" : "HALF_DAY";
        for (RegisterSync register : registers) {
            List<StoredMark> marks = jdbc.query("""
                    SELECT id,state_code,check_in_time,check_out_time,note,row_version
                    FROM staff_attendance_marks
                    WHERE staff_attendance_register_id=:registerId AND staff_profile_id=:staffId
                    """, scope.params().addValue("registerId", register.id())
                    .addValue("staffId", leave.staffId()), (r, n) -> new StoredMark(
                    r.getObject("id", UUID.class), r.getString("state_code"),
                    r.getObject("check_in_time", LocalTime.class), r.getObject("check_out_time", LocalTime.class),
                    r.getString("note"), r.getLong("row_version")));
            if (marks.isEmpty()) {
                if (!"DRAFT".equals(register.status())) {
                    throw conflict("SUBMITTED_STAFF_REGISTER_INCOMPLETE",
                            "A submitted staff register is missing the approved leave holder.");
                }
                UUID markId = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO staff_attendance_marks(id,organisation_id,school_id,
                          staff_attendance_register_id,staff_profile_id,state_code,note,
                          recorded_by_membership_id,created_at,updated_at)
                        VALUES(:id,:organisationId,:schoolId,:registerId,:staffId,:state,:note,
                          :actorMembershipId,:now,:now)
                        """, scope.params().addValue("id", markId).addValue("registerId", register.id())
                        .addValue("staffId", leave.staffId()).addValue("state", state)
                        .addValue("note", "Approved leave synchronized from the staff leave workflow")
                        .addValue("now", Instant.now(clock)));
                markHistory(scope, markId, null, state, null, null, null, null,
                        "LEAVE_SYNC", approvalReason);
                touchRegisterWithoutVersion(scope, register.id());
                continue;
            }
            StoredMark mark = marks.getFirst();
            if (state.equals(mark.state()) && mark.checkIn() == null && mark.checkOut() == null) continue;
            if ("DRAFT".equals(register.status())) {
                jdbc.update("""
                        UPDATE staff_attendance_marks SET state_code=:state,check_in_time=NULL,
                          check_out_time=NULL,note=:note,recorded_by_membership_id=:actorMembershipId,
                          updated_at=:now,row_version=row_version+1 WHERE id=:id
                        """, scope.params().addValue("state", state)
                        .addValue("note", "Approved leave synchronized from the staff leave workflow")
                        .addValue("now", Instant.now(clock)).addValue("id", mark.id()));
                markHistory(scope, mark.id(), mark.state(), state, mark.checkIn(), null,
                        mark.checkOut(), null, "LEAVE_SYNC", approvalReason);
                touchRegisterWithoutVersion(scope, register.id());
            } else {
                int created = jdbc.update("""
                        INSERT INTO staff_attendance_correction_requests(id,organisation_id,school_id,
                          staff_attendance_mark_id,requested_state_code,request_reason,status,
                          requested_by_membership_id,created_at,updated_at)
                        VALUES(:id,:organisationId,:schoolId,:markId,:state,:reason,'PENDING',
                          :actorMembershipId,:now,:now)
                        ON CONFLICT DO NOTHING
                        """, scope.params().addValue("id", UUID.randomUUID()).addValue("markId", mark.id())
                        .addValue("state", state)
                        .addValue("reason", "Approved leave requires a controlled attendance correction: "
                                + truncate(approvalReason, 420))
                        .addValue("now", Instant.now(clock)));
                if (created == 0) {
                    int compatible = jdbc.queryForObject("""
                            SELECT count(*) FROM staff_attendance_correction_requests
                            WHERE organisation_id=:organisationId AND school_id=:schoolId
                              AND staff_attendance_mark_id=:markId AND status='PENDING'
                              AND requested_state_code=:state
                              AND requested_check_in_time IS NULL AND requested_check_out_time IS NULL
                            """, scope.params().addValue("markId", mark.id()).addValue("state", state),
                            Integer.class);
                    if (compatible == 0) {
                        throw conflict("STAFF_ATTENDANCE_CORRECTION_ALREADY_PENDING",
                                "Resolve the existing attendance correction before approving this leave request.");
                    }
                }
            }
        }
    }

    private void touchRegisterWithoutVersion(Scope scope, UUID id) {
        jdbc.update("""
                UPDATE staff_attendance_registers SET updated_at=:now,row_version=row_version+1
                WHERE id=:id AND status='DRAFT'
                """, scope.params().addValue("id", id).addValue("now", Instant.now(clock)));
    }

    private void markHistory(Scope scope, UUID markId, String previousState, String newState,
            LocalTime previousCheckIn, LocalTime newCheckIn, LocalTime previousCheckOut,
            LocalTime newCheckOut, String kind, String changeReason) {
        jdbc.update("""
                INSERT INTO staff_attendance_mark_history(id,organisation_id,school_id,
                  staff_attendance_mark_id,previous_state_code,new_state_code,previous_check_in_time,
                  new_check_in_time,previous_check_out_time,new_check_out_time,change_kind,reason,
                  actor_membership_id,occurred_at)
                VALUES(:id,:organisationId,:schoolId,:markId,:previousState,:newState,:previousCheckIn,
                  :newCheckIn,:previousCheckOut,:newCheckOut,:kind,:reason,:actorMembershipId,:now)
                """, scope.params().addValue("id", UUID.randomUUID()).addValue("markId", markId)
                .addValue("previousState", previousState).addValue("newState", newState)
                .addValue("previousCheckIn", previousCheckIn).addValue("newCheckIn", newCheckIn)
                .addValue("previousCheckOut", previousCheckOut).addValue("newCheckOut", newCheckOut)
                .addValue("kind", kind).addValue("reason", clean(changeReason))
                .addValue("now", Instant.now(clock)));
    }

    private void leaveHistory(Scope scope, UUID requestId, String previousStatus,
            String newStatus, String changeReason) {
        jdbc.update("""
                INSERT INTO staff_leave_request_history(id,organisation_id,school_id,
                  staff_leave_request_id,previous_status,new_status,reason,actor_membership_id,occurred_at)
                VALUES(:id,:organisationId,:schoolId,:requestId,:previousStatus,:newStatus,:reason,
                  :actorMembershipId,:now)
                """, scope.params().addValue("id", UUID.randomUUID()).addValue("requestId", requestId)
                .addValue("previousStatus", previousStatus).addValue("newStatus", newStatus)
                .addValue("reason", reason(changeReason)).addValue("now", Instant.now(clock)));
    }

    private void audit(AuthenticatedUser user, Scope scope, String action, String type,
            UUID id, String reason) {
        audit.recordScoped(user, scope.actorMembershipId(), scope.schoolId(), action, type,
                id.toString(), AuditOutcome.SUCCEEDED, clean(reason), Map.of());
    }

    private void validateTimes(LocalTime checkIn, LocalTime checkOut) {
        if (checkIn != null && checkOut != null && checkOut.isBefore(checkIn)) {
            throw bad("STAFF_ATTENDANCE_TIME_INVALID", "Check-out time cannot be before check-in time.");
        }
    }

    private static String idempotencyKey(String value) {
        String key = required(value);
        if (key.length() < 8 || key.length() > 100) {
            throw bad("IDEMPOTENCY_KEY_INVALID", "Use a retry key between 8 and 100 characters.");
        }
        return key;
    }

    private static String code(String value) {
        String code = required(value).toUpperCase(Locale.ROOT).replace(' ', '_');
        if (!code.matches("^[A-Z][A-Z0-9_]{1,39}$")) {
            throw bad("CONFIGURATION_CODE_INVALID",
                    "Use 2 to 40 uppercase letters, numbers, or underscores, starting with a letter.");
        }
        return code;
    }

    private static String enumValue(String value, Set<String> allowed, String label) {
        String normalized = required(value).toUpperCase(Locale.ROOT).replace(' ', '_');
        if (!allowed.contains(normalized)) {
            throw bad("INVALID_VALUE", "Choose a supported " + label + ".");
        }
        return normalized;
    }

    private static long version(Long value) {
        if (value == null) {
            throw bad("EXPECTED_VERSION_REQUIRED", "Include the current record version before editing.");
        }
        return value;
    }

    private static String reason(String value) {
        String safe = clean(value);
        if (safe == null || safe.length() < 3) {
            throw bad("CHANGE_REASON_REQUIRED", "Enter a meaningful reason for this change.");
        }
        return safe;
    }

    private static String required(String value) {
        String safe = clean(value);
        if (safe == null) throw bad("REQUIRED_VALUE_MISSING", "Enter the required value.");
        return safe;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null
                : value.replaceAll("[\\p{Cntrl}]", " ").strip();
    }

    private static String truncate(String value, int maximum) {
        String safe = clean(value);
        if (safe == null) return "approved leave";
        return safe.substring(0, Math.min(safe.length(), maximum));
    }

    private static List<String> arrayStrings(Array value) {
        if (value == null) return List.of();
        try {
            return Arrays.stream((Object[]) value.getArray()).map(Object::toString).toList();
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to read staff attendance states", exception);
        }
    }

    private Map<String, BigDecimal> stateWeights(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JacksonException exception) {
            throw new IllegalStateException("Invalid workforce attendance state weights", exception);
        }
    }

    private PolicyEvidence policyEvidence(String value) {
        try {
            return objectMapper.readValue(value, PolicyEvidence.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Invalid frozen workforce attendance policy", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to encode workforce evidence", exception);
        }
    }

    private static <T> T safe(String code, Work<T> work) {
        try {
            return work.run();
        } catch (DataIntegrityViolationException exception) {
            throw conflict(code, "The same workforce configuration or pending operation already exists.");
        }
    }

    private static void changed(int count) {
        if (count != 1) {
            throw conflict("STALE_VERSION", "The record changed. Refresh and try again.");
        }
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
            UUID actorMembershipId, UUID selfStaffId, boolean canSelf, boolean canReadAll,
            boolean canRecord, boolean canCorrect, boolean canApproveLeave, boolean canConfigure) {
        MapSqlParameterSource params() {
            return new MapSqlParameterSource().addValue("organisationId", organisationId)
                    .addValue("schoolId", schoolId).addValue("actorMembershipId", actorMembershipId)
                    .addValue("selfStaffId", selfStaffId).addValue("canReadAll", canReadAll);
        }

        boolean has(String capability) {
            return switch (capability) {
                case "workforce.self" -> canSelf;
                case "workforce.read" -> canReadAll;
                case "workforce.attendance.record" -> canRecord;
                case "workforce.attendance.correct" -> canCorrect;
                case "workforce.leave.approve" -> canApproveLeave;
                case "workforce.configure" -> canConfigure;
                default -> false;
            };
        }
    }

    private record PayrollBridgeScope(UUID organisationId, UUID schoolId,
            UUID actorMembershipId) {
        MapSqlParameterSource params() {
            return new MapSqlParameterSource().addValue("organisationId", organisationId)
                    .addValue("schoolId", schoolId)
                    .addValue("actorMembershipId", actorMembershipId);
        }
    }

    private record PolicyEvidence(String name, List<String> enabledStates,
            Map<String, BigDecimal> stateWeights, int lockAfterHours,
            BigDecimal warningThresholdPercent, int consecutiveAbsenceThreshold, String status) {}
    private record LeaveTypeEvidence(String code, String name, boolean paid,
            boolean balanceRequired, BigDecimal maxDaysPerRequest) {}
    private record DayContext(UUID sessionId, UUID campusId, LocalDate date,
            boolean attendanceRequired, String status, String timeZone) {}
    private record ActivePolicy(UUID id, PolicyEvidence evidence) {}
    private record LeaveContext(LocalDate sessionStarts, LocalDate sessionEnds,
            BigDecimal maxDays, LeaveTypeEvidence snapshot) {}
    private record ExistingRegister(UUID id, long version) {}
    private record StoredRegister(UUID id, UUID sessionId, UUID campusId, LocalDate date,
            String status, int expected, String policySnapshot, long version) {}
    private record StoredMark(UUID id, String state, LocalTime checkIn,
            LocalTime checkOut, String note, long version) {}
    private record StoredMarkContext(UUID staffId, String registerStatus, String policySnapshot) {}
    private record StoredCorrection(UUID markId, String requestedState,
            LocalTime requestedCheckIn, LocalTime requestedCheckOut, String status,
            UUID requesterMembershipId, long version, String currentState,
            LocalTime currentCheckIn, LocalTime currentCheckOut) {}
    private record StoredLeave(UUID id, UUID sessionId, UUID staffId, UUID leaveTypeId,
            LocalDate startsOn, LocalDate endsOn, String duration, BigDecimal days,
            String status, UUID requesterMembershipId, boolean balanceRequired, long version) {}
    private record LeaveSeed(UUID staffId, String duration) {}
    private record RegisterSync(UUID id, String status, LocalDate date) {}
    private record SummaryKey(UUID staffId, UUID sessionId) {}
    private record SummaryMark(UUID staffId, UUID sessionId, LocalDate date,
            String state, PolicyEvidence policy) {}
    private record StoredBalanceEntry(UUID id, UUID sessionId, UUID staffId,
            UUID leaveTypeId, String kind, BigDecimal quantity, String reason) {
        boolean matches(UUID requestedSession, UUID requestedStaff, UUID requestedType,
                String requestedKind, BigDecimal requestedQuantity, String requestedReason) {
            return sessionId.equals(requestedSession) && staffId.equals(requestedStaff)
                    && leaveTypeId.equals(requestedType) && kind.equals(requestedKind)
                    && quantity.compareTo(requestedQuantity) == 0 && reason.equals(requestedReason);
        }
    }
    private record StoredLeaveRetry(UUID id, UUID sessionId, UUID leaveTypeId,
            LocalDate startsOn, LocalDate endsOn, String duration, String reason, long version) {
        boolean matches(UUID requestedSession, UUID requestedType, LocalDate requestedStarts,
                LocalDate requestedEnds, String requestedDuration, String requestedReason) {
            return sessionId.equals(requestedSession) && leaveTypeId.equals(requestedType)
                    && startsOn.equals(requestedStarts) && endsOn.equals(requestedEnds)
                    && duration.equals(requestedDuration) && reason.equals(requestedReason);
        }
    }

    @FunctionalInterface
    private interface Work<T> { T run(); }
}
