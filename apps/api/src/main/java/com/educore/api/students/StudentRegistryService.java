package com.educore.api.students;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
public class StudentRegistryService {

    private static final String STUDENT_READ = "student.read";
    private static final String STUDENT_MANAGE = "student.manage";
    private static final String GUARDIAN_READ = "guardian.read";
    private static final String GUARDIAN_MANAGE = "guardian.manage";
    private static final Set<String> PLACEMENT_STATUSES = Set.of("PENDING", "PLACED");
    private static final Set<String> GENDER_CODES =
            Set.of("FEMALE", "MALE", "NON_BINARY", "SELF_DESCRIBED", "NOT_STATED");

    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final Clock clock;

    public StudentRegistryService(
            NamedParameterJdbcTemplate jdbc,
            SecurityAuditService audit,
            Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public StudentPageResponse list(
            AuthenticatedUser user,
            String query,
            UUID academicSessionId,
            String gradeCode,
            String placementStatus,
            int page,
            int size) {
        return list(user, query, academicSessionId, gradeCode, placementStatus, page, size, null);
    }

    @Transactional(readOnly = true)
    public StudentPageResponse list(AuthenticatedUser user, String query, UUID academicSessionId,
            String gradeCode, String placementStatus, int page, int size,
            com.educore.api.admissions.AdmissionType admissionType) {
        UUID organisationId = requireOrganisation(user);
        String cleanQuery = clean(query);
        String cleanGrade = upperClean(gradeCode);
        String cleanPlacement = upperClean(placementStatus);
        if (cleanPlacement != null && !PLACEMENT_STATUSES.contains(cleanPlacement)) {
            throw badRequest("INVALID_PLACEMENT_STATUS", "Placement status must be PENDING or PLACED.");
        }

        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("actorUserId", user.userId())
                .addValue("organisationId", organisationId)
                .addValue("requiredCapability", STUDENT_READ)
                .addValue("now", Instant.now(clock))
                .addValue("academicSessionId", academicSessionId)
                .addValue("gradeCode", cleanGrade)
                .addValue("placementStatus", cleanPlacement)
                .addValue("admissionType", admissionType == null ? null : admissionType.name())
                .addValue("query", cleanQuery == null ? null : "%" + escapeLike(cleanQuery.toLowerCase(Locale.ROOT)) + "%")
                .addValue("limit", size)
                .addValue("offset", Math.multiplyExact(page, size));

        String fromAndWhere = """
                FROM learners learner
                JOIN LATERAL (
                    SELECT candidate.*
                    FROM student_enrolments candidate
                    WHERE candidate.learner_id = learner.id
                      AND candidate.organisation_id = learner.organisation_id
                      AND candidate.school_id = learner.school_id
                    ORDER BY (candidate.status = 'ACTIVE') DESC, candidate.starts_on DESC, candidate.created_at DESC
                    LIMIT 1
                ) enrolment ON true
                JOIN campuses campus ON campus.id = enrolment.campus_id
                    AND campus.organisation_id = enrolment.organisation_id
                    AND campus.school_id = enrolment.school_id
                JOIN academic_sessions session ON session.id = enrolment.academic_session_id
                    AND session.organisation_id = enrolment.organisation_id
                    AND session.school_id = enrolment.school_id
                LEFT JOIN LATERAL (
                    SELECT placement.id, placement.class_section_id, placement.roll_number
                    FROM student_section_placements placement
                    WHERE placement.enrolment_id = enrolment.id
                      AND placement.organisation_id = enrolment.organisation_id
                      AND placement.school_id = enrolment.school_id
                      AND placement.status = 'ACTIVE'
                    LIMIT 1
                ) placement ON true
                LEFT JOIN class_sections section ON section.id = placement.class_section_id
                    AND section.organisation_id = learner.organisation_id
                    AND section.school_id = learner.school_id
                LEFT JOIN LATERAL (
                    SELECT count(*) AS guardian_count,
                           count(*) FILTER (
                               WHERE relationship.portal_access_status NOT IN ('DENIED', 'SUSPENDED')
                                 AND (guardian.email_normalized IS NOT NULL OR guardian.phone IS NOT NULL)
                           ) AS portal_ready_count
                    FROM learner_guardian_relationships relationship
                    JOIN guardians guardian ON guardian.id = relationship.guardian_id
                        AND guardian.organisation_id = relationship.organisation_id
                        AND guardian.school_id = relationship.school_id
                    WHERE relationship.learner_id = learner.id
                      AND relationship.organisation_id = learner.organisation_id
                      AND relationship.school_id = learner.school_id
                ) guardian_counts ON true
                WHERE learner.organisation_id = :organisationId
                  AND EXISTS (
                      SELECT 1
                      FROM memberships actor_membership
                      JOIN membership_roles actor_role ON actor_role.membership_id = actor_membership.id
                      JOIN role_capabilities actor_capability ON actor_capability.role_id = actor_role.role_id
                      WHERE actor_membership.user_id = :actorUserId
                        AND actor_membership.organisation_id = learner.organisation_id
                        AND actor_membership.school_id = learner.school_id
                        AND actor_membership.status = 'ACTIVE'
                        AND actor_membership.valid_from <= :now
                        AND (actor_membership.valid_until IS NULL OR actor_membership.valid_until > :now)
                        AND actor_capability.capability_code = :requiredCapability
                  )
                  AND (CAST(:academicSessionId AS uuid) IS NULL OR enrolment.academic_session_id = :academicSessionId)
                  AND (CAST(:gradeCode AS varchar) IS NULL OR enrolment.grade_code = :gradeCode)
                  AND (CAST(:placementStatus AS varchar) IS NULL OR enrolment.placement_status = :placementStatus)
                  AND (CAST(:admissionType AS varchar) IS NULL OR learner.admission_type = :admissionType)
                  AND (
                      CAST(:query AS text) IS NULL
                      OR lower(learner.legal_name) LIKE :query ESCAPE '\\'
                      OR lower(coalesce(learner.preferred_name, '')) LIKE :query ESCAPE '\\'
                      OR lower(coalesce(enrolment.admission_number, '')) LIKE :query ESCAPE '\\'
                      OR lower(enrolment.grade_code) LIKE :query ESCAPE '\\'
                      OR lower(coalesce(section.display_name, '')) LIKE :query ESCAPE '\\'
                      OR lower(learner.id::text) LIKE replace(:query, 'stu-', '') ESCAPE '\\'
                  )
                """;

        List<StudentListItem> items = jdbc.query("""
                SELECT learner.id AS learner_id, learner.legal_name, learner.preferred_name,
                       learner.birth_date, learner.status AS learner_status, learner.row_version AS learner_version,
                       learner.admission_type, learner.rte_admission_session_id, learner.rte_reference,
                       (SELECT name FROM academic_sessions WHERE id=learner.rte_admission_session_id
                            AND organisation_id=learner.organisation_id AND school_id=learner.school_id) rte_session_name,
                       enrolment.id AS enrolment_id, enrolment.admission_number, enrolment.campus_id,
                       campus.display_name AS campus_name, enrolment.academic_session_id,
                       session.name AS academic_session_name, enrolment.grade_code,
                       enrolment.placement_status, enrolment.row_version AS enrolment_version,
                       section.id AS section_id, section.code AS section_code,
                       section.display_name AS section_name, placement.roll_number,
                       coalesce(guardian_counts.guardian_count, 0) AS guardian_count,
                       coalesce(guardian_counts.portal_ready_count, 0) AS portal_ready_count
                """ + fromAndWhere + " ORDER BY learner.legal_name, learner.id LIMIT :limit OFFSET :offset",
                parameters,
                StudentRegistryService::mapStudent);

        Long total = jdbc.queryForObject("SELECT count(*) " + fromAndWhere, parameters, Long.class);
        StudentMetrics metrics = jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE learner_status = 'ACTIVE') AS active_count,
                       count(*) FILTER (WHERE placement_status = 'PENDING') AS pending_count,
                       count(*) FILTER (WHERE placement_status = 'PLACED') AS placed_count,
                       count(*) FILTER (WHERE portal_ready_count > 0) AS portal_ready_count
                FROM (
                    SELECT learner.status AS learner_status, enrolment.placement_status,
                           coalesce(guardian_counts.portal_ready_count, 0) AS portal_ready_count
                """ + fromAndWhere + ") visible_students", parameters, (result, row) -> new StudentMetrics(
                result.getLong("active_count"),
                result.getLong("pending_count"),
                result.getLong("placed_count"),
                result.getLong("portal_ready_count")));

        return new StudentPageResponse(items, page, size, total == null ? 0 : total, metrics);
    }

    @Transactional(readOnly = true)
    public StudentDetailResponse get(AuthenticatedUser user, UUID learnerId) {
        StoredStudent student = findAccessible(user, learnerId, List.of(STUDENT_READ, GUARDIAN_READ));
        return detail(student);
    }

    @Transactional
    public StudentDetailResponse updateProfile(
            AuthenticatedUser user,
            UUID learnerId,
            UpdateStudentProfileRequest request) {
        StoredStudent student = findAccessible(user, learnerId, List.of(STUDENT_MANAGE, GUARDIAN_READ));
        String legalName = requiredClean(request.legalName(), "legal name");
        String preferredName = clean(request.preferredName());
        String genderCode = upperClean(request.genderCode());
        String genderDescription = clean(request.genderDescription());
        validateGender(genderCode, genderDescription);

        int updated = jdbc.update("""
                UPDATE learners
                SET legal_name = :legalName,
                    preferred_name = :preferredName,
                    birth_date = :birthDate,
                    gender_code = :genderCode,
                    gender_description = :genderDescription,
                    updated_at = :now,
                    row_version = row_version + 1
                WHERE id = :learnerId
                  AND organisation_id = :organisationId
                  AND school_id = :schoolId
                  AND row_version = :expectedVersion
                """, scope(student)
                .addValue("legalName", legalName)
                .addValue("preferredName", preferredName)
                .addValue("birthDate", request.birthDate())
                .addValue("genderCode", genderCode)
                .addValue("genderDescription", genderDescription)
                .addValue("now", Instant.now(clock))
                .addValue("expectedVersion", request.expectedVersion()));
        if (updated != 1) throw stale("The learner profile changed before this correction was saved.");

        auditMutation(user, student, "student.profile.corrected", "LEARNER", learnerId,
                request.correctionReason(), Map.of("changed", "identity"));
        return detail(findAccessible(user, learnerId, List.of(STUDENT_MANAGE, GUARDIAN_READ)));
    }

    @Transactional
    public StudentDetailResponse updateGuardian(
            AuthenticatedUser user,
            UUID learnerId,
            UUID guardianId,
            UpdateGuardianRequest request) {
        StoredStudent student = findAccessible(user, learnerId, List.of(STUDENT_READ, GUARDIAN_MANAGE));
        String displayName = requiredClean(request.displayName(), "guardian name");
        String email = lowerClean(request.email());
        String phone = clean(request.phone());
        if (email == null && phone == null) {
            throw badRequest("GUARDIAN_CONTACT_REQUIRED", "Keep at least one guardian email address or phone number.");
        }

        List<StoredGuardian> matches = jdbc.query("""
                SELECT guardian.id, guardian.row_version AS guardian_version,
                       relationship.row_version AS relationship_version
                FROM learner_guardian_relationships relationship
                JOIN guardians guardian ON guardian.id = relationship.guardian_id
                    AND guardian.organisation_id = relationship.organisation_id
                    AND guardian.school_id = relationship.school_id
                WHERE relationship.learner_id = :learnerId
                  AND relationship.guardian_id = :guardianId
                  AND relationship.organisation_id = :organisationId
                  AND relationship.school_id = :schoolId
                FOR UPDATE OF relationship, guardian
                """, scope(student).addValue("guardianId", guardianId), (result, row) -> new StoredGuardian(
                result.getObject("id", UUID.class),
                result.getLong("guardian_version"),
                result.getLong("relationship_version")));
        if (matches.isEmpty()) throw notFound();
        StoredGuardian guardian = matches.getFirst();
        if (guardian.guardianVersion() != request.expectedGuardianVersion()
                || guardian.relationshipVersion() != request.expectedRelationshipVersion()) {
            throw stale("The guardian contact changed before this correction was saved.");
        }

        if (request.primaryContact()) {
            jdbc.update("""
                    UPDATE learner_guardian_relationships
                    SET primary_contact = false, updated_at = :now, row_version = row_version + 1
                    WHERE learner_id = :learnerId
                      AND organisation_id = :organisationId
                      AND school_id = :schoolId
                      AND guardian_id <> :guardianId
                      AND primary_contact
                    """, scope(student).addValue("guardianId", guardianId).addValue("now", Instant.now(clock)));
        }

        int guardianUpdated = jdbc.update("""
                UPDATE guardians
                SET display_name = :displayName, email_normalized = :email, phone = :phone,
                    updated_at = :now, row_version = row_version + 1
                WHERE id = :guardianId
                  AND organisation_id = :organisationId
                  AND school_id = :schoolId
                  AND row_version = :expectedGuardianVersion
                """, scope(student)
                .addValue("guardianId", guardianId)
                .addValue("displayName", displayName)
                .addValue("email", email)
                .addValue("phone", phone)
                .addValue("now", Instant.now(clock))
                .addValue("expectedGuardianVersion", request.expectedGuardianVersion()));
        int relationshipUpdated = jdbc.update("""
                UPDATE learner_guardian_relationships
                SET relationship_code = :relationshipCode,
                    primary_contact = :primaryContact,
                    pickup_authorized = :pickupAuthorized,
                    updated_at = :now,
                    row_version = row_version + 1
                WHERE learner_id = :learnerId
                  AND guardian_id = :guardianId
                  AND organisation_id = :organisationId
                  AND school_id = :schoolId
                  AND row_version = :expectedRelationshipVersion
                """, scope(student)
                .addValue("guardianId", guardianId)
                .addValue("relationshipCode", upperClean(request.relationshipCode()))
                .addValue("primaryContact", request.primaryContact())
                .addValue("pickupAuthorized", request.pickupAuthorized())
                .addValue("now", Instant.now(clock))
                .addValue("expectedRelationshipVersion", request.expectedRelationshipVersion()));
        if (guardianUpdated != 1 || relationshipUpdated != 1) {
            throw stale("The guardian contact changed before this correction was saved.");
        }

        auditMutation(user, student, "guardian.contact.corrected", "GUARDIAN", guardianId,
                request.correctionReason(), Map.of(
                        "primaryContact", request.primaryContact(),
                        "pickupAuthorized", request.pickupAuthorized()));
        return detail(findAccessible(user, learnerId, List.of(STUDENT_READ, GUARDIAN_MANAGE)));
    }

    @Transactional(readOnly = true)
    public StudentOptionsResponse options(AuthenticatedUser user, UUID learnerId) {
        StoredStudent student = findAccessible(user, learnerId, List.of(STUDENT_MANAGE));
        return new StudentOptionsResponse(sectionOptions(student));
    }

    @Transactional
    public StudentDetailResponse place(
            AuthenticatedUser user,
            UUID learnerId,
            PlaceStudentRequest request) {
        StoredStudent student = findAccessible(user, learnerId, List.of(STUDENT_MANAGE, GUARDIAN_READ));
        Long lockedVersion = jdbc.queryForObject("""
                SELECT row_version
                FROM student_enrolments
                WHERE id = :enrolmentId AND organisation_id = :organisationId AND school_id = :schoolId
                FOR UPDATE
                """, scope(student), Long.class);
        if (lockedVersion == null || lockedVersion.longValue() != request.expectedEnrolmentVersion()) {
            throw stale("The enrolment changed before this placement was saved.");
        }
        if (request.startsOn().isBefore(student.enrolmentStartsOn())
                || (student.enrolmentEndsOn() != null && request.startsOn().isAfter(student.enrolmentEndsOn()))) {
            throw badRequest("PLACEMENT_DATE_OUTSIDE_ENROLMENT", "Placement date must fall within the enrolment dates.");
        }

        List<StoredSection> sections = jdbc.query("""
                SELECT section.id, section.capacity
                FROM class_sections section
                WHERE section.id = :sectionId
                  AND section.organisation_id = :organisationId
                  AND section.school_id = :schoolId
                  AND section.campus_id = :campusId
                  AND section.academic_session_id = :academicSessionId
                  AND section.grade_code = :gradeCode
                  AND section.status = 'ACTIVE'
                FOR UPDATE
                """, scope(student).addValue("sectionId", request.sectionId()), (result, row) -> new StoredSection(
                result.getObject("id", UUID.class),
                (Integer) result.getObject("capacity"),
                0));
        if (sections.isEmpty()) {
            throw badRequest("SECTION_NOT_ELIGIBLE", "Choose an active section from this learner's campus, session, and grade.");
        }

        List<StoredPlacement> active = jdbc.query("""
                SELECT id, class_section_id, roll_number, starts_on
                FROM student_section_placements
                WHERE enrolment_id = :enrolmentId
                  AND organisation_id = :organisationId
                  AND school_id = :schoolId
                  AND status = 'ACTIVE'
                FOR UPDATE
                """, scope(student), (result, row) -> new StoredPlacement(
                result.getObject("id", UUID.class),
                result.getObject("class_section_id", UUID.class),
                result.getString("roll_number"),
                result.getObject("starts_on", LocalDate.class)));
        StoredPlacement current = active.isEmpty() ? null : active.getFirst();
        String rollNumber = clean(request.rollNumber());
        if (current != null && current.sectionId().equals(request.sectionId())
                && equalNullable(current.rollNumber(), rollNumber)) {
            throw conflict("PLACEMENT_UNCHANGED", "This learner is already in the selected section with that roll number.");
        }
        if (current != null && !request.startsOn().isAfter(current.startsOn())) {
            throw badRequest("PLACEMENT_DATE_NOT_AFTER_CURRENT", "A reassignment must start after the current placement began.");
        }

        StoredSection selectedSection = sections.getFirst();
        Long activeCount = jdbc.queryForObject("""
                SELECT count(*)
                FROM student_section_placements
                WHERE class_section_id = :sectionId
                  AND organisation_id = :organisationId
                  AND school_id = :schoolId
                  AND status = 'ACTIVE'
                """, scope(student).addValue("sectionId", request.sectionId()), Long.class);
        StoredSection section = new StoredSection(
                selectedSection.id(), selectedSection.capacity(), activeCount == null ? 0 : activeCount);
        long effectiveCount = section.activeCount()
                - (current != null && current.sectionId().equals(section.id()) ? 1 : 0);
        if (section.capacity() != null && effectiveCount >= section.capacity()) {
            throw conflict("SECTION_CAPACITY_REACHED", "The selected section has reached its configured capacity.");
        }

        Instant now = Instant.now(clock);
        if (current != null) {
            jdbc.update("""
                    UPDATE student_section_placements
                    SET status = 'ENDED', ends_on = :endsOn, ended_by_membership_id = :actorMembershipId,
                        end_reason = :changeReason, updated_at = :now, row_version = row_version + 1
                    WHERE id = :placementId AND organisation_id = :organisationId AND school_id = :schoolId
                    """, scope(student)
                    .addValue("placementId", current.id())
                    .addValue("endsOn", request.startsOn().minusDays(1))
                    .addValue("changeReason", clean(request.changeReason()))
                    .addValue("now", now));
        }

        try {
            jdbc.update("""
                    INSERT INTO student_section_placements (
                        id, organisation_id, school_id, enrolment_id, learner_id, class_section_id,
                        roll_number, starts_on, status, placed_by_membership_id, placement_reason,
                        created_at, updated_at, row_version
                    ) VALUES (
                        :placementId, :organisationId, :schoolId, :enrolmentId, :learnerId, :sectionId,
                        :rollNumber, :startsOn, 'ACTIVE', :actorMembershipId, :changeReason,
                        :now, :now, 0
                    )
                    """, scope(student)
                    .addValue("placementId", UUID.randomUUID())
                    .addValue("sectionId", request.sectionId())
                    .addValue("rollNumber", rollNumber)
                    .addValue("startsOn", request.startsOn())
                    .addValue("changeReason", clean(request.changeReason()))
                    .addValue("now", now));
        } catch (DataIntegrityViolationException exception) {
            throw conflict("PLACEMENT_CONFLICT", "That active section or roll-number placement already exists.");
        }

        int enrolmentUpdated = jdbc.update("""
                UPDATE student_enrolments
                SET placement_status = 'PLACED', updated_at = :now, row_version = row_version + 1
                WHERE id = :enrolmentId
                  AND organisation_id = :organisationId
                  AND school_id = :schoolId
                  AND row_version = :expectedVersion
                """, scope(student).addValue("now", now).addValue("expectedVersion", request.expectedEnrolmentVersion()));
        if (enrolmentUpdated != 1) throw stale("The enrolment changed before this placement was saved.");

        auditMutation(user, student, current == null ? "student.placement.created" : "student.placement.changed",
                "LEARNER", learnerId, request.changeReason(), Map.of("sectionId", request.sectionId().toString()));
        return detail(findAccessible(user, learnerId, List.of(STUDENT_MANAGE, GUARDIAN_READ)));
    }

    @Transactional
    public SectionOption createSection(AuthenticatedUser user, CreateSectionRequest request) {
        UUID organisationId = requireOrganisation(user);
        Instant now = Instant.now(clock);
        List<SectionCreationScope> scopes = jdbc.query("""
                SELECT campus.school_id, actor_membership.id AS actor_membership_id
                FROM campuses campus
                JOIN academic_sessions session ON session.id = :academicSessionId
                    AND session.organisation_id = campus.organisation_id
                    AND session.school_id = campus.school_id
                JOIN memberships actor_membership ON actor_membership.user_id = :actorUserId
                    AND actor_membership.organisation_id = campus.organisation_id
                    AND actor_membership.school_id = campus.school_id
                    AND actor_membership.status = 'ACTIVE'
                    AND actor_membership.valid_from <= :now
                    AND (actor_membership.valid_until IS NULL OR actor_membership.valid_until > :now)
                JOIN membership_roles actor_role ON actor_role.membership_id = actor_membership.id
                JOIN role_capabilities actor_capability ON actor_capability.role_id = actor_role.role_id
                WHERE campus.id = :campusId
                  AND campus.organisation_id = :organisationId
                  AND campus.status = 'ACTIVE'
                  AND session.status IN ('PLANNED', 'ACTIVE')
                  AND actor_capability.capability_code IN ('student.manage', 'academics.configure')
                GROUP BY campus.school_id, actor_membership.id
                HAVING count(DISTINCT actor_capability.capability_code) = 2
                ORDER BY actor_membership.created_at, actor_membership.id
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("academicSessionId", request.academicSessionId())
                .addValue("actorUserId", user.userId())
                .addValue("now", now)
                .addValue("campusId", request.campusId())
                .addValue("organisationId", organisationId), (result, row) -> new SectionCreationScope(
                result.getObject("school_id", UUID.class),
                result.getObject("actor_membership_id", UUID.class)));
        if (scopes.isEmpty()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "SECTION_SCOPE_NOT_FOUND",
                    "The campus and academic session were not found in an authorized school.");
        }
        SectionCreationScope scope = scopes.getFirst();
        UUID sectionId = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO class_sections (
                        id, organisation_id, school_id, campus_id, academic_session_id,
                        grade_code, code, display_name, capacity, status,
                        created_at, updated_at, row_version
                    ) VALUES (
                        :id, :organisationId, :schoolId, :campusId, :academicSessionId,
                        :gradeCode, :code, :displayName, :capacity, 'ACTIVE',
                        :now, :now, 0
                    )
                    """, new MapSqlParameterSource()
                    .addValue("id", sectionId)
                    .addValue("organisationId", organisationId)
                    .addValue("schoolId", scope.schoolId())
                    .addValue("campusId", request.campusId())
                    .addValue("academicSessionId", request.academicSessionId())
                    .addValue("gradeCode", upperClean(request.gradeCode()))
                    .addValue("code", upperClean(request.code()))
                    .addValue("displayName", requiredClean(request.displayName(), "section name"))
                    .addValue("capacity", request.capacity())
                    .addValue("now", now));
        } catch (DataIntegrityViolationException exception) {
            throw conflict("SECTION_ALREADY_EXISTS", "A section with this code already exists for the grade and session.");
        }
        audit.recordScoped(user, scope.actorMembershipId(), scope.schoolId(), "academic.section.created",
                "CLASS_SECTION", sectionId.toString(), AuditOutcome.SUCCEEDED, null,
                Map.of("gradeCode", upperClean(request.gradeCode()), "code", upperClean(request.code())));
        return new SectionOption(sectionId, upperClean(request.code()), requiredClean(request.displayName(), "section name"),
                request.capacity(), 0, 0);
    }

    private StudentDetailResponse detail(StoredStudent stored) {
        StudentListItem student = student(stored);
        List<GuardianSummary> guardians = jdbc.query("""
                SELECT guardian.id, guardian.display_name, guardian.email_normalized, guardian.phone,
                       guardian.row_version AS guardian_version, relationship.relationship_code,
                       relationship.primary_contact, relationship.pickup_authorized,
                       relationship.portal_access_status, relationship.row_version AS relationship_version,
                       (SELECT count(*) FROM learner_guardian_relationships linked
                        WHERE linked.guardian_id = guardian.id
                          AND linked.organisation_id = guardian.organisation_id
                          AND linked.school_id = guardian.school_id) AS linked_learner_count
                FROM learner_guardian_relationships relationship
                JOIN guardians guardian ON guardian.id = relationship.guardian_id
                    AND guardian.organisation_id = relationship.organisation_id
                    AND guardian.school_id = relationship.school_id
                WHERE relationship.learner_id = :learnerId
                  AND relationship.organisation_id = :organisationId
                  AND relationship.school_id = :schoolId
                ORDER BY relationship.primary_contact DESC, guardian.display_name, guardian.id
                """, scope(stored), (result, row) -> {
            String email = result.getString("email_normalized");
            String phone = result.getString("phone");
            String access = result.getString("portal_access_status");
            return new GuardianSummary(
                    result.getObject("id", UUID.class),
                    result.getString("display_name"),
                    result.getString("relationship_code"),
                    email,
                    phone,
                    result.getBoolean("primary_contact"),
                    result.getBoolean("pickup_authorized"),
                    access,
                    portalReadiness(access, email, phone),
                    result.getLong("linked_learner_count"),
                    result.getLong("guardian_version"),
                    result.getLong("relationship_version"));
        });
        List<PlacementSummary> history = jdbc.query("""
                SELECT placement.id, section.id AS section_id, section.code, section.display_name,
                       placement.roll_number, placement.starts_on, placement.ends_on,
                       placement.status, placement.placement_reason, placement.end_reason, placement.row_version
                FROM student_section_placements placement
                JOIN class_sections section ON section.id = placement.class_section_id
                    AND section.organisation_id = placement.organisation_id
                    AND section.school_id = placement.school_id
                WHERE placement.learner_id = :learnerId
                  AND placement.organisation_id = :organisationId
                  AND placement.school_id = :schoolId
                ORDER BY placement.starts_on DESC, placement.created_at DESC
                """, scope(stored), (result, row) -> new PlacementSummary(
                result.getObject("id", UUID.class),
                result.getObject("section_id", UUID.class),
                result.getString("code"),
                result.getString("display_name"),
                result.getString("roll_number"),
                result.getObject("starts_on", LocalDate.class),
                result.getObject("ends_on", LocalDate.class),
                result.getString("status"),
                result.getString("placement_reason"),
                result.getString("end_reason"),
                result.getLong("row_version")));
        return new StudentDetailResponse(student, stored.genderCode(), stored.genderDescription(), guardians, history);
    }

    private List<SectionOption> sectionOptions(StoredStudent student) {
        return jdbc.query("""
                SELECT section.id, section.code, section.display_name, section.capacity, section.row_version,
                       count(placement.id) FILTER (WHERE placement.status = 'ACTIVE') AS active_count
                FROM class_sections section
                LEFT JOIN student_section_placements placement ON placement.class_section_id = section.id
                    AND placement.organisation_id = section.organisation_id
                    AND placement.school_id = section.school_id
                WHERE section.organisation_id = :organisationId
                  AND section.school_id = :schoolId
                  AND section.campus_id = :campusId
                  AND section.academic_session_id = :academicSessionId
                  AND section.grade_code = :gradeCode
                  AND section.status = 'ACTIVE'
                GROUP BY section.id
                ORDER BY section.display_name, section.code
                """, scope(student), (result, row) -> new SectionOption(
                result.getObject("id", UUID.class),
                result.getString("code"),
                result.getString("display_name"),
                (Integer) result.getObject("capacity"),
                result.getLong("active_count"),
                result.getLong("row_version")));
    }

    private StoredStudent findAccessible(
            AuthenticatedUser user,
            UUID learnerId,
            List<String> requiredCapabilities) {
        List<StoredStudent> matches = jdbc.query("""
                SELECT learner.id AS learner_id, learner.organisation_id, learner.school_id,
                       learner.legal_name, learner.preferred_name, learner.birth_date,
                       learner.gender_code, learner.gender_description, learner.status AS learner_status,
                       learner.admission_type, learner.rte_admission_session_id, learner.rte_reference,
                       (SELECT name FROM academic_sessions WHERE id=learner.rte_admission_session_id
                            AND organisation_id=learner.organisation_id AND school_id=learner.school_id) rte_session_name,
                       learner.row_version AS learner_version, enrolment.id AS enrolment_id,
                       enrolment.admission_number, enrolment.campus_id, campus.display_name AS campus_name,
                       enrolment.academic_session_id, session.name AS academic_session_name,
                       enrolment.grade_code, enrolment.placement_status,
                       enrolment.starts_on AS enrolment_starts_on, enrolment.ends_on AS enrolment_ends_on,
                       enrolment.row_version AS enrolment_version,
                       placement.class_section_id AS section_id, section.code AS section_code,
                       section.display_name AS section_name, placement.roll_number,
                       guardian_counts.guardian_count, guardian_counts.portal_ready_count,
                       actor_membership.id AS actor_membership_id
                FROM learners learner
                JOIN LATERAL (
                    SELECT candidate.* FROM student_enrolments candidate
                    WHERE candidate.learner_id = learner.id
                      AND candidate.organisation_id = learner.organisation_id
                      AND candidate.school_id = learner.school_id
                    ORDER BY (candidate.status = 'ACTIVE') DESC, candidate.starts_on DESC, candidate.created_at DESC
                    LIMIT 1
                ) enrolment ON true
                JOIN campuses campus ON campus.id = enrolment.campus_id
                    AND campus.organisation_id = enrolment.organisation_id
                    AND campus.school_id = enrolment.school_id
                JOIN academic_sessions session ON session.id = enrolment.academic_session_id
                    AND session.organisation_id = enrolment.organisation_id
                    AND session.school_id = enrolment.school_id
                LEFT JOIN LATERAL (
                    SELECT current.class_section_id, current.roll_number
                    FROM student_section_placements current
                    WHERE current.enrolment_id = enrolment.id
                      AND current.organisation_id = enrolment.organisation_id
                      AND current.school_id = enrolment.school_id
                      AND current.status = 'ACTIVE'
                    LIMIT 1
                ) placement ON true
                LEFT JOIN class_sections section ON section.id = placement.class_section_id
                    AND section.organisation_id = learner.organisation_id
                    AND section.school_id = learner.school_id
                LEFT JOIN LATERAL (
                    SELECT count(*) AS guardian_count,
                           count(*) FILTER (
                               WHERE relationship.portal_access_status NOT IN ('DENIED', 'SUSPENDED')
                                 AND (guardian.email_normalized IS NOT NULL OR guardian.phone IS NOT NULL)
                           ) AS portal_ready_count
                    FROM learner_guardian_relationships relationship
                    JOIN guardians guardian ON guardian.id = relationship.guardian_id
                        AND guardian.organisation_id = relationship.organisation_id
                        AND guardian.school_id = relationship.school_id
                    WHERE relationship.learner_id = learner.id
                      AND relationship.organisation_id = learner.organisation_id
                      AND relationship.school_id = learner.school_id
                ) guardian_counts ON true
                JOIN memberships actor_membership ON actor_membership.user_id = :actorUserId
                    AND actor_membership.organisation_id = learner.organisation_id
                    AND actor_membership.school_id = learner.school_id
                    AND actor_membership.status = 'ACTIVE'
                    AND actor_membership.valid_from <= :now
                    AND (actor_membership.valid_until IS NULL OR actor_membership.valid_until > :now)
                JOIN membership_roles actor_role ON actor_role.membership_id = actor_membership.id
                JOIN role_capabilities actor_capability ON actor_capability.role_id = actor_role.role_id
                WHERE learner.id = :learnerId
                  AND learner.organisation_id = :organisationId
                  AND actor_capability.capability_code IN (:requiredCapabilities)
                GROUP BY learner.id, enrolment.id, enrolment.admission_number,
                         enrolment.campus_id, enrolment.academic_session_id, enrolment.grade_code,
                         enrolment.placement_status, enrolment.starts_on, enrolment.ends_on,
                         enrolment.row_version, campus.display_name, session.name,
                         placement.class_section_id, placement.roll_number,
                         section.code, section.display_name,
                         guardian_counts.guardian_count, guardian_counts.portal_ready_count,
                         actor_membership.id
                HAVING count(DISTINCT actor_capability.capability_code) = :capabilityCount
                ORDER BY actor_membership.created_at, actor_membership.id
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("actorUserId", user.userId())
                .addValue("organisationId", requireOrganisation(user))
                .addValue("learnerId", learnerId)
                .addValue("requiredCapabilities", requiredCapabilities)
                .addValue("capabilityCount", requiredCapabilities.size())
                .addValue("now", Instant.now(clock)), StudentRegistryService::mapStoredStudent);
        if (matches.isEmpty()) throw notFound();
        return matches.getFirst();
    }

    private static StoredStudent mapStoredStudent(ResultSet result, int row) throws SQLException {
        return new StoredStudent(
                result.getObject("learner_id", UUID.class),
                result.getObject("organisation_id", UUID.class),
                result.getObject("school_id", UUID.class),
                result.getObject("actor_membership_id", UUID.class),
                result.getString("legal_name"),
                result.getString("preferred_name"),
                result.getObject("birth_date", LocalDate.class),
                result.getString("gender_code"),
                result.getString("gender_description"),
                result.getString("learner_status"),
                result.getLong("learner_version"),
                result.getObject("enrolment_id", UUID.class),
                result.getString("admission_number"),
                result.getObject("campus_id", UUID.class),
                result.getString("campus_name"),
                result.getObject("academic_session_id", UUID.class),
                result.getString("academic_session_name"),
                result.getString("grade_code"),
                result.getString("placement_status"),
                result.getObject("enrolment_starts_on", LocalDate.class),
                result.getObject("enrolment_ends_on", LocalDate.class),
                result.getLong("enrolment_version"),
                result.getObject("section_id", UUID.class),
                result.getString("section_code"),
                result.getString("section_name"),
                result.getString("roll_number"),
                result.getLong("guardian_count"),
                result.getLong("portal_ready_count"), result.getString("admission_type"),
                result.getObject("rte_admission_session_id", UUID.class), result.getString("rte_session_name"),
                result.getString("rte_reference"));
    }

    private static StudentListItem mapStudent(ResultSet result, int row) throws SQLException {
        UUID learnerId = result.getObject("learner_id", UUID.class);
        UUID enrolmentId = result.getObject("enrolment_id", UUID.class);
        return new StudentListItem(
                learnerId, reference("STU", learnerId), result.getString("legal_name"),
                result.getString("preferred_name"), result.getObject("birth_date", LocalDate.class),
                result.getString("learner_status"), enrolmentId, reference("ENR", enrolmentId),
                result.getString("admission_number"), result.getObject("campus_id", UUID.class),
                result.getString("campus_name"), result.getObject("academic_session_id", UUID.class),
                result.getString("academic_session_name"), result.getString("grade_code"),
                result.getString("placement_status"), result.getObject("section_id", UUID.class),
                result.getString("section_code"), result.getString("section_name"),
                result.getString("roll_number"), result.getLong("guardian_count"),
                result.getLong("portal_ready_count"), result.getLong("learner_version"),
                result.getLong("enrolment_version"), result.getString("admission_type"),
                result.getObject("rte_admission_session_id", UUID.class), result.getString("rte_session_name"),
                result.getString("rte_reference"));
    }

    private static StudentListItem student(StoredStudent value) {
        return new StudentListItem(
                value.learnerId(), reference("STU", value.learnerId()), value.legalName(), value.preferredName(),
                value.birthDate(), value.learnerStatus(), value.enrolmentId(), reference("ENR", value.enrolmentId()),
                value.admissionNumber(), value.campusId(), value.campusName(), value.academicSessionId(),
                value.academicSessionName(), value.gradeCode(), value.placementStatus(), value.sectionId(),
                value.sectionCode(), value.sectionName(), value.rollNumber(), value.guardianCount(),
                value.portalReadyCount(), value.learnerVersion(), value.enrolmentVersion(), value.admissionType(),
                value.rteAdmissionSessionId(), value.rteSessionName(), value.rteReference());
    }

    private void auditMutation(
            AuthenticatedUser user,
            StoredStudent student,
            String action,
            String subjectType,
            UUID subjectId,
            String reason,
            Map<String, ?> metadata) {
        audit.recordScoped(user, student.actorMembershipId(), student.schoolId(), action, subjectType,
                subjectId.toString(), AuditOutcome.SUCCEEDED, clean(reason), metadata);
    }

    private static MapSqlParameterSource scope(StoredStudent student) {
        return new MapSqlParameterSource()
                .addValue("learnerId", student.learnerId())
                .addValue("enrolmentId", student.enrolmentId())
                .addValue("organisationId", student.organisationId())
                .addValue("schoolId", student.schoolId())
                .addValue("campusId", student.campusId())
                .addValue("academicSessionId", student.academicSessionId())
                .addValue("gradeCode", student.gradeCode())
                .addValue("actorMembershipId", student.actorMembershipId());
    }

    private static String portalReadiness(String access, String email, String phone) {
        return switch (access) {
            case "ACTIVE" -> "ACTIVE";
            case "INVITED" -> "INVITED";
            case "DENIED", "SUSPENDED" -> "ACCESS_BLOCKED";
            default -> email != null || phone != null ? "READY" : "CONTACT_REQUIRED";
        };
    }

    private static void validateGender(String code, String description) {
        if (!GENDER_CODES.contains(code)) {
            throw badRequest("INVALID_GENDER_CODE", "Choose a supported gender value.");
        }
        if ("SELF_DESCRIBED".equals(code) != (description != null)) {
            throw badRequest("INVALID_GENDER_DESCRIPTION",
                    "A description is required only when the gender value is self-described.");
        }
    }

    private static UUID requireOrganisation(AuthenticatedUser user) {
        if (user.activeOrganisationId() == null) {
            throw conflict("ORGANISATION_SELECTION_REQUIRED", "Select a school organisation before opening students.");
        }
        return user.activeOrganisationId();
    }

    private static String reference(String prefix, UUID id) {
        return prefix + "-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static String requiredClean(String value, String label) {
        String result = clean(value);
        if (result == null) throw badRequest("REQUIRED_VALUE_MISSING", "Enter the " + label + ".");
        return result;
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

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static boolean equalNullable(Object first, Object second) {
        return first == null ? second == null : first.equals(second);
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
        return new ApiException(HttpStatus.NOT_FOUND, "STUDENT_NOT_FOUND", "The student record was not found.");
    }

    private record StoredGuardian(UUID id, long guardianVersion, long relationshipVersion) {
    }

    private record StoredSection(UUID id, Integer capacity, long activeCount) {
    }

    private record StoredPlacement(UUID id, UUID sectionId, String rollNumber, LocalDate startsOn) {
    }

    private record SectionCreationScope(UUID schoolId, UUID actorMembershipId) {
    }

    private record StoredStudent(
            UUID learnerId,
            UUID organisationId,
            UUID schoolId,
            UUID actorMembershipId,
            String legalName,
            String preferredName,
            LocalDate birthDate,
            String genderCode,
            String genderDescription,
            String learnerStatus,
            long learnerVersion,
            UUID enrolmentId,
            String admissionNumber,
            UUID campusId,
            String campusName,
            UUID academicSessionId,
            String academicSessionName,
            String gradeCode,
            String placementStatus,
            LocalDate enrolmentStartsOn,
            LocalDate enrolmentEndsOn,
            long enrolmentVersion,
            UUID sectionId,
            String sectionCode,
            String sectionName,
            String rollNumber,
            long guardianCount,
            long portalReadyCount,
            String admissionType,
            UUID rteAdmissionSessionId,
            String rteSessionName,
            String rteReference) {
    }
}
