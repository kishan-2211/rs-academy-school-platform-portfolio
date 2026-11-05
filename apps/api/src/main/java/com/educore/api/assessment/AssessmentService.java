package com.educore.api.assessment;

import static com.educore.api.assessment.AssessmentController.*;
import static com.educore.api.assessment.AssessmentSnapshot.*;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
public class AssessmentService {

    private static final Set<String> ACTIVE_STATUSES = Set.of("ACTIVE", "INACTIVE");
    private static final Set<String> AGGREGATION_MODES = Set.of("WEIGHTED", "DESCRIPTIVE");
    private static final Set<String> ROUNDING_MODES = Set.of("HALF_UP", "HALF_EVEN", "DOWN");
    private static final Set<String> PASS_RULES = Set.of("NO_PASS_RULE", "OVERALL", "EACH_COMPONENT", "BOTH");
    private static final Set<String> SCORING_MODES = Set.of("MARKS", "GRADE", "NARRATIVE");
    private static final Set<String> EVIDENCE_STATES = Set.of("MISSING", "RECORDED", "ABSENT", "EXEMPT");
    private static final Set<String> BAND_OUTCOMES = Set.of("PASS", "FAIL", "NOT_APPLICABLE");

    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AssessmentService(
            NamedParameterJdbcTemplate jdbc,
            SecurityAuditService audit,
            ObjectMapper objectMapper,
            Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AssessmentSnapshot snapshot(AuthenticatedUser user, UUID requestedSchoolId) {
        Scope scope = requireScope(user, requestedSchoolId, "assessment.read");
        MapSqlParameterSource parameters = scope.parameters();
        String visibleOffering = """
                (:canConfigure OR :canPublish OR EXISTS (
                    SELECT 1 FROM teaching_allocations allocation
                    WHERE allocation.section_subject_offering_id = offering.id
                      AND allocation.organisation_id = offering.organisation_id
                      AND allocation.school_id = offering.school_id
                      AND allocation.teacher_membership_id = :actorMembershipId
                      AND allocation.status = 'ACTIVE'))
                """;

        String assessmentSql = """
                SELECT assessment.id, assessment.academic_term_id, assessment.class_section_id,
                       assessment.section_subject_offering_id, assessment.assessment_scheme_id,
                       assessment.assessment_component_id, assessment.assessment_name,
                       assessment.assessment_date, assessment.scoring_mode, assessment.maximum_marks,
                       subject.display_name AS subject_name, component.display_name AS component_name,
                       assessment.status, assessment.expected_learner_count,
                       (SELECT count(*) FROM assessment_marks mark
                        WHERE mark.assessment_id = assessment.id AND mark.evidence_state <> 'MISSING') AS recorded_count,
                       assessment.submitted_at, assessment.validated_at, assessment.published_at,
                       assessment.row_version
                FROM assessments assessment
                JOIN section_subject_offerings offering ON offering.id = assessment.section_subject_offering_id
                JOIN school_subjects subject ON subject.id = offering.subject_id
                JOIN assessment_components component ON component.id = assessment.assessment_component_id
                WHERE assessment.organisation_id = :organisationId AND assessment.school_id = :schoolId
                  AND %s
                ORDER BY assessment.assessment_date DESC, assessment.created_at DESC, assessment.id
                """.formatted(visibleOffering);
        List<AssessmentRow> rows = jdbc.query(assessmentSql, parameters, AssessmentService::mapAssessmentRow);

        List<AssessmentItem> assessmentItems = rows.stream().map(row -> new AssessmentItem(
                row.id(), row.termId(), row.sectionId(), row.offeringId(), row.schemeId(), row.componentId(),
                row.name(), row.date(), row.scoringMode(), row.maximumMarks(), row.subjectName(),
                row.componentName(), row.status(), row.expectedCount(), row.recordedCount(),
                row.submittedAt(), row.validatedAt(), row.publishedAt(), row.rowVersion(),
                marks(scope, row.id()))).toList();

        return new AssessmentSnapshot(
                scope.schoolId(), scope.schoolName(), scope.canConfigure(), scope.canPublish(),
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
                          AND (:canConfigure OR :canPublish OR (offering.id IS NOT NULL AND %s))
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
                        SELECT id, academic_session_id, scale_name, status, row_version
                        FROM assessment_grading_scales
                        WHERE organisation_id = :organisationId AND school_id = :schoolId
                        ORDER BY academic_session_id, scale_name, id
                        """, parameters, (result, row) -> new ScaleItem(
                        result.getObject("id", UUID.class), result.getObject("academic_session_id", UUID.class),
                        result.getString("scale_name"), result.getString("status"), result.getLong("row_version"))),
                jdbc.query("""
                        SELECT id, grading_scale_id, grade_code, display_name, minimum_percentage,
                               maximum_percentage, grade_point, outcome, descriptor, display_order, row_version
                        FROM assessment_grade_bands
                        WHERE organisation_id = :organisationId AND school_id = :schoolId
                        ORDER BY grading_scale_id, display_order, minimum_percentage DESC, id
                        """, parameters, (result, row) -> new BandItem(
                        result.getObject("id", UUID.class), result.getObject("grading_scale_id", UUID.class),
                        result.getString("grade_code"), result.getString("display_name"),
                        result.getBigDecimal("minimum_percentage"), result.getBigDecimal("maximum_percentage"),
                        result.getBigDecimal("grade_point"), result.getString("outcome"),
                        result.getString("descriptor"), result.getInt("display_order"), result.getLong("row_version"))),
                jdbc.query("""
                        SELECT id, academic_session_id, grading_scale_id, grade_code, scheme_name,
                               aggregation_mode, rounding_mode, decimal_places, pass_rule,
                               overall_pass_percentage, status, activated_at, row_version
                        FROM assessment_schemes
                        WHERE organisation_id = :organisationId AND school_id = :schoolId
                        ORDER BY academic_session_id, grade_code, scheme_name, id
                        """, parameters, (result, row) -> new SchemeItem(
                        result.getObject("id", UUID.class), result.getObject("academic_session_id", UUID.class),
                        result.getObject("grading_scale_id", UUID.class), result.getString("grade_code"),
                        result.getString("scheme_name"), result.getString("aggregation_mode"),
                        result.getString("rounding_mode"), result.getInt("decimal_places"),
                        result.getString("pass_rule"), result.getBigDecimal("overall_pass_percentage"),
                        result.getString("status"), instant(result, "activated_at"), result.getLong("row_version"))),
                jdbc.query("""
                        SELECT id, assessment_scheme_id, component_code, display_name, scoring_mode,
                               weight_percentage, default_max_marks, pass_percentage, display_order, status, row_version
                        FROM assessment_components
                        WHERE organisation_id = :organisationId AND school_id = :schoolId
                        ORDER BY assessment_scheme_id, display_order, component_code, id
                        """, parameters, (result, row) -> new ComponentItem(
                        result.getObject("id", UUID.class), result.getObject("assessment_scheme_id", UUID.class),
                        result.getString("component_code"), result.getString("display_name"),
                        result.getString("scoring_mode"), result.getBigDecimal("weight_percentage"),
                        result.getBigDecimal("default_max_marks"), result.getBigDecimal("pass_percentage"),
                        result.getInt("display_order"), result.getString("status"), result.getLong("row_version"))),
                assessmentItems,
                corrections(scope),
                publications(scope));
    }

    @Transactional
    public AssessmentMutationResponse createScale(AuthenticatedUser user, ScaleRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "assessment.configure");
        requireSession(scope, request.academicSessionId());
        UUID id = UUID.randomUUID();
        safely("GRADING_SCALE_EXISTS", "A grading scale with this name already exists for the session.", () -> jdbc.update("""
                INSERT INTO assessment_grading_scales (
                    id, organisation_id, school_id, academic_session_id, scale_name, status,
                    created_at, updated_at, row_version
                ) VALUES (:id, :organisationId, :schoolId, :sessionId, :name, :status, :now, :now, 0)
                """, scaleParameters(scope, id, request, null)));
        audit(user, scope, "assessment.grading-scale.created", "ASSESSMENT_GRADING_SCALE", id, request.reason());
        return new AssessmentMutationResponse(id, 0);
    }

    @Transactional
    public AssessmentMutationResponse updateScale(AuthenticatedUser user, UUID id, ScaleRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "assessment.configure");
        requireSession(scope, request.academicSessionId());
        long version = requireVersion(request.expectedVersion());
        int changed = safely("GRADING_SCALE_EXISTS", "A grading scale with this name already exists for the session.", () -> jdbc.update("""
                UPDATE assessment_grading_scales SET academic_session_id = :sessionId, scale_name = :name,
                    status = :status, updated_at = :now, row_version = row_version + 1
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                  AND row_version = :expectedVersion
                  AND NOT EXISTS (SELECT 1 FROM assessment_schemes scheme
                                  WHERE scheme.grading_scale_id = assessment_grading_scales.id
                                    AND scheme.status <> 'DRAFT')
                """, scaleParameters(scope, id, request, version)));
        requireChanged(changed, "GRADING_SCALE_STALE", "The grading scale changed, is unavailable, or is already used by an activated scheme.");
        audit(user, scope, "assessment.grading-scale.updated", "ASSESSMENT_GRADING_SCALE", id, requiredReason(request.reason()));
        return new AssessmentMutationResponse(id, version + 1);
    }

    @Transactional
    public AssessmentMutationResponse createBand(AuthenticatedUser user, BandRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "assessment.configure");
        validateBand(scope, request, null);
        UUID id = UUID.randomUUID();
        safely("GRADE_BAND_EXISTS", "This grade band overlaps or duplicates another band.", () -> jdbc.update("""
                INSERT INTO assessment_grade_bands (
                    id, organisation_id, school_id, grading_scale_id, grade_code, display_name,
                    minimum_percentage, maximum_percentage, grade_point, outcome, descriptor,
                    display_order, created_at, updated_at, row_version
                ) VALUES (:id, :organisationId, :schoolId, :scaleId, :code, :name, :minimum, :maximum,
                    :gradePoint, :outcome, :descriptor, :displayOrder, :now, :now, 0)
                """, bandParameters(scope, id, request, null)));
        audit(user, scope, "assessment.grade-band.created", "ASSESSMENT_GRADE_BAND", id, request.reason());
        return new AssessmentMutationResponse(id, 0);
    }

    @Transactional
    public AssessmentMutationResponse updateBand(AuthenticatedUser user, UUID id, BandRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "assessment.configure");
        validateBand(scope, request, id);
        long version = requireVersion(request.expectedVersion());
        int changed = safely("GRADE_BAND_EXISTS", "This grade band overlaps or duplicates another band.", () -> jdbc.update("""
                UPDATE assessment_grade_bands SET grading_scale_id = :scaleId, grade_code = :code,
                    display_name = :name, minimum_percentage = :minimum, maximum_percentage = :maximum,
                    grade_point = :gradePoint, outcome = :outcome, descriptor = :descriptor,
                    display_order = :displayOrder, updated_at = :now, row_version = row_version + 1
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                  AND row_version = :expectedVersion
                """, bandParameters(scope, id, request, version)));
        requireChanged(changed, "GRADE_BAND_STALE", "The grade band changed or is unavailable.");
        audit(user, scope, "assessment.grade-band.updated", "ASSESSMENT_GRADE_BAND", id, requiredReason(request.reason()));
        return new AssessmentMutationResponse(id, version + 1);
    }

    @Transactional
    public AssessmentMutationResponse createScheme(AuthenticatedUser user, SchemeRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "assessment.configure");
        validateScheme(scope, request);
        UUID id = UUID.randomUUID();
        safely("ASSESSMENT_SCHEME_EXISTS", "A scheme with this name already exists for the grade and session.", () -> jdbc.update("""
                INSERT INTO assessment_schemes (
                    id, organisation_id, school_id, academic_session_id, grading_scale_id, grade_code,
                    scheme_name, aggregation_mode, rounding_mode, decimal_places, pass_rule,
                    overall_pass_percentage, status, created_at, updated_at, row_version
                ) VALUES (:id, :organisationId, :schoolId, :sessionId, :scaleId, :gradeCode,
                    :name, :aggregationMode, :roundingMode, :decimalPlaces, :passRule,
                    :overallPass, 'DRAFT', :now, :now, 0)
                """, schemeParameters(scope, id, request, null)));
        audit(user, scope, "assessment.scheme.created", "ASSESSMENT_SCHEME", id, request.reason());
        return new AssessmentMutationResponse(id, 0);
    }

    @Transactional
    public AssessmentMutationResponse updateScheme(AuthenticatedUser user, UUID id, SchemeRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "assessment.configure");
        validateScheme(scope, request);
        long version = requireVersion(request.expectedVersion());
        int changed = safely("ASSESSMENT_SCHEME_EXISTS", "A scheme with this name already exists for the grade and session.", () -> jdbc.update("""
                UPDATE assessment_schemes SET academic_session_id = :sessionId, grading_scale_id = :scaleId,
                    grade_code = :gradeCode, scheme_name = :name, aggregation_mode = :aggregationMode,
                    rounding_mode = :roundingMode, decimal_places = :decimalPlaces, pass_rule = :passRule,
                    overall_pass_percentage = :overallPass, updated_at = :now, row_version = row_version + 1
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                  AND status = 'DRAFT' AND row_version = :expectedVersion
                """, schemeParameters(scope, id, request, version)));
        requireChanged(changed, "ASSESSMENT_SCHEME_STALE", "The draft scheme changed, was activated, or is unavailable.");
        audit(user, scope, "assessment.scheme.updated", "ASSESSMENT_SCHEME", id, requiredReason(request.reason()));
        return new AssessmentMutationResponse(id, version + 1);
    }

    @Transactional
    public AssessmentMutationResponse activateScheme(AuthenticatedUser user, UUID id, TransitionRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "assessment.configure");
        StoredScheme scheme = schemeForUpdate(scope, id, request.expectedVersion());
        if (!"DRAFT".equals(scheme.status())) throw conflict("SCHEME_NOT_DRAFT", "Only a draft scheme can be activated.");
        validateSchemeForActivation(scope, scheme);
        Instant now = Instant.now(clock);
        int changed = jdbc.update("""
                UPDATE assessment_schemes SET status = 'ACTIVE', activated_by_membership_id = :actorMembershipId,
                    activated_at = :now, updated_at = :now, row_version = row_version + 1
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                  AND status = 'DRAFT' AND row_version = :expectedVersion
                """, scope.parameters().addValue("id", id).addValue("now", now)
                .addValue("expectedVersion", request.expectedVersion()));
        requireChanged(changed, "SCHEME_STALE", "The scheme changed before it could be activated.");
        audit(user, scope, "assessment.scheme.activated", "ASSESSMENT_SCHEME", id, request.reason());
        return new AssessmentMutationResponse(id, request.expectedVersion() + 1);
    }

    @Transactional
    public AssessmentMutationResponse createComponent(AuthenticatedUser user, ComponentRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "assessment.configure");
        validateComponent(scope, request);
        UUID id = UUID.randomUUID();
        safely("ASSESSMENT_COMPONENT_EXISTS", "This component code already exists in the scheme.", () -> jdbc.update("""
                INSERT INTO assessment_components (
                    id, organisation_id, school_id, assessment_scheme_id, component_code, display_name,
                    scoring_mode, weight_percentage, default_max_marks, pass_percentage, display_order,
                    status, created_at, updated_at, row_version
                ) VALUES (:id, :organisationId, :schoolId, :schemeId, :code, :name, :scoringMode,
                    :weight, :defaultMax, :passPercentage, :displayOrder, :status, :now, :now, 0)
                """, componentParameters(scope, id, request, null)));
        audit(user, scope, "assessment.component.created", "ASSESSMENT_COMPONENT", id, request.reason());
        return new AssessmentMutationResponse(id, 0);
    }

    @Transactional
    public AssessmentMutationResponse updateComponent(AuthenticatedUser user, UUID id, ComponentRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "assessment.configure");
        validateComponent(scope, request);
        long version = requireVersion(request.expectedVersion());
        int changed = safely("ASSESSMENT_COMPONENT_EXISTS", "This component code already exists in the scheme.", () -> jdbc.update("""
                UPDATE assessment_components SET assessment_scheme_id = :schemeId, component_code = :code,
                    display_name = :name, scoring_mode = :scoringMode, weight_percentage = :weight,
                    default_max_marks = :defaultMax, pass_percentage = :passPercentage,
                    display_order = :displayOrder, status = :status, updated_at = :now,
                    row_version = row_version + 1
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                  AND row_version = :expectedVersion
                """, componentParameters(scope, id, request, version)));
        requireChanged(changed, "ASSESSMENT_COMPONENT_STALE", "The component changed, its scheme was activated, or it is unavailable.");
        audit(user, scope, "assessment.component.updated", "ASSESSMENT_COMPONENT", id, requiredReason(request.reason()));
        return new AssessmentMutationResponse(id, version + 1);
    }

    @Transactional
    public AssessmentMutationResponse createAssessment(AuthenticatedUser user, CreateAssessmentRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "assessment.record");
        AssessmentDefinition definition = assessmentDefinition(scope, request);
        authorizeOffering(scope, definition.offeringId(), request.assessmentDate());
        BigDecimal maximum = "MARKS".equals(definition.scoringMode())
                ? Objects.requireNonNullElse(request.maximumMarks(), definition.defaultMaxMarks()) : null;
        if ("MARKS".equals(definition.scoringMode()) && (maximum == null || maximum.signum() <= 0)) {
            throw badRequest("MAXIMUM_MARKS_REQUIRED", "Enter the maximum marks for this assessment.");
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        Map<String, Object> schemeSnapshot = new LinkedHashMap<>();
        schemeSnapshot.put("schemeId", definition.schemeId());
        schemeSnapshot.put("name", definition.schemeName());
        schemeSnapshot.put("aggregationMode", definition.aggregationMode());
        schemeSnapshot.put("roundingMode", definition.roundingMode());
        schemeSnapshot.put("decimalPlaces", definition.decimalPlaces());
        schemeSnapshot.put("passRule", definition.passRule());
        schemeSnapshot.put("overallPassPercentage", definition.overallPass());
        schemeSnapshot.put("gradingScaleId", definition.gradingScaleId());
        Map<String, Object> componentSnapshot = new LinkedHashMap<>();
        componentSnapshot.put("componentId", definition.componentId());
        componentSnapshot.put("code", definition.componentCode());
        componentSnapshot.put("name", definition.componentName());
        componentSnapshot.put("scoringMode", definition.scoringMode());
        componentSnapshot.put("weightPercentage", definition.weight());
        componentSnapshot.put("passPercentage", definition.passPercentage());
        Map<String, Object> subjectSnapshot = Map.of(
                "subjectId", definition.subjectId(),
                "code", definition.subjectCode(),
                "name", definition.subjectName());
        MapSqlParameterSource parameters = scope.parameters().addValue("id", id)
                .addValue("sessionId", definition.sessionId()).addValue("termId", request.academicTermId())
                .addValue("sectionId", definition.sectionId()).addValue("offeringId", definition.offeringId())
                .addValue("schemeId", definition.schemeId()).addValue("componentId", definition.componentId())
                .addValue("name", required(request.name(), "assessment name"))
                .addValue("assessmentDate", request.assessmentDate()).addValue("scoringMode", definition.scoringMode())
                .addValue("maximumMarks", maximum).addValue("schemeSnapshot", json(schemeSnapshot))
                .addValue("componentSnapshot", json(componentSnapshot)).addValue("subjectSnapshot", json(subjectSnapshot))
                .addValue("now", now);
        safely("ASSESSMENT_EXISTS", "An assessment with this name already exists for the subject and component.", () -> jdbc.update("""
                INSERT INTO assessments (
                    id, organisation_id, school_id, academic_session_id, academic_term_id,
                    class_section_id, section_subject_offering_id, assessment_scheme_id,
                    assessment_component_id, assessment_name, assessment_date, scoring_mode,
                    maximum_marks, scheme_snapshot, component_snapshot, subject_snapshot,
                    status, expected_learner_count, created_by_membership_id,
                    created_at, updated_at, row_version
                ) VALUES (:id, :organisationId, :schoolId, :sessionId, :termId, :sectionId,
                    :offeringId, :schemeId, :componentId, :name, :assessmentDate, :scoringMode,
                    :maximumMarks, CAST(:schemeSnapshot AS jsonb), CAST(:componentSnapshot AS jsonb),
                    CAST(:subjectSnapshot AS jsonb), 'DRAFT', 0, :actorMembershipId, :now, :now, 0)
                """, parameters));
        audit(user, scope, "assessment.assessment.created", "ASSESSMENT", id, null);
        return new AssessmentMutationResponse(id, 0);
    }

    @Transactional
    public AssessmentMutationResponse openAssessment(AuthenticatedUser user, UUID id, TransitionRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "assessment.record");
        StoredAssessment assessment = assessmentForUpdate(scope, id, request.expectedVersion());
        authorizeOffering(scope, assessment.offeringId(), assessment.date());
        if (!"DRAFT".equals(assessment.status())) throw conflict("ASSESSMENT_NOT_DRAFT", "Only a draft assessment can be opened.");
        List<RosterLearner> roster = roster(scope, assessment.sectionId(), assessment.date());
        if (roster.isEmpty()) throw conflict("ASSESSMENT_ROSTER_EMPTY", "No active learners are placed in this section on the assessment date.");
        Instant now = Instant.now(clock);
        for (RosterLearner learner : roster) {
            UUID markId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO assessment_marks (
                        id, organisation_id, school_id, assessment_id, learner_id, enrolment_id,
                        evidence_state, recorded_by_membership_id, created_at, updated_at, row_version
                    ) VALUES (:id, :organisationId, :schoolId, :assessmentId, :learnerId, :enrolmentId,
                        'MISSING', :actorMembershipId, :now, :now, 0)
                    """, scope.parameters().addValue("id", markId).addValue("assessmentId", id)
                    .addValue("learnerId", learner.learnerId()).addValue("enrolmentId", learner.enrolmentId())
                    .addValue("now", now));
            history(scope, markId, null, new Evidence("MISSING", null, null, null, null), "INITIAL", null);
        }
        int changed = jdbc.update("""
                UPDATE assessments SET status = 'OPEN', expected_learner_count = :count,
                    updated_at = :now, row_version = row_version + 1
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                  AND status = 'DRAFT' AND row_version = :expectedVersion
                """, scope.parameters().addValue("id", id).addValue("count", roster.size())
                .addValue("now", now).addValue("expectedVersion", request.expectedVersion()));
        requireChanged(changed, "ASSESSMENT_STALE", "The assessment changed before it could be opened.");
        audit(user, scope, "assessment.assessment.opened", "ASSESSMENT", id, request.reason());
        return new AssessmentMutationResponse(id, request.expectedVersion() + 1);
    }

