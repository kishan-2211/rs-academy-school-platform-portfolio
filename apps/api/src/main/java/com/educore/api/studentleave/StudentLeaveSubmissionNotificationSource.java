package com.educore.api.studentleave;

import com.educore.api.tenancy.TenantRequestContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal current-source/reviewer authority; never projects learner names, dates, reasons or leave types. */
@Service
public class StudentLeaveSubmissionNotificationSource {
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public StudentLeaveSubmissionNotificationSource(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Submission current(UUID organisationId, UUID schoolId, UUID eventId) {
        return jdbc.query("""
                SELECT event_id,request_id,history_id,request_version,occurred_at,expires_at
                FROM student_leave_submission_notification_sources
                WHERE event_id=:eventId AND organisation_id=:organisationId AND school_id=:schoolId
                  AND occurred_at<=:now AND expires_at>:now
                """, parameters(organisationId, schoolId, eventId), rows -> rows.next()
                ? new Submission(rows.getObject("event_id", UUID.class), rows.getObject("request_id", UUID.class),
                        rows.getObject("history_id", UUID.class), rows.getLong("request_version"),
                        rows.getTimestamp("occurred_at").toInstant(), rows.getTimestamp("expires_at").toInstant()) : null);
    }

    @Transactional(readOnly = true)
    public List<Reviewer> reviewers(UUID organisationId, UUID schoolId, UUID eventId) {
        return jdbc.query("""
                SELECT DISTINCT ON (user_account_id) membership_id,user_account_id
                FROM student_leave_submission_notification_reviewers
                WHERE event_id=:eventId AND organisation_id=:organisationId AND school_id=:schoolId
                  AND occurred_at<=:now AND expires_at>:now
                ORDER BY user_account_id,membership_created_at,membership_id
                """, parameters(organisationId, schoolId, eventId), (row, index) ->
                new Reviewer(row.getObject("membership_id", UUID.class), row.getObject("user_account_id", UUID.class)));
    }

    @Transactional(readOnly = true)
    public boolean isCurrentReviewer(UUID organisationId, UUID schoolId, UUID eventId,
            UUID membershipId, UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM student_leave_submission_notification_reviewers
                  WHERE event_id=:eventId AND organisation_id=:organisationId AND school_id=:schoolId
                    AND membership_id=:membershipId AND user_account_id=:userId
                    AND occurred_at<=:now AND expires_at>:now)
                """, parameters(organisationId, schoolId, eventId)
                .addValue("membershipId", membershipId).addValue("userId", userId), Boolean.class));
    }

    private MapSqlParameterSource parameters(UUID organisationId, UUID schoolId, UUID eventId) {
        if (!TenantRequestContext.current().map(scope -> organisationId.equals(scope.organisationId())).orElse(false))
            throw new IllegalStateException("Student leave submission notification requires a matching organisation context");
        return new MapSqlParameterSource("organisationId", organisationId).addValue("schoolId", schoolId)
                .addValue("eventId", eventId).addValue("now", clock.instant());
    }

    public record Submission(UUID eventId, UUID requestId, UUID historyId, long requestVersion,
                             Instant occurredAt, Instant expiresAt) {}
    public record Reviewer(UUID membershipId, UUID userId) {}
}
