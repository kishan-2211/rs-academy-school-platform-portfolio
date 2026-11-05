package com.educore.api.studentleave;

import com.educore.api.tenancy.TenantRequestContext;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Internal notification authority, not an endpoint: never returns a private leave reason or type. */
@Service
public class StudentLeaveNotificationSource {
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public StudentLeaveNotificationSource(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc=jdbc;
        this.clock=clock;
    }

    @Transactional(readOnly=true)
    public Update current(UUID organisationId, UUID schoolId, UUID eventId) {
        if(!TenantRequestContext.current().map(scope -> organisationId.equals(scope.organisationId())).orElse(false))
            throw new IllegalStateException("Student leave notification requires a matching organisation context");
        return jdbc.query("""
                SELECT event_id,request_id,history_id,guardian_id,learner_id,user_account_id,
                  request_version,status,occurred_at,expires_at FROM student_leave_notification_sources
                WHERE event_id=:eventId AND organisation_id=:organisationId AND school_id=:schoolId
                  AND occurred_at<=:now AND expires_at>:now
                """,new MapSqlParameterSource("eventId",eventId).addValue("organisationId",organisationId)
                .addValue("schoolId",schoolId).addValue("now",clock.instant()),row -> row.next()?new Update(
                    row.getObject("event_id",UUID.class),row.getObject("request_id",UUID.class),row.getObject("history_id",UUID.class),
                    row.getObject("guardian_id",UUID.class),row.getObject("learner_id",UUID.class),row.getObject("user_account_id",UUID.class),
                    row.getLong("request_version"),row.getString("status"),row.getTimestamp("occurred_at").toInstant(),
                    row.getTimestamp("expires_at").toInstant()):null);
    }

    public record Update(UUID eventId, UUID requestId, UUID historyId, UUID guardianId, UUID learnerId,
                         UUID userId, long requestVersion, String status, Instant occurredAt, Instant expiresAt) {}
}
