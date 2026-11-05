package com.educore.api.admissions;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdmissionApplicationService {

    private static final String READ = "admissions.application.read";
    private static final String MANAGE = "admissions.application.manage";
    private static final String DECIDE = "admissions.application.decide";
    private static final String ENROL = "admissions.application.enrol";
    private static final List<String> HANDOFF_CAPABILITIES =
            List.of(ENROL, "student.manage", "guardian.manage");

    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final Clock clock;

    public AdmissionApplicationService(
            NamedParameterJdbcTemplate jdbc,
            SecurityAuditService audit,
            Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ApplicationPageResponse list(
            AuthenticatedUser user,
            ApplicationStatus status,
            String query,
            int page,
            int size) {
        return list(user, status, query, page, size, null);
    }

    @Transactional(readOnly = true)
    public ApplicationPageResponse list(AuthenticatedUser user, ApplicationStatus status,
            String query, int page, int size, AdmissionType admissionType) {
        UUID organisationId = requireOrganisation(user);
        Instant now = Instant.now(clock);
        String search = nullableClean(query);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("actorUserId", user.userId())
                .addValue("organisationId", organisationId)
                .addValue("requiredCapability", READ)
                .addValue("now", now)
                .addValue("status", status == null ? null : status.name())
                .addValue("admissionType", admissionType == null ? null : admissionType.name())
                .addValue("query", search == null ? null : "%" + escapeLike(search.toLowerCase(Locale.ROOT)) + "%")
                .addValue("limit", size)
                .addValue("offset", Math.multiplyExact(page, size));

        String visibleWhere = """
                application.organisation_id = :organisationId
                AND EXISTS (
                    SELECT 1
                    FROM memberships actor_membership
                    JOIN membership_roles actor_role ON actor_role.membership_id = actor_membership.id
                    JOIN role_capabilities actor_capability ON actor_capability.role_id = actor_role.role_id
                    WHERE actor_membership.user_id = :actorUserId
                      AND actor_membership.organisation_id = application.organisation_id
                      AND actor_membership.school_id = application.school_id
                      AND actor_membership.status = 'ACTIVE'
                      AND actor_membership.valid_from <= :now
                      AND (actor_membership.valid_until IS NULL OR actor_membership.valid_until > :now)
                      AND actor_capability.capability_code = :requiredCapability
                )
                AND (CAST(:status AS varchar) IS NULL OR application.status = :status)
                AND (CAST(:admissionType AS varchar) IS NULL OR application.admission_type = :admissionType)
                AND (
                    CAST(:query AS text) IS NULL
                    OR lower(application.applicant_legal_name) LIKE :query ESCAPE '\\'
                    OR lower(coalesce(application.applicant_preferred_name, '')) LIKE :query ESCAPE '\\'
                    OR lower(application.target_grade_code) LIKE :query ESCAPE '\\'
                    OR lower(application.intake_session_label) LIKE :query ESCAPE '\\'
                    OR lower(application.id::text) LIKE replace(:query, 'app-', '') ESCAPE '\\'
                )
                """;

        List<ApplicationListItem> items = jdbc.query("""
                SELECT application.id, application.school_id, application.applicant_legal_name,
                       application.applicant_preferred_name, application.target_grade_code,
                       application.intake_session_label, application.status,
                       application.assigned_membership_id, assignee.display_name AS assignee_name,
                       application.enquiry_id, document_counts.required_count,
                       document_counts.verified_count, application.created_at,
                       application.updated_at, application.row_version, application.admission_type
                FROM admission_applications application
                LEFT JOIN memberships assigned ON assigned.id = application.assigned_membership_id
                LEFT JOIN user_accounts assignee ON assignee.id = assigned.user_id
                LEFT JOIN LATERAL (
                    SELECT count(*) FILTER (WHERE document.required) AS required_count,
                           count(*) FILTER (WHERE document.required AND document.status = 'VERIFIED') AS verified_count
                    FROM admission_application_documents document
                    WHERE document.application_id = application.id
                      AND document.organisation_id = application.organisation_id
                      AND document.school_id = application.school_id
                ) document_counts ON true
                WHERE
                """ + visibleWhere + """
                ORDER BY application.updated_at DESC, application.id DESC
                LIMIT :limit OFFSET :offset
                """, parameters, AdmissionApplicationService::mapListItem);

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM admission_applications application WHERE " + visibleWhere,
                parameters,
                Long.class);
        ApplicationMetrics metrics = jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE application.status = 'DRAFT') AS draft_count,
                       count(*) FILTER (WHERE application.status IN ('SUBMITTED', 'UNDER_REVIEW', 'WAITLISTED')) AS review_count,
                       count(*) FILTER (WHERE application.status = 'OFFERED') AS offered_count,
                       count(*) FILTER (WHERE application.status = 'ACCEPTED') AS accepted_count,
                       count(*) FILTER (WHERE EXISTS (
                           SELECT 1 FROM admission_application_documents document
                           WHERE document.application_id = application.id
                             AND document.required
                             AND document.status <> 'VERIFIED'
                       )) AS incomplete_document_count
                FROM admission_applications application
                WHERE application.organisation_id = :organisationId
                  AND EXISTS (
                      SELECT 1
                      FROM memberships actor_membership
                      JOIN membership_roles actor_role ON actor_role.membership_id = actor_membership.id
                      JOIN role_capabilities actor_capability ON actor_capability.role_id = actor_role.role_id
                      WHERE actor_membership.user_id = :actorUserId
                        AND actor_membership.organisation_id = application.organisation_id
                        AND actor_membership.school_id = application.school_id
                        AND actor_membership.status = 'ACTIVE'
                        AND actor_membership.valid_from <= :now
                        AND (actor_membership.valid_until IS NULL OR actor_membership.valid_until > :now)
                        AND actor_capability.capability_code = :requiredCapability
                  )
                """, parameters, (result, row) -> new ApplicationMetrics(
                result.getLong("draft_count"),
                result.getLong("review_count"),
                result.getLong("offered_count"),
                result.getLong("accepted_count"),
                result.getLong("incomplete_document_count")));
        return new ApplicationPageResponse(items, page, size, total == null ? 0 : total, metrics);
    }

    @Transactional(readOnly = true)
    public ApplicationDetailResponse get(AuthenticatedUser user, UUID applicationId) {
        return toDetail(findAccessible(user, applicationId, List.of(READ)));
    }

    @Transactional
    public ApplicationDetailResponse create(
            AuthenticatedUser user,
            CreateApplicationRequest request,
            String idempotencyKey) {
        validateGender(request.gender(), request.genderDescription());
        String normalizedKey = validateIdempotencyKey(idempotencyKey);
        Instant now = Instant.now(clock);
        CreationScope scope = resolveCreationScope(user, request, now);
        validateTargetOptions(
                scope.organisationId(),
                scope.schoolId(),
                request.targetCampusId(),
                request.targetAcademicSessionId());
        AdmissionType admissionType = AdmissionType.orRegular(request.admissionType());
        validateRte(scope.organisationId(), scope.schoolId(), admissionType, request.targetGradeCode(),
                request.targetAcademicSessionId(), request.birthDate(), request.rteReference(), false);
        List<GuardianInput> guardians = normalizeGuardians(request.guardians(), scope.enquiry());
        validateGuardians(guardians);
        UUID proposedId = UUID.randomUUID();
        String requestFingerprint = fingerprint(request, scope.schoolId(), guardians);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("id", proposedId)
                .addValue("organisationId", scope.organisationId())
                .addValue("schoolId", scope.schoolId())
                .addValue("enquiryId", scope.enquiry() == null ? null : scope.enquiry().id())
                .addValue("idempotencyKey", normalizedKey)
                .addValue("fingerprint", requestFingerprint)
                .addValue("campusId", request.targetCampusId())
                .addValue("sessionId", request.targetAcademicSessionId())
                .addValue("sessionLabel", clean(request.intakeSessionLabel()))
                .addValue("gradeCode", clean(request.targetGradeCode()).toUpperCase(Locale.ROOT))
                .addValue("legalName", clean(request.applicantLegalName()))
                .addValue("preferredName", nullableClean(request.applicantPreferredName()))
                .addValue("birthDate", request.birthDate())
                .addValue("gender", request.gender().name())
                .addValue("genderDescription", normalizedGenderDescription(request.gender(), request.genderDescription()))
                .addValue("admissionType", admissionType.name())
                .addValue("rteReference", nullableClean(request.rteReference()))
                .addValue("actorMembershipId", scope.actorMembershipId())
                .addValue("now", now);
        InsertedApplication inserted = jdbc.queryForObject("""
                INSERT INTO admission_applications (
                    id, organisation_id, school_id, enquiry_id, idempotency_key, request_fingerprint,
                    target_campus_id, target_academic_session_id, intake_session_label,
                    target_grade_code, applicant_legal_name, applicant_preferred_name,
                    birth_date, gender_code, gender_description, status,
                    assigned_membership_id, created_by_membership_id,
                    created_at, updated_at, row_version, admission_type, rte_reference
                ) VALUES (
                    :id, :organisationId, :schoolId, :enquiryId, :idempotencyKey, :fingerprint,
                    :campusId, :sessionId, :sessionLabel, :gradeCode, :legalName, :preferredName,
                    :birthDate, :gender, :genderDescription, 'DRAFT',
                    :actorMembershipId, :actorMembershipId, :now, :now, 0, :admissionType, :rteReference
                )
                ON CONFLICT (school_id, idempotency_key) DO UPDATE
                    SET idempotency_key = EXCLUDED.idempotency_key
                RETURNING id, organisation_id, school_id, request_fingerprint
                """, parameters, (result, row) -> new InsertedApplication(
                result.getObject("id", UUID.class),
                result.getObject("organisation_id", UUID.class),
                result.getObject("school_id", UUID.class),
                result.getString("request_fingerprint")));
        if (!requestFingerprint.equals(inserted.requestFingerprint())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_KEY_REUSED",
                    "This retry key was already used for a different admission application.");
        }
        if (proposedId.equals(inserted.id())) {
            insertGuardians(inserted, guardians, now);
            appendOutbox(inserted.id(), "admissions.application.created.v1", "DRAFT", 0, now);
            audit.recordScoped(
                    user,
                    scope.actorMembershipId(),
                    scope.schoolId(),
                    "admissions.application.created",
                    "ADMISSION_APPLICATION",
                    inserted.id().toString(),
                    AuditOutcome.SUCCEEDED,
                    null,
                    Map.of(
                            "source", scope.enquiry() == null ? "STAFF" : "ENQUIRY",
                            "guardianCount", guardians.size(),
                            "targetConfigured", request.targetCampusId() != null
                                    && request.targetAcademicSessionId() != null));
        }
        return toDetail(findAccessible(user, inserted.id(), List.of(MANAGE)));
    }

    @Transactional
    public ApplicationDetailResponse update(
            AuthenticatedUser user,
            UUID applicationId,
            UpdateApplicationRequest request) {
        validateGender(request.gender(), request.genderDescription());
        StoredApplication existing = findAccessible(user, applicationId, List.of(MANAGE));
        requireVersion(existing, request.expectedVersion());
        if (!Set.of(
                ApplicationStatus.DRAFT,
                ApplicationStatus.SUBMITTED,
                ApplicationStatus.UNDER_REVIEW,
                ApplicationStatus.WAITLISTED,
                ApplicationStatus.OFFERED).contains(existing.status())) {
            throw conflict("APPLICATION_EDIT_LOCKED", "Reopen this application before changing applicant details.");
        }
        validateTargetOptions(
                existing.organisationId(),
                existing.schoolId(),
                request.targetCampusId(),
                request.targetAcademicSessionId());
        AdmissionType admissionType = request.admissionType() == null ? existing.admissionType() : request.admissionType();
        String rteReference = request.admissionType() == null ? existing.rteReference() : nullableClean(request.rteReference());
        validateRte(existing.organisationId(), existing.schoolId(), admissionType, request.targetGradeCode(),
                request.targetAcademicSessionId(), request.birthDate(), rteReference, false);
        if (request.assignedMembershipId() != null) {
            requireMembershipCapabilities(
                    user,
                    existing.schoolId(),
                    request.assignedMembershipId(),
                    List.of(READ));
        }
        Instant now = Instant.now(clock);
        int updated = jdbc.update("""
                UPDATE admission_applications
                SET target_campus_id = :campusId,
                    target_academic_session_id = :sessionId,
                    intake_session_label = :sessionLabel,
                    target_grade_code = :gradeCode,
                    applicant_legal_name = :legalName,
                    applicant_preferred_name = :preferredName,
                    birth_date = :birthDate,
                    gender_code = :gender,
                    gender_description = :genderDescription,
                    assigned_membership_id = :assignedMembershipId,
                    admission_type = :admissionType,
                    rte_reference = :rteReference,
                    updated_at = :now,
                    row_version = row_version + 1
                WHERE id = :id
                  AND organisation_id = :organisationId
                  AND school_id = :schoolId
                  AND row_version = :expectedVersion
                """, new MapSqlParameterSource()
                .addValue("id", existing.id())
                .addValue("organisationId", existing.organisationId())
                .addValue("schoolId", existing.schoolId())
                .addValue("campusId", request.targetCampusId())
                .addValue("sessionId", request.targetAcademicSessionId())
                .addValue("sessionLabel", clean(request.intakeSessionLabel()))
                .addValue("gradeCode", clean(request.targetGradeCode()).toUpperCase(Locale.ROOT))
                .addValue("legalName", clean(request.applicantLegalName()))
                .addValue("preferredName", nullableClean(request.applicantPreferredName()))
                .addValue("birthDate", request.birthDate())
                .addValue("gender", request.gender().name())
                .addValue("genderDescription", normalizedGenderDescription(request.gender(), request.genderDescription()))
                .addValue("assignedMembershipId", request.assignedMembershipId())
                .addValue("admissionType", admissionType.name())
                .addValue("rteReference", rteReference)
                .addValue("now", now)
                .addValue("expectedVersion", request.expectedVersion()));
        requireSingleUpdate(updated);
        auditMutation(user, existing, "admissions.application.updated", Map.of(
                "assigned", request.assignedMembershipId() != null,
                "targetConfigured", request.targetCampusId() != null && request.targetAcademicSessionId() != null,
                "previousAdmissionType", existing.admissionType().name(), "admissionType", admissionType.name(),
                "version", existing.version() + 1));
        appendOutbox(existing.id(), "admissions.application.updated.v1", existing.status().name(), existing.version() + 1, now);
        return toDetail(findAccessible(user, applicationId, List.of(MANAGE)));
    }

    @Transactional
    public ApplicationDetailResponse updateStatus(
            AuthenticatedUser user,
            UUID applicationId,
            UpdateApplicationStatusRequest request) {
        StoredApplication existing = findAccessible(user, applicationId, List.of(MANAGE));
        requireVersion(existing, request.expectedVersion());
        ApplicationStatus target = request.status();
        if (Set.of(
                ApplicationStatus.WAITLISTED,
                ApplicationStatus.OFFERED,
                ApplicationStatus.REJECTED,
                ApplicationStatus.ENROLLED).contains(target)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "APPLICATION_STATUS_ACTION_REQUIRED",
                    "Use the controlled decision or enrolment action for this status.");
        }
        if (!existing.status().canTransitionTo(target)) {
            throw conflict("APPLICATION_STATUS_TRANSITION_INVALID", "The requested application transition is not allowed.");
        }
        if (target == ApplicationStatus.SUBMITTED && countGuardians(existing) == 0) {
            throw conflict("APPLICATION_GUARDIAN_REQUIRED", "Add at least one guardian before submitting the application.");
        }
        if (target == ApplicationStatus.ACCEPTED) validateOfferAcceptance(existing);
        Instant now = Instant.now(clock);
        int updated = jdbc.update("""
                UPDATE admission_applications
                SET status = :status,
                    submitted_at = CASE WHEN :status = 'SUBMITTED' THEN coalesce(submitted_at, :now) ELSE submitted_at END,
                    accepted_at = CASE WHEN :status = 'ACCEPTED' THEN :now ELSE accepted_at END,
                    withdrawn_at = CASE WHEN :status = 'WITHDRAWN' THEN :now ELSE NULL END,
                    updated_at = :now,
                    row_version = row_version + 1
                WHERE id = :id
                  AND organisation_id = :organisationId
                  AND school_id = :schoolId
                  AND row_version = :expectedVersion
                """, applicationParameters(existing)
                .addValue("status", target.name())
                .addValue("now", now)
                .addValue("expectedVersion", request.expectedVersion()));
        requireSingleUpdate(updated);
        appendOutbox(existing.id(), "admissions.application.status-changed.v1", target.name(), existing.version() + 1, now);
        auditMutation(user, existing, "admissions.application.status-changed", Map.of(
                "previousStatus", existing.status().name(),
                "status", target.name(),
                "version", existing.version() + 1));
        return toDetail(findAccessible(user, applicationId, List.of(MANAGE)));
    }

    @Transactional
    ApplicationDetailResponse addGuardian(
            AuthenticatedUser user,
            UUID applicationId,
            AddApplicationGuardianRequest request) {
        StoredApplication existing = findAccessible(user, applicationId, List.of(MANAGE));
        ensureGuardiansEditable(existing);
        requireVersion(existing, request.expectedVersion());
        GuardianInput guardian = request.guardian();
        validateGuardian(guardian);
        Instant now = Instant.now(clock);
        touchApplication(existing, now, request.expectedVersion());
        long guardianCount = countGuardians(existing);
        if (guardianCount >= 10) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "APPLICATION_GUARDIAN_LIMIT",
                    "An application may have at most 10 guardian contacts.");
        }
        if (guardian.primaryContact()) {
            demotePrimaryGuardian(existing, null, now);
        }
        insertGuardian(existing, guardian, now);
        auditMutation(user, existing, "admissions.application.guardian-added", Map.of(
                "primaryContact", guardian.primaryContact(),
                "relationshipCode", normalizeRelationshipCode(guardian.relationshipCode())));
        return toDetail(findAccessible(user, applicationId, List.of(MANAGE)));
    }

    @Transactional
    ApplicationDetailResponse updateGuardian(
            AuthenticatedUser user,
            UUID applicationId,
            UUID guardianId,
            UpdateApplicationGuardianRequest request) {
        StoredApplication existing = findAccessible(user, applicationId, List.of(MANAGE));
        ensureGuardiansEditable(existing);
        StoredGuardian stored = findGuardian(existing, guardianId);
        if (request.expectedVersion() == null || request.expectedVersion() != stored.version()) {
            throw conflict(
                    "APPLICATION_GUARDIAN_VERSION_CONFLICT",
                    "This guardian contact changed after it was opened. Refresh before saving again.");
        }
        GuardianInput guardian = request.guardian();
        validateGuardian(guardian);
        if (stored.primaryContact() && !guardian.primaryContact()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "APPLICATION_PRIMARY_GUARDIAN_REQUIRED",
                    "Choose another guardian as primary before changing this contact.");
        }
        Instant now = Instant.now(clock);
        if (guardian.primaryContact() && !stored.primaryContact()) {
            demotePrimaryGuardian(existing, guardianId, now);
        }
        int updated = jdbc.update("""
                UPDATE admission_application_guardians
                SET display_name = :displayName,
                    relationship_code = :relationshipCode,
                    email_normalized = :email,
                    phone = :phone,
                    primary_contact = :primaryContact,
                    pickup_authorized = :pickupAuthorized,
                    updated_at = :now,
                    row_version = row_version + 1
                WHERE id = :guardianId
                  AND application_id = :applicationId
                  AND organisation_id = :organisationId
                  AND school_id = :schoolId
                  AND row_version = :expectedVersion
                """, applicationParameters(existing)
                .addValue("guardianId", guardianId)
                .addValue("displayName", clean(guardian.displayName()))
                .addValue("relationshipCode", normalizeRelationshipCode(guardian.relationshipCode()))
                .addValue("email", normalizeEmail(guardian.email()))
                .addValue("phone", nullableClean(guardian.phone()))
                .addValue("primaryContact", guardian.primaryContact())
                .addValue("pickupAuthorized", guardian.pickupAuthorized())
                .addValue("now", now)
                .addValue("expectedVersion", request.expectedVersion()));
        if (updated != 1) {
            throw conflict(
                    "APPLICATION_GUARDIAN_VERSION_CONFLICT",
                    "This guardian contact changed after it was opened. Refresh before saving again.");
        }
        touchApplication(existing, now);
        auditMutation(user, existing, "admissions.application.guardian-updated", Map.of(
                "guardianId", guardianId.toString(),
                "primaryContact", guardian.primaryContact(),
                "relationshipCode", normalizeRelationshipCode(guardian.relationshipCode())));
        return toDetail(findAccessible(user, applicationId, List.of(MANAGE)));
    }

    @Transactional
    ApplicationDetailResponse addDocument(
            AuthenticatedUser user,
            UUID applicationId,
            AddApplicationDocumentRequest request) {
        StoredApplication existing = findAccessible(user, applicationId, List.of(MANAGE));
        if (existing.status() == ApplicationStatus.ENROLLED) {
            throw conflict("APPLICATION_DOCUMENTS_LOCKED", "The enrolled application checklist is locked.");
        }
        Instant now = Instant.now(clock);
        try {
            jdbc.update("""
                    INSERT INTO admission_application_documents (
                        id, organisation_id, school_id, application_id, document_code,
                        display_name, required, status, created_at, updated_at, row_version
                    ) VALUES (
                        :id, :organisationId, :schoolId, :applicationId, :documentCode,
                        :displayName, :required, 'MISSING', :now, :now, 0
                    )
                    """, applicationParameters(existing)
                    .addValue("id", UUID.randomUUID())
                    .addValue("documentCode", request.documentCode())
                    .addValue("displayName", clean(request.displayName()))
                    .addValue("required", request.required())
                    .addValue("now", now));
        } catch (DataIntegrityViolationException exception) {
            throw conflict("APPLICATION_DOCUMENT_DUPLICATE", "This document is already on the checklist.");
        }
        touchApplication(existing, now);
        auditMutation(user, existing, "admissions.application.document-added", Map.of(
                "documentCode", request.documentCode(),
                "required", request.required()));
        return toDetail(findAccessible(user, applicationId, List.of(MANAGE)));
    }

    @Transactional
    ApplicationDetailResponse reviewDocument(
            AuthenticatedUser user,
            UUID applicationId,
            UUID documentId,
            ReviewApplicationDocumentRequest request) {
        StoredApplication existing = findAccessible(user, applicationId, List.of(MANAGE));
        StoredDocument document = findDocument(existing, documentId);
        if (request.expectedVersion() == null || request.expectedVersion() != document.version()) {
            throw conflict("APPLICATION_DOCUMENT_VERSION_CONFLICT", "This document changed after it was opened.");
        }
        if (existing.status() == ApplicationStatus.ENROLLED) {
            throw conflict("APPLICATION_DOCUMENTS_LOCKED", "The enrolled application checklist is locked.");
        }
        validateDocumentReview(document, request);
        Instant now = Instant.now(clock);
        DocumentReceivedMethod method = request.status() == DocumentReviewStatus.MISSING
                        || request.status() == DocumentReviewStatus.WAIVED
                ? null
                : request.receivedMethod() == null ? document.receivedMethod() : request.receivedMethod();
        Instant receivedAt = Set.of(
                DocumentReviewStatus.RECEIVED,
                DocumentReviewStatus.VERIFIED,
                DocumentReviewStatus.REJECTED).contains(request.status())
                ? document.receivedAt() == null ? now : document.receivedAt()
                : null;
        Instant verifiedAt = request.status() == DocumentReviewStatus.VERIFIED ? now : null;
        UUID verifier = request.status() == DocumentReviewStatus.VERIFIED ? existing.actorMembershipId() : null;
        int updated = jdbc.update("""
                UPDATE admission_application_documents
                SET status = :status,
                    received_method = :receivedMethod,
                    received_at = :receivedAt,
                    verified_at = :verifiedAt,
                    verified_by_membership_id = :verifier,
                    review_note = :reviewNote,
                    updated_at = :now,
                    row_version = row_version + 1
                WHERE id = :documentId
                  AND application_id = :applicationId
                  AND organisation_id = :organisationId
                  AND school_id = :schoolId
                  AND row_version = :expectedVersion
                """, applicationParameters(existing)
                .addValue("documentId", documentId)
                .addValue("status", request.status().name())
                .addValue("receivedMethod", method == null ? null : method.name())
                .addValue("receivedAt", receivedAt)
                .addValue("verifiedAt", verifiedAt)
                .addValue("verifier", verifier)
                .addValue("reviewNote", nullableClean(request.reviewNote()))
                .addValue("now", now)
                .addValue("expectedVersion", request.expectedVersion()));
        if (updated != 1) {
            throw conflict("APPLICATION_DOCUMENT_VERSION_CONFLICT", "This document changed after it was opened.");
        }
        touchApplication(existing, now);
        auditMutation(user, existing, "admissions.application.document-reviewed", Map.of(
                "documentCode", document.documentCode(),
                "previousStatus", document.status().name(),
                "status", request.status().name()));
        return toDetail(findAccessible(user, applicationId, List.of(MANAGE)));
    }

    @Transactional
    public ApplicationDetailResponse decide(
            AuthenticatedUser user,
            UUID applicationId,
            RecordAdmissionDecisionRequest request) {
        StoredApplication existing = findAccessible(user, applicationId, List.of(DECIDE));
        requireVersion(existing, request.expectedVersion());
        if (!existing.status().canReceiveDecision()) {
            throw conflict("APPLICATION_DECISION_NOT_ALLOWED", "This application is not in a decision-ready state.");
        }
        validateDecision(request);
        Instant now = Instant.now(clock);
        UUID decisionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO admission_decisions (
                    id, organisation_id, school_id, application_id, decision, reason_code,
                    internal_summary, offer_expires_on, decided_by_membership_id,
                    supersedes_decision_id, created_at
                ) VALUES (
                    :decisionId, :organisationId, :schoolId, :applicationId, :decision, :reasonCode,
                    :summary, :offerExpiresOn, :actorMembershipId, :supersedesDecisionId, :now
                )
                """, applicationParameters(existing)
                .addValue("decisionId", decisionId)
                .addValue("decision", request.decision().name())
                .addValue("reasonCode", clean(request.reasonCode()).toUpperCase(Locale.ROOT))
                .addValue("summary", nullableClean(request.internalSummary()))
                .addValue("offerExpiresOn", request.offerExpiresOn())
                .addValue("actorMembershipId", existing.actorMembershipId())
                .addValue("supersedesDecisionId", existing.currentDecisionId())
                .addValue("now", now));
        ApplicationStatus nextStatus = request.decision().applicationStatus();
        int updated = jdbc.update("""
                UPDATE admission_applications
                SET current_decision_id = :decisionId,
                    status = :status,
                    accepted_at = NULL,
                    withdrawn_at = NULL,
                    updated_at = :now,
                    row_version = row_version + 1
                WHERE id = :applicationId
                  AND organisation_id = :organisationId
                  AND school_id = :schoolId
                  AND row_version = :expectedVersion
                """, applicationParameters(existing)
                .addValue("decisionId", decisionId)
                .addValue("status", nextStatus.name())
                .addValue("now", now)
                .addValue("expectedVersion", request.expectedVersion()));
        requireSingleUpdate(updated);
        appendOutbox(existing.id(), "admissions.application.decision-recorded.v1", nextStatus.name(), existing.version() + 1, now);
        auditMutation(user, existing, "admissions.application.decision-recorded", Map.of(
                "decision", request.decision().name(),
                "reasonCode", clean(request.reasonCode()).toUpperCase(Locale.ROOT),
                "superseded", existing.currentDecisionId() != null,
                "version", existing.version() + 1));
        return toDetail(findAccessible(user, applicationId, List.of(DECIDE)));
    }

    @Transactional(readOnly = true)
    public ApplicationOptionsResponse options(AuthenticatedUser user, UUID applicationId) {
        StoredApplication existing = findAccessible(user, applicationId, List.of(MANAGE, "guardian.read"));
        Instant now = Instant.now(clock);
        MapSqlParameterSource scope = applicationParameters(existing).addValue("now", now);
        List<ApplicationCampusOption> campuses = jdbc.query("""
                SELECT id, display_name
                FROM campuses
                WHERE organisation_id = :organisationId
                  AND school_id = :schoolId
                  AND status = 'ACTIVE'
                ORDER BY display_name, id
                """, scope, (result, row) -> new ApplicationCampusOption(
                result.getObject("id", UUID.class), result.getString("display_name")));
        List<ApplicationSessionOption> sessions = jdbc.query("""
                SELECT session.id, session.name, session.starts_on, session.ends_on, session.status,
                       session.rte_class_one_seat_limit, session.rte_min_age_years, session.rte_max_age_years,
                       (SELECT count(*) FROM learners learner WHERE learner.organisation_id = session.organisation_id
                            AND learner.school_id = session.school_id AND learner.rte_admission_session_id = session.id) rte_seats_used
                FROM academic_sessions session
                WHERE organisation_id = :organisationId
                  AND school_id = :schoolId
                  AND status IN ('PLANNED', 'ACTIVE')
                ORDER BY starts_on DESC, id
                """, scope, (result, row) -> new ApplicationSessionOption(
                result.getObject("id", UUID.class),
                result.getString("name"),
                result.getObject("starts_on", LocalDate.class),
                result.getObject("ends_on", LocalDate.class),
                result.getString("status"), result.getInt("rte_class_one_seat_limit"),
                result.getLong("rte_seats_used"), result.getInt("rte_min_age_years"), result.getInt("rte_max_age_years")));
        List<EnquiryAssignee> assignees = jdbc.query("""
                SELECT DISTINCT membership.id, membership.school_id, account.display_name
                FROM memberships membership
                JOIN user_accounts account ON account.id = membership.user_id
                JOIN membership_roles membership_role ON membership_role.membership_id = membership.id
                JOIN role_capabilities capability ON capability.role_id = membership_role.role_id
                WHERE membership.organisation_id = :organisationId
                  AND membership.school_id = :schoolId
                  AND membership.status = 'ACTIVE'
                  AND membership.valid_from <= :now
                  AND (membership.valid_until IS NULL OR membership.valid_until > :now)
                  AND account.status = 'ACTIVE'
                  AND capability.capability_code = :applicationRead
                ORDER BY account.display_name, membership.id
                """, scope.addValue("applicationRead", READ), (result, row) -> new EnquiryAssignee(
                result.getObject("id", UUID.class),
                result.getObject("school_id", UUID.class),
                result.getString("display_name")));
        return new ApplicationOptionsResponse(campuses, sessions, assignees, guardianMatches(existing));
    }

    @Transactional
    public EnrolmentHandoffResponse enrol(
            AuthenticatedUser user,
            UUID applicationId,
            EnrolApplicationRequest request) {
        StoredApplication existing = findAccessible(user, applicationId, HANDOFF_CAPABILITIES);
        EnrolmentSummary alreadyEnrolled = loadEnrolment(existing);
        if (alreadyEnrolled != null && existing.status() == ApplicationStatus.ENROLLED) {
            ApplicationDetailResponse detail = toDetail(existing);
            return new EnrolmentHandoffResponse(detail, alreadyEnrolled);
        }
        requireVersion(existing, request.expectedVersion());
        if (existing.status() != ApplicationStatus.ACCEPTED) {
            throw conflict("APPLICATION_NOT_ACCEPTED", "Only an accepted application can be handed off to enrolment.");
        }
        validateHandoffConfiguration(existing, request);
        if (existing.admissionType() == AdmissionType.RTE
                && !request.academicSessionId().equals(existing.targetAcademicSessionId())) {
            throw conflict("RTE_SESSION_MISMATCH", "Use the original RTE application session for admission.");
        }
        validateRte(existing.organisationId(), existing.schoolId(), existing.admissionType(), existing.targetGradeCode(),
                request.academicSessionId(), existing.birthDate(), existing.rteReference(), true);
        ensureRequiredDocumentsVerified(existing);
        List<StoredGuardian> applicationGuardians = loadStoredGuardians(existing);
        if (applicationGuardians.isEmpty()) {
            throw conflict("APPLICATION_GUARDIAN_REQUIRED", "At least one guardian is required before enrolment.");
        }
        Map<UUID, UUID> requestedMatches = normalizeGuardianMatches(request.guardianMatches(), applicationGuardians);
        Instant now = Instant.now(clock);
        UUID learnerId = UUID.randomUUID();
        UUID enrolmentId = UUID.randomUUID();
        try {
            jdbc.update("""
                INSERT INTO learners (
                    id, organisation_id, school_id, origin_application_id, legal_name,
                    preferred_name, birth_date, gender_code, gender_description,
                    status, created_at, updated_at, row_version
                ) VALUES (
                    :learnerId, :organisationId, :schoolId, :applicationId, :legalName,
                    :preferredName, :birthDate, :gender, :genderDescription,
                    'ACTIVE', :now, :now, 0
                )
                """, applicationParameters(existing)
                .addValue("learnerId", learnerId)
                .addValue("legalName", existing.applicantLegalName())
                .addValue("preferredName", existing.applicantPreferredName())
                .addValue("birthDate", existing.birthDate())
                .addValue("gender", existing.gender().name())
                .addValue("genderDescription", existing.genderDescription())
                .addValue("now", now));
        } catch (DataIntegrityViolationException exception) {
            if (existing.admissionType() == AdmissionType.RTE
                    && exception.getMostSpecificCause().getMessage() != null
                    && exception.getMostSpecificCause().getMessage().contains("learners_rte_reference_unique")) {
                throw conflict("RTE_REFERENCE_DUPLICATE", "This RTE reference has already been admitted.");
            }
            throw exception;
        }

        Set<UUID> linkedGuardianIds = new HashSet<>();
        for (StoredGuardian source : applicationGuardians) {
            UUID guardianId = resolveGuardian(existing, source, requestedMatches.get(source.id()), now);
            if (!linkedGuardianIds.add(guardianId)) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "GUARDIAN_MATCH_DUPLICATE",
                        "Two application contacts cannot be linked to the same guardian in one handoff.");
            }
            jdbc.update("""
                    INSERT INTO learner_guardian_relationships (
                        learner_id, guardian_id, organisation_id, school_id, relationship_code,
                        primary_contact, pickup_authorized, portal_access_status,
                        created_at, updated_at, row_version
                    ) VALUES (
                        :learnerId, :guardianId, :organisationId, :schoolId, :relationshipCode,
                        :primaryContact, :pickupAuthorized, 'NOT_INVITED', :now, :now, 0
                    )
                    """, applicationParameters(existing)
                    .addValue("learnerId", learnerId)
                    .addValue("guardianId", guardianId)
                    .addValue("relationshipCode", source.relationshipCode())
                    .addValue("primaryContact", source.primaryContact())
                    .addValue("pickupAuthorized", source.pickupAuthorized())
                    .addValue("now", now));
        }

        String admissionNumber = nullableClean(request.admissionNumber());
        try {
            jdbc.update("""
                    INSERT INTO student_enrolments (
                        id, organisation_id, school_id, campus_id, academic_session_id,
                        learner_id, origin_application_id, grade_code, admission_number,
                        status, placement_status, starts_on, created_at, updated_at, row_version
                    ) VALUES (
                        :enrolmentId, :organisationId, :schoolId, :campusId, :sessionId,
                        :learnerId, :applicationId, :gradeCode, :admissionNumber,
                        'ACTIVE', 'PENDING', :startsOn, :now, :now, 0
                    )
                    """, applicationParameters(existing)
                    .addValue("enrolmentId", enrolmentId)
                    .addValue("campusId", request.campusId())
                    .addValue("sessionId", request.academicSessionId())
                    .addValue("learnerId", learnerId)
                    .addValue("gradeCode", existing.targetGradeCode())
                    .addValue("admissionNumber", admissionNumber)
                    .addValue("startsOn", request.startsOn())
                    .addValue("now", now));
        } catch (DataIntegrityViolationException exception) {
            throw conflict(
                    "ADMISSION_NUMBER_CONFLICT",
                    "The admission number is already in use or the application was already handed off.");
        }

        int updated = jdbc.update("""
                UPDATE admission_applications
                SET status = 'ENROLLED',
                    target_campus_id = :campusId,
                    target_academic_session_id = :sessionId,
                    enrolled_at = :now,
                    withdrawn_at = NULL,
                    updated_at = :now,
                    row_version = row_version + 1
                WHERE id = :applicationId
                  AND organisation_id = :organisationId
                  AND school_id = :schoolId
                  AND row_version = :expectedVersion
                """, applicationParameters(existing)
                .addValue("campusId", request.campusId())
                .addValue("sessionId", request.academicSessionId())
                .addValue("now", now)
                .addValue("expectedVersion", request.expectedVersion()));
        requireSingleUpdate(updated);
        appendOutbox(existing.id(), "admissions.application.enrolled.v1", "ENROLLED", existing.version() + 1, now);
        auditMutation(user, existing, "admissions.application.enrolled", Map.of(
                "guardianCount", applicationGuardians.size(),
                "matchedGuardianCount", requestedMatches.values().stream().filter(value -> value != null).count(),
                "admissionNumberAssigned", admissionNumber != null,
                "placementStatus", "PENDING"));
        StoredApplication enrolled = findAccessible(user, applicationId, HANDOFF_CAPABILITIES);
        EnrolmentSummary summary = loadEnrolment(enrolled);
        return new EnrolmentHandoffResponse(toDetail(enrolled), summary);
    }

    private CreationScope resolveCreationScope(
            AuthenticatedUser user,
            CreateApplicationRequest request,
            Instant now) {
        UUID organisationId = requireOrganisation(user);
        if (request.enquiryId() != null) {
            List<StoredEnquiryForApplication> enquiries = jdbc.query("""
                    SELECT enquiry.id, enquiry.school_id, enquiry.name, enquiry.email_normalized,
                           enquiry.phone, enquiry.status, actor_membership.id AS actor_membership_id
                    FROM public_enquiries enquiry
                    JOIN LATERAL (
                        SELECT membership.id
                        FROM memberships membership
                        JOIN membership_roles membership_role ON membership_role.membership_id = membership.id
                        JOIN role_capabilities capability ON capability.role_id = membership_role.role_id
                        WHERE membership.user_id = :actorUserId
                          AND membership.organisation_id = enquiry.organisation_id
                          AND membership.school_id = enquiry.school_id
                          AND membership.status = 'ACTIVE'
                          AND membership.valid_from <= :now
                          AND (membership.valid_until IS NULL OR membership.valid_until > :now)
                          AND capability.capability_code = :requiredCapability
                        ORDER BY membership.created_at, membership.id
                        LIMIT 1
                    ) actor_membership ON true
                    WHERE enquiry.id = :enquiryId
                      AND enquiry.organisation_id = :organisationId
                    """, new MapSqlParameterSource()
                    .addValue("actorUserId", user.userId())
                    .addValue("organisationId", organisationId)
                    .addValue("enquiryId", request.enquiryId())
                    .addValue("requiredCapability", MANAGE)
                    .addValue("now", now), (result, row) -> new StoredEnquiryForApplication(
                    result.getObject("id", UUID.class),
                    result.getObject("school_id", UUID.class),
                    result.getString("name"),
                    result.getString("email_normalized"),
                    result.getString("phone"),
                    EnquiryStatus.valueOf(result.getString("status")),
                    result.getObject("actor_membership_id", UUID.class)));
            if (enquiries.isEmpty()) {
                throw new ApiException(HttpStatus.NOT_FOUND, "ENQUIRY_NOT_FOUND", "The enquiry was not found.");
            }
            StoredEnquiryForApplication enquiry = enquiries.getFirst();
            if (request.schoolId() != null && !request.schoolId().equals(enquiry.schoolId())) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "APPLICATION_SCHOOL_MISMATCH",
                        "The application school must match the enquiry school.");
            }
            if (enquiry.status() != EnquiryStatus.QUALIFIED) {
                throw conflict(
                        "ENQUIRY_NOT_QUALIFIED",
                        "Qualify the enquiry before starting an admission application.");
            }
            return new CreationScope(
                    organisationId,
                    enquiry.schoolId(),
                    enquiry.actorMembershipId(),
                    enquiry);
        }
        if (request.schoolId() == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "APPLICATION_SCHOOL_REQUIRED",
                    "Choose a school for an application that is not linked to an enquiry.");
        }
        UUID actorMembershipId = actorMembership(user, request.schoolId(), List.of(MANAGE), now);
        return new CreationScope(organisationId, request.schoolId(), actorMembershipId, null);
    }

    private StoredApplication findAccessible(
            AuthenticatedUser user,
            UUID applicationId,
            List<String> requiredCapabilities) {
        UUID organisationId = requireOrganisation(user);
        Instant now = Instant.now(clock);
        List<StoredApplication> applications = jdbc.query("""
                SELECT application.id, application.organisation_id, application.school_id,
                       application.enquiry_id, application.target_campus_id, campus.display_name AS campus_name,
                       application.target_academic_session_id, session.name AS session_name,
                       application.intake_session_label, application.target_grade_code,
                       application.applicant_legal_name, application.applicant_preferred_name,
                       application.birth_date, application.gender_code, application.gender_description,
                       application.status, application.assigned_membership_id,
                       assignee.display_name AS assignee_name, application.current_decision_id,
                       application.submitted_at, application.accepted_at, application.withdrawn_at,
                       application.enrolled_at, application.created_at, application.updated_at,
                       application.admission_type, application.rte_reference,
                       application.row_version, application.request_fingerprint,
                       actor_membership.id AS actor_membership_id
                FROM admission_applications application
                JOIN LATERAL (
                    SELECT membership.id
                    FROM memberships membership
                    JOIN membership_roles membership_role ON membership_role.membership_id = membership.id
                    JOIN role_capabilities capability ON capability.role_id = membership_role.role_id
                    WHERE membership.user_id = :actorUserId
                      AND membership.organisation_id = application.organisation_id
                      AND membership.school_id = application.school_id
                      AND membership.status = 'ACTIVE'
                      AND membership.valid_from <= :now
                      AND (membership.valid_until IS NULL OR membership.valid_until > :now)
                      AND capability.capability_code IN (:requiredCapabilities)
                    GROUP BY membership.id, membership.created_at
                    HAVING count(DISTINCT capability.capability_code) = :capabilityCount
                    ORDER BY membership.created_at, membership.id
                    LIMIT 1
                ) actor_membership ON true
                LEFT JOIN campuses campus ON campus.id = application.target_campus_id
                LEFT JOIN academic_sessions session ON session.id = application.target_academic_session_id
                LEFT JOIN memberships assigned ON assigned.id = application.assigned_membership_id
                LEFT JOIN user_accounts assignee ON assignee.id = assigned.user_id
                WHERE application.id = :applicationId
                  AND application.organisation_id = :organisationId
                """, new MapSqlParameterSource()
                .addValue("actorUserId", user.userId())
                .addValue("organisationId", organisationId)
                .addValue("applicationId", applicationId)
                .addValue("requiredCapabilities", requiredCapabilities)
                .addValue("capabilityCount", requiredCapabilities.size())
                .addValue("now", now), AdmissionApplicationService::mapStoredApplication);
        if (applications.isEmpty()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "APPLICATION_NOT_FOUND",
                    "The admission application was not found.");
        }
        return applications.getFirst();
    }

    private ApplicationDetailResponse toDetail(StoredApplication application) {
        List<ApplicationGuardianResponse> guardians = jdbc.query("""
                SELECT id, display_name, relationship_code, email_normalized, phone,
                       primary_contact, pickup_authorized, matched_guardian_id, row_version
                FROM admission_application_guardians
                WHERE application_id = :applicationId
                  AND organisation_id = :organisationId
                  AND school_id = :schoolId
                ORDER BY primary_contact DESC, created_at, id
                """, applicationParameters(application), (result, row) -> new ApplicationGuardianResponse(
                result.getObject("id", UUID.class),
                result.getString("display_name"),
                result.getString("relationship_code"),
                result.getString("email_normalized"),
                result.getString("phone"),
                result.getBoolean("primary_contact"),
                result.getBoolean("pickup_authorized"),
                result.getObject("matched_guardian_id", UUID.class),
                result.getLong("row_version")));
        List<ApplicationDocumentResponse> documents = jdbc.query("""
                SELECT document.id, document.document_code, document.display_name, document.required,
                       document.status, document.received_method, document.received_at,
                       document.verified_at, verifier.display_name AS verifier_name,
                       document.review_note, document.row_version
                FROM admission_application_documents document
                LEFT JOIN memberships verifier_membership
                  ON verifier_membership.id = document.verified_by_membership_id
                LEFT JOIN user_accounts verifier ON verifier.id = verifier_membership.user_id
                WHERE document.application_id = :applicationId
                  AND document.organisation_id = :organisationId
                  AND document.school_id = :schoolId
                ORDER BY document.required DESC, document.display_name, document.id
                """, applicationParameters(application), (result, row) -> new ApplicationDocumentResponse(
                result.getObject("id", UUID.class),
                result.getString("document_code"),
                result.getString("display_name"),
                result.getBoolean("required"),
                DocumentReviewStatus.valueOf(result.getString("status")),
                result.getString("received_method") == null
                        ? null : DocumentReceivedMethod.valueOf(result.getString("received_method")),
                instant(result, "received_at"),
                instant(result, "verified_at"),
                result.getString("verifier_name"),
                result.getString("review_note"),
                result.getLong("row_version")));
        List<AdmissionDecisionResponse> decisions = jdbc.query("""
                SELECT decision.id, decision.decision, decision.reason_code,
                       decision.internal_summary, decision.offer_expires_on,
                       account.display_name AS decided_by_name, decision.supersedes_decision_id,
                       decision.created_at
                FROM admission_decisions decision
                JOIN memberships membership ON membership.id = decision.decided_by_membership_id
                JOIN user_accounts account ON account.id = membership.user_id
                WHERE decision.application_id = :applicationId
                  AND decision.organisation_id = :organisationId
                  AND decision.school_id = :schoolId
                ORDER BY decision.created_at DESC, decision.id DESC
                """, applicationParameters(application), (result, row) -> new AdmissionDecisionResponse(
                result.getObject("id", UUID.class),
                AdmissionDecision.valueOf(result.getString("decision")),
                result.getString("reason_code"),
                result.getString("internal_summary"),
                result.getObject("offer_expires_on", LocalDate.class),
                result.getString("decided_by_name"),
                result.getObject("supersedes_decision_id", UUID.class),
                instant(result, "created_at")));
        return new ApplicationDetailResponse(
                application.id(),
                application.schoolId(),
                applicationReference(application.id()),
                application.enquiryId(),
                application.enquiryId() == null ? null : EnquiryService.reference(application.enquiryId()),
                application.targetCampusId(),
                application.targetCampusName(),
                application.targetAcademicSessionId(),
                application.targetAcademicSessionName(),
                application.intakeSessionLabel(),
                application.targetGradeCode(),
                application.applicantLegalName(),
                application.applicantPreferredName(),
                application.birthDate(),
                application.gender(),
                application.genderDescription(),
                application.status(),
                application.assignedMembershipId() == null ? null : new EnquiryAssignee(
                        application.assignedMembershipId(),
                        application.schoolId(),
                        application.assigneeName()),
                application.submittedAt(),
                application.acceptedAt(),
                application.withdrawnAt(),
                application.enrolledAt(),
                application.createdAt(),
                application.updatedAt(),
                application.version(),
                guardians,
                documents,
                decisions,
                loadEnrolment(application), application.admissionType(), application.rteReference());
    }

    private List<ApplicationGuardianMatches> guardianMatches(StoredApplication application) {
        List<StoredGuardian> sources = loadStoredGuardians(application);
        List<ApplicationGuardianMatches> matches = new ArrayList<>();
        for (StoredGuardian source : sources) {
            List<GuardianMatchCandidate> candidates = jdbc.query("""
                    SELECT guardian.id, guardian.display_name, guardian.email_normalized, guardian.phone
                    FROM guardians guardian
                    WHERE guardian.organisation_id = :organisationId
                      AND guardian.school_id = :schoolId
                      AND guardian.status = 'ACTIVE'
                      AND (
                          (CAST(:email AS varchar) IS NOT NULL AND guardian.email_normalized = :email)
                          OR (CAST(:phone AS varchar) IS NOT NULL AND guardian.phone = :phone)
                      )
                    ORDER BY guardian.display_name, guardian.id
                    LIMIT 10
                    """, applicationParameters(application)
                    .addValue("email", source.email())
                    .addValue("phone", source.phone()), (result, row) -> new GuardianMatchCandidate(
                    result.getObject("id", UUID.class),
                    result.getString("display_name"),
                    maskEmail(result.getString("email_normalized")),
                    maskPhone(result.getString("phone"))));
            matches.add(new ApplicationGuardianMatches(source.id(), source.displayName(), candidates));
        }
        return matches;
    }

    private EnrolmentSummary loadEnrolment(StoredApplication application) {
        List<EnrolmentSummary> enrolments = jdbc.query("""
                SELECT enrolment.id, enrolment.learner_id, enrolment.admission_number,
                       enrolment.campus_id, campus.display_name AS campus_name,
                       enrolment.academic_session_id, session.name AS session_name,
                       enrolment.grade_code, enrolment.placement_status, enrolment.starts_on
                FROM student_enrolments enrolment
                JOIN campuses campus ON campus.id = enrolment.campus_id
                JOIN academic_sessions session ON session.id = enrolment.academic_session_id
                WHERE enrolment.origin_application_id = :applicationId
                  AND enrolment.organisation_id = :organisationId
                  AND enrolment.school_id = :schoolId
                """, applicationParameters(application), (result, row) -> {
                UUID learnerId = result.getObject("learner_id", UUID.class);
                UUID enrolmentId = result.getObject("id", UUID.class);
                return new EnrolmentSummary(
                        learnerId,
                        enrolmentId,
                        learnerReference(learnerId),
                        enrolmentReference(enrolmentId),
                        result.getString("admission_number"),
                        result.getObject("campus_id", UUID.class),
                        result.getString("campus_name"),
                        result.getObject("academic_session_id", UUID.class),
                        result.getString("session_name"),
                        result.getString("grade_code"),
                        result.getString("placement_status"),
                        result.getObject("starts_on", LocalDate.class));
            });
        return enrolments.isEmpty() ? null : enrolments.getFirst();
    }

    private List<StoredGuardian> loadStoredGuardians(StoredApplication application) {
        return jdbc.query("""
                SELECT id, display_name, relationship_code, email_normalized, phone,
                       primary_contact, pickup_authorized, matched_guardian_id, row_version
                FROM admission_application_guardians
                WHERE application_id = :applicationId
                  AND organisation_id = :organisationId
                  AND school_id = :schoolId
                ORDER BY primary_contact DESC, created_at, id
                """, applicationParameters(application), (result, row) -> new StoredGuardian(
                result.getObject("id", UUID.class),
                result.getString("display_name"),
                result.getString("relationship_code"),
                result.getString("email_normalized"),
                result.getString("phone"),
                result.getBoolean("primary_contact"),
                result.getBoolean("pickup_authorized"),
                result.getObject("matched_guardian_id", UUID.class),
                result.getLong("row_version")));
    }

    private UUID resolveGuardian(
            StoredApplication application,
            StoredGuardian source,
            UUID requestedGuardianId,
            Instant now) {
        UUID existingGuardianId = source.matchedGuardianId() != null
                ? source.matchedGuardianId() : requestedGuardianId;
        if (source.matchedGuardianId() != null
                && requestedGuardianId != null
                && !source.matchedGuardianId().equals(requestedGuardianId)) {
            throw conflict("GUARDIAN_MATCH_CHANGED", "The guardian match changed after the application was opened.");
        }
        if (existingGuardianId != null) {
            Long matchCount = jdbc.queryForObject("""
                    SELECT count(*)
                    FROM guardians guardian
                    WHERE guardian.id = :guardianId
                      AND guardian.organisation_id = :organisationId
                      AND guardian.school_id = :schoolId
                      AND guardian.status = 'ACTIVE'
                      AND (
                          (CAST(:email AS varchar) IS NOT NULL AND guardian.email_normalized = :email)
                          OR (CAST(:phone AS varchar) IS NOT NULL AND guardian.phone = :phone)
                      )
                    """, applicationParameters(application)
                    .addValue("guardianId", existingGuardianId)
                    .addValue("email", source.email())
                    .addValue("phone", source.phone()), Long.class);
            if (matchCount == null || matchCount != 1) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "GUARDIAN_MATCH_INVALID",
                        "The selected guardian is not an exact contact match in this school.");
            }
            if (source.matchedGuardianId() == null) {
                jdbc.update("""
                        UPDATE admission_application_guardians
                        SET matched_guardian_id = :guardianId,
                            updated_at = :now,
                            row_version = row_version + 1
                        WHERE id = :sourceId
                          AND application_id = :applicationId
                          AND organisation_id = :organisationId
                          AND school_id = :schoolId
                          AND matched_guardian_id IS NULL
                        """, applicationParameters(application)
                        .addValue("guardianId", existingGuardianId)
                        .addValue("sourceId", source.id())
                        .addValue("now", now));
            }
            return existingGuardianId;
        }
        UUID guardianId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO guardians (
                    id, organisation_id, school_id, display_name, email_normalized,
                    phone, status, created_at, updated_at, row_version
                ) VALUES (
                    :guardianId, :organisationId, :schoolId, :displayName, :email,
                    :phone, 'ACTIVE', :now, :now, 0
                )
                """, applicationParameters(application)
                .addValue("guardianId", guardianId)
                .addValue("displayName", source.displayName())
                .addValue("email", source.email())
                .addValue("phone", source.phone())
                .addValue("now", now));
        jdbc.update("""
                UPDATE admission_application_guardians
                SET matched_guardian_id = :guardianId,
                    updated_at = :now,
                    row_version = row_version + 1
                WHERE id = :sourceId
                  AND application_id = :applicationId
                  AND organisation_id = :organisationId
                  AND school_id = :schoolId
                """, applicationParameters(application)
                .addValue("guardianId", guardianId)
                .addValue("sourceId", source.id())
                .addValue("now", now));
        return guardianId;
    }

    private void validateHandoffConfiguration(
            StoredApplication application,
            EnrolApplicationRequest request) {
        List<SessionConfiguration> configurations = jdbc.query("""
                SELECT session.starts_on, session.ends_on
                FROM academic_sessions session
                JOIN campuses campus
                  ON campus.organisation_id = session.organisation_id
                 AND campus.school_id = session.school_id
                WHERE session.id = :sessionId
                  AND campus.id = :campusId
                  AND session.organisation_id = :organisationId
                  AND session.school_id = :schoolId
                  AND session.status IN ('PLANNED', 'ACTIVE')
                  AND campus.status = 'ACTIVE'
                """, applicationParameters(application)
                .addValue("sessionId", request.academicSessionId())
                .addValue("campusId", request.campusId()), (result, row) -> new SessionConfiguration(
                result.getObject("starts_on", LocalDate.class),
                result.getObject("ends_on", LocalDate.class)));
        if (configurations.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "ENROLMENT_CONFIGURATION_INVALID",
                    "Choose an active campus and a planned or active academic session from this school.");
        }
        SessionConfiguration session = configurations.getFirst();
        if (request.startsOn().isBefore(session.startsOn()) || request.startsOn().isAfter(session.endsOn())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "ENROLMENT_START_OUTSIDE_SESSION",
                    "The enrolment start date must fall inside the selected academic session.");
        }
    }

    private void ensureRequiredDocumentsVerified(StoredApplication application) {
        Long incomplete = jdbc.queryForObject("""
                SELECT count(*)
                FROM admission_application_documents
                WHERE application_id = :applicationId
                  AND organisation_id = :organisationId
                  AND school_id = :schoolId
                  AND required
                  AND status <> 'VERIFIED'
                """, applicationParameters(application), Long.class);
        if (incomplete != null && incomplete > 0) {
            throw conflict(
                    "APPLICATION_DOCUMENTS_INCOMPLETE",
                    "Verify every required document before enrolment handoff.");
        }
    }

    private Map<UUID, UUID> normalizeGuardianMatches(
            List<GuardianMatchInput> requested,
            List<StoredGuardian> sources) {
        Set<UUID> sourceIds = sources.stream().map(StoredGuardian::id).collect(Collectors.toSet());
        Map<UUID, UUID> matches = new HashMap<>();
        if (requested == null) return matches;
        for (GuardianMatchInput match : requested) {
            if (!sourceIds.contains(match.applicationGuardianId())) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "GUARDIAN_MATCH_SOURCE_INVALID",
                        "A guardian match does not belong to this application.");
            }
            if (matches.containsKey(match.applicationGuardianId())) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "GUARDIAN_MATCH_SOURCE_DUPLICATE",
                        "Each application guardian may be matched only once.");
            }
            matches.put(match.applicationGuardianId(), match.existingGuardianId());
        }
        return matches;
    }

    private void validateTargetOptions(
            UUID organisationId,
            UUID schoolId,
            UUID campusId,
            UUID sessionId) {
        if (campusId != null) {
            Long count = jdbc.queryForObject("""
                    SELECT count(*) FROM campuses
                    WHERE id = :campusId
                      AND organisation_id = :organisationId
                      AND school_id = :schoolId
                      AND status = 'ACTIVE'
                    """, new MapSqlParameterSource()
                    .addValue("campusId", campusId)
                    .addValue("organisationId", organisationId)
                    .addValue("schoolId", schoolId), Long.class);
            if (count == null || count != 1) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "APPLICATION_CAMPUS_INVALID", "Choose an active campus from this school.");
            }
        }
        if (sessionId != null) {
            Long count = jdbc.queryForObject("""
                    SELECT count(*) FROM academic_sessions
                    WHERE id = :sessionId
                      AND organisation_id = :organisationId
                      AND school_id = :schoolId
                      AND status IN ('PLANNED', 'ACTIVE')
                    """, new MapSqlParameterSource()
                    .addValue("sessionId", sessionId)
                    .addValue("organisationId", organisationId)
                    .addValue("schoolId", schoolId), Long.class);
            if (count == null || count != 1) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "APPLICATION_SESSION_INVALID", "Choose a planned or active academic session from this school.");
            }
        }
    }

    private UUID actorMembership(
            AuthenticatedUser user,
            UUID schoolId,
            List<String> capabilities,
            Instant now) {
        List<UUID> matches = jdbc.query("""
                SELECT membership.id
                FROM memberships membership
                JOIN membership_roles membership_role ON membership_role.membership_id = membership.id
                JOIN role_capabilities capability ON capability.role_id = membership_role.role_id
                WHERE membership.user_id = :actorUserId
                  AND membership.organisation_id = :organisationId
                  AND membership.school_id = :schoolId
                  AND membership.status = 'ACTIVE'
                  AND membership.valid_from <= :now
                  AND (membership.valid_until IS NULL OR membership.valid_until > :now)
                  AND capability.capability_code IN (:requiredCapabilities)
                GROUP BY membership.id, membership.created_at
                HAVING count(DISTINCT capability.capability_code) = :capabilityCount
                ORDER BY membership.created_at, membership.id
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("actorUserId", user.userId())
                .addValue("organisationId", requireOrganisation(user))
                .addValue("schoolId", schoolId)
                .addValue("requiredCapabilities", capabilities)
                .addValue("capabilityCount", capabilities.size())
                .addValue("now", now), (result, row) -> result.getObject("id", UUID.class));
        if (matches.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND", "The admission application was not found.");
        }
        return matches.getFirst();
    }

    private void requireMembershipCapabilities(
            AuthenticatedUser user,
            UUID schoolId,
            UUID membershipId,
            List<String> capabilities) {
        Long count = jdbc.queryForObject("""
                SELECT count(DISTINCT capability.capability_code)
                FROM memberships membership
                JOIN membership_roles membership_role ON membership_role.membership_id = membership.id
                JOIN role_capabilities capability ON capability.role_id = membership_role.role_id
                JOIN user_accounts account ON account.id = membership.user_id
                WHERE membership.id = :membershipId
                  AND membership.organisation_id = :organisationId
                  AND membership.school_id = :schoolId
                  AND membership.status = 'ACTIVE'
                  AND membership.valid_from <= :now
                  AND (membership.valid_until IS NULL OR membership.valid_until > :now)
                  AND account.status = 'ACTIVE'
                  AND capability.capability_code IN (:requiredCapabilities)
                """, new MapSqlParameterSource()
                .addValue("membershipId", membershipId)
                .addValue("organisationId", requireOrganisation(user))
                .addValue("schoolId", schoolId)
                .addValue("now", Instant.now(clock))
                .addValue("requiredCapabilities", capabilities), Long.class);
        if (count == null || count != capabilities.size()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "APPLICATION_ASSIGNEE_INVALID",
                    "The selected assignee lacks active application access in this school.");
        }
    }

    private void insertGuardians(
            ApplicationScope application,
            List<GuardianInput> guardians,
            Instant now) {
        for (GuardianInput guardian : guardians) {
            insertGuardian(application, guardian, now);
        }
    }

    private void insertGuardian(ApplicationScope application, GuardianInput guardian, Instant now) {
        jdbc.update("""
                INSERT INTO admission_application_guardians (
                    id, organisation_id, school_id, application_id, display_name,
                    relationship_code, email_normalized, phone, primary_contact,
                    pickup_authorized, created_at, updated_at, row_version
                ) VALUES (
                    :id, :organisationId, :schoolId, :applicationId, :displayName,
                    :relationshipCode, :email, :phone, :primaryContact,
                    :pickupAuthorized, :now, :now, 0
                )
                """, applicationParameters(application)
                .addValue("id", UUID.randomUUID())
                .addValue("displayName", clean(guardian.displayName()))
                .addValue("relationshipCode", normalizeRelationshipCode(guardian.relationshipCode()))
                .addValue("email", normalizeEmail(guardian.email()))
                .addValue("phone", nullableClean(guardian.phone()))
                .addValue("primaryContact", guardian.primaryContact())
                .addValue("pickupAuthorized", guardian.pickupAuthorized())
                .addValue("now", now));
    }

    private static List<GuardianInput> normalizeGuardians(
            List<GuardianInput> requested,
            StoredEnquiryForApplication enquiry) {
        if (requested != null && !requested.isEmpty()) return List.copyOf(requested);
        if (enquiry == null) return List.of();
        return List.of(new GuardianInput(
                enquiry.name(),
                "PARENT_GUARDIAN",
                enquiry.email(),
                enquiry.phone(),
                true,
                false));
    }

    private static void validateGuardians(List<GuardianInput> guardians) {
        if (guardians.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "APPLICATION_GUARDIAN_REQUIRED",
                    "Add at least one guardian contact.");
        }
        long primaryCount = guardians.stream().filter(GuardianInput::primaryContact).count();
        if (primaryCount != 1) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "APPLICATION_PRIMARY_GUARDIAN_REQUIRED",
                    "Choose exactly one primary guardian contact.");
        }
        for (GuardianInput guardian : guardians) {
            validateGuardian(guardian);
        }
    }

    private static void validateGuardian(GuardianInput guardian) {
        if (normalizeRelationshipCode(guardian.relationshipCode()).isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "APPLICATION_GUARDIAN_RELATIONSHIP_REQUIRED",
                    "Choose a recognizable guardian relationship.");
        }
        if (nullableClean(guardian.email()) == null && nullableClean(guardian.phone()) == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "APPLICATION_GUARDIAN_CONTACT_REQUIRED",
                    "Every guardian needs an email address or phone number.");
        }
    }

    private void validateOfferAcceptance(StoredApplication application) {
        if (application.status() != ApplicationStatus.OFFERED || application.currentDecisionId() == null) {
            throw conflict("APPLICATION_OFFER_REQUIRED", "Record an active offer before accepting the application.");
        }
        LocalDate expiry = jdbc.queryForObject("""
                SELECT offer_expires_on
                FROM admission_decisions
                WHERE id = :decisionId
                  AND application_id = :applicationId
                  AND organisation_id = :organisationId
                  AND school_id = :schoolId
                  AND decision = 'OFFER'
                """, applicationParameters(application)
                .addValue("decisionId", application.currentDecisionId()), LocalDate.class);
        if (expiry == null || expiry.isBefore(LocalDate.now(clock))) {
            throw conflict("APPLICATION_OFFER_EXPIRED", "Record a current offer before accepting the application.");
        }
    }

    private static void validateDecision(RecordAdmissionDecisionRequest request) {
        if (request.decision() == AdmissionDecision.OFFER && request.offerExpiresOn() == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "APPLICATION_OFFER_EXPIRY_REQUIRED",
                    "An offer must have an expiry date.");
        }
        if (request.decision() != AdmissionDecision.OFFER && request.offerExpiresOn() != null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "APPLICATION_OFFER_EXPIRY_NOT_ALLOWED",
                    "Only an offer may have an expiry date.");
        }
    }

    private static void validateGender(ApplicantGender gender, String description) {
        String normalized = nullableClean(description);
        if (gender == ApplicantGender.SELF_DESCRIBED && normalized == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "APPLICANT_GENDER_DESCRIPTION_REQUIRED",
                    "Provide the self-described gender value.");
        }
        if (gender != ApplicantGender.SELF_DESCRIBED && normalized != null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "APPLICANT_GENDER_DESCRIPTION_NOT_ALLOWED",
                    "A gender description is used only with the self-described option.");
        }
    }

    private static String normalizedGenderDescription(ApplicantGender gender, String value) {
        return gender == ApplicantGender.SELF_DESCRIBED ? nullableClean(value) : null;
    }

    private static void validateDocumentReview(
            StoredDocument document,
            ReviewApplicationDocumentRequest request) {
        if (Set.of(
                DocumentReviewStatus.RECEIVED,
                DocumentReviewStatus.VERIFIED,
                DocumentReviewStatus.REJECTED).contains(request.status())
                && request.receivedMethod() == null
                && document.receivedMethod() == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "DOCUMENT_RECEIVED_METHOD_REQUIRED",
                    "Record how the document was received.");
        }
        if (Set.of(DocumentReviewStatus.REJECTED, DocumentReviewStatus.WAIVED).contains(request.status())
                && nullableClean(request.reviewNote()) == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "DOCUMENT_REVIEW_NOTE_REQUIRED",
                    "Add a reason when rejecting or waiving a document.");
        }
    }

    private StoredDocument findDocument(StoredApplication application, UUID documentId) {
        List<StoredDocument> documents = jdbc.query("""
                SELECT id, document_code, status, received_method, received_at, row_version
                FROM admission_application_documents
                WHERE id = :documentId
                  AND application_id = :applicationId
                  AND organisation_id = :organisationId
                  AND school_id = :schoolId
                """, applicationParameters(application).addValue("documentId", documentId), (result, row) -> new StoredDocument(
                result.getObject("id", UUID.class),
                result.getString("document_code"),
                DocumentReviewStatus.valueOf(result.getString("status")),
                result.getString("received_method") == null
                        ? null : DocumentReceivedMethod.valueOf(result.getString("received_method")),
                instant(result, "received_at"),
                result.getLong("row_version")));
        if (documents.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "APPLICATION_DOCUMENT_NOT_FOUND", "The checklist document was not found.");
        }
        return documents.getFirst();
    }

    private StoredGuardian findGuardian(StoredApplication application, UUID guardianId) {
        List<StoredGuardian> guardians = jdbc.query("""
                SELECT id, display_name, relationship_code, email_normalized, phone,
                       primary_contact, pickup_authorized, matched_guardian_id, row_version
                FROM admission_application_guardians
                WHERE id = :guardianId
                  AND application_id = :applicationId
                  AND organisation_id = :organisationId
                  AND school_id = :schoolId
                """, applicationParameters(application).addValue("guardianId", guardianId), (result, row) -> new StoredGuardian(
                result.getObject("id", UUID.class),
                result.getString("display_name"),
                result.getString("relationship_code"),
                result.getString("email_normalized"),
                result.getString("phone"),
                result.getBoolean("primary_contact"),
                result.getBoolean("pickup_authorized"),
                result.getObject("matched_guardian_id", UUID.class),
                result.getLong("row_version")));
        if (guardians.isEmpty()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "APPLICATION_GUARDIAN_NOT_FOUND",
                    "The guardian contact was not found.");
        }
        return guardians.getFirst();
    }

    private static void ensureGuardiansEditable(StoredApplication application) {
        if (application.status() == ApplicationStatus.ENROLLED) {
            throw conflict("APPLICATION_GUARDIANS_LOCKED", "Guardian contacts are locked after enrolment handoff.");
        }
    }

    private void demotePrimaryGuardian(StoredApplication application, UUID exceptGuardianId, Instant now) {
        jdbc.update("""
                UPDATE admission_application_guardians
                SET primary_contact = false, updated_at = :now, row_version = row_version + 1
                WHERE application_id = :applicationId
                  AND organisation_id = :organisationId
                  AND school_id = :schoolId
                  AND primary_contact
                  AND (CAST(:exceptGuardianId AS uuid) IS NULL OR id <> :exceptGuardianId)
                """, applicationParameters(application)
                .addValue("exceptGuardianId", exceptGuardianId)
                .addValue("now", now));
    }

    private long countGuardians(StoredApplication application) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM admission_application_guardians
                WHERE application_id = :applicationId
                  AND organisation_id = :organisationId
                  AND school_id = :schoolId
                """, applicationParameters(application), Long.class);
        return count == null ? 0 : count;
    }

    private void touchApplication(StoredApplication application, Instant now) {
        jdbc.update("""
                UPDATE admission_applications
                SET updated_at = :now, row_version = row_version + 1
                WHERE id = :applicationId
                  AND organisation_id = :organisationId
                  AND school_id = :schoolId
                """, applicationParameters(application).addValue("now", now));
    }

    private void touchApplication(StoredApplication application, Instant now, Long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE admission_applications
                SET updated_at = :now, row_version = row_version + 1
                WHERE id = :applicationId
                  AND organisation_id = :organisationId
                  AND school_id = :schoolId
                  AND row_version = :expectedVersion
                """, applicationParameters(application)
                .addValue("now", now)
                .addValue("expectedVersion", expectedVersion));
        requireSingleUpdate(updated);
    }

    private void auditMutation(
            AuthenticatedUser user,
            StoredApplication application,
            String action,
            Map<String, ?> metadata) {
        audit.recordScoped(
                user,
                application.actorMembershipId(),
                application.schoolId(),
                action,
                "ADMISSION_APPLICATION",
                application.id().toString(),
                AuditOutcome.SUCCEEDED,
                null,
                metadata);
    }

    private void appendOutbox(
            UUID applicationId,
            String eventType,
            String status,
            long version,
            Instant now) {
        jdbc.update("""
                INSERT INTO outbox_events (
                    id, aggregate_type, aggregate_id, event_type, payload, occurred_at,
                    available_at, attempt_count, created_at
                ) VALUES (
                    :eventId, 'AdmissionApplication', :aggregateId, :eventType,
                    CAST(:payload AS jsonb), :now, :now, 0, :now
                )
                """, new MapSqlParameterSource()
                .addValue("eventId", UUID.randomUUID())
                .addValue("aggregateId", applicationId.toString())
                .addValue("eventType", eventType)
                .addValue("payload", "{\"applicationId\":\"" + applicationId + "\",\"status\":\"" + status
                        + "\",\"version\":" + version + "}")
                .addValue("now", now));
    }

    private static ApplicationListItem mapListItem(ResultSet result, int row) throws SQLException {
        UUID id = result.getObject("id", UUID.class);
        UUID schoolId = result.getObject("school_id", UUID.class);
        UUID assignedMembershipId = result.getObject("assigned_membership_id", UUID.class);
        UUID enquiryId = result.getObject("enquiry_id", UUID.class);
        return new ApplicationListItem(
                id,
                applicationReference(id),
                result.getString("applicant_legal_name"),
                result.getString("applicant_preferred_name"),
                result.getString("target_grade_code"),
                result.getString("intake_session_label"),
                ApplicationStatus.valueOf(result.getString("status")),
                assignedMembershipId == null ? null : new EnquiryAssignee(
                        assignedMembershipId, schoolId, result.getString("assignee_name")),
                enquiryId == null ? null : EnquiryService.reference(enquiryId),
                result.getLong("required_count"),
                result.getLong("verified_count"),
                instant(result, "created_at"),
                instant(result, "updated_at"),
                result.getLong("row_version"), AdmissionType.valueOf(result.getString("admission_type")));
    }

    private static StoredApplication mapStoredApplication(ResultSet result, int row) throws SQLException {
        return new StoredApplication(
                result.getObject("id", UUID.class),
                result.getObject("organisation_id", UUID.class),
                result.getObject("school_id", UUID.class),
                result.getObject("enquiry_id", UUID.class),
                result.getObject("target_campus_id", UUID.class),
                result.getString("campus_name"),
                result.getObject("target_academic_session_id", UUID.class),
                result.getString("session_name"),
                result.getString("intake_session_label"),
                result.getString("target_grade_code"),
                result.getString("applicant_legal_name"),
                result.getString("applicant_preferred_name"),
                result.getObject("birth_date", LocalDate.class),
                ApplicantGender.valueOf(result.getString("gender_code")),
                result.getString("gender_description"),
                ApplicationStatus.valueOf(result.getString("status")),
                result.getObject("assigned_membership_id", UUID.class),
                result.getString("assignee_name"),
                result.getObject("current_decision_id", UUID.class),
                instant(result, "submitted_at"),
                instant(result, "accepted_at"),
                instant(result, "withdrawn_at"),
                instant(result, "enrolled_at"),
                instant(result, "created_at"),
                instant(result, "updated_at"),
                result.getObject("actor_membership_id", UUID.class),
                result.getLong("row_version"),
                result.getString("request_fingerprint"), AdmissionType.valueOf(result.getString("admission_type")),
                result.getString("rte_reference"));
    }

    private static MapSqlParameterSource applicationParameters(ApplicationScope application) {
        return new MapSqlParameterSource()
                .addValue("id", application.id())
                .addValue("applicationId", application.id())
                .addValue("organisationId", application.organisationId())
                .addValue("schoolId", application.schoolId());
    }

    private static void requireVersion(StoredApplication application, Long expectedVersion) {
        if (expectedVersion == null || expectedVersion != application.version()) {
            throw conflict(
                    "APPLICATION_VERSION_CONFLICT",
                    "This application changed after it was opened. Refresh before saving again.");
        }
    }

    private static void requireSingleUpdate(int updated) {
        if (updated != 1) {
            throw conflict(
                    "APPLICATION_VERSION_CONFLICT",
                    "This application changed after it was opened. Refresh before saving again.");
        }
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private static UUID requireOrganisation(AuthenticatedUser user) {
        if (user.activeOrganisationId() == null) {
            throw conflict(
                    "ORGANISATION_SELECTION_REQUIRED",
                    "Select a school organisation before opening admissions.");
        }
        return user.activeOrganisationId();
    }

    private static String validateIdempotencyKey(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{16,100}")) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must contain 16 to 100 safe characters.");
        }
        return value;
    }

    private void validateRte(UUID organisationId, UUID schoolId, AdmissionType type, String grade,
            UUID sessionId, LocalDate birthDate, String reference, boolean claimSeat) {
        if (type == AdmissionType.REGULAR) {
            if (nullableClean(reference) != null) throw new ApiException(HttpStatus.BAD_REQUEST,
                    "REGULAR_RTE_REFERENCE", "An RTE reference is only applicable to RTE admissions.");
            return;
        }
        if (!"I".equalsIgnoreCase(grade.strip()) || sessionId == null) throw new ApiException(HttpStatus.BAD_REQUEST,
                "RTE_CLASS_SESSION_REQUIRED", "New RTE admissions require Class I and an academic session.");
        var params = new MapSqlParameterSource().addValue("organisationId", organisationId)
                .addValue("schoolId", schoolId).addValue("sessionId", sessionId);
        var rows = jdbc.query("""
                SELECT starts_on, rte_class_one_seat_limit, rte_min_age_years, rte_max_age_years
                FROM academic_sessions WHERE id=:sessionId AND organisation_id=:organisationId AND school_id=:schoolId
                """ + (claimSeat ? " FOR UPDATE" : ""), params, (r, n) -> new RtePolicy(
                r.getObject("starts_on", LocalDate.class), r.getInt("rte_class_one_seat_limit"),
                r.getInt("rte_min_age_years"), r.getInt("rte_max_age_years")));
        if (rows.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "RTE_SESSION_REQUIRED", "Choose an available school session.");
        var policy = rows.getFirst();
        int age = java.time.Period.between(birthDate, policy.startsOn()).getYears();
        if (birthDate.isAfter(policy.startsOn()) || age < policy.minAge() || age > policy.maxAge())
            throw new ApiException(HttpStatus.BAD_REQUEST, "RTE_AGE_POLICY",
                    "The applicant must meet the configured RTE age range at the academic session start.");
        if (claimSeat) {
            Long used = jdbc.queryForObject("""
                    SELECT count(*) FROM learners WHERE organisation_id=:organisationId AND school_id=:schoolId
                        AND admission_type='RTE' AND rte_admission_session_id=:sessionId
                    """, params, Long.class);
            if (used != null && used >= policy.seats()) throw conflict("RTE_SEATS_EXHAUSTED",
                    "This session's configured RTE seats are already filled. Review the session policy before another admission.");
            Long duplicate = jdbc.queryForObject("""
                    SELECT count(*) FROM learners WHERE organisation_id=:organisationId AND school_id=:schoolId
                        AND lower(btrim(rte_reference))=lower(:reference)
                    """, params.addValue("reference", nullableClean(reference)), Long.class);
            if (duplicate != null && duplicate > 0) throw conflict("RTE_REFERENCE_DUPLICATE", "This RTE reference has already been admitted.");
        }
    }

    private record RtePolicy(LocalDate startsOn, int seats, int minAge, int maxAge) {}

    private static String fingerprint(
            CreateApplicationRequest request,
            UUID schoolId,
            List<GuardianInput> guardians) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("school", schoolId.toString());
        values.put("enquiry", request.enquiryId() == null ? "" : request.enquiryId().toString());
        values.put("campus", request.targetCampusId() == null ? "" : request.targetCampusId().toString());
        values.put("session", request.targetAcademicSessionId() == null ? "" : request.targetAcademicSessionId().toString());
        values.put("sessionLabel", clean(request.intakeSessionLabel()));
        values.put("grade", clean(request.targetGradeCode()).toUpperCase(Locale.ROOT));
        values.put("legalName", clean(request.applicantLegalName()));
        values.put("preferredName", value(nullableClean(request.applicantPreferredName())));
        values.put("birthDate", request.birthDate().toString());
        values.put("gender", request.gender().name());
        values.put("genderDescription", value(normalizedGenderDescription(request.gender(), request.genderDescription())));
        String guardianValue = guardians.stream()
                .map(guardian -> String.join("|",
                        clean(guardian.displayName()),
                        normalizeRelationshipCode(guardian.relationshipCode()),
                        value(normalizeEmail(guardian.email())),
                        value(nullableClean(guardian.phone())),
                        Boolean.toString(guardian.primaryContact()),
                        Boolean.toString(guardian.pickupAuthorized())))
                .collect(Collectors.joining("\n"));
        values.put("guardians", guardianValue);
        // Keep old Regular retry fingerprints stable across this additive migration.
        if (AdmissionType.orRegular(request.admissionType()) == AdmissionType.RTE) {
            values.put("admissionType", "RTE");
            values.put("rteReference", value(nullableClean(request.rteReference())));
        }
        String canonical = values.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("\n"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        var value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String clean(String value) {
        return value.strip().replaceAll("[\\p{Cc}&&[^\\r\\n\\t]]", "");
    }

    private static String nullableClean(String value) {
        return value == null || value.isBlank() ? null : clean(value);
    }

    private static String normalizeEmail(String value) {
        String cleaned = nullableClean(value);
        return cleaned == null ? null : cleaned.toLowerCase(Locale.ROOT);
    }

    private static String normalizeRelationshipCode(String value) {
        return clean(value)
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString();
    }

    static String applicationReference(UUID id) {
        return "APP-" + id.toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
    }

    private static String learnerReference(UUID id) {
        return "LRN-" + id.toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
    }

    private static String enrolmentReference(UUID id) {
        return "ENR-" + id.toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
    }

    private static String maskEmail(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        if (at <= 1) return "***" + email.substring(Math.max(at, 0));
        return email.substring(0, 1) + "***" + email.substring(at);
    }

    private static String maskPhone(String phone) {
        if (phone == null) return null;
        int visible = Math.min(4, phone.length());
        return "*".repeat(Math.max(0, phone.length() - visible)) + phone.substring(phone.length() - visible);
    }

    private record CreationScope(
            UUID organisationId,
            UUID schoolId,
            UUID actorMembershipId,
            StoredEnquiryForApplication enquiry) {
    }

    private interface ApplicationScope {
        UUID id();

        UUID organisationId();

        UUID schoolId();
    }

    private record InsertedApplication(
            UUID id,
            UUID organisationId,
            UUID schoolId,
            String requestFingerprint) implements ApplicationScope {
    }

    private record StoredEnquiryForApplication(
            UUID id,
            UUID schoolId,
            String name,
            String email,
            String phone,
            EnquiryStatus status,
            UUID actorMembershipId) {
    }

    private record StoredApplication(
            UUID id,
            UUID organisationId,
            UUID schoolId,
            UUID enquiryId,
            UUID targetCampusId,
            String targetCampusName,
            UUID targetAcademicSessionId,
            String targetAcademicSessionName,
            String intakeSessionLabel,
            String targetGradeCode,
            String applicantLegalName,
            String applicantPreferredName,
            LocalDate birthDate,
            ApplicantGender gender,
            String genderDescription,
            ApplicationStatus status,
            UUID assignedMembershipId,
            String assigneeName,
            UUID currentDecisionId,
            Instant submittedAt,
            Instant acceptedAt,
            Instant withdrawnAt,
            Instant enrolledAt,
            Instant createdAt,
            Instant updatedAt,
            UUID actorMembershipId,
            long version,
            String requestFingerprint,
            AdmissionType admissionType,
            String rteReference) implements ApplicationScope {
    }

    private record StoredDocument(
            UUID id,
            String documentCode,
            DocumentReviewStatus status,
            DocumentReceivedMethod receivedMethod,
            Instant receivedAt,
            long version) {
    }

    private record StoredGuardian(
            UUID id,
            String displayName,
            String relationshipCode,
            String email,
            String phone,
            boolean primaryContact,
            boolean pickupAuthorized,
            UUID matchedGuardianId,
            long version) {
    }

    private record SessionConfiguration(LocalDate startsOn, LocalDate endsOn) {
    }
}
