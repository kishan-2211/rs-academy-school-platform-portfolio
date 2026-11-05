package com.educore.api.schoolcalendar;

import com.educore.api.common.api.ApiException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublishedSchoolCalendarService {

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public PublishedSchoolCalendarService(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Internal delivery-time authority check. A draft correction does not supersede an approval. */
    @Transactional(readOnly = true)
    public boolean isCurrentNotificationRevision(UUID organisationId, UUID schoolId, UUID revisionId) {
        if (!com.educore.api.tenancy.TenantRequestContext.current()
                .map(scope -> organisationId.equals(scope.organisationId())).orElse(false)) {
            throw new IllegalStateException("Calendar delivery requires a matching organisation context");
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1 FROM school_calendar_days day
                  JOIN school_calendar_notice_reviews review
                    ON review.notice_revision_id=day.authority_notice_revision_id
                    AND review.organisation_id=day.organisation_id AND review.school_id=day.school_id
                  WHERE day.organisation_id=:organisationId AND day.school_id=:schoolId
                    AND day.authority_notice_revision_id=:revisionId AND day.status='ACTIVE'
                    AND review.status='APPROVED')
                """, new MapSqlParameterSource("organisationId", organisationId)
                .addValue("schoolId", schoolId).addValue("revisionId", revisionId), Boolean.class));
    }

    @Transactional(readOnly = true)
    public PublishedSchoolCalendarResponse published(
            UUID organisationId,
            UUID schoolId,
            UUID campusId,
            UUID academicSessionId,
            UUID learnerId) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("organisationId", organisationId)
                .addValue("schoolId", schoolId)
                .addValue("campusId", campusId)
                .addValue("sessionId", academicSessionId);
        List<Context> contexts = jdbc.query("""
                SELECT session.name,session.starts_on,session.ends_on,campus.time_zone
                FROM academic_sessions session
                JOIN campuses campus
                  ON campus.id=:campusId
                 AND campus.organisation_id=session.organisation_id
                 AND campus.school_id=session.school_id
                 AND campus.status='ACTIVE'
                WHERE session.id=:sessionId
                  AND session.organisation_id=:organisationId
                  AND session.school_id=:schoolId
                  AND session.status IN ('PLANNED','ACTIVE','CLOSED')
                """, parameters, (result, row) -> new Context(
                result.getString("name"),
                result.getDate("starts_on").toLocalDate(),
                result.getDate("ends_on").toLocalDate(),
                result.getString("time_zone")));
        if (contexts.isEmpty()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "PUBLISHED_CALENDAR_UNAVAILABLE",
                    "The school calendar is unavailable for this learner's current session.");
        }
        Context context = contexts.getFirst();
        List<PublishedSchoolCalendarResponse.PublishedDay> days = jdbc.query("""
                SELECT day.id AS calendar_day_id,day.calendar_date,day.day_type,
                       day.attendance_required,revision.id AS notice_revision_id,
                       revision.notice_kind,revision.title_en,revision.title_hi,
                       revision.body_en,revision.body_hi,review.decided_at AS published_at
                FROM school_calendar_days day
                JOIN school_calendar_notice_revisions revision
                  ON revision.id=day.authority_notice_revision_id
                 AND revision.organisation_id=day.organisation_id
                 AND revision.school_id=day.school_id
                JOIN school_calendar_notice_reviews review
                  ON review.notice_revision_id=revision.id
                 AND review.organisation_id=revision.organisation_id
                 AND review.school_id=revision.school_id
                 AND review.status='APPROVED'
                WHERE day.organisation_id=:organisationId
                  AND day.school_id=:schoolId
                  AND day.campus_id=:campusId
                  AND day.academic_session_id=:sessionId
                  AND day.status='ACTIVE'
                  AND day.authority_notice_revision_id IS NOT NULL
                ORDER BY day.calendar_date,day.id
                """, parameters, (result, row) -> new PublishedSchoolCalendarResponse.PublishedDay(
                result.getObject("calendar_day_id", UUID.class),
                result.getObject("notice_revision_id", UUID.class),
                result.getDate("calendar_date").toLocalDate(),
                result.getString("day_type"),
                result.getString("notice_kind"),
                result.getString("title_en"),
                result.getString("title_hi"),
                result.getString("body_en"),
                result.getString("body_hi"),
                result.getBoolean("attendance_required"),
                result.getTimestamp("published_at").toInstant()));
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(context.timeZone())));
        return new PublishedSchoolCalendarResponse(
                learnerId, schoolId, campusId, academicSessionId, context.sessionName(),
                context.startsOn(), context.endsOn(), context.timeZone(), today, days);
    }

    private record Context(
            String sessionName, LocalDate startsOn, LocalDate endsOn, String timeZone) {}
}
