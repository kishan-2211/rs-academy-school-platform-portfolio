package com.educore.api.learning;

import com.educore.api.communication.LearningPublicationService;
import com.educore.api.tenancy.TenantRequestContext;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LearningPublicationNotificationProcessor {
    private final NamedParameterJdbcTemplate jdbc;
    private final LearningPublicationService publications;
    private final Clock clock;

    public LearningPublicationNotificationProcessor(NamedParameterJdbcTemplate jdbc,
            LearningPublicationService publications, Clock clock) {
        this.jdbc=jdbc; this.publications=publications; this.clock=clock;
    }

    @Transactional
    public UUID processNext(UUID organisationId) {
        var p=scope(organisationId);
        var event=jdbc.query("""
                SELECT evidence.id,assignment.school_id FROM outbox_events evidence
                JOIN assignments assignment ON assignment.id::text=evidence.aggregate_id
                WHERE assignment.organisation_id=:organisationId AND evidence.aggregate_type='ASSIGNMENT'
                  AND evidence.event_type='learning.assignment.publication' AND evidence.processed_at IS NULL
                  AND evidence.available_at<=:now AND evidence.attempt_count<8
                ORDER BY evidence.available_at,evidence.id FOR UPDATE OF evidence SKIP LOCKED LIMIT 1
                """,p,r -> r.next()?new Event(r.getObject("id",UUID.class),r.getObject("school_id",UUID.class)):null);
        if(event==null) return null;
        try {
            UUID notice=publications.publish(organisationId,event.schoolId(),event.id());
            jdbc.update("""
                    UPDATE outbox_events SET processed_at=:now,attempt_count=attempt_count+1,last_error_code=:code
                    WHERE id=:id AND processed_at IS NULL
                    """,p.addValue("id",event.id()).addValue("code",notice==null?"LEARNING_SOURCE_UNAVAILABLE":null));
            return event.id();
        } catch(RuntimeException failure) { throw new PublicationException(event.id()); }
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void recordFailure(UUID organisationId,UUID eventId) {
        jdbc.update("""
                UPDATE outbox_events evidence SET attempt_count=attempt_count+1,last_error_code='LEARNING_NOTIFICATION_FAILED',
                  available_at=:now+interval '1 minute'*least(60,power(2,least(attempt_count,6)))
                WHERE evidence.id=:id AND evidence.aggregate_type='ASSIGNMENT' AND evidence.event_type='learning.assignment.publication'
                  AND evidence.processed_at IS NULL AND evidence.attempt_count<8
                  AND EXISTS(SELECT 1 FROM assignments assignment WHERE assignment.id::text=evidence.aggregate_id
                    AND assignment.organisation_id=:organisationId)
                """,scope(organisationId).addValue("id",eventId));
    }

    private MapSqlParameterSource scope(UUID organisationId) {
        if(!TenantRequestContext.current().map(s -> organisationId.equals(s.organisationId())).orElse(false))
            throw new IllegalStateException("Learning notification requires a matching organisation context");
        return new MapSqlParameterSource("organisationId",organisationId).addValue("now",clock.instant());
    }
    private record Event(UUID id,UUID schoolId) {}
    public static final class PublicationException extends RuntimeException {
        private static final long serialVersionUID=1L;
        private final UUID eventId;
        PublicationException(UUID eventId) { super("Learning notification publication failed"); this.eventId=eventId; }
        public UUID eventId() { return eventId; }
    }
}
