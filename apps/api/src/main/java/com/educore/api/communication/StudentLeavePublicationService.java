package com.educore.api.communication;

import com.educore.api.studentleave.StudentLeaveNotificationSource;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Publishes a generic decision update through the existing private family inbox and delivery ledger. */
@Service
public class StudentLeavePublicationService {
    private final NamedParameterJdbcTemplate jdbc;
    private final StudentLeaveNotificationSource sources;
    private final DomainFamilyDelivery deliveries;
    private final Clock clock;
    public StudentLeavePublicationService(NamedParameterJdbcTemplate jdbc,StudentLeaveNotificationSource sources,
            DomainFamilyDelivery deliveries,Clock clock) {
        this.jdbc=jdbc; this.sources=sources; this.deliveries=deliveries; this.clock=clock;
    }

    @Transactional
    public UUID publish(UUID organisationId,UUID schoolId,UUID eventId) {
        var source=sources.current(organisationId,schoolId,eventId);
        if(source==null) return null;
        var p=new MapSqlParameterSource("organisationId",organisationId).addValue("schoolId",schoolId).addValue("eventId",eventId);
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key,0))) locked",
                new MapSqlParameterSource("key","student-leave-notification:"+eventId),Integer.class);
        UUID existing=jdbc.query("""
                SELECT id FROM communication_announcements WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND authority_kind='DOMAIN_EVENT' AND source_type='STUDENT_LEAVE_UPDATE' AND domain_event_id=:eventId
                """,p,row -> row.next()?row.getObject(1,UUID.class):null);
        if(existing!=null) return existing;
        UUID id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO communication_announcements(id,organisation_id,school_id,category,priority,audience_kind,
                  audience_reference_id,language_mode,title_en,title_hi,body_en,body_hi,request_push,scheduled_for,expires_at,
                  status,created_at,updated_at,authority_kind,source_type,source_id,domain_event_id,
                  student_leave_request_id,student_leave_history_id)
                VALUES(:id,:organisationId,:schoolId,'ATTENDANCE','NORMAL','LEARNER_FAMILIES',:learnerId,'BILINGUAL',
                  'Your leave request has an update','आपके अवकाश अनुरोध में नया अपडेट है',
                  'Open Student leave in the family portal to check the current school decision.',
                  'स्कूल का वर्तमान निर्णय देखने के लिए परिवार पोर्टल में विद्यार्थी अवकाश खोलें।',
                  true,:scheduled,:expires,'APPROVED',:now,:now,'DOMAIN_EVENT','STUDENT_LEAVE_UPDATE',:eventId,:eventId,
                  :requestId,:historyId)
                """,p.addValue("id",id).addValue("learnerId",source.learnerId()).addValue("scheduled",source.occurredAt())
                .addValue("expires",source.expiresAt()).addValue("now",clock.instant())
                .addValue("requestId",source.requestId()).addValue("historyId",source.historyId()));
        deliveries.attach(organisationId,schoolId,id,source.guardianId(),source.learnerId(),source.userId(),
                "Automatic update from verified student leave decision history; no human notice approval asserted");
        return id;
    }
}
