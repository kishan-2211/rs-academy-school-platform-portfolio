package com.educore.api.ptm;

import com.educore.api.tenancy.TenantRequestContext;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The PTM domain owns current booking/slot authority; no caller-supplied message body is trusted. */
@Service
public class PtmNotificationSource {
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    public PtmNotificationSource(NamedParameterJdbcTemplate jdbc, Clock clock) { this.jdbc=jdbc; this.clock=clock; }

    @Transactional(readOnly=true)
    public Reminder current(UUID organisationId, UUID schoolId, UUID reminderId) {
        if (!TenantRequestContext.current().map(s -> organisationId.equals(s.organisationId())).orElse(false))
            throw new IllegalStateException("PTM notifications require a matching organisation context");
        return jdbc.query("""
                SELECT reminder.id,booking.guardian_id,booking.learner_id,guardian.user_account_id,slot.starts_at
                FROM ptm_booking_reminders reminder
                JOIN ptm_bookings booking ON booking.id=reminder.ptm_booking_id
                  AND booking.organisation_id=reminder.organisation_id AND booking.school_id=reminder.school_id
                JOIN ptm_events event ON event.id=booking.ptm_event_id
                  AND event.organisation_id=reminder.organisation_id AND event.school_id=reminder.school_id
                JOIN ptm_slots slot ON slot.id=reminder.ptm_slot_id
                  AND slot.organisation_id=reminder.organisation_id AND slot.school_id=reminder.school_id
                JOIN guardians guardian ON guardian.id=booking.guardian_id
                  AND guardian.organisation_id=reminder.organisation_id AND guardian.school_id=reminder.school_id
                JOIN learners learner ON learner.id=booking.learner_id
                  AND learner.organisation_id=reminder.organisation_id AND learner.school_id=reminder.school_id
                JOIN learner_guardian_relationships relationship ON relationship.guardian_id=guardian.id
                  AND relationship.learner_id=learner.id AND relationship.organisation_id=reminder.organisation_id
                  AND relationship.school_id=reminder.school_id
                JOIN user_accounts account ON account.id=guardian.user_account_id
                JOIN schools school ON school.id=reminder.school_id AND school.organisation_id=reminder.organisation_id
                JOIN organisations organisation ON organisation.id=reminder.organisation_id
                WHERE reminder.id=:id AND reminder.organisation_id=:organisationId AND reminder.school_id=:schoolId
                  AND reminder.status='MATERIALIZED' AND booking.status='BOOKED' AND event.status='PUBLISHED'
                  AND slot.status='AVAILABLE' AND slot.id=booking.ptm_slot_id AND slot.starts_at>:now
                  AND guardian.status='ACTIVE' AND learner.status='ACTIVE' AND relationship.portal_access_status='ACTIVE'
                  AND account.status='ACTIVE' AND school.status='ACTIVE' AND organisation.status='ACTIVE'
                  AND EXISTS (SELECT 1 FROM memberships membership
                    JOIN membership_roles mr ON mr.membership_id=membership.id
                    JOIN roles role ON role.id=mr.role_id
                    JOIN role_capabilities capability ON capability.role_id=role.id
                    WHERE membership.user_id=guardian.user_account_id AND membership.organisation_id=reminder.organisation_id
                      AND membership.school_id=reminder.school_id AND membership.status='ACTIVE'
                      AND membership.valid_from<=:now AND (membership.valid_until IS NULL OR membership.valid_until>:now)
                      AND (role.organisation_id IS NULL OR role.organisation_id=reminder.organisation_id)
                      AND capability.capability_code='portal.guardian')
                  AND EXISTS (SELECT 1 FROM student_section_placements placement
                    JOIN student_enrolments enrolment ON enrolment.id=placement.enrolment_id
                      AND enrolment.organisation_id=placement.organisation_id AND enrolment.school_id=placement.school_id
                    WHERE placement.organisation_id=reminder.organisation_id AND placement.school_id=reminder.school_id
                      AND placement.learner_id=learner.id AND placement.class_section_id=event.class_section_id
                      AND placement.status='ACTIVE' AND placement.starts_on<=event.event_date
                      AND (placement.ends_on IS NULL OR placement.ends_on>=event.event_date)
                      AND enrolment.status='ACTIVE' AND enrolment.academic_session_id=event.academic_session_id)
                """,new MapSqlParameterSource("organisationId",organisationId).addValue("schoolId",schoolId)
                .addValue("id",reminderId).addValue("now",clock.instant()),r -> r.next()?new Reminder(
                    organisationId,schoolId,reminderId,r.getObject("guardian_id",UUID.class),r.getObject("learner_id",UUID.class),
                    r.getObject("user_account_id",UUID.class),r.getTimestamp("starts_at").toInstant()):null);
    }

    public record Reminder(UUID organisationId, UUID schoolId, UUID reminderId, UUID guardianId,
            UUID learnerId, UUID userId, Instant expiresAt) {}
}
