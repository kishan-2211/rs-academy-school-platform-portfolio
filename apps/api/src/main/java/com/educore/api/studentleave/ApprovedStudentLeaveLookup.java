package com.educore.api.studentleave;

import com.educore.api.common.api.ApiException;
import java.time.LocalDate;
import java.time.Instant;
import java.util.Collection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Internal domain boundary. Call only after authorizing the exact attendance register and roster. */
@Service
public class ApprovedStudentLeaveLookup {
    private final NamedParameterJdbcTemplate jdbc;

    public ApprovedStudentLeaveLookup(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Hold the source row lock until the caller's register transaction commits. A concurrent
    // withdrawal/revocation cannot race the initial attendance evidence capture.
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, Approval> forRoster(UUID organisationId, UUID schoolId, UUID sessionId,
            UUID campusId, LocalDate date, Collection<UUID> enrolmentIds) {
        if (enrolmentIds.isEmpty()) return Map.of();
        var parameters = new MapSqlParameterSource("organisationId", organisationId)
                .addValue("schoolId", schoolId).addValue("sessionId", sessionId)
                .addValue("campusId", campusId).addValue("date", date)
                .addValue("enrolmentIds", enrolmentIds);
        var approvals = jdbc.query("""
                SELECT request.id, request.enrolment_id, request.row_version,
                       request.policy_snapshot->>'attendanceState' attendance_state
                FROM student_leave_requests request
                JOIN student_leave_date_claims claim ON claim.request_id=request.id
                  AND claim.organisation_id=request.organisation_id AND claim.school_id=request.school_id
                  AND claim.learner_id=request.learner_id AND claim.calendar_date=:date
                JOIN student_leave_history evidence ON evidence.request_id=request.id
                  AND evidence.organisation_id=request.organisation_id AND evidence.school_id=request.school_id
                  AND evidence.request_version=request.row_version AND evidence.new_status='APPROVED'
                WHERE request.organisation_id=:organisationId AND request.school_id=:schoolId
                  AND request.academic_session_id=:sessionId AND request.campus_id=:campusId
                  AND request.enrolment_id IN (:enrolmentIds) AND request.status='APPROVED'
                  AND :date BETWEEN request.starts_on AND request.ends_on
                ORDER BY request.id FOR SHARE OF request
                """, parameters, (row, index) -> new Approval(row.getObject("id", UUID.class),
                row.getObject("enrolment_id", UUID.class), row.getLong("row_version"),
                row.getString("attendance_state")));
        Map<UUID, Approval> result = new HashMap<>();
        for (Approval approval : approvals) {
            if (approval.attendanceState() == null
                    || !Set.of("EXCUSED", "ABSENT").contains(approval.attendanceState())
                    || result.putIfAbsent(approval.enrolmentId(), approval) != null) {
                throw new ApiException(HttpStatus.CONFLICT, "LEAVE_ATTENDANCE_EVIDENCE_INVALID",
                        "Approved leave evidence needs school review before opening attendance.");
            }
        }
        return Map.copyOf(result);
    }

    public record Approval(UUID requestId, UUID enrolmentId, long requestVersion, String attendanceState) {}

    /** Read-only context for an already authorized register. Never includes private leave reasons. */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Map<UUID, List<AttendanceEvidence>> forRecordedMarks(UUID organisationId, UUID schoolId,
            UUID registerId, Collection<UUID> markIds) {
        if (markIds.isEmpty()) return Map.of();
        var parameters = new MapSqlParameterSource("organisationId", organisationId)
                .addValue("schoolId", schoolId).addValue("registerId", registerId).addValue("markIds", markIds);
        var rows = jdbc.query("""
                SELECT mark.id mark_id, request.id request_id, request.row_version, request.status,
                  request.policy_snapshot->>'attendanceState' attendance_state,
                  initial.id IS NOT NULL initialized_from_leave,
                  review.id review_id,review.decision review_decision,review.occurred_at reviewed_at
                FROM attendance_marks mark
                JOIN attendance_registers register ON register.id=mark.attendance_register_id
                  AND register.organisation_id=mark.organisation_id AND register.school_id=mark.school_id
                JOIN school_calendar_days day ON day.id=register.calendar_day_id
                  AND day.organisation_id=register.organisation_id AND day.school_id=register.school_id
                JOIN student_leave_requests request ON request.organisation_id=mark.organisation_id
                  AND request.school_id=mark.school_id AND request.learner_id=mark.learner_id
                  AND request.enrolment_id=mark.enrolment_id AND request.campus_id=register.campus_id
                  AND request.academic_session_id=register.academic_session_id
                  AND day.calendar_date BETWEEN request.starts_on AND request.ends_on
                JOIN student_leave_history evidence ON evidence.request_id=request.id
                  AND evidence.request_version=request.row_version AND evidence.new_status=request.status
                  AND evidence.organisation_id=request.organisation_id AND evidence.school_id=request.school_id
                LEFT JOIN attendance_mark_history initial ON initial.attendance_mark_id=mark.id
                  AND initial.student_leave_request_id=request.id AND initial.change_kind='LEAVE_INITIAL'
                  AND initial.organisation_id=mark.organisation_id AND initial.school_id=mark.school_id
                LEFT JOIN LATERAL (
                  SELECT receipt.id,receipt.decision,receipt.occurred_at FROM attendance_leave_reviews receipt
                  WHERE receipt.organisation_id=mark.organisation_id AND receipt.school_id=mark.school_id
                    AND receipt.attendance_mark_id=mark.id AND receipt.student_leave_request_id=request.id
                    AND receipt.student_leave_request_version=request.row_version
                    AND receipt.resulting_mark_version=mark.row_version
                  ORDER BY receipt.resulting_register_version DESC,receipt.id LIMIT 1
                ) review ON true
                WHERE mark.organisation_id=:organisationId AND mark.school_id=:schoolId
                  AND register.id=:registerId AND mark.id IN (:markIds)
                  AND ((request.status='APPROVED' AND EXISTS (
                    SELECT 1 FROM student_leave_date_claims claim WHERE claim.request_id=request.id
                      AND claim.organisation_id=request.organisation_id AND claim.school_id=request.school_id
                      AND claim.learner_id=request.learner_id AND claim.calendar_date=day.calendar_date))
                    OR (request.status IN ('WITHDRAWN','REVOKED') AND (initial.id IS NOT NULL OR EXISTS (
                      SELECT 1 FROM attendance_correction_requests correction
                      WHERE correction.student_leave_request_id=request.id AND correction.attendance_mark_id=mark.id
                        AND correction.organisation_id=mark.organisation_id AND correction.school_id=mark.school_id
                        AND correction.status IN ('PENDING','APPROVED')) OR EXISTS (
                      SELECT 1 FROM attendance_leave_reviews receipt WHERE receipt.attendance_mark_id=mark.id
                        AND receipt.student_leave_request_id=request.id AND receipt.organisation_id=mark.organisation_id
                        AND receipt.school_id=mark.school_id AND receipt.decision='APPLY_APPROVED'))))
                ORDER BY mark.id, request.created_at, request.id
                """, parameters, (row, index) -> new MarkEvidence(row.getObject("mark_id", UUID.class),
                        new AttendanceEvidence(row.getObject("request_id", UUID.class), row.getLong("row_version"),
                                row.getString("status"), row.getString("attendance_state"),
                                row.getBoolean("initialized_from_leave"),row.getObject("review_id",UUID.class)==null?null:
                                new ReviewReceipt(row.getObject("review_id",UUID.class),row.getString("review_decision"),
                                        row.getTimestamp("reviewed_at").toInstant()))));
        Map<UUID, List<AttendanceEvidence>> result = new HashMap<>();
        for (MarkEvidence row : rows) result.computeIfAbsent(row.markId(), ignored -> new ArrayList<>()).add(row.evidence());
        result.replaceAll((id, evidence) -> List.copyOf(evidence));
        return Map.copyOf(result);
    }

    public record AttendanceEvidence(UUID requestId, long requestVersion, String status,
            String attendanceState, boolean initializedFromLeave, ReviewReceipt review) {}
    public record ReviewReceipt(UUID id,String decision,Instant occurredAt) {}
    private record MarkEvidence(UUID markId, AttendanceEvidence evidence) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public AttendanceEvidence requireReviewSource(UUID organisationId,UUID schoolId,UUID registerId,
            UUID markId,UUID requestId,long requestVersion) {
        var versions=jdbc.query("""
                SELECT row_version FROM student_leave_requests WHERE id=:id AND organisation_id=:organisationId
                  AND school_id=:schoolId FOR SHARE
                """,new MapSqlParameterSource("id",requestId).addValue("organisationId",organisationId)
                .addValue("schoolId",schoolId),(row,index)->row.getLong("row_version"));
        if(versions.size()!=1 || versions.getFirst()!=requestVersion) throw staleReview();
        return forRecordedMarks(organisationId,schoolId,registerId,List.of(markId)).getOrDefault(markId,List.of())
                .stream().filter(source->source.requestId().equals(requestId)&&source.requestVersion()==requestVersion)
                .findFirst().orElseThrow(ApprovedStudentLeaveLookup::staleReview);
    }

    private static ApiException staleReview() {
        return new ApiException(HttpStatus.CONFLICT,"LEAVE_REVIEW_SOURCE_CHANGED",
                "The leave evidence changed or does not apply to this mark. Refresh before reviewing attendance.");
    }

    /** Call after attendance permission and register assignment checks. Lock mark before leave source. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireCorrectionApproval(UUID organisationId, UUID schoolId, UUID markId,
            UUID requestId, long requestVersion, long expectedMarkVersion, String requestedState) {
        var p = new MapSqlParameterSource("organisationId",organisationId).addValue("schoolId",schoolId)
                .addValue("markId",markId).addValue("requestId",requestId).addValue("version",requestVersion)
                .addValue("state",requestedState);
        var marks = jdbc.query("""
                SELECT row_version FROM attendance_marks WHERE id=:markId
                  AND organisation_id=:organisationId AND school_id=:schoolId FOR UPDATE
                """,p,(row,index)->row.getLong("row_version"));
        if (marks.size()!=1 || marks.getFirst()!=expectedMarkVersion) throw staleCorrection();
        var sources = jdbc.query("""
                SELECT request.id FROM student_leave_requests request
                JOIN student_leave_history evidence ON evidence.request_id=request.id
                  AND evidence.request_version=request.row_version AND evidence.new_status='APPROVED'
                  AND evidence.organisation_id=request.organisation_id AND evidence.school_id=request.school_id
                JOIN attendance_marks mark ON mark.id=:markId AND mark.organisation_id=request.organisation_id
                  AND mark.school_id=request.school_id AND mark.enrolment_id=request.enrolment_id
                  AND mark.learner_id=request.learner_id
                JOIN attendance_registers register ON register.id=mark.attendance_register_id
                  AND register.organisation_id=mark.organisation_id AND register.school_id=mark.school_id
                  AND register.campus_id=request.campus_id AND register.academic_session_id=request.academic_session_id
                JOIN school_calendar_days day ON day.id=register.calendar_day_id
                  AND day.organisation_id=register.organisation_id AND day.school_id=register.school_id
                JOIN student_leave_date_claims claim ON claim.request_id=request.id AND claim.calendar_date=day.calendar_date
                  AND claim.organisation_id=request.organisation_id AND claim.school_id=request.school_id
                  AND claim.learner_id=request.learner_id
                WHERE request.id=:requestId AND request.organisation_id=:organisationId AND request.school_id=:schoolId
                  AND request.status='APPROVED' AND request.row_version=:version
                  AND request.policy_snapshot->>'attendanceState'=:state
                  AND day.calendar_date BETWEEN request.starts_on AND request.ends_on
                  AND register.status IN ('SUBMITTED','LOCKED')
                  AND jsonb_exists(register.policy_snapshot->'enabledStates',:state)
                FOR SHARE OF request
                """,p,(row,index)->row.getObject("id",UUID.class));
        if (sources.size()!=1) throw staleCorrection();
    }

    private static ApiException staleCorrection() {
        return new ApiException(HttpStatus.CONFLICT,"LEAVE_CORRECTION_SOURCE_CHANGED",
                "The leave approval or attendance mark changed. Refresh and review the correction source.");
    }
}
