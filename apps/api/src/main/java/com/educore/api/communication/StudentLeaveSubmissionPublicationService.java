package com.educore.api.communication;

import com.educore.api.studentleave.StudentLeaveSubmissionNotificationSource;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One generic pending-request alert per verified event, for independent current reviewers only. */
@Service
public class StudentLeaveSubmissionPublicationService {
    private final NamedParameterJdbcTemplate jdbc;
    private final StudentLeaveSubmissionNotificationSource sources;
    private final DomainFamilyDelivery deliveries;
    private final Clock clock;

    public StudentLeaveSubmissionPublicationService(NamedParameterJdbcTemplate jdbc,
            StudentLeaveSubmissionNotificationSource sources,DomainFamilyDelivery deliveries,Clock clock) {
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
                  AND authority_kind='DOMAIN_EVENT' AND source_type='STUDENT_LEAVE_SUBMISSION' AND domain_event_id=:eventId
                """,p,row -> row.next()?row.getObject(1,UUID.class):null);
        if(existing!=null) return existing;
        var reviewers=sources.reviewers(organisationId,schoolId,eventId);
        if(reviewers.isEmpty()) throw new IllegalStateException("Student leave submission has no eligible independent reviewers");
        UUID id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO communication_announcements(id,organisation_id,school_id,category,priority,audience_kind,
                  language_mode,title_en,title_hi,body_en,body_hi,request_push,scheduled_for,expires_at,
                  status,created_at,updated_at,authority_kind,source_type,source_id,domain_event_id,
                  student_leave_request_id,student_leave_history_id)
                VALUES(:id,:organisationId,:schoolId,'ATTENDANCE','NORMAL','ALL_STAFF','BILINGUAL',
                  'Student leave needs review','विद्यार्थी अवकाश की समीक्षा आवश्यक है',
                  'Open Student leave in the school workspace to review current pending requests.',
                  'वर्तमान लंबित अनुरोधों की समीक्षा के लिए स्कूल कार्यक्षेत्र में विद्यार्थी अवकाश खोलें।',
                  true,:scheduled,:expires,'APPROVED',:now,:now,'DOMAIN_EVENT','STUDENT_LEAVE_SUBMISSION',:eventId,:eventId,
                  :requestId,:historyId)
                """,p.addValue("id",id).addValue("scheduled",source.occurredAt()).addValue("expires",source.expiresAt())
                .addValue("now",clock.instant()).addValue("requestId",source.requestId()).addValue("historyId",source.historyId()));
        for(var reviewer:reviewers)
            deliveries.attachStaff(organisationId,schoolId,id,reviewer.membershipId(),reviewer.userId());
        jdbc.update("""
                INSERT INTO communication_announcement_history(id,organisation_id,school_id,communication_announcement_id,
                  event_type,new_status,reason,actor_kind,occurred_at)
                VALUES(:historyId,:organisationId,:schoolId,:id,'CREATED','APPROVED',
                  'Automatic reviewer alert from verified student leave submission history; no human notice approval asserted',
                  'SYSTEM',:now)
                """,p.addValue("historyId",UUID.randomUUID()));
        return id;
    }
}
