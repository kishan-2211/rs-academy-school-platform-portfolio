package com.educore.api.schoolcalendar;

import com.educore.api.communication.CommunicationService;
import com.educore.api.communication.CommunicationService.CalendarNoticePublication;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SchoolCalendarNotificationProcessor {
    private final NamedParameterJdbcTemplate jdbc;
    private final CommunicationService communications;
    private final Clock clock;

    public SchoolCalendarNotificationProcessor(
            NamedParameterJdbcTemplate jdbc,
            CommunicationService communications,
            Clock clock) {
        this.jdbc = jdbc;
        this.communications = communications;
        this.clock = clock;
    }

    @Transactional
    public UUID processNext(UUID organisationId) {
        List<DueJob> jobs = jdbc.query("""
                SELECT job.id,job.school_id,job.notice_revision_id,job.audience_kind,
                  job.request_push,job.requires_acknowledgement,
                  revision.notice_kind,revision.title_en,revision.title_hi,
                  revision.body_en,revision.body_hi,series.calendar_date,
                  policy.time_zone,review.submitted_by_membership_id,
                  review.decided_by_membership_id,review.decided_at,review.decision_reason
                FROM school_calendar_notification_jobs job
                JOIN school_calendar_notice_reviews review ON review.id=job.notice_review_id
                JOIN school_calendar_notice_revisions revision ON revision.id=job.notice_revision_id
                JOIN school_calendar_notice_series series ON series.id=job.notice_series_id
                JOIN school_calendar_policy_revisions policy ON policy.id=review.policy_revision_id
                WHERE job.organisation_id=:organisationId AND job.status='PENDING'
                  AND job.next_attempt_at<=clock_timestamp() AND review.status='APPROVED'
                ORDER BY job.next_attempt_at,job.created_at,job.id
                FOR UPDATE OF job SKIP LOCKED LIMIT 1
                """, new MapSqlParameterSource().addValue("organisationId", organisationId),
                (result, row) -> new DueJob(
                        result.getObject("id", UUID.class), result.getObject("school_id", UUID.class),
                        result.getObject("notice_revision_id", UUID.class), result.getString("audience_kind"),
                        result.getBoolean("request_push"), result.getBoolean("requires_acknowledgement"),
                        result.getString("notice_kind"), result.getString("title_en"),
                        result.getString("title_hi"), result.getString("body_en"), result.getString("body_hi"),
                        result.getDate("calendar_date").toLocalDate(), result.getString("time_zone"),
                        result.getObject("submitted_by_membership_id", UUID.class),
                        result.getObject("decided_by_membership_id", UUID.class),
                        result.getTimestamp("decided_at").toInstant(), result.getString("decision_reason")));
        if (jobs.isEmpty()) return null;
        DueJob job = jobs.getFirst();
        try {
            Instant now = Instant.now(clock);
            Instant expiresAt = job.calendarDate().plusDays(1)
                    .atStartOfDay(ZoneId.of(job.timeZone())).toInstant();
            if (!expiresAt.isAfter(now)) {
                throw new IllegalStateException("Calendar notice expired before materialization");
            }
            var result = communications.publishApprovedCalendarNotice(new CalendarNoticePublication(
                    organisationId, job.schoolId(), job.revisionId(), job.audienceKind(), job.noticeKind(),
                    job.titleEn(), job.titleHi(), job.bodyEn(), job.bodyHi(), job.push(),
                    job.requiresAcknowledgement(), now, expiresAt, job.proposerMembershipId(),
                    job.approverMembershipId(), job.approvedAt(), job.decisionReason()));
            int changed = jdbc.update("""
                    UPDATE school_calendar_notification_jobs SET status='MATERIALIZED',
                      announcement_id=:announcementId,recipient_count=:recipientCount,
                      attempt_count=attempt_count+1,last_error_code=NULL,processed_at=:now,
                      updated_at=:now,row_version=row_version+1
                    WHERE id=:id AND organisation_id=:organisationId AND status='PENDING'
                    """, new MapSqlParameterSource().addValue("id", job.id())
                    .addValue("organisationId", organisationId)
                    .addValue("announcementId", result.announcementId())
                    .addValue("recipientCount", result.recipientCount()).addValue("now", now));
            if (changed != 1) throw new IllegalStateException("Calendar notification job changed concurrently");
            return job.id();
        } catch (RuntimeException exception) {
            throw new CalendarNotificationProcessingException(job.id(), exception);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID organisationId, UUID jobId) {
        List<Integer> attempts = jdbc.query("""
                SELECT attempt_count FROM school_calendar_notification_jobs
                WHERE id=:id AND organisation_id=:organisationId AND status='PENDING'
                FOR UPDATE
                """, new MapSqlParameterSource().addValue("id", jobId)
                .addValue("organisationId", organisationId),
                (result, row) -> result.getInt("attempt_count"));
        if (attempts.isEmpty()) return;
        int nextAttempt = attempts.getFirst() + 1;
        int delayMinutes = Math.min(60, 1 << Math.min(nextAttempt, 6));
        Instant now = Instant.now(clock);
        jdbc.update("""
                UPDATE school_calendar_notification_jobs SET
                  status=CASE WHEN :attempt>=10 THEN 'FAILED' ELSE 'PENDING' END,
                  attempt_count=:attempt,last_error_code='MATERIALIZATION_FAILED',
                  next_attempt_at=:nextAttemptAt,
                  processed_at=CASE WHEN :attempt>=10 THEN :now ELSE NULL END,
                  updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND status='PENDING'
                """, new MapSqlParameterSource().addValue("id", jobId)
                .addValue("organisationId", organisationId).addValue("attempt", nextAttempt)
                .addValue("nextAttemptAt", now.plusSeconds(delayMinutes * 60L)).addValue("now", now));
    }

    public static final class CalendarNotificationProcessingException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final UUID jobId;

        CalendarNotificationProcessingException(UUID jobId, RuntimeException cause) {
            super("Calendar notification materialization failed", cause);
            this.jobId = jobId;
        }

        public UUID jobId() {
            return jobId;
        }
    }

    private record DueJob(
            UUID id,
            UUID schoolId,
            UUID revisionId,
            String audienceKind,
            boolean push,
            boolean requiresAcknowledgement,
            String noticeKind,
            String titleEn,
            String titleHi,
            String bodyEn,
            String bodyHi,
            java.time.LocalDate calendarDate,
            String timeZone,
            UUID proposerMembershipId,
            UUID approverMembershipId,
            Instant approvedAt,
            String decisionReason) {}
}
