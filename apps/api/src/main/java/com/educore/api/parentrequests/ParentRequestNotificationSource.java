package com.educore.api.parentrequests;

import com.educore.api.tenancy.TenantRequestContext;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves current request ownership and exact public history; no request/message text crosses this boundary. */
@Service
public class ParentRequestNotificationSource {
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    public ParentRequestNotificationSource(NamedParameterJdbcTemplate jdbc,Clock clock) { this.jdbc=jdbc; this.clock=clock; }

    @Transactional(readOnly=true)
    public Update current(UUID organisationId,UUID schoolId,UUID eventId) {
        if(!TenantRequestContext.current().map(s -> organisationId.equals(s.organisationId())).orElse(false))
            throw new IllegalStateException("Request notification requires a matching organisation context");
        return jdbc.query("""
                SELECT * FROM parent_request_notification_sources WHERE event_id=:eventId
                  AND organisation_id=:organisationId AND school_id=:schoolId AND expires_at>:now
                """,new MapSqlParameterSource("eventId",eventId).addValue("organisationId",organisationId)
                .addValue("schoolId",schoolId).addValue("now",clock.instant()),r -> r.next()?new Update(
                    r.getObject("request_id",UUID.class),r.getObject("history_id",UUID.class),r.getObject("message_id",UUID.class),
                    r.getObject("guardian_id",UUID.class),r.getObject("learner_id",UUID.class),r.getObject("user_account_id",UUID.class),
                    r.getString("category"),r.getTimestamp("occurred_at").toInstant(),r.getTimestamp("expires_at").toInstant()):null);
    }
    public record Update(UUID requestId,UUID historyId,UUID messageId,UUID guardianId,UUID learnerId,UUID userId,
                         String category,Instant occurredAt,Instant expiresAt) {}
}
