package com.educore.api.schoolcalendar;

import static com.educore.api.schoolcalendar.SchoolCalendarController.CreateNoticeRequest;
import static com.educore.api.schoolcalendar.SchoolCalendarController.CreatePolicyRequest;
import static com.educore.api.schoolcalendar.SchoolCalendarController.ReviewDecisionRequest;
import static com.educore.api.schoolcalendar.SchoolCalendarController.ReviseNoticeRequest;
import static com.educore.api.schoolcalendar.SchoolCalendarController.RevisePolicyRequest;
import static com.educore.api.schoolcalendar.SchoolCalendarController.SubmitReviewRequest;
import static com.educore.api.schoolcalendar.SchoolCalendarSnapshot.CampusOption;
import static com.educore.api.schoolcalendar.SchoolCalendarSnapshot.NoticeItem;
import static com.educore.api.schoolcalendar.SchoolCalendarSnapshot.NotificationJobItem;
import static com.educore.api.schoolcalendar.SchoolCalendarSnapshot.PolicyItem;
import static com.educore.api.schoolcalendar.SchoolCalendarSnapshot.ReviewItem;
import static com.educore.api.schoolcalendar.SchoolCalendarSnapshot.SessionOption;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.ZoneOffset;
import java.time.zone.ZoneRulesException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SchoolCalendarService {
    private static final Set<String> DAY_TYPES = Set.of("HOLIDAY", "CLOSURE", "INSTRUCTIONAL");
    private static final Set<String> NOTICE_KINDS = Set.of(
            "PLANNED_HOLIDAY", "EMERGENCY_CLOSURE", "GOVERNMENT_ORDER", "REOPENING", "OTHER");
    private static final Set<String> DECISIONS = Set.of("APPROVED", "REJECTED");

    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final Clock clock;

    public SchoolCalendarService(
            NamedParameterJdbcTemplate jdbc,
            SecurityAuditService audit,
            Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SchoolCalendarSnapshot snapshot(
            AuthenticatedUser user,
            UUID requestedSchoolId,
            UUID requestedCampusId,
            UUID requestedSessionId) {
        Scope scope = requireScope(user, requestedSchoolId, null);
        List<CampusOption> campuses = campusOptions(scope);
        List<SessionOption> sessions = sessionOptions(scope);
        UUID campusId = selectCampus(campuses, requestedCampusId);
        UUID sessionId = selectSession(sessions, requestedSessionId);
        if (campusId == null || sessionId == null) {
            return new SchoolCalendarSnapshot(scope.schoolId(), scope.schoolName(),
                    scope.canManage(), scope.canApprove(), campusId, sessionId,
                    campuses, sessions, null, List.of(), List.of());
        }
        PolicyStored policy = currentPolicy(scope, campusId, sessionId, false);
        Map<UUID, List<NotificationJobItem>> jobs = notificationJobs(scope, campusId, sessionId);
        List<NoticeItem> notices = currentNotices(scope, campusId, sessionId, jobs);
        List<ReviewItem> reviews = reviews(scope, campusId, sessionId);
        return new SchoolCalendarSnapshot(scope.schoolId(), scope.schoolName(),
                scope.canManage(), scope.canApprove(), campusId, sessionId,
                campuses, sessions, policy == null ? null : policy.publicItem(), notices, reviews);
    }

    @Transactional
    public SchoolCalendarMutationResponse createPolicy(
            AuthenticatedUser user, CreatePolicyRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "calendar.manage");
        Selection selection = selection(scope, request.campusId(), request.academicSessionId());
        lock(scope, selection.campusId(), selection.sessionId());
        if (currentPolicy(scope, selection.campusId(), selection.sessionId(), true) != null) {
            throw conflict("CALENDAR_POLICY_EXISTS",
                    "This campus and session already has a notification policy. Record a new revision instead.");
        }
        PolicyValues values = policyValues(
                request.schoolStartTime(), request.sameDayCutoffMinutes(), request.reminderTime(),
                request.timeZone(), request.guardianPushEnabled(), request.studentInAppEnabled(),
                request.emergencyAcknowledgementRequired(), request.authorityConfirmed(), request.reason());
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        safe("CALENDAR_POLICY_EXISTS", () -> jdbc.update("""
                INSERT INTO school_calendar_policy_revisions(
                  id,organisation_id,school_id,campus_id,academic_session_id,revision_number,
                  supersedes_policy_id,school_start_time,same_day_cutoff_minutes,reminder_time,
                  time_zone,guardian_push_enabled,student_in_app_enabled,emergency_ack_required,
                  change_reason,revised_by_membership_id,revised_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:campusId,:sessionId,1,NULL,:schoolStart,
                  :cutoffMinutes,:reminderTime,:timeZone,:guardianPush,:studentInApp,:emergencyAck,
                  :reason,:actorMembershipId,:now,0)
                """, policyParams(scope, id, selection, 1, null, values, now)));
        audit(user, scope, "school.calendar.policy.created", "SCHOOL_CALENDAR_POLICY", id,
                values.reason(), Map.of("campusId", selection.campusId(),
                        "academicSessionId", selection.sessionId(), "revision", 1));
        return new SchoolCalendarMutationResponse(id, null, 1, 0, "ACTIVE", null);
    }

    @Transactional
    public SchoolCalendarMutationResponse revisePolicy(
            AuthenticatedUser user, UUID policyId, RevisePolicyRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "calendar.manage");
        List<PolicyStored> rows = jdbc.query("""
                SELECT policy.*,account.display_name AS revised_by,
                  session.starts_on,session.ends_on
                FROM school_calendar_policy_revisions policy
                JOIN memberships membership ON membership.id=policy.revised_by_membership_id
                JOIN user_accounts account ON account.id=membership.user_id
                JOIN academic_sessions session ON session.id=policy.academic_session_id
                  AND session.organisation_id=policy.organisation_id AND session.school_id=policy.school_id
                WHERE policy.id=:id AND policy.organisation_id=:organisationId
                  AND policy.school_id=:schoolId
                  AND NOT EXISTS (SELECT 1 FROM school_calendar_policy_revisions newer
                    WHERE newer.campus_id=policy.campus_id
                      AND newer.academic_session_id=policy.academic_session_id
                      AND newer.revision_number>policy.revision_number)
                FOR UPDATE OF policy
                """, scope.params().addValue("id", policyId), this::mapPolicy);
        if (rows.isEmpty()) {
            throw conflict("STALE_CALENDAR_POLICY",
                    "This policy is missing or has a newer revision. Refresh and try again.");
        }
        PolicyStored current = rows.getFirst();
        if (current.revisionNumber() != request.expectedRevision()) {
            throw conflict("STALE_CALENDAR_POLICY", "The notification policy changed. Refresh and try again.");
        }
        lock(scope, current.campusId(), current.sessionId());
        PolicyValues values = policyValues(
                request.schoolStartTime(), request.sameDayCutoffMinutes(), request.reminderTime(),
                request.timeZone(), request.guardianPushEnabled(), request.studentInAppEnabled(),
                request.emergencyAcknowledgementRequired(), request.authorityConfirmed(), request.reason());
        int revision = current.revisionNumber() + 1;
        if (revision > 1000) throw conflict("CALENDAR_POLICY_REVISION_LIMIT", "Policy revision limit reached.");
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        Selection selection = new Selection(current.campusId(), current.sessionId(),
                current.sessionStartsOn(), current.sessionEndsOn());
        safe("CALENDAR_POLICY_REVISION_CONFLICT", () -> jdbc.update("""
                INSERT INTO school_calendar_policy_revisions(
                  id,organisation_id,school_id,campus_id,academic_session_id,revision_number,
                  supersedes_policy_id,school_start_time,same_day_cutoff_minutes,reminder_time,
                  time_zone,guardian_push_enabled,student_in_app_enabled,emergency_ack_required,
                  change_reason,revised_by_membership_id,revised_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:campusId,:sessionId,:revision,:supersedesId,
                  :schoolStart,:cutoffMinutes,:reminderTime,:timeZone,:guardianPush,:studentInApp,
                  :emergencyAck,:reason,:actorMembershipId,:now,0)
                """, policyParams(scope, id, selection, revision, current.id(), values, now)));
        audit(user, scope, "school.calendar.policy.revised", "SCHOOL_CALENDAR_POLICY", id,
                values.reason(), Map.of("campusId", current.campusId(),
                        "academicSessionId", current.sessionId(), "revision", revision));
        return new SchoolCalendarMutationResponse(id, null, revision, 0, "ACTIVE", null);
    }

    @Transactional
    public SchoolCalendarMutationResponse createNotice(
            AuthenticatedUser user, CreateNoticeRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "calendar.manage");
        Selection selection = selection(scope, request.campusId(), request.academicSessionId());
        lock(scope, selection.campusId(), selection.sessionId());
        Integer duplicates = jdbc.queryForObject("""
                SELECT count(*) FROM school_calendar_notice_series
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND campus_id=:campusId AND academic_session_id=:sessionId
                  AND calendar_date=:calendarDate
                """, scope.params().addValue("campusId", selection.campusId())
                .addValue("sessionId", selection.sessionId())
                .addValue("calendarDate", request.calendarDate()), Integer.class);
        if (duplicates != null && duplicates > 0) {
            throw conflict("CALENDAR_DATE_EXISTS",
                    "The same calendar policy, date, revision, review, or notification already exists.");
        }
        PolicyStored policy = requirePolicy(scope, selection.campusId(), selection.sessionId());
        NoticeValues values = noticeValues(
                request.dayType(), request.noticeKind(), request.titleEn(), request.titleHi(),
                request.bodyEn(), request.bodyHi(), request.sourceReference(),
                request.bilingualContentConfirmed(), request.reason());
        assertDateAndCutoff(request.calendarDate(), selection, policy);
        UUID seriesId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        safe("CALENDAR_DATE_EXISTS", () -> {
            jdbc.update("""
                    INSERT INTO school_calendar_notice_series(
                      id,organisation_id,school_id,campus_id,academic_session_id,calendar_date,
                      created_by_membership_id,created_at)
                    VALUES(:seriesId,:organisationId,:schoolId,:campusId,:sessionId,:calendarDate,
                      :actorMembershipId,:now)
                    """, scope.params().addValue("seriesId", seriesId)
                    .addValue("campusId", selection.campusId()).addValue("sessionId", selection.sessionId())
                    .addValue("calendarDate", request.calendarDate()).addValue("now", now));
            return jdbc.update("""
                    INSERT INTO school_calendar_notice_revisions(
                      id,organisation_id,school_id,notice_series_id,revision_number,
                      supersedes_revision_id,day_type,notice_kind,title_en,title_hi,body_en,body_hi,
                      source_reference,change_reason,revised_by_membership_id,revised_at,row_version)
                    VALUES(:id,:organisationId,:schoolId,:seriesId,1,NULL,:dayType,:noticeKind,
                      :titleEn,:titleHi,:bodyEn,:bodyHi,:sourceReference,:reason,
                      :actorMembershipId,:now,0)
                    """, noticeParams(scope, revisionId, seriesId, 1, null, values, now));
        });
        audit(user, scope, "school.calendar.notice.created", "SCHOOL_CALENDAR_NOTICE", revisionId,
                values.reason(), Map.of("noticeSeriesId", seriesId,
                        "calendarDate", request.calendarDate().toString(), "revision", 1));
        return new SchoolCalendarMutationResponse(revisionId, seriesId, 1, 0, "DRAFT", null);
    }

    @Transactional
    public SchoolCalendarMutationResponse reviseNotice(
            AuthenticatedUser user, UUID seriesId, ReviseNoticeRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "calendar.manage");
        NoticeStored current = currentNotice(scope, seriesId, true);
        if (current.revisionNumber() != request.expectedRevision()) {
            throw conflict("STALE_CALENDAR_NOTICE", "The calendar notice changed. Refresh and try again.");
        }
        lock(scope, current.campusId(), current.sessionId());
        if (hasPendingReview(scope, seriesId)) {
            throw conflict("CALENDAR_NOTICE_REVIEW_PENDING",
                    "Reject or decide the pending review before recording another revision.");
        }
        PolicyStored policy = requirePolicy(scope, current.campusId(), current.sessionId());
        Selection selection = new Selection(current.campusId(), current.sessionId(),
                current.sessionStartsOn(), current.sessionEndsOn());
        assertDateAndCutoff(current.calendarDate(), selection, policy);
        NoticeValues values = noticeValues(
                request.dayType(), request.noticeKind(), request.titleEn(), request.titleHi(),
                request.bodyEn(), request.bodyHi(), request.sourceReference(),
                request.bilingualContentConfirmed(), request.reason());
        int revision = current.revisionNumber() + 1;
        if (revision > 1000) throw conflict("CALENDAR_NOTICE_REVISION_LIMIT", "Notice revision limit reached.");
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        safe("CALENDAR_NOTICE_REVISION_CONFLICT", () -> jdbc.update("""
                INSERT INTO school_calendar_notice_revisions(
                  id,organisation_id,school_id,notice_series_id,revision_number,
                  supersedes_revision_id,day_type,notice_kind,title_en,title_hi,body_en,body_hi,
                  source_reference,change_reason,revised_by_membership_id,revised_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:seriesId,:revision,:supersedesId,:dayType,
                  :noticeKind,:titleEn,:titleHi,:bodyEn,:bodyHi,:sourceReference,:reason,
                  :actorMembershipId,:now,0)
                """, noticeParams(scope, id, seriesId, revision, current.revisionId(), values, now)));
        audit(user, scope, "school.calendar.notice.revised", "SCHOOL_CALENDAR_NOTICE", id,
                values.reason(), Map.of("noticeSeriesId", seriesId,
                        "calendarDate", current.calendarDate().toString(), "revision", revision));
        return new SchoolCalendarMutationResponse(id, seriesId, revision, 0, "DRAFT", null);
    }

    @Transactional
    public SchoolCalendarMutationResponse submitReview(
            AuthenticatedUser user, UUID seriesId, SubmitReviewRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "calendar.manage");
        NoticeStored current = currentNotice(scope, seriesId, true);
        if (current.revisionNumber() != request.expectedRevision()) {
            throw conflict("STALE_CALENDAR_NOTICE", "The calendar notice changed. Refresh and try again.");
        }
        if (!request.evidenceConfirmed()) {
            throw bad("CALENDAR_NOTICE_CONFIRMATION_REQUIRED",
                    "Confirm the date, bilingual content, source, and family impact before review.");
        }
        lock(scope, current.campusId(), current.sessionId());
        PolicyStored policy = requirePolicy(scope, current.campusId(), current.sessionId());
        Selection selection = new Selection(current.campusId(), current.sessionId(),
                current.sessionStartsOn(), current.sessionEndsOn());
        assertDateAndCutoff(current.calendarDate(), selection, policy);
        if (hasPendingReview(scope, seriesId)) {
            throw conflict("CALENDAR_NOTICE_REVIEW_PENDING",
                    "This calendar date already has a revision waiting for a decision.");
        }
        int reviewed = jdbc.queryForObject("""
                SELECT count(*) FROM school_calendar_notice_reviews
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND notice_revision_id=:revisionId
                """, scope.params().addValue("revisionId", current.revisionId()), Integer.class);
        if (reviewed > 0) {
            throw conflict("CALENDAR_NOTICE_REVISION_ALREADY_REVIEWED",
                    "This revision already has a decision. Record a new revision for any correction.");
        }
        UUID id = UUID.randomUUID();
        String why = reason(request.reason());
        Instant now = Instant.now(clock);
        safe("CALENDAR_NOTICE_REVIEW_CONFLICT", () -> jdbc.update("""
                INSERT INTO school_calendar_notice_reviews(
                  id,organisation_id,school_id,notice_series_id,notice_revision_id,policy_revision_id,
                  status,submission_reason,submitted_by_membership_id,submitted_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:seriesId,:revisionId,:policyId,'PENDING',
                  :reason,:actorMembershipId,:now,0)
                """, scope.params().addValue("id", id).addValue("seriesId", seriesId)
                .addValue("revisionId", current.revisionId()).addValue("policyId", policy.id())
                .addValue("reason", why).addValue("now", now)));
        audit(user, scope, "school.calendar.notice.submitted", "SCHOOL_CALENDAR_NOTICE_REVIEW", id,
                why, Map.of("noticeSeriesId", seriesId, "revision", current.revisionNumber()));
        return new SchoolCalendarMutationResponse(
                id, seriesId, current.revisionNumber(), 0, "PENDING", null);
    }

    @Transactional
    public SchoolCalendarMutationResponse decideReview(
            AuthenticatedUser user, UUID reviewId, ReviewDecisionRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "calendar.approve");
        ReviewStored review = review(scope, reviewId);
        String decision = enumValue(request.decision(), DECISIONS, "calendar decision");
        String why = reason(request.reason());
        if (!"PENDING".equals(review.status())) {
            if (review.status().equals(decision)
                    && review.rowVersion() == request.expectedVersion() + 1
                    && scope.actorMembershipId().equals(review.decidedByMembershipId())
                    && why.equals(review.decisionReason())) {
                return existingDecisionResponse(scope, review);
            }
            throw conflict("STALE_CALENDAR_REVIEW", "This calendar review has already changed.");
        }
        if (review.rowVersion() != request.expectedVersion()) {
            throw conflict("STALE_CALENDAR_REVIEW", "This calendar review has already changed.");
        }
        if (review.submittedByMembershipId().equals(scope.actorMembershipId())) {
            throw conflict("CALENDAR_MAKER_CHECKER_REQUIRED",
                    "A different authorized person must decide this calendar notice.");
        }
        lock(scope, review.campusId(), review.sessionId());
        NoticeStored current = currentNotice(scope, review.seriesId(), true);
        if (!current.revisionId().equals(review.revisionId())) {
            throw conflict("CALENDAR_REVIEW_EVIDENCE_STALE",
                    "The notice changed after submission. Reject it and submit the latest revision.");
        }
        PolicyStored currentPolicy = requirePolicy(scope, review.campusId(), review.sessionId());
        if (!currentPolicy.id().equals(review.policyId())) {
            throw conflict("CALENDAR_REVIEW_POLICY_STALE",
                    "The notification policy changed after submission. Reject and resubmit this notice.");
        }
        Instant scheduledFor = null;
        if ("APPROVED".equals(decision)) {
            Selection selection = new Selection(review.campusId(), review.sessionId(),
                    review.sessionStartsOn(), review.sessionEndsOn());
            assertDateAndCutoff(review.calendarDate(), selection, currentPolicy);
            synchronizeCalendarDay(scope, review);
            scheduledFor = scheduleNotifications(scope, review, currentPolicy);
        }
        jdbc.queryForObject("SELECT set_config('app.school_calendar_review_decision','approved',true)",
                new MapSqlParameterSource(), String.class);
        int changed = jdbc.update("""
                UPDATE school_calendar_notice_reviews SET status=:decision,
                  decided_by_membership_id=:actorMembershipId,decided_at=:now,
                  decision_reason=:reason,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND status='PENDING' AND row_version=:version
                """, scope.params().addValue("id", reviewId).addValue("decision", decision)
                .addValue("reason", why).addValue("now", Instant.now(clock))
                .addValue("version", review.rowVersion()));
        changed(changed);
        audit(user, scope, "school.calendar.notice." + decision.toLowerCase(Locale.ROOT),
                "SCHOOL_CALENDAR_NOTICE_REVIEW", reviewId, why,
                Map.of("noticeSeriesId", review.seriesId(), "revision", review.revisionNumber(),
                        "calendarDate", review.calendarDate().toString()));
        return new SchoolCalendarMutationResponse(reviewId, review.seriesId(),
                review.revisionNumber(), review.rowVersion() + 1, decision, scheduledFor);
    }

    private SchoolCalendarMutationResponse existingDecisionResponse(Scope scope, ReviewStored review) {
        Instant scheduled = jdbc.query("""
                SELECT min(scheduled_for) FROM school_calendar_notification_jobs
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND notice_review_id=:reviewId
                """, scope.params().addValue("reviewId", review.id()), result -> {
            result.next();
            var value = result.getTimestamp(1);
            return value == null ? null : value.toInstant();
        });
        return new SchoolCalendarMutationResponse(review.id(), review.seriesId(),
                review.revisionNumber(), review.rowVersion(), review.status(), scheduled);
    }

    private void synchronizeCalendarDay(Scope scope, ReviewStored review) {
        jdbc.queryForObject("SELECT set_config('app.school_calendar_sync','approved',true)",
                new MapSqlParameterSource(), String.class);
        List<CalendarDayStored> rows = jdbc.query("""
                SELECT id,day_type,display_name,attendance_required,status,row_version
                FROM school_calendar_days
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND campus_id=:campusId AND academic_session_id=:sessionId
                  AND calendar_date=:calendarDate
                FOR UPDATE
                """, scope.params().addValue("campusId", review.campusId())
                .addValue("sessionId", review.sessionId()).addValue("calendarDate", review.calendarDate()),
                (result, row) -> new CalendarDayStored(
                        result.getObject("id", UUID.class), result.getString("day_type"),
                        result.getString("display_name"), result.getBoolean("attendance_required"),
                        result.getString("status"), result.getLong("row_version")));
        boolean attendanceRequired = "INSTRUCTIONAL".equals(review.dayType());
        Instant now = Instant.now(clock);
        if (rows.isEmpty()) {
            jdbc.update("""
                    INSERT INTO school_calendar_days(
                      id,organisation_id,school_id,campus_id,academic_session_id,calendar_date,
                      day_type,display_name,attendance_required,status,authority_notice_revision_id,
                      created_at,updated_at,row_version)
                    VALUES(:id,:organisationId,:schoolId,:campusId,:sessionId,:calendarDate,
                      :dayType,:displayName,:attendanceRequired,'ACTIVE',:revisionId,:now,:now,0)
                    """, scope.params().addValue("id", UUID.randomUUID())
                    .addValue("campusId", review.campusId()).addValue("sessionId", review.sessionId())
                    .addValue("calendarDate", review.calendarDate()).addValue("dayType", review.dayType())
                    .addValue("displayName", review.titleEn()).addValue("attendanceRequired", attendanceRequired)
                    .addValue("revisionId", review.revisionId()).addValue("now", now));
            return;
        }
        CalendarDayStored day = rows.getFirst();
        boolean changesAttendanceEvidence = !day.dayType().equals(review.dayType())
                || day.attendanceRequired() != attendanceRequired || !"ACTIVE".equals(day.status());
        if (changesAttendanceEvidence) {
            int registers = jdbc.queryForObject("""
                    SELECT count(*) FROM attendance_registers
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND calendar_day_id=:calendarDayId
                    """, scope.params().addValue("calendarDayId", day.id()), Integer.class);
            if (registers > 0) {
                throw conflict("CALENDAR_ATTENDANCE_EVIDENCE_EXISTS",
                        "Attendance evidence already exists for this date. Use a formal attendance correction before changing the day.");
            }
        }
        int changed = jdbc.update("""
                UPDATE school_calendar_days SET day_type=:dayType,display_name=:displayName,
                  attendance_required=:attendanceRequired,status='ACTIVE',
                  authority_notice_revision_id=:revisionId,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND row_version=:version
                """, scope.params().addValue("id", day.id()).addValue("dayType", review.dayType())
                .addValue("displayName", review.titleEn()).addValue("attendanceRequired", attendanceRequired)
                .addValue("revisionId", review.revisionId()).addValue("now", now)
                .addValue("version", day.rowVersion()));
        changed(changed);
    }

    private Instant scheduleNotifications(Scope scope, ReviewStored review, PolicyStored policy) {
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        int previousMaterialized = jdbc.queryForObject("""
                SELECT count(*) FROM school_calendar_notification_jobs
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND notice_series_id=:seriesId AND status='MATERIALIZED'
                """, scope.params().addValue("seriesId", review.seriesId()), Integer.class);
        jdbc.update("""
                UPDATE school_calendar_notification_jobs SET status='CANCELLED',cancelled_at=:now,
                  cancellation_reason='Superseded by approved calendar revision',updated_at=:now,
                  row_version=row_version+1
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND notice_series_id=:seriesId AND status='PENDING'
                """, scope.params().addValue("seriesId", review.seriesId()).addValue("now", now));
        ZoneId zone = zone(policy.timeZone());
        Instant scheduled = now;
        if ("PLANNED_HOLIDAY".equals(review.noticeKind()) && previousMaterialized == 0) {
            scheduled = review.calendarDate().minusDays(1).atTime(policy.reminderTime())
                    .atZone(zone).toInstant();
            if (scheduled.isBefore(now)) scheduled = now;
        }
        insertNotificationJob(scope, review, "ALL_FAMILIES", policy.guardianPushEnabled(),
                policy.emergencyAckRequired() && isEmergency(review.noticeKind()), scheduled, now);
        if (policy.studentInAppEnabled()) {
            insertNotificationJob(scope, review, "ALL_STUDENTS", false,
                    policy.emergencyAckRequired() && isEmergency(review.noticeKind()), scheduled, now);
        }
        return scheduled;
    }

    private void insertNotificationJob(
            Scope scope,
            ReviewStored review,
            String audience,
            boolean push,
            boolean acknowledgement,
            Instant scheduled,
            Instant now) {
        safe("CALENDAR_NOTIFICATION_ALREADY_SCHEDULED", () -> jdbc.update("""
                INSERT INTO school_calendar_notification_jobs(
                  id,organisation_id,school_id,notice_series_id,notice_revision_id,notice_review_id,
                  audience_kind,request_push,requires_acknowledgement,scheduled_for,next_attempt_at,
                  status,attempt_count,created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:seriesId,:revisionId,:reviewId,:audience,
                  :push,:ack,:scheduledFor,:scheduledFor,'PENDING',0,:now,:now,0)
                """, scope.params().addValue("id", UUID.randomUUID())
                .addValue("seriesId", review.seriesId()).addValue("revisionId", review.revisionId())
                .addValue("reviewId", review.id()).addValue("audience", audience)
                .addValue("push", push).addValue("ack", acknowledgement)
                .addValue("scheduledFor", scheduled).addValue("now", now)));
    }

    private ReviewStored review(Scope scope, UUID reviewId) {
        List<ReviewStored> rows = jdbc.query("""
                SELECT review.id,review.notice_series_id,review.notice_revision_id,
                  review.policy_revision_id,review.status,review.submitted_by_membership_id,
                  review.decided_by_membership_id,review.decision_reason,review.row_version,
                  revision.revision_number,revision.day_type,revision.notice_kind,
                  revision.title_en,series.campus_id,series.academic_session_id,
                  series.calendar_date,session.starts_on,session.ends_on
                FROM school_calendar_notice_reviews review
                JOIN school_calendar_notice_revisions revision ON revision.id=review.notice_revision_id
                JOIN school_calendar_notice_series series ON series.id=review.notice_series_id
                JOIN academic_sessions session ON session.id=series.academic_session_id
                WHERE review.id=:id AND review.organisation_id=:organisationId
                  AND review.school_id=:schoolId
                FOR UPDATE OF review
                """, scope.params().addValue("id", reviewId), (result, row) -> new ReviewStored(
                result.getObject("id", UUID.class), result.getObject("notice_series_id", UUID.class),
                result.getObject("notice_revision_id", UUID.class),
                result.getObject("policy_revision_id", UUID.class), result.getString("status"),
                result.getObject("submitted_by_membership_id", UUID.class),
                result.getObject("decided_by_membership_id", UUID.class), result.getString("decision_reason"),
                result.getLong("row_version"), result.getInt("revision_number"),
                result.getString("day_type"), result.getString("notice_kind"),
                result.getString("title_en"), result.getObject("campus_id", UUID.class),
                result.getObject("academic_session_id", UUID.class), result.getDate("calendar_date").toLocalDate(),
                result.getDate("starts_on").toLocalDate(), result.getDate("ends_on").toLocalDate()));
        if (rows.isEmpty()) throw notFound("CALENDAR_REVIEW_NOT_FOUND", "The calendar review was not found.");
        return rows.getFirst();
    }

    private List<NoticeItem> currentNotices(
            Scope scope, UUID campusId, UUID sessionId, Map<UUID, List<NotificationJobItem>> jobs) {
        return jdbc.query("""
                SELECT DISTINCT ON (series.id) series.id AS series_id,series.calendar_date,
                  revision.*,account.display_name AS revised_by,
                  review.status AS review_status
                FROM school_calendar_notice_series series
                JOIN school_calendar_notice_revisions revision ON revision.notice_series_id=series.id
                JOIN memberships membership ON membership.id=revision.revised_by_membership_id
                JOIN user_accounts account ON account.id=membership.user_id
                LEFT JOIN school_calendar_notice_reviews review
                  ON review.notice_revision_id=revision.id
                WHERE series.organisation_id=:organisationId AND series.school_id=:schoolId
                  AND series.campus_id=:campusId AND series.academic_session_id=:sessionId
                ORDER BY series.id,revision.revision_number DESC,revision.id
                """, scope.params().addValue("campusId", campusId).addValue("sessionId", sessionId),
                (result, row) -> new NoticeItem(
                        result.getObject("series_id", UUID.class), result.getObject("id", UUID.class),
                        result.getInt("revision_number"), result.getObject("supersedes_revision_id", UUID.class),
                        result.getDate("calendar_date").toLocalDate(), result.getString("day_type"),
                        result.getString("notice_kind"), result.getString("title_en"),
                        result.getString("title_hi"), result.getString("body_en"), result.getString("body_hi"),
                        result.getString("source_reference"), result.getString("change_reason"),
                        result.getString("revised_by"), result.getTimestamp("revised_at").toInstant(),
                        result.getString("review_status"),
                        jobs.getOrDefault(result.getObject("id", UUID.class), List.of())))
                .stream().sorted((left, right) -> left.calendarDate().compareTo(right.calendarDate())).toList();
    }

    private Map<UUID, List<NotificationJobItem>> notificationJobs(
            Scope scope, UUID campusId, UUID sessionId) {
        Map<UUID, List<NotificationJobItem>> grouped = new LinkedHashMap<>();
        jdbc.query("""
                SELECT job.*,
                  (SELECT count(*) FROM communication_deliveries delivery
                    JOIN communication_recipients recipient
                      ON recipient.id=delivery.communication_recipient_id
                    WHERE recipient.communication_announcement_id=job.announcement_id
                      AND delivery.channel='IN_APP' AND delivery.status='READY') in_app_ready_count,
                  (SELECT count(*) FROM communication_deliveries delivery
                    JOIN communication_recipients recipient
                      ON recipient.id=delivery.communication_recipient_id
                    WHERE recipient.communication_announcement_id=job.announcement_id
                      AND delivery.channel='PUSH' AND delivery.status='AWAITING_PROVIDER') push_awaiting_count,
                  (SELECT count(*) FROM communication_deliveries delivery
                    JOIN communication_recipients recipient
                      ON recipient.id=delivery.communication_recipient_id
                    WHERE recipient.communication_announcement_id=job.announcement_id
                      AND delivery.channel='PUSH' AND delivery.status='READY') push_ready_count,
                  (SELECT count(*) FROM communication_deliveries delivery
                    JOIN communication_recipients recipient
                      ON recipient.id=delivery.communication_recipient_id
                    WHERE recipient.communication_announcement_id=job.announcement_id
                      AND delivery.channel='PUSH' AND delivery.status='SENT') push_sent_count,
                  (SELECT count(*) FROM communication_deliveries delivery
                    JOIN communication_recipients recipient
                      ON recipient.id=delivery.communication_recipient_id
                    WHERE recipient.communication_announcement_id=job.announcement_id
                      AND delivery.channel='PUSH' AND delivery.status='DELIVERED') push_delivered_count,
                  (SELECT count(*) FROM communication_deliveries delivery
                    JOIN communication_recipients recipient
                      ON recipient.id=delivery.communication_recipient_id
                    WHERE recipient.communication_announcement_id=job.announcement_id
                      AND delivery.channel='PUSH' AND delivery.status='FAILED') push_failed_count,
                  (SELECT count(*) FROM communication_deliveries delivery
                    JOIN communication_recipients recipient
                      ON recipient.id=delivery.communication_recipient_id
                    WHERE recipient.communication_announcement_id=job.announcement_id
                      AND delivery.channel='PUSH' AND delivery.status='SUPPRESSED') push_suppressed_count
                FROM school_calendar_notification_jobs job
                JOIN school_calendar_notice_series series ON series.id=job.notice_series_id
                WHERE job.organisation_id=:organisationId AND job.school_id=:schoolId
                  AND series.campus_id=:campusId AND series.academic_session_id=:sessionId
                ORDER BY job.created_at,job.audience_kind
                """, scope.params().addValue("campusId", campusId).addValue("sessionId", sessionId), result -> {
            UUID revisionId = result.getObject("notice_revision_id", UUID.class);
            List<String> channels = new ArrayList<>();
            channels.add("IN_APP");
            if (result.getBoolean("request_push")) channels.add("PUSH");
            var next = result.getTimestamp("next_attempt_at");
            grouped.computeIfAbsent(revisionId, ignored -> new ArrayList<>()).add(new NotificationJobItem(
                    result.getObject("id", UUID.class), result.getString("audience_kind"),
                    List.copyOf(channels), result.getBoolean("requires_acknowledgement"),
                    result.getTimestamp("scheduled_for").toInstant(), next.toInstant(),
                    result.getString("status"), result.getObject("announcement_id", UUID.class),
                    (Integer) result.getObject("recipient_count"), result.getInt("attempt_count"),
                    result.getString("last_error_code"), result.getInt("in_app_ready_count"),
                    result.getInt("push_awaiting_count"), result.getInt("push_ready_count"),
                    result.getInt("push_sent_count"), result.getInt("push_delivered_count"),
                    result.getInt("push_failed_count"), result.getInt("push_suppressed_count")));
        });
        grouped.replaceAll((id, list) -> List.copyOf(list));
        return Map.copyOf(grouped);
    }

    private List<ReviewItem> reviews(Scope scope, UUID campusId, UUID sessionId) {
        return jdbc.query("""
                SELECT review.*,revision.revision_number,revision.title_en,revision.title_hi,
                  series.calendar_date,submitter.display_name AS submitted_by,
                  decider.display_name AS decided_by
                FROM school_calendar_notice_reviews review
                JOIN school_calendar_notice_revisions revision ON revision.id=review.notice_revision_id
                JOIN school_calendar_notice_series series ON series.id=review.notice_series_id
                JOIN memberships submitter_membership ON submitter_membership.id=review.submitted_by_membership_id
                JOIN user_accounts submitter ON submitter.id=submitter_membership.user_id
                LEFT JOIN memberships decider_membership ON decider_membership.id=review.decided_by_membership_id
                LEFT JOIN user_accounts decider ON decider.id=decider_membership.user_id
                WHERE review.organisation_id=:organisationId AND review.school_id=:schoolId
                  AND series.campus_id=:campusId AND series.academic_session_id=:sessionId
                ORDER BY CASE review.status WHEN 'PENDING' THEN 0 ELSE 1 END,
                  review.submitted_at DESC,review.id LIMIT 100
                """, scope.params().addValue("campusId", campusId).addValue("sessionId", sessionId),
                (result, row) -> new ReviewItem(
                        result.getObject("id", UUID.class), result.getObject("notice_series_id", UUID.class),
                        result.getObject("notice_revision_id", UUID.class), result.getInt("revision_number"),
                        result.getDate("calendar_date").toLocalDate(), result.getString("title_en"),
                        result.getString("title_hi"), result.getString("status"),
                        result.getString("submission_reason"), result.getString("submitted_by"),
                        result.getTimestamp("submitted_at").toInstant(), result.getString("decided_by"),
                        nullableInstant(result, "decided_at"), result.getString("decision_reason"),
                        result.getLong("row_version")));
    }

    private NoticeStored currentNotice(Scope scope, UUID seriesId, boolean forUpdate) {
        String locking = forUpdate ? " FOR UPDATE OF revision" : "";
        List<NoticeStored> rows = jdbc.query("""
                SELECT revision.*,series.campus_id,series.academic_session_id,series.calendar_date,
                  session.starts_on,session.ends_on
                FROM school_calendar_notice_revisions revision
                JOIN school_calendar_notice_series series ON series.id=revision.notice_series_id
                JOIN academic_sessions session ON session.id=series.academic_session_id
                WHERE series.id=:seriesId AND series.organisation_id=:organisationId
                  AND series.school_id=:schoolId
                ORDER BY revision.revision_number DESC LIMIT 1
                """ + locking, scope.params().addValue("seriesId", seriesId), (result, row) -> new NoticeStored(
                result.getObject("notice_series_id", UUID.class), result.getObject("id", UUID.class),
                result.getInt("revision_number"), result.getString("day_type"),
                result.getString("notice_kind"), result.getObject("campus_id", UUID.class),
                result.getObject("academic_session_id", UUID.class), result.getDate("calendar_date").toLocalDate(),
                result.getDate("starts_on").toLocalDate(), result.getDate("ends_on").toLocalDate()));
        if (rows.isEmpty()) throw notFound("CALENDAR_NOTICE_NOT_FOUND", "The calendar notice was not found.");
        return rows.getFirst();
    }

    private boolean hasPendingReview(Scope scope, UUID seriesId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM school_calendar_notice_reviews
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND notice_series_id=:seriesId AND status='PENDING'
                """, scope.params().addValue("seriesId", seriesId), Integer.class) > 0;
    }

    private PolicyStored requirePolicy(Scope scope, UUID campusId, UUID sessionId) {
        PolicyStored policy = currentPolicy(scope, campusId, sessionId, false);
        if (policy == null) {
            throw conflict("CALENDAR_POLICY_REQUIRED",
                    "Configure school start, same-day cut-off, reminder time, and notification channels first.");
        }
        return policy;
    }

    private PolicyStored currentPolicy(Scope scope, UUID campusId, UUID sessionId, boolean forUpdate) {
        String locking = forUpdate ? " FOR UPDATE" : "";
        List<PolicyStored> rows = jdbc.query("""
                SELECT policy.*,account.display_name AS revised_by,
                  session.starts_on,session.ends_on
                FROM school_calendar_policy_revisions policy
                JOIN memberships membership ON membership.id=policy.revised_by_membership_id
                JOIN user_accounts account ON account.id=membership.user_id
                JOIN academic_sessions session ON session.id=policy.academic_session_id
                WHERE policy.organisation_id=:organisationId AND policy.school_id=:schoolId
                  AND policy.campus_id=:campusId AND policy.academic_session_id=:sessionId
                ORDER BY policy.revision_number DESC LIMIT 1
                """ + locking, scope.params().addValue("campusId", campusId).addValue("sessionId", sessionId),
                this::mapPolicy);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private PolicyStored mapPolicy(java.sql.ResultSet result, int row) throws java.sql.SQLException {
        return new PolicyStored(
                result.getObject("id", UUID.class), result.getObject("campus_id", UUID.class),
                result.getObject("academic_session_id", UUID.class), result.getInt("revision_number"),
                result.getObject("supersedes_policy_id", UUID.class), result.getTime("school_start_time").toLocalTime(),
                result.getInt("same_day_cutoff_minutes"), result.getTime("reminder_time").toLocalTime(),
                result.getString("time_zone"), result.getBoolean("guardian_push_enabled"),
                result.getBoolean("student_in_app_enabled"), result.getBoolean("emergency_ack_required"),
                result.getString("change_reason"), result.getString("revised_by"),
                result.getTimestamp("revised_at").toInstant(), result.getDate("starts_on").toLocalDate(),
                result.getDate("ends_on").toLocalDate());
    }

    private List<CampusOption> campusOptions(Scope scope) {
        return jdbc.query("""
                SELECT id,code,display_name,time_zone FROM campuses
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND status='ACTIVE'
                ORDER BY display_name,id
                """, scope.params(), (result, row) -> new CampusOption(
                result.getObject("id", UUID.class), result.getString("code"),
                result.getString("display_name"), result.getString("time_zone")));
    }

    private List<SessionOption> sessionOptions(Scope scope) {
        return jdbc.query("""
                SELECT id,name,starts_on,ends_on,status FROM academic_sessions
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND status IN ('PLANNED','ACTIVE','CLOSED')
                ORDER BY CASE status WHEN 'ACTIVE' THEN 0 WHEN 'PLANNED' THEN 1 ELSE 2 END,
                  starts_on DESC,id
                """, scope.params(), (result, row) -> new SessionOption(
                result.getObject("id", UUID.class), result.getString("name"),
                result.getDate("starts_on").toLocalDate(), result.getDate("ends_on").toLocalDate(),
                result.getString("status")));
    }

    private Selection selection(Scope scope, UUID campusId, UUID sessionId) {
        int count = jdbc.queryForObject("""
                SELECT count(*) FROM campuses campus CROSS JOIN academic_sessions session
                WHERE campus.id=:campusId AND campus.organisation_id=:organisationId
                  AND campus.school_id=:schoolId AND campus.status='ACTIVE'
                  AND session.id=:sessionId AND session.organisation_id=:organisationId
                  AND session.school_id=:schoolId AND session.status IN ('PLANNED','ACTIVE')
                """, scope.params().addValue("campusId", campusId).addValue("sessionId", sessionId),
                Integer.class);
        if (count != 1) throw forbidden("CALENDAR_SCOPE_DENIED", "The campus or session is unavailable.");
        return jdbc.queryForObject("""
                SELECT id,starts_on,ends_on FROM academic_sessions
                WHERE id=:sessionId AND organisation_id=:organisationId AND school_id=:schoolId
                """, scope.params().addValue("sessionId", sessionId), (result, row) -> new Selection(
                campusId, result.getObject("id", UUID.class), result.getDate("starts_on").toLocalDate(),
                result.getDate("ends_on").toLocalDate()));
    }

    private Scope requireScope(AuthenticatedUser user, UUID schoolId, String requiredCapability) {
        if (user == null || user.activeOrganisationId() == null) {
            throw conflict("ORGANISATION_SELECTION_REQUIRED", "Select a school organisation first.");
        }
        if (user.membershipIds() == null || user.membershipIds().isEmpty()) {
            throw forbidden("CALENDAR_SCOPE_DENIED", "School calendar management is unavailable.");
        }
        List<Scope> scopes = jdbc.query("""
                SELECT school.id,school.display_name,membership.id AS membership_id,
                  EXISTS(SELECT 1 FROM membership_roles mr_manage
                    JOIN role_capabilities rc_manage ON rc_manage.role_id=mr_manage.role_id
                    WHERE mr_manage.membership_id=membership.id
                      AND rc_manage.capability_code='calendar.manage') can_manage,
                  EXISTS(SELECT 1 FROM membership_roles mr_approve
                    JOIN role_capabilities rc_approve ON rc_approve.role_id=mr_approve.role_id
                    WHERE mr_approve.membership_id=membership.id
                      AND rc_approve.capability_code='calendar.approve') can_approve
                FROM memberships membership
                JOIN schools school ON school.id=membership.school_id
                  AND school.organisation_id=membership.organisation_id
                JOIN membership_roles mr ON mr.membership_id=membership.id
                JOIN role_capabilities rc ON rc.role_id=mr.role_id
                WHERE membership.user_id=:userId AND membership.organisation_id=:organisationId
                  AND membership.id IN(:membershipIds)
                  AND (CAST(:schoolId AS uuid) IS NULL OR school.id=:schoolId)
                  AND membership.status='ACTIVE' AND membership.valid_from<=CURRENT_TIMESTAMP
                  AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                  AND rc.capability_code IN ('calendar.manage','calendar.approve')
                  AND (CAST(:requiredCapability AS varchar) IS NULL OR rc.capability_code=:requiredCapability)
                ORDER BY can_approve DESC,membership.created_at LIMIT 1
                """, new MapSqlParameterSource().addValue("userId", user.userId())
                .addValue("organisationId", user.activeOrganisationId())
                .addValue("membershipIds", user.membershipIds()).addValue("schoolId", schoolId)
                .addValue("requiredCapability", requiredCapability), (result, row) -> new Scope(
                user.activeOrganisationId(), result.getObject("id", UUID.class),
                result.getString("display_name"), result.getObject("membership_id", UUID.class),
                result.getBoolean("can_manage"), result.getBoolean("can_approve")));
        if (scopes.isEmpty()) throw forbidden("CALENDAR_SCOPE_DENIED", "School calendar management is unavailable.");
        return scopes.getFirst();
    }

    private void assertDateAndCutoff(LocalDate date, Selection selection, PolicyStored policy) {
        if (date.isBefore(selection.startsOn()) || date.isAfter(selection.endsOn())) {
            throw bad("CALENDAR_DATE_OUTSIDE_SESSION", "Choose a date inside the selected academic session.");
        }
        ZoneId zone = zone(policy.timeZone());
        var localNow = Instant.now(clock).atZone(zone);
        if (date.isBefore(localNow.toLocalDate())) {
            throw conflict("CALENDAR_DATE_PAST", "Past school dates cannot be changed.");
        }
        if (date.equals(localNow.toLocalDate())
                && localNow.toLocalTime().isAfter(policy.cutoffTime())) {
            throw conflict("CALENDAR_SAME_DAY_CUTOFF_PASSED",
                    "The configured same-day calendar cut-off has passed.");
        }
    }

    private PolicyValues policyValues(
            LocalTime schoolStart,
            int cutoffMinutes,
            LocalTime reminderTime,
            String timeZone,
            boolean guardianPush,
            boolean studentInApp,
            boolean emergencyAck,
            boolean confirmed,
            String why) {
        if (!confirmed) throw bad("CALENDAR_POLICY_CONFIRMATION_REQUIRED",
                "Confirm that school leadership approved these session notification rules.");
        String zoneName = required(timeZone, "Enter an IANA time zone.");
        zone(zoneName);
        if (cutoffMinutes > schoolStart.toSecondOfDay() / 60) {
            throw bad("CALENDAR_CUTOFF_INVALID", "The same-day cut-off cannot fall on the previous date.");
        }
        return new PolicyValues(schoolStart, cutoffMinutes, reminderTime, zoneName,
                guardianPush, studentInApp, emergencyAck, reason(why));
    }

    private NoticeValues noticeValues(
            String dayType,
            String noticeKind,
            String titleEn,
            String titleHi,
            String bodyEn,
            String bodyHi,
            String sourceReference,
            boolean confirmed,
            String why) {
        if (!confirmed) throw bad("CALENDAR_BILINGUAL_CONFIRMATION_REQUIRED",
                "Confirm that both language versions carry the same operational meaning.");
        String day = enumValue(dayType, DAY_TYPES, "calendar day type");
        String kind = enumValue(noticeKind, NOTICE_KINDS, "notice type");
        boolean compatible = ("PLANNED_HOLIDAY".equals(kind) && "HOLIDAY".equals(day))
                || (Set.of("EMERGENCY_CLOSURE", "GOVERNMENT_ORDER").contains(kind) && "CLOSURE".equals(day))
                || ("REOPENING".equals(kind) && "INSTRUCTIONAL".equals(day))
                || "OTHER".equals(kind);
        if (!compatible) throw bad("CALENDAR_NOTICE_TYPE_MISMATCH",
                "The notice type does not match the selected school-day outcome.");
        return new NoticeValues(day, kind, required(titleEn, "Enter the English title."),
                required(titleHi, "Enter the Hindi title."), required(bodyEn, "Enter the English message."),
                required(bodyHi, "Enter the Hindi message."), clean(sourceReference), reason(why));
    }

    private MapSqlParameterSource policyParams(
            Scope scope, UUID id, Selection selection, int revision, UUID supersedesId,
            PolicyValues values, Instant now) {
        return scope.params().addValue("id", id).addValue("campusId", selection.campusId())
                .addValue("sessionId", selection.sessionId()).addValue("revision", revision)
                .addValue("supersedesId", supersedesId).addValue("schoolStart", values.schoolStart())
                .addValue("cutoffMinutes", values.cutoffMinutes()).addValue("reminderTime", values.reminderTime())
                .addValue("timeZone", values.timeZone()).addValue("guardianPush", values.guardianPush())
                .addValue("studentInApp", values.studentInApp()).addValue("emergencyAck", values.emergencyAck())
                .addValue("reason", values.reason()).addValue("now", now);
    }

    private MapSqlParameterSource noticeParams(
            Scope scope, UUID id, UUID seriesId, int revision, UUID supersedesId,
            NoticeValues values, Instant now) {
        return scope.params().addValue("id", id).addValue("seriesId", seriesId)
                .addValue("revision", revision).addValue("supersedesId", supersedesId)
                .addValue("dayType", values.dayType()).addValue("noticeKind", values.noticeKind())
                .addValue("titleEn", values.titleEn()).addValue("titleHi", values.titleHi())
                .addValue("bodyEn", values.bodyEn()).addValue("bodyHi", values.bodyHi())
                .addValue("sourceReference", values.sourceReference()).addValue("reason", values.reason())
                .addValue("now", now);
    }

    private void lock(Scope scope, UUID campusId, UUID sessionId) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(:lockKey,0))",
                new MapSqlParameterSource().addValue("lockKey",
                        scope.schoolId() + ":school-calendar:" + campusId + ":" + sessionId),
                (result, row) -> Boolean.TRUE);
    }

    private void audit(
            AuthenticatedUser user,
            Scope scope,
            String action,
            String subjectType,
            UUID subjectId,
            String why,
            Map<String, ?> metadata) {
        audit.recordScoped(user, scope.actorMembershipId(), scope.schoolId(), action,
                subjectType, subjectId.toString(), AuditOutcome.SUCCEEDED, why, metadata);
    }

    private static UUID selectCampus(List<CampusOption> options, UUID requested) {
        if (requested == null) return options.isEmpty() ? null : options.getFirst().id();
        return options.stream().filter(item -> item.id().equals(requested)).findFirst()
                .orElseThrow(() -> forbidden("CALENDAR_SCOPE_DENIED", "The campus is unavailable.")).id();
    }

    private static UUID selectSession(List<SessionOption> options, UUID requested) {
        if (requested == null) return options.isEmpty() ? null : options.getFirst().id();
        return options.stream().filter(item -> item.id().equals(requested)).findFirst()
                .orElseThrow(() -> forbidden("CALENDAR_SCOPE_DENIED", "The academic session is unavailable.")).id();
    }

    private static boolean isEmergency(String kind) {
        return Set.of("EMERGENCY_CLOSURE", "GOVERNMENT_ORDER").contains(kind);
    }

    private static ZoneId zone(String value) {
        try {
            ZoneId zone = ZoneId.of(value);
            if (zone.getRules().equals(ZoneOffset.UTC.getRules()) && !"UTC".equals(value)) {
                return zone;
            }
            return zone;
        } catch (ZoneRulesException exception) {
            throw bad("CALENDAR_TIME_ZONE_INVALID", "Choose a valid IANA time zone.");
        }
    }

    private static Instant nullableInstant(java.sql.ResultSet result, String column)
            throws java.sql.SQLException {
        var timestamp = result.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static <T> T safe(String code, Work<T> work) {
        try {
            return work.run();
        } catch (DataIntegrityViolationException exception) {
            throw conflict(code, "The same calendar policy, date, revision, review, or notification already exists.");
        }
    }

    private static void changed(int count) {
        if (count != 1) throw conflict("STALE_VERSION", "The record changed. Refresh and try again.");
    }

    private static String enumValue(String value, Set<String> allowed, String label) {
        String normalized = required(value, "Choose a " + label + ".")
                .toUpperCase(Locale.ROOT).replace(' ', '_');
        if (!allowed.contains(normalized)) throw bad("INVALID_VALUE", "Choose a supported " + label + ".");
        return normalized;
    }

    private static String reason(String value) {
        String result = required(value, "Enter the reason for this calendar change.");
        if (result.length() < 3) throw bad("CHANGE_REASON_TOO_SHORT", "Enter a more specific change reason.");
        return result;
    }

    private static String required(String value, String message) {
        String result = clean(value);
        if (result == null) throw bad("REQUIRED_VALUE_MISSING", message);
        return result;
    }

    private static String clean(String value) {
        return value == null || value.isBlank()
                ? null : value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ").strip();
    }

    private static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    private static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private record Scope(
            UUID organisationId,
            UUID schoolId,
            String schoolName,
            UUID actorMembershipId,
            boolean canManage,
            boolean canApprove) {
        MapSqlParameterSource params() {
            return new MapSqlParameterSource().addValue("organisationId", organisationId)
                    .addValue("schoolId", schoolId).addValue("actorMembershipId", actorMembershipId);
        }
    }

    private record Selection(UUID campusId, UUID sessionId, LocalDate startsOn, LocalDate endsOn) {}

    private record PolicyValues(
            LocalTime schoolStart,
            int cutoffMinutes,
            LocalTime reminderTime,
            String timeZone,
            boolean guardianPush,
            boolean studentInApp,
            boolean emergencyAck,
            String reason) {}

    private record PolicyStored(
            UUID id,
            UUID campusId,
            UUID sessionId,
            int revisionNumber,
            UUID supersedesId,
            LocalTime schoolStartTime,
            int cutoffMinutes,
            LocalTime reminderTime,
            String timeZone,
            boolean guardianPushEnabled,
            boolean studentInAppEnabled,
            boolean emergencyAckRequired,
            String changeReason,
            String revisedBy,
            Instant revisedAt,
            LocalDate sessionStartsOn,
            LocalDate sessionEndsOn) {
        LocalTime cutoffTime() {
            return schoolStartTime.minusMinutes(cutoffMinutes);
        }

        PolicyItem publicItem() {
            return new PolicyItem(id, revisionNumber, supersedesId, schoolStartTime,
                    cutoffMinutes, cutoffTime(), reminderTime, timeZone, guardianPushEnabled,
                    studentInAppEnabled, emergencyAckRequired, changeReason, revisedBy, revisedAt);
        }
    }

    private record NoticeValues(
            String dayType,
            String noticeKind,
            String titleEn,
            String titleHi,
            String bodyEn,
            String bodyHi,
            String sourceReference,
            String reason) {}

    private record NoticeStored(
            UUID seriesId,
            UUID revisionId,
            int revisionNumber,
            String dayType,
            String noticeKind,
            UUID campusId,
            UUID sessionId,
            LocalDate calendarDate,
            LocalDate sessionStartsOn,
            LocalDate sessionEndsOn) {}

    private record ReviewStored(
            UUID id,
            UUID seriesId,
            UUID revisionId,
            UUID policyId,
            String status,
            UUID submittedByMembershipId,
            UUID decidedByMembershipId,
            String decisionReason,
            long rowVersion,
            int revisionNumber,
            String dayType,
            String noticeKind,
            String titleEn,
            UUID campusId,
            UUID sessionId,
            LocalDate calendarDate,
            LocalDate sessionStartsOn,
            LocalDate sessionEndsOn) {}

    private record CalendarDayStored(
            UUID id,
            String dayType,
            String displayName,
            boolean attendanceRequired,
            String status,
            long rowVersion) {}

    @FunctionalInterface
    private interface Work<T> {
        T run();
    }
}
