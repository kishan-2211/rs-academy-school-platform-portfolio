package com.educore.api.communication;

import com.educore.api.learning.LearningPublicationNotificationSource;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Publishes only database-validated revision events into the existing verified inbox. */
@Service
public class LearningPublicationService {
    private final NamedParameterJdbcTemplate jdbc;
    private final LearningPublicationNotificationSource sources;
    private final DomainFamilyDelivery deliveries;
    private final Clock clock;
    public LearningPublicationService(NamedParameterJdbcTemplate jdbc,LearningPublicationNotificationSource sources,
            DomainFamilyDelivery deliveries,Clock clock) {
        this.jdbc=jdbc; this.sources=sources; this.deliveries=deliveries; this.clock=clock;
    }

    @Transactional
    public UUID publish(UUID organisationId,UUID schoolId,UUID eventId) {
        var source=sources.current(organisationId,schoolId,eventId);
        if(source==null) return null;
        var p=new MapSqlParameterSource("organisationId",organisationId).addValue("schoolId",schoolId).addValue("eventId",eventId);
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key,0))) locked",
                new MapSqlParameterSource("key","learning-publication:"+eventId),Integer.class);
        UUID existing=jdbc.query("""
                SELECT id FROM communication_announcements WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND authority_kind='DOMAIN_EVENT' AND source_type='ASSIGNMENT_PUBLICATION' AND domain_event_id=:eventId
                """,p,r->r.next()?r.getObject(1,UUID.class):null);
        if(existing!=null) return existing;
        var recipients=sources.recipients(organisationId,schoolId,eventId);
        if(recipients.isEmpty()) return null;
        UUID id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO communication_announcements(id,organisation_id,school_id,category,priority,audience_kind,
                  audience_reference_id,language_mode,title_en,title_hi,body_en,body_hi,request_push,scheduled_for,expires_at,
                  status,created_at,updated_at,authority_kind,source_type,source_id,domain_event_id,learning_assignment_id,learning_history_id)
                VALUES(:id,:organisationId,:schoolId,'ACADEMIC','NORMAL',:audience,:sectionId,'BILINGUAL',
                  'School learning work has an update','स्कूल के अध्ययन कार्य में नया अपडेट है',
                  'Open Assignments in your verified school portal to read the published work.',
                  'प्रकाशित कार्य पढ़ने के लिए अपने सत्यापित स्कूल पोर्टल में असाइनमेंट खोलें।',
                  :push,:scheduled,:expires,'APPROVED',:now,:now,'DOMAIN_EVENT','ASSIGNMENT_PUBLICATION',:eventId,:eventId,
                  :assignmentId,:historyId)
                """,p.addValue("id",id).addValue("audience",source.audienceKind()).addValue("sectionId",source.sectionId())
                .addValue("push","SECTION_FAMILIES".equals(source.audienceKind())).addValue("scheduled",source.scheduledFor())
                .addValue("expires",source.expiresAt()).addValue("now",clock.instant())
                .addValue("assignmentId",source.assignmentId()).addValue("historyId",source.historyId()));
        for(var recipient:recipients) {
            if("GUARDIAN".equals(recipient.kind()))
                deliveries.attachGuardian(organisationId,schoolId,id,recipient.guardianId(),recipient.learnerId(),recipient.userId());
            else if("STUDENT".equals(recipient.kind()))
                deliveries.attachStudent(organisationId,schoolId,id,recipient.membershipId(),recipient.learnerId(),recipient.userId());
            else throw new IllegalStateException("Unknown learning recipient kind");
        }
        jdbc.update("""
                INSERT INTO communication_announcement_history(id,organisation_id,school_id,communication_announcement_id,
                  event_type,new_status,reason,actor_kind,occurred_at)
                VALUES(:evidenceId,:organisationId,:schoolId,:id,'CREATED','APPROVED',
                  'Automatic update from verified assignment revision and frozen learner audience; no human notice approval asserted',
                  'SYSTEM',:now)
                """,p.addValue("evidenceId",UUID.randomUUID()));
        return id;
    }
}
