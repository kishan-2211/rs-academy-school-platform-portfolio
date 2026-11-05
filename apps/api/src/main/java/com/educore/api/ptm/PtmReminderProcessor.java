package com.educore.api.ptm;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PtmReminderProcessor {
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public PtmReminderProcessor(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public UUID processNext(UUID organisationId) {
        MapSqlParameterSource params = new MapSqlParameterSource("organisationId", organisationId);
        // Match event -> booking -> reminder order used by cancellation and rescheduling.
        List<UUID> events = jdbc.query("""
                SELECT event.id FROM ptm_events event
                WHERE event.organisation_id=:organisationId AND EXISTS(
                  SELECT 1 FROM ptm_booking_reminders job JOIN ptm_bookings booking
                    ON booking.id=job.ptm_booking_id AND booking.organisation_id=job.organisation_id
                      AND booking.school_id=job.school_id
                  WHERE booking.ptm_event_id=event.id AND job.status='PENDING'
                    AND job.organisation_id=event.organisation_id AND job.school_id=event.school_id
                    AND job.next_attempt_at<=clock_timestamp())
                ORDER BY event.id FOR UPDATE OF event SKIP LOCKED LIMIT 1
                """, params, (r, n) -> r.getObject(1, UUID.class));
        if (events.isEmpty()) return null;
        params.addValue("eventId", events.getFirst());
        List<DueJob> jobs = jdbc.query("""
                SELECT job.id,job.school_id,job.ptm_booking_id,job.ptm_slot_id,
                  booking.learner_id,booking.guardian_id,booking.status booking_status,
                  booking.ptm_slot_id current_slot_id,event.status event_status,slot.starts_at,slot.status slot_status
                FROM ptm_booking_reminders job JOIN ptm_bookings booking
                  ON booking.id=job.ptm_booking_id AND booking.organisation_id=job.organisation_id
                    AND booking.school_id=job.school_id
                JOIN ptm_events event ON event.id=booking.ptm_event_id
                  AND event.organisation_id=job.organisation_id AND event.school_id=job.school_id
                JOIN ptm_slots slot ON slot.id=job.ptm_slot_id
                  AND slot.organisation_id=job.organisation_id AND slot.school_id=job.school_id
                WHERE job.organisation_id=:organisationId AND event.id=:eventId
                  AND job.status='PENDING' AND job.next_attempt_at<=clock_timestamp()
                ORDER BY job.next_attempt_at,job.id FOR UPDATE OF booking,job SKIP LOCKED LIMIT 1
                """, params, (r, n) -> new DueJob(r.getObject("id", UUID.class),
                r.getObject("school_id", UUID.class), r.getObject("ptm_booking_id", UUID.class),
                r.getObject("ptm_slot_id", UUID.class), r.getObject("learner_id", UUID.class),
                r.getObject("guardian_id", UUID.class), "BOOKED".equals(r.getString("booking_status"))
                        && "PUBLISHED".equals(r.getString("event_status"))
                        && "AVAILABLE".equals(r.getString("slot_status"))
                        && r.getObject("ptm_slot_id", UUID.class).equals(r.getObject("current_slot_id", UUID.class)),
                r.getTimestamp("starts_at").toInstant()));
        if (jobs.isEmpty()) return null;
        DueJob job = jobs.getFirst();
        try {
            Instant now = Instant.now(clock);
            params.addValue("id", job.id()).addValue("schoolId", job.schoolId())
                    .addValue("bookingId", job.bookingId()).addValue("slotId", job.slotId())
                    .addValue("guardianId", job.guardianId()).addValue("learnerId", job.learnerId()).addValue("now", now);
            boolean eligible = job.active() && job.startsAt().isAfter(now) && Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM guardians guardian
                      JOIN learner_guardian_relationships relationship ON relationship.guardian_id=guardian.id
                        AND relationship.organisation_id=guardian.organisation_id
                        AND relationship.school_id=guardian.school_id AND relationship.portal_access_status='ACTIVE'
                      JOIN learners learner ON learner.id=relationship.learner_id
                        AND learner.organisation_id=relationship.organisation_id
                        AND learner.school_id=relationship.school_id AND learner.status='ACTIVE'
                      JOIN memberships membership ON membership.user_id=guardian.user_account_id
                        AND membership.organisation_id=guardian.organisation_id AND membership.school_id=guardian.school_id
                      WHERE guardian.id=:guardianId AND guardian.organisation_id=:organisationId
                        AND guardian.school_id=:schoolId AND guardian.status='ACTIVE' AND learner.id=:learnerId
                        AND membership.status='ACTIVE' AND membership.valid_from<=clock_timestamp()
                        AND (membership.valid_until IS NULL OR membership.valid_until>clock_timestamp())
                        AND EXISTS(SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id=mr.role_id
                          WHERE mr.membership_id=membership.id AND rc.capability_code='portal.guardian')
                        AND EXISTS(SELECT 1 FROM student_section_placements placement JOIN student_enrolments enrolment
                          ON enrolment.id=placement.enrolment_id AND enrolment.organisation_id=placement.organisation_id
                            AND enrolment.school_id=placement.school_id
                          JOIN ptm_events event ON event.id=:eventId AND event.organisation_id=placement.organisation_id
                            AND event.school_id=placement.school_id AND event.class_section_id=placement.class_section_id
                          WHERE placement.learner_id=learner.id AND placement.status='ACTIVE'
                            AND placement.starts_on<=event.event_date
                            AND (placement.ends_on IS NULL OR placement.ends_on>=event.event_date)
                            AND enrolment.status='ACTIVE' AND enrolment.academic_session_id=event.academic_session_id))
                    """, params, Boolean.class));
            if (eligible) {
                // Provider-neutral evidence only; downstream delivery must revalidate relationship and consent.
                jdbc.update("""
                        INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload,headers,
                          occurred_at,available_at,attempt_count,created_at)
                        VALUES(:outboxId,'PTM_REMINDER',:aggregateId,'ptm.booking_reminder',
                          jsonb_build_object('organisationId',CAST(:organisationId AS text),'schoolId',CAST(:schoolId AS text),
                            'eventId',CAST(:eventId AS text),'bookingId',CAST(:bookingId AS text),
                            'slotId',CAST(:slotId AS text),'learnerId',CAST(:learnerId AS text),
                            'guardianId',CAST(:guardianId AS text)),'{}'::jsonb,:now,:now,0,:now)
                        """, params.addValue("outboxId", UUID.randomUUID()).addValue("aggregateId", job.id().toString()));
            }
            int count = jdbc.update("""
                    UPDATE ptm_booking_reminders SET status=:status,attempt_count=attempt_count+1,
                      last_error_code=NULL,processed_at=:now,updated_at=:now,row_version=row_version+1
                    WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId AND status='PENDING'
                    """, params.addValue("status", eligible ? "MATERIALIZED" : "CANCELLED"));
            if (count != 1) throw new IllegalStateException("PTM reminder changed concurrently");
            return job.id();
        } catch (RuntimeException exception) {
            throw new ReminderProcessingException(job.id(), exception);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID organisationId, UUID jobId) {
        MapSqlParameterSource params = new MapSqlParameterSource("organisationId", organisationId).addValue("id", jobId);
        List<Integer> rows = jdbc.query("""
                SELECT attempt_count FROM ptm_booking_reminders
                WHERE id=:id AND organisation_id=:organisationId AND status='PENDING' FOR UPDATE
                """, params, (r, n) -> r.getInt(1));
        if (rows.isEmpty()) return;
        int attempt = rows.getFirst() + 1;
        Instant now = Instant.now(clock);
        jdbc.update("""
                UPDATE ptm_booking_reminders SET status=CASE WHEN :attempt>=10 THEN 'FAILED' ELSE 'PENDING' END,
                  attempt_count=:attempt,last_error_code='MATERIALIZATION_FAILED',next_attempt_at=:nextAt,
                  processed_at=CASE WHEN :attempt>=10 THEN :now ELSE NULL END,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND status='PENDING'
                """, params.addValue("attempt", attempt).addValue("now", now)
                .addValue("nextAt", now.plusSeconds(60L * Math.min(60, 1 << Math.min(attempt, 6)))));
    }

    public static final class ReminderProcessingException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final UUID jobId;
        ReminderProcessingException(UUID jobId, RuntimeException cause) {
            super("PTM reminder materialization failed", cause);
            this.jobId = jobId;
        }
        public UUID jobId() { return jobId; }
    }
    private record DueJob(UUID id, UUID schoolId, UUID bookingId, UUID slotId, UUID learnerId,
            UUID guardianId, boolean active, Instant startsAt) {}
}
