package com.educore.api.files;

import com.educore.api.common.api.ApiException;
import com.educore.api.files.FileQuarantineService.MalwareDetectedException;
import com.educore.api.files.FileQuarantineService.QuarantinedFile;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PrivateFileService {

    private static final DateTimeFormatter KEY_DATE = DateTimeFormatter.ofPattern("uuuu/MM").withZone(ZoneOffset.UTC);
    private final NamedParameterJdbcTemplate jdbc;
    private final FileQuarantineService quarantine;
    private final ObjectStorage storage;
    private final FileStorageProperties properties;
    private final SecurityAuditService audit;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public PrivateFileService(
            NamedParameterJdbcTemplate jdbc,
            FileQuarantineService quarantine,
            ObjectStorage storage,
            FileStorageProperties properties,
            SecurityAuditService audit,
            Clock clock,
            TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.quarantine = quarantine;
        this.storage = storage;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
        this.transactions = transactions;
    }

    public FileUploadResponse uploadAssignmentResource(
            AuthenticatedUser user,
            UUID schoolId,
            UUID assignmentId,
            UUID replacesFileId,
            MultipartFile upload) {
        StaffContext initial = requireAssignmentStaff(user, schoolId, assignmentId, "files.manage", "learning.manage");
        return upload(user, initial, "ASSIGNMENT_RESOURCE", assignmentId, null,
                replacesFileId, upload, "Private assignment resource uploaded", () -> {
                    StaffContext current = requireAssignmentStaff(
                            user, schoolId, assignmentId, "files.manage", "learning.manage");
                    if (!"DRAFT".equals(current.assignmentStatus())) {
                        throw conflict("ASSIGNMENT_RESOURCE_FROZEN",
                                "Assignment resources can only change while the assignment is a draft.");
                    }
                    return current;
                });
    }

    public FileUploadResponse uploadFamilySubmissionEvidence(
            AuthenticatedUser user,
            UUID learnerId,
            UUID assignmentId,
            UUID replacesFileId,
            MultipartFile upload) {
        FamilySubmissionContext initial = requireFamilySubmission(user, learnerId, assignmentId);
        validateFamilyAttachmentWindow(initial, replacesFileId);
        return upload(user, initial.staffContext(), "SUBMISSION_EVIDENCE", null, initial.submissionId(),
                replacesFileId, upload, "Guardian added private submission evidence", () -> {
                    FamilySubmissionContext current = requireFamilySubmission(user, learnerId, assignmentId);
                    validateFamilyAttachmentWindow(current, replacesFileId);
                    return current.staffContext();
        });
    }

    public FileUploadResponse uploadStudentSubmissionEvidence(
            AuthenticatedUser user,
            UUID assignmentId,
            UUID replacesFileId,
            MultipartFile upload) {
        FamilySubmissionContext initial = requireStudentSubmission(user, assignmentId);
        validateFamilyAttachmentWindow(initial, replacesFileId);
        return upload(user, initial.staffContext(), "SUBMISSION_EVIDENCE", null, initial.submissionId(),
                replacesFileId, upload, "Student added private submission evidence", () -> {
                    FamilySubmissionContext current = requireStudentSubmission(user, assignmentId);
                    validateFamilyAttachmentWindow(current, replacesFileId);
                    return current.staffContext();
                });
    }

    public long expire(
            AuthenticatedUser user,
            UUID fileId,
            long expectedVersion,
            String reasonValue) {
        String reason = requiredReason(reasonValue);
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                StoredFile initialFile = storedFile(user, fileId, false);
                lockFileContext(initialFile);
                StoredFile file = storedFile(user, fileId, true);
                ContextAuthorization authorization = requireManageAuthorization(user, file);
                String previousEvidence = file.submissionId() == null ? null : submissionEvidence(file.submissionId());
                int changed = jdbc.update("""
                    UPDATE private_files SET lifecycle_status = 'EXPIRED',
                        expired_by_membership_id = :actorMembershipId, expired_at = :now,
                        expiry_reason = :reason, row_version = row_version + 1
                    WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                      AND lifecycle_status = 'AVAILABLE' AND row_version = :expectedVersion
                        """, authorization.parameters().addValue("id", file.id())
                        .addValue("expectedVersion", expectedVersion).addValue("reason", reason)
                        .addValue("now", Instant.now(clock)));
                if (changed != 1) throw conflict("FILE_STALE", "Refresh the attachment before removing it.");
                fileHistory(authorization, file.id(), "EXPIRED", "AVAILABLE", "EXPIRED", reason);
                long contextVersion;
                if (file.assignmentId() != null) {
                    contextVersion = touchAssignment(file.assignmentId(), authorization);
                } else {
                    String nextEvidence = submissionEvidence(file.submissionId());
                    submissionAttachmentHistory(authorization, file.submissionId(), previousEvidence, nextEvidence, reason);
                    contextVersion = submissionVersion(file.submissionId(), authorization);
                }
                audit.recordScoped(user, authorization.membershipId(), authorization.schoolId(),
                        "file.expired", "PRIVATE_FILE", file.id().toString(), AuditOutcome.SUCCEEDED,
                        reason, Map.of("purpose", file.purpose()));
                return contextVersion;
            }));
        } catch (DataIntegrityViolationException exception) {
            throw conflict("FILE_CONTEXT_CHANGED",
                    "This attachment can no longer be changed. Refresh the assignment before continuing.");
        }
    }

    public PrivateFileDownload download(AuthenticatedUser user, UUID fileId) {
        StoredFile file = storedFile(user, fileId, false);
        ContextAuthorization authorization = requireReadAuthorization(user, file);
        audit.recordScoped(user, authorization.membershipId(), authorization.schoolId(),
                "file.download.authorized", "PRIVATE_FILE", file.id().toString(), AuditOutcome.SUCCEEDED,
                "Private file download authorized", Map.of("purpose", file.purpose()));
        return new PrivateFileDownload(file.downloadFilename(), file.mediaType(), file.sizeBytes(),
                output -> storage.copyVerifiedTo(file.storageKey(), file.sizeBytes(), file.sha256(), output));
    }

    public Map<UUID, List<PrivateFileSummary>> assignmentFiles(
            UUID organisationId, UUID schoolId, List<UUID> assignmentIds) {
        return summariesByContext("assignment_id", organisationId, schoolId, assignmentIds);
    }

    public Map<UUID, List<PrivateFileSummary>> submissionFiles(
            UUID organisationId, UUID schoolId, List<UUID> submissionIds) {
        return summariesByContext("learner_assignment_submission_id", organisationId, schoolId, submissionIds);
    }

    private FileUploadResponse upload(
            AuthenticatedUser user,
            StaffContext initial,
            String purpose,
            UUID assignmentId,
            UUID submissionId,
            UUID replacesFileId,
            MultipartFile upload,
            String auditReason,
            ContextRefresh refresh) {
        try (QuarantinedFile file = quarantine.inspect(upload)) {
            UUID fileId = UUID.randomUUID();
            Instant now = Instant.now(clock);
            String key = initial.organisationId() + "/" + initial.schoolId() + "/"
                    + KEY_DATE.format(now) + "/" + fileId;
            storage.put(key, file.path(), file.mediaType(), file.sizeBytes(), file.sha256Hex());
            try {
                return Objects.requireNonNull(transactions.execute(status -> {
                    if (assignmentId != null) {
                        lockAssignment(assignmentId, initial.organisationId(), initial.schoolId());
                    } else {
                        UUID parentAssignmentId = submissionAssignmentId(
                                submissionId, initial.organisationId(), initial.schoolId());
                        lockAssignment(parentAssignmentId, initial.organisationId(), initial.schoolId());
                        lockSubmission(submissionId, initial.organisationId(), initial.schoolId());
                    }
                    StaffContext current = refresh.current();
                    validateReplacementContext(
                            current.authorization(), purpose, assignmentId, submissionId, replacesFileId);
                    String previousEvidence = submissionId == null ? null : submissionEvidence(submissionId);
                    insertFile(current, fileId, purpose, assignmentId, submissionId, replacesFileId, file, key, now);
                    fileHistory(current.authorization(), fileId,
                            replacesFileId == null ? "UPLOADED" : "REPLACED", null, "AVAILABLE", auditReason);
                    if (replacesFileId != null) {
                        expireReplacement(current.authorization(), replacesFileId, fileId);
                    }
                    long contextVersion;
                    if (assignmentId != null) {
                        contextVersion = touchAssignment(assignmentId, current.authorization());
                    } else {
                        String nextEvidence = submissionEvidence(submissionId);
                        submissionAttachmentHistory(current.authorization(), submissionId,
                                previousEvidence, nextEvidence, auditReason);
                        contextVersion = submissionVersion(submissionId, current.authorization());
                    }
                    audit.recordScoped(user, current.membershipId(), current.schoolId(),
                            "file.uploaded", "PRIVATE_FILE", fileId.toString(), AuditOutcome.SUCCEEDED,
                            auditReason, Map.of("purpose", purpose, "mediaType", file.mediaType(),
                                    "sizeBytes", file.sizeBytes(), "sha256Prefix", file.sha256Hex().substring(0, 12)));
                    return new FileUploadResponse(summary(fileId, purpose, replacesFileId, file, now), contextVersion);
                }));
            } catch (DataIntegrityViolationException exception) {
                try {
                    storage.delete(key);
                } catch (RuntimeException cleanup) {
                    exception.addSuppressed(cleanup);
                }
                throw conflict("FILE_CONTEXT_CHANGED",
                        "The assignment or attachment changed while the file was being scanned. Refresh and try again.");
            } catch (RuntimeException exception) {
                try {
                    storage.delete(key);
                } catch (RuntimeException cleanup) {
                    exception.addSuppressed(cleanup);
                }
                throw exception;
            }
        } catch (MalwareDetectedException exception) {
            audit.recordScoped(user, initial.membershipId(), initial.schoolId(),
                    "file.upload.rejected", assignmentId == null ? "LEARNER_ASSIGNMENT_SUBMISSION" : "ASSIGNMENT",
                    Objects.toString(assignmentId == null ? submissionId : assignmentId), AuditOutcome.DENIED,
                    "Malware scanner rejected a private upload",
                    Map.of("sha256Prefix", exception.sha256Hex().substring(0, 12),
                            "threat", Objects.toString(exception.threatName(), "DETECTED")));
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_MALWARE_DETECTED",
                    "The security scanner rejected this file. Choose a different file or contact the school.");
        }
    }

    private void insertFile(
            StaffContext context,
            UUID fileId,
            String purpose,
            UUID assignmentId,
            UUID submissionId,
            UUID replacesFileId,
            QuarantinedFile file,
            String key,
            Instant now) {
        jdbc.update("""
                INSERT INTO private_files (
                    id, organisation_id, school_id, assignment_id, learner_assignment_submission_id,
                    purpose, access_scope, original_filename, download_filename, detected_media_type,
                    size_bytes, sha256_hex, storage_bucket, storage_key, scan_engine,
                    scan_engine_version, scan_status, lifecycle_status, replaces_file_id,
                    created_by_user_id, created_by_membership_id, created_at, available_at, row_version
                ) VALUES (:id, :organisationId, :schoolId, :assignmentId, :submissionId,
                    :purpose, 'CONTEXT_FAMILY', :originalFilename, :downloadFilename, :mediaType,
                    :sizeBytes, :sha256, :bucket, :storageKey, :scanEngine,
                    :scanVersion, 'CLEAN', 'AVAILABLE', :replacesFileId,
                    :actorUserId, :actorMembershipId, :now, :now, 0)
                """, context.parameters().addValue("id", fileId).addValue("assignmentId", assignmentId)
                .addValue("submissionId", submissionId).addValue("purpose", purpose)
                .addValue("originalFilename", file.originalFilename())
                .addValue("downloadFilename", file.downloadFilename()).addValue("mediaType", file.mediaType())
                .addValue("sizeBytes", file.sizeBytes()).addValue("sha256", file.sha256Hex())
                .addValue("bucket", properties.bucket()).addValue("storageKey", key)
                .addValue("scanEngine", file.scanEngine()).addValue("scanVersion", file.scanEngineVersion())
                .addValue("replacesFileId", replacesFileId).addValue("now", now));
    }

    private void expireReplacement(ContextAuthorization context, UUID oldFileId, UUID newFileId) {
        String reason = "Replaced by private file " + newFileId;
        int changed = jdbc.update("""
                UPDATE private_files SET lifecycle_status = 'EXPIRED',
                    expired_by_membership_id = :actorMembershipId, expired_at = :now,
                    expiry_reason = :reason, row_version = row_version + 1
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                  AND lifecycle_status = 'AVAILABLE'
                """, context.parameters().addValue("id", oldFileId).addValue("now", Instant.now(clock))
                .addValue("reason", reason));
        if (changed != 1) throw conflict("FILE_REPLACEMENT_STALE", "The attachment selected for replacement has changed.");
        fileHistory(context, oldFileId, "EXPIRED", "AVAILABLE", "EXPIRED", reason);
    }

    private StoredFile storedFile(AuthenticatedUser user, UUID fileId, boolean lock) {
        if (user.activeOrganisationId() == null) {
            throw conflict("ORGANISATION_SELECTION_REQUIRED", "Select a school organisation before opening files.");
        }
        String suffix = lock ? " FOR UPDATE" : "";
        List<StoredFile> files = jdbc.query("""
                SELECT id, school_id, assignment_id, learner_assignment_submission_id,
                       purpose, access_scope, download_filename, detected_media_type,
                       size_bytes, sha256_hex, storage_key, lifecycle_status, row_version
                FROM private_files
                WHERE id = :id AND organisation_id = :organisationId AND lifecycle_status = 'AVAILABLE'
                """ + suffix, new MapSqlParameterSource().addValue("id", fileId)
                .addValue("organisationId", user.activeOrganisationId()), (result, row) -> new StoredFile(
                result.getObject("id", UUID.class), user.activeOrganisationId(),
                result.getObject("school_id", UUID.class), result.getObject("assignment_id", UUID.class),
                result.getObject("learner_assignment_submission_id", UUID.class), result.getString("purpose"),
                result.getString("access_scope"), result.getString("download_filename"),
                result.getString("detected_media_type"), result.getLong("size_bytes"),
                result.getString("sha256_hex"), result.getString("storage_key"), result.getLong("row_version")));
        if (files.isEmpty()) throw notFound("FILE_NOT_FOUND", "The private file was not found.");
        return files.getFirst();
    }

    private StaffContext requireAssignmentStaff(
            AuthenticatedUser user,
            UUID requestedSchoolId,
            UUID assignmentId,
            String fileCapability,
            String learningCapability) {
        requireOrganisation(user);
        List<StaffContext> contexts = jdbc.query("""
                SELECT assignment.school_id, membership.id AS membership_id, assignment.status,
                       assignment.row_version
                FROM assignments assignment
                JOIN class_sections section ON section.id = assignment.class_section_id
                JOIN campuses campus ON campus.id = section.campus_id
                JOIN memberships membership ON membership.organisation_id = assignment.organisation_id
                    AND membership.school_id = assignment.school_id
                WHERE assignment.id = :assignmentId AND assignment.organisation_id = :organisationId
                  AND (CAST(:schoolId AS uuid) IS NULL OR assignment.school_id = :schoolId)
                  AND membership.user_id = :actorUserId AND membership.id IN (:membershipIds)
                  AND membership.status = 'ACTIVE' AND membership.valid_from <= CURRENT_TIMESTAMP
                  AND (membership.valid_until IS NULL OR membership.valid_until > CURRENT_TIMESTAMP)
                  AND EXISTS (SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id = mr.role_id
                              WHERE mr.membership_id = membership.id AND rc.capability_code = :fileCapability)
                  AND EXISTS (SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id = mr.role_id
                              WHERE mr.membership_id = membership.id AND rc.capability_code = :learningCapability)
                  AND (EXISTS (SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id = mr.role_id
                               WHERE mr.membership_id = membership.id AND rc.capability_code = 'learning.moderate')
                       OR EXISTS (SELECT 1 FROM teaching_allocations allocation
                                  WHERE allocation.section_subject_offering_id = assignment.section_subject_offering_id
                                    AND allocation.organisation_id = assignment.organisation_id
                                    AND allocation.school_id = assignment.school_id
                                    AND allocation.teacher_membership_id = membership.id
                                    AND allocation.status = 'ACTIVE'
                                    AND allocation.starts_on <= (assignment.available_from AT TIME ZONE campus.time_zone)::date
                                    AND (allocation.ends_on IS NULL OR allocation.ends_on >=
                                         (assignment.available_from AT TIME ZONE campus.time_zone)::date)))
                ORDER BY membership.created_at, membership.id LIMIT 1
                """, baseParameters(user).addValue("assignmentId", assignmentId).addValue("schoolId", requestedSchoolId)
                .addValue("fileCapability", fileCapability).addValue("learningCapability", learningCapability),
                (result, row) -> new StaffContext(user.activeOrganisationId(),
                        result.getObject("school_id", UUID.class), user.userId(),
                        result.getObject("membership_id", UUID.class), result.getString("status"),
                        result.getLong("row_version")));
        if (contexts.isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FILE_CONTEXT_DENIED",
                    "Private file access is not available for this assignment.");
        }
        return contexts.getFirst();
    }

    private FamilySubmissionContext requireFamilySubmission(
            AuthenticatedUser user, UUID learnerId, UUID assignmentId) {
        requireOrganisation(user);
        List<FamilySubmissionContext> contexts = jdbc.query("""
                SELECT assignment.school_id, membership.id AS membership_id, assignment.status,
                       assignment.row_version, submission.id AS submission_id,
                       submission.row_version AS submission_version, submission.state AS submission_state,
                       assignment.allow_attachments,assignment.max_attachment_count,
                       assignment.available_from,assignment.due_at,
                       assignment.allow_late,assignment.late_until,
                       (SELECT count(*) FROM private_files file
                        WHERE file.learner_assignment_submission_id=submission.id
                          AND file.lifecycle_status='AVAILABLE') AS active_attachment_count
                FROM assignment_recipients recipient
                JOIN assignments assignment ON assignment.id = recipient.assignment_id
                JOIN learner_assignment_submissions submission ON submission.assignment_recipient_id = recipient.id
                JOIN learner_guardian_relationships relationship
                  ON relationship.learner_id = recipient.learner_id
                 AND relationship.organisation_id = recipient.organisation_id
                 AND relationship.school_id = recipient.school_id
                 AND relationship.portal_access_status = 'ACTIVE'
                JOIN guardians guardian ON guardian.id = relationship.guardian_id
                    AND guardian.user_account_id = :actorUserId AND guardian.status = 'ACTIVE'
                JOIN memberships membership ON membership.user_id = :actorUserId
                    AND membership.organisation_id = recipient.organisation_id
                    AND membership.school_id = recipient.school_id
                    AND membership.status = 'ACTIVE' AND membership.valid_from <= CURRENT_TIMESTAMP
                    AND (membership.valid_until IS NULL OR membership.valid_until > CURRENT_TIMESTAMP)
                WHERE recipient.assignment_id = :assignmentId AND recipient.learner_id = :learnerId
                  AND recipient.organisation_id = :organisationId AND membership.id IN (:membershipIds)
                  AND EXISTS (SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id = mr.role_id
                              WHERE mr.membership_id = membership.id AND rc.capability_code = 'portal.guardian')
                ORDER BY membership.created_at, membership.id LIMIT 1
                """, baseParameters(user).addValue("learnerId", learnerId).addValue("assignmentId", assignmentId),
                (result, row) -> new FamilySubmissionContext(new StaffContext(
                        user.activeOrganisationId(), result.getObject("school_id", UUID.class), user.userId(),
                        result.getObject("membership_id", UUID.class), result.getString("status"),
                        result.getLong("row_version")), result.getObject("submission_id", UUID.class),
                        result.getLong("submission_version"), result.getString("submission_state"),
                        result.getBoolean("allow_attachments"), result.getInt("max_attachment_count"),
                        result.getInt("active_attachment_count"),
                        result.getTimestamp("available_from").toInstant(),
                        result.getTimestamp("due_at").toInstant(), result.getBoolean("allow_late"),
                        result.getTimestamp("late_until") == null ? null : result.getTimestamp("late_until").toInstant()));
        if (contexts.isEmpty()) {
            throw notFound("FAMILY_ASSIGNMENT_NOT_FOUND",
                    "This assignment is not available to your verified relationship.");
        }
        FamilySubmissionContext context = contexts.getFirst();
        if (!"PUBLISHED".equals(context.staffContext().assignmentStatus())) {
            throw conflict("ASSIGNMENT_CLOSED", "This assignment is not accepting attachment changes.");
        }
        return context;
    }

    private FamilySubmissionContext requireStudentSubmission(
            AuthenticatedUser user, UUID assignmentId) {
        requireOrganisation(user);
        List<FamilySubmissionContext> contexts = jdbc.query("""
                SELECT assignment.school_id,membership.id AS membership_id,assignment.status,
                       assignment.row_version,submission.id AS submission_id,
                       submission.row_version AS submission_version,submission.state AS submission_state,
                       assignment.allow_attachments,assignment.max_attachment_count,
                       assignment.available_from,assignment.due_at,
                       assignment.allow_late,assignment.late_until,
                       (SELECT count(*) FROM private_files file
                        WHERE file.learner_assignment_submission_id=submission.id
                          AND file.lifecycle_status='AVAILABLE') AS active_attachment_count
                FROM learner_portal_accounts portal
                JOIN student_portal_consent_requests consent
                  ON consent.id=portal.current_consent_request_id
                 AND consent.organisation_id=portal.organisation_id AND consent.school_id=portal.school_id
                 AND consent.status='APPROVED' AND consent.approved_user_account_id=portal.user_account_id
                JOIN assignment_recipients recipient ON recipient.learner_id=portal.learner_id
                 AND recipient.organisation_id=portal.organisation_id AND recipient.school_id=portal.school_id
                 AND recipient.assignment_id=:assignmentId
                JOIN assignments assignment ON assignment.id=recipient.assignment_id
                JOIN learner_assignment_submissions submission ON submission.assignment_recipient_id=recipient.id
                JOIN memberships membership ON membership.user_id=portal.user_account_id
                 AND membership.organisation_id=portal.organisation_id AND membership.school_id=portal.school_id
                 AND membership.status='ACTIVE' AND membership.valid_from<=CURRENT_TIMESTAMP
                 AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                WHERE portal.user_account_id=:actorUserId AND portal.organisation_id=:organisationId
                  AND portal.status='ACTIVE' AND membership.id IN (:membershipIds)
                  AND EXISTS (SELECT 1 FROM membership_roles mr
                    JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='portal.student')
                ORDER BY membership.created_at,membership.id LIMIT 1
                """, baseParameters(user).addValue("assignmentId", assignmentId),
                (result, row) -> new FamilySubmissionContext(new StaffContext(
                        user.activeOrganisationId(), result.getObject("school_id", UUID.class), user.userId(),
                        result.getObject("membership_id", UUID.class), result.getString("status"),
                        result.getLong("row_version")), result.getObject("submission_id", UUID.class),
                        result.getLong("submission_version"), result.getString("submission_state"),
                        result.getBoolean("allow_attachments"), result.getInt("max_attachment_count"),
                        result.getInt("active_attachment_count"),
                        result.getTimestamp("available_from").toInstant(),
                        result.getTimestamp("due_at").toInstant(), result.getBoolean("allow_late"),
                        result.getTimestamp("late_until") == null
                                ? null : result.getTimestamp("late_until").toInstant()));
        if (contexts.isEmpty()) {
            throw notFound("STUDENT_ASSIGNMENT_NOT_FOUND", "This assignment is unavailable.");
        }
        FamilySubmissionContext context = contexts.getFirst();
        if (!"PUBLISHED".equals(context.staffContext().assignmentStatus())) {
            throw conflict("ASSIGNMENT_CLOSED", "This assignment is not accepting attachment changes.");
        }
        return context;
    }

    private void validateFamilyAttachmentWindow(
            FamilySubmissionContext context, UUID replacesFileId) {
        if (!context.attachmentsAllowed()) {
            throw conflict("ASSIGNMENT_ATTACHMENTS_DISABLED",
                    "The school has not enabled private attachments for this assignment.");
        }
        if (!Set.of("NOT_STARTED", "DRAFT", "RETURNED").contains(context.submissionState())) {
            throw conflict("SUBMISSION_LOCKED", "This response is awaiting or has completed teacher review.");
        }
        Instant now = Instant.now(clock);
        if (now.isBefore(context.availableFrom())) {
            throw conflict("ASSIGNMENT_NOT_AVAILABLE", "This assignment is not available yet.");
        }
        if (now.isAfter(context.dueAt()) && !context.allowLate()) {
            throw conflict("ASSIGNMENT_DUE_DATE_PASSED",
                    "The school has not enabled late responses for this assignment.");
        }
        if (now.isAfter(context.dueAt()) && context.lateUntil() != null && now.isAfter(context.lateUntil())) {
            throw conflict("ASSIGNMENT_LATE_WINDOW_CLOSED", "The late-response window has closed.");
        }
        if (context.activeAttachmentCount() > context.maxAttachmentCount()
                || (replacesFileId == null
                    && context.activeAttachmentCount() >= context.maxAttachmentCount())) {
            throw conflict("ASSIGNMENT_ATTACHMENT_LIMIT_REACHED",
                    "This submission already has the maximum number of private attachments.");
        }
    }

    private void validateReplacementContext(
            ContextAuthorization context,
            String purpose,
            UUID assignmentId,
            UUID submissionId,
            UUID replacesFileId) {
        if (replacesFileId == null) return;
        Integer matching = jdbc.queryForObject("""
                SELECT count(*) FROM private_files
                WHERE id=:replacesFileId AND organisation_id=:organisationId AND school_id=:schoolId
                  AND purpose=:purpose AND lifecycle_status='AVAILABLE'
                  AND assignment_id IS NOT DISTINCT FROM :assignmentId
                  AND learner_assignment_submission_id IS NOT DISTINCT FROM :submissionId
                """, context.parameters().addValue("replacesFileId", replacesFileId)
                .addValue("purpose", purpose).addValue("assignmentId", assignmentId)
                .addValue("submissionId", submissionId), Integer.class);
        if (matching == null || matching != 1) {
            throw notFound("FILE_REPLACEMENT_NOT_FOUND",
                    "The attachment selected for replacement is not available in this assignment.");
        }
    }

    private void lockFileContext(StoredFile file) {
        if (file.assignmentId() != null) {
            lockAssignment(file.assignmentId(), file.organisationId(), file.schoolId());
        } else {
            lockAssignment(contextAssignmentId(file), file.organisationId(), file.schoolId());
            lockSubmission(file.submissionId(), file.organisationId(), file.schoolId());
        }
    }

    private void lockAssignment(UUID assignmentId, UUID organisationId, UUID schoolId) {
        List<UUID> rows = jdbc.query("""
                SELECT id FROM assignments
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                FOR UPDATE
                """, new MapSqlParameterSource().addValue("id", assignmentId)
                .addValue("organisationId", organisationId).addValue("schoolId", schoolId),
                (result, row) -> result.getObject("id", UUID.class));
        if (rows.isEmpty()) throw notFound("FILE_CONTEXT_NOT_FOUND", "The private file context was not found.");
    }

    private void lockSubmission(UUID submissionId, UUID organisationId, UUID schoolId) {
        List<UUID> rows = jdbc.query("""
                SELECT id FROM learner_assignment_submissions
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                FOR UPDATE
                """, new MapSqlParameterSource().addValue("id", submissionId)
                .addValue("organisationId", organisationId).addValue("schoolId", schoolId),
                (result, row) -> result.getObject("id", UUID.class));
        if (rows.isEmpty()) throw notFound("FILE_CONTEXT_NOT_FOUND", "The private file context was not found.");
    }

    private ContextAuthorization requireReadAuthorization(AuthenticatedUser user, StoredFile file) {
        List<ContextAuthorization> staff = staffAuthorization(user, file, "files.read", "learning.read");
        if (!staff.isEmpty()) return staff.getFirst();
        List<ContextAuthorization> family = familyAuthorization(user, file, false);
        if (!family.isEmpty()) return family.getFirst();
        List<ContextAuthorization> student = studentAuthorization(user, file, false);
        if (!student.isEmpty()) return student.getFirst();
        throw notFound("FILE_NOT_FOUND", "The private file was not found.");
    }

    private ContextAuthorization requireManageAuthorization(AuthenticatedUser user, StoredFile file) {
        List<ContextAuthorization> staff = staffAuthorization(user, file, "files.manage", "learning.manage");
        if (!staff.isEmpty()) return staff.getFirst();
        if (file.submissionId() != null) {
            List<ContextAuthorization> family = familyAuthorization(user, file, true);
            if (!family.isEmpty()) return family.getFirst();
            List<ContextAuthorization> student = studentAuthorization(user, file, true);
            if (!student.isEmpty()) return student.getFirst();
        }
        throw notFound("FILE_NOT_FOUND", "The private file was not found.");
    }

    private List<ContextAuthorization> staffAuthorization(
            AuthenticatedUser user, StoredFile file, String fileCapability, String learningCapability) {
        UUID assignmentId = contextAssignmentId(file);
        try {
            StaffContext context = requireAssignmentStaff(
                    user, file.schoolId(), assignmentId, fileCapability, learningCapability);
            return List.of(context.authorization());
        } catch (ApiException exception) {
            if (exception.status() != HttpStatus.FORBIDDEN) throw exception;
            return List.of();
        }
    }

    private List<ContextAuthorization> familyAuthorization(
            AuthenticatedUser user, StoredFile file, boolean requireEditableSubmission) {
        return jdbc.query("""
                SELECT membership.id AS membership_id
                FROM memberships membership
                JOIN guardians guardian ON guardian.user_account_id = membership.user_id
                    AND guardian.organisation_id = membership.organisation_id
                    AND guardian.school_id = membership.school_id AND guardian.status = 'ACTIVE'
                JOIN learner_guardian_relationships relationship ON relationship.guardian_id = guardian.id
                    AND relationship.organisation_id = guardian.organisation_id
                    AND relationship.school_id = guardian.school_id
                    AND relationship.portal_access_status = 'ACTIVE'
                LEFT JOIN assignment_recipients recipient ON recipient.assignment_id = :assignmentId
                    AND recipient.learner_id = relationship.learner_id
                LEFT JOIN learner_assignment_submissions submission ON submission.id = :submissionId
                    AND submission.learner_id = relationship.learner_id
                LEFT JOIN assignments assignment ON assignment.id = COALESCE(recipient.assignment_id, submission.assignment_id)
                WHERE membership.user_id = :actorUserId AND membership.organisation_id = :organisationId
                  AND membership.school_id = :schoolId AND membership.id IN (:membershipIds)
                  AND membership.status = 'ACTIVE' AND membership.valid_from <= CURRENT_TIMESTAMP
                  AND (membership.valid_until IS NULL OR membership.valid_until > CURRENT_TIMESTAMP)
                  AND EXISTS (SELECT 1 FROM membership_roles mr JOIN role_capabilities rc ON rc.role_id = mr.role_id
                              WHERE mr.membership_id = membership.id AND rc.capability_code = 'portal.guardian')
                  AND :accessScope = 'CONTEXT_FAMILY'
                  AND ((:assignmentId IS NOT NULL AND recipient.id IS NOT NULL
                        AND assignment.status IN ('PUBLISHED', 'CLOSED')
                        AND assignment.available_from <= CURRENT_TIMESTAMP AND :requireEditable = FALSE)
                       OR (:submissionId IS NOT NULL AND submission.id IS NOT NULL
                           AND (:requireEditable = FALSE OR (assignment.status = 'PUBLISHED'
                               AND assignment.allow_attachments
                               AND assignment.available_from <= CURRENT_TIMESTAMP
                               AND (CURRENT_TIMESTAMP <= assignment.due_at
                                    OR (assignment.allow_late
                                        AND (assignment.late_until IS NULL
                                             OR CURRENT_TIMESTAMP <= assignment.late_until)))
                               AND submission.state IN ('NOT_STARTED', 'DRAFT', 'RETURNED')))))
                ORDER BY membership.created_at, membership.id LIMIT 1
                """, baseParameters(user).addValue("schoolId", file.schoolId())
                .addValue("assignmentId", file.assignmentId()).addValue("submissionId", file.submissionId())
                .addValue("accessScope", file.accessScope()).addValue("requireEditable", requireEditableSubmission),
                (result, row) -> new ContextAuthorization(user.activeOrganisationId(), file.schoolId(),
                        user.userId(), result.getObject("membership_id", UUID.class)));
    }

    private List<ContextAuthorization> studentAuthorization(
            AuthenticatedUser user, StoredFile file, boolean requireEditableSubmission) {
        if (user.activeOrganisationId() == null || user.membershipIds().isEmpty()) return List.of();
        return jdbc.query("""
                SELECT membership.id AS membership_id
                FROM learner_portal_accounts portal
                JOIN student_portal_consent_requests consent
                  ON consent.id=portal.current_consent_request_id
                 AND consent.organisation_id=portal.organisation_id AND consent.school_id=portal.school_id
                 AND consent.status='APPROVED' AND consent.approved_user_account_id=portal.user_account_id
                JOIN memberships membership ON membership.user_id=portal.user_account_id
                 AND membership.organisation_id=portal.organisation_id AND membership.school_id=portal.school_id
                 AND membership.status='ACTIVE' AND membership.valid_from<=CURRENT_TIMESTAMP
                 AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                LEFT JOIN assignment_recipients recipient ON recipient.assignment_id=:assignmentId
                 AND recipient.learner_id=portal.learner_id
                 AND recipient.organisation_id=portal.organisation_id AND recipient.school_id=portal.school_id
                LEFT JOIN learner_assignment_submissions submission ON submission.id=:submissionId
                 AND submission.learner_id=portal.learner_id
                 AND submission.organisation_id=portal.organisation_id AND submission.school_id=portal.school_id
                LEFT JOIN assignments assignment
                  ON assignment.id=COALESCE(recipient.assignment_id,submission.assignment_id)
                 AND assignment.organisation_id=portal.organisation_id AND assignment.school_id=portal.school_id
                WHERE portal.user_account_id=:actorUserId AND portal.organisation_id=:organisationId
                  AND portal.school_id=:schoolId AND portal.status='ACTIVE'
                  AND membership.id IN (:membershipIds)
                  AND EXISTS (SELECT 1 FROM membership_roles mr
                    JOIN role_capabilities rc ON rc.role_id=mr.role_id
                    WHERE mr.membership_id=membership.id AND rc.capability_code='portal.student')
                  AND :accessScope='CONTEXT_FAMILY'
                  AND ((:assignmentId IS NOT NULL AND recipient.id IS NOT NULL
                        AND assignment.status IN ('PUBLISHED','CLOSED')
                        AND assignment.available_from<=CURRENT_TIMESTAMP AND :requireEditable=FALSE)
                       OR (:submissionId IS NOT NULL AND submission.id IS NOT NULL
                           AND (:requireEditable=FALSE OR (assignment.status='PUBLISHED'
                               AND assignment.allow_attachments
                               AND assignment.available_from<=CURRENT_TIMESTAMP
                               AND (CURRENT_TIMESTAMP<=assignment.due_at
                                    OR (assignment.allow_late
                                        AND (assignment.late_until IS NULL
                                             OR CURRENT_TIMESTAMP<=assignment.late_until)))
                               AND submission.state IN ('NOT_STARTED','DRAFT','RETURNED')))))
                ORDER BY membership.created_at,membership.id LIMIT 1
                """, baseParameters(user).addValue("schoolId", file.schoolId())
                .addValue("assignmentId", file.assignmentId()).addValue("submissionId", file.submissionId())
                .addValue("accessScope", file.accessScope()).addValue("requireEditable", requireEditableSubmission),
                (result, row) -> new ContextAuthorization(user.activeOrganisationId(), file.schoolId(),
                        user.userId(), result.getObject("membership_id", UUID.class)));
    }

    private UUID contextAssignmentId(StoredFile file) {
        if (file.assignmentId() != null) return file.assignmentId();
        return submissionAssignmentId(file.submissionId(), file.organisationId(), file.schoolId());
    }

    private UUID submissionAssignmentId(UUID submissionId, UUID organisationId, UUID schoolId) {
        UUID value = jdbc.queryForObject("""
                SELECT assignment_id FROM learner_assignment_submissions
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                """, new MapSqlParameterSource().addValue("id", submissionId)
                .addValue("organisationId", organisationId).addValue("schoolId", schoolId), UUID.class);
        if (value == null) throw notFound("FILE_NOT_FOUND", "The private file was not found.");
        return value;
    }

    private void fileHistory(
            ContextAuthorization context,
            UUID fileId,
            String event,
            String previous,
            String next,
            String reason) {
        jdbc.update("""
                INSERT INTO private_file_history (
                    id, organisation_id, school_id, private_file_id, event_type,
                    previous_status, new_status, reason, actor_user_id,
                    actor_membership_id, occurred_at
                ) VALUES (:id, :organisationId, :schoolId, :fileId, :event,
                    :previous, :next, :reason, :actorUserId, :actorMembershipId, :now)
                """, context.parameters().addValue("id", UUID.randomUUID()).addValue("fileId", fileId)
                .addValue("event", event).addValue("previous", previous).addValue("next", next)
                .addValue("reason", reason).addValue("now", Instant.now(clock)));
    }

    private void submissionAttachmentHistory(
            ContextAuthorization context,
            UUID submissionId,
            String previous,
            String next,
            String reason) {
        jdbc.update("""
                INSERT INTO learner_submission_history (
                    id, organisation_id, school_id, learner_assignment_submission_id,
                    previous_evidence, new_evidence, change_kind, reason,
                    actor_user_id, actor_membership_id, occurred_at
                ) VALUES (:id, :organisationId, :schoolId, :submissionId,
                    CAST(:previous AS jsonb), CAST(:next AS jsonb), 'ATTACHMENT_CHANGE', :reason,
                    :actorUserId, :actorMembershipId, :now)
                """, context.parameters().addValue("id", UUID.randomUUID()).addValue("submissionId", submissionId)
                .addValue("previous", previous).addValue("next", next).addValue("reason", reason)
                .addValue("now", Instant.now(clock)));
    }

    private String submissionEvidence(UUID submissionId) {
        return jdbc.queryForObject("""
                SELECT jsonb_build_object(
                    'state', state, 'responseText', response_text, 'responseLink', response_link,
                    'hasAttachmentEvidence', has_attachment_evidence,
                    'submittedByUserId', submitted_by_user_id,
                    'submittedByMembershipId', submitted_by_membership_id,
                    'submittedAt', submitted_at, 'teacherFeedback', teacher_feedback,
                    'pointsAwarded', points_awarded, 'reviewedByMembershipId', reviewed_by_membership_id,
                    'reviewedAt', reviewed_at)::text
                FROM learner_assignment_submissions WHERE id = :id
                """, new MapSqlParameterSource("id", submissionId), String.class);
    }

    private long submissionVersion(UUID submissionId, ContextAuthorization context) {
        Long version = jdbc.queryForObject("""
                SELECT row_version FROM learner_assignment_submissions
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                """, context.parameters().addValue("id", submissionId), Long.class);
        return Objects.requireNonNull(version);
    }

    private long touchAssignment(UUID assignmentId, ContextAuthorization context) {
        Long version = jdbc.queryForObject("""
                UPDATE assignments SET updated_at = :now, row_version = row_version + 1
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                  AND status = 'DRAFT'
                RETURNING row_version
                """, context.parameters().addValue("id", assignmentId).addValue("now", Instant.now(clock)), Long.class);
        if (version == null) throw conflict("ASSIGNMENT_RESOURCE_FROZEN",
                "Assignment resources can only change while the assignment is a draft.");
        return version;
    }

    private Map<UUID, List<PrivateFileSummary>> summariesByContext(
            String contextColumn, UUID organisationId, UUID schoolId, List<UUID> contextIds) {
        if (!Set.of("assignment_id", "learner_assignment_submission_id").contains(contextColumn)) {
            throw new IllegalArgumentException("Unsupported private file context");
        }
        if (contextIds == null || contextIds.isEmpty()) return Map.of();
        Map<UUID, List<PrivateFileSummary>> grouped = new LinkedHashMap<>();
        jdbc.query("""
                SELECT %s AS context_id, id, download_filename, detected_media_type, size_bytes,
                       sha256_hex, purpose, available_at, replaces_file_id, row_version
                FROM private_files
                WHERE organisation_id = :organisationId AND school_id = :schoolId
                  AND %s IN (:contextIds) AND lifecycle_status = 'AVAILABLE'
                ORDER BY available_at, id
                """.formatted(contextColumn, contextColumn), new MapSqlParameterSource()
                .addValue("organisationId", organisationId).addValue("schoolId", schoolId)
                .addValue("contextIds", contextIds), result -> {
            UUID contextId = result.getObject("context_id", UUID.class);
            grouped.computeIfAbsent(contextId, ignored -> new java.util.ArrayList<>()).add(new PrivateFileSummary(
                    result.getObject("id", UUID.class), result.getString("download_filename"),
                    result.getString("detected_media_type"), result.getLong("size_bytes"),
                    result.getString("sha256_hex"), result.getString("purpose"),
                    result.getTimestamp("available_at").toInstant(), result.getObject("replaces_file_id", UUID.class),
                    result.getLong("row_version")));
        });
        grouped.replaceAll((id, values) -> List.copyOf(values));
        return Map.copyOf(grouped);
    }

    private static PrivateFileSummary summary(
            UUID id, String purpose, UUID replacesFileId, QuarantinedFile file, Instant availableAt) {
        return new PrivateFileSummary(id, file.downloadFilename(), file.mediaType(), file.sizeBytes(),
                file.sha256Hex(), purpose, availableAt, replacesFileId, 0);
    }

    private MapSqlParameterSource baseParameters(AuthenticatedUser user) {
        return new MapSqlParameterSource().addValue("actorUserId", user.userId())
                .addValue("organisationId", user.activeOrganisationId())
                .addValue("membershipIds", user.membershipIds());
    }

    private static void requireOrganisation(AuthenticatedUser user) {
        if (user.activeOrganisationId() == null) {
            throw conflict("ORGANISATION_SELECTION_REQUIRED", "Select a school organisation before opening files.");
        }
    }

    private static String requiredReason(String value) {
        if (value == null || value.isBlank()) throw badRequest("FILE_REASON_REQUIRED", "Explain why this attachment is being removed.");
        String result = value.replaceAll("[\\p{Cntrl}]", " ").strip();
        if (result.length() > 240) throw badRequest("FILE_REASON_TOO_LONG", "Keep the file reason within 240 characters.");
        return result;
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

    @FunctionalInterface
    private interface ContextRefresh { StaffContext current(); }

    private record StaffContext(
            UUID organisationId,
            UUID schoolId,
            UUID actorUserId,
            UUID membershipId,
            String assignmentStatus,
            long assignmentVersion) {
        MapSqlParameterSource parameters() { return authorization().parameters(); }
        ContextAuthorization authorization() {
            return new ContextAuthorization(organisationId, schoolId, actorUserId, membershipId);
        }
    }

    private record FamilySubmissionContext(
            StaffContext staffContext,
            UUID submissionId,
            long submissionVersion,
            String submissionState,
            boolean attachmentsAllowed,
            int maxAttachmentCount,
            int activeAttachmentCount,
            Instant availableFrom,
            Instant dueAt,
            boolean allowLate,
            Instant lateUntil) {}

    private record ContextAuthorization(
            UUID organisationId, UUID schoolId, UUID actorUserId, UUID membershipId) {
        MapSqlParameterSource parameters() {
            return new MapSqlParameterSource().addValue("organisationId", organisationId)
                    .addValue("schoolId", schoolId).addValue("actorUserId", actorUserId)
                    .addValue("actorMembershipId", membershipId);
        }
    }

    private record StoredFile(
            UUID id,
            UUID organisationId,
            UUID schoolId,
            UUID assignmentId,
            UUID submissionId,
            String purpose,
            String accessScope,
            String downloadFilename,
            String mediaType,
            long sizeBytes,
            String sha256,
            String storageKey,
            long rowVersion) {}
}
