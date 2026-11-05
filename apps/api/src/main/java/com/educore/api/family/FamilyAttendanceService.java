package com.educore.api.family;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class FamilyAttendanceService {

    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public FamilyAttendanceService(
            NamedParameterJdbcTemplate jdbc,
            SecurityAuditService audit,
            ObjectMapper objectMapper,
            Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public FamilyAttendanceResponse attendance(
            AuthenticatedUser user, UUID learnerId, LocalDate requestedFrom, LocalDate requestedTo) {
        FamilyContext context = requireContext(user, learnerId);
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(context.timeZone())));
        LocalDate to = requestedTo == null ? today : requestedTo;
        LocalDate from = requestedFrom == null ? to.minusDays(30) : requestedFrom;
        validateRange(from, to);
        MapSqlParameterSource parameters = context.parameters()
                .addValue("learnerId", learnerId)
                .addValue("from", from)
                .addValue("to", to);
        List<StoredRecord> stored = jdbc.query("""
                SELECT mark.id AS mark_id, day.calendar_date, register.register_mode,
                       register.period_name_snapshot, register.period_starts_at_snapshot,
                       register.period_ends_at_snapshot, mark.state_code, register.policy_snapshot,
                       explanation.id AS explanation_id, explanation.status AS explanation_status
                FROM attendance_marks mark
                JOIN attendance_registers register
                  ON register.id = mark.attendance_register_id
                 AND register.organisation_id = mark.organisation_id
                 AND register.school_id = mark.school_id
                JOIN school_calendar_days day
                  ON day.id = register.calendar_day_id
                 AND day.organisation_id = register.organisation_id
                 AND day.school_id = register.school_id
                LEFT JOIN attendance_explanations explanation
                  ON explanation.attendance_mark_id = mark.id
                 AND explanation.guardian_id = :guardianId
                 AND explanation.organisation_id = mark.organisation_id
                 AND explanation.school_id = mark.school_id
                WHERE mark.organisation_id = :organisationId
                  AND mark.school_id = :schoolId
                  AND mark.learner_id = :learnerId
                  AND register.status IN ('SUBMITTED', 'LOCKED')
                  AND day.calendar_date BETWEEN :from AND :to
                ORDER BY day.calendar_date DESC, register.period_starts_at_snapshot DESC NULLS LAST,
                         register.created_at DESC, mark.id
                """, parameters, (result, row) -> new StoredRecord(
                result.getObject("mark_id", UUID.class),
                result.getObject("calendar_date", LocalDate.class),
                result.getString("register_mode"),
                result.getString("period_name_snapshot"),
                result.getObject("period_starts_at_snapshot", LocalTime.class),
                result.getObject("period_ends_at_snapshot", LocalTime.class),
                result.getString("state_code"),
                policy(result.getString("policy_snapshot")),
                result.getObject("explanation_id", UUID.class),
                result.getString("explanation_status")));

        BigDecimal earned = BigDecimal.ZERO;
        Map<String, Integer> counts = new LinkedHashMap<>();
        BigDecimal warningThreshold = null;
        List<FamilyAttendanceResponse.AttendanceRecord> records = new java.util.ArrayList<>();
        for (StoredRecord record : stored) {
            BigDecimal weight = record.policy().stateWeights().get(record.stateCode());
            if (weight == null) {
                throw new IllegalStateException("Frozen attendance policy does not define the recorded state");
            }
            earned = earned.add(weight);
            counts.merge(record.stateCode(), 1, Integer::sum);
            if (warningThreshold == null) warningThreshold = record.policy().warningThresholdPercent();
            LocalDate allowedUntil = record.date().plusDays(record.policy().guardianExplanationDays());
            boolean canExplain = weight.compareTo(BigDecimal.ONE) < 0
                    && !today.isAfter(allowedUntil)
                    && record.explanationId() == null;
            records.add(new FamilyAttendanceResponse.AttendanceRecord(
                    record.markId(), record.date(), record.registerMode(), record.periodName(),
                    record.startsAt(), record.endsAt(), record.stateCode(), weight, allowedUntil,
                    canExplain, record.explanationId(), record.explanationStatus()));
        }
        BigDecimal percentage = stored.isEmpty() ? null : earned
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(stored.size()), 2, RoundingMode.HALF_UP);
        boolean belowWarning = percentage != null && warningThreshold != null
                && percentage.compareTo(warningThreshold) < 0;
        return new FamilyAttendanceResponse(
                learnerId,
                from,
                to,
                new FamilyAttendanceResponse.Summary(
                        stored.size(), earned.setScale(2, RoundingMode.HALF_UP), percentage,
                        warningThreshold, belowWarning, counts),
                records);
    }

    @Transactional
    public FamilyExplanationResponse explain(
            AuthenticatedUser user, UUID learnerId, UUID markId, String explanation) {
        String cleanExplanation = requiredExplanation(explanation);
        FamilyContext context = requireContext(user, learnerId);
        List<ExplainableMark> marks = jdbc.query("""
                SELECT mark.id, mark.state_code, day.calendar_date, register.policy_snapshot,
                       register.status
                FROM attendance_marks mark
                JOIN attendance_registers register
                  ON register.id = mark.attendance_register_id
                 AND register.organisation_id = mark.organisation_id
                 AND register.school_id = mark.school_id
                JOIN school_calendar_days day
                  ON day.id = register.calendar_day_id
                 AND day.organisation_id = register.organisation_id
                 AND day.school_id = register.school_id
                WHERE mark.id = :markId
                  AND mark.learner_id = :learnerId
                  AND mark.organisation_id = :organisationId
                  AND mark.school_id = :schoolId
                FOR UPDATE OF mark
                """, context.parameters().addValue("markId", markId).addValue("learnerId", learnerId),
                (result, row) -> new ExplainableMark(
                        result.getObject("id", UUID.class), result.getString("state_code"),
                        result.getObject("calendar_date", LocalDate.class),
                        policy(result.getString("policy_snapshot")), result.getString("status")));
        if (marks.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FAMILY_ATTENDANCE_NOT_FOUND",
                    "This attendance record is not available to your verified relationship.");
        }
        ExplainableMark mark = marks.getFirst();
        if (!List.of("SUBMITTED", "LOCKED").contains(mark.registerStatus())) {
            throw conflict("ATTENDANCE_NOT_PUBLISHED", "This attendance record is not available for explanation yet.");
        }
        BigDecimal weight = mark.policy().stateWeights().get(mark.stateCode());
        if (weight == null || weight.compareTo(BigDecimal.ONE) >= 0) {
            throw conflict("ATTENDANCE_EXPLANATION_NOT_APPLICABLE",
                    "This attendance state does not need a guardian explanation under the recorded policy.");
        }
        LocalDate allowedUntil = mark.date().plusDays(mark.policy().guardianExplanationDays());
        if (LocalDate.now(clock.withZone(ZoneId.of(context.timeZone()))).isAfter(allowedUntil)) {
            throw conflict("ATTENDANCE_EXPLANATION_WINDOW_CLOSED",
                    "The school-configured explanation window has closed for this attendance record.");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO attendance_explanations(
                        id, organisation_id, school_id, attendance_mark_id, guardian_id,
                        explanation, status, created_at, updated_at, row_version)
                    VALUES(:id, :organisationId, :schoolId, :markId, :guardianId,
                        :explanation, 'SUBMITTED', :now, :now, 0)
                    """, context.parameters()
                    .addValue("id", id)
                    .addValue("markId", markId)
                    .addValue("explanation", cleanExplanation)
                    .addValue("now", Instant.now(clock)));
        } catch (DataIntegrityViolationException exception) {
            throw conflict("ATTENDANCE_EXPLANATION_ALREADY_EXISTS",
                    "An explanation from this guardian already exists for this attendance record.");
        }
        audit.recordScoped(user, context.actorMembershipId(), context.schoolId(),
                "attendance.explanation.submitted", "ATTENDANCE_EXPLANATION", id.toString(),
                AuditOutcome.SUCCEEDED, "Guardian supplied an attendance explanation", Map.of());
        return new FamilyExplanationResponse(id, 0);
    }

    private FamilyContext requireContext(AuthenticatedUser user, UUID learnerId) {
        if (user.activeOrganisationId() == null) {
            throw conflict("ORGANISATION_SELECTION_REQUIRED",
                    "Select a school organisation before opening family attendance.");
        }
        List<FamilyContext> contexts = jdbc.query("""
                SELECT DISTINCT guardian.id AS guardian_id, learner.school_id,
                       membership.id AS actor_membership_id,campus.time_zone
                FROM guardians guardian
                JOIN learner_guardian_relationships relationship
                  ON relationship.guardian_id = guardian.id
                 AND relationship.organisation_id = guardian.organisation_id
                 AND relationship.school_id = guardian.school_id
                 AND relationship.portal_access_status = 'ACTIVE'
                JOIN learners learner
                  ON learner.id = relationship.learner_id
                 AND learner.organisation_id = relationship.organisation_id
                 AND learner.school_id = relationship.school_id
                 AND learner.status = 'ACTIVE'
                JOIN memberships membership
                  ON membership.user_id = :actorUserId
                 AND membership.organisation_id = guardian.organisation_id
                 AND membership.school_id = guardian.school_id
                 AND membership.status = 'ACTIVE'
                 AND membership.valid_from <= CURRENT_TIMESTAMP
                 AND (membership.valid_until IS NULL OR membership.valid_until > CURRENT_TIMESTAMP)
                JOIN membership_roles membership_role ON membership_role.membership_id = membership.id
                JOIN role_capabilities capability ON capability.role_id = membership_role.role_id
                 AND capability.capability_code = 'portal.guardian'
                JOIN LATERAL (
                    SELECT enrolment.campus_id FROM student_enrolments enrolment
                    WHERE enrolment.learner_id=learner.id
                      AND enrolment.organisation_id=learner.organisation_id
                      AND enrolment.school_id=learner.school_id
                      AND enrolment.status='ACTIVE'
                    ORDER BY enrolment.starts_on DESC,enrolment.id DESC LIMIT 1
                ) current_enrolment ON true
                JOIN campuses campus ON campus.id=current_enrolment.campus_id
                WHERE guardian.user_account_id = :actorUserId
                  AND guardian.organisation_id = :organisationId
                  AND guardian.status = 'ACTIVE'
                  AND learner.id = :learnerId
                  AND membership.id IN (:membershipIds)
                ORDER BY membership.id
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("actorUserId", user.userId())
                .addValue("organisationId", user.activeOrganisationId())
                .addValue("learnerId", learnerId)
                .addValue("membershipIds", user.membershipIds()),
                (result, row) -> new FamilyContext(
                        user.activeOrganisationId(), result.getObject("school_id", UUID.class),
                        result.getObject("guardian_id", UUID.class),
                        result.getObject("actor_membership_id", UUID.class),result.getString("time_zone")));
        if (contexts.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FAMILY_CHILD_NOT_FOUND",
                    "This learner is not available to your verified relationship.");
        }
        return contexts.getFirst();
    }

    private PolicyEvidence policy(String json) {
        try {
            return objectMapper.readValue(json, PolicyEvidence.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Invalid frozen attendance policy", exception);
        }
    }

    private static void validateRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to) || from.isBefore(to.minusDays(366))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTENDANCE_DATE_RANGE_INVALID",
                    "Choose an attendance date range of no more than 367 days.");
        }
    }

    private static String requiredExplanation(String value) {
        String clean = value == null ? "" : value.replaceAll("[\\p{Cntrl}]", " ").strip();
        if (clean.length() < 3 || clean.length() > 1000) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ATTENDANCE_EXPLANATION_INVALID",
                    "Enter an explanation between 3 and 1000 characters.");
        }
        return clean;
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private record FamilyContext(
            UUID organisationId, UUID schoolId, UUID guardianId, UUID actorMembershipId,
            String timeZone) {
        MapSqlParameterSource parameters() {
            return new MapSqlParameterSource()
                    .addValue("organisationId", organisationId)
                    .addValue("schoolId", schoolId)
                    .addValue("guardianId", guardianId);
        }
    }

    private record StoredRecord(
            UUID markId, LocalDate date, String registerMode, String periodName,
            LocalTime startsAt, LocalTime endsAt, String stateCode, PolicyEvidence policy,
            UUID explanationId, String explanationStatus) {}

    private record ExplainableMark(
            UUID id, String stateCode, LocalDate date, PolicyEvidence policy, String registerStatus) {}

    private record PolicyEvidence(
            String policyName,
            String registerMode,
            List<String> enabledStates,
            Map<String, BigDecimal> stateWeights,
            LocalTime submissionCutoff,
            int lockAfterHours,
            int guardianExplanationDays,
            BigDecimal warningThresholdPercent) {}
}
