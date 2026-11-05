package com.educore.api.learning;

import com.educore.api.tenancy.TenantRequestContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Privacy-minimal source boundary; no assignment text or learner/contact data is returned. */
@Service
public class LearningPublicationNotificationSource {
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    public LearningPublicationNotificationSource(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc=jdbc; this.clock=clock;
    }

    @Transactional(readOnly=true)
    public Publication current(UUID organisationId, UUID schoolId, UUID eventId) {
        return jdbc.query("""
                SELECT * FROM learning_publication_notification_sources
                WHERE event_id=:eventId AND organisation_id=:organisationId AND school_id=:schoolId
                  AND scheduled_for<=:now AND expires_at>:now
                """, scope(organisationId,schoolId,eventId), r -> r.next()?new Publication(
                    r.getObject("assignment_id",UUID.class),r.getObject("history_id",UUID.class),
                    r.getObject("class_section_id",UUID.class),r.getString("audience_kind"),
                    r.getTimestamp("scheduled_for").toInstant(),r.getTimestamp("expires_at").toInstant()):null);
    }

    @Transactional(readOnly=true)
    public List<Recipient> recipients(UUID organisationId, UUID schoolId, UUID eventId) {
        return jdbc.query("""
                SELECT recipient.* FROM learning_publication_notification_recipients recipient
                JOIN learning_publication_notification_sources source ON source.event_id=recipient.event_id
                  AND source.organisation_id=recipient.organisation_id AND source.school_id=recipient.school_id
                WHERE recipient.event_id=:eventId AND recipient.organisation_id=:organisationId AND recipient.school_id=:schoolId
                  AND source.scheduled_for<=:now AND source.expires_at>:now
                ORDER BY recipient.learner_id,recipient.user_account_id,recipient.guardian_id,recipient.membership_id
                """,scope(organisationId,schoolId,eventId),(r,n)->new Recipient(r.getString("recipient_kind"),
                    r.getObject("guardian_id",UUID.class),r.getObject("membership_id",UUID.class),
                    r.getObject("learner_id",UUID.class),r.getObject("user_account_id",UUID.class)));
    }

    @Transactional(readOnly=true)
    public boolean isCurrentGuardian(UUID organisationId, UUID schoolId, UUID eventId,
            UUID guardianId, UUID learnerId, UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM learning_publication_notification_recipients recipient
                  JOIN learning_publication_notification_sources source ON source.event_id=recipient.event_id
                    AND source.organisation_id=recipient.organisation_id AND source.school_id=recipient.school_id
                  WHERE recipient.event_id=:eventId AND recipient.organisation_id=:organisationId AND recipient.school_id=:schoolId
                    AND recipient.recipient_kind='GUARDIAN' AND recipient.guardian_id=:guardianId
                    AND recipient.learner_id=:learnerId AND recipient.user_account_id=:userId
                    AND source.scheduled_for<=:now AND source.expires_at>:now)
                """,scope(organisationId,schoolId,eventId).addValue("guardianId",guardianId)
                .addValue("learnerId",learnerId).addValue("userId",userId),Boolean.class));
    }

    private MapSqlParameterSource scope(UUID organisationId, UUID schoolId, UUID eventId) {
        if(!TenantRequestContext.current().map(s->organisationId.equals(s.organisationId())).orElse(false))
            throw new IllegalStateException("Learning publication requires a matching organisation context");
        return new MapSqlParameterSource("organisationId",organisationId).addValue("schoolId",schoolId)
                .addValue("eventId",eventId).addValue("now",clock.instant());
    }
    public record Publication(UUID assignmentId,UUID historyId,UUID sectionId,String audienceKind,
                              Instant scheduledFor,Instant expiresAt) {}
    public record Recipient(String kind,UUID guardianId,UUID membershipId,UUID learnerId,UUID userId) {}
}
