package com.educore.api.family;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.files.PrivateFileService;
import com.educore.api.files.PrivateFileSummary;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import com.educore.api.learning.LearningMutationResponse;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class FamilyLearningService {

    private static final Set<String> ACTIONS = Set.of("DRAFT", "SUBMIT");

    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final PrivateFileService files;

    public FamilyLearningService(
            NamedParameterJdbcTemplate jdbc,
            SecurityAuditService audit,
            ObjectMapper objectMapper,
            Clock clock,
            PrivateFileService files) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.files = files;
    }

    @Transactional(readOnly = true)
    public FamilyAssignmentsResponse assignments(AuthenticatedUser user, UUID learnerId) {
        FamilyContext context = requireContext(user, learnerId);
        List<FamilyAssignmentRow> rows = jdbc.query("""
                SELECT assignment.id, assignment.title, assignment.instructions,
                       subject.display_name AS subject_name, category.display_name AS category_name,
                       assignment.resource_label, assignment.resource_url, assignment.available_from,
                       assignment.due_at, assignment.submission_mode, assignment.maximum_points,
                       assignment.allow_attachments, assignment.max_attachment_count,
                       assignment.attachment_required,
                       assignment.allow_late, assignment.late_until, assignment.status,
                       assignment.publication_revision, submission.id AS submission_id,
                       submission.state, submission.response_text, submission.response_link,
                       submission.submitted_at, submission.teacher_feedback,
                       submission.points_awarded, submission.reviewed_at, submission.row_version
                FROM assignment_recipients recipient
                JOIN assignments assignment ON assignment.id = recipient.assignment_id
                JOIN section_subject_offerings offering ON offering.id = assignment.section_subject_offering_id
                JOIN school_subjects subject ON subject.id = offering.subject_id
                JOIN learner_assignment_submissions submission
                  ON submission.assignment_recipient_id = recipient.id
                LEFT JOIN assignment_categories category ON category.id = assignment.assignment_category_id
                WHERE recipient.organisation_id = :organisationId AND recipient.school_id = :schoolId
                  AND recipient.learner_id = :learnerId
                  AND assignment.status IN ('PUBLISHED', 'CLOSED')
                  AND assignment.available_from <= :now
                ORDER BY CASE WHEN assignment.status = 'PUBLISHED' THEN 0 ELSE 1 END,
                         assignment.due_at DESC, assignment.id
                """, context.parameters().addValue("learnerId", learnerId)
                .addValue("now", Instant.now(clock)), (result, row) -> new FamilyAssignmentRow(
                result.getObject("id", UUID.class), result.getString("title"),
                result.getString("instructions"), result.getString("subject_name"),
                result.getString("category_name"), result.getString("resource_label"),
                result.getString("resource_url"), instant(result, "available_from"),
                instant(result, "due_at"), result.getString("submission_mode"),
                result.getBigDecimal("maximum_points"), result.getBoolean("allow_attachments"),
                result.getInt("max_attachment_count"), result.getBoolean("attachment_required"),
                result.getBoolean("allow_late"),
                instant(result, "late_until"), result.getString("status"),
                result.getInt("publication_revision"), result.getObject("submission_id", UUID.class),
                result.getString("state"), result.getString("response_text"),
                result.getString("response_link"), instant(result, "submitted_at"),
                result.getString("teacher_feedback"), result.getBigDecimal("points_awarded"),
                instant(result, "reviewed_at"), result.getLong("row_version")));
        Map<UUID, List<PrivateFileSummary>> assignmentFiles = files.assignmentFiles(
                context.organisationId(), context.schoolId(), rows.stream().map(FamilyAssignmentRow::assignmentId).toList());
        Map<UUID, List<PrivateFileSummary>> submissionFiles = files.submissionFiles(
                context.organisationId(), context.schoolId(), rows.stream().map(FamilyAssignmentRow::submissionId).toList());
        List<FamilyAssignmentsResponse.Assignment> assignments = rows.stream().map(row ->
                new FamilyAssignmentsResponse.Assignment(
                        row.assignmentId(), row.title(), row.instructions(), row.subjectName(), row.categoryName(),
                        row.resourceLabel(), row.resourceUrl(), row.availableFrom(), row.dueAt(), row.submissionMode(),
                        row.maximumPoints(), row.allowAttachments(), row.maxAttachmentCount(), row.attachmentRequired(),
                        row.allowLate(), row.lateUntil(), row.status(), row.publicationRevision(),
                        assignmentFiles.getOrDefault(row.assignmentId(), List.of()),
                        new FamilyAssignmentsResponse.Submission(
                                row.submissionId(), row.state(), row.responseText(), row.responseLink(),
                                row.submittedAt(), row.teacherFeedback(), row.pointsAwarded(), row.reviewedAt(),
                                row.rowVersion(), submissionFiles.getOrDefault(row.submissionId(), List.of())))).toList();
        return new FamilyAssignmentsResponse(learnerId, assignments);
    }

    @Transactional
    public LearningMutationResponse saveSubmission(
            AuthenticatedUser user,
            UUID learnerId,
            UUID assignmentId,
            String actionValue,
            String responseTextValue,
            String responseLinkValue,
            long expectedVersion) {
        FamilyContext context = requireContext(user, learnerId);
        List<StoredSubmission> rows = jdbc.query("""
                SELECT submission.id, submission.state, submission.response_text,
                       submission.response_link, submission.submitted_by_user_id,
                       submission.submitted_by_membership_id, submission.submitted_at,
                       submission.teacher_feedback, submission.points_awarded,
                       submission.reviewed_by_membership_id, submission.reviewed_at,
                       submission.row_version, assignment.status AS assignment_status,
                       assignment.available_from, assignment.due_at, assignment.allow_late,
                       assignment.late_until, assignment.submission_mode,
                       assignment.allow_attachments, assignment.attachment_required,
                       (SELECT count(*) FROM private_files file
                        WHERE file.learner_assignment_submission_id = submission.id
                          AND file.lifecycle_status = 'AVAILABLE') AS attachment_count,
                       submission.has_attachment_evidence
                FROM assignment_recipients recipient
                JOIN assignments assignment ON assignment.id = recipient.assignment_id
                JOIN learner_assignment_submissions submission
                  ON submission.assignment_recipient_id = recipient.id
                WHERE recipient.assignment_id = :assignmentId AND recipient.learner_id = :learnerId
                  AND recipient.organisation_id = :organisationId AND recipient.school_id = :schoolId
                FOR UPDATE OF submission
                """, context.parameters().addValue("assignmentId", assignmentId)
                .addValue("learnerId", learnerId), (result, row) -> new StoredSubmission(
                result.getObject("id", UUID.class), result.getString("assignment_status"),
                instant(result, "available_from"), instant(result, "due_at"),
                result.getBoolean("allow_late"), instant(result, "late_until"),
                result.getString("submission_mode"), result.getBoolean("allow_attachments"),
                result.getBoolean("attachment_required"), result.getInt("attachment_count"),
                new SubmissionEvidence(
                        result.getString("state"), result.getString("response_text"),
                        result.getString("response_link"), result.getBoolean("has_attachment_evidence"),
                        result.getObject("submitted_by_user_id", UUID.class),
                        result.getObject("submitted_by_membership_id", UUID.class), instant(result, "submitted_at"),
                        result.getString("teacher_feedback"), result.getBigDecimal("points_awarded"),
                        result.getObject("reviewed_by_membership_id", UUID.class), instant(result, "reviewed_at")),
                result.getLong("row_version")));
        if (rows.isEmpty()) {
            throw notFound("FAMILY_ASSIGNMENT_NOT_FOUND",
                    "This assignment is not available to your verified relationship.");
        }
        StoredSubmission stored = rows.getFirst();
        if (stored.rowVersion() != expectedVersion) {
            throw conflict("SUBMISSION_STALE", "Refresh the assignment before saving this response.");
        }
        if (!"PUBLISHED".equals(stored.assignmentStatus())) {
            throw conflict("ASSIGNMENT_CLOSED", "This assignment is closed and no longer accepts responses.");
        }
        Instant now = Instant.now(clock);
        if (now.isBefore(stored.availableFrom())) {
            throw conflict("ASSIGNMENT_NOT_AVAILABLE", "This assignment is not available yet.");
        }
        if (!Set.of("NOT_STARTED", "DRAFT", "RETURNED").contains(stored.evidence().state())) {
            throw conflict("SUBMISSION_LOCKED", "This response is awaiting or has completed teacher review.");
        }
        String action = upper(actionValue);
        if (!ACTIONS.contains(action)) throw badRequest("INVALID_SUBMISSION_ACTION", "Choose save draft or submit.");
        String responseText = clean(responseTextValue);
        String responseLink = clean(responseLinkValue);
        validateResponse(stored.submissionMode(), responseText, responseLink,
                stored.allowAttachments(), stored.attachmentRequired(), stored.attachmentCount(),
                "SUBMIT".equals(action));

        String nextState = "DRAFT";
        Instant submittedAt = null;
        if ("SUBMIT".equals(action)) {
            boolean afterDue = now.isAfter(stored.dueAt());
            if (afterDue && !stored.allowLate()) {
                throw conflict("ASSIGNMENT_DUE_DATE_PASSED", "The school has not enabled late responses for this assignment.");
            }
            if (afterDue && stored.lateUntil() != null && now.isAfter(stored.lateUntil())) {
                throw conflict("ASSIGNMENT_LATE_WINDOW_CLOSED", "The late-response window has closed.");
            }
            nextState = afterDue ? "LATE" : "SUBMITTED";
            submittedAt = now;
        }
        SubmissionEvidence previous = stored.evidence();
        SubmissionEvidence next = new SubmissionEvidence(
                nextState, responseText, responseLink, stored.attachmentCount() > 0, context.actorUserId(),
                context.actorMembershipId(), submittedAt, null, null, null, null);
        int changed = jdbc.update("""
                UPDATE learner_assignment_submissions SET state = :state,
                    response_text = :responseText, response_link = :responseLink,
                    submitted_by_user_id = :actorUserId,
                    submitted_by_membership_id = :actorMembershipId,
                    submitted_at = :submittedAt, teacher_feedback = NULL,
                    points_awarded = NULL, reviewed_by_membership_id = NULL,
                    reviewed_at = NULL, updated_at = :now, row_version = row_version + 1
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                  AND row_version = :expectedVersion
                """, context.parameters().addValue("id", stored.id()).addValue("state", nextState)
                .addValue("responseText", responseText).addValue("responseLink", responseLink)
                .addValue("submittedAt", submittedAt).addValue("now", now)
                .addValue("expectedVersion", expectedVersion));
        if (changed != 1) throw conflict("SUBMISSION_STALE", "Refresh the assignment before saving this response.");
        String kind = "DRAFT".equals(nextState) ? "DRAFT_SAVE"
                : "RETURNED".equals(previous.state()) ? "RESUBMIT" : "SUBMIT";
        history(context, stored.id(), previous, next, kind);
        audit.recordScoped(user, context.actorMembershipId(), context.schoolId(),
                "DRAFT".equals(nextState) ? "learning.family-submission.saved" : "learning.family-submission.submitted",
                "LEARNER_ASSIGNMENT_SUBMISSION", stored.id().toString(), AuditOutcome.SUCCEEDED,
                "DRAFT".equals(nextState) ? "Guardian saved learner response draft" : "Guardian submitted learner response",
                Map.of());
        return new LearningMutationResponse(stored.id(), expectedVersion + 1);
    }

    private FamilyContext requireContext(AuthenticatedUser user, UUID learnerId) {
        if (user.activeOrganisationId() == null) {
            throw conflict("ORGANISATION_SELECTION_REQUIRED", "Select a school organisation before opening family assignments.");
        }
        List<FamilyContext> contexts = jdbc.query("""
                SELECT DISTINCT guardian.id AS guardian_id, learner.school_id,
                       membership.id AS actor_membership_id
                FROM guardians guardian
                JOIN learner_guardian_relationships relationship
                  ON relationship.guardian_id = guardian.id
                 AND relationship.organisation_id = guardian.organisation_id
                 AND relationship.school_id = guardian.school_id
                 AND relationship.portal_access_status = 'ACTIVE'
                JOIN learners learner
                  ON learner.id = relationship.learner_id
                 AND learner.organisation_id = relationship.organisation_id
                 AND learner.school_id = relationship.school_id
                 AND learner.status = 'ACTIVE'
                JOIN memberships membership
                  ON membership.user_id = :actorUserId
                 AND membership.organisation_id = guardian.organisation_id
                 AND membership.school_id = guardian.school_id
                 AND membership.status = 'ACTIVE'
                 AND membership.valid_from <= CURRENT_TIMESTAMP
                 AND (membership.valid_until IS NULL OR membership.valid_until > CURRENT_TIMESTAMP)
                JOIN membership_roles membership_role ON membership_role.membership_id = membership.id
                JOIN role_capabilities capability ON capability.role_id = membership_role.role_id
                 AND capability.capability_code = 'portal.guardian'
                WHERE guardian.user_account_id = :actorUserId
                  AND guardian.organisation_id = :organisationId
                  AND guardian.status = 'ACTIVE'
                  AND learner.id = :learnerId
                  AND membership.id IN (:membershipIds)
                ORDER BY membership.id LIMIT 1
                """, new MapSqlParameterSource().addValue("actorUserId", user.userId())
                .addValue("organisationId", user.activeOrganisationId()).addValue("learnerId", learnerId)
                .addValue("membershipIds", user.membershipIds()), (result, row) -> new FamilyContext(
                user.activeOrganisationId(), result.getObject("school_id", UUID.class),
                user.userId(), result.getObject("actor_membership_id", UUID.class)));
        if (contexts.isEmpty()) {
            throw notFound("FAMILY_CHILD_NOT_FOUND", "This learner is not available to your verified relationship.");
        }
        return contexts.getFirst();
    }

    private void validateResponse(
            String mode,
            String text,
            String link,
            boolean allowAttachments,
            boolean attachmentRequired,
            int attachmentCount,
            boolean finalSubmission) {
        if ("OFFLINE".equals(mode)) {
            throw conflict("ASSIGNMENT_OFFLINE_ONLY", "This assignment is handed in directly to the school.");
        }
        if (link != null) requireHttpsUrl(link);
        if (attachmentCount > 0 && !allowAttachments) {
            throw conflict("ASSIGNMENT_ATTACHMENTS_DISABLED", "This assignment no longer accepts private attachments.");
        }
        if (finalSubmission && attachmentRequired && attachmentCount < 1) {
            throw badRequest("ASSIGNMENT_ATTACHMENT_REQUIRED", "Add the required private attachment before saving this response.");
        }
        if ("TEXT".equals(mode) && (text == null || link != null)) {
            throw badRequest("ASSIGNMENT_TEXT_REQUIRED", "Enter the learner's text response.");
        }
        if ("LINK".equals(mode) && (link == null || text != null)) {
            throw badRequest("ASSIGNMENT_LINK_REQUIRED", "Enter the learner's HTTPS response link.");
        }
        if ("TEXT_OR_LINK".equals(mode) && text == null && link == null) {
            throw badRequest("ASSIGNMENT_RESPONSE_REQUIRED", "Enter text or an HTTPS response link.");
        }
        if ("FILE".equals(mode) && attachmentCount < 1) {
            throw badRequest("ASSIGNMENT_ATTACHMENT_REQUIRED", "Add a private attachment before saving this response.");
        }
        if ("FLEXIBLE".equals(mode) && text == null && link == null && attachmentCount < 1) {
            throw badRequest("ASSIGNMENT_RESPONSE_REQUIRED",
                    "Enter text, an HTTPS link, or add a private attachment.");
        }
    }

    private void history(
            FamilyContext context, UUID submissionId, SubmissionEvidence previous,
            SubmissionEvidence next, String kind) {
        jdbc.update("""
                INSERT INTO learner_submission_history (
                    id, organisation_id, school_id, learner_assignment_submission_id,
                    previous_evidence, new_evidence, change_kind, reason,
                    actor_user_id, actor_membership_id, occurred_at
                ) VALUES (:id, :organisationId, :schoolId, :submissionId,
                    CAST(:previous AS jsonb), CAST(:next AS jsonb), :kind, NULL,
                    :actorUserId, :actorMembershipId, :now)
                """, context.parameters().addValue("id", UUID.randomUUID()).addValue("submissionId", submissionId)
                .addValue("previous", json(previous)).addValue("next", json(next))
                .addValue("kind", kind).addValue("now", Instant.now(clock)));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to encode learner submission evidence", exception);
        }
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        return result.getTimestamp(column) == null ? null : result.getTimestamp(column).toInstant();
    }

    private static void requireHttpsUrl(String value) {
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw badRequest("INVALID_RESPONSE_LINK", "Use a complete HTTPS response link.");
            }
        } catch (URISyntaxException exception) {
            throw badRequest("INVALID_RESPONSE_LINK", "Use a complete HTTPS response link.");
        }
    }

    private static String upper(String value) {
        String clean = clean(value);
        if (clean == null) throw badRequest("INVALID_SUBMISSION_ACTION", "Choose save draft or submit.");
        return clean.toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) return null;
        return value.replaceAll("[\\p{Cntrl}]", " ").strip();
    }

    private static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private record FamilyContext(
            UUID organisationId, UUID schoolId, UUID actorUserId, UUID actorMembershipId) {
        MapSqlParameterSource parameters() {
            return new MapSqlParameterSource().addValue("organisationId", organisationId)
                    .addValue("schoolId", schoolId).addValue("actorUserId", actorUserId)
                    .addValue("actorMembershipId", actorMembershipId);
        }
    }

    private record StoredSubmission(
            UUID id, String assignmentStatus, Instant availableFrom, Instant dueAt,
            boolean allowLate, Instant lateUntil, String submissionMode,
            boolean allowAttachments, boolean attachmentRequired, int attachmentCount,
            SubmissionEvidence evidence, long rowVersion) {}

    private record SubmissionEvidence(
            String state, String responseText, String responseLink, boolean hasAttachmentEvidence,
            UUID submittedByUserId, UUID submittedByMembershipId, Instant submittedAt,
            String teacherFeedback, java.math.BigDecimal pointsAwarded,
            UUID reviewedByMembershipId, Instant reviewedAt) {}

    private record FamilyAssignmentRow(
            UUID assignmentId, String title, String instructions, String subjectName,
            String categoryName, String resourceLabel, String resourceUrl,
            Instant availableFrom, Instant dueAt, String submissionMode,
            java.math.BigDecimal maximumPoints, boolean allowAttachments,
            int maxAttachmentCount, boolean attachmentRequired,
            boolean allowLate, Instant lateUntil, String status, int publicationRevision,
            UUID submissionId, String state, String responseText, String responseLink,
            Instant submittedAt, String teacherFeedback, java.math.BigDecimal pointsAwarded,
            Instant reviewedAt, long rowVersion) {}
}
