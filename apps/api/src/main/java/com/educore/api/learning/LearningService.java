package com.educore.api.learning;

import static com.educore.api.learning.LearningController.*;
import static com.educore.api.learning.LearningSnapshot.*;

import com.educore.api.common.api.ApiException;
import com.educore.api.files.PrivateFileService;
import com.educore.api.files.PrivateFileSummary;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class LearningService {

    private static final Set<String> ACTIVE_STATUSES = Set.of("ACTIVE", "INACTIVE");
    private static final Set<String> SUBMISSION_MODES = Set.of(
            "TEXT", "LINK", "TEXT_OR_LINK", "FILE", "FLEXIBLE", "OFFLINE");
    private static final Set<String> REVIEW_DECISIONS = Set.of("RETURNED", "ACCEPTED", "EXEMPT");

    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final PrivateFileService files;

    public LearningService(
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
    public LearningSnapshot snapshot(AuthenticatedUser user, UUID requestedSchoolId) {
        Scope scope = requireScope(user, requestedSchoolId, "learning.read");
        MapSqlParameterSource parameters = scope.parameters();
        String visibleOffering = """
                (:canModerate OR EXISTS (
                    SELECT 1 FROM teaching_allocations allocation
                    WHERE allocation.section_subject_offering_id = offering.id
                      AND allocation.organisation_id = offering.organisation_id
                      AND allocation.school_id = offering.school_id
                      AND allocation.teacher_membership_id = :actorMembershipId
                      AND allocation.status = 'ACTIVE'
                      AND allocation.starts_on <= CURRENT_DATE
                      AND (allocation.ends_on IS NULL OR allocation.ends_on >= CURRENT_DATE)))
                """;
        String visibleAssignment = """
                (:canModerate OR EXISTS (
                    SELECT 1 FROM teaching_allocations allocation
                    WHERE allocation.section_subject_offering_id = offering.id
                      AND allocation.organisation_id = offering.organisation_id
                      AND allocation.school_id = offering.school_id
                      AND allocation.teacher_membership_id = :actorMembershipId
                      AND allocation.status = 'ACTIVE'
                      AND allocation.starts_on <= (assignment.available_from AT TIME ZONE campus.time_zone)::date
                      AND (allocation.ends_on IS NULL
                          OR allocation.ends_on >= (assignment.available_from AT TIME ZONE campus.time_zone)::date)))
                """;
        List<AssignmentRow> assignmentRows = jdbc.query("""
                SELECT assignment.id, assignment.academic_term_id, assignment.class_section_id,
                       assignment.section_subject_offering_id, assignment.assignment_category_id,
                       assignment.title, assignment.instructions, subject.display_name AS subject_name,
                       category.display_name AS category_name, assignment.resource_label,
                       assignment.resource_url, assignment.available_from, assignment.due_at,
                       assignment.submission_mode, assignment.maximum_points,
                       assignment.allow_attachments, assignment.max_attachment_count,
                       assignment.attachment_required, assignment.allow_late,
                       assignment.late_until, assignment.status, assignment.publication_revision,
                       assignment.expected_learner_count, assignment.published_at, assignment.closed_at,
                       assignment.row_version
                FROM assignments assignment
                JOIN section_subject_offerings offering ON offering.id = assignment.section_subject_offering_id
                JOIN school_subjects subject ON subject.id = offering.subject_id
                JOIN class_sections assignment_section ON assignment_section.id = assignment.class_section_id
                JOIN campuses campus ON campus.id = assignment_section.campus_id
                LEFT JOIN assignment_categories category ON category.id = assignment.assignment_category_id
                WHERE assignment.organisation_id = :organisationId AND assignment.school_id = :schoolId
                  AND %s
                ORDER BY assignment.due_at DESC, assignment.created_at DESC, assignment.id
                """.formatted(visibleAssignment), parameters, LearningService::mapAssignmentRow);

        Map<UUID, List<PrivateFileSummary>> assignmentFiles = files.assignmentFiles(
                scope.organisationId(), scope.schoolId(), assignmentRows.stream().map(AssignmentRow::id).toList());
        List<AssignmentItem> assignments = assignmentRows.stream().map(row -> new AssignmentItem(
                row.id(), row.termId(), row.sectionId(), row.offeringId(), row.categoryId(),
                row.title(), row.instructions(), row.subjectName(), row.categoryName(),
                row.resourceLabel(), row.resourceUrl(), row.availableFrom(), row.dueAt(),
                row.submissionMode(), row.maximumPoints(), row.allowAttachments(),
                row.maxAttachmentCount(), row.attachmentRequired(), row.allowLate(), row.lateUntil(),
                row.status(), row.publicationRevision(), row.expectedLearnerCount(),
                submissionCounts(scope, row.id()), row.publishedAt(), row.closedAt(),
                row.rowVersion(), assignmentFiles.getOrDefault(row.id(), List.of()),
                submissions(scope, row.id()))).toList();

        return new LearningSnapshot(
                scope.schoolId(), scope.schoolName(), scope.canModerate(),
                jdbc.query("""
                        SELECT id, name, starts_on, ends_on, status FROM academic_sessions
                        WHERE organisation_id = :organisationId AND school_id = :schoolId
                        ORDER BY starts_on DESC, id
                        """, parameters, (result, row) -> new SessionItem(
                        result.getObject("id", UUID.class), result.getString("name"),
                        result.getObject("starts_on", LocalDate.class), result.getObject("ends_on", LocalDate.class),
                        result.getString("status"))),
                jdbc.query("""
                        SELECT id, academic_session_id, code, display_name, starts_on, ends_on, status
                        FROM academic_terms WHERE organisation_id = :organisationId AND school_id = :schoolId
                        ORDER BY starts_on DESC, code, id
                        """, parameters, (result, row) -> new TermItem(
                        result.getObject("id", UUID.class), result.getObject("academic_session_id", UUID.class),
                        result.getString("code"), result.getString("display_name"),
                        result.getObject("starts_on", LocalDate.class), result.getObject("ends_on", LocalDate.class),
                        result.getString("status"))),
                jdbc.query("""
                        SELECT DISTINCT section.id, section.academic_session_id, section.grade_code, section.display_name
                        FROM class_sections section
                        LEFT JOIN section_subject_offerings offering ON offering.class_section_id = section.id
                        WHERE section.organisation_id = :organisationId AND section.school_id = :schoolId
                          AND section.status = 'ACTIVE'
                          AND (:canModerate OR (offering.id IS NOT NULL AND %s))
                        ORDER BY section.grade_code, section.display_name, section.id
                        """.formatted(visibleOffering), parameters, (result, row) -> new SectionItem(
                        result.getObject("id", UUID.class), result.getObject("academic_session_id", UUID.class),
                        result.getString("grade_code"), result.getString("display_name"))),
                jdbc.query("""
                        SELECT offering.id, offering.class_section_id, subject.id AS subject_id,
                               subject.code AS subject_code, subject.display_name AS subject_name
                        FROM section_subject_offerings offering
                        JOIN school_subjects subject ON subject.id = offering.subject_id
                        WHERE offering.organisation_id = :organisationId AND offering.school_id = :schoolId
                          AND offering.status = 'ACTIVE' AND %s
                        ORDER BY offering.class_section_id, subject.display_name, offering.id
                        """.formatted(visibleOffering), parameters, (result, row) -> new OfferingItem(
                        result.getObject("id", UUID.class), result.getObject("class_section_id", UUID.class),
                        result.getObject("subject_id", UUID.class), result.getString("subject_code"),
                        result.getString("subject_name"))),
                jdbc.query("""
                        SELECT id, academic_session_id, category_code, display_name, accent_color,
                               display_order, status, row_version
                        FROM assignment_categories
                        WHERE organisation_id = :organisationId AND school_id = :schoolId
                        ORDER BY academic_session_id, display_order, display_name, id
                        """, parameters, (result, row) -> new CategoryItem(
                        result.getObject("id", UUID.class), result.getObject("academic_session_id", UUID.class),
                        result.getString("category_code"), result.getString("display_name"),
                        result.getString("accent_color"), result.getInt("display_order"),
                        result.getString("status"), result.getLong("row_version"))),
                assignments);
    }

    @Transactional
    public LearningMutationResponse createCategory(AuthenticatedUser user, CategoryRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "learning.moderate");
        requireSession(scope, request.academicSessionId());
        UUID id = UUID.randomUUID();
        safely("ASSIGNMENT_CATEGORY_EXISTS", "This assignment category code already exists in the session.", () ->
                jdbc.update("""
                        INSERT INTO assignment_categories (
                            id, organisation_id, school_id, academic_session_id, category_code,
                            display_name, accent_color, display_order, status, created_at, updated_at, row_version
                        ) VALUES (:id, :organisationId, :schoolId, :sessionId, :code,
                            :name, :color, :displayOrder, :status, :now, :now, 0)
                        """, categoryParameters(scope, id, request, null)));
        recordAudit(user, scope, "learning.category.created", "ASSIGNMENT_CATEGORY", id, request.reason());
        return new LearningMutationResponse(id, 0);
    }

    @Transactional
    public LearningMutationResponse updateCategory(AuthenticatedUser user, UUID id, CategoryRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "learning.moderate");
        requireSession(scope, request.academicSessionId());
        long version = requireVersion(request.expectedVersion());
        int changed = safely("ASSIGNMENT_CATEGORY_EXISTS", "This assignment category code already exists in the session.", () ->
                jdbc.update("""
                        UPDATE assignment_categories SET academic_session_id = :sessionId,
                            category_code = :code, display_name = :name, accent_color = :color,
                            display_order = :displayOrder, status = :status,
                            updated_at = :now, row_version = row_version + 1
                        WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                          AND academic_session_id = :sessionId AND row_version = :expectedVersion
                        """, categoryParameters(scope, id, request, version)));
        requireChanged(changed, "ASSIGNMENT_CATEGORY_STALE", "Refresh the assignment category before editing it.");
        recordAudit(user, scope, "learning.category.updated", "ASSIGNMENT_CATEGORY", id, request.reason());
        return new LearningMutationResponse(id, version + 1);
    }

    @Transactional
    public LearningMutationResponse createAssignment(AuthenticatedUser user, AssignmentRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "learning.manage");
        Definition definition = definition(scope, request);
        authorizeOffering(scope, definition.offeringId(), definition.availableDate());
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO assignments (
                    id, organisation_id, school_id, academic_session_id, academic_term_id,
                    class_section_id, section_subject_offering_id, assignment_category_id,
                    title, instructions, resource_label, resource_url, available_from, due_at,
                    submission_mode, maximum_points, allow_attachments, max_attachment_count,
                    attachment_required, allow_late, late_until, status,
                    publication_revision, expected_learner_count, created_by_membership_id,
                    created_at, updated_at, row_version
                ) VALUES (:id, :organisationId, :schoolId, :sessionId, :termId, :sectionId,
                    :offeringId, :categoryId, :title, :instructions, :resourceLabel, :resourceUrl,
                    :availableFrom, :dueAt, :submissionMode, :maximumPoints,
                    :allowAttachments, :maxAttachmentCount, :attachmentRequired, :allowLate,
                    :lateUntil, 'DRAFT', 0, 0, :actorMembershipId, :now, :now, 0)
                """, assignmentParameters(scope, id, request, definition, null));
        recordAudit(user, scope, "learning.assignment.created", "ASSIGNMENT", id, null);
        return new LearningMutationResponse(id, 0);
    }

    @Transactional
    public LearningMutationResponse updateAssignment(
            AuthenticatedUser user, UUID id, AssignmentRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "learning.manage");
        long version = requireVersion(request.expectedVersion());
        StoredAssignment stored = assignmentForUpdate(scope, id, version);
        authorizeOffering(scope, stored.offeringId(), stored.availableDate());
        Definition definition = definition(scope, request);
        authorizeOffering(scope, definition.offeringId(), definition.availableDate());
        if (!stored.termId().equals(definition.termId()) || !stored.offeringId().equals(definition.offeringId())) {
            throw badRequest("ASSIGNMENT_AUDIENCE_IMMUTABLE", "Create a new assignment to use a different term, section, or subject audience.");
        }
        boolean revising = "PUBLISHED".equals(stored.status());
        if ("CLOSED".equals(stored.status())) {
            throw conflict("ASSIGNMENT_CLOSED", "Closed assignments cannot be edited.");
        }
        if (revising) validatePublishedAttachmentRevision(scope, id, request);
        String reason = revising ? requiredReason(request.reason()) : clean(request.reason());
        int nextRevision = stored.publicationRevision() + (revising ? 1 : 0);
        if (revising) {
            jdbc.queryForObject("SELECT set_config('app.assignment_revision', 'approved', true)",
                    new MapSqlParameterSource(), String.class);
        }
        int changed = jdbc.update("""
                UPDATE assignments SET assignment_category_id = :categoryId, title = :title,
                    instructions = :instructions, resource_label = :resourceLabel,
                    resource_url = :resourceUrl, available_from = :availableFrom, due_at = :dueAt,
                    submission_mode = :submissionMode, maximum_points = :maximumPoints,
                    allow_attachments = :allowAttachments,
                    max_attachment_count = :maxAttachmentCount,
                    attachment_required = :attachmentRequired,
                    allow_late = :allowLate, late_until = :lateUntil,
                    publication_revision = :nextRevision, updated_at = :now,
                    row_version = row_version + 1
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                  AND row_version = :expectedVersion AND status = :status
                """, assignmentParameters(scope, id, request, definition, version)
                .addValue("nextRevision", nextRevision).addValue("status", stored.status()));
        requireChanged(changed, "ASSIGNMENT_STALE", "Refresh the assignment before editing it.");
        if (revising) revisionHistory(scope, id, nextRevision, "REVISED", reason);
        recordAudit(user, scope, revising ? "learning.assignment.revised" : "learning.assignment.updated",
                "ASSIGNMENT", id, reason);
        return new LearningMutationResponse(id, version + 1);
    }

    @Transactional
    public LearningMutationResponse publishAssignment(
            AuthenticatedUser user, UUID id, TransitionRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "learning.manage");
        StoredAssignment assignment = assignmentForUpdate(scope, id, request.expectedVersion());
        authorizeOffering(scope, assignment.offeringId(), assignment.availableDate());
        if (!"DRAFT".equals(assignment.status())) {
            throw conflict("ASSIGNMENT_NOT_DRAFT", "Only a draft assignment can be published.");
        }
        List<RosterLearner> roster = roster(scope, assignment.sectionId(), assignment.availableDate());
        if (roster.isEmpty()) {
            throw conflict("ASSIGNMENT_AUDIENCE_EMPTY", "No active learners are placed in this section when the assignment becomes available.");
        }
        Instant now = Instant.now(clock);
        for (RosterLearner learner : roster) {
            UUID recipientId = UUID.randomUUID();
            UUID submissionId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO assignment_recipients (
                        id, organisation_id, school_id, assignment_id, learner_id, enrolment_id,
                        learner_name_snapshot, roll_number_snapshot, assigned_at
                    ) VALUES (:recipientId, :organisationId, :schoolId, :assignmentId, :learnerId,
                        :enrolmentId, :learnerName, :rollNumber, :now)
                    """, scope.parameters().addValue("recipientId", recipientId).addValue("assignmentId", id)
                    .addValue("learnerId", learner.learnerId()).addValue("enrolmentId", learner.enrolmentId())
                    .addValue("learnerName", learner.name()).addValue("rollNumber", learner.rollNumber())
                    .addValue("now", now));
            jdbc.update("""
                    INSERT INTO learner_assignment_submissions (
                        id, organisation_id, school_id, assignment_id, assignment_recipient_id,
                        learner_id, state, created_at, updated_at, row_version
                    ) VALUES (:submissionId, :organisationId, :schoolId, :assignmentId,
                        :recipientId, :learnerId, 'NOT_STARTED', :now, :now, 0)
                    """, scope.parameters().addValue("submissionId", submissionId).addValue("assignmentId", id)
                    .addValue("recipientId", recipientId).addValue("learnerId", learner.learnerId())
                    .addValue("now", now));
            submissionHistory(scope, submissionId, null, SubmissionEvidence.empty(), "INITIAL", null);
        }
        int changed = jdbc.update("""
                UPDATE assignments SET status = 'PUBLISHED', publication_revision = 1,
                    expected_learner_count = :count, published_by_membership_id = :actorMembershipId,
                    published_at = :now, updated_at = :now, row_version = row_version + 1
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                  AND status = 'DRAFT' AND row_version = :expectedVersion
                """, scope.parameters().addValue("id", id).addValue("count", roster.size())
                .addValue("now", now).addValue("expectedVersion", request.expectedVersion()));
        requireChanged(changed, "ASSIGNMENT_STALE", "The assignment changed before it could be published.");
        revisionHistory(scope, id, 1, "PUBLISHED", requiredReason(request.reason()));
        recordAudit(user, scope, "learning.assignment.published", "ASSIGNMENT", id, request.reason());
        return new LearningMutationResponse(id, request.expectedVersion() + 1);
    }

    @Transactional
    public LearningMutationResponse closeAssignment(
            AuthenticatedUser user, UUID id, TransitionRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "learning.manage");
        StoredAssignment assignment = assignmentForUpdate(scope, id, request.expectedVersion());
        authorizeOffering(scope, assignment.offeringId(), assignment.availableDate());
        if (!"PUBLISHED".equals(assignment.status())) {
            throw conflict("ASSIGNMENT_NOT_PUBLISHED", "Only a published assignment can be closed.");
        }
        Instant now = Instant.now(clock);
        int changed = jdbc.update("""
                UPDATE assignments SET status = 'CLOSED', closed_by_membership_id = :actorMembershipId,
                    closed_at = :now, updated_at = :now, row_version = row_version + 1
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                  AND status = 'PUBLISHED' AND row_version = :expectedVersion
                """, scope.parameters().addValue("id", id).addValue("now", now)
                .addValue("expectedVersion", request.expectedVersion()));
        requireChanged(changed, "ASSIGNMENT_STALE", "The assignment changed before it could be closed.");
        recordAudit(user, scope, "learning.assignment.closed", "ASSIGNMENT", id, request.reason());
        return new LearningMutationResponse(id, request.expectedVersion() + 1);
    }

    @Transactional
    public LearningMutationResponse reviewSubmission(
            AuthenticatedUser user, UUID id, ReviewRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "learning.manage");
        StoredSubmission submission = submissionForUpdate(scope, id, request.expectedVersion());
        authorizeOffering(scope, submission.offeringId(), submission.availableDate());
        String decision = enumValue(request.decision(), REVIEW_DECISIONS, "review decision");
        String reason = requiredReason(request.reason());
        String feedback = clean(request.feedback());
        if ("RETURNED".equals(decision) && feedback == null) {
            throw badRequest("ASSIGNMENT_FEEDBACK_REQUIRED", "Enter feedback explaining what the learner should revise.");
        }
        if ("RETURNED".equals(decision) && !Set.of("SUBMITTED", "LATE").contains(submission.evidence().state())) {
            throw conflict("SUBMISSION_NOT_REVIEWABLE", "Only a submitted response can be returned for revision.");
        }
        if ("ACCEPTED".equals(decision)
                && !Set.of("SUBMITTED", "LATE").contains(submission.evidence().state())
                && !("OFFLINE".equals(submission.submissionMode())
                    && "NOT_STARTED".equals(submission.evidence().state()))) {
            throw conflict("SUBMISSION_NOT_REVIEWABLE", "This response is not ready to be accepted.");
        }
        validatePoints(request.pointsAwarded(), submission.maximumPoints());
        Instant now = Instant.now(clock);
        SubmissionEvidence previous = submission.evidence();
        String responseText = "EXEMPT".equals(decision) ? null : previous.responseText();
        String responseLink = "EXEMPT".equals(decision) ? null : previous.responseLink();
        UUID submittedByUser = previous.submittedByUserId();
        UUID submittedByMembership = previous.submittedByMembershipId();
        Instant submittedAt = previous.submittedAt();
        if ("ACCEPTED".equals(decision) && "NOT_STARTED".equals(previous.state())) {
            submittedByUser = scope.actorUserId();
            submittedByMembership = scope.actorMembershipId();
            submittedAt = now;
        }
        if ("EXEMPT".equals(decision)) {
            submittedByUser = null;
            submittedByMembership = null;
            submittedAt = null;
        }
        SubmissionEvidence next = new SubmissionEvidence(
                decision, responseText, responseLink, previous.hasAttachmentEvidence(), submittedByUser, submittedByMembership,
                submittedAt, feedback, request.pointsAwarded(), scope.actorMembershipId(), now);
        int changed = jdbc.update("""
                UPDATE learner_assignment_submissions SET state = :state,
                    response_text = :responseText, response_link = :responseLink,
                    submitted_by_user_id = :submittedByUser,
                    submitted_by_membership_id = :submittedByMembership,
                    submitted_at = :submittedAt, teacher_feedback = :feedback,
                    points_awarded = :points, reviewed_by_membership_id = :actorMembershipId,
                    reviewed_at = :now, updated_at = :now, row_version = row_version + 1
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                  AND row_version = :expectedVersion
                """, scope.parameters().addValue("id", id).addValue("state", decision)
                .addValue("responseText", responseText).addValue("responseLink", responseLink)
                .addValue("submittedByUser", submittedByUser)
                .addValue("submittedByMembership", submittedByMembership)
                .addValue("submittedAt", submittedAt).addValue("feedback", feedback)
                .addValue("points", request.pointsAwarded()).addValue("now", now)
                .addValue("expectedVersion", request.expectedVersion()));
        requireChanged(changed, "SUBMISSION_STALE", "Refresh the learner submission before reviewing it.");
        submissionHistory(scope, id, previous, next,
                "OFFLINE".equals(submission.submissionMode()) && "ACCEPTED".equals(decision)
                        ? "STAFF_RECORD" : "REVIEW",
                reason);
        recordAudit(user, scope, "learning.submission.reviewed", "LEARNER_ASSIGNMENT_SUBMISSION", id, reason);
        return new LearningMutationResponse(id, request.expectedVersion() + 1);
    }

    private Scope requireScope(AuthenticatedUser user, UUID requestedSchoolId, String capability) {
        if (user.activeOrganisationId() == null) {
            throw conflict("ORGANISATION_SELECTION_REQUIRED", "Select a school organisation before opening learning work.");
        }
        List<Scope> scopes = jdbc.query("""
                SELECT school.id AS school_id, school.display_name AS school_name, membership.id AS membership_id,
                       EXISTS (
                           SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id = mr.role_id
                           WHERE mr.membership_id = membership.id AND rc.capability_code = 'learning.moderate'
                       ) AS can_moderate
                FROM memberships membership
                JOIN schools school ON school.id = membership.school_id AND school.organisation_id = membership.organisation_id
                JOIN membership_roles membership_role ON membership_role.membership_id = membership.id
                JOIN roles role ON role.id = membership_role.role_id
                JOIN role_capabilities role_capability ON role_capability.role_id = role.id
                WHERE membership.user_id = :userId AND membership.organisation_id = :organisationId
                  AND membership.id IN (:membershipIds) AND membership.school_id IS NOT NULL
                  AND (CAST(:requestedSchoolId AS uuid) IS NULL OR membership.school_id = :requestedSchoolId)
                  AND membership.status = 'ACTIVE' AND membership.valid_from <= CURRENT_TIMESTAMP
                  AND (membership.valid_until IS NULL OR membership.valid_until > CURRENT_TIMESTAMP)
                  AND (role.organisation_id IS NULL OR role.organisation_id = membership.organisation_id)
                  AND role_capability.capability_code = :capability
                ORDER BY school.display_name, membership.created_at, membership.id LIMIT 1
                """, new MapSqlParameterSource().addValue("userId", user.userId())
                .addValue("organisationId", user.activeOrganisationId()).addValue("membershipIds", user.membershipIds())
                .addValue("requestedSchoolId", requestedSchoolId).addValue("capability", capability),
                (result, row) -> new Scope(user.activeOrganisationId(), result.getObject("school_id", UUID.class),
                        result.getString("school_name"), user.userId(), result.getObject("membership_id", UUID.class),
                        result.getBoolean("can_moderate")));
        if (scopes.isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "LEARNING_SCOPE_DENIED",
                    "Learning access is not available for the selected school.");
        }
        return scopes.getFirst();
    }

    private Definition definition(Scope scope, AssignmentRequest request) {
        if (!request.dueAt().isAfter(request.availableFrom())) {
            throw badRequest("ASSIGNMENT_DATES_INVALID", "The due time must be after the available time.");
        }
        if (!request.allowLate() && request.lateUntil() != null) {
            throw badRequest("ASSIGNMENT_LATE_RULE_INVALID", "Remove the late deadline or enable late submission.");
        }
        if (request.lateUntil() != null && !request.lateUntil().isAfter(request.dueAt())) {
            throw badRequest("ASSIGNMENT_LATE_RULE_INVALID", "The late deadline must be after the due time.");
        }
        String resourceLabel = clean(request.resourceLabel());
        String resourceUrl = clean(request.resourceUrl());
        if ((resourceLabel == null) != (resourceUrl == null)) {
            throw badRequest("ASSIGNMENT_RESOURCE_INCOMPLETE", "Provide both a resource label and its HTTPS link.");
        }
        if (resourceUrl != null) requireHttpsUrl(resourceUrl, "assignment resource");
        enumValue(request.submissionMode(), SUBMISSION_MODES, "submission mode");
        List<Definition> rows = jdbc.query("""
                SELECT term.id AS term_id, term.academic_session_id, term.starts_on AS term_start,
                       term.ends_on AS term_end, section.id AS section_id,
                       offering.id AS offering_id, campus.time_zone
                FROM academic_terms term
                JOIN class_sections section ON section.academic_session_id = term.academic_session_id
                    AND section.organisation_id = term.organisation_id AND section.school_id = term.school_id
                JOIN section_subject_offerings offering ON offering.class_section_id = section.id
                    AND offering.organisation_id = section.organisation_id AND offering.school_id = section.school_id
                JOIN campuses campus ON campus.id = section.campus_id
                LEFT JOIN assignment_categories category ON category.id = :categoryId
                    AND category.organisation_id = term.organisation_id AND category.school_id = term.school_id
                WHERE term.id = :termId AND offering.id = :offeringId
                  AND term.organisation_id = :organisationId AND term.school_id = :schoolId
                  AND term.status = 'ACTIVE' AND section.status = 'ACTIVE' AND offering.status = 'ACTIVE'
                  AND (CAST(:categoryId AS uuid) IS NULL OR (category.academic_session_id = term.academic_session_id
                      AND category.status = 'ACTIVE'))
                """, scope.parameters().addValue("termId", request.academicTermId())
                .addValue("offeringId", request.offeringId()).addValue("categoryId", request.categoryId()),
                (result, row) -> {
                    ZoneId zone = ZoneId.of(result.getString("time_zone"));
                    return new Definition(result.getObject("academic_session_id", UUID.class),
                            result.getObject("term_id", UUID.class), result.getObject("term_start", LocalDate.class),
                            result.getObject("term_end", LocalDate.class), result.getObject("section_id", UUID.class),
                            result.getObject("offering_id", UUID.class), zone,
                            request.availableFrom().atZone(zone).toLocalDate(), request.dueAt().atZone(zone).toLocalDate());
                });
        if (rows.isEmpty()) {
            throw badRequest("ASSIGNMENT_SCOPE_INVALID", "Choose an active term, section subject, and matching active category.");
        }
        Definition definition = rows.getFirst();
        if (definition.availableDate().isBefore(definition.termStart())
                || definition.dueDate().isAfter(definition.termEnd())) {
            throw badRequest("ASSIGNMENT_OUTSIDE_TERM", "Assignment availability and due date must fall inside the selected term.");
        }
        return definition;
    }

    private void authorizeOffering(Scope scope, UUID offeringId, LocalDate date) {
        if (scope.canModerate()) return;
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM teaching_allocations allocation
                WHERE allocation.section_subject_offering_id = :offeringId
                  AND allocation.organisation_id = :organisationId AND allocation.school_id = :schoolId
                  AND allocation.teacher_membership_id = :actorMembershipId AND allocation.status = 'ACTIVE'
                  AND allocation.starts_on <= :date
                  AND (allocation.ends_on IS NULL OR allocation.ends_on >= :date)
                """, scope.parameters().addValue("offeringId", offeringId).addValue("date", date), Integer.class);
        if (count == null || count == 0) {
            throw new ApiException(HttpStatus.FORBIDDEN, "LEARNING_ASSIGNMENT_DENIED",
                    "This work is outside your active teaching assignment.");
        }
    }

    private StoredAssignment assignmentForUpdate(Scope scope, UUID id, long expectedVersion) {
        List<StoredAssignment> rows = jdbc.query("""
                SELECT assignment.id, assignment.academic_term_id, assignment.class_section_id,
                       assignment.section_subject_offering_id, assignment.available_from,
                       assignment.status, assignment.publication_revision, assignment.row_version,
                       campus.time_zone
                FROM assignments assignment
                JOIN class_sections section ON section.id = assignment.class_section_id
                JOIN campuses campus ON campus.id = section.campus_id
                WHERE assignment.id = :id AND assignment.organisation_id = :organisationId
                  AND assignment.school_id = :schoolId FOR UPDATE
                """, scope.parameters().addValue("id", id), (result, row) -> {
                    Instant available = instant(result, "available_from");
                    return new StoredAssignment(result.getObject("id", UUID.class),
                            result.getObject("academic_term_id", UUID.class),
                            result.getObject("class_section_id", UUID.class),
                            result.getObject("section_subject_offering_id", UUID.class), available,
                            available.atZone(ZoneId.of(result.getString("time_zone"))).toLocalDate(),
                            result.getString("status"), result.getInt("publication_revision"),
                            result.getLong("row_version"));
                });
        if (rows.isEmpty()) throw notFound("ASSIGNMENT_NOT_FOUND", "The assignment was not found.");
        StoredAssignment assignment = rows.getFirst();
        if (assignment.rowVersion() != expectedVersion) {
            throw conflict("ASSIGNMENT_STALE", "Refresh the assignment before continuing.");
        }
        return assignment;
    }

    private StoredSubmission submissionForUpdate(Scope scope, UUID id, long expectedVersion) {
        List<StoredSubmission> rows = jdbc.query("""
                SELECT submission.id, submission.state, submission.response_text,
                       submission.response_link, submission.has_attachment_evidence,
                       submission.submitted_by_user_id,
                       submission.submitted_by_membership_id, submission.submitted_at,
                       submission.teacher_feedback, submission.points_awarded,
                       submission.reviewed_by_membership_id, submission.reviewed_at,
                       submission.row_version, assignment.section_subject_offering_id,
                       assignment.available_from, assignment.submission_mode,
                       assignment.maximum_points, campus.time_zone
                FROM learner_assignment_submissions submission
                JOIN assignments assignment ON assignment.id = submission.assignment_id
                JOIN class_sections section ON section.id = assignment.class_section_id
                JOIN campuses campus ON campus.id = section.campus_id
                WHERE submission.id = :id AND submission.organisation_id = :organisationId
                  AND submission.school_id = :schoolId FOR UPDATE OF submission
                """, scope.parameters().addValue("id", id), (result, row) -> {
                    Instant available = instant(result, "available_from");
                    SubmissionEvidence evidence = new SubmissionEvidence(
                            result.getString("state"), result.getString("response_text"),
                            result.getString("response_link"), result.getBoolean("has_attachment_evidence"),
                            result.getObject("submitted_by_user_id", UUID.class),
                            result.getObject("submitted_by_membership_id", UUID.class), instant(result, "submitted_at"),
                            result.getString("teacher_feedback"), result.getBigDecimal("points_awarded"),
                            result.getObject("reviewed_by_membership_id", UUID.class), instant(result, "reviewed_at"));
                    return new StoredSubmission(result.getObject("id", UUID.class),
                            result.getObject("section_subject_offering_id", UUID.class),
                            available.atZone(ZoneId.of(result.getString("time_zone"))).toLocalDate(),
                            result.getString("submission_mode"), result.getBigDecimal("maximum_points"),
                            evidence, result.getLong("row_version"));
                });
        if (rows.isEmpty()) throw notFound("SUBMISSION_NOT_FOUND", "The learner submission was not found.");
        StoredSubmission submission = rows.getFirst();
        if (submission.rowVersion() != expectedVersion) {
            throw conflict("SUBMISSION_STALE", "Refresh the learner submission before continuing.");
        }
        return submission;
    }

    private List<RosterLearner> roster(Scope scope, UUID sectionId, LocalDate date) {
        return jdbc.query("""
                SELECT placement.learner_id, placement.enrolment_id, learner.legal_name, placement.roll_number
                FROM student_section_placements placement
                JOIN student_enrolments enrolment ON enrolment.id = placement.enrolment_id
                JOIN learners learner ON learner.id = placement.learner_id
                WHERE placement.organisation_id = :organisationId AND placement.school_id = :schoolId
                  AND placement.class_section_id = :sectionId AND placement.starts_on <= :date
                  AND (placement.ends_on IS NULL OR placement.ends_on >= :date)
                  AND placement.status IN ('ACTIVE', 'ENDED')
                  AND enrolment.starts_on <= :date AND (enrolment.ends_on IS NULL OR enrolment.ends_on >= :date)
                  AND enrolment.status IN ('ACTIVE', 'ENDED') AND learner.status IN ('ACTIVE', 'ALUMNI')
                ORDER BY placement.roll_number NULLS LAST, learner.legal_name, learner.id
                """, scope.parameters().addValue("sectionId", sectionId).addValue("date", date),
                (result, row) -> new RosterLearner(result.getObject("learner_id", UUID.class),
                        result.getObject("enrolment_id", UUID.class), result.getString("legal_name"),
                        result.getString("roll_number")));
    }

    private List<SubmissionItem> submissions(Scope scope, UUID assignmentId) {
        List<SubmissionItemRow> rows = jdbc.query("""
                SELECT submission.id, submission.learner_id, recipient.learner_name_snapshot,
                       recipient.roll_number_snapshot, submission.state, submission.response_text,
                       submission.response_link, submission.submitted_at, submission.teacher_feedback,
                       submission.points_awarded, submission.reviewed_at, submission.row_version
                FROM learner_assignment_submissions submission
                JOIN assignment_recipients recipient ON recipient.id = submission.assignment_recipient_id
                WHERE submission.assignment_id = :assignmentId
                  AND submission.organisation_id = :organisationId AND submission.school_id = :schoolId
                ORDER BY recipient.roll_number_snapshot NULLS LAST, recipient.learner_name_snapshot, submission.id
                """, scope.parameters().addValue("assignmentId", assignmentId), (result, row) -> new SubmissionItemRow(
                result.getObject("id", UUID.class), result.getObject("learner_id", UUID.class),
                result.getString("learner_name_snapshot"), result.getString("roll_number_snapshot"),
                result.getString("state"), result.getString("response_text"), result.getString("response_link"),
                instant(result, "submitted_at"), result.getString("teacher_feedback"),
                result.getBigDecimal("points_awarded"), instant(result, "reviewed_at"),
                result.getLong("row_version")));
        Map<UUID, List<PrivateFileSummary>> submissionFiles = files.submissionFiles(
                scope.organisationId(), scope.schoolId(), rows.stream().map(SubmissionItemRow::id).toList());
        return rows.stream().map(row -> new SubmissionItem(
                row.id(), row.learnerId(), row.learnerName(), row.rollNumber(), row.state(),
                row.responseText(), row.responseLink(), row.submittedAt(), row.teacherFeedback(),
                row.pointsAwarded(), row.reviewedAt(), row.rowVersion(),
                submissionFiles.getOrDefault(row.id(), List.of()))).toList();
    }

    private Map<String, Integer> submissionCounts(Scope scope, UUID assignmentId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbc.query("""
                SELECT state, count(*) AS total FROM learner_assignment_submissions
                WHERE assignment_id = :assignmentId AND organisation_id = :organisationId AND school_id = :schoolId
                GROUP BY state ORDER BY state
                """, scope.parameters().addValue("assignmentId", assignmentId), result -> {
            counts.put(result.getString("state"), result.getInt("total"));
        });
        return counts;
    }

    private void revisionHistory(Scope scope, UUID assignmentId, int revision, String kind, String reason) {
        String snapshot = jdbc.queryForObject("""
                SELECT jsonb_build_object(
                    'title', assignment.title, 'instructions', assignment.instructions,
                    'resourceLabel', assignment.resource_label, 'resourceUrl', assignment.resource_url,
                    'availableFrom', assignment.available_from, 'dueAt', assignment.due_at,
                    'submissionMode', assignment.submission_mode, 'maximumPoints', assignment.maximum_points,
                    'allowAttachments', assignment.allow_attachments,
                    'maxAttachmentCount', assignment.max_attachment_count,
                    'attachmentRequired', assignment.attachment_required,
                    'allowLate', assignment.allow_late, 'lateUntil', assignment.late_until,
                    'categoryId', assignment.assignment_category_id,
                    'privateResources', COALESCE((
                        SELECT jsonb_agg(jsonb_build_object(
                            'fileId', file.id, 'filename', file.download_filename,
                            'mediaType', file.detected_media_type, 'sizeBytes', file.size_bytes,
                            'sha256', file.sha256_hex) ORDER BY file.available_at, file.id)
                        FROM private_files file
                        WHERE file.assignment_id = assignment.id AND file.lifecycle_status = 'AVAILABLE'
                    ), '[]'::jsonb),
                    'publicationRevision', assignment.publication_revision
                )::text
                FROM assignments assignment
                WHERE assignment.id = :assignmentId AND assignment.organisation_id = :organisationId
                  AND assignment.school_id = :schoolId
                """, scope.parameters().addValue("assignmentId", assignmentId), String.class);
        jdbc.update("""
                INSERT INTO assignment_revision_history (
                    id, organisation_id, school_id, assignment_id, publication_revision,
                    assignment_snapshot, change_kind, reason, actor_membership_id, occurred_at
                ) VALUES (:id, :organisationId, :schoolId, :assignmentId, :revision,
                    CAST(:snapshot AS jsonb), :kind, :reason, :actorMembershipId, :now)
                """, scope.parameters().addValue("id", UUID.randomUUID()).addValue("assignmentId", assignmentId)
                .addValue("revision", revision).addValue("snapshot", snapshot).addValue("kind", kind)
                .addValue("reason", reason).addValue("now", Instant.now(clock)));
        // The outbox is committed atomically with the immutable publication proof.
        // It contains identifiers only; no task text, learner names or credentials.
        for (String audience : List.of("SECTION_FAMILIES", "SECTION_STUDENTS")) {
            jdbc.update("""
                    INSERT INTO outbox_events(id,aggregate_type,aggregate_id,event_type,payload,headers,
                      occurred_at,available_at,attempt_count,created_at)
                    SELECT :eventId,'ASSIGNMENT',assignment.id::text,'learning.assignment.publication',
                      jsonb_build_object('organisationId',assignment.organisation_id,'schoolId',assignment.school_id,
                        'assignmentId',assignment.id,'historyId',history.id,'publicationRevision',history.publication_revision,
                        'audienceKind',:audience),'{}'::jsonb,history.occurred_at,
                      greatest(history.occurred_at,assignment.available_from),0,history.occurred_at
                    FROM assignments assignment JOIN assignment_revision_history history ON history.assignment_id=assignment.id
                      AND history.organisation_id=assignment.organisation_id AND history.school_id=assignment.school_id
                    WHERE assignment.id=:assignmentId AND assignment.organisation_id=:organisationId
                      AND assignment.school_id=:schoolId AND history.publication_revision=:revision
                    """, scope.parameters().addValue("assignmentId", assignmentId).addValue("revision", revision)
                    .addValue("eventId", UUID.randomUUID()).addValue("audience", audience));
        }
    }

    private void submissionHistory(
            Scope scope, UUID submissionId, SubmissionEvidence previous,
            SubmissionEvidence next, String kind, String reason) {
        jdbc.update("""
                INSERT INTO learner_submission_history (
                    id, organisation_id, school_id, learner_assignment_submission_id,
                    previous_evidence, new_evidence, change_kind, reason,
                    actor_user_id, actor_membership_id, occurred_at
                ) VALUES (:id, :organisationId, :schoolId, :submissionId,
                    CAST(:previous AS jsonb), CAST(:next AS jsonb), :kind, :reason,
                    :actorUserId, :actorMembershipId, :now)
                """, scope.parameters().addValue("id", UUID.randomUUID()).addValue("submissionId", submissionId)
                .addValue("previous", previous == null ? null : json(previous)).addValue("next", json(next))
                .addValue("kind", kind).addValue("reason", reason).addValue("now", Instant.now(clock)));
    }

    private MapSqlParameterSource categoryParameters(
            Scope scope, UUID id, CategoryRequest request, Long version) {
        return scope.parameters().addValue("id", id).addValue("sessionId", request.academicSessionId())
                .addValue("code", upper(request.code())).addValue("name", required(request.name(), "category name"))
                .addValue("color", clean(request.accentColor())).addValue("displayOrder", request.displayOrder())
                .addValue("status", enumValue(request.status(), ACTIVE_STATUSES, "category status"))
                .addValue("expectedVersion", version).addValue("now", Instant.now(clock));
    }

    private MapSqlParameterSource assignmentParameters(
            Scope scope, UUID id, AssignmentRequest request, Definition definition, Long version) {
        return scope.parameters().addValue("id", id).addValue("sessionId", definition.sessionId())
                .addValue("termId", definition.termId()).addValue("sectionId", definition.sectionId())
                .addValue("offeringId", definition.offeringId()).addValue("categoryId", request.categoryId())
                .addValue("title", required(request.title(), "assignment title"))
                .addValue("instructions", required(request.instructions(), "assignment instructions"))
                .addValue("resourceLabel", clean(request.resourceLabel()))
                .addValue("resourceUrl", clean(request.resourceUrl()))
                .addValue("availableFrom", request.availableFrom()).addValue("dueAt", request.dueAt())
                .addValue("submissionMode", enumValue(request.submissionMode(), SUBMISSION_MODES, "submission mode"))
                .addValue("maximumPoints", request.maximumPoints())
                .addValue("allowAttachments", validatedAllowAttachments(request))
                .addValue("maxAttachmentCount", request.maxAttachmentCount())
                .addValue("attachmentRequired", request.attachmentRequired())
                .addValue("allowLate", request.allowLate())
                .addValue("lateUntil", request.lateUntil()).addValue("expectedVersion", version)
                .addValue("now", Instant.now(clock));
    }

    private void requireSession(Scope scope, UUID sessionId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM academic_sessions WHERE id = :sessionId
                  AND organisation_id = :organisationId AND school_id = :schoolId
                """, scope.parameters().addValue("sessionId", sessionId), Integer.class);
        if (!Objects.equals(count, 1)) {
            throw badRequest("ACADEMIC_SESSION_NOT_FOUND", "Choose an academic session from this school.");
        }
    }

    private static void validatePoints(BigDecimal points, BigDecimal maximum) {
        if (points == null) return;
        if (maximum == null) {
            throw badRequest("ASSIGNMENT_NOT_SCORED", "This assignment does not use points.");
        }
        if (points.signum() < 0 || points.compareTo(maximum) > 0) {
            throw badRequest("ASSIGNMENT_POINTS_INVALID", "Awarded points must be between zero and the configured maximum.");
        }
    }

    private void validatePublishedAttachmentRevision(Scope scope, UUID assignmentId, AssignmentRequest request) {
        Integer largestCurrentAttachmentCount = jdbc.queryForObject("""
                SELECT COALESCE(max(attachment_count), 0) FROM (
                    SELECT count(file.id) AS attachment_count
                    FROM learner_assignment_submissions submission
                    LEFT JOIN private_files file ON file.learner_assignment_submission_id = submission.id
                        AND file.lifecycle_status = 'AVAILABLE'
                    WHERE submission.assignment_id = :assignmentId
                      AND submission.organisation_id = :organisationId AND submission.school_id = :schoolId
                    GROUP BY submission.id
                ) counts
                """, scope.parameters().addValue("assignmentId", assignmentId), Integer.class);
        int supportedCount = request.allowAttachments() ? request.maxAttachmentCount() : 0;
        if (largestCurrentAttachmentCount != null && largestCurrentAttachmentCount > supportedCount) {
            throw conflict("ASSIGNMENT_ATTACHMENT_POLICY_IN_USE",
                    "The revised attachment policy cannot invalidate existing learner evidence.");
        }
    }

    private static boolean validatedAllowAttachments(AssignmentRequest request) {
        String mode = upper(request.submissionMode());
        if ("OFFLINE".equals(mode) && request.allowAttachments()) {
            throw badRequest("ASSIGNMENT_ATTACHMENT_POLICY_INVALID",
                    "Offline hand-in assignments cannot also accept online attachments.");
        }
        if (!request.allowAttachments()
                && (request.maxAttachmentCount() != 0 || request.attachmentRequired())) {
            throw badRequest("ASSIGNMENT_ATTACHMENT_POLICY_INVALID",
                    "Enable attachments before setting an attachment limit or requirement.");
        }
        if (request.allowAttachments()
                && (request.maxAttachmentCount() < 1 || request.maxAttachmentCount() > 10)) {
            throw badRequest("ASSIGNMENT_ATTACHMENT_POLICY_INVALID",
                    "Choose an attachment limit between one and ten.");
        }
        if (Set.of("FILE", "FLEXIBLE").contains(mode) && !request.allowAttachments()) {
            throw badRequest("ASSIGNMENT_ATTACHMENT_POLICY_INVALID",
                    "File and flexible response modes require private attachments.");
        }
        return request.allowAttachments();
    }

    private void recordAudit(
            AuthenticatedUser user, Scope scope, String action, String type, UUID id, String reason) {
        audit.recordScoped(user, scope.actorMembershipId(), scope.schoolId(), action, type, id.toString(),
                AuditOutcome.SUCCEEDED, clean(reason), Map.of());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to encode assignment evidence", exception);
        }
    }

    private static AssignmentRow mapAssignmentRow(ResultSet result, int row) throws SQLException {
        return new AssignmentRow(
                result.getObject("id", UUID.class), result.getObject("academic_term_id", UUID.class),
                result.getObject("class_section_id", UUID.class),
                result.getObject("section_subject_offering_id", UUID.class),
                result.getObject("assignment_category_id", UUID.class), result.getString("title"),
                result.getString("instructions"), result.getString("subject_name"),
                result.getString("category_name"), result.getString("resource_label"),
                result.getString("resource_url"), instant(result, "available_from"),
                instant(result, "due_at"), result.getString("submission_mode"),
                result.getBigDecimal("maximum_points"), result.getBoolean("allow_attachments"),
                result.getInt("max_attachment_count"), result.getBoolean("attachment_required"),
                result.getBoolean("allow_late"),
                instant(result, "late_until"), result.getString("status"),
                result.getInt("publication_revision"), result.getInt("expected_learner_count"),
                instant(result, "published_at"), instant(result, "closed_at"), result.getLong("row_version"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        return result.getTimestamp(column) == null ? null : result.getTimestamp(column).toInstant();
    }

    private static void requireHttpsUrl(String value, String label) {
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw badRequest("INVALID_HTTPS_URL", "Use a complete HTTPS URL for the " + label + ".");
            }
        } catch (URISyntaxException exception) {
            throw badRequest("INVALID_HTTPS_URL", "Use a complete HTTPS URL for the " + label + ".");
        }
    }

    private static long requireVersion(Long version) {
        if (version == null) {
            throw badRequest("EXPECTED_VERSION_REQUIRED", "Refresh the record and include its current version before editing.");
        }
        return version;
    }

    private static String requiredReason(String value) {
        String result = clean(value);
        if (result == null) throw badRequest("CHANGE_REASON_REQUIRED", "Enter a reason for this change.");
        return result;
    }

    private static String required(String value, String label) {
        String result = clean(value);
        if (result == null) throw badRequest("REQUIRED_VALUE_MISSING", "Enter the " + label + ".");
        return result;
    }

    private static String enumValue(String value, Set<String> allowed, String label) {
        String result = upper(value);
        if (!allowed.contains(result)) throw badRequest("INVALID_VALUE", "Choose a supported " + label + ".");
        return result;
    }

    private static String upper(String value) {
        return required(value, "value").toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) return null;
        return value.replaceAll("[\\p{Cntrl}]", " ").strip();
    }

    private static void requireChanged(int changed, String code, String message) {
        if (changed != 1) throw conflict(code, message);
    }

    private static <T> T safely(String code, String message, Work<T> work) {
        try {
            return work.run();
        } catch (DataIntegrityViolationException exception) {
            throw conflict(code, message);
        }
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

    private record Scope(
            UUID organisationId, UUID schoolId, String schoolName, UUID actorUserId,
            UUID actorMembershipId, boolean canModerate) {
        MapSqlParameterSource parameters() {
            return new MapSqlParameterSource().addValue("organisationId", organisationId)
                    .addValue("schoolId", schoolId).addValue("actorUserId", actorUserId)
                    .addValue("actorMembershipId", actorMembershipId).addValue("canModerate", canModerate);
        }
    }

    private record Definition(
            UUID sessionId, UUID termId, LocalDate termStart, LocalDate termEnd,
            UUID sectionId, UUID offeringId, ZoneId zone, LocalDate availableDate, LocalDate dueDate) {}

    private record AssignmentRow(
            UUID id, UUID termId, UUID sectionId, UUID offeringId, UUID categoryId,
            String title, String instructions, String subjectName, String categoryName,
            String resourceLabel, String resourceUrl, Instant availableFrom, Instant dueAt,
            String submissionMode, BigDecimal maximumPoints,
            boolean allowAttachments, int maxAttachmentCount, boolean attachmentRequired,
            boolean allowLate, Instant lateUntil,
            String status, int publicationRevision, int expectedLearnerCount,
            Instant publishedAt, Instant closedAt, long rowVersion) {}

    private record StoredAssignment(
            UUID id, UUID termId, UUID sectionId, UUID offeringId, Instant availableFrom,
            LocalDate availableDate, String status, int publicationRevision, long rowVersion) {}

    private record StoredSubmission(
            UUID id, UUID offeringId, LocalDate availableDate, String submissionMode,
            BigDecimal maximumPoints, SubmissionEvidence evidence, long rowVersion) {}

    private record RosterLearner(UUID learnerId, UUID enrolmentId, String name, String rollNumber) {}

    private record SubmissionEvidence(
            String state, String responseText, String responseLink, boolean hasAttachmentEvidence,
            UUID submittedByUserId, UUID submittedByMembershipId, Instant submittedAt,
            String teacherFeedback, BigDecimal pointsAwarded,
            UUID reviewedByMembershipId, Instant reviewedAt) {
        static SubmissionEvidence empty() {
            return new SubmissionEvidence("NOT_STARTED", null, null, false,
                    null, null, null, null, null, null, null);
        }
    }

    private record SubmissionItemRow(
            UUID id, UUID learnerId, String learnerName, String rollNumber, String state,
            String responseText, String responseLink, Instant submittedAt,
            String teacherFeedback, BigDecimal pointsAwarded, Instant reviewedAt,
            long rowVersion) {}

    @FunctionalInterface
    private interface Work<T> {
        T run();
    }
}
