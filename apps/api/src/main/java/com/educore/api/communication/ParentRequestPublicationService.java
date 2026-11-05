package com.educore.api.communication;

import com.educore.api.parentrequests.ParentRequestNotificationSource;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ParentRequestPublicationService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ParentRequestNotificationSource sources;
    private final DomainFamilyDelivery deliveries;
    private final Clock clock;
    public ParentRequestPublicationService(NamedParameterJdbcTemplate jdbc,ParentRequestNotificationSource sources,
            DomainFamilyDelivery deliveries,Clock clock) { this.jdbc=jdbc; this.sources=sources; this.deliveries=deliveries; this.clock=clock; }

    @Transactional
    public UUID publish(UUID organisationId,UUID schoolId,UUID eventId) {
        var source=sources.current(organisationId,schoolId,eventId);
        if(source==null) return null;
        var p=new MapSqlParameterSource("organisationId",organisationId).addValue("schoolId",schoolId).addValue("eventId",eventId);
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key,0))) locked",
                new MapSqlParameterSource("key","request-notification:"+eventId),Integer.class);
        UUID existing=jdbc.query("""
                SELECT id FROM communication_announcements WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND authority_kind='DOMAIN_EVENT' AND source_type='PARENT_REQUEST_UPDATE' AND domain_event_id=:eventId
                """,p,r -> r.next()?r.getObject(1,UUID.class):null);
        if(existing!=null) return existing;
        UUID id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO communication_announcements(id,organisation_id,school_id,category,priority,audience_kind,
                  audience_reference_id,language_mode,title_en,title_hi,body_en,body_hi,request_push,scheduled_for,expires_at,
                  status,created_at,updated_at,authority_kind,source_type,source_id,domain_event_id,
                  parent_request_id,parent_request_history_id,parent_request_message_id)
                VALUES(:id,:organisationId,:schoolId,:category,'NORMAL','LEARNER_FAMILIES',:learnerId,'BILINGUAL',
                  'Your school request has an update','आपके स्कूल अनुरोध में नया अपडेट है',
                  'Open Requests in the family portal to read the latest response or status.',
                  'नवीनतम उत्तर या स्थिति देखने के लिए परिवार पोर्टल में अनुरोध खोलें।',
                  true,:scheduled,:expires,'APPROVED',:now,:now,'DOMAIN_EVENT','PARENT_REQUEST_UPDATE',:eventId,:eventId,
                  :requestId,:historyId,:messageId)
                """,p.addValue("id",id).addValue("category",source.category()).addValue("learnerId",source.learnerId())
                .addValue("scheduled",source.occurredAt()).addValue("expires",source.expiresAt()).addValue("now",clock.instant())
                .addValue("requestId",source.requestId()).addValue("historyId",source.historyId()).addValue("messageId",source.messageId()));
        deliveries.attach(organisationId,schoolId,id,source.guardianId(),source.learnerId(),source.userId(),
                "Automatic update from verified public request history; no human notice approval asserted");
        return id;
    }
}
