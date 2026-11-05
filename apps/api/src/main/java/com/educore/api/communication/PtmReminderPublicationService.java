package com.educore.api.communication;

import com.educore.api.ptm.PtmNotificationSource;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Adds source-authorized reminders to the existing inbox/delivery ledger; it never calls a provider. */
@Service
public class PtmReminderPublicationService {
    private final NamedParameterJdbcTemplate jdbc;
    private final PtmNotificationSource source;
    private final Clock clock;
    private final DomainFamilyDelivery deliveries;
    public PtmReminderPublicationService(NamedParameterJdbcTemplate jdbc, PtmNotificationSource source, Clock clock,
            DomainFamilyDelivery deliveries) {
        this.jdbc=jdbc; this.source=source; this.clock=clock; this.deliveries=deliveries;
    }

    @Transactional
    public UUID publish(UUID organisationId, UUID schoolId, UUID reminderId, UUID outboxId) {
        var reminder=source.current(organisationId,schoolId,reminderId);
        if(reminder==null) return null;
        var p=new MapSqlParameterSource("organisationId",organisationId).addValue("schoolId",schoolId)
                .addValue("reminderId",reminderId).addValue("outboxId",outboxId).addValue("now",clock.instant());
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key,0))) locked",
                new MapSqlParameterSource("key","ptm-notification:"+reminderId),Integer.class);
        UUID existing=jdbc.query("""
                SELECT id FROM communication_announcements WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND authority_kind='DOMAIN_EVENT' AND ptm_reminder_id=:reminderId AND domain_event_id=:outboxId
                """,p,r -> r.next()?r.getObject(1,UUID.class):null);
        if(existing!=null) return existing;
        UUID notice=UUID.randomUUID(),recipient=UUID.randomUUID();
        p.addValue("id",notice).addValue("recipientId",recipient).addValue("guardianId",reminder.guardianId())
                .addValue("learnerId",reminder.learnerId()).addValue("userId",reminder.userId()).addValue("expiresAt",reminder.expiresAt());
        int inserted=jdbc.update("""
                INSERT INTO communication_announcements(id,organisation_id,school_id,category,priority,audience_kind,
                  audience_reference_id,language_mode,title_en,title_hi,body_en,body_hi,request_push,scheduled_for,expires_at,
                  status,created_at,updated_at,authority_kind,source_type,source_id,ptm_reminder_id,domain_event_id)
                SELECT :id,:organisationId,:schoolId,'ACADEMIC','NORMAL','LEARNER_FAMILIES',:learnerId,'BILINGUAL',
                  'Parent-teacher meeting reminder','अभिभावक-शिक्षक बैठक की याद दिलाना',
                  'Open Parent-teacher meetings in the family portal to check your current appointment details.',
                  'अपनी बैठक का वर्तमान विवरण देखने के लिए परिवार पोर्टल में अभिभावक-शिक्षक बैठक खोलें।',
                  true,evidence.occurred_at,:expiresAt,'APPROVED',:now,:now,'DOMAIN_EVENT','PTM_REMINDER',:reminderId,:reminderId,:outboxId
                FROM outbox_events evidence WHERE evidence.id=:outboxId AND evidence.aggregate_type='PTM_REMINDER'
                  AND evidence.aggregate_id=CAST(:reminderId AS text) AND evidence.event_type='ptm.booking_reminder'
                  AND evidence.payload->>'organisationId'=CAST(:organisationId AS text)
                  AND evidence.payload->>'schoolId'=CAST(:schoolId AS text)
                """,p);
        if(inserted!=1) throw new IllegalStateException("PTM notification outbox scope does not match its source");
        deliveries.attach(organisationId,schoolId,notice,reminder.guardianId(),reminder.learnerId(),reminder.userId(),
                "Automatic reminder from a current published PTM booking; no human notice approval asserted");
        return notice;
    }
}