    @Transactional
    public AssessmentMutationResponse recordMarks(AuthenticatedUser user, UUID id, RecordMarksRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "assessment.record");
        StoredAssessment assessment = assessmentForUpdate(scope, id, request.expectedAssessmentVersion());
        authorizeOffering(scope, assessment.offeringId(), assessment.date());
        if (!"OPEN".equals(assessment.status())) throw conflict("ASSESSMENT_NOT_OPEN", "Marks can only be entered while the assessment is open.");
        String reason = requiredReason(request.reason());
        for (MarkInput input : request.marks()) {
            StoredMark mark = storedMark(scope, id, input.learnerId());
            if (input.expectedVersion() == null || input.expectedVersion() != mark.rowVersion()) {
                throw conflict("ASSESSMENT_MARK_STALE", "Refresh the marks register before changing this learner.");
            }
            Evidence next = validateEvidence(assessment, input.evidenceState(), input.marksAwarded(),
                    input.gradeCode(), input.narrative(), input.teacherRemark(), true);
            Evidence previous = mark.evidence();
            if (!previous.equals(next)) {
                int changed = jdbc.update("""
                        UPDATE assessment_marks SET evidence_state = :state, marks_awarded = :marks,
                            grade_code = :grade, narrative = :narrative, teacher_remark = :remark,
                            recorded_by_membership_id = :actorMembershipId, updated_at = :now,
                            row_version = row_version + 1
                        WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                          AND row_version = :expectedVersion
                        """, evidenceParameters(scope, mark.id(), next, mark.rowVersion()));
                requireChanged(changed, "ASSESSMENT_MARK_STALE", "The learner mark changed before it could be saved.");
                history(scope, mark.id(), previous, next, "ENTRY", reason);
            }
        }
        long nextVersion = touchAssessment(scope, id, assessment.rowVersion());
        audit(user, scope, "assessment.marks.recorded", "ASSESSMENT", id, reason);
        return new AssessmentMutationResponse(id, nextVersion);
    }

    @Transactional
    public AssessmentMutationResponse submitAssessment(AuthenticatedUser user, UUID id, TransitionRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "assessment.record");
        StoredAssessment assessment = assessmentForUpdate(scope, id, request.expectedVersion());
        authorizeOffering(scope, assessment.offeringId(), assessment.date());
        if (!"OPEN".equals(assessment.status())) throw conflict("ASSESSMENT_NOT_OPEN", "Only an open assessment can be submitted.");
        Integer incomplete = jdbc.queryForObject("""
                SELECT count(*) FROM assessment_marks
                WHERE assessment_id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                  AND evidence_state = 'MISSING'
                """, scope.parameters().addValue("id", id), Integer.class);
        if (incomplete != null && incomplete > 0) throw conflict("ASSESSMENT_INCOMPLETE", "Record every learner before submitting the assessment.");
        Instant now = Instant.now(clock);
        int changed = jdbc.update("""
                UPDATE assessments SET status = 'SUBMITTED', submitted_by_membership_id = :actorMembershipId,
                    submitted_at = :now, updated_at = :now, row_version = row_version + 1
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                  AND status = 'OPEN' AND row_version = :expectedVersion
                """, scope.parameters().addValue("id", id).addValue("now", now)
                .addValue("expectedVersion", request.expectedVersion()));
        requireChanged(changed, "ASSESSMENT_STALE", "The assessment changed before it could be submitted.");
        audit(user, scope, "assessment.assessment.submitted", "ASSESSMENT", id, request.reason());
        return new AssessmentMutationResponse(id, request.expectedVersion() + 1);
    }

    @Transactional
    public AssessmentMutationResponse validateAssessment(AuthenticatedUser user, UUID id, TransitionRequest request) {
        return authorityTransition(user, id, request, "SUBMITTED", "VALIDATED", "validated");
    }

    @Transactional
    public AssessmentMutationResponse publishAssessment(AuthenticatedUser user, UUID id, TransitionRequest request) {
        return authorityTransition(user, id, request, "VALIDATED", "PUBLISHED", "published");
    }

    @Transactional
    public AssessmentMutationResponse requestCorrection(AuthenticatedUser user, CorrectionRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "assessment.record");
        MarkContext context = markContext(scope, request.markId());
        authorizeOffering(scope, context.offeringId(), context.date());
        if (Set.of("DRAFT", "OPEN", "ARCHIVED").contains(context.status())) {
            throw conflict("CORRECTION_NOT_AVAILABLE", "Use the open register directly; archived assessment evidence cannot be changed.");
        }
        Evidence requested = validateEvidence(context.assessment(), request.evidenceState(), request.marksAwarded(),
                request.gradeCode(), request.narrative(), request.teacherRemark(), false);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        safely("CORRECTION_ALREADY_PENDING", "A correction request is already pending for this learner mark.", () -> jdbc.update("""
                INSERT INTO assessment_correction_requests (
                    id, organisation_id, school_id, assessment_mark_id, requested_evidence,
                    request_reason, status, requested_by_membership_id, created_at, updated_at, row_version
                ) VALUES (:id, :organisationId, :schoolId, :markId, CAST(:evidence AS jsonb),
                    :reason, 'PENDING', :actorMembershipId, :now, :now, 0)
                """, scope.parameters().addValue("id", id).addValue("markId", request.markId())
                .addValue("evidence", json(requested)).addValue("reason", requiredReason(request.reason()))
                .addValue("now", now)));
        audit(user, scope, "assessment.correction.requested", "ASSESSMENT_CORRECTION", id, request.reason());
        return new AssessmentMutationResponse(id, 0);
    }

    @Transactional
    public AssessmentMutationResponse decideCorrection(AuthenticatedUser user, UUID id, DecisionRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "assessment.publish");
        List<StoredCorrection> rows = jdbc.query("""
                SELECT correction.assessment_mark_id, correction.requested_evidence::text,
                       correction.status, correction.row_version, mark.assessment_id,
                       mark.evidence_state, mark.marks_awarded, mark.grade_code, mark.narrative,
                       mark.teacher_remark, mark.row_version AS mark_version,
                       assessment.scoring_mode, assessment.maximum_marks, assessment.status AS assessment_status,
                       assessment.section_subject_offering_id, assessment.assessment_date,
                       assessment.id AS assessment_identity, assessment.class_section_id,
                       assessment.row_version AS assessment_version
                FROM assessment_correction_requests correction
                JOIN assessment_marks mark ON mark.id = correction.assessment_mark_id
                JOIN assessments assessment ON assessment.id = mark.assessment_id
                WHERE correction.id = :id AND correction.organisation_id = :organisationId
                  AND correction.school_id = :schoolId
                FOR UPDATE OF correction, mark
                """, scope.parameters().addValue("id", id), (result, row) -> new StoredCorrection(
                result.getObject("assessment_mark_id", UUID.class), evidence(result.getString("requested_evidence")),
                result.getString("status"), result.getLong("row_version"),
                result.getObject("assessment_id", UUID.class),
                new Evidence(result.getString("evidence_state"), result.getBigDecimal("marks_awarded"),
                        result.getString("grade_code"), result.getString("narrative"), result.getString("teacher_remark")),
                result.getLong("mark_version"),
                new StoredAssessment(result.getObject("assessment_identity", UUID.class),
                        result.getObject("class_section_id", UUID.class),
                        result.getObject("section_subject_offering_id", UUID.class),
                        result.getObject("assessment_date", LocalDate.class), result.getString("assessment_status"),
                        result.getString("scoring_mode"), result.getBigDecimal("maximum_marks"),
                        result.getLong("assessment_version"))));
        if (rows.isEmpty()) throw notFound("CORRECTION_NOT_FOUND", "The correction request was not found.");
        StoredCorrection correction = rows.getFirst();
        if (correction.rowVersion() != request.expectedVersion() || !"PENDING".equals(correction.status())) {
            throw conflict("CORRECTION_STALE", "The correction request has already changed.");
        }
        String decision = enumValue(request.decision(), Set.of("APPROVED", "REJECTED"), "decision");
        String reason = requiredReason(request.reason());
        if ("APPROVED".equals(decision)) {
            jdbc.queryForObject("SELECT set_config('app.assessment_correction', 'approved', true)", new MapSqlParameterSource(), String.class);
            int changed = jdbc.update("""
                    UPDATE assessment_marks SET evidence_state = :state, marks_awarded = :marks,
                        grade_code = :grade, narrative = :narrative, teacher_remark = :remark,
                        recorded_by_membership_id = :actorMembershipId, updated_at = :now,
                        row_version = row_version + 1
                    WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                      AND row_version = :expectedVersion
                    """, evidenceParameters(scope, correction.markId(), correction.requested(), correction.markVersion()));
            requireChanged(changed, "ASSESSMENT_MARK_STALE", "The learner mark changed before the correction could be applied.");
            history(scope, correction.markId(), correction.current(), correction.requested(), "CORRECTION", reason);
        }
        Instant now = Instant.now(clock);
        int changed = jdbc.update("""
                UPDATE assessment_correction_requests SET status = :decision,
                    decided_by_membership_id = :actorMembershipId, decision_reason = :reason,
                    decided_at = :now, updated_at = :now, row_version = row_version + 1
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                  AND status = 'PENDING' AND row_version = :expectedVersion
                """, scope.parameters().addValue("id", id).addValue("decision", decision)
                .addValue("reason", reason).addValue("now", now).addValue("expectedVersion", request.expectedVersion()));
        requireChanged(changed, "CORRECTION_STALE", "The correction request changed before the decision was saved.");
        audit(user, scope, "assessment.correction.decided", "ASSESSMENT_CORRECTION", id, reason);
        return new AssessmentMutationResponse(id, request.expectedVersion() + 1);
    }

    @Transactional
    public AssessmentMutationResponse publishReportCards(AuthenticatedUser user, PublicationRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "assessment.publish");
        PublicationContext context = publicationContext(scope, request);
        validatePublicationReadiness(scope, context);
        List<RosterLearner> learners = publicationRoster(scope, context);
        if (learners.isEmpty()) throw conflict("REPORT_CARD_ROSTER_EMPTY", "No learners have published assessment evidence for this result publication.");

        List<UUID> current = jdbc.query("""
                SELECT id FROM result_publications
                WHERE organisation_id = :organisationId AND school_id = :schoolId
                  AND class_section_id = :sectionId AND academic_term_id = :termId
                  AND status = 'PUBLISHED' FOR UPDATE
                """, scope.parameters().addValue("sectionId", context.sectionId()).addValue("termId", context.termId()),
                (result, row) -> result.getObject("id", UUID.class));
        UUID supersedesId = current.isEmpty() ? null : current.getFirst();
        Integer maximumVersion = jdbc.queryForObject("""
                SELECT COALESCE(max(publication_version), 0) FROM result_publications
                WHERE organisation_id = :organisationId AND school_id = :schoolId
                  AND class_section_id = :sectionId AND academic_term_id = :termId
                """, scope.parameters().addValue("sectionId", context.sectionId()).addValue("termId", context.termId()), Integer.class);
        int version = Objects.requireNonNullElse(maximumVersion, 0) + 1;
        Instant now = Instant.now(clock);
        if (supersedesId != null) {
            jdbc.update("""
                    UPDATE result_publications SET status = 'SUPERSEDED', superseded_at = :now
                    WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                      AND status = 'PUBLISHED'
                    """, scope.parameters().addValue("id", supersedesId).addValue("now", now));
        }
        UUID publicationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO result_publications (
                    id, organisation_id, school_id, academic_session_id, academic_term_id,
                    class_section_id, assessment_scheme_id, publication_version, publication_name,
                    status, supersedes_publication_id, published_by_membership_id, published_at, created_at
                ) VALUES (:id, :organisationId, :schoolId, :sessionId, :termId, :sectionId,
                    :schemeId, :version, :name, 'PUBLISHED', :supersedesId,
                    :actorMembershipId, :now, :now)
                """, scope.parameters().addValue("id", publicationId).addValue("sessionId", context.sessionId())
                .addValue("termId", context.termId()).addValue("sectionId", context.sectionId())
                .addValue("schemeId", context.schemeId()).addValue("version", version)
                .addValue("name", required(request.name(), "publication name"))
                .addValue("supersedesId", supersedesId).addValue("now", now));

        List<Band> bands = bands(scope, context.gradingScaleId());
        for (RosterLearner learner : learners) {
            ReportEvidence report = buildReport(scope, context, learner, bands, publicationId, version, now);
            jdbc.update("""
                    INSERT INTO learner_report_cards (
                        id, organisation_id, school_id, result_publication_id, learner_id, enrolment_id,
                        report_number, learner_name_snapshot, section_name_snapshot, term_name_snapshot,
                        report_snapshot, overall_percentage, overall_grade_code, overall_outcome,
                        snapshot_sha256, published_at
                    ) VALUES (:id, :organisationId, :schoolId, :publicationId, :learnerId, :enrolmentId,
                        :reportNumber, :learnerName, :sectionName, :termName, CAST(:snapshot AS jsonb),
                        :percentage, :grade, :outcome, :hash, :now)
                    """, scope.parameters().addValue("id", UUID.randomUUID()).addValue("publicationId", publicationId)
                    .addValue("learnerId", learner.learnerId()).addValue("enrolmentId", learner.enrolmentId())
                    .addValue("reportNumber", report.reportNumber()).addValue("learnerName", learner.name())
                    .addValue("sectionName", context.sectionName()).addValue("termName", context.termName())
                    .addValue("snapshot", report.snapshotJson()).addValue("percentage", report.percentage())
                    .addValue("grade", report.gradeCode()).addValue("outcome", report.outcome())
                    .addValue("hash", report.hash()).addValue("now", now));
        }
        audit(user, scope, "assessment.report-cards.published", "RESULT_PUBLICATION", publicationId, request.reason());
        return new AssessmentMutationResponse(publicationId, version);
    }

    @Transactional
    public AssessmentMutationResponse revokePublication(AuthenticatedUser user, UUID id, RevokePublicationRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "assessment.publish");
        String reason = requiredReason(request.reason());
        Instant now = Instant.now(clock);
        int changed = jdbc.update("""
                UPDATE result_publications SET status = 'REVOKED', revoked_by_membership_id = :actorMembershipId,
                    revoked_at = :now, revocation_reason = :reason
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                  AND status = 'PUBLISHED'
                """, scope.parameters().addValue("id", id).addValue("now", now).addValue("reason", reason));
        requireChanged(changed, "PUBLICATION_NOT_CURRENT", "Only the current published result version can be revoked.");
        audit(user, scope, "assessment.report-cards.revoked", "RESULT_PUBLICATION", id, reason);
        return new AssessmentMutationResponse(id, 0);
    }

    private AssessmentMutationResponse authorityTransition(
            AuthenticatedUser user, UUID id, TransitionRequest request,
            String expectedStatus, String nextStatus, String action) {
        Scope scope = requireScope(user, request.schoolId(), "assessment.publish");
        StoredAssessment assessment = assessmentForUpdate(scope, id, request.expectedVersion());
        if (!expectedStatus.equals(assessment.status())) {
            throw conflict("ASSESSMENT_STATUS_INVALID", "This assessment is not ready to be " + action + ".");
        }
        String actorColumn = "VALIDATED".equals(nextStatus) ? "validated_by_membership_id" : "published_by_membership_id";
        String timeColumn = "VALIDATED".equals(nextStatus) ? "validated_at" : "published_at";
        Instant now = Instant.now(clock);
        int changed = jdbc.update("UPDATE assessments SET status = :nextStatus, " + actorColumn
                        + " = :actorMembershipId, " + timeColumn + " = :now, updated_at = :now, row_version = row_version + 1 "
                        + "WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId "
                        + "AND status = :expectedStatus AND row_version = :expectedVersion",
                scope.parameters().addValue("id", id).addValue("nextStatus", nextStatus)
                        .addValue("expectedStatus", expectedStatus).addValue("expectedVersion", request.expectedVersion())
                        .addValue("now", now));
        requireChanged(changed, "ASSESSMENT_STALE", "The assessment changed before the decision was saved.");
        audit(user, scope, "assessment.assessment." + action, "ASSESSMENT", id, request.reason());
        return new AssessmentMutationResponse(id, request.expectedVersion() + 1);
    }

    private ReportEvidence buildReport(
            Scope scope, PublicationContext context, RosterLearner learner,
            List<Band> bands, UUID publicationId, int version, Instant publishedAt) {
        List<ReportRow> rows = jdbc.query("""
                SELECT subject.id AS subject_id, subject.code AS subject_code, subject.display_name AS subject_name,
                       component.id AS component_id, component.component_code, component.display_name AS component_name,
                       component.scoring_mode, component.weight_percentage, component.pass_percentage,
                       assessment.id AS assessment_id, assessment.assessment_name, assessment.assessment_date,
                       assessment.maximum_marks, mark.evidence_state, mark.marks_awarded,
                       mark.grade_code, mark.narrative, mark.teacher_remark
                FROM assessments assessment
                JOIN assessment_components component ON component.id = assessment.assessment_component_id
                JOIN section_subject_offerings offering ON offering.id = assessment.section_subject_offering_id
                JOIN school_subjects subject ON subject.id = offering.subject_id
                JOIN assessment_marks mark ON mark.assessment_id = assessment.id AND mark.learner_id = :learnerId
                WHERE assessment.organisation_id = :organisationId AND assessment.school_id = :schoolId
                  AND assessment.academic_term_id = :termId AND assessment.class_section_id = :sectionId
                  AND assessment.assessment_scheme_id = :schemeId AND assessment.status = 'PUBLISHED'
                ORDER BY subject.display_name, component.display_order, assessment.assessment_date, assessment.id
                """, scope.parameters().addValue("learnerId", learner.learnerId())
                .addValue("termId", context.termId()).addValue("sectionId", context.sectionId())
                .addValue("schemeId", context.schemeId()), AssessmentService::mapReportRow);

        Map<UUID, List<ReportRow>> bySubject = new LinkedHashMap<>();
        for (ReportRow row : rows) bySubject.computeIfAbsent(row.subjectId(), ignored -> new ArrayList<>()).add(row);
        List<Map<String, Object>> subjectSnapshots = new ArrayList<>();
        List<BigDecimal> subjectPercentages = new ArrayList<>();
        boolean allSubjectsPass = true;

        for (List<ReportRow> subjectRows : bySubject.values()) {
            ReportRow first = subjectRows.getFirst();
            Map<UUID, List<ReportRow>> byComponent = new LinkedHashMap<>();
            for (ReportRow row : subjectRows) byComponent.computeIfAbsent(row.componentId(), ignored -> new ArrayList<>()).add(row);
            List<Map<String, Object>> componentSnapshots = new ArrayList<>();
            BigDecimal weightedPoints = BigDecimal.ZERO;
            BigDecimal appliedWeight = BigDecimal.ZERO;
            boolean componentsPass = true;
            for (List<ReportRow> componentRows : byComponent.values()) {
                ReportRow component = componentRows.getFirst();
                BigDecimal awarded = BigDecimal.ZERO;
                BigDecimal maximum = BigDecimal.ZERO;
                boolean exemptOnly = true;
                boolean absent = false;
                List<Map<String, Object>> evidence = new ArrayList<>();
                for (ReportRow row : componentRows) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("assessmentId", row.assessmentId());
                    item.put("name", row.assessmentName());
                    item.put("date", row.assessmentDate());
                    item.put("state", row.evidenceState());
                    item.put("marksAwarded", row.marksAwarded());
                    item.put("maximumMarks", row.maximumMarks());
                    item.put("gradeCode", row.gradeCode());
                    item.put("narrative", row.narrative());
                    item.put("teacherRemark", row.teacherRemark());
                    evidence.add(item);
                    if (!"EXEMPT".equals(row.evidenceState())) exemptOnly = false;
                    if ("ABSENT".equals(row.evidenceState())) absent = true;
                    if ("MARKS".equals(row.scoringMode()) && !"EXEMPT".equals(row.evidenceState())) {
                        maximum = maximum.add(row.maximumMarks());
                        if (row.marksAwarded() != null) awarded = awarded.add(row.marksAwarded());
                    }
                }
                BigDecimal percentage = maximum.signum() == 0 ? null : awarded.multiply(BigDecimal.valueOf(100))
                        .divide(maximum, context.decimalPlaces(), roundingMode(context.roundingMode()));
                if (percentage != null) {
                    appliedWeight = appliedWeight.add(component.weight());
                    weightedPoints = weightedPoints.add(percentage.multiply(component.weight())
                            .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP));
                    if (component.passPercentage() != null && percentage.compareTo(component.passPercentage()) < 0) {
                        componentsPass = false;
                    }
                }
                Map<String, Object> componentSnapshot = new LinkedHashMap<>();
                componentSnapshot.put("componentId", component.componentId());
                componentSnapshot.put("code", component.componentCode());
                componentSnapshot.put("name", component.componentName());
                componentSnapshot.put("scoringMode", component.scoringMode());
                componentSnapshot.put("weightPercentage", component.weight());
                componentSnapshot.put("percentage", percentage);
                componentSnapshot.put("absent", absent);
                componentSnapshot.put("exempt", exemptOnly);
                componentSnapshot.put("evidence", evidence);
                componentSnapshots.add(componentSnapshot);
            }
            BigDecimal percentage = appliedWeight.signum() == 0 ? null : weightedPoints
                    .multiply(BigDecimal.valueOf(100))
                    .divide(appliedWeight, context.decimalPlaces(), roundingMode(context.roundingMode()));
            boolean overallPass = context.overallPass() == null || percentage == null
                    || percentage.compareTo(context.overallPass()) >= 0;
            boolean subjectPass = switch (context.passRule()) {
                case "OVERALL" -> overallPass;
                case "EACH_COMPONENT" -> componentsPass;
                case "BOTH" -> overallPass && componentsPass;
                default -> true;
            };
            Band band = bandFor(bands, percentage);
            if (percentage != null) subjectPercentages.add(percentage);
            allSubjectsPass = allSubjectsPass && subjectPass;
            Map<String, Object> subjectSnapshot = new LinkedHashMap<>();
            subjectSnapshot.put("subjectId", first.subjectId());
            subjectSnapshot.put("code", first.subjectCode());
            subjectSnapshot.put("name", first.subjectName());
            subjectSnapshot.put("percentage", percentage);
            subjectSnapshot.put("gradeCode", band == null ? null : band.gradeCode());
            subjectSnapshot.put("outcome", subjectPass ? "PASS" : "REVIEW_REQUIRED");
            subjectSnapshot.put("components", componentSnapshots);
            subjectSnapshots.add(subjectSnapshot);
        }

        BigDecimal overall = subjectPercentages.isEmpty() ? null : subjectPercentages.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(subjectPercentages.size()), context.decimalPlaces(), roundingMode(context.roundingMode()));
        Band overallBand = bandFor(bands, overall);
        String outcome = "NO_PASS_RULE".equals(context.passRule()) ? "NOT_APPLICABLE"
                : (allSubjectsPass ? "PASS" : "REVIEW_REQUIRED");
        String reportNumber = "RC-" + publicationId.toString().substring(0, 8).toUpperCase(Locale.ROOT)
                + "-" + learner.learnerId().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", 1);
        snapshot.put("reportNumber", reportNumber);
        snapshot.put("publicationId", publicationId);
        snapshot.put("publicationVersion", version);
        snapshot.put("publishedAt", publishedAt);
        snapshot.put("school", Map.of("id", scope.schoolId(), "name", scope.schoolName()));
        snapshot.put("learner", Map.of("id", learner.learnerId(), "name", learner.name(),
                "rollNumber", Objects.requireNonNullElse(learner.rollNumber(), "")));
        snapshot.put("academic", Map.of("session", context.sessionName(), "term", context.termName(),
                "section", context.sectionName(), "gradeCode", context.gradeCode()));
        snapshot.put("scheme", Map.of("id", context.schemeId(), "name", context.schemeName(),
                "aggregationMode", context.aggregationMode(), "roundingMode", context.roundingMode(),
                "decimalPlaces", context.decimalPlaces(), "passRule", context.passRule()));
        snapshot.put("subjects", subjectSnapshots);
        snapshot.put("overallPercentage", overall);
        snapshot.put("overallGradeCode", overallBand == null ? null : overallBand.gradeCode());
        snapshot.put("overallOutcome", outcome);
        String snapshotJson = json(snapshot);
        return new ReportEvidence(reportNumber, snapshotJson, overall,
                overallBand == null ? null : overallBand.gradeCode(), outcome, sha256(snapshotJson));
    }

    private void validatePublicationReadiness(Scope scope, PublicationContext context) {
        Integer missingDefinitions = jdbc.queryForObject("""
                SELECT count(*) FROM section_subject_offerings offering
                CROSS JOIN assessment_components component
                WHERE offering.organisation_id = :organisationId AND offering.school_id = :schoolId
                  AND offering.class_section_id = :sectionId AND offering.status = 'ACTIVE'
                  AND component.assessment_scheme_id = :schemeId AND component.status = 'ACTIVE'
                  AND NOT EXISTS (
                    SELECT 1 FROM assessments assessment
                    WHERE assessment.section_subject_offering_id = offering.id
                      AND assessment.assessment_component_id = component.id
                      AND assessment.academic_term_id = :termId
                      AND assessment.status = 'PUBLISHED')
                """, scope.parameters().addValue("sectionId", context.sectionId())
                .addValue("schemeId", context.schemeId()).addValue("termId", context.termId()), Integer.class);
        if (missingDefinitions != null && missingDefinitions > 0) {
            throw conflict("RESULTS_INCOMPLETE", "Publish at least one assessment for every active subject and scheme component before issuing report cards.");
        }
        Integer unresolved = jdbc.queryForObject("""
                SELECT count(*) FROM assessment_marks mark
                JOIN assessments assessment ON assessment.id = mark.assessment_id
                WHERE assessment.organisation_id = :organisationId AND assessment.school_id = :schoolId
                  AND assessment.class_section_id = :sectionId AND assessment.academic_term_id = :termId
                  AND assessment.assessment_scheme_id = :schemeId AND assessment.status = 'PUBLISHED'
                  AND mark.evidence_state = 'MISSING'
                """, scope.parameters().addValue("sectionId", context.sectionId())
                .addValue("schemeId", context.schemeId()).addValue("termId", context.termId()), Integer.class);
        if (unresolved != null && unresolved > 0) throw conflict("RESULTS_HAVE_MISSING_MARKS", "Resolve all missing learner evidence before issuing report cards.");
    }

    private PublicationContext publicationContext(Scope scope, PublicationRequest request) {
        List<PublicationContext> contexts = jdbc.query("""
                SELECT session.id AS session_id, session.name AS session_name,
                       term.id AS term_id, term.display_name AS term_name, term.ends_on AS term_end,
                       section.id AS section_id, section.display_name AS section_name, section.grade_code,
                       scheme.id AS scheme_id, scheme.scheme_name, scheme.aggregation_mode,
                       scheme.rounding_mode, scheme.decimal_places, scheme.pass_rule,
                       scheme.overall_pass_percentage, scheme.grading_scale_id
                FROM academic_terms term
                JOIN academic_sessions session ON session.id = term.academic_session_id
                JOIN class_sections section ON section.academic_session_id = session.id
                JOIN assessment_schemes scheme ON scheme.academic_session_id = session.id
                  AND scheme.grade_code = section.grade_code
                WHERE term.id = :termId AND section.id = :sectionId AND scheme.id = :schemeId
                  AND term.organisation_id = :organisationId AND term.school_id = :schoolId
                  AND section.organisation_id = :organisationId AND section.school_id = :schoolId
                  AND scheme.organisation_id = :organisationId AND scheme.school_id = :schoolId
                  AND term.status = 'ACTIVE' AND section.status = 'ACTIVE' AND scheme.status = 'ACTIVE'
                """, scope.parameters().addValue("termId", request.academicTermId())
                .addValue("sectionId", request.sectionId()).addValue("schemeId", request.schemeId()),
                (result, row) -> new PublicationContext(
                        result.getObject("session_id", UUID.class), result.getString("session_name"),
                        result.getObject("term_id", UUID.class), result.getString("term_name"),
                        result.getObject("term_end", LocalDate.class), result.getObject("section_id", UUID.class),
                        result.getString("section_name"), result.getString("grade_code"),
                        result.getObject("scheme_id", UUID.class), result.getString("scheme_name"),
                        result.getString("aggregation_mode"), result.getString("rounding_mode"),
                        result.getInt("decimal_places"), result.getString("pass_rule"),
                        result.getBigDecimal("overall_pass_percentage"), result.getObject("grading_scale_id", UUID.class)));
        if (contexts.isEmpty()) throw badRequest("PUBLICATION_SCOPE_INVALID", "Choose a matching active term, section, and assessment scheme.");
        return contexts.getFirst();
    }

    private void validateBand(Scope scope, BandRequest request, UUID bandId) {
        if (request.maximumPercentage().compareTo(request.minimumPercentage()) < 0) {
            throw badRequest("GRADE_BAND_RANGE_INVALID", "The maximum percentage must be at least the minimum percentage.");
        }
        Integer scaleCount = jdbc.queryForObject("""
                SELECT count(*) FROM assessment_grading_scales scale
                WHERE scale.id = :scaleId AND scale.organisation_id = :organisationId
                  AND scale.school_id = :schoolId AND scale.status = 'ACTIVE'
                  AND NOT EXISTS (SELECT 1 FROM assessment_schemes scheme
                                  WHERE scheme.grading_scale_id = scale.id AND scheme.status <> 'DRAFT')
                """, scope.parameters().addValue("scaleId", request.gradingScaleId()), Integer.class);
        if (!Objects.equals(scaleCount, 1)) throw conflict("GRADING_SCALE_LOCKED", "Choose an active grading scale that is not used by an activated scheme.");
        Integer overlaps = jdbc.queryForObject("""
                SELECT count(*) FROM assessment_grade_bands band
                WHERE band.grading_scale_id = :scaleId AND band.organisation_id = :organisationId
                  AND band.school_id = :schoolId AND (CAST(:bandId AS uuid) IS NULL OR band.id <> :bandId)
                  AND band.minimum_percentage <= :maximum AND band.maximum_percentage >= :minimum
                """, scope.parameters().addValue("scaleId", request.gradingScaleId()).addValue("bandId", bandId)
                .addValue("minimum", request.minimumPercentage()).addValue("maximum", request.maximumPercentage()), Integer.class);
        if (overlaps != null && overlaps > 0) throw conflict("GRADE_BANDS_OVERLAP", "Grade percentage bands cannot overlap.");
        enumValue(request.outcome(), BAND_OUTCOMES, "grade outcome");
    }

    private void validateScheme(Scope scope, SchemeRequest request) {
        requireSession(scope, request.academicSessionId());
        String aggregation = enumValue(request.aggregationMode(), AGGREGATION_MODES, "aggregation mode");
        enumValue(request.roundingMode(), ROUNDING_MODES, "rounding mode");
        String passRule = enumValue(request.passRule(), PASS_RULES, "pass rule");
        if ((Set.of("OVERALL", "BOTH").contains(passRule)) != (request.overallPassPercentage() != null)) {
            throw badRequest("PASS_RULE_CONFIGURATION_INVALID", "Overall and combined pass rules require an overall pass percentage; other rules must leave it empty.");
        }
        if ("WEIGHTED".equals(aggregation) && request.gradingScaleId() == null) {
            throw badRequest("GRADING_SCALE_REQUIRED", "Weighted schemes require a grading scale.");
        }
        if (request.gradingScaleId() != null) {
            Integer count = jdbc.queryForObject("""
                    SELECT count(*) FROM assessment_grading_scales
                    WHERE id = :scaleId AND organisation_id = :organisationId AND school_id = :schoolId
                      AND academic_session_id = :sessionId AND status = 'ACTIVE'
                    """, scope.parameters().addValue("scaleId", request.gradingScaleId())
                    .addValue("sessionId", request.academicSessionId()), Integer.class);
            if (!Objects.equals(count, 1)) throw badRequest("GRADING_SCALE_SCOPE_INVALID", "Choose an active grading scale from the same academic session.");
        }
    }

    private void validateSchemeForActivation(Scope scope, StoredScheme scheme) {
        List<ComponentDefinition> components = jdbc.query("""
                SELECT scoring_mode, weight_percentage, pass_percentage
                FROM assessment_components
                WHERE assessment_scheme_id = :schemeId AND organisation_id = :organisationId
                  AND school_id = :schoolId AND status = 'ACTIVE'
                """, scope.parameters().addValue("schemeId", scheme.id()), (result, row) -> new ComponentDefinition(
                result.getString("scoring_mode"), result.getBigDecimal("weight_percentage"), result.getBigDecimal("pass_percentage")));
        if (components.isEmpty()) throw conflict("SCHEME_HAS_NO_COMPONENTS", "Add at least one active assessment component before activation.");
        BigDecimal marksWeight = components.stream().filter(component -> "MARKS".equals(component.scoringMode()))
                .map(ComponentDefinition::weight).reduce(BigDecimal.ZERO, BigDecimal::add);
        if ("WEIGHTED".equals(scheme.aggregationMode()) && marksWeight.compareTo(BigDecimal.valueOf(100)) != 0) {
            throw conflict("SCHEME_WEIGHT_INVALID", "Active marks components in a weighted scheme must total exactly 100 percent.");
        }
        if ("DESCRIPTIVE".equals(scheme.aggregationMode()) && marksWeight.signum() != 0) {
            throw conflict("DESCRIPTIVE_SCHEME_HAS_WEIGHT", "Descriptive scheme components cannot carry numeric weight.");
        }
        if (Set.of("EACH_COMPONENT", "BOTH").contains(scheme.passRule())
                && components.stream().filter(component -> "MARKS".equals(component.scoringMode()))
                        .anyMatch(component -> component.passPercentage() == null)) {
            throw conflict("COMPONENT_PASS_RULE_INCOMPLETE", "Every active marks component needs a pass percentage for this pass rule.");
        }
        if (scheme.gradingScaleId() != null) {
            List<Band> bands = bands(scope, scheme.gradingScaleId());
            if (bands.isEmpty()) throw conflict("GRADING_SCALE_EMPTY", "Add grade bands before activating this scheme.");
            BigDecimal cursor = BigDecimal.ZERO;
            List<Band> ascending = bands.stream().sorted(Comparator.comparing(Band::minimum)).toList();
            for (Band band : ascending) {
                if (band.minimum().compareTo(cursor) > 0) throw conflict("GRADING_SCALE_GAP", "Grade bands must cover the complete 0 to 100 range without gaps.");
                cursor = band.maximum().add(new BigDecimal("0.01"));
            }
            if (cursor.compareTo(new BigDecimal("100.01")) < 0) throw conflict("GRADING_SCALE_GAP", "Grade bands must cover the complete 0 to 100 range.");
        }
    }

    private void validateComponent(Scope scope, ComponentRequest request) {
        String scoring = enumValue(request.scoringMode(), SCORING_MODES, "scoring mode");
        enumValue(request.status(), ACTIVE_STATUSES, "component status");
        Integer drafts = jdbc.queryForObject("""
                SELECT count(*) FROM assessment_schemes
                WHERE id = :schemeId AND organisation_id = :organisationId AND school_id = :schoolId
                  AND status = 'DRAFT'
                """, scope.parameters().addValue("schemeId", request.schemeId()), Integer.class);
        if (!Objects.equals(drafts, 1)) throw conflict("SCHEME_NOT_DRAFT", "Components can only be changed while the scheme is a draft.");
        if ("MARKS".equals(scoring) && request.defaultMaxMarks() == null) {
            throw badRequest("DEFAULT_MAX_MARKS_REQUIRED", "Marks components require default maximum marks.");
        }
        if (!"MARKS".equals(scoring) && (request.defaultMaxMarks() != null || request.weightPercentage().signum() != 0
                || request.passPercentage() != null)) {
            throw badRequest("DESCRIPTIVE_COMPONENT_NUMERIC_RULES", "Grade and narrative components cannot carry marks, weight, or pass percentages.");
        }
    }

    private AssessmentDefinition assessmentDefinition(Scope scope, CreateAssessmentRequest request) {
        List<AssessmentDefinition> definitions = jdbc.query("""
                SELECT section.academic_session_id AS session_id, term.id AS term_id,
                       term.starts_on AS term_start, term.ends_on AS term_end,
                       section.id AS section_id, section.grade_code, offering.id AS offering_id,
                       subject.id AS subject_id, subject.code AS subject_code, subject.display_name AS subject_name,
                       scheme.id AS scheme_id, scheme.scheme_name, scheme.aggregation_mode,
                       scheme.rounding_mode, scheme.decimal_places, scheme.pass_rule,
                       scheme.overall_pass_percentage, scheme.grading_scale_id,
                       component.id AS component_id, component.component_code,
                       component.display_name AS component_name, component.scoring_mode,
                       component.weight_percentage, component.default_max_marks, component.pass_percentage
                FROM academic_terms term
                JOIN section_subject_offerings offering ON offering.id = :offeringId
                JOIN class_sections section ON section.id = offering.class_section_id
                JOIN school_subjects subject ON subject.id = offering.subject_id
                JOIN assessment_components component ON component.id = :componentId
                JOIN assessment_schemes scheme ON scheme.id = component.assessment_scheme_id
                WHERE term.id = :termId AND term.academic_session_id = section.academic_session_id
                  AND scheme.academic_session_id = section.academic_session_id
                  AND scheme.grade_code = section.grade_code
                  AND term.organisation_id = :organisationId AND term.school_id = :schoolId
                  AND offering.organisation_id = :organisationId AND offering.school_id = :schoolId
                  AND component.organisation_id = :organisationId AND component.school_id = :schoolId
                  AND term.status = 'ACTIVE' AND section.status = 'ACTIVE' AND offering.status = 'ACTIVE'
                  AND subject.status = 'ACTIVE' AND scheme.status = 'ACTIVE' AND component.status = 'ACTIVE'
                """, scope.parameters().addValue("termId", request.academicTermId())
                .addValue("offeringId", request.offeringId()).addValue("componentId", request.componentId()),
                (result, row) -> new AssessmentDefinition(
                        result.getObject("session_id", UUID.class), result.getObject("term_id", UUID.class),
                        result.getObject("term_start", LocalDate.class), result.getObject("term_end", LocalDate.class),
                        result.getObject("section_id", UUID.class), result.getString("grade_code"),
                        result.getObject("offering_id", UUID.class), result.getObject("subject_id", UUID.class),
                        result.getString("subject_code"), result.getString("subject_name"),
                        result.getObject("scheme_id", UUID.class), result.getString("scheme_name"),
                        result.getString("aggregation_mode"), result.getString("rounding_mode"),
                        result.getInt("decimal_places"), result.getString("pass_rule"),
                        result.getBigDecimal("overall_pass_percentage"), result.getObject("grading_scale_id", UUID.class),
                        result.getObject("component_id", UUID.class), result.getString("component_code"),
                        result.getString("component_name"), result.getString("scoring_mode"),
                        result.getBigDecimal("weight_percentage"), result.getBigDecimal("default_max_marks"),
                        result.getBigDecimal("pass_percentage")));
        if (definitions.isEmpty()) throw badRequest("ASSESSMENT_SCOPE_INVALID", "Choose a matching active term, class subject, and scheme component.");
        AssessmentDefinition definition = definitions.getFirst();
        if (request.assessmentDate().isBefore(definition.termStart()) || request.assessmentDate().isAfter(definition.termEnd())) {
            throw badRequest("ASSESSMENT_DATE_OUTSIDE_TERM", "The assessment date must remain inside the selected term.");
        }
        return definition;
    }

    private List<MarkItem> marks(Scope scope, UUID assessmentId) {
        return jdbc.query("""
                SELECT mark.id, mark.learner_id, learner.legal_name,
                       placement.roll_number, mark.evidence_state, mark.marks_awarded,
                       mark.grade_code, mark.narrative, mark.teacher_remark, mark.row_version
                FROM assessment_marks mark
                JOIN learners learner ON learner.id = mark.learner_id
                LEFT JOIN student_section_placements placement
                  ON placement.enrolment_id = mark.enrolment_id AND placement.class_section_id = (
                    SELECT class_section_id FROM assessments WHERE id = mark.assessment_id)
                  AND placement.starts_on <= (SELECT assessment_date FROM assessments WHERE id = mark.assessment_id)
                  AND (placement.ends_on IS NULL OR placement.ends_on >= (SELECT assessment_date FROM assessments WHERE id = mark.assessment_id))
                WHERE mark.assessment_id = :assessmentId AND mark.organisation_id = :organisationId
                  AND mark.school_id = :schoolId
                ORDER BY placement.roll_number NULLS LAST, learner.legal_name, mark.id
                """, scope.parameters().addValue("assessmentId", assessmentId), (result, row) -> new MarkItem(
                result.getObject("id", UUID.class), result.getObject("learner_id", UUID.class),
                result.getString("legal_name"), result.getString("roll_number"), result.getString("evidence_state"),
                result.getBigDecimal("marks_awarded"), result.getString("grade_code"), result.getString("narrative"),
                result.getString("teacher_remark"), result.getLong("row_version")));
    }

    private List<CorrectionItem> corrections(Scope scope) {
        return jdbc.query("""
                SELECT correction.id, mark.id AS mark_id, assessment.id AS assessment_id,
                       learner.legal_name, assessment.assessment_name,
                       correction.requested_evidence->>'state' AS requested_state,
                       NULLIF(correction.requested_evidence->>'marksAwarded','')::numeric AS requested_marks,
                       correction.requested_evidence->>'gradeCode' AS requested_grade,
                       correction.requested_evidence->>'narrative' AS requested_narrative,
                       correction.request_reason, correction.status, account.display_name AS requested_by,
                       correction.created_at, correction.row_version
                FROM assessment_correction_requests correction
                JOIN assessment_marks mark ON mark.id = correction.assessment_mark_id
                JOIN assessments assessment ON assessment.id = mark.assessment_id
                JOIN learners learner ON learner.id = mark.learner_id
                JOIN memberships membership ON membership.id = correction.requested_by_membership_id
                JOIN user_accounts account ON account.id = membership.user_id
                WHERE correction.organisation_id = :organisationId AND correction.school_id = :schoolId
                  AND (:canPublish OR correction.requested_by_membership_id = :actorMembershipId)
                ORDER BY CASE correction.status WHEN 'PENDING' THEN 0 ELSE 1 END,
                         correction.created_at DESC, correction.id
                """, scope.parameters(), (result, row) -> new CorrectionItem(
                result.getObject("id", UUID.class), result.getObject("mark_id", UUID.class),
                result.getObject("assessment_id", UUID.class), result.getString("legal_name"),
                result.getString("assessment_name"), result.getString("requested_state"),
                result.getBigDecimal("requested_marks"), result.getString("requested_grade"),
                result.getString("requested_narrative"), result.getString("request_reason"),
                result.getString("status"), result.getString("requested_by"),
                instant(result, "created_at"), result.getLong("row_version")));
    }

    private List<PublicationItem> publications(Scope scope) {
        return jdbc.query("""
                SELECT publication.id, publication.academic_term_id, publication.class_section_id,
                       publication.assessment_scheme_id, publication.publication_version,
                       publication.publication_name, publication.status, publication.supersedes_publication_id,
                       (SELECT count(*) FROM learner_report_cards card
                        WHERE card.result_publication_id = publication.id) AS card_count,
                       publication.published_at, publication.superseded_at, publication.revoked_at
                FROM result_publications publication
                WHERE publication.organisation_id = :organisationId AND publication.school_id = :schoolId
                ORDER BY publication.published_at DESC, publication.id
                """, scope.parameters(), (result, row) -> new PublicationItem(
                result.getObject("id", UUID.class), result.getObject("academic_term_id", UUID.class),
                result.getObject("class_section_id", UUID.class), result.getObject("assessment_scheme_id", UUID.class),
                result.getInt("publication_version"), result.getString("publication_name"),
                result.getString("status"), result.getObject("supersedes_publication_id", UUID.class),
                result.getInt("card_count"), instant(result, "published_at"),
                instant(result, "superseded_at"), instant(result, "revoked_at")));
    }

    private Scope requireScope(AuthenticatedUser user, UUID requestedSchoolId, String capability) {
        if (user.activeOrganisationId() == null) throw conflict("ORGANISATION_SELECTION_REQUIRED", "Select a school organisation before opening assessment.");
        List<Scope> scopes = jdbc.query("""
                SELECT school.id AS school_id, school.display_name AS school_name, membership.id AS membership_id,
                       EXISTS (SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id = mr.role_id
                               WHERE mr.membership_id = membership.id AND rc.capability_code = 'assessment.configure') AS can_configure,
                       EXISTS (SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id = mr.role_id
                               WHERE mr.membership_id = membership.id AND rc.capability_code = 'assessment.publish') AS can_publish
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
                        result.getString("school_name"), result.getObject("membership_id", UUID.class),
                        result.getBoolean("can_configure"), result.getBoolean("can_publish")));
        if (scopes.isEmpty()) throw new ApiException(HttpStatus.FORBIDDEN, "ASSESSMENT_SCOPE_DENIED", "Assessment access is not available for the selected school.");
        return scopes.getFirst();
    }

    private void authorizeOffering(Scope scope, UUID offeringId, LocalDate date) {
        if (scope.canConfigure() || scope.canPublish()) return;
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM teaching_allocations allocation
                WHERE allocation.section_subject_offering_id = :offeringId
                  AND allocation.organisation_id = :organisationId AND allocation.school_id = :schoolId
                  AND allocation.teacher_membership_id = :actorMembershipId AND allocation.status = 'ACTIVE'
                  AND allocation.starts_on <= :date
                  AND (allocation.ends_on IS NULL OR allocation.ends_on >= :date)
                """, scope.parameters().addValue("offeringId", offeringId).addValue("date", date), Integer.class);
        if (count == null || count == 0) throw new ApiException(HttpStatus.FORBIDDEN, "ASSESSMENT_ASSIGNMENT_DENIED", "This subject assessment is outside your active teaching assignment.");
    }

    private StoredAssessment assessmentForUpdate(Scope scope, UUID id, long expectedVersion) {
        List<StoredAssessment> rows = jdbc.query("""
                SELECT id, class_section_id, section_subject_offering_id, assessment_date,
                       status, scoring_mode, maximum_marks, row_version
                FROM assessments WHERE id = :id AND organisation_id = :organisationId
                  AND school_id = :schoolId FOR UPDATE
                """, scope.parameters().addValue("id", id), (result, row) -> new StoredAssessment(
                result.getObject("id", UUID.class), result.getObject("class_section_id", UUID.class),
                result.getObject("section_subject_offering_id", UUID.class),
                result.getObject("assessment_date", LocalDate.class), result.getString("status"),
                result.getString("scoring_mode"), result.getBigDecimal("maximum_marks"), result.getLong("row_version")));
        if (rows.isEmpty()) throw notFound("ASSESSMENT_NOT_FOUND", "The assessment was not found.");
        StoredAssessment assessment = rows.getFirst();
        if (assessment.rowVersion() != expectedVersion) throw conflict("ASSESSMENT_STALE", "Refresh the assessment before continuing.");
        return assessment;
    }

    private StoredScheme schemeForUpdate(Scope scope, UUID id, long expectedVersion) {
        List<StoredScheme> rows = jdbc.query("""
                SELECT id, status, aggregation_mode, pass_rule, grading_scale_id, row_version
                FROM assessment_schemes WHERE id = :id AND organisation_id = :organisationId
                  AND school_id = :schoolId FOR UPDATE
                """, scope.parameters().addValue("id", id), (result, row) -> new StoredScheme(
                result.getObject("id", UUID.class), result.getString("status"),
                result.getString("aggregation_mode"), result.getString("pass_rule"),
                result.getObject("grading_scale_id", UUID.class), result.getLong("row_version")));
        if (rows.isEmpty()) throw notFound("ASSESSMENT_SCHEME_NOT_FOUND", "The assessment scheme was not found.");
        if (rows.getFirst().rowVersion() != expectedVersion) throw conflict("SCHEME_STALE", "Refresh the scheme before continuing.");
        return rows.getFirst();
    }

    private StoredMark storedMark(Scope scope, UUID assessmentId, UUID learnerId) {
        List<StoredMark> rows = jdbc.query("""
                SELECT id, evidence_state, marks_awarded, grade_code, narrative, teacher_remark, row_version
                FROM assessment_marks WHERE assessment_id = :assessmentId AND learner_id = :learnerId
                  AND organisation_id = :organisationId AND school_id = :schoolId
                """, scope.parameters().addValue("assessmentId", assessmentId).addValue("learnerId", learnerId),
                (result, row) -> new StoredMark(result.getObject("id", UUID.class),
                        new Evidence(result.getString("evidence_state"), result.getBigDecimal("marks_awarded"),
                                result.getString("grade_code"), result.getString("narrative"), result.getString("teacher_remark")),
                        result.getLong("row_version")));
        if (rows.isEmpty()) throw badRequest("LEARNER_NOT_IN_ASSESSMENT", "Choose a learner from this assessment register.");
        return rows.getFirst();
    }

    private MarkContext markContext(Scope scope, UUID markId) {
        List<MarkContext> rows = jdbc.query("""
                SELECT assessment.id, assessment.class_section_id, assessment.section_subject_offering_id,
                       assessment.assessment_date, assessment.status, assessment.scoring_mode,
                       assessment.maximum_marks, assessment.row_version
                FROM assessment_marks mark JOIN assessments assessment ON assessment.id = mark.assessment_id
                WHERE mark.id = :markId AND mark.organisation_id = :organisationId AND mark.school_id = :schoolId
                """, scope.parameters().addValue("markId", markId), (result, row) -> {
                    StoredAssessment assessment = new StoredAssessment(result.getObject("id", UUID.class),
                            result.getObject("class_section_id", UUID.class),
                            result.getObject("section_subject_offering_id", UUID.class),
                            result.getObject("assessment_date", LocalDate.class), result.getString("status"),
                            result.getString("scoring_mode"), result.getBigDecimal("maximum_marks"), result.getLong("row_version"));
                    return new MarkContext(assessment.offeringId(), assessment.date(), assessment.status(), assessment);
                });
        if (rows.isEmpty()) throw notFound("ASSESSMENT_MARK_NOT_FOUND", "The learner mark was not found.");
        return rows.getFirst();
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

    private List<RosterLearner> publicationRoster(Scope scope, PublicationContext context) {
        return jdbc.query("""
                SELECT evidence.learner_id, evidence.enrolment_id, evidence.legal_name, evidence.roll_number
                FROM (
                    SELECT DISTINCT ON (mark.learner_id)
                        mark.learner_id, mark.enrolment_id, learner.legal_name, placement.roll_number,
                        assessment.assessment_date
                    FROM assessment_marks mark
                    JOIN assessments assessment ON assessment.id = mark.assessment_id
                    JOIN learners learner ON learner.id = mark.learner_id
                    LEFT JOIN LATERAL (
                        SELECT candidate.roll_number
                        FROM student_section_placements candidate
                        WHERE candidate.organisation_id = mark.organisation_id
                          AND candidate.school_id = mark.school_id
                          AND candidate.enrolment_id = mark.enrolment_id
                          AND candidate.class_section_id = assessment.class_section_id
                          AND candidate.starts_on <= assessment.assessment_date
                          AND (candidate.ends_on IS NULL OR candidate.ends_on >= assessment.assessment_date)
                        ORDER BY candidate.starts_on DESC, candidate.id
                        LIMIT 1
                    ) placement ON TRUE
                    WHERE mark.organisation_id = :organisationId AND mark.school_id = :schoolId
                      AND assessment.academic_session_id = :sessionId
                      AND assessment.academic_term_id = :termId
                      AND assessment.class_section_id = :sectionId
                      AND assessment.assessment_scheme_id = :schemeId
                      AND assessment.status = 'PUBLISHED'
                    ORDER BY mark.learner_id, assessment.assessment_date DESC, assessment.id
                ) evidence
                ORDER BY evidence.roll_number NULLS LAST, evidence.legal_name, evidence.learner_id
                """, scope.parameters().addValue("sessionId", context.sessionId())
                .addValue("termId", context.termId()).addValue("sectionId", context.sectionId())
                .addValue("schemeId", context.schemeId()),
                (result, row) -> new RosterLearner(result.getObject("learner_id", UUID.class),
                        result.getObject("enrolment_id", UUID.class), result.getString("legal_name"),
                        result.getString("roll_number")));
    }

    private void requireSession(Scope scope, UUID sessionId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM academic_sessions WHERE id = :sessionId
                  AND organisation_id = :organisationId AND school_id = :schoolId
                """, scope.parameters().addValue("sessionId", sessionId), Integer.class);
        if (!Objects.equals(count, 1)) throw badRequest("ACADEMIC_SESSION_NOT_FOUND", "Choose an academic session from this school.");
    }

    private Evidence validateEvidence(
            StoredAssessment assessment, String stateValue, BigDecimal marksAwarded,
            String gradeCode, String narrative, String teacherRemark, boolean allowMissing) {
        String state = enumValue(stateValue, EVIDENCE_STATES, "evidence state");
        String grade = upperNullable(gradeCode);
        String note = clean(narrative);
        String remark = clean(teacherRemark);
        if (!allowMissing && "MISSING".equals(state)) throw badRequest("MISSING_EVIDENCE_NOT_SAVABLE", "Choose recorded, absent, or exempt evidence.");
        if (Set.of("ABSENT", "EXEMPT", "MISSING").contains(state)) {
            if (marksAwarded != null || grade != null || note != null) throw badRequest("EVIDENCE_STATE_HAS_VALUE", "Absent, exempt, and missing evidence cannot carry a score, grade, or narrative.");
            return new Evidence(state, null, null, null, remark);
        }
        switch (assessment.scoringMode()) {
            case "MARKS" -> {
                if (marksAwarded == null) throw badRequest("MARKS_REQUIRED", "Enter marks for recorded evidence.");
                if (marksAwarded.signum() < 0 || marksAwarded.compareTo(assessment.maximumMarks()) > 0) {
                    throw badRequest("MARKS_OUT_OF_RANGE", "Marks must be between zero and the configured maximum.");
                }
                if (grade != null || note != null) throw badRequest("MARKS_VALUE_INVALID", "Marks assessments accept a numeric score and optional teacher remark.");
                return new Evidence(state, marksAwarded, null, null, remark);
            }
            case "GRADE" -> {
                if (grade == null) throw badRequest("GRADE_REQUIRED", "Enter a grade code for recorded evidence.");
                if (marksAwarded != null || note != null) throw badRequest("GRADE_VALUE_INVALID", "Grade assessments accept a grade code and optional teacher remark.");
                return new Evidence(state, null, grade, null, remark);
            }
            case "NARRATIVE" -> {
                if (note == null) throw badRequest("NARRATIVE_REQUIRED", "Enter a narrative observation for recorded evidence.");
                if (marksAwarded != null || grade != null) throw badRequest("NARRATIVE_VALUE_INVALID", "Narrative assessments accept an observation and optional teacher remark.");
                return new Evidence(state, null, null, note, remark);
            }
            default -> throw new IllegalStateException("Unknown frozen scoring mode");
        }
    }

    private void history(Scope scope, UUID markId, Evidence previous, Evidence next, String kind, String reason) {
        jdbc.update("""
                INSERT INTO assessment_mark_history (
                    id, organisation_id, school_id, assessment_mark_id, previous_evidence,
                    new_evidence, change_kind, reason, actor_membership_id, occurred_at
                ) VALUES (:id, :organisationId, :schoolId, :markId, CAST(:previous AS jsonb),
                    CAST(:next AS jsonb), :kind, :reason, :actorMembershipId, :now)
                """, scope.parameters().addValue("id", UUID.randomUUID()).addValue("markId", markId)
                .addValue("previous", previous == null ? null : json(previous)).addValue("next", json(next))
                .addValue("kind", kind).addValue("reason", reason).addValue("now", Instant.now(clock)));
    }

    private long touchAssessment(Scope scope, UUID id, long expectedVersion) {
        int changed = jdbc.update("""
                UPDATE assessments SET updated_at = :now, row_version = row_version + 1
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                  AND row_version = :expectedVersion AND status = 'OPEN'
                """, scope.parameters().addValue("id", id).addValue("now", Instant.now(clock))
                .addValue("expectedVersion", expectedVersion));
        requireChanged(changed, "ASSESSMENT_STALE", "The assessment changed before marks could be saved.");
        return expectedVersion + 1;
    }

    private List<Band> bands(Scope scope, UUID scaleId) {
        if (scaleId == null) return List.of();
        return jdbc.query("""
                SELECT grade_code, minimum_percentage, maximum_percentage, outcome
                FROM assessment_grade_bands
                WHERE grading_scale_id = :scaleId AND organisation_id = :organisationId AND school_id = :schoolId
                ORDER BY minimum_percentage DESC, display_order, id
                """, scope.parameters().addValue("scaleId", scaleId), (result, row) -> new Band(
                result.getString("grade_code"), result.getBigDecimal("minimum_percentage"),
                result.getBigDecimal("maximum_percentage"), result.getString("outcome")));
    }

    private static Band bandFor(List<Band> bands, BigDecimal percentage) {
        if (percentage == null) return null;
        return bands.stream().filter(band -> percentage.compareTo(band.minimum()) >= 0
                && percentage.compareTo(band.maximum()) <= 0).findFirst().orElse(null);
    }

    private MapSqlParameterSource scaleParameters(Scope scope, UUID id, ScaleRequest request, Long version) {
        return scope.parameters().addValue("id", id).addValue("sessionId", request.academicSessionId())
                .addValue("name", required(request.name(), "grading scale name"))
                .addValue("status", enumValue(request.status(), ACTIVE_STATUSES, "grading scale status"))
                .addValue("expectedVersion", version).addValue("now", Instant.now(clock));
    }

    private MapSqlParameterSource bandParameters(Scope scope, UUID id, BandRequest request, Long version) {
        return scope.parameters().addValue("id", id).addValue("scaleId", request.gradingScaleId())
                .addValue("code", upper(request.gradeCode())).addValue("name", required(request.name(), "grade name"))
                .addValue("minimum", request.minimumPercentage()).addValue("maximum", request.maximumPercentage())
                .addValue("gradePoint", request.gradePoint()).addValue("outcome", enumValue(request.outcome(), BAND_OUTCOMES, "grade outcome"))
                .addValue("descriptor", clean(request.descriptor())).addValue("displayOrder", request.displayOrder())
                .addValue("expectedVersion", version).addValue("now", Instant.now(clock));
    }

    private MapSqlParameterSource schemeParameters(Scope scope, UUID id, SchemeRequest request, Long version) {
        return scope.parameters().addValue("id", id).addValue("sessionId", request.academicSessionId())
                .addValue("scaleId", request.gradingScaleId()).addValue("gradeCode", upper(request.gradeCode()))
                .addValue("name", required(request.name(), "scheme name"))
                .addValue("aggregationMode", enumValue(request.aggregationMode(), AGGREGATION_MODES, "aggregation mode"))
                .addValue("roundingMode", enumValue(request.roundingMode(), ROUNDING_MODES, "rounding mode"))
                .addValue("decimalPlaces", request.decimalPlaces())
                .addValue("passRule", enumValue(request.passRule(), PASS_RULES, "pass rule"))
                .addValue("overallPass", request.overallPassPercentage()).addValue("expectedVersion", version)
                .addValue("now", Instant.now(clock));
    }

    private MapSqlParameterSource componentParameters(Scope scope, UUID id, ComponentRequest request, Long version) {
        return scope.parameters().addValue("id", id).addValue("schemeId", request.schemeId())
                .addValue("code", upper(request.code())).addValue("name", required(request.name(), "component name"))
                .addValue("scoringMode", enumValue(request.scoringMode(), SCORING_MODES, "scoring mode"))
                .addValue("weight", request.weightPercentage()).addValue("defaultMax", request.defaultMaxMarks())
                .addValue("passPercentage", request.passPercentage()).addValue("displayOrder", request.displayOrder())
                .addValue("status", enumValue(request.status(), ACTIVE_STATUSES, "component status"))
                .addValue("expectedVersion", version).addValue("now", Instant.now(clock));
    }

    private MapSqlParameterSource evidenceParameters(Scope scope, UUID id, Evidence evidence, long version) {
        return scope.parameters().addValue("id", id).addValue("state", evidence.state())
                .addValue("marks", evidence.marksAwarded()).addValue("grade", evidence.gradeCode())
                .addValue("narrative", evidence.narrative()).addValue("remark", evidence.teacherRemark())
                .addValue("expectedVersion", version).addValue("now", Instant.now(clock));
    }

    private void audit(AuthenticatedUser user, Scope scope, String action, String type, UUID id, String reason) {
        audit.recordScoped(user, scope.actorMembershipId(), scope.schoolId(), action, type, id.toString(),
                AuditOutcome.SUCCEEDED, clean(reason), Map.of());
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalStateException("Unable to encode assessment evidence", exception); }
    }

    private Evidence evidence(String value) {
        try { return objectMapper.readValue(value, Evidence.class); }
        catch (JacksonException exception) { throw new IllegalStateException("Stored assessment correction evidence is invalid", exception); }
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }

    private static RoundingMode roundingMode(String value) {
        return switch (value) {
            case "HALF_EVEN" -> RoundingMode.HALF_EVEN;
            case "DOWN" -> RoundingMode.DOWN;
            default -> RoundingMode.HALF_UP;
        };
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        return result.getTimestamp(column) == null ? null : result.getTimestamp(column).toInstant();
    }

    private static AssessmentRow mapAssessmentRow(ResultSet result, int row) throws SQLException {
        return new AssessmentRow(result.getObject("id", UUID.class), result.getObject("academic_term_id", UUID.class),
                result.getObject("class_section_id", UUID.class), result.getObject("section_subject_offering_id", UUID.class),
                result.getObject("assessment_scheme_id", UUID.class), result.getObject("assessment_component_id", UUID.class),
                result.getString("assessment_name"), result.getObject("assessment_date", LocalDate.class),
                result.getString("scoring_mode"), result.getBigDecimal("maximum_marks"), result.getString("subject_name"),
                result.getString("component_name"), result.getString("status"), result.getInt("expected_learner_count"),
                result.getInt("recorded_count"), instant(result, "submitted_at"), instant(result, "validated_at"),
                instant(result, "published_at"), result.getLong("row_version"));
    }

    private static ReportRow mapReportRow(ResultSet result, int row) throws SQLException {
        return new ReportRow(result.getObject("subject_id", UUID.class), result.getString("subject_code"),
                result.getString("subject_name"), result.getObject("component_id", UUID.class),
                result.getString("component_code"), result.getString("component_name"),
                result.getString("scoring_mode"), result.getBigDecimal("weight_percentage"),
                result.getBigDecimal("pass_percentage"), result.getObject("assessment_id", UUID.class),
                result.getString("assessment_name"), result.getObject("assessment_date", LocalDate.class),
                result.getBigDecimal("maximum_marks"), result.getString("evidence_state"),
                result.getBigDecimal("marks_awarded"), result.getString("grade_code"),
                result.getString("narrative"), result.getString("teacher_remark"));
    }

    private static long requireVersion(Long version) {
        if (version == null) throw badRequest("EXPECTED_VERSION_REQUIRED", "Refresh the record and include its current version before editing.");
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

    private static String upperNullable(String value) {
        String result = clean(value);
        return result == null ? null : result.toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) return null;
        return value.replaceAll("[\\p{Cntrl}]", " ").strip();
    }

    private static void requireChanged(int changed, String code, String message) {
        if (changed != 1) throw conflict(code, message);
    }

    private static <T> T safely(String code, String message, Work<T> work) {
        try { return work.run(); }
        catch (DataIntegrityViolationException exception) { throw conflict(code, message); }
    }

    private static ApiException badRequest(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
    private static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
    private static ApiException notFound(String code, String message) { return new ApiException(HttpStatus.NOT_FOUND, code, message); }

    private record Scope(UUID organisationId, UUID schoolId, String schoolName, UUID actorMembershipId,
                         boolean canConfigure, boolean canPublish) {
        MapSqlParameterSource parameters() {
            return new MapSqlParameterSource().addValue("organisationId", organisationId)
                    .addValue("schoolId", schoolId).addValue("actorMembershipId", actorMembershipId)
                    .addValue("canConfigure", canConfigure).addValue("canPublish", canPublish);
        }
    }
    private record AssessmentRow(UUID id, UUID termId, UUID sectionId, UUID offeringId, UUID schemeId,
                                 UUID componentId, String name, LocalDate date, String scoringMode,
                                 BigDecimal maximumMarks, String subjectName, String componentName,
                                 String status, int expectedCount, int recordedCount, Instant submittedAt,
                                 Instant validatedAt, Instant publishedAt, long rowVersion) {}
    private record StoredAssessment(UUID id, UUID sectionId, UUID offeringId, LocalDate date, String status,
                                    String scoringMode, BigDecimal maximumMarks, long rowVersion) {}
    private record StoredScheme(UUID id, String status, String aggregationMode, String passRule,
                                UUID gradingScaleId, long rowVersion) {}
    private record ComponentDefinition(String scoringMode, BigDecimal weight, BigDecimal passPercentage) {}
    private record AssessmentDefinition(UUID sessionId, UUID termId, LocalDate termStart, LocalDate termEnd,
                                        UUID sectionId, String gradeCode, UUID offeringId, UUID subjectId,
                                        String subjectCode, String subjectName, UUID schemeId, String schemeName,
                                        String aggregationMode, String roundingMode, int decimalPlaces,
                                        String passRule, BigDecimal overallPass, UUID gradingScaleId,
                                        UUID componentId, String componentCode, String componentName,
                                        String scoringMode, BigDecimal weight, BigDecimal defaultMaxMarks,
                                        BigDecimal passPercentage) {}
    private record RosterLearner(UUID learnerId, UUID enrolmentId, String name, String rollNumber) {}
    private record Evidence(String state, BigDecimal marksAwarded, String gradeCode, String narrative, String teacherRemark) {}
    private record StoredMark(UUID id, Evidence evidence, long rowVersion) {}
    private record MarkContext(UUID offeringId, LocalDate date, String status, StoredAssessment assessment) {}
    private record StoredCorrection(UUID markId, Evidence requested, String status, long rowVersion,
                                    UUID assessmentId, Evidence current, long markVersion, StoredAssessment assessment) {}
    private record PublicationContext(UUID sessionId, String sessionName, UUID termId, String termName,
                                      LocalDate termEnd, UUID sectionId, String sectionName, String gradeCode,
                                      UUID schemeId, String schemeName, String aggregationMode, String roundingMode,
                                      int decimalPlaces, String passRule, BigDecimal overallPass,
                                      UUID gradingScaleId) {}
    private record Band(String gradeCode, BigDecimal minimum, BigDecimal maximum, String outcome) {}
    private record ReportRow(UUID subjectId, String subjectCode, String subjectName, UUID componentId,
                             String componentCode, String componentName, String scoringMode, BigDecimal weight,
                             BigDecimal passPercentage, UUID assessmentId, String assessmentName,
                             LocalDate assessmentDate, BigDecimal maximumMarks, String evidenceState,
                             BigDecimal marksAwarded, String gradeCode, String narrative, String teacherRemark) {}
    private record ReportEvidence(String reportNumber, String snapshotJson, BigDecimal percentage,
                                  String gradeCode, String outcome, String hash) {}

    @FunctionalInterface
    private interface Work<T> { T run(); }
}
