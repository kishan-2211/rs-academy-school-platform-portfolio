package com.educore.api.studentportal;

import com.educore.api.academics.CurriculumProjectionService;
import com.educore.api.academics.PublishedCurriculumResponse;
import com.educore.api.common.api.ApiException;
import com.educore.api.files.PrivateFileService;
import com.educore.api.files.PrivateFileSummary;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import com.educore.api.learning.LearningMutationResponse;
import com.educore.api.schoolcalendar.PublishedSchoolCalendarResponse;
import com.educore.api.schoolcalendar.PublishedSchoolCalendarService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class StudentPortalService {

    private static final Set<String> SUBMISSION_ACTIONS = Set.of("DRAFT", "SUBMIT");

    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final PrivateFileService files;
    private final CurriculumProjectionService curriculum;
    private final PublishedSchoolCalendarService schoolCalendar;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public StudentPortalService(
            NamedParameterJdbcTemplate jdbc,
            SecurityAuditService audit,
            PrivateFileService files,
            CurriculumProjectionService curriculum,
            PublishedSchoolCalendarService schoolCalendar,
            ObjectMapper objectMapper,
            Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.files = files;
        this.curriculum = curriculum;
        this.schoolCalendar = schoolCalendar;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public StudentPortalResponses.Overview overview(AuthenticatedUser user) {
        Scope scope = requireScope(user);
        return new StudentPortalResponses.Overview(
                scope.learnerId(), reference(scope.learnerId()), scope.legalName(),
                scope.preferredName(), scope.schoolName(), scope.campusName(), scope.sessionName(),
                scope.gradeCode(), scope.sectionName(), scope.rollNumber(),
                scope.preferredLanguage(), scope.activatedAt());
    }

    @Transactional(readOnly = true)
    public StudentPortalResponses.Schedule schedule(AuthenticatedUser user) {
        Scope scope = requireScope(user);
        if (scope.sectionId() == null) {
            return new StudentPortalResponses.Schedule(scope.learnerId(), null, null, null, List.of());
        }
        List<PublishedPlan> plans = jdbc.query("""
                SELECT id,plan_name,published_at FROM timetable_plans
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND campus_id=:campusId AND academic_session_id=:sessionId AND status='PUBLISHED'
                ORDER BY published_at DESC,id DESC LIMIT 1
                """, scope.parameters(), (result, row) -> new PublishedPlan(
                result.getObject("id", UUID.class), result.getString("plan_name"),
                instant(result, "published_at")));
        if (plans.isEmpty()) {
            return new StudentPortalResponses.Schedule(scope.learnerId(), null, null, null, List.of());
        }
        PublishedPlan plan = plans.getFirst();
        List<StudentPortalResponses.ScheduleEntry> entries = jdbc.query("""
                SELECT id,day_of_week_snapshot,sequence_number_snapshot,period_name_snapshot,
                       starts_at_snapshot,ends_at_snapshot,subject_name_snapshot,
                       subject_short_name_snapshot,room_label
                FROM timetable_entries
                WHERE timetable_plan_id=:planId AND class_section_id=:sectionId
                  AND organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY day_of_week_snapshot,starts_at_snapshot,sequence_number_snapshot,id
                """, scope.parameters().addValue("planId", plan.id())
                .addValue("sectionId", scope.sectionId()), (result, row) ->
                new StudentPortalResponses.ScheduleEntry(
                        result.getObject("id", UUID.class), result.getInt("day_of_week_snapshot"),
                        result.getInt("sequence_number_snapshot"), result.getString("period_name_snapshot"),
                        result.getObject("starts_at_snapshot", LocalTime.class),
                        result.getObject("ends_at_snapshot", LocalTime.class),
                        result.getString("subject_name_snapshot"),
                        result.getString("subject_short_name_snapshot"), result.getString("room_label")));
        return new StudentPortalResponses.Schedule(
                scope.learnerId(), plan.id(), plan.name(), plan.publishedAt(), entries);
    }

    @Transactional(readOnly = true)
    public PublishedCurriculumResponse curriculum(AuthenticatedUser user) {
        Scope scope = requireScope(user);
        return curriculum.published(
                scope.organisationId(), scope.schoolId(), scope.learnerId(), scope.sessionId(),
                scope.sessionName(), scope.gradeCode());
    }

    @Transactional(readOnly = true)
    public PublishedSchoolCalendarResponse calendar(AuthenticatedUser user) {
        Scope scope = requireScope(user);
        return schoolCalendar.published(
                scope.organisationId(), scope.schoolId(), scope.campusId(), scope.sessionId(),
                scope.learnerId());
    }

    @Transactional(readOnly = true)
    public StudentPortalResponses.Attendance attendance(
            AuthenticatedUser user, LocalDate requestedFrom, LocalDate requestedTo) {
        Scope scope = requireScope(user);
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(scope.timeZone())));
        LocalDate to = requestedTo == null ? today : requestedTo;
        LocalDate from = requestedFrom == null ? to.minusDays(30) : requestedFrom;
        validateAttendanceRange(from, to);
        List<AttendanceRow> stored = jdbc.query("""
                SELECT mark.id AS mark_id,day.calendar_date,register.register_mode,
                       register.period_name_snapshot,register.period_starts_at_snapshot,
                       register.period_ends_at_snapshot,mark.state_code,register.policy_snapshot
                FROM attendance_marks mark
                JOIN attendance_registers register ON register.id=mark.attendance_register_id
                 AND register.organisation_id=mark.organisation_id AND register.school_id=mark.school_id
                JOIN school_calendar_days day ON day.id=register.calendar_day_id
                 AND day.organisation_id=register.organisation_id AND day.school_id=register.school_id
                WHERE mark.organisation_id=:organisationId AND mark.school_id=:schoolId
                  AND mark.learner_id=:learnerId AND register.status IN ('SUBMITTED','LOCKED')
                  AND day.calendar_date BETWEEN :from AND :to
                ORDER BY day.calendar_date DESC,register.period_starts_at_snapshot DESC NULLS LAST,
                         register.created_at DESC,mark.id
                """, scope.parameters().addValue("from", from).addValue("to", to),
                (result, row) -> new AttendanceRow(
                        result.getObject("mark_id", UUID.class),
                        result.getObject("calendar_date", LocalDate.class),
                        result.getString("register_mode"), result.getString("period_name_snapshot"),
                        result.getObject("period_starts_at_snapshot", LocalTime.class),
                        result.getObject("period_ends_at_snapshot", LocalTime.class),
                        result.getString("state_code"), policy(result.getString("policy_snapshot"))));
        BigDecimal earned = BigDecimal.ZERO;
        BigDecimal warning = null;
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<StudentPortalResponses.AttendanceRecord> records = new ArrayList<>();
        for (AttendanceRow row : stored) {
            BigDecimal weight = row.policy().stateWeights().get(row.stateCode());
            if (weight == null) {
                throw new IllegalStateException("Frozen attendance policy does not define the recorded state");
            }
            earned = earned.add(weight);
            counts.merge(row.stateCode(), 1, Integer::sum);
            if (warning == null) warning = row.policy().warningThresholdPercent();
            records.add(new StudentPortalResponses.AttendanceRecord(
                    row.markId(), row.date(), row.registerMode(), row.periodName(), row.startsAt(),
                    row.endsAt(), row.stateCode(), weight));
        }
        BigDecimal percentage = stored.isEmpty() ? null : earned.multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(stored.size()), 2, RoundingMode.HALF_UP);
        return new StudentPortalResponses.Attendance(
                scope.learnerId(), from, to,
                new StudentPortalResponses.AttendanceSummary(
                        stored.size(), earned.setScale(2, RoundingMode.HALF_UP), percentage, warning,
                        percentage != null && warning != null && percentage.compareTo(warning) < 0, counts),
                records);
    }

    @Transactional(readOnly = true)
    public StudentPortalResponses.Results results(AuthenticatedUser user) {
        Scope scope = requireScope(user);
        List<StudentPortalResponses.ReportCard> cards = jdbc.query("""
                SELECT card.id AS report_card_id,publication.id AS publication_id,
                       publication.publication_version,card.report_number,session.name AS session_name,
                       term.display_name AS term_name,card.section_name_snapshot,card.overall_percentage,
                       card.overall_grade_code,card.overall_outcome,card.published_at,
                       card.snapshot_sha256,card.report_snapshot::text
                FROM learner_report_cards card
                JOIN result_publications publication ON publication.id=card.result_publication_id
                 AND publication.organisation_id=card.organisation_id AND publication.school_id=card.school_id
                 AND publication.status='PUBLISHED'
                JOIN academic_sessions session ON session.id=publication.academic_session_id
                JOIN academic_terms term ON term.id=publication.academic_term_id
                WHERE card.organisation_id=:organisationId AND card.school_id=:schoolId
                  AND card.learner_id=:learnerId
                ORDER BY card.published_at DESC,card.id
                """, scope.parameters(), (result, row) -> new StudentPortalResponses.ReportCard(
                result.getObject("report_card_id", UUID.class),
                result.getObject("publication_id", UUID.class), result.getInt("publication_version"),
                result.getString("report_number"), result.getString("session_name"),
                result.getString("term_name"), result.getString("section_name_snapshot"),
                result.getBigDecimal("overall_percentage"), result.getString("overall_grade_code"),
                result.getString("overall_outcome"), instant(result, "published_at"),
                result.getString("snapshot_sha256"), report(result.getString("report_snapshot"))));
        return new StudentPortalResponses.Results(scope.learnerId(), cards);
    }

    @Transactional(readOnly = true)
    public StudentPortalResponses.Assignments assignments(AuthenticatedUser user) {
        Scope scope = requireScope(user);
        List<AssignmentRow> rows = assignmentRows(scope);
        Map<UUID, List<PrivateFileSummary>> assignmentFiles = files.assignmentFiles(
                scope.organisationId(), scope.schoolId(), rows.stream().map(AssignmentRow::assignmentId).toList());
        Map<UUID, List<PrivateFileSummary>> submissionFiles = files.submissionFiles(
                scope.organisationId(), scope.schoolId(), rows.stream().map(AssignmentRow::submissionId).toList());
        List<StudentPortalResponses.Assignment> assignments = rows.stream().map(row ->
                new StudentPortalResponses.Assignment(
                        row.assignmentId(), row.title(), row.instructions(), row.subjectName(), row.categoryName(),
                        row.resourceLabel(), row.resourceUrl(), row.availableFrom(), row.dueAt(),
                        row.submissionMode(), row.maximumPoints(), row.allowAttachments(),
                        row.maxAttachmentCount(), row.attachmentRequired(), row.allowLate(), row.lateUntil(),
                        row.status(), row.publicationRevision(),
                        assignmentFiles.getOrDefault(row.assignmentId(), List.of()),
                        new StudentPortalResponses.Submission(
                                row.submissionId(), row.state(), row.responseText(), row.responseLink(),
                                row.submittedAt(), row.teacherFeedback(), row.pointsAwarded(), row.reviewedAt(),
                                row.rowVersion(), submissionFiles.getOrDefault(row.submissionId(), List.of()))))
                .toList();
        return new StudentPortalResponses.Assignments(scope.learnerId(), assignments);
    }

    @Transactional
    public LearningMutationResponse saveSubmission(
            AuthenticatedUser user, UUID assignmentId, String actionValue,
            String responseTextValue, String responseLinkValue, long expectedVersion) {
        Scope scope = requireScope(user);
        List<StoredSubmission> rows = jdbc.query("""
                SELECT submission.id,submission.state,submission.response_text,submission.response_link,
                       submission.submitted_by_user_id,submission.submitted_by_membership_id,
                       submission.submitted_at,submission.teacher_feedback,submission.points_awarded,
                       submission.reviewed_by_membership_id,submission.reviewed_at,submission.row_version,
                       assignment.status AS assignment_status,assignment.available_from,assignment.due_at,
                       assignment.allow_late,assignment.late_until,assignment.submission_mode,
                       assignment.allow_attachments,assignment.attachment_required,
                       (SELECT count(*) FROM private_files file
                        WHERE file.learner_assignment_submission_id=submission.id
                          AND file.lifecycle_status='AVAILABLE') AS attachment_count,
                       submission.has_attachment_evidence
                FROM assignment_recipients recipient
                JOIN assignments assignment ON assignment.id=recipient.assignment_id
                JOIN learner_assignment_submissions submission ON submission.assignment_recipient_id=recipient.id
                WHERE recipient.assignment_id=:assignmentId AND recipient.learner_id=:learnerId
                  AND recipient.organisation_id=:organisationId AND recipient.school_id=:schoolId
                FOR UPDATE OF submission
                """, scope.parameters().addValue("assignmentId", assignmentId), (result, row) ->
                new StoredSubmission(
                        result.getObject("id", UUID.class), result.getString("assignment_status"),
                        instant(result, "available_from"), instant(result, "due_at"),
                        result.getBoolean("allow_late"), instant(result, "late_until"),
                        result.getString("submission_mode"), result.getBoolean("allow_attachments"),
                        result.getBoolean("attachment_required"), result.getInt("attachment_count"),
                        new SubmissionEvidence(
                                result.getString("state"), result.getString("response_text"),
                                result.getString("response_link"), result.getBoolean("has_attachment_evidence"),
                                result.getObject("submitted_by_user_id", UUID.class),
                                result.getObject("submitted_by_membership_id", UUID.class),
                                instant(result, "submitted_at"), result.getString("teacher_feedback"),
                                result.getBigDecimal("points_awarded"),
                                result.getObject("reviewed_by_membership_id", UUID.class),
                                instant(result, "reviewed_at")), result.getLong("row_version")));
        if (rows.isEmpty()) throw notFound("STUDENT_ASSIGNMENT_NOT_FOUND", "This assignment is unavailable.");
        StoredSubmission stored = rows.getFirst();
        if (stored.rowVersion() != expectedVersion) throw conflict("SUBMISSION_STALE", "Refresh before saving.");
        if (!"PUBLISHED".equals(stored.assignmentStatus()))
            throw conflict("ASSIGNMENT_CLOSED", "This assignment no longer accepts responses.");
        Instant now = Instant.now(clock);
        if (now.isBefore(stored.availableFrom()))
            throw conflict("ASSIGNMENT_NOT_AVAILABLE", "This assignment is not available yet.");
        if (!Set.of("NOT_STARTED", "DRAFT", "RETURNED").contains(stored.evidence().state()))
            throw conflict("SUBMISSION_LOCKED", "This response is awaiting or has completed review.");
        String action = upper(actionValue);
        if (!SUBMISSION_ACTIONS.contains(action))
            throw bad("INVALID_SUBMISSION_ACTION", "Choose save draft or submit.");
        String responseText = clean(responseTextValue);
        String responseLink = clean(responseLinkValue);
        validateResponse(stored, responseText, responseLink, "SUBMIT".equals(action));
        String nextState = "DRAFT";
        Instant submittedAt = null;
        if ("SUBMIT".equals(action)) {
            boolean afterDue = now.isAfter(stored.dueAt());
            if (afterDue && !stored.allowLate())
                throw conflict("ASSIGNMENT_DUE_DATE_PASSED", "Late responses are disabled.");
            if (afterDue && stored.lateUntil() != null && now.isAfter(stored.lateUntil()))
                throw conflict("ASSIGNMENT_LATE_WINDOW_CLOSED", "The late-response window has closed.");
            nextState = afterDue ? "LATE" : "SUBMITTED";
            submittedAt = now;
        }
        SubmissionEvidence next = new SubmissionEvidence(
                nextState, responseText, responseLink, stored.attachmentCount() > 0,
                scope.actorUserId(), scope.actorMembershipId(), submittedAt,
                null, null, null, null);
        int changed = jdbc.update("""
                UPDATE learner_assignment_submissions SET state=:state,response_text=:responseText,
                  response_link=:responseLink,submitted_by_user_id=:actorUserId,
                  submitted_by_membership_id=:actorMembershipId,submitted_at=:submittedAt,
                  teacher_feedback=NULL,points_awarded=NULL,reviewed_by_membership_id=NULL,
                  reviewed_at=NULL,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND row_version=:expectedVersion
                """, scope.parameters().addValue("id", stored.id()).addValue("state", nextState)
                .addValue("responseText", responseText).addValue("responseLink", responseLink)
                .addValue("submittedAt", submittedAt).addValue("now", now)
                .addValue("expectedVersion", expectedVersion));
        if (changed != 1) throw conflict("SUBMISSION_STALE", "Refresh before saving.");
        String kind = "DRAFT".equals(nextState) ? "DRAFT_SAVE"
                : "RETURNED".equals(stored.evidence().state()) ? "RESUBMIT" : "SUBMIT";
        submissionHistory(scope, stored.id(), stored.evidence(), next, kind, now);
        audit.recordScoped(user, scope.actorMembershipId(), scope.schoolId(),
                "DRAFT".equals(nextState) ? "learning.student-submission.saved"
                        : "learning.student-submission.submitted",
                "LEARNER_ASSIGNMENT_SUBMISSION", stored.id().toString(), AuditOutcome.SUCCEEDED,
                "DRAFT".equals(nextState) ? "Student saved own response draft"
                        : "Student submitted own response", Map.of("learnerId", scope.learnerId()));
        return new LearningMutationResponse(stored.id(), expectedVersion + 1);
    }

    private List<AssignmentRow> assignmentRows(Scope scope) {
        return jdbc.query("""
                SELECT assignment.id,assignment.title,assignment.instructions,
                       subject.display_name AS subject_name,category.display_name AS category_name,
                       assignment.resource_label,assignment.resource_url,assignment.available_from,
                       assignment.due_at,assignment.submission_mode,assignment.maximum_points,
                       assignment.allow_attachments,assignment.max_attachment_count,
                       assignment.attachment_required,assignment.allow_late,assignment.late_until,
                       assignment.status,assignment.publication_revision,submission.id AS submission_id,
                       submission.state,submission.response_text,submission.response_link,
                       submission.submitted_at,submission.teacher_feedback,submission.points_awarded,
                       submission.reviewed_at,submission.row_version
                FROM assignment_recipients recipient
                JOIN assignments assignment ON assignment.id=recipient.assignment_id
                JOIN section_subject_offerings offering ON offering.id=assignment.section_subject_offering_id
                JOIN school_subjects subject ON subject.id=offering.subject_id
                JOIN learner_assignment_submissions submission ON submission.assignment_recipient_id=recipient.id
                LEFT JOIN assignment_categories category ON category.id=assignment.assignment_category_id
                WHERE recipient.organisation_id=:organisationId AND recipient.school_id=:schoolId
                  AND recipient.learner_id=:learnerId AND assignment.status IN ('PUBLISHED','CLOSED')
                  AND assignment.available_from<=:now
                ORDER BY CASE WHEN assignment.status='PUBLISHED' THEN 0 ELSE 1 END,
                         assignment.due_at DESC,assignment.id
                """, scope.parameters().addValue("now", Instant.now(clock)), (result, row) -> new AssignmentRow(
                result.getObject("id", UUID.class), result.getString("title"),
                result.getString("instructions"), result.getString("subject_name"),
                result.getString("category_name"), result.getString("resource_label"),
                result.getString("resource_url"), instant(result, "available_from"),
                instant(result, "due_at"), result.getString("submission_mode"),
                result.getBigDecimal("maximum_points"), result.getBoolean("allow_attachments"),
                result.getInt("max_attachment_count"), result.getBoolean("attachment_required"),
                result.getBoolean("allow_late"), instant(result, "late_until"),
                result.getString("status"), result.getInt("publication_revision"),
                result.getObject("submission_id", UUID.class), result.getString("state"),
                result.getString("response_text"), result.getString("response_link"),
                instant(result, "submitted_at"), result.getString("teacher_feedback"),
                result.getBigDecimal("points_awarded"), instant(result, "reviewed_at"),
                result.getLong("row_version")));
    }

    private Scope requireScope(AuthenticatedUser user) {
        if (user.activeOrganisationId() == null || user.membershipIds().isEmpty()) {
            throw conflict("ORGANISATION_SELECTION_REQUIRED", "Select your school before opening the student portal.");
        }
        List<Scope> scopes = jdbc.query("""
                SELECT portal.organisation_id,portal.school_id,portal.learner_id,
                       portal.activated_at,membership.id AS actor_membership_id,
                       learner.legal_name,learner.preferred_name,school.display_name AS school_name,
                       campus.id AS campus_id,campus.display_name AS campus_name,campus.time_zone,
                       session.id AS session_id,session.name AS session_name,enrolment.grade_code,
                       placement.class_section_id,section.display_name AS section_name,placement.roll_number,
                       COALESCE(preference.preferred_language,'EN') AS preferred_language
                FROM learner_portal_accounts portal
                JOIN student_portal_consent_requests consent
                  ON consent.id=portal.current_consent_request_id
                 AND consent.organisation_id=portal.organisation_id AND consent.school_id=portal.school_id
                 AND consent.status='APPROVED' AND consent.approved_user_account_id=portal.user_account_id
                JOIN user_accounts account ON account.id=portal.user_account_id AND account.status='ACTIVE'
                JOIN learners learner ON learner.id=portal.learner_id
                 AND learner.organisation_id=portal.organisation_id AND learner.school_id=portal.school_id
                 AND learner.status='ACTIVE'
                JOIN schools school ON school.id=portal.school_id AND school.organisation_id=portal.organisation_id
                JOIN LATERAL (
                  SELECT candidate.* FROM student_enrolments candidate
                  WHERE candidate.learner_id=learner.id
                    AND candidate.organisation_id=learner.organisation_id
                    AND candidate.school_id=learner.school_id AND candidate.status='ACTIVE'
                  ORDER BY candidate.starts_on DESC,candidate.id DESC LIMIT 1
                ) enrolment ON true
                JOIN campuses campus ON campus.id=enrolment.campus_id
                 AND campus.organisation_id=enrolment.organisation_id AND campus.school_id=enrolment.school_id
                JOIN academic_sessions session ON session.id=enrolment.academic_session_id
                 AND session.organisation_id=enrolment.organisation_id AND session.school_id=enrolment.school_id
                LEFT JOIN student_section_placements placement ON placement.enrolment_id=enrolment.id
                 AND placement.organisation_id=enrolment.organisation_id
                 AND placement.school_id=enrolment.school_id AND placement.status='ACTIVE'
                LEFT JOIN class_sections section ON section.id=placement.class_section_id
                 AND section.organisation_id=placement.organisation_id AND section.school_id=placement.school_id
                JOIN memberships membership ON membership.user_id=portal.user_account_id
                 AND membership.organisation_id=portal.organisation_id AND membership.school_id=portal.school_id
                 AND membership.status='ACTIVE' AND membership.valid_from<=CURRENT_TIMESTAMP
                 AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                LEFT JOIN communication_contact_preferences preference
                  ON preference.school_id=portal.school_id AND preference.user_account_id=portal.user_account_id
                WHERE portal.user_account_id=:actorUserId AND portal.organisation_id=:organisationId
                  AND portal.status='ACTIVE' AND membership.id IN (:membershipIds)
                  AND EXISTS (SELECT 1 FROM membership_roles mr
                    JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='portal.student')
                ORDER BY membership.created_at,membership.id LIMIT 1
                """, base(user), (result, row) -> new Scope(
                result.getObject("organisation_id", UUID.class), result.getObject("school_id", UUID.class),
                result.getObject("learner_id", UUID.class), user.userId(),
                result.getObject("actor_membership_id", UUID.class), result.getString("legal_name"),
                result.getString("preferred_name"), result.getString("school_name"),
                result.getObject("campus_id", UUID.class), result.getString("campus_name"),
                result.getString("time_zone"), result.getObject("session_id", UUID.class),
                result.getString("session_name"), result.getString("grade_code"),
                result.getObject("class_section_id", UUID.class), result.getString("section_name"),
                result.getString("roll_number"), result.getString("preferred_language"),
                instant(result, "activated_at")));
        if (scopes.isEmpty()) {
            throw notFound("STUDENT_PORTAL_UNAVAILABLE",
                    "Your learner account is not active for this school. Ask your guardian or school office for help.");
        }
        return scopes.getFirst();
    }

    private void validateResponse(StoredSubmission stored, String text, String link, boolean finalSubmission) {
        if ("OFFLINE".equals(stored.submissionMode()))
            throw conflict("ASSIGNMENT_OFFLINE_ONLY", "Hand this assignment directly to the school.");
        if (link != null) requireHttpsUrl(link);
        if (stored.attachmentCount() > 0 && !stored.allowAttachments())
            throw conflict("ASSIGNMENT_ATTACHMENTS_DISABLED", "Attachments are disabled for this assignment.");
        if (finalSubmission && stored.attachmentRequired() && stored.attachmentCount() < 1)
            throw bad("ASSIGNMENT_ATTACHMENT_REQUIRED", "Add the required private attachment first.");
        if ("TEXT".equals(stored.submissionMode()) && (text == null || link != null))
            throw bad("ASSIGNMENT_TEXT_REQUIRED", "Enter your text response.");
        if ("LINK".equals(stored.submissionMode()) && (link == null || text != null))
            throw bad("ASSIGNMENT_LINK_REQUIRED", "Enter your HTTPS response link.");
        if ("TEXT_OR_LINK".equals(stored.submissionMode()) && text == null && link == null)
            throw bad("ASSIGNMENT_RESPONSE_REQUIRED", "Enter text or an HTTPS response link.");
        if ("FILE".equals(stored.submissionMode()) && stored.attachmentCount() < 1)
            throw bad("ASSIGNMENT_ATTACHMENT_REQUIRED", "Add a private attachment first.");
        if ("FLEXIBLE".equals(stored.submissionMode())
                && text == null && link == null && stored.attachmentCount() < 1)
            throw bad("ASSIGNMENT_RESPONSE_REQUIRED", "Enter text, a link, or add a private attachment.");
    }

    private void submissionHistory(
            Scope scope, UUID submissionId, SubmissionEvidence previous,
            SubmissionEvidence next, String kind, Instant now) {
        jdbc.update("""
                INSERT INTO learner_submission_history(
                  id,organisation_id,school_id,learner_assignment_submission_id,
                  previous_evidence,new_evidence,change_kind,reason,
                  actor_user_id,actor_membership_id,occurred_at)
                VALUES(:id,:organisationId,:schoolId,:submissionId,
                  CAST(:previous AS jsonb),CAST(:next AS jsonb),:kind,NULL,
                  :actorUserId,:actorMembershipId,:now)
                """, scope.parameters().addValue("id", UUID.randomUUID())
                .addValue("submissionId", submissionId).addValue("previous", json(previous))
                .addValue("next", json(next)).addValue("kind", kind).addValue("now", now));
    }

    private AttendancePolicy policy(String value) {
        try { return objectMapper.readValue(value, AttendancePolicy.class); }
        catch (JacksonException exception) { throw new IllegalStateException("Invalid frozen attendance policy", exception); }
    }

    private Map<String, Object> report(String value) {
        try { return objectMapper.readValue(value, new TypeReference<>() {}); }
        catch (JacksonException exception) { throw new IllegalStateException("Stored report-card evidence is invalid", exception); }
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalStateException("Unable to encode submission evidence", exception); }
    }

    private static void validateAttendanceRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to) || from.isBefore(to.minusDays(366)))
            throw bad("ATTENDANCE_DATE_RANGE_INVALID", "Choose a date range of no more than 367 days.");
    }

    private static void requireHttpsUrl(String value) {
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null)
                throw bad("INVALID_RESPONSE_LINK", "Use a complete HTTPS response link.");
        } catch (URISyntaxException exception) {
            throw bad("INVALID_RESPONSE_LINK", "Use a complete HTTPS response link.");
        }
    }

    private static String upper(String value) {
        String normalized = clean(value);
        if (normalized == null) throw bad("INVALID_SUBMISSION_ACTION", "Choose save draft or submit.");
        return normalized.toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.replaceAll("[\\p{Cntrl}]", " ").strip();
    }

    private static String reference(UUID id) {
        return "STU-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        var value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static MapSqlParameterSource base(AuthenticatedUser user) {
        return new MapSqlParameterSource().addValue("actorUserId", user.userId())
                .addValue("organisationId", user.activeOrganisationId())
                .addValue("membershipIds", user.membershipIds());
    }

    private static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private record Scope(
            UUID organisationId, UUID schoolId, UUID learnerId, UUID actorUserId,
            UUID actorMembershipId, String legalName, String preferredName, String schoolName,
            UUID campusId, String campusName, String timeZone, UUID sessionId, String sessionName,
            String gradeCode, UUID sectionId, String sectionName, String rollNumber,
            String preferredLanguage, Instant activatedAt) {
        MapSqlParameterSource parameters() {
            return new MapSqlParameterSource().addValue("organisationId", organisationId)
                    .addValue("schoolId", schoolId).addValue("learnerId", learnerId)
                    .addValue("actorUserId", actorUserId).addValue("actorMembershipId", actorMembershipId)
                    .addValue("campusId", campusId).addValue("sessionId", sessionId);
        }
    }

    private record PublishedPlan(UUID id, String name, Instant publishedAt) {}
    private record AttendanceRow(
            UUID markId, LocalDate date, String registerMode, String periodName,
            LocalTime startsAt, LocalTime endsAt, String stateCode, AttendancePolicy policy) {}
    private record AttendancePolicy(
            String policyName, String registerMode, List<String> enabledStates,
            Map<String, BigDecimal> stateWeights, LocalTime submissionCutoff,
            int lockAfterHours, int guardianExplanationDays, BigDecimal warningThresholdPercent) {}
    private record AssignmentRow(
            UUID assignmentId, String title, String instructions, String subjectName,
            String categoryName, String resourceLabel, String resourceUrl,
            Instant availableFrom, Instant dueAt, String submissionMode,
            BigDecimal maximumPoints, boolean allowAttachments, int maxAttachmentCount,
            boolean attachmentRequired, boolean allowLate, Instant lateUntil,
            String status, int publicationRevision, UUID submissionId, String state,
            String responseText, String responseLink, Instant submittedAt,
            String teacherFeedback, BigDecimal pointsAwarded, Instant reviewedAt, long rowVersion) {}
    private record StoredSubmission(
            UUID id, String assignmentStatus, Instant availableFrom, Instant dueAt,
            boolean allowLate, Instant lateUntil, String submissionMode,
            boolean allowAttachments, boolean attachmentRequired, int attachmentCount,
            SubmissionEvidence evidence, long rowVersion) {}
    private record SubmissionEvidence(
            String state, String responseText, String responseLink, boolean hasAttachmentEvidence,
            UUID submittedByUserId, UUID submittedByMembershipId, Instant submittedAt,
            String teacherFeedback, BigDecimal pointsAwarded,
            UUID reviewedByMembershipId, Instant reviewedAt) {}
}
