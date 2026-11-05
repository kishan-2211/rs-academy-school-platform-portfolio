package com.educore.api.students.imports;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class StudentImportService {

    private static final String IMPORT = "student.import";
    private static final List<String> COMMIT_CAPABILITIES =
            List.of(IMPORT, "student.manage", "guardian.manage");
    private static final Set<String> GENDER_CODES =
            Set.of("FEMALE", "MALE", "NON_BINARY", "SELF_DESCRIBED", "NOT_STATED");
    private static final Set<String> LEARNER_RESOLUTIONS = Set.of("CREATE_NEW", "SKIP");
    private static final Set<String> GUARDIAN_RESOLUTIONS = Set.of("CREATE_NEW", "LINK_EXISTING");

    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public StudentImportService(
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
    public StudentImportOptionsResponse options(AuthenticatedUser user) {
        return new StudentImportOptionsResponse(jdbc.query("""
                SELECT school.id AS school_id, school.display_name AS school_name,
                       campus.id AS campus_id, campus.code AS campus_code,
                       campus.display_name AS campus_name, session.id AS session_id,
                       session.name AS session_name, session.starts_on, session.ends_on
                FROM memberships membership
                JOIN membership_roles membership_role ON membership_role.membership_id = membership.id
                JOIN role_capabilities capability ON capability.role_id = membership_role.role_id
                JOIN schools school ON school.id = membership.school_id
                    AND school.organisation_id = membership.organisation_id
                JOIN campuses campus ON campus.school_id = school.id
                    AND campus.organisation_id = school.organisation_id
                    AND campus.status = 'ACTIVE'
                JOIN academic_sessions session ON session.school_id = school.id
                    AND session.organisation_id = school.organisation_id
                    AND session.status IN ('PLANNED', 'ACTIVE')
                WHERE membership.user_id = :actorUserId
                  AND membership.organisation_id = :organisationId
                  AND membership.status = 'ACTIVE'
                  AND membership.valid_from <= :now
                  AND (membership.valid_until IS NULL OR membership.valid_until > :now)
                  AND capability.capability_code IN (:requiredCapabilities)
                GROUP BY school.id, campus.id, session.id
                HAVING count(DISTINCT capability.capability_code) = :capabilityCount
                ORDER BY school.display_name, campus.display_name, session.starts_on DESC
                """, new MapSqlParameterSource()
                .addValue("actorUserId", user.userId())
                .addValue("organisationId", requireOrganisation(user))
                .addValue("requiredCapabilities", COMMIT_CAPABILITIES)
                .addValue("capabilityCount", COMMIT_CAPABILITIES.size())
                .addValue("now", Instant.now(clock)), (result, row) -> new ImportScopeOption(
                result.getObject("school_id", UUID.class),
                result.getString("school_name"),
                result.getObject("campus_id", UUID.class),
                result.getString("campus_code"),
                result.getString("campus_name"),
                result.getObject("session_id", UUID.class),
                result.getString("session_name"),
                result.getObject("starts_on", LocalDate.class),
                result.getObject("ends_on", LocalDate.class))));
    }

    @Transactional(readOnly = true)
    public StudentImportPageResponse list(AuthenticatedUser user, int page, int size) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("actorUserId", user.userId())
                .addValue("organisationId", requireOrganisation(user))
                .addValue("requiredCapability", IMPORT)
                .addValue("now", Instant.now(clock))
                .addValue("limit", size)
                .addValue("offset", Math.multiplyExact(page, size));
        String visible = """
                batch.organisation_id = :organisationId
                AND EXISTS (
                    SELECT 1
                    FROM memberships membership
                    JOIN membership_roles membership_role ON membership_role.membership_id = membership.id
                    JOIN role_capabilities capability ON capability.role_id = membership_role.role_id
                    WHERE membership.user_id = :actorUserId
                      AND membership.organisation_id = batch.organisation_id
                      AND membership.school_id = batch.school_id
                      AND membership.status = 'ACTIVE'
                      AND membership.valid_from <= :now
                      AND (membership.valid_until IS NULL OR membership.valid_until > :now)
                      AND capability.capability_code = :requiredCapability
                )
                """;
        List<StudentImportBatchSummary> items = jdbc.query(batchSelect()
                        + " WHERE " + visible
                        + " ORDER BY batch.created_at DESC, batch.id DESC LIMIT :limit OFFSET :offset",
                parameters, StudentImportService::mapBatch);
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM student_import_batches batch WHERE " + visible,
                parameters,
                Long.class);
        return new StudentImportPageResponse(items, page, size, total == null ? 0 : total);
    }

    @Transactional
    public StudentImportBatchDetail preview(
            AuthenticatedUser user,
            PreviewStudentImportRequest request) {
        Instant now = Instant.now(clock);
        ImportScope scope = resolveScope(user, request.campusId(), request.academicSessionId(), now);
        if (request.startsOn().isBefore(scope.sessionStartsOn())
                || request.startsOn().isAfter(scope.sessionEndsOn())) {
            throw badRequest(
                    "IMPORT_START_OUTSIDE_SESSION",
                    "The enrolment start date must fall inside the selected academic session.");
        }
        List<StoredBatch> existing = jdbc.query("""
                SELECT batch.id, batch.organisation_id, batch.school_id, batch.campus_id,
                       batch.academic_session_id, batch.starts_on, batch.status,
                       batch.created_by_membership_id, batch.row_version
                FROM student_import_batches batch
                WHERE batch.organisation_id = :organisationId
                  AND batch.school_id = :schoolId
                  AND batch.campus_id = :campusId
                  AND batch.academic_session_id = :academicSessionId
                  AND batch.file_sha256 = :fileSha256
                """, scopeParameters(scope)
                .addValue("fileSha256", request.fileSha256()), StudentImportService::mapStoredBatch);
        if (!existing.isEmpty()) return detail(existing.getFirst());

        Set<Integer> rowNumbers = new HashSet<>();
        List<PreparedRow> rows = new ArrayList<>(request.rows().size());
        for (StudentImportRowInput input : request.rows()) {
            if (!rowNumbers.add(input.sourceRowNumber())) {
                throw badRequest("DUPLICATE_SOURCE_ROW", "Each source row number must appear only once.");
            }
            rows.add(prepare(input, LocalDate.now(clock)));
        }
        validateWithinFile(rows);
        attachExistingCandidates(scope, rows);

        int ready = 0;
        int review = 0;
        int invalid = 0;
        for (PreparedRow row : rows) {
            if (!row.errors.isEmpty()) {
                row.status = "INVALID";
                invalid++;
            } else if (row.learnerCandidate != null || row.guardianCandidate != null) {
                row.status = "REVIEW";
                review++;
            } else {
                row.status = "READY";
                row.learnerResolution = "CREATE_NEW";
                row.guardianResolution = "CREATE_NEW";
                ready++;
            }
        }

        UUID batchId = UUID.randomUUID();
        String batchStatus = review > 0 || invalid > 0 ? "REVIEW_REQUIRED" : "READY";
        int inserted = jdbc.update("""
                    INSERT INTO student_import_batches (
                        id, organisation_id, school_id, campus_id, academic_session_id,
                        starts_on, source_file_name, file_sha256, status, total_rows,
                        ready_rows, review_rows, invalid_rows, skipped_rows, committed_rows,
                        created_by_membership_id, created_at, updated_at, row_version
                    ) VALUES (
                        :id, :organisationId, :schoolId, :campusId, :academicSessionId,
                        :startsOn, :sourceFileName, :fileSha256, :status, :totalRows,
                        :readyRows, :reviewRows, :invalidRows, 0, 0,
                        :actorMembershipId, :now, :now, 0
                    )
                    ON CONFLICT (school_id, campus_id, academic_session_id, file_sha256) DO NOTHING
                    """, scopeParameters(scope)
                    .addValue("id", batchId)
                    .addValue("startsOn", request.startsOn())
                    .addValue("sourceFileName", safeFileName(request.sourceFileName()))
                    .addValue("fileSha256", request.fileSha256())
                    .addValue("status", batchStatus)
                    .addValue("totalRows", rows.size())
                    .addValue("readyRows", ready)
                    .addValue("reviewRows", review)
                    .addValue("invalidRows", invalid)
                    .addValue("now", now));
        if (inserted == 0) {
            List<StoredBatch> retry = findByFingerprint(scope, request.fileSha256());
            if (!retry.isEmpty()) return detail(retry.getFirst());
            throw conflict("IMPORT_PREVIEW_CONFLICT", "The import preview conflicted with another request.");
        }
        try {
            for (PreparedRow row : rows) insertRow(scope, batchId, row, now);
        } catch (DataIntegrityViolationException exception) {
            throw conflict("IMPORT_PREVIEW_CONFLICT", "The import preview conflicted with another request.");
        }

        StoredBatch stored = new StoredBatch(
                batchId,
                scope.organisationId(),
                scope.schoolId(),
                scope.campusId(),
                scope.academicSessionId(),
                request.startsOn(),
                batchStatus,
                scope.actorMembershipId(),
                0);
        audit.recordScoped(user, scope.actorMembershipId(), scope.schoolId(), "student.import.previewed",
                "STUDENT_IMPORT_BATCH", batchId.toString(), AuditOutcome.SUCCEEDED, null,
                Map.of("totalRows", rows.size(), "readyRows", ready, "reviewRows", review, "invalidRows", invalid));
        return detail(stored);
    }

    @Transactional(readOnly = true)
    public StudentImportBatchDetail get(AuthenticatedUser user, UUID batchId) {
        return detail(findAccessible(user, batchId, List.of(IMPORT), false));
    }

    @Transactional
    public StudentImportBatchDetail resolve(
            AuthenticatedUser user,
            UUID batchId,
            UUID rowId,
            ResolveStudentImportRowRequest request) {
        StoredBatch batch = findAccessible(user, batchId, COMMIT_CAPABILITIES, true);
        if (Set.of("COMMITTED", "CANCELLED").contains(batch.status())) {
            throw conflict("IMPORT_NOT_EDITABLE", "This import batch is no longer editable.");
        }
        List<StoredRow> rows = jdbc.query("""
                SELECT row.id, row.status, jsonb_array_length(row.validation_errors) > 0 AS has_errors,
                       row.candidate_learner_id, row.candidate_guardian_id,
                       EXISTS (
                           SELECT 1 FROM student_enrolments enrolment
                           WHERE enrolment.organisation_id = row.organisation_id
                             AND enrolment.school_id = row.school_id
                             AND enrolment.learner_id = row.candidate_learner_id
                             AND lower(enrolment.admission_number) = lower(row.admission_number)
                       ) AS exact_admission_conflict,
                       row.row_version
                FROM student_import_rows row
                WHERE row.id = :rowId AND row.batch_id = :batchId
                  AND row.organisation_id = :organisationId AND row.school_id = :schoolId
                FOR UPDATE
                """, batchParameters(batch).addValue("rowId", rowId), (result, number) -> new StoredRow(
                result.getObject("id", UUID.class),
                result.getString("status"),
                result.getBoolean("has_errors"),
                result.getObject("candidate_learner_id", UUID.class),
                result.getObject("candidate_guardian_id", UUID.class),
                result.getBoolean("exact_admission_conflict"),
                result.getLong("row_version")));
        if (rows.isEmpty()) throw notFound();
        StoredRow row = rows.getFirst();
        if (row.version() != request.expectedVersion()) throw stale("The staged row changed before it was resolved.");
        if ("COMMITTED".equals(row.status())) throw conflict("IMPORT_ROW_COMMITTED", "A committed row cannot be changed.");

        String learnerResolution = upperClean(request.learnerResolution());
        String guardianResolution = upperClean(request.guardianResolution());
        if (!LEARNER_RESOLUTIONS.contains(learnerResolution)) {
            throw badRequest("INVALID_LEARNER_RESOLUTION", "Choose CREATE_NEW or SKIP for the learner.");
        }
        if ("CREATE_NEW".equals(learnerResolution) && row.exactAdmissionConflict()) {
            throw conflict(
                    "IMPORT_ADMISSION_NUMBER_EXISTS",
                    "An exact admission number already exists. Skip this row and review the existing learner record.");
        }
        String nextStatus;
        if ("SKIP".equals(learnerResolution)) {
            guardianResolution = null;
            nextStatus = "SKIPPED";
        } else {
            if (row.hasErrors()) {
                throw conflict("IMPORT_ROW_INVALID", "Invalid rows must be skipped or corrected in a new source file.");
            }
            if (!GUARDIAN_RESOLUTIONS.contains(guardianResolution)) {
                throw badRequest(
                        "INVALID_GUARDIAN_RESOLUTION",
                        "Choose CREATE_NEW or LINK_EXISTING for the guardian.");
            }
            if ("LINK_EXISTING".equals(guardianResolution) && row.guardianCandidateId() == null) {
                throw badRequest(
                        "GUARDIAN_CANDIDATE_REQUIRED",
                        "Linking requires the exact existing guardian candidate from this preview.");
            }
            nextStatus = "READY";
        }

        int updated = jdbc.update("""
                UPDATE student_import_rows
                SET status = :status, learner_resolution = :learnerResolution,
                    guardian_resolution = :guardianResolution, resolution_reason = :reason,
                    updated_at = :now, row_version = row_version + 1
                WHERE id = :rowId AND batch_id = :batchId
                  AND organisation_id = :organisationId AND school_id = :schoolId
                  AND row_version = :expectedVersion
                """, batchParameters(batch)
                .addValue("rowId", rowId)
                .addValue("status", nextStatus)
                .addValue("learnerResolution", learnerResolution)
                .addValue("guardianResolution", guardianResolution)
                .addValue("reason", clean(request.reason()))
                .addValue("now", Instant.now(clock))
                .addValue("expectedVersion", request.expectedVersion()));
        if (updated != 1) throw stale("The staged row changed before it was resolved.");
        recomputeBatch(batch, Instant.now(clock));
        audit.recordScoped(user, batch.actorMembershipId(), batch.schoolId(), "student.import.row.resolved",
                "STUDENT_IMPORT_ROW", rowId.toString(), AuditOutcome.SUCCEEDED, clean(request.reason()),
                Map.of("learnerResolution", learnerResolution,
                        "guardianResolution", guardianResolution == null ? "NONE" : guardianResolution));
        return detail(findAccessible(user, batchId, COMMIT_CAPABILITIES, false));
    }

    @Transactional
    public StudentImportBatchDetail commit(
            AuthenticatedUser user,
            UUID batchId,
            CommitStudentImportRequest request) {
        StoredBatch batch = findAccessible(user, batchId, COMMIT_CAPABILITIES, true);
        if ("COMMITTED".equals(batch.status())) return detail(batch);
        if (!"READY".equals(batch.status())) {
            throw conflict(
                    "IMPORT_NOT_READY",
                    "Resolve or skip every invalid and duplicate row before committing this import.");
        }
        if (batch.version() != request.expectedVersion()) throw stale("The import batch changed before it was committed.");
        List<CommitRow> rows = jdbc.query("""
                SELECT id, legal_name, preferred_name, birth_date, gender_code, gender_description,
                       grade_code, admission_number, guardian_name, guardian_external_key,
                       guardian_relationship_code, guardian_email_normalized, guardian_phone,
                       primary_contact, pickup_authorized, candidate_guardian_id,
                       guardian_resolution
                FROM student_import_rows
                WHERE batch_id = :batchId AND organisation_id = :organisationId
                  AND school_id = :schoolId AND status = 'READY'
                ORDER BY source_row_number
                FOR UPDATE
                """, batchParameters(batch), StudentImportService::mapCommitRow);
        Instant now = Instant.now(clock);
        Map<String, UUID> guardiansByExternalKey = new HashMap<>();
        try {
            for (CommitRow row : rows) {
                UUID guardianId = resolveGuardianForCommit(batch, row, guardiansByExternalKey, now);
                UUID learnerId = UUID.randomUUID();
                UUID enrolmentId = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO learners (
                            id, organisation_id, school_id, origin_import_row_id,
                            legal_name, preferred_name, birth_date, gender_code,
                            gender_description, status, created_at, updated_at, row_version
                        ) VALUES (
                            :learnerId, :organisationId, :schoolId, :rowId,
                            :legalName, :preferredName, :birthDate, :genderCode,
                            :genderDescription, 'ACTIVE', :now, :now, 0
                        )
                        """, batchParameters(batch)
                        .addValue("learnerId", learnerId)
                        .addValue("rowId", row.id())
                        .addValue("legalName", row.legalName())
                        .addValue("preferredName", row.preferredName())
                        .addValue("birthDate", row.birthDate())
                        .addValue("genderCode", row.genderCode())
                        .addValue("genderDescription", row.genderDescription())
                        .addValue("now", now));
                jdbc.update("""
                        INSERT INTO learner_guardian_relationships (
                            learner_id, guardian_id, organisation_id, school_id,
                            relationship_code, primary_contact, pickup_authorized,
                            portal_access_status, created_at, updated_at, row_version
                        ) VALUES (
                            :learnerId, :guardianId, :organisationId, :schoolId,
                            :relationshipCode, :primaryContact, :pickupAuthorized,
                            'NOT_INVITED', :now, :now, 0
                        )
                        """, batchParameters(batch)
                        .addValue("learnerId", learnerId)
                        .addValue("guardianId", guardianId)
                        .addValue("relationshipCode", row.guardianRelationshipCode())
                        .addValue("primaryContact", row.primaryContact())
                        .addValue("pickupAuthorized", row.pickupAuthorized())
                        .addValue("now", now));
                jdbc.update("""
                        INSERT INTO student_enrolments (
                            id, organisation_id, school_id, campus_id, academic_session_id,
                            learner_id, origin_import_row_id, grade_code, admission_number,
                            status, placement_status, starts_on, created_at, updated_at, row_version
                        ) VALUES (
                            :enrolmentId, :organisationId, :schoolId, :campusId, :academicSessionId,
                            :learnerId, :rowId, :gradeCode, :admissionNumber,
                            'ACTIVE', 'PENDING', :startsOn, :now, :now, 0
                        )
                        """, batchParameters(batch)
                        .addValue("enrolmentId", enrolmentId)
                        .addValue("learnerId", learnerId)
                        .addValue("rowId", row.id())
                        .addValue("gradeCode", row.gradeCode())
                        .addValue("admissionNumber", row.admissionNumber())
                        .addValue("startsOn", batch.startsOn())
                        .addValue("now", now));
                jdbc.update("""
                        UPDATE student_import_rows
                        SET status = 'COMMITTED', created_learner_id = :learnerId,
                            created_enrolment_id = :enrolmentId, resolved_guardian_id = :guardianId,
                            updated_at = :now,
                            row_version = row_version + 1
                        WHERE id = :rowId AND batch_id = :batchId
                          AND organisation_id = :organisationId AND school_id = :schoolId
                          AND status = 'READY'
                        """, batchParameters(batch)
                        .addValue("rowId", row.id())
                        .addValue("learnerId", learnerId)
                        .addValue("enrolmentId", enrolmentId)
                        .addValue("guardianId", guardianId)
                        .addValue("now", now));
            }
            int updated = jdbc.update("""
                    UPDATE student_import_batches
                    SET status = 'COMMITTED', committed_rows = ready_rows, ready_rows = 0,
                        committed_by_membership_id = :actorMembershipId, committed_at = :now,
                        updated_at = :now, row_version = row_version + 1
                    WHERE id = :batchId AND organisation_id = :organisationId
                      AND school_id = :schoolId AND status = 'READY'
                      AND row_version = :expectedVersion
                    """, batchParameters(batch)
                    .addValue("now", now)
                    .addValue("expectedVersion", request.expectedVersion()));
            if (updated != 1) throw stale("The import batch changed before it was committed.");
        } catch (DataIntegrityViolationException exception) {
            throw conflict(
                    "IMPORT_COMMIT_CONFLICT",
                    "A canonical learner, guardian, or admission number now conflicts with this preview. Create a fresh preview.");
        }
        audit.recordScoped(user, batch.actorMembershipId(), batch.schoolId(), "student.import.committed",
                "STUDENT_IMPORT_BATCH", batchId.toString(), AuditOutcome.SUCCEEDED, null,
                Map.of("committedRows", rows.size()));
        return detail(findAccessible(user, batchId, COMMIT_CAPABILITIES, false));
    }

    private ImportScope resolveScope(
            AuthenticatedUser user,
            UUID campusId,
            UUID academicSessionId,
            Instant now) {
        List<ImportScope> scopes = jdbc.query("""
                SELECT campus.organisation_id, campus.school_id, campus.id AS campus_id,
                       session.id AS session_id, session.starts_on, session.ends_on,
                       membership.id AS actor_membership_id
                FROM campuses campus
                JOIN academic_sessions session ON session.id = :academicSessionId
                    AND session.organisation_id = campus.organisation_id
                    AND session.school_id = campus.school_id
                JOIN memberships membership ON membership.user_id = :actorUserId
                    AND membership.organisation_id = campus.organisation_id
                    AND membership.school_id = campus.school_id
                    AND membership.status = 'ACTIVE'
                    AND membership.valid_from <= :now
                    AND (membership.valid_until IS NULL OR membership.valid_until > :now)
                JOIN membership_roles membership_role ON membership_role.membership_id = membership.id
                JOIN role_capabilities capability ON capability.role_id = membership_role.role_id
                WHERE campus.id = :campusId
                  AND campus.organisation_id = :organisationId
                  AND campus.status = 'ACTIVE'
                  AND session.status IN ('PLANNED', 'ACTIVE')
                  AND capability.capability_code IN (:requiredCapabilities)
                GROUP BY campus.organisation_id, campus.school_id, campus.id, session.id, membership.id
                HAVING count(DISTINCT capability.capability_code) = :capabilityCount
                ORDER BY membership.created_at, membership.id
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("academicSessionId", academicSessionId)
                .addValue("actorUserId", user.userId())
                .addValue("now", now)
                .addValue("campusId", campusId)
                .addValue("organisationId", requireOrganisation(user))
                .addValue("requiredCapabilities", COMMIT_CAPABILITIES)
                .addValue("capabilityCount", COMMIT_CAPABILITIES.size()), (result, row) -> new ImportScope(
                result.getObject("organisation_id", UUID.class),
                result.getObject("school_id", UUID.class),
                result.getObject("campus_id", UUID.class),
                result.getObject("session_id", UUID.class),
                result.getObject("starts_on", LocalDate.class),
                result.getObject("ends_on", LocalDate.class),
                result.getObject("actor_membership_id", UUID.class)));
        if (scopes.isEmpty()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "IMPORT_SCOPE_NOT_FOUND",
                    "The campus and academic session were not found in an authorized school.");
        }
        return scopes.getFirst();
    }

    private PreparedRow prepare(StudentImportRowInput input, LocalDate today) {
        PreparedRow row = new PreparedRow(input.sourceRowNumber());
        row.legalName = clean(input.legalName());
        row.preferredName = clean(input.preferredName());
        row.birthDate = parseDate(input.birthDate(), "birth_date", row.errors);
        row.genderCode = upperClean(input.genderCode());
        row.genderDescription = clean(input.genderDescription());
        row.gradeCode = upperClean(input.gradeCode());
        row.admissionNumber = clean(input.admissionNumber());
        row.guardianName = clean(input.guardianName());
        row.guardianExternalKey = clean(input.guardianExternalKey());
        row.guardianRelationshipCode = upperClean(input.guardianRelationshipCode());
        row.guardianEmail = lowerClean(input.guardianEmail());
        row.guardianPhone = clean(input.guardianPhone());
        row.primaryContact = input.primaryContact();
        row.pickupAuthorized = input.pickupAuthorized();

        require(row.legalName, "legal_name is required", row.errors);
        require(row.gradeCode, "grade_code is required", row.errors);
        require(row.guardianName, "guardian_name is required", row.errors);
        require(row.guardianRelationshipCode, "guardian_relationship_code is required", row.errors);
        if (row.birthDate != null && !row.birthDate.isBefore(today)) {
            row.errors.add("birth_date must be in the past");
        }
        if (row.birthDate != null && row.birthDate.isBefore(today.minusYears(120))) {
            row.errors.add("birth_date is outside the supported range");
        }
        if (!GENDER_CODES.contains(row.genderCode)) {
            row.errors.add("gender_code must be FEMALE, MALE, NON_BINARY, SELF_DESCRIBED, or NOT_STATED");
            row.genderCode = null;
            row.genderDescription = null;
        } else if ("SELF_DESCRIBED".equals(row.genderCode) != (row.genderDescription != null)) {
            row.errors.add("gender_description is required only for SELF_DESCRIBED");
        }
        if (row.guardianEmail == null && row.guardianPhone == null) {
            row.errors.add("guardian_email or guardian_phone is required");
        }
        if (row.guardianEmail != null && !row.guardianEmail.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            row.errors.add("guardian_email is not valid");
            row.guardianEmail = null;
        }
        return row;
    }

    private void validateWithinFile(List<PreparedRow> rows) {
        Map<String, List<PreparedRow>> admissionNumbers = grouped(rows, row -> lowerClean(row.admissionNumber));
        for (List<PreparedRow> duplicates : admissionNumbers.values()) {
            if (duplicates.size() > 1) duplicates.forEach(row -> row.errors.add("admission_number is duplicated in this file"));
        }
        Map<String, List<PreparedRow>> learnerKeys = grouped(rows, row -> row.legalName == null || row.birthDate == null
                ? null : row.legalName.toLowerCase(Locale.ROOT) + "|" + row.birthDate);
        for (List<PreparedRow> duplicates : learnerKeys.values()) {
            if (duplicates.size() > 1) duplicates.forEach(row -> row.errors.add("learner name and birth_date are duplicated in this file"));
        }

        validateReusedGuardianContacts(grouped(rows, row -> row.guardianEmail));
        validateReusedGuardianContacts(grouped(rows, row -> row.guardianPhone));
        Map<String, List<PreparedRow>> guardianExternalKeys = grouped(rows, row -> row.guardianExternalKey);
        for (List<PreparedRow> matches : guardianExternalKeys.values()) {
            if (matches.stream().map(StudentImportService::guardianSignature).distinct().count() > 1) {
                matches.forEach(row -> row.errors.add("guardian_external_key has inconsistent guardian details"));
            }
        }
    }

    private static void validateReusedGuardianContacts(Map<String, List<PreparedRow>> contacts) {
        for (List<PreparedRow> matches : contacts.values()) {
            if (matches.size() <= 1) continue;
            Set<String> externalKeys = matches.stream()
                    .map(row -> row.guardianExternalKey)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            boolean oneExplicitKey = externalKeys.size() == 1 && externalKeys.iterator().next() != null;
            boolean consistent = matches.stream().map(StudentImportService::guardianSignature).distinct().count() == 1;
            if (!oneExplicitKey || !consistent) {
                matches.forEach(row -> row.errors.add(
                        "reused guardian contact requires one matching guardian_external_key and consistent details"));
            }
        }
    }

    private void attachExistingCandidates(ImportScope scope, List<PreparedRow> rows) {
        Set<String> admissionNumbers = rows.stream()
                .map(row -> lowerClean(row.admissionNumber))
                .filter(value -> value != null)
                .collect(Collectors.toSet());
        Set<String> names = rows.stream()
                .map(row -> row.legalName == null ? null : row.legalName.toLowerCase(Locale.ROOT))
                .filter(value -> value != null)
                .collect(Collectors.toSet());
        Set<LocalDate> dates = rows.stream().map(row -> row.birthDate).filter(value -> value != null).collect(Collectors.toSet());
        Map<UUID, LearnerCandidateData> learnerById = new LinkedHashMap<>();
        if (!admissionNumbers.isEmpty() || (!names.isEmpty() && !dates.isEmpty())) {
            jdbc.query("""
                    SELECT learner.id, learner.legal_name, learner.birth_date, learner.status,
                           enrolment.admission_number
                    FROM learners learner
                    LEFT JOIN LATERAL (
                        SELECT candidate.admission_number
                        FROM student_enrolments candidate
                        WHERE candidate.learner_id = learner.id
                          AND candidate.organisation_id = learner.organisation_id
                          AND candidate.school_id = learner.school_id
                        ORDER BY (lower(candidate.admission_number) IN (:admissionNumbers)) DESC,
                                 (candidate.status = 'ACTIVE') DESC, candidate.starts_on DESC
                        LIMIT 1
                    ) enrolment ON true
                    WHERE learner.organisation_id = :organisationId
                      AND learner.school_id = :schoolId
                      AND (
                        lower(coalesce(enrolment.admission_number, '')) IN (:admissionNumbers)
                        OR (lower(learner.legal_name) IN (:names) AND learner.birth_date IN (:birthDates))
                      )
                    """, scopeParameters(scope)
                    .addValue("admissionNumbers", admissionNumbers.isEmpty() ? Set.of("") : admissionNumbers)
                    .addValue("names", names.isEmpty() ? Set.of("") : names)
                    .addValue("birthDates", dates.isEmpty() ? Set.of(LocalDate.of(1900, 1, 1)) : dates), result -> {
                        UUID id = result.getObject("id", UUID.class);
                        learnerById.put(id, new LearnerCandidateData(
                                id,
                                result.getString("legal_name"),
                                result.getObject("birth_date", LocalDate.class),
                                result.getString("admission_number"),
                                result.getString("status")));
                    });
        }

        Set<String> emails = rows.stream().map(row -> row.guardianEmail).filter(value -> value != null).collect(Collectors.toSet());
        Set<String> phones = rows.stream().map(row -> row.guardianPhone).filter(value -> value != null).collect(Collectors.toSet());
        Map<UUID, GuardianCandidateData> guardianById = new LinkedHashMap<>();
        if (!emails.isEmpty() || !phones.isEmpty()) {
            jdbc.query("""
                    SELECT id, display_name, email_normalized, phone, status
                    FROM guardians
                    WHERE organisation_id = :organisationId AND school_id = :schoolId
                      AND (email_normalized IN (:emails) OR phone IN (:phones))
                    """, scopeParameters(scope)
                    .addValue("emails", emails.isEmpty() ? Set.of("") : emails)
                    .addValue("phones", phones.isEmpty() ? Set.of("") : phones), result -> {
                        UUID id = result.getObject("id", UUID.class);
                        guardianById.put(id, new GuardianCandidateData(
                                id,
                                result.getString("display_name"),
                                result.getString("email_normalized"),
                                result.getString("phone"),
                                result.getString("status")));
                    });
        }

        for (PreparedRow row : rows) {
            List<LearnerCandidateData> learnerMatches = learnerById.values().stream()
                    .filter(candidate -> matchesLearner(row, candidate))
                    .toList();
            if (learnerMatches.size() > 1) {
                row.errors.add("multiple existing learners match this row; review the source outside this import");
            } else if (learnerMatches.size() == 1) {
                LearnerCandidateData candidate = learnerMatches.getFirst();
                if (!"ACTIVE".equals(candidate.status())) {
                    row.errors.add("the matching learner is not active; review the existing record outside this import");
                }
                String reason = row.admissionNumber != null && candidate.admissionNumber() != null
                        && row.admissionNumber.equalsIgnoreCase(candidate.admissionNumber())
                        ? "ADMISSION_NUMBER"
                        : "NAME_AND_BIRTH_DATE";
                row.learnerCandidate = new DuplicateLearnerCandidate(
                        candidate.id(), reference("STU", candidate.id()), candidate.legalName(),
                        candidate.birthDate(), candidate.admissionNumber(), reason);
            }
            List<GuardianCandidateData> guardianMatches = guardianById.values().stream()
                    .filter(candidate -> matchesGuardian(row, candidate))
                    .toList();
            if (guardianMatches.size() > 1) {
                row.errors.add("multiple existing guardians match this contact; review the source outside this import");
            } else if (guardianMatches.size() == 1) {
                GuardianCandidateData candidate = guardianMatches.getFirst();
                if (!"ACTIVE".equals(candidate.status())) {
                    row.errors.add("the matching guardian is not active; review the existing record outside this import");
                }
                String reason = row.guardianEmail != null && row.guardianEmail.equals(candidate.email())
                        ? "EMAIL" : "PHONE";
                row.guardianCandidate = new DuplicateGuardianCandidate(
                        candidate.id(), candidate.displayName(), maskEmail(candidate.email()),
                        maskPhone(candidate.phone()), reason);
            }
        }
    }

    private void insertRow(ImportScope scope, UUID batchId, PreparedRow row, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO student_import_rows (
                    id, batch_id, organisation_id, school_id, source_row_number,
                    legal_name, preferred_name, birth_date, gender_code, gender_description,
                    grade_code, admission_number, guardian_name, guardian_external_key,
                    guardian_relationship_code, guardian_email_normalized, guardian_phone,
                    primary_contact, pickup_authorized, status, validation_errors,
                    candidate_learner_id, candidate_guardian_id, learner_resolution,
                    guardian_resolution, created_at, updated_at, row_version
                ) VALUES (
                    :id, :batchId, :organisationId, :schoolId, :sourceRowNumber,
                    :legalName, :preferredName, :birthDate, :genderCode, :genderDescription,
                    :gradeCode, :admissionNumber, :guardianName, :guardianExternalKey,
                    :guardianRelationshipCode, :guardianEmail, :guardianPhone,
                    :primaryContact, :pickupAuthorized, :status, CAST(:validationErrors AS jsonb),
                    :candidateLearnerId, :candidateGuardianId, :learnerResolution,
                    :guardianResolution, :now, :now, 0
                )
                """, scopeParameters(scope)
                .addValue("id", id)
                .addValue("batchId", batchId)
                .addValue("sourceRowNumber", row.sourceRowNumber)
                .addValue("legalName", row.legalName)
                .addValue("preferredName", row.preferredName)
                .addValue("birthDate", row.birthDate)
                .addValue("genderCode", row.genderCode)
                .addValue("genderDescription", row.genderDescription)
                .addValue("gradeCode", row.gradeCode)
                .addValue("admissionNumber", row.admissionNumber)
                .addValue("guardianName", row.guardianName)
                .addValue("guardianExternalKey", row.guardianExternalKey)
                .addValue("guardianRelationshipCode", row.guardianRelationshipCode)
                .addValue("guardianEmail", row.guardianEmail)
                .addValue("guardianPhone", row.guardianPhone)
                .addValue("primaryContact", row.primaryContact)
                .addValue("pickupAuthorized", row.pickupAuthorized)
                .addValue("status", row.status)
                .addValue("validationErrors", json(row.errors))
                .addValue("candidateLearnerId", row.learnerCandidate == null ? null : row.learnerCandidate.learnerId())
                .addValue("candidateGuardianId", row.guardianCandidate == null ? null : row.guardianCandidate.guardianId())
                .addValue("learnerResolution", row.learnerResolution)
                .addValue("guardianResolution", row.guardianResolution)
                .addValue("now", now));
    }

    private StudentImportBatchDetail detail(StoredBatch batch) {
        List<StudentImportBatchSummary> summaries = jdbc.query(batchSelect() + """
                WHERE batch.id = :batchId AND batch.organisation_id = :organisationId
                  AND batch.school_id = :schoolId
                """, batchParameters(batch), StudentImportService::mapBatch);
        if (summaries.isEmpty()) throw notFound();
        List<StudentImportRowResponse> rows = jdbc.query("""
                SELECT row.id, row.source_row_number, row.legal_name, row.preferred_name,
                       row.birth_date, row.gender_code, row.gender_description, row.grade_code,
                       row.admission_number, row.guardian_name, row.guardian_external_key,
                       row.guardian_relationship_code, row.guardian_email_normalized,
                       row.guardian_phone, row.primary_contact, row.pickup_authorized,
                       row.status, ARRAY(SELECT jsonb_array_elements_text(row.validation_errors)) AS errors,
                       row.learner_resolution, row.guardian_resolution, row.resolution_reason,
                       row.resolved_guardian_id, row.created_learner_id, row.created_enrolment_id,
                       row.row_version,
                       learner.id AS candidate_learner_id, learner.legal_name AS candidate_learner_name,
                       learner.birth_date AS candidate_learner_birth_date,
                       candidate_enrolment.admission_number AS candidate_admission_number,
                       guardian.id AS candidate_guardian_id, guardian.display_name AS candidate_guardian_name,
                       guardian.email_normalized AS candidate_guardian_email,
                       guardian.phone AS candidate_guardian_phone
                FROM student_import_rows row
                LEFT JOIN learners learner ON learner.id = row.candidate_learner_id
                    AND learner.organisation_id = row.organisation_id AND learner.school_id = row.school_id
                LEFT JOIN LATERAL (
                    SELECT enrolment.admission_number
                    FROM student_enrolments enrolment
                    WHERE enrolment.learner_id = learner.id
                      AND enrolment.organisation_id = learner.organisation_id
                      AND enrolment.school_id = learner.school_id
                    ORDER BY (lower(enrolment.admission_number) = lower(row.admission_number)) DESC,
                             (enrolment.status = 'ACTIVE') DESC, enrolment.starts_on DESC
                    LIMIT 1
                ) candidate_enrolment ON true
                LEFT JOIN guardians guardian ON guardian.id = row.candidate_guardian_id
                    AND guardian.organisation_id = row.organisation_id AND guardian.school_id = row.school_id
                WHERE row.batch_id = :batchId AND row.organisation_id = :organisationId
                  AND row.school_id = :schoolId
                ORDER BY row.source_row_number
                """, batchParameters(batch), StudentImportService::mapRow);
        return new StudentImportBatchDetail(summaries.getFirst(), rows);
    }

    private StoredBatch findAccessible(
            AuthenticatedUser user,
            UUID batchId,
            List<String> capabilities,
            boolean lock) {
        List<StoredBatch> batches = jdbc.query("""
                SELECT batch.id, batch.organisation_id, batch.school_id, batch.campus_id,
                       batch.academic_session_id, batch.starts_on, batch.status,
                       membership.id AS actor_membership_id, batch.row_version
                FROM student_import_batches batch
                JOIN memberships membership ON membership.user_id = :actorUserId
                    AND membership.organisation_id = batch.organisation_id
                    AND membership.school_id = batch.school_id
                    AND membership.status = 'ACTIVE'
                    AND membership.valid_from <= :now
                    AND (membership.valid_until IS NULL OR membership.valid_until > :now)
                JOIN membership_roles membership_role ON membership_role.membership_id = membership.id
                JOIN role_capabilities capability ON capability.role_id = membership_role.role_id
                WHERE batch.id = :batchId AND batch.organisation_id = :organisationId
                  AND capability.capability_code IN (:requiredCapabilities)
                GROUP BY batch.id, membership.id
                HAVING count(DISTINCT capability.capability_code) = :capabilityCount
                ORDER BY membership.created_at, membership.id
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("actorUserId", user.userId())
                .addValue("organisationId", requireOrganisation(user))
                .addValue("batchId", batchId)
                .addValue("requiredCapabilities", capabilities)
                .addValue("capabilityCount", capabilities.size())
                .addValue("now", Instant.now(clock)), StudentImportService::mapStoredBatch);
        if (batches.isEmpty()) throw notFound();
        StoredBatch batch = batches.getFirst();
        if (!lock) return batch;
        Long lockedVersion = jdbc.queryForObject("""
                SELECT row_version
                FROM student_import_batches
                WHERE id = :batchId AND organisation_id = :organisationId AND school_id = :schoolId
                FOR UPDATE
                """, batchParameters(batch), Long.class);
        return new StoredBatch(
                batch.id(), batch.organisationId(), batch.schoolId(), batch.campusId(),
                batch.academicSessionId(), batch.startsOn(), batch.status(),
                batch.actorMembershipId(), lockedVersion == null ? batch.version() : lockedVersion);
    }

    private List<StoredBatch> findByFingerprint(ImportScope scope, String sha256) {
        return jdbc.query("""
                SELECT id, organisation_id, school_id, campus_id, academic_session_id,
                       starts_on, status, created_by_membership_id, row_version
                FROM student_import_batches
                WHERE organisation_id = :organisationId AND school_id = :schoolId
                  AND campus_id = :campusId AND academic_session_id = :academicSessionId
                  AND file_sha256 = :fileSha256
                """, scopeParameters(scope).addValue("fileSha256", sha256), StudentImportService::mapStoredBatch);
    }

    private void recomputeBatch(StoredBatch batch, Instant now) {
        jdbc.update("""
                WITH counts AS (
                    SELECT count(*) FILTER (WHERE status = 'READY') AS ready_rows,
                           count(*) FILTER (WHERE status = 'REVIEW') AS review_rows,
                           count(*) FILTER (WHERE status = 'INVALID') AS invalid_rows,
                           count(*) FILTER (WHERE status = 'SKIPPED') AS skipped_rows,
                           count(*) FILTER (WHERE status = 'COMMITTED') AS committed_rows
                    FROM student_import_rows
                    WHERE batch_id = :batchId AND organisation_id = :organisationId
                      AND school_id = :schoolId
                )
                UPDATE student_import_batches batch
                SET ready_rows = counts.ready_rows, review_rows = counts.review_rows,
                    invalid_rows = counts.invalid_rows, skipped_rows = counts.skipped_rows,
                    committed_rows = counts.committed_rows,
                    status = CASE
                        WHEN counts.invalid_rows > 0 OR counts.review_rows > 0 THEN 'REVIEW_REQUIRED'
                        ELSE 'READY'
                    END,
                    updated_at = :now, row_version = row_version + 1
                FROM counts
                WHERE batch.id = :batchId AND batch.organisation_id = :organisationId
                  AND batch.school_id = :schoolId
                """, batchParameters(batch).addValue("now", now));
    }

    private UUID resolveGuardianForCommit(
            StoredBatch batch,
            CommitRow row,
            Map<String, UUID> guardiansByExternalKey,
            Instant now) {
        if ("LINK_EXISTING".equals(row.guardianResolution())) {
            if (row.candidateGuardianId() == null) {
                throw conflict("IMPORT_GUARDIAN_CANDIDATE_MISSING", "A reviewed guardian candidate is no longer available.");
            }
            Long currentMatch = jdbc.queryForObject("""
                    SELECT count(*)
                    FROM guardians guardian
                    WHERE guardian.id = :guardianId
                      AND guardian.organisation_id = :organisationId
                      AND guardian.school_id = :schoolId
                      AND guardian.status = 'ACTIVE'
                      AND (
                        (:email IS NOT NULL AND guardian.email_normalized = :email)
                        OR (:phone IS NOT NULL AND guardian.phone = :phone)
                      )
                    """, batchParameters(batch)
                    .addValue("guardianId", row.candidateGuardianId())
                    .addValue("email", row.guardianEmail())
                    .addValue("phone", row.guardianPhone()), Long.class);
            if (currentMatch == null || currentMatch != 1) {
                throw conflict(
                        "IMPORT_GUARDIAN_CANDIDATE_CHANGED",
                        "The reviewed guardian candidate changed. Create a fresh preview before linking it.");
            }
            if (row.guardianExternalKey() != null) guardiansByExternalKey.put(row.guardianExternalKey(), row.candidateGuardianId());
            return row.candidateGuardianId();
        }
        if (row.guardianExternalKey() != null && guardiansByExternalKey.containsKey(row.guardianExternalKey())) {
            return guardiansByExternalKey.get(row.guardianExternalKey());
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
                """, batchParameters(batch)
                .addValue("guardianId", guardianId)
                .addValue("displayName", row.guardianName())
                .addValue("email", row.guardianEmail())
                .addValue("phone", row.guardianPhone())
                .addValue("now", now));
        if (row.guardianExternalKey() != null) guardiansByExternalKey.put(row.guardianExternalKey(), guardianId);
        return guardianId;
    }

    private static String batchSelect() {
        return """
                SELECT batch.id, batch.school_id, school.display_name AS school_name,
                       batch.campus_id, campus.display_name AS campus_name,
                       batch.academic_session_id, session.name AS session_name,
                       batch.starts_on, batch.source_file_name, batch.file_sha256,
                       batch.status, batch.total_rows, batch.ready_rows, batch.review_rows,
                       batch.invalid_rows, batch.skipped_rows, batch.committed_rows,
                       batch.created_at, batch.committed_at, batch.row_version
                FROM student_import_batches batch
                JOIN schools school ON school.id = batch.school_id AND school.organisation_id = batch.organisation_id
                JOIN campuses campus ON campus.id = batch.campus_id
                    AND campus.organisation_id = batch.organisation_id AND campus.school_id = batch.school_id
                JOIN academic_sessions session ON session.id = batch.academic_session_id
                    AND session.organisation_id = batch.organisation_id AND session.school_id = batch.school_id
                """;
    }

    private static StudentImportBatchSummary mapBatch(ResultSet result, int row) throws SQLException {
        return new StudentImportBatchSummary(
                result.getObject("id", UUID.class),
                result.getObject("school_id", UUID.class),
                result.getString("school_name"),
                result.getObject("campus_id", UUID.class),
                result.getString("campus_name"),
                result.getObject("academic_session_id", UUID.class),
                result.getString("session_name"),
                result.getObject("starts_on", LocalDate.class),
                result.getString("source_file_name"),
                result.getString("file_sha256"),
                result.getString("status"),
                result.getInt("total_rows"),
                result.getInt("ready_rows"),
                result.getInt("review_rows"),
                result.getInt("invalid_rows"),
                result.getInt("skipped_rows"),
                result.getInt("committed_rows"),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("committed_at") == null ? null : result.getTimestamp("committed_at").toInstant(),
                result.getLong("row_version"));
    }

    private static StudentImportRowResponse mapRow(ResultSet result, int row) throws SQLException {
        UUID learnerId = result.getObject("candidate_learner_id", UUID.class);
        UUID guardianId = result.getObject("candidate_guardian_id", UUID.class);
        DuplicateLearnerCandidate learner = learnerId == null ? null : new DuplicateLearnerCandidate(
                learnerId,
                reference("STU", learnerId),
                result.getString("candidate_learner_name"),
                result.getObject("candidate_learner_birth_date", LocalDate.class),
                result.getString("candidate_admission_number"),
                matchesIgnoreCase(result.getString("admission_number"), result.getString("candidate_admission_number"))
                        ? "ADMISSION_NUMBER" : "NAME_AND_BIRTH_DATE");
        DuplicateGuardianCandidate guardian = guardianId == null ? null : new DuplicateGuardianCandidate(
                guardianId,
                result.getString("candidate_guardian_name"),
                maskEmail(result.getString("candidate_guardian_email")),
                maskPhone(result.getString("candidate_guardian_phone")),
                matchesIgnoreCase(result.getString("guardian_email_normalized"), result.getString("candidate_guardian_email"))
                        ? "EMAIL" : "PHONE");
        return new StudentImportRowResponse(
                result.getObject("id", UUID.class),
                result.getInt("source_row_number"),
                result.getString("legal_name"),
                result.getString("preferred_name"),
                result.getObject("birth_date", LocalDate.class),
                result.getString("gender_code"),
                result.getString("gender_description"),
                result.getString("grade_code"),
                result.getString("admission_number"),
                result.getString("guardian_name"),
                result.getString("guardian_external_key"),
                result.getString("guardian_relationship_code"),
                result.getString("guardian_email_normalized"),
                result.getString("guardian_phone"),
                result.getBoolean("primary_contact"),
                result.getBoolean("pickup_authorized"),
                result.getString("status"),
                sqlArray(result.getArray("errors")),
                learner,
                guardian,
                result.getString("learner_resolution"),
                result.getString("guardian_resolution"),
                result.getString("resolution_reason"),
                result.getObject("resolved_guardian_id", UUID.class),
                result.getObject("created_learner_id", UUID.class),
                result.getObject("created_enrolment_id", UUID.class),
                result.getLong("row_version"));
    }

    private static CommitRow mapCommitRow(ResultSet result, int row) throws SQLException {
        return new CommitRow(
                result.getObject("id", UUID.class),
                result.getString("legal_name"),
                result.getString("preferred_name"),
                result.getObject("birth_date", LocalDate.class),
                result.getString("gender_code"),
                result.getString("gender_description"),
                result.getString("grade_code"),
                result.getString("admission_number"),
                result.getString("guardian_name"),
                result.getString("guardian_external_key"),
                result.getString("guardian_relationship_code"),
                result.getString("guardian_email_normalized"),
                result.getString("guardian_phone"),
                result.getBoolean("primary_contact"),
                result.getBoolean("pickup_authorized"),
                result.getObject("candidate_guardian_id", UUID.class),
                result.getString("guardian_resolution"));
    }

    private static StoredBatch mapStoredBatch(ResultSet result, int row) throws SQLException {
        return new StoredBatch(
                result.getObject("id", UUID.class),
                result.getObject("organisation_id", UUID.class),
                result.getObject("school_id", UUID.class),
                result.getObject("campus_id", UUID.class),
                result.getObject("academic_session_id", UUID.class),
                result.getObject("starts_on", LocalDate.class),
                result.getString("status"),
                result.getObject("actor_membership_id", UUID.class),
                result.getLong("row_version"));
    }

    private static MapSqlParameterSource scopeParameters(ImportScope scope) {
        return new MapSqlParameterSource()
                .addValue("organisationId", scope.organisationId())
                .addValue("schoolId", scope.schoolId())
                .addValue("campusId", scope.campusId())
                .addValue("academicSessionId", scope.academicSessionId())
                .addValue("actorMembershipId", scope.actorMembershipId());
    }

    private static MapSqlParameterSource batchParameters(StoredBatch batch) {
        return new MapSqlParameterSource()
                .addValue("batchId", batch.id())
                .addValue("organisationId", batch.organisationId())
                .addValue("schoolId", batch.schoolId())
                .addValue("campusId", batch.campusId())
                .addValue("academicSessionId", batch.academicSessionId())
                .addValue("actorMembershipId", batch.actorMembershipId());
    }

    private String json(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Import validation errors must be JSON serializable", exception);
        }
    }

    private static List<String> sqlArray(Array value) throws SQLException {
        if (value == null) return List.of();
        Object data = value.getArray();
        if (data instanceof String[] strings) return List.of(strings);
        if (data instanceof Object[] objects) {
            List<String> strings = new ArrayList<>(objects.length);
            for (Object object : objects) strings.add(String.valueOf(object));
            return strings;
        }
        return List.of();
    }

    private static boolean matchesLearner(PreparedRow row, LearnerCandidateData candidate) {
        boolean admission = row.admissionNumber != null && candidate.admissionNumber() != null
                && row.admissionNumber.equalsIgnoreCase(candidate.admissionNumber());
        boolean identity = row.legalName != null && row.birthDate != null
                && row.legalName.equalsIgnoreCase(candidate.legalName())
                && row.birthDate.equals(candidate.birthDate());
        return admission || identity;
    }

    private static boolean matchesGuardian(PreparedRow row, GuardianCandidateData candidate) {
        return (row.guardianEmail != null && row.guardianEmail.equals(candidate.email()))
                || (row.guardianPhone != null && row.guardianPhone.equals(candidate.phone()));
    }

    private static <T> Map<String, List<T>> grouped(List<T> values, java.util.function.Function<T, String> key) {
        Map<String, List<T>> result = new LinkedHashMap<>();
        for (T value : values) {
            String resolved = key.apply(value);
            if (resolved != null) result.computeIfAbsent(resolved, ignored -> new ArrayList<>()).add(value);
        }
        return result;
    }

    private static String guardianSignature(PreparedRow row) {
        return String.join("|", value(row.guardianName), value(row.guardianEmail), value(row.guardianPhone));
    }

    private static LocalDate parseDate(String value, String field, List<String> errors) {
        String clean = clean(value);
        if (clean == null) {
            errors.add(field + " is required");
            return null;
        }
        try {
            return LocalDate.parse(clean);
        } catch (DateTimeParseException exception) {
            errors.add(field + " must use YYYY-MM-DD");
            return null;
        }
    }

    private static void require(String value, String message, List<String> errors) {
        if (value == null) errors.add(message);
    }

    private static String safeFileName(String value) {
        String clean = clean(value);
        if (clean == null) return "student-import.csv";
        clean = clean.replace('\\', '/');
        int separator = clean.lastIndexOf('/');
        String name = separator >= 0 ? clean.substring(separator + 1) : clean;
        return name.isBlank() ? "student-import.csv" : name.substring(0, Math.min(255, name.length()));
    }

    private static String maskEmail(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        return email.substring(0, 1) + "***" + email.substring(at);
    }

    private static String maskPhone(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("\\D", "");
        return digits.length() <= 4 ? "****" : "******" + digits.substring(digits.length() - 4);
    }

    private static boolean matchesIgnoreCase(String first, String second) {
        return first != null && second != null && first.equalsIgnoreCase(second);
    }

    private static String reference(String prefix, UUID id) {
        return prefix + "-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) return null;
        return value.replaceAll("[\\p{Cntrl}]", " ").strip();
    }

    private static String lowerClean(String value) {
        String result = clean(value);
        return result == null ? null : result.toLowerCase(Locale.ROOT);
    }

    private static String upperClean(String value) {
        String result = clean(value);
        return result == null ? null : result.toUpperCase(Locale.ROOT);
    }

    private static UUID requireOrganisation(AuthenticatedUser user) {
        if (user.activeOrganisationId() == null) {
            throw conflict("ORGANISATION_SELECTION_REQUIRED", "Select a school organisation before importing students.");
        }
        return user.activeOrganisationId();
    }

    private static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private static ApiException stale(String message) {
        return conflict("STALE_VERSION", message);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "IMPORT_NOT_FOUND", "The import batch was not found.");
    }

    private static final class PreparedRow {
        private final int sourceRowNumber;
        private final List<String> errors = new ArrayList<>();
        private String legalName;
        private String preferredName;
        private LocalDate birthDate;
        private String genderCode;
        private String genderDescription;
        private String gradeCode;
        private String admissionNumber;
        private String guardianName;
        private String guardianExternalKey;
        private String guardianRelationshipCode;
        private String guardianEmail;
        private String guardianPhone;
        private boolean primaryContact;
        private boolean pickupAuthorized;
        private DuplicateLearnerCandidate learnerCandidate;
        private DuplicateGuardianCandidate guardianCandidate;
        private String learnerResolution;
        private String guardianResolution;
        private String status;

        private PreparedRow(int sourceRowNumber) {
            this.sourceRowNumber = sourceRowNumber;
        }
    }

    private record ImportScope(
            UUID organisationId,
            UUID schoolId,
            UUID campusId,
            UUID academicSessionId,
            LocalDate sessionStartsOn,
            LocalDate sessionEndsOn,
            UUID actorMembershipId) {
    }

    private record StoredBatch(
            UUID id,
            UUID organisationId,
            UUID schoolId,
            UUID campusId,
            UUID academicSessionId,
            LocalDate startsOn,
            String status,
            UUID actorMembershipId,
            long version) {
    }

    private record StoredRow(
            UUID id,
            String status,
            boolean hasErrors,
            UUID learnerCandidateId,
            UUID guardianCandidateId,
            boolean exactAdmissionConflict,
            long version) {
    }

    private record LearnerCandidateData(
            UUID id,
            String legalName,
            LocalDate birthDate,
            String admissionNumber,
            String status) {
    }

    private record GuardianCandidateData(
            UUID id,
            String displayName,
            String email,
            String phone,
            String status) {
    }

    private record CommitRow(
            UUID id,
            String legalName,
            String preferredName,
            LocalDate birthDate,
            String genderCode,
            String genderDescription,
            String gradeCode,
            String admissionNumber,
            String guardianName,
            String guardianExternalKey,
            String guardianRelationshipCode,
            String guardianEmail,
            String guardianPhone,
            boolean primaryContact,
            boolean pickupAuthorized,
            UUID candidateGuardianId,
            String guardianResolution) {
    }
}
