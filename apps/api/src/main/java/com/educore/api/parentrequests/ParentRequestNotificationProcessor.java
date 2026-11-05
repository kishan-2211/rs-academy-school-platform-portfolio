package com.educore.api.parentrequests;

import com.educore.api.communication.ParentRequestPublicationService;
import com.educore.api.tenancy.TenantRequestContext;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ParentRequestNotificationProcessor {
    private final NamedParameterJdbcTemplate jdbc;
    private final ParentRequestPublicationService publications;
    private final Clock clock;
    public ParentRequestNotificationProcessor(NamedParameterJdbcTemplate jdbc,ParentRequestPublicationService publications,Clock clock) {
        this.jdbc=jdbc; this.publications=publications; this.clock=clock;
    }
    @Transactional
    public UUID processNext(UUID organisationId) {
        var p=scope(organisationId);
        var event=jdbc.query("""
                SELECT evidence.id,request.school_id FROM outbox_events evidence
                JOIN parent_requests request ON request.id::text=evidence.aggregate_id
                WHERE request.organisation_id=:organisationId AND evidence.aggregate_type='PARENT_REQUEST'
                  AND evidence.event_type IN ('parent_request.staff_replied','parent_request.resolve','parent_request.close','parent_request.reopen')
                  AND evidence.processed_at IS NULL AND evidence.available_at<=:now AND evidence.attempt_count<8
                ORDER BY evidence.available_at,evidence.id FOR UPDATE OF evidence SKIP LOCKED LIMIT 1
                """,p,r -> r.next()?new Event(r.getObject("id",UUID.class),r.getObject("school_id",UUID.class)):null);
        if(event==null) return null;
        try {
            UUID notice=publications.publish(organisationId,event.schoolId(),event.id());
            jdbc.update("""
                    UPDATE outbox_events SET processed_at=:now,attempt_count=attempt_count+1,last_error_code=:code
                    WHERE id=:id AND processed_at IS NULL
                    """,p.addValue("id",event.id()).addValue("code",notice==null?"REQUEST_SOURCE_UNAVAILABLE":null));
            return event.id();
        } catch(RuntimeException failure) { throw new PublicationException(event.id()); }
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void recordFailure(UUID organisationId,UUID eventId) {
        jdbc.update("""
                UPDATE outbox_events evidence SET attempt_count=attempt_count+1,last_error_code='REQUEST_NOTIFICATION_FAILED',
                  available_at=:now+interval '1 minute'*least(60,power(2,least(attempt_count,6)))
                WHERE evidence.id=:id AND evidence.aggregate_type='PARENT_REQUEST'
                  AND evidence.event_type IN ('parent_request.staff_replied','parent_request.resolve','parent_request.close','parent_request.reopen')
                  AND evidence.processed_at IS NULL AND evidence.attempt_count<8
                  AND EXISTS (SELECT 1 FROM parent_requests request WHERE request.id::text=evidence.aggregate_id
                    AND request.organisation_id=:organisationId)
                """,scope(organisationId).addValue("id",eventId));
    }
    private MapSqlParameterSource scope(UUID organisationId) {
        if(!TenantRequestContext.current().map(s -> organisationId.equals(s.organisationId())).orElse(false))
            throw new IllegalStateException("Request notification requires a matching organisation context");
        return new MapSqlParameterSource("organisationId",organisationId).addValue("now",clock.instant());
    }
    private record Event(UUID id,UUID schoolId) {}
    public static final class PublicationException extends RuntimeException {
        private static final long serialVersionUID=1L;
        private final UUID eventId;
        PublicationException(UUID eventId) { super("Request notification publication failed"); this.eventId=eventId; }
        public UUID eventId() { return eventId; }
    }
}
