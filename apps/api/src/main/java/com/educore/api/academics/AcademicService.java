package com.educore.api.academics;

import static com.educore.api.academics.AcademicController.*;
import static com.educore.api.academics.AcademicSnapshot.*;
import static com.educore.api.academics.TeacherAcademicSnapshot.*;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
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
public class AcademicService {

    private static final Set<String> TERM_STATUSES = Set.of("ACTIVE", "INACTIVE");
    private static final Set<String> SESSION_STATUSES = Set.of("PLANNED", "ACTIVE", "CLOSED", "ARCHIVED");
    private static final Set<String> SUBJECT_CATEGORIES =
            Set.of("LANGUAGE", "CORE", "SKILL", "ACTIVITY", "INTERNAL", "OTHER");
    private static final Set<String> RESOURCE_TYPES =
            Set.of("SYLLABUS", "TEXTBOOK", "WORKBOOK", "REFERENCE", "DIGITAL");
    private static final Set<String> ACTIVE_STATUSES = Set.of("ACTIVE", "INACTIVE");
    private static final Set<String> ALLOCATION_ROLES = Set.of("PRIMARY", "CO_TEACHER");
    private static final Set<String> PERIOD_KINDS =
            Set.of("TEACHING", "ASSEMBLY", "BREAK", "ACTIVITY", "DISMISSAL");

    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final Clock clock;

    public AcademicService(NamedParameterJdbcTemplate jdbc, SecurityAuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AcademicSnapshot snapshot(AuthenticatedUser user, UUID requestedSchoolId) {
        Scope scope = requireScope(user, requestedSchoolId, "academics.configure");
        MapSqlParameterSource parameters = scope.parameters();
        return new AcademicSnapshot(
                scope.schoolId(),
                scope.schoolName(),
                jdbc.query("""
                        SELECT id, name, starts_on, ends_on, status, row_version,
                               rte_class_one_seat_limit, rte_min_age_years, rte_max_age_years
                        FROM academic_sessions
                        WHERE organisation_id = :organisationId AND school_id = :schoolId
                        ORDER BY starts_on DESC, id
                        """, parameters, (result, row) -> new SessionOption(
                        result.getObject("id", UUID.class), result.getString("name"),
                        result.getObject("starts_on", LocalDate.class), result.getObject("ends_on", LocalDate.class),
                        result.getString("status"), result.getLong("row_version"), result.getInt("rte_class_one_seat_limit"),
                        result.getInt("rte_min_age_years"), result.getInt("rte_max_age_years"))),
                jdbc.query("""
                        SELECT id, display_name, time_zone
                        FROM campuses
                        WHERE organisation_id = :organisationId AND school_id = :schoolId AND status = 'ACTIVE'
                        ORDER BY display_name, id
                        """, parameters, (result, row) -> new CampusOption(
                        result.getObject("id", UUID.class), result.getString("display_name"),
                        result.getString("time_zone"))),
                jdbc.query("""
                        SELECT id, campus_id, academic_session_id, grade_code, code, display_name, status
                        FROM class_sections
                        WHERE organisation_id = :organisationId AND school_id = :schoolId
                        ORDER BY academic_session_id, grade_code, code, id
                        """, parameters, (result, row) -> new SectionOption(
                        result.getObject("id", UUID.class), result.getObject("campus_id", UUID.class),
                        result.getObject("academic_session_id", UUID.class), result.getString("grade_code"),
                        result.getString("code"), result.getString("display_name"), result.getString("status"))),
                jdbc.query("""
                        SELECT DISTINCT membership.id, account.display_name, account.email_normalized
                        FROM memberships membership
                        JOIN user_accounts account ON account.id = membership.user_id
                        JOIN membership_roles membership_role ON membership_role.membership_id = membership.id
                        JOIN roles role ON role.id = membership_role.role_id
                        JOIN staff_profiles staff ON staff.user_account_id = membership.user_id
                          AND staff.organisation_id = membership.organisation_id
                          AND staff.school_id = membership.school_id AND staff.status = 'ACTIVE'
                        JOIN staff_assignments employment ON employment.staff_profile_id = staff.id
                          AND employment.organisation_id = staff.organisation_id
                          AND employment.school_id = staff.school_id AND employment.status = 'ACTIVE'
                        JOIN staff_designations designation ON designation.id = employment.staff_designation_id
                          AND designation.organisation_id = employment.organisation_id
                          AND designation.school_id = employment.school_id
                          AND designation.status = 'ACTIVE' AND designation.teaching_role
                        WHERE membership.organisation_id = :organisationId
                          AND membership.school_id = :schoolId
                          AND membership.status = 'ACTIVE'
                          AND membership.valid_from <= CURRENT_TIMESTAMP
                          AND (membership.valid_until IS NULL OR membership.valid_until > CURRENT_TIMESTAMP)
                          AND role.code = 'teacher'
                          AND (role.organisation_id IS NULL OR role.organisation_id = membership.organisation_id)
                        ORDER BY account.display_name, membership.id
                        """, parameters, (result, row) -> new TeacherOption(
                        result.getObject("id", UUID.class), result.getString("display_name"),
                        result.getString("email_normalized"))),
                jdbc.query("""
                        SELECT id, academic_session_id, code, display_name, starts_on, ends_on, status, row_version
                        FROM academic_terms
                        WHERE organisation_id = :organisationId AND school_id = :schoolId
                        ORDER BY starts_on, code, id
                        """, parameters, AcademicService::mapTerm),
                jdbc.query("""
                        SELECT id, code, display_name, short_name, subject_category, language_code,
                               board_subject_code, status, row_version
                        FROM school_subjects
                        WHERE organisation_id = :organisationId AND school_id = :schoolId
                        ORDER BY display_name, id
                        """, parameters, AcademicService::mapSubject),
                jdbc.query("""
                        SELECT id, academic_session_id, subject_id, grade_code, resource_type, title,
                               description, author, publisher, edition, isbn, source_url, display_order,
                               status, row_version
                        FROM academic_subject_resources
                        WHERE organisation_id = :organisationId AND school_id = :schoolId
                        ORDER BY academic_session_id, grade_code, subject_id, display_order, title, id
                        """, parameters, AcademicService::mapResource),
                jdbc.query("""
                        SELECT publication.id, publication.academic_session_id, publication.grade_code,
                               publication.publication_version, publication.status,
                               publication.supersedes_publication_id, publication.created_at,
                               publication.published_at, publication.row_version,
                               count(item.id) AS item_count
                        FROM curriculum_publications publication
                        LEFT JOIN curriculum_publication_items item
                          ON item.curriculum_publication_id = publication.id
                         AND item.organisation_id = publication.organisation_id
                         AND item.school_id = publication.school_id
                        WHERE publication.organisation_id = :organisationId
                          AND publication.school_id = :schoolId
                        GROUP BY publication.id
                        ORDER BY publication.academic_session_id, publication.grade_code,
                                 publication.publication_version DESC, publication.id
                        """, parameters, AcademicService::mapCurriculumPublication),
                jdbc.query("""
                        SELECT item.id, item.curriculum_publication_id, item.source_resource_id,
                               item.subject_code_snapshot, item.subject_name_snapshot,
                               item.subject_short_name_snapshot, item.resource_type_snapshot,
                               item.title_snapshot, item.description_snapshot, item.author_snapshot,
                               item.publisher_snapshot, item.edition_snapshot, item.isbn_snapshot,
                               item.source_url_snapshot, item.display_order_snapshot
                        FROM curriculum_publication_items item
                        JOIN curriculum_publications publication
                          ON publication.id = item.curriculum_publication_id
                         AND publication.organisation_id = item.organisation_id
                         AND publication.school_id = item.school_id
                        WHERE item.organisation_id = :organisationId AND item.school_id = :schoolId
                        ORDER BY publication.publication_version DESC, item.subject_name_snapshot,
                                 item.display_order_snapshot, item.title_snapshot, item.id
                        """, parameters, AcademicService::mapCurriculumItem),
                jdbc.query("""
                        SELECT id, class_section_id, subject_id, weekly_target_periods, status, row_version
                        FROM section_subject_offerings
                        WHERE organisation_id = :organisationId AND school_id = :schoolId
                        ORDER BY class_section_id, subject_id, id
                        """, parameters, AcademicService::mapOffering),
                jdbc.query("""
                        SELECT id, section_subject_offering_id, teacher_membership_id, allocation_role,
                               starts_on, ends_on, status, row_version
                        FROM teaching_allocations
                        WHERE organisation_id = :organisationId AND school_id = :schoolId
                        ORDER BY section_subject_offering_id, allocation_role, starts_on, id
                        """, parameters, AcademicService::mapAllocation),
                jdbc.query("""
                        SELECT id, campus_id, academic_session_id, day_of_week, sequence_number, code,
                               display_name, starts_at, ends_at, period_kind, status, row_version
                        FROM bell_schedule_periods
                        WHERE organisation_id = :organisationId AND school_id = :schoolId
                        ORDER BY academic_session_id, campus_id, day_of_week, sequence_number, id
                        """, parameters, AcademicService::mapPeriod),
                jdbc.query("""
                        SELECT id, campus_id, academic_session_id, academic_term_id, plan_name, status,
                               supersedes_plan_id, published_at, row_version
                        FROM timetable_plans
                        WHERE organisation_id = :organisationId AND school_id = :schoolId
                        ORDER BY created_at DESC, id
                        """, parameters, AcademicService::mapPlan),
                jdbc.query("""
                        SELECT entry.id, entry.timetable_plan_id, entry.class_section_id,
                               entry.section_subject_offering_id, entry.bell_schedule_period_id,
                               entry.teacher_membership_id, entry.room_label, entry.note, entry.row_version
                        FROM timetable_entries entry
                        JOIN timetable_plans plan ON plan.id = entry.timetable_plan_id
                          AND plan.organisation_id = entry.organisation_id AND plan.school_id = entry.school_id
                        WHERE entry.organisation_id = :organisationId AND entry.school_id = :schoolId
                          AND plan.status IN ('DRAFT', 'PUBLISHED')
                        ORDER BY entry.timetable_plan_id, entry.class_section_id,
                                 entry.bell_schedule_period_id, entry.id
                        """, parameters, AcademicService::mapEntry));
    }

    @Transactional(readOnly = true)
    public TeacherAcademicSnapshot teacherSnapshot(
            AuthenticatedUser user, UUID requestedSchoolId) {
        Scope scope = requireScope(user, requestedSchoolId, "academics.read");
        MapSqlParameterSource parameters = scope.parameters();
        List<TeachingGroup> teachingGroups = jdbc.query("""
                SELECT allocation.id AS allocation_id, offering.id AS offering_id,
                       session.id AS academic_session_id, session.name AS academic_session_name,
                       section.id AS section_id, section.grade_code, section.code AS section_code,
                       section.display_name AS section_name, subject.id AS subject_id,
                       subject.display_name AS subject_name, subject.short_name AS subject_short_name,
                       offering.weekly_target_periods, allocation.allocation_role,
                       allocation.starts_on, allocation.ends_on
                FROM teaching_allocations allocation
                JOIN section_subject_offerings offering
                  ON offering.id = allocation.section_subject_offering_id
                 AND offering.organisation_id = allocation.organisation_id
                 AND offering.school_id = allocation.school_id
                JOIN class_sections section
                  ON section.id = offering.class_section_id
                 AND section.organisation_id = offering.organisation_id
                 AND section.school_id = offering.school_id
                JOIN academic_sessions session
                  ON session.id = section.academic_session_id
                 AND session.organisation_id = section.organisation_id
                 AND session.school_id = section.school_id
                JOIN school_subjects subject
                  ON subject.id = offering.subject_id
                 AND subject.organisation_id = offering.organisation_id
                 AND subject.school_id = offering.school_id
                WHERE allocation.organisation_id = :organisationId
                  AND allocation.school_id = :schoolId
                  AND allocation.teacher_membership_id = :actorMembershipId
                  AND allocation.status = 'ACTIVE'
                  AND allocation.starts_on <= CURRENT_DATE
                  AND (allocation.ends_on IS NULL OR allocation.ends_on >= CURRENT_DATE)
                  AND offering.status = 'ACTIVE'
                  AND section.status = 'ACTIVE'
                  AND subject.status = 'ACTIVE'
                  AND session.status IN ('PLANNED', 'ACTIVE')
                  AND EXISTS (
                      SELECT 1
                      FROM memberships teacher_membership
                      JOIN membership_roles teacher_membership_role
                        ON teacher_membership_role.membership_id = teacher_membership.id
                      JOIN roles teacher_role ON teacher_role.id = teacher_membership_role.role_id
                      JOIN staff_profiles staff
                        ON staff.user_account_id = teacher_membership.user_id
                       AND staff.organisation_id = teacher_membership.organisation_id
                       AND staff.school_id = teacher_membership.school_id
                      JOIN staff_assignments employment
                        ON employment.staff_profile_id = staff.id
                       AND employment.organisation_id = staff.organisation_id
                       AND employment.school_id = staff.school_id
                      JOIN staff_designations designation
                        ON designation.id = employment.staff_designation_id
                       AND designation.organisation_id = employment.organisation_id
                       AND designation.school_id = employment.school_id
                      WHERE teacher_membership.id = allocation.teacher_membership_id
                        AND teacher_membership.organisation_id = allocation.organisation_id
                        AND teacher_membership.school_id = allocation.school_id
                        AND teacher_membership.status = 'ACTIVE'
                        AND teacher_membership.valid_from <= CURRENT_TIMESTAMP
                        AND (teacher_membership.valid_until IS NULL
                             OR teacher_membership.valid_until > CURRENT_TIMESTAMP)
                        AND teacher_role.code = 'teacher'
                        AND (teacher_role.organisation_id IS NULL
                             OR teacher_role.organisation_id = teacher_membership.organisation_id)
                        AND staff.status = 'ACTIVE'
                        AND employment.status = 'ACTIVE'
                        AND employment.starts_on <= CURRENT_DATE
                        AND (employment.ends_on IS NULL OR employment.ends_on >= CURRENT_DATE)
                        AND designation.status = 'ACTIVE'
                        AND designation.teaching_role
                  )
                ORDER BY session.starts_on DESC, section.grade_code, section.code,
                         subject.display_name, allocation.starts_on, allocation.id
                """, parameters, AcademicService::mapTeachingGroup);
        List<PublishedScheduleEntry> scheduleEntries = jdbc.query("""
                SELECT entry.id AS entry_id, plan.id AS timetable_plan_id,
                       plan.plan_name AS timetable_name, plan.published_at,
                       campus.id AS campus_id, campus.display_name AS campus_name,
                       session.id AS academic_session_id, session.name AS academic_session_name,
                       term.id AS academic_term_id, term.display_name AS academic_term_name,
                       section.id AS section_id, section.grade_code,
                       section.code AS section_code, section.display_name AS section_name,
                       entry.day_of_week_snapshot, entry.sequence_number_snapshot,
                       entry.period_name_snapshot, entry.starts_at_snapshot,
                       entry.ends_at_snapshot, entry.subject_name_snapshot,
                       entry.subject_short_name_snapshot, entry.room_label, entry.note
                FROM timetable_entries entry
                JOIN timetable_plans plan
                  ON plan.id = entry.timetable_plan_id
                 AND plan.organisation_id = entry.organisation_id
                 AND plan.school_id = entry.school_id
                JOIN campuses campus
                  ON campus.id = plan.campus_id
                 AND campus.organisation_id = plan.organisation_id
                 AND campus.school_id = plan.school_id
                JOIN academic_sessions session
                  ON session.id = plan.academic_session_id
                 AND session.organisation_id = plan.organisation_id
                 AND session.school_id = plan.school_id
                LEFT JOIN academic_terms term
                  ON term.id = plan.academic_term_id
                 AND term.organisation_id = plan.organisation_id
                 AND term.school_id = plan.school_id
                JOIN class_sections section
                  ON section.id = entry.class_section_id
                 AND section.organisation_id = entry.organisation_id
                 AND section.school_id = entry.school_id
                WHERE entry.organisation_id = :organisationId
                  AND entry.school_id = :schoolId
                  AND entry.teacher_membership_id = :actorMembershipId
                  AND plan.status = 'PUBLISHED'
                  AND session.status IN ('PLANNED', 'ACTIVE')
                  AND EXISTS (
                      SELECT 1
                      FROM teaching_allocations current_allocation
                      WHERE current_allocation.organisation_id = entry.organisation_id
                        AND current_allocation.school_id = entry.school_id
                        AND current_allocation.section_subject_offering_id =
                            entry.section_subject_offering_id
                        AND current_allocation.teacher_membership_id =
                            entry.teacher_membership_id
                        AND current_allocation.status = 'ACTIVE'
                        AND current_allocation.starts_on <= CURRENT_DATE
                        AND (current_allocation.ends_on IS NULL
                             OR current_allocation.ends_on >= CURRENT_DATE)
                  )
                  AND EXISTS (
                      SELECT 1
                      FROM memberships teacher_membership
                      JOIN membership_roles teacher_membership_role
                        ON teacher_membership_role.membership_id = teacher_membership.id
                      JOIN roles teacher_role ON teacher_role.id = teacher_membership_role.role_id
                      JOIN staff_profiles staff
                        ON staff.user_account_id = teacher_membership.user_id
                       AND staff.organisation_id = teacher_membership.organisation_id
                       AND staff.school_id = teacher_membership.school_id
                      JOIN staff_assignments employment
                        ON employment.staff_profile_id = staff.id
                       AND employment.organisation_id = staff.organisation_id
                       AND employment.school_id = staff.school_id
                      JOIN staff_designations designation
                        ON designation.id = employment.staff_designation_id
                       AND designation.organisation_id = employment.organisation_id
                       AND designation.school_id = employment.school_id
                      WHERE teacher_membership.id = entry.teacher_membership_id
                        AND teacher_membership.organisation_id = entry.organisation_id
                        AND teacher_membership.school_id = entry.school_id
                        AND teacher_membership.status = 'ACTIVE'
                        AND teacher_membership.valid_from <= CURRENT_TIMESTAMP
                        AND (teacher_membership.valid_until IS NULL
                             OR teacher_membership.valid_until > CURRENT_TIMESTAMP)
                        AND teacher_role.code = 'teacher'
                        AND (teacher_role.organisation_id IS NULL
                             OR teacher_role.organisation_id = teacher_membership.organisation_id)
                        AND staff.status = 'ACTIVE'
                        AND employment.status = 'ACTIVE'
                        AND employment.starts_on <= CURRENT_DATE
                        AND (employment.ends_on IS NULL OR employment.ends_on >= CURRENT_DATE)
                        AND designation.status = 'ACTIVE'
                        AND designation.teaching_role
                  )
                ORDER BY entry.day_of_week_snapshot, entry.sequence_number_snapshot,
                         entry.starts_at_snapshot, section.grade_code, section.code, entry.id
                """, parameters, AcademicService::mapPublishedScheduleEntry);
        return new TeacherAcademicSnapshot(
                scope.schoolId(), scope.schoolName(), teachingGroups, scheduleEntries);
    }

    @Transactional
    public AcademicMutationResponse createSession(AuthenticatedUser user, SessionRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "academics.configure");
        validateSession(request);
        UUID id = UUID.randomUUID();
        safely("ACADEMIC_SESSION_CONFLICT", "The session name already exists or another session is already active.", () ->
                jdbc.update("""
                        INSERT INTO academic_sessions (
                            id, organisation_id, school_id, name, starts_on, ends_on, status,
                            created_at, updated_at, row_version
                        ) VALUES (:id, :organisationId, :schoolId, :name, :startsOn, :endsOn,
                            :status, :now, :now, 0)
                        """, scope.parameters().addValue("id", id)
                        .addValue("name", required(request.name(), "academic session name"))
                        .addValue("startsOn", request.startsOn()).addValue("endsOn", request.endsOn())
                        .addValue("status", enumValue(request.status(), SESSION_STATUSES, "session status"))
                        .addValue("now", Instant.now(clock))));
        updateRteSessionPolicy(scope, id, request);
        audit(user, scope, "academic.session.created", "ACADEMIC_SESSION", id, request.reason(), sessionAuditMetadata(scope, id));
        return new AcademicMutationResponse(id, 0);
    }

    private void updateRteSessionPolicy(Scope scope, UUID id, SessionRequest request) {
        safely("RTE_SESSION_POLICY_CONFLICT", "Review the RTE age range and completed seat count before changing this policy.", () ->
                jdbc.update("""
                        UPDATE academic_sessions SET
                            rte_class_one_seat_limit=coalesce(:seats,rte_class_one_seat_limit),
                            rte_min_age_years=coalesce(:minAge,rte_min_age_years),
                            rte_max_age_years=coalesce(:maxAge,rte_max_age_years)
                        WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                        """, scope.parameters().addValue("id", id).addValue("seats", request.rteSeatLimit())
                        .addValue("minAge", request.rteMinAgeYears()).addValue("maxAge", request.rteMaxAgeYears())));
    }

    private Map<String, Object> sessionAuditMetadata(Scope scope, UUID id) {
        return jdbc.queryForMap("""
                SELECT status, row_version AS version, rte_class_one_seat_limit AS rte_seat_limit,
                       rte_min_age_years AS rte_min_age_years, rte_max_age_years AS rte_max_age_years
                FROM academic_sessions WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                """, scope.parameters().addValue("id", id));
    }

    @Transactional
    public AcademicMutationResponse updateSession(AuthenticatedUser user, UUID id, SessionRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "academics.configure");
        long version = requireVersion(request.expectedVersion());
        validateSession(request);
        validateSessionUpdate(scope, id, request);
        updateRteSessionPolicy(scope, id, request);
        int changed = safely("ACADEMIC_SESSION_CONFLICT", "The session name already exists or another session is already active.", () ->
                jdbc.update("""
                        UPDATE academic_sessions SET name = :name, starts_on = :startsOn,
                            ends_on = :endsOn, status = :status, updated_at = :now,
                            row_version = row_version + 1
                        WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                          AND row_version = :expectedVersion
                        """, scope.parameters().addValue("id", id)
                        .addValue("name", required(request.name(), "academic session name"))
                        .addValue("startsOn", request.startsOn()).addValue("endsOn", request.endsOn())
                        .addValue("status", enumValue(request.status(), SESSION_STATUSES, "session status"))
                        .addValue("expectedVersion", version).addValue("now", Instant.now(clock))));
        requireChanged(changed, "ACADEMIC_SESSION_NOT_FOUND", "The session changed or is no longer available.");
        audit(user, scope, "academic.session.updated", "ACADEMIC_SESSION", id,
                requiredReason(request.reason()), sessionAuditMetadata(scope, id));
        return new AcademicMutationResponse(id, version + 1);
    }

    @Transactional
    public AcademicMutationResponse createTerm(AuthenticatedUser user, TermRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "academics.configure");
        validateTerm(request, scope, null);
        UUID id = UUID.randomUUID();
        safely("TERM_ALREADY_EXISTS", "A term with this code already exists in the selected session.", () ->
                jdbc.update("""
                        INSERT INTO academic_terms (
                            id, organisation_id, school_id, academic_session_id, code, display_name,
                            starts_on, ends_on, status, created_at, updated_at, row_version
                        ) VALUES (:id, :organisationId, :schoolId, :sessionId, :code, :name,
                            :startsOn, :endsOn, :status, :now, :now, 0)
                        """, scope.parameters()
                        .addValue("id", id).addValue("sessionId", request.academicSessionId())
                        .addValue("code", upper(request.code())).addValue("name", required(request.name(), "term name"))
                        .addValue("startsOn", request.startsOn()).addValue("endsOn", request.endsOn())
                        .addValue("status", enumValue(request.status(), TERM_STATUSES, "term status"))
                        .addValue("now", Instant.now(clock))));
        audit(user, scope, "academic.term.created", "ACADEMIC_TERM", id, request.reason(), Map.of("code", upper(request.code())));
        return new AcademicMutationResponse(id, 0);
    }

    @Transactional
    public AcademicMutationResponse updateTerm(AuthenticatedUser user, UUID id, TermRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "academics.configure");
        long version = requireVersion(request.expectedVersion());
        validateTerm(request, scope, id);
        int changed = safely("TERM_ALREADY_EXISTS", "A term with this code already exists in the selected session.", () ->
                jdbc.update("""
                        UPDATE academic_terms SET academic_session_id = :sessionId, code = :code,
                            display_name = :name, starts_on = :startsOn, ends_on = :endsOn,
                            status = :status, updated_at = :now, row_version = row_version + 1
                        WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                          AND row_version = :expectedVersion
                        """, scope.parameters().addValue("id", id).addValue("sessionId", request.academicSessionId())
                        .addValue("code", upper(request.code())).addValue("name", required(request.name(), "term name"))
                        .addValue("startsOn", request.startsOn()).addValue("endsOn", request.endsOn())
                        .addValue("status", enumValue(request.status(), TERM_STATUSES, "term status"))
                        .addValue("expectedVersion", version).addValue("now", Instant.now(clock))));
        requireChanged(changed, "TERM_NOT_FOUND", "The term changed or is no longer available.");
        audit(user, scope, "academic.term.updated", "ACADEMIC_TERM", id, requiredReason(request.reason()), Map.of("version", version + 1));
        return new AcademicMutationResponse(id, version + 1);
    }

    @Transactional
    public AcademicMutationResponse createSubject(AuthenticatedUser user, SubjectRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "academics.configure");
        UUID id = UUID.randomUUID();
        safely("SUBJECT_ALREADY_EXISTS", "A subject with this code already exists.", () -> jdbc.update("""
                INSERT INTO school_subjects (
                    id, organisation_id, school_id, code, display_name, short_name, subject_category,
                    language_code, board_subject_code, status, created_at, updated_at, row_version
                ) VALUES (:id, :organisationId, :schoolId, :code, :name, :shortName, :category,
                    :languageCode, :boardCode, :status, :now, :now, 0)
                """, subjectParameters(scope, id, request, null)));
        audit(user, scope, "academic.subject.created", "SCHOOL_SUBJECT", id, request.reason(), Map.of("code", upper(request.code())));
        return new AcademicMutationResponse(id, 0);
    }

    @Transactional
    public AcademicMutationResponse updateSubject(AuthenticatedUser user, UUID id, SubjectRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "academics.configure");
        long version = requireVersion(request.expectedVersion());
        int changed = safely("SUBJECT_ALREADY_EXISTS", "A subject with this code already exists.", () -> jdbc.update("""
                UPDATE school_subjects SET code = :code, display_name = :name, short_name = :shortName,
                    subject_category = :category, language_code = :languageCode,
                    board_subject_code = :boardCode, status = :status, updated_at = :now,
                    row_version = row_version + 1
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                  AND row_version = :expectedVersion
                """, subjectParameters(scope, id, request, version)));
        requireChanged(changed, "SUBJECT_NOT_FOUND", "The subject changed or is no longer available.");
        audit(user, scope, "academic.subject.updated", "SCHOOL_SUBJECT", id, requiredReason(request.reason()), Map.of("version", version + 1));
        return new AcademicMutationResponse(id, version + 1);
    }

    @Transactional
    public AcademicMutationResponse createResource(AuthenticatedUser user, ResourceRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "academics.configure");
        validateResourceReferences(scope, request);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO academic_subject_resources (
                    id, organisation_id, school_id, academic_session_id, subject_id, grade_code,
                    resource_type, title, description, author, publisher, edition, isbn, source_url,
                    display_order, status, created_at, updated_at, row_version
                ) VALUES (:id, :organisationId, :schoolId, :sessionId, :subjectId, :gradeCode,
                    :resourceType, :title, :description, :author, :publisher, :edition, :isbn, :sourceUrl,
                    :displayOrder, :status, :now, :now, 0)
                """, resourceParameters(scope, id, request, null));
        audit(user, scope, "academic.resource.created", "ACADEMIC_SUBJECT_RESOURCE", id, request.reason(),
                Map.of("type", enumValue(request.resourceType(), RESOURCE_TYPES, "resource type"), "grade", upper(request.gradeCode())));
        return new AcademicMutationResponse(id, 0);
    }

    @Transactional
    public AcademicMutationResponse updateResource(AuthenticatedUser user, UUID id, ResourceRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "academics.configure");
        long version = requireVersion(request.expectedVersion());
        validateResourceReferences(scope, request);
        int changed = jdbc.update("""
                UPDATE academic_subject_resources SET academic_session_id = :sessionId,
                    subject_id = :subjectId, grade_code = :gradeCode, resource_type = :resourceType,
                    title = :title, description = :description, author = :author, publisher = :publisher,
                    edition = :edition, isbn = :isbn, source_url = :sourceUrl, display_order = :displayOrder,
                    status = :status, updated_at = :now, row_version = row_version + 1
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                  AND row_version = :expectedVersion
                """, resourceParameters(scope, id, request, version));
        requireChanged(changed, "RESOURCE_NOT_FOUND", "The syllabus or book record changed or is no longer available.");
        audit(user, scope, "academic.resource.updated", "ACADEMIC_SUBJECT_RESOURCE", id,
                requiredReason(request.reason()), Map.of("version", version + 1));
        return new AcademicMutationResponse(id, version + 1);
    }

    @Transactional
    public AcademicMutationResponse createCurriculumDraft(
            AuthenticatedUser user, CurriculumDraftRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "academics.configure");
        String gradeCode = upper(request.gradeCode());
        String reason = requiredReason(request.reason());
        MapSqlParameterSource parameters = scope.parameters()
                .addValue("sessionId", request.academicSessionId())
                .addValue("gradeCode", gradeCode);
        Integer sessionCount = jdbc.queryForObject("""
                SELECT count(*) FROM academic_sessions
                WHERE id = :sessionId AND organisation_id = :organisationId
                  AND school_id = :schoolId AND status <> 'ARCHIVED'
                """, parameters, Integer.class);
        if (sessionCount == null || sessionCount != 1) {
            throw badRequest("ACADEMIC_SESSION_NOT_FOUND", "Choose a current academic session from this school.");
        }
        List<CurriculumSource> sources = jdbc.query("""
                SELECT resource.id AS resource_id, subject.code AS subject_code,
                       subject.display_name AS subject_name, subject.short_name AS subject_short_name,
                       resource.resource_type, resource.title, resource.description, resource.author,
                       resource.publisher, resource.edition, resource.isbn, resource.source_url,
                       resource.display_order
                FROM academic_subject_resources resource
                JOIN school_subjects subject ON subject.id = resource.subject_id
                  AND subject.organisation_id = resource.organisation_id
                  AND subject.school_id = resource.school_id
                WHERE resource.organisation_id = :organisationId AND resource.school_id = :schoolId
                  AND resource.academic_session_id = :sessionId AND resource.grade_code = :gradeCode
                  AND resource.status = 'ACTIVE' AND subject.status = 'ACTIVE'
                ORDER BY subject.display_name, resource.display_order, resource.title, resource.id
                """, parameters, AcademicService::mapCurriculumSource);
        if (sources.isEmpty()) {
            throw conflict("EMPTY_CURRICULUM", "Add at least one active book or syllabus record for this session and grade.");
        }
        String lockKey = "curriculum:" + scope.schoolId() + ":" + request.academicSessionId() + ":" + gradeCode;
        jdbc.queryForObject(
                "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:lockKey, 0))) locked",
                new MapSqlParameterSource("lockKey", lockKey), Integer.class);
        Integer version = jdbc.queryForObject("""
                SELECT COALESCE(max(publication_version), 0) + 1
                FROM curriculum_publications
                WHERE organisation_id = :organisationId AND school_id = :schoolId
                  AND academic_session_id = :sessionId AND grade_code = :gradeCode
                """, parameters, Integer.class);
        UUID supersedesId = jdbc.query("""
                SELECT id FROM curriculum_publications
                WHERE organisation_id = :organisationId AND school_id = :schoolId
                  AND academic_session_id = :sessionId AND grade_code = :gradeCode
                  AND status = 'PUBLISHED'
                ORDER BY publication_version DESC, id DESC LIMIT 1
                """, parameters, result -> result.next() ? result.getObject("id", UUID.class) : null);
        UUID publicationId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        safely("CURRICULUM_DRAFT_EXISTS", "A curriculum draft already exists for this session and grade.", () ->
                jdbc.update("""
                        INSERT INTO curriculum_publications (
                            id, organisation_id, school_id, academic_session_id, grade_code,
                            publication_version, status, supersedes_publication_id,
                            created_by_membership_id, created_at, updated_at, row_version
                        ) VALUES (
                            :id, :organisationId, :schoolId, :sessionId, :gradeCode,
                            :publicationVersion, 'DRAFT', :supersedesId,
                            :actorMembershipId, :now, :now, 0
                        )
                        """, parameters.addValue("id", publicationId)
                        .addValue("publicationVersion", version)
                        .addValue("supersedesId", supersedesId).addValue("now", now)));
        for (CurriculumSource source : sources) {
            jdbc.update("""
                    INSERT INTO curriculum_publication_items (
                        id, organisation_id, school_id, curriculum_publication_id, source_resource_id,
                        subject_code_snapshot, subject_name_snapshot, subject_short_name_snapshot,
                        resource_type_snapshot, title_snapshot, description_snapshot, author_snapshot,
                        publisher_snapshot, edition_snapshot, isbn_snapshot, source_url_snapshot,
                        display_order_snapshot, created_at
                    ) VALUES (
                        :id, :organisationId, :schoolId, :publicationId, :sourceResourceId,
                        :subjectCode, :subjectName, :subjectShortName, :resourceType, :title,
                        :description, :author, :publisher, :edition, :isbn, :sourceUrl,
                        :displayOrder, :now
                    )
                    """, parameters.addValue("id", UUID.randomUUID())
                    .addValue("publicationId", publicationId).addValue("sourceResourceId", source.resourceId())
                    .addValue("subjectCode", source.subjectCode()).addValue("subjectName", source.subjectName())
                    .addValue("subjectShortName", source.subjectShortName()).addValue("resourceType", source.resourceType())
                    .addValue("title", source.title()).addValue("description", source.description())
                    .addValue("author", source.author()).addValue("publisher", source.publisher())
                    .addValue("edition", source.edition()).addValue("isbn", source.isbn())
                    .addValue("sourceUrl", source.sourceUrl()).addValue("displayOrder", source.displayOrder()));
        }
        audit(user, scope, "academic.curriculum-draft.created", "CURRICULUM_PUBLICATION",
                publicationId, reason, Map.of("grade", gradeCode, "version", version, "items", sources.size()));
        return new AcademicMutationResponse(publicationId, 0);
    }

    @Transactional
    public AcademicMutationResponse publishCurriculum(
            AuthenticatedUser user, UUID publicationId, CurriculumMutationRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "academics.configure");
        StoredCurriculumPublication publication = lockCurriculumDraft(scope, publicationId, request.expectedVersion());
        Integer itemCount = jdbc.queryForObject("""
                SELECT count(*) FROM curriculum_publication_items
                WHERE curriculum_publication_id = :publicationId
                  AND organisation_id = :organisationId AND school_id = :schoolId
                """, scope.parameters().addValue("publicationId", publicationId), Integer.class);
        if (itemCount == null || itemCount == 0) {
            throw conflict("EMPTY_CURRICULUM", "A curriculum publication must contain at least one item.");
        }
        Instant now = Instant.now(clock);
        MapSqlParameterSource parameters = scope.parameters().addValue("publicationId", publicationId)
                .addValue("sessionId", publication.sessionId()).addValue("gradeCode", publication.gradeCode())
                .addValue("expectedVersion", publication.rowVersion()).addValue("now", now);
        jdbc.update("""
                UPDATE curriculum_publications
                SET status = 'ARCHIVED', archived_at = :now, updated_at = :now,
                    row_version = row_version + 1
                WHERE organisation_id = :organisationId AND school_id = :schoolId
                  AND academic_session_id = :sessionId AND grade_code = :gradeCode
                  AND status = 'PUBLISHED'
                """, parameters);
        int changed = jdbc.update("""
                UPDATE curriculum_publications
                SET status = 'PUBLISHED', published_by_membership_id = :actorMembershipId,
                    published_at = :now, updated_at = :now, row_version = row_version + 1
                WHERE id = :publicationId AND organisation_id = :organisationId
                  AND school_id = :schoolId AND status = 'DRAFT' AND row_version = :expectedVersion
                """, parameters);
        requireChanged(changed, "STALE_CURRICULUM", "The curriculum draft changed before it could be published.");
        audit(user, scope, "academic.curriculum.published", "CURRICULUM_PUBLICATION",
                publicationId, requiredReason(request.reason()),
                Map.of("grade", publication.gradeCode(), "version", publication.publicationVersion(), "items", itemCount));
        return new AcademicMutationResponse(publicationId, publication.rowVersion() + 1);
    }

    @Transactional
    public AcademicMutationResponse cancelCurriculumDraft(
            AuthenticatedUser user, UUID publicationId, CurriculumMutationRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "academics.configure");
        StoredCurriculumPublication publication = lockCurriculumDraft(scope, publicationId, request.expectedVersion());
        int changed = jdbc.update("""
                UPDATE curriculum_publications
                SET status = 'CANCELLED', cancelled_at = :now, updated_at = :now,
                    row_version = row_version + 1
                WHERE id = :publicationId AND organisation_id = :organisationId
                  AND school_id = :schoolId AND status = 'DRAFT' AND row_version = :expectedVersion
                """, scope.parameters().addValue("publicationId", publicationId)
                .addValue("expectedVersion", publication.rowVersion()).addValue("now", Instant.now(clock)));
        requireChanged(changed, "STALE_CURRICULUM", "The curriculum draft changed before it could be cancelled.");
        audit(user, scope, "academic.curriculum-draft.cancelled", "CURRICULUM_PUBLICATION",
                publicationId, requiredReason(request.reason()),
                Map.of("grade", publication.gradeCode(), "version", publication.publicationVersion()));
        return new AcademicMutationResponse(publicationId, publication.rowVersion() + 1);
    }

    @Transactional
    public AcademicMutationResponse createOffering(AuthenticatedUser user, OfferingRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "academics.configure");
        validateOfferingReferences(scope, request.sectionId(), request.subjectId());
        UUID id = UUID.randomUUID();
        safely("OFFERING_ALREADY_EXISTS", "This subject is already configured for the selected section.", () -> jdbc.update("""
                INSERT INTO section_subject_offerings (
                    id, organisation_id, school_id, class_section_id, subject_id,
                    weekly_target_periods, status, created_at, updated_at, row_version
                ) VALUES (:id, :organisationId, :schoolId, :sectionId, :subjectId,
                    :target, :status, :now, :now, 0)
                """, offeringParameters(scope, id, request, null)));
        audit(user, scope, "academic.offering.created", "SECTION_SUBJECT_OFFERING", id, request.reason(), Map.of());
        return new AcademicMutationResponse(id, 0);
    }

    @Transactional
    public AcademicMutationResponse updateOffering(AuthenticatedUser user, UUID id, OfferingRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "academics.configure");
        long version = requireVersion(request.expectedVersion());
        validateOfferingReferences(scope, request.sectionId(), request.subjectId());
        int changed = safely("OFFERING_ALREADY_EXISTS", "This subject is already configured for the selected section.", () -> jdbc.update("""
                UPDATE section_subject_offerings SET class_section_id = :sectionId, subject_id = :subjectId,
                    weekly_target_periods = :target, status = :status, updated_at = :now,
                    row_version = row_version + 1
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                  AND row_version = :expectedVersion
                """, offeringParameters(scope, id, request, version)));
        requireChanged(changed, "OFFERING_NOT_FOUND", "The class subject changed or is no longer available.");
        audit(user, scope, "academic.offering.updated", "SECTION_SUBJECT_OFFERING", id,
                requiredReason(request.reason()), Map.of("version", version + 1));
        return new AcademicMutationResponse(id, version + 1);
    }

    @Transactional
    public AcademicMutationResponse createAllocation(AuthenticatedUser user, AllocationRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "academics.configure");
        validateAllocation(scope, request, null);
        UUID id = UUID.randomUUID();
        safely("ALLOCATION_ALREADY_EXISTS", "This teaching allocation already exists for the start date.", () -> jdbc.update("""
                INSERT INTO teaching_allocations (
                    id, organisation_id, school_id, section_subject_offering_id, teacher_membership_id,
                    allocation_role, starts_on, ends_on, status, created_at, updated_at, row_version
                ) VALUES (:id, :organisationId, :schoolId, :offeringId, :teacherId,
                    :role, :startsOn, :endsOn, :status, :now, :now, 0)
                """, allocationParameters(scope, id, request, null)));
        audit(user, scope, "academic.teaching-allocation.created", "TEACHING_ALLOCATION", id, request.reason(), Map.of());
        return new AcademicMutationResponse(id, 0);
    }

    @Transactional
    public AcademicMutationResponse updateAllocation(AuthenticatedUser user, UUID id, AllocationRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "academics.configure");
        long version = requireVersion(request.expectedVersion());
        validateAllocation(scope, request, id);
        int changed = safely("ALLOCATION_ALREADY_EXISTS", "This teaching allocation already exists for the start date.", () -> jdbc.update("""
                UPDATE teaching_allocations SET section_subject_offering_id = :offeringId,
                    teacher_membership_id = :teacherId, allocation_role = :role, starts_on = :startsOn,
                    ends_on = :endsOn, status = :status, updated_at = :now, row_version = row_version + 1
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                  AND row_version = :expectedVersion
                """, allocationParameters(scope, id, request, version)));
        requireChanged(changed, "ALLOCATION_NOT_FOUND", "The teaching allocation changed or is no longer available.");
        audit(user, scope, "academic.teaching-allocation.updated", "TEACHING_ALLOCATION", id,
                requiredReason(request.reason()), Map.of("version", version + 1));
        return new AcademicMutationResponse(id, version + 1);
    }

    @Transactional
    public AcademicMutationResponse createPeriod(AuthenticatedUser user, PeriodRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "academics.configure");
        validatePeriod(scope, request, null);
        UUID id = UUID.randomUUID();
        safely("PERIOD_ALREADY_EXISTS", "A period already uses this day, sequence, or code.", () -> jdbc.update("""
                INSERT INTO bell_schedule_periods (
                    id, organisation_id, school_id, campus_id, academic_session_id, day_of_week,
                    sequence_number, code, display_name, starts_at, ends_at, period_kind, status,
                    created_at, updated_at, row_version
                ) VALUES (:id, :organisationId, :schoolId, :campusId, :sessionId, :dayOfWeek,
                    :sequence, :code, :name, :startsAt, :endsAt, :kind, :status, :now, :now, 0)
                """, periodParameters(scope, id, request, null)));
        audit(user, scope, "academic.bell-period.created", "BELL_SCHEDULE_PERIOD", id, request.reason(), Map.of("day", request.dayOfWeek()));
        return new AcademicMutationResponse(id, 0);
    }

    @Transactional
    public AcademicMutationResponse updatePeriod(AuthenticatedUser user, UUID id, PeriodRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "academics.configure");
        long version = requireVersion(request.expectedVersion());
        validatePeriod(scope, request, id);
        int changed = safely("PERIOD_ALREADY_EXISTS", "A period already uses this day, sequence, or code.", () -> jdbc.update("""
                UPDATE bell_schedule_periods SET campus_id = :campusId, academic_session_id = :sessionId,
                    day_of_week = :dayOfWeek, sequence_number = :sequence, code = :code,
                    display_name = :name, starts_at = :startsAt, ends_at = :endsAt,
                    period_kind = :kind, status = :status, updated_at = :now, row_version = row_version + 1
                WHERE id = :id AND organisation_id = :organisationId AND school_id = :schoolId
                  AND row_version = :expectedVersion
                """, periodParameters(scope, id, request, version)));
        requireChanged(changed, "PERIOD_NOT_FOUND", "The period changed or is no longer available.");
        audit(user, scope, "academic.bell-period.updated", "BELL_SCHEDULE_PERIOD", id,
                requiredReason(request.reason()), Map.of("version", version + 1));
        return new AcademicMutationResponse(id, version + 1);
    }

    @Transactional
    public AcademicMutationResponse createDraft(AuthenticatedUser user, CreateTimetableDraftRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "academics.configure");
        validatePlanScope(scope, request.campusId(), request.academicSessionId(), request.academicTermId());
        UUID publishedId = jdbc.query("""
                SELECT id FROM timetable_plans
                WHERE organisation_id = :organisationId AND school_id = :schoolId
                  AND campus_id = :campusId AND academic_session_id = :sessionId AND status = 'PUBLISHED'
                """, scope.parameters().addValue("campusId", request.campusId())
                .addValue("sessionId", request.academicSessionId()),
                result -> result.next() ? result.getObject("id", UUID.class) : null);
        UUID planId = UUID.randomUUID();
        safely("TIMETABLE_DRAFT_EXISTS", "A draft already exists for this campus and session.", () -> jdbc.update("""
                INSERT INTO timetable_plans (
                    id, organisation_id, school_id, campus_id, academic_session_id, academic_term_id,
                    plan_name, status, created_by_membership_id, supersedes_plan_id,
                    created_at, updated_at, row_version
                ) VALUES (:id, :organisationId, :schoolId, :campusId, :sessionId, :termId,
                    :name, 'DRAFT', :actorMembershipId, :supersedesId, :now, :now, 0)
                """, scope.parameters().addValue("id", planId).addValue("campusId", request.campusId())
                .addValue("sessionId", request.academicSessionId()).addValue("termId", request.academicTermId())
                .addValue("name", required(request.name(), "timetable name"))
                .addValue("supersedesId", publishedId).addValue("now", Instant.now(clock))));
        int copied = request.copyPublished() && publishedId != null ? copyEntries(scope, publishedId, planId) : 0;
        audit(user, scope, "academic.timetable-draft.created", "TIMETABLE_PLAN", planId, request.reason(),
                Map.of("copiedEntries", copied));
        return new AcademicMutationResponse(planId, 0);
    }

    @Transactional
    public AcademicMutationResponse addEntry(AuthenticatedUser user, UUID planId, TimetableEntryRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "academics.configure");
        StoredPlan plan = lockDraft(scope, planId, request.expectedPlanVersion());
        validateEntry(scope, plan, request, null);
        UUID entryId = UUID.randomUUID();
        safely("TIMETABLE_SLOT_CONFLICT", "The class or teacher is already assigned in this timetable slot.", () -> jdbc.update("""
                INSERT INTO timetable_entries (
                    id, organisation_id, school_id, timetable_plan_id, class_section_id,
                    section_subject_offering_id, bell_schedule_period_id, teacher_membership_id,
                    subject_name_snapshot, subject_short_name_snapshot, period_name_snapshot,
                    day_of_week_snapshot, sequence_number_snapshot, starts_at_snapshot, ends_at_snapshot,
                    room_label, note, created_at, updated_at, row_version
                ) SELECT :id, :organisationId, :schoolId, :planId, :sectionId,
                    :offeringId, :periodId, :teacherId, subject.display_name, subject.short_name,
                    period.display_name, period.day_of_week, period.sequence_number,
                    period.starts_at, period.ends_at, :roomLabel, :note, :now, :now, 0
                  FROM section_subject_offerings offering
                  JOIN school_subjects subject ON subject.id = offering.subject_id
                    AND subject.organisation_id = offering.organisation_id AND subject.school_id = offering.school_id
                  JOIN bell_schedule_periods period ON period.id = :periodId
                    AND period.organisation_id = offering.organisation_id AND period.school_id = offering.school_id
                 WHERE offering.id = :offeringId AND offering.organisation_id = :organisationId
                   AND offering.school_id = :schoolId
                """, scope.parameters().addValue("id", entryId).addValue("planId", planId)
                .addValue("sectionId", request.sectionId()).addValue("offeringId", request.offeringId())
                .addValue("periodId", request.periodId()).addValue("teacherId", request.teacherMembershipId())
                .addValue("roomLabel", clean(request.roomLabel())).addValue("note", clean(request.note()))
                .addValue("now", Instant.now(clock))));
        long newVersion = touchPlan(scope, planId, plan.rowVersion());
        audit(user, scope, "academic.timetable-entry.created", "TIMETABLE_ENTRY", entryId, request.reason(), Map.of("planId", planId));
        return new AcademicMutationResponse(entryId, newVersion);
    }

    @Transactional
    public AcademicMutationResponse removeEntry(
            AuthenticatedUser user, UUID planId, UUID entryId, DraftMutationRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "academics.configure");
        StoredPlan plan = lockDraft(scope, planId, request.expectedPlanVersion());
        int removed = jdbc.update("""
                DELETE FROM timetable_entries
                WHERE id = :entryId AND timetable_plan_id = :planId
                  AND organisation_id = :organisationId AND school_id = :schoolId
                """, scope.parameters().addValue("entryId", entryId).addValue("planId", planId));
        requireChanged(removed, "TIMETABLE_ENTRY_NOT_FOUND", "The draft entry was not found.");
        long newVersion = touchPlan(scope, planId, plan.rowVersion());
        audit(user, scope, "academic.timetable-entry.removed", "TIMETABLE_ENTRY", entryId,
                requiredReason(request.reason()), Map.of("planId", planId));
        return new AcademicMutationResponse(planId, newVersion);
    }

    @Transactional
    public AcademicMutationResponse publish(AuthenticatedUser user, UUID planId, DraftMutationRequest request) {
        Scope scope = requireScope(user, request.schoolId(), "academics.configure");
        StoredPlan plan = lockDraft(scope, planId, request.expectedPlanVersion());
        PublicationCheck check = validatePublication(scope, plan);
        if (check.entryCount() == 0) {
            throw conflict("EMPTY_TIMETABLE", "Add at least one teaching period before publishing.");
        }
        if (check.invalidCount() > 0) {
            throw conflict("TIMETABLE_REQUIRES_REVIEW", "One or more entries no longer match active sections, periods, subjects, teachers, or allocations.");
        }
        if (check.overlapCount() > 0) {
            throw conflict("TIMETABLE_OVERLAP", "A teacher or class has overlapping times. Resolve the collisions before publishing.");
        }
        jdbc.update("""
                UPDATE timetable_entries entry SET
                    subject_name_snapshot = subject.display_name,
                    subject_short_name_snapshot = subject.short_name,
                    period_name_snapshot = period.display_name,
                    day_of_week_snapshot = period.day_of_week,
                    sequence_number_snapshot = period.sequence_number,
                    starts_at_snapshot = period.starts_at,
                    ends_at_snapshot = period.ends_at,
                    updated_at = :now,
                    row_version = entry.row_version + 1
                FROM section_subject_offerings offering
                JOIN school_subjects subject ON subject.id = offering.subject_id
                  AND subject.organisation_id = offering.organisation_id AND subject.school_id = offering.school_id
                JOIN bell_schedule_periods period ON period.organisation_id = offering.organisation_id
                  AND period.school_id = offering.school_id
                WHERE entry.timetable_plan_id = :planId
                  AND entry.section_subject_offering_id = offering.id
                  AND entry.bell_schedule_period_id = period.id
                  AND entry.organisation_id = :organisationId AND entry.school_id = :schoolId
                """, scope.parameters().addValue("planId", planId).addValue("now", Instant.now(clock)));
        jdbc.update("""
                UPDATE timetable_plans SET status = 'ARCHIVED', updated_at = :now, row_version = row_version + 1
                WHERE organisation_id = :organisationId AND school_id = :schoolId
                  AND campus_id = :campusId AND academic_session_id = :sessionId
                  AND status = 'PUBLISHED'
                """, scope.parameters().addValue("campusId", plan.campusId())
                .addValue("sessionId", plan.sessionId()).addValue("now", Instant.now(clock)));
        int changed = jdbc.update("""
                UPDATE timetable_plans SET status = 'PUBLISHED', published_by_membership_id = :actorMembershipId,
                    published_at = :now, updated_at = :now, row_version = row_version + 1
                WHERE id = :planId AND organisation_id = :organisationId AND school_id = :schoolId
                  AND status = 'DRAFT' AND row_version = :expectedVersion
                """, scope.parameters().addValue("planId", planId).addValue("expectedVersion", plan.rowVersion())
                .addValue("now", Instant.now(clock)));
        requireChanged(changed, "STALE_TIMETABLE", "The draft changed before it could be published.");
        List<String> warnings = workloadWarnings(scope, planId);
        audit(user, scope, "academic.timetable.published", "TIMETABLE_PLAN", planId,
                requiredReason(request.reason()), Map.of("entries", check.entryCount(), "warnings", warnings.size()));
        return new AcademicMutationResponse(planId, plan.rowVersion() + 1, warnings);
    }

    private void validateTerm(TermRequest request, Scope scope, UUID termId) {
        if (request.endsOn().isBefore(request.startsOn())) {
            throw badRequest("INVALID_TERM_DATES", "The term end date must be on or after the start date.");
        }
        List<SessionDates> sessions = jdbc.query("""
                SELECT starts_on, ends_on FROM academic_sessions
                WHERE id = :sessionId AND organisation_id = :organisationId AND school_id = :schoolId
                """, scope.parameters().addValue("sessionId", request.academicSessionId()),
                (result, row) -> new SessionDates(result.getObject("starts_on", LocalDate.class), result.getObject("ends_on", LocalDate.class)));
        if (sessions.isEmpty()) throw badRequest("ACADEMIC_SESSION_NOT_FOUND", "Choose an academic session from this school.");
        SessionDates session = sessions.getFirst();
        if (request.startsOn().isBefore(session.startsOn()) || request.endsOn().isAfter(session.endsOn())) {
            throw badRequest("TERM_OUTSIDE_SESSION", "Term dates must remain inside the selected academic session.");
        }
        enumValue(request.status(), TERM_STATUSES, "term status");
    }

    private void validateSession(SessionRequest request) {
        if (!request.endsOn().isAfter(request.startsOn())) {
            throw badRequest("INVALID_SESSION_DATES", "The academic session end date must be after its start date.");
        }
        enumValue(request.status(), SESSION_STATUSES, "session status");
    }

    private void validateSessionUpdate(Scope scope, UUID sessionId, SessionRequest request) {
        List<String> statuses = jdbc.query("""
                SELECT status FROM academic_sessions
                WHERE id = :sessionId AND organisation_id = :organisationId AND school_id = :schoolId
                FOR UPDATE
                """, scope.parameters().addValue("sessionId", sessionId),
                (result, row) -> result.getString("status"));
        if (statuses.isEmpty()) throw notFound("ACADEMIC_SESSION_NOT_FOUND", "The academic session was not found.");
        String current = statuses.getFirst();
        String requested = enumValue(request.status(), SESSION_STATUSES, "session status");
        boolean transitionAllowed = switch (current) {
            case "PLANNED" -> Set.of("PLANNED", "ACTIVE", "ARCHIVED").contains(requested);
            case "ACTIVE" -> Set.of("ACTIVE", "CLOSED").contains(requested);
            case "CLOSED" -> Set.of("CLOSED", "ARCHIVED").contains(requested);
            case "ARCHIVED" -> "ARCHIVED".equals(requested);
            default -> false;
        };
        if (!transitionAllowed) {
            throw conflict("INVALID_SESSION_TRANSITION", "The academic session cannot move from "
                    + current.toLowerCase(Locale.ROOT) + " to " + requested.toLowerCase(Locale.ROOT) + ".");
        }
        int outside = jdbc.queryForObject("""
                SELECT (
                    SELECT count(*) FROM academic_terms term
                    WHERE term.academic_session_id = :sessionId
                      AND term.organisation_id = :organisationId AND term.school_id = :schoolId
                      AND (term.starts_on < :startsOn OR term.ends_on > :endsOn)
                ) + (
                    SELECT count(*) FROM teaching_allocations allocation
                    JOIN section_subject_offerings offering
                      ON offering.id = allocation.section_subject_offering_id
                    JOIN class_sections section ON section.id = offering.class_section_id
                    WHERE section.academic_session_id = :sessionId
                      AND section.organisation_id = :organisationId AND section.school_id = :schoolId
                      AND (allocation.starts_on < :startsOn
                        OR (allocation.ends_on IS NOT NULL AND allocation.ends_on > :endsOn))
                )
                """, scope.parameters().addValue("sessionId", sessionId)
                .addValue("startsOn", request.startsOn()).addValue("endsOn", request.endsOn()), Integer.class);
        if (outside > 0) {
            throw conflict("SESSION_DATES_HAVE_DEPENDENCIES", "Update term and teacher-allocation dates before narrowing this session.");
        }
        if (Set.of("CLOSED", "ARCHIVED").contains(requested)) {
            int drafts = jdbc.queryForObject("""
                    SELECT count(*) FROM timetable_plans
                    WHERE academic_session_id = :sessionId AND organisation_id = :organisationId
                      AND school_id = :schoolId AND status = 'DRAFT'
                    """, scope.parameters().addValue("sessionId", sessionId), Integer.class);
            if (drafts > 0) throw conflict("SESSION_HAS_OPEN_TIMETABLE_DRAFT", "Publish or resolve the open timetable draft before closing this session.");
        }
    }

    private void validateResourceReferences(Scope scope, ResourceRequest request) {
        int count = jdbc.queryForObject("""
                SELECT count(*) FROM academic_sessions session
                JOIN school_subjects subject ON subject.organisation_id = session.organisation_id
                  AND subject.school_id = session.school_id AND subject.id = :subjectId
                WHERE session.id = :sessionId AND session.organisation_id = :organisationId
                  AND session.school_id = :schoolId
                """, scope.parameters().addValue("sessionId", request.academicSessionId())
                .addValue("subjectId", request.subjectId()), Integer.class);
        if (count != 1) throw badRequest("ACADEMIC_RESOURCE_SCOPE_INVALID", "Choose a session and subject from this school.");
        enumValue(request.resourceType(), RESOURCE_TYPES, "resource type");
        enumValue(request.status(), ACTIVE_STATUSES, "resource status");
        validateHttpsUrl(request.sourceUrl());
    }

    private void validateOfferingReferences(Scope scope, UUID sectionId, UUID subjectId) {
        int count = jdbc.queryForObject("""
                SELECT count(*) FROM class_sections section
                JOIN school_subjects subject ON subject.organisation_id = section.organisation_id
                  AND subject.school_id = section.school_id AND subject.id = :subjectId
                WHERE section.id = :sectionId AND section.organisation_id = :organisationId
                  AND section.school_id = :schoolId
                """, scope.parameters().addValue("sectionId", sectionId).addValue("subjectId", subjectId), Integer.class);
        if (count != 1) throw badRequest("OFFERING_SCOPE_INVALID", "Choose a section and subject from this school.");
    }

    private void validateAllocation(Scope scope, AllocationRequest request, UUID allocationId) {
        if (request.endsOn() != null && request.endsOn().isBefore(request.startsOn())) {
            throw badRequest("INVALID_ALLOCATION_DATES", "The allocation end date must be on or after its start date.");
        }
        List<SessionDates> sessions = jdbc.query("""
                SELECT session.starts_on, session.ends_on FROM section_subject_offerings offering
                JOIN class_sections section ON section.id = offering.class_section_id
                  AND section.organisation_id = offering.organisation_id AND section.school_id = offering.school_id
                JOIN academic_sessions session ON session.id = section.academic_session_id
                  AND session.organisation_id = section.organisation_id AND session.school_id = section.school_id
                JOIN memberships teacher ON teacher.id = :teacherId
                  AND teacher.organisation_id = offering.organisation_id AND teacher.school_id = offering.school_id
                JOIN membership_roles membership_role ON membership_role.membership_id = teacher.id
                JOIN roles role ON role.id = membership_role.role_id AND role.code = 'teacher'
                  AND (role.organisation_id IS NULL OR role.organisation_id = teacher.organisation_id)
                JOIN staff_profiles staff ON staff.user_account_id = teacher.user_id
                  AND staff.organisation_id = teacher.organisation_id
                  AND staff.school_id = teacher.school_id AND staff.status = 'ACTIVE'
                JOIN staff_assignments employment ON employment.staff_profile_id = staff.id
                  AND employment.organisation_id = staff.organisation_id
                  AND employment.school_id = staff.school_id AND employment.status = 'ACTIVE'
                  AND employment.starts_on <= :allocationStart
                  AND (employment.ends_on IS NULL OR employment.ends_on >= COALESCE(:allocationEnd, :allocationStart))
                JOIN staff_designations designation ON designation.id = employment.staff_designation_id
                  AND designation.organisation_id = employment.organisation_id
                  AND designation.school_id = employment.school_id
                  AND designation.status = 'ACTIVE' AND designation.teaching_role
                WHERE offering.id = :offeringId AND offering.organisation_id = :organisationId
                  AND offering.school_id = :schoolId AND teacher.status = 'ACTIVE'
                """, scope.parameters().addValue("offeringId", request.offeringId())
                .addValue("teacherId", request.teacherMembershipId()).addValue("allocationStart", request.startsOn())
                .addValue("allocationEnd", request.endsOn()),
                (result, row) -> new SessionDates(
                        result.getObject("starts_on", LocalDate.class), result.getObject("ends_on", LocalDate.class)));
        if (sessions.isEmpty()) throw badRequest("TEACHING_ALLOCATION_SCOPE_INVALID", "Choose an active teacher and class subject from this school.");
        SessionDates session = sessions.getFirst();
        if (request.startsOn().isBefore(session.startsOn()) || request.startsOn().isAfter(session.endsOn())
                || (request.endsOn() != null && request.endsOn().isAfter(session.endsOn()))) {
            throw badRequest("ALLOCATION_OUTSIDE_SESSION", "Teaching allocation dates must remain inside the section's academic session.");
        }
        enumValue(request.allocationRole(), ALLOCATION_ROLES, "allocation role");
        enumValue(request.status(), ACTIVE_STATUSES, "allocation status");
    }

    private void validatePeriod(Scope scope, PeriodRequest request, UUID periodId) {
        if (!request.endsAt().isAfter(request.startsAt())) {
            throw badRequest("INVALID_PERIOD_TIME", "The period end time must be after its start time.");
        }
        int contextCount = jdbc.queryForObject("""
                SELECT count(*) FROM campuses campus
                JOIN academic_sessions session ON session.organisation_id = campus.organisation_id
                  AND session.school_id = campus.school_id AND session.id = :sessionId
                WHERE campus.id = :campusId AND campus.organisation_id = :organisationId
                  AND campus.school_id = :schoolId
                """, scope.parameters().addValue("campusId", request.campusId())
                .addValue("sessionId", request.academicSessionId()), Integer.class);
        if (contextCount != 1) throw badRequest("PERIOD_SCOPE_INVALID", "Choose a campus and session from this school.");
        enumValue(request.kind(), PERIOD_KINDS, "period kind");
        enumValue(request.status(), ACTIVE_STATUSES, "period status");
        if ("ACTIVE".equals(upper(request.status()))) {
            int overlap = jdbc.queryForObject("""
                    SELECT count(*) FROM bell_schedule_periods
                    WHERE organisation_id = :organisationId AND school_id = :schoolId
                      AND campus_id = :campusId AND academic_session_id = :sessionId
                      AND day_of_week = :dayOfWeek AND status = 'ACTIVE'
                      AND starts_at < :endsAt AND ends_at > :startsAt
                      AND (CAST(:periodId AS uuid) IS NULL OR id <> :periodId)
                    """, scope.parameters().addValue("campusId", request.campusId())
                    .addValue("sessionId", request.academicSessionId()).addValue("dayOfWeek", request.dayOfWeek())
                    .addValue("startsAt", request.startsAt()).addValue("endsAt", request.endsAt())
                    .addValue("periodId", periodId), Integer.class);
            if (overlap > 0) throw conflict("PERIOD_TIME_OVERLAP", "This period overlaps another active period on the same day.");
        }
    }

    private void validatePlanScope(Scope scope, UUID campusId, UUID sessionId, UUID termId) {
        int count = jdbc.queryForObject("""
                SELECT count(*) FROM campuses campus
                JOIN academic_sessions session ON session.organisation_id = campus.organisation_id
                  AND session.school_id = campus.school_id AND session.id = :sessionId
                WHERE campus.id = :campusId AND campus.organisation_id = :organisationId
                  AND campus.school_id = :schoolId
                  AND (CAST(:termId AS uuid) IS NULL OR EXISTS (
                    SELECT 1 FROM academic_terms term WHERE term.id = :termId
                      AND term.academic_session_id = session.id
                      AND term.organisation_id = session.organisation_id AND term.school_id = session.school_id
                  ))
                """, scope.parameters().addValue("campusId", campusId).addValue("sessionId", sessionId)
                .addValue("termId", termId), Integer.class);
        if (count != 1) throw badRequest("TIMETABLE_SCOPE_INVALID", "Choose a campus, session, and matching term from this school.");
    }

    private void validateEntry(Scope scope, StoredPlan plan, TimetableEntryRequest request, UUID entryId) {
        int valid = jdbc.queryForObject("""
                SELECT count(*) FROM class_sections section
                JOIN section_subject_offerings offering ON offering.id = :offeringId
                  AND offering.class_section_id = section.id
                  AND offering.organisation_id = section.organisation_id AND offering.school_id = section.school_id
                JOIN bell_schedule_periods period ON period.id = :periodId
                  AND period.organisation_id = section.organisation_id AND period.school_id = section.school_id
                JOIN teaching_allocations allocation ON allocation.section_subject_offering_id = offering.id
                  AND allocation.teacher_membership_id = :teacherId
                  AND allocation.organisation_id = offering.organisation_id AND allocation.school_id = offering.school_id
                  AND allocation.status = 'ACTIVE'
                  AND allocation.starts_on <= :coverageStart
                  AND (allocation.ends_on IS NULL OR allocation.ends_on >= :coverageEnd)
                JOIN memberships teacher ON teacher.id = allocation.teacher_membership_id
                  AND teacher.organisation_id = allocation.organisation_id AND teacher.school_id = allocation.school_id
                JOIN membership_roles membership_role ON membership_role.membership_id = teacher.id
                JOIN roles role ON role.id = membership_role.role_id AND role.code = 'teacher'
                JOIN staff_profiles staff ON staff.user_account_id = teacher.user_id
                  AND staff.organisation_id = teacher.organisation_id
                  AND staff.school_id = teacher.school_id AND staff.status = 'ACTIVE'
                JOIN staff_assignments employment ON employment.staff_profile_id = staff.id
                  AND employment.organisation_id = staff.organisation_id
                  AND employment.school_id = staff.school_id AND employment.status = 'ACTIVE'
                  AND employment.starts_on <= :coverageStart
                  AND (employment.ends_on IS NULL OR employment.ends_on >= :coverageEnd)
                JOIN staff_designations designation ON designation.id = employment.staff_designation_id
                  AND designation.organisation_id = employment.organisation_id
                  AND designation.school_id = employment.school_id
                  AND designation.status = 'ACTIVE' AND designation.teaching_role
                WHERE section.id = :sectionId AND section.organisation_id = :organisationId
                  AND section.school_id = :schoolId AND section.campus_id = :campusId
                  AND section.academic_session_id = :sessionId AND section.status = 'ACTIVE'
                  AND offering.status = 'ACTIVE' AND period.campus_id = :campusId
                  AND period.academic_session_id = :sessionId AND period.status = 'ACTIVE'
                  AND period.period_kind = 'TEACHING' AND teacher.status = 'ACTIVE'
                """, scope.parameters().addValue("sectionId", request.sectionId())
                .addValue("offeringId", request.offeringId()).addValue("periodId", request.periodId())
                .addValue("teacherId", request.teacherMembershipId()).addValue("campusId", plan.campusId())
                .addValue("sessionId", plan.sessionId()).addValue("coverageStart", plan.coverageStart())
                .addValue("coverageEnd", plan.coverageEnd()), Integer.class);
        if (valid < 1) throw badRequest("TIMETABLE_ENTRY_INVALID", "Choose an active class subject, teaching period, and allocated teacher from this timetable context.");
        int overlap = jdbc.queryForObject("""
                SELECT count(*) FROM timetable_entries entry
                JOIN bell_schedule_periods existing_period ON existing_period.id = entry.bell_schedule_period_id
                JOIN bell_schedule_periods requested_period ON requested_period.id = :periodId
                WHERE entry.timetable_plan_id = :planId AND entry.organisation_id = :organisationId
                  AND entry.school_id = :schoolId AND existing_period.day_of_week = requested_period.day_of_week
                  AND existing_period.starts_at < requested_period.ends_at
                  AND existing_period.ends_at > requested_period.starts_at
                  AND (entry.class_section_id = :sectionId OR entry.teacher_membership_id = :teacherId)
                  AND (CAST(:entryId AS uuid) IS NULL OR entry.id <> :entryId)
                """, scope.parameters().addValue("periodId", request.periodId()).addValue("planId", plan.id())
                .addValue("sectionId", request.sectionId()).addValue("teacherId", request.teacherMembershipId())
                .addValue("entryId", entryId), Integer.class);
        if (overlap > 0) throw conflict("TIMETABLE_SLOT_CONFLICT", "The class or teacher already has an overlapping timetable entry.");
    }

    private PublicationCheck validatePublication(Scope scope, StoredPlan plan) {
        MapSqlParameterSource parameters = scope.parameters().addValue("planId", plan.id())
                .addValue("campusId", plan.campusId()).addValue("sessionId", plan.sessionId());
        int entries = jdbc.queryForObject("SELECT count(*) FROM timetable_entries WHERE timetable_plan_id = :planId", parameters, Integer.class);
        int invalid = jdbc.queryForObject("""
                SELECT count(*) FROM timetable_entries entry
                LEFT JOIN class_sections section ON section.id = entry.class_section_id
                  AND section.organisation_id = entry.organisation_id AND section.school_id = entry.school_id
                LEFT JOIN section_subject_offerings offering ON offering.id = entry.section_subject_offering_id
                  AND offering.class_section_id = entry.class_section_id
                  AND offering.organisation_id = entry.organisation_id AND offering.school_id = entry.school_id
                LEFT JOIN bell_schedule_periods period ON period.id = entry.bell_schedule_period_id
                  AND period.organisation_id = entry.organisation_id AND period.school_id = entry.school_id
                LEFT JOIN memberships teacher ON teacher.id = entry.teacher_membership_id
                  AND teacher.organisation_id = entry.organisation_id AND teacher.school_id = entry.school_id
                WHERE entry.timetable_plan_id = :planId AND (
                    section.id IS NULL OR section.status <> 'ACTIVE' OR section.campus_id <> :campusId
                    OR section.academic_session_id <> :sessionId OR offering.id IS NULL OR offering.status <> 'ACTIVE'
                    OR period.id IS NULL OR period.status <> 'ACTIVE' OR period.period_kind <> 'TEACHING'
                    OR period.campus_id <> :campusId OR period.academic_session_id <> :sessionId
                    OR teacher.id IS NULL OR teacher.status <> 'ACTIVE'
                    OR NOT EXISTS (
                        SELECT 1 FROM teaching_allocations allocation
                        WHERE allocation.section_subject_offering_id = entry.section_subject_offering_id
                          AND allocation.teacher_membership_id = entry.teacher_membership_id
                          AND allocation.organisation_id = entry.organisation_id
                          AND allocation.school_id = entry.school_id AND allocation.status = 'ACTIVE'
                          AND allocation.starts_on <= :coverageStart
                          AND (allocation.ends_on IS NULL OR allocation.ends_on >= :coverageEnd)
                    )
                    OR NOT EXISTS (
                        SELECT 1 FROM membership_roles membership_role
                        JOIN roles role ON role.id = membership_role.role_id AND role.code = 'teacher'
                          AND (role.organisation_id IS NULL OR role.organisation_id = entry.organisation_id)
                        WHERE membership_role.membership_id = entry.teacher_membership_id
                    )
                    OR NOT EXISTS (
                        SELECT 1 FROM staff_profiles staff
                        JOIN staff_assignments employment ON employment.staff_profile_id = staff.id
                          AND employment.organisation_id = staff.organisation_id
                          AND employment.school_id = staff.school_id AND employment.status = 'ACTIVE'
                          AND employment.starts_on <= :coverageStart
                          AND (employment.ends_on IS NULL OR employment.ends_on >= :coverageEnd)
                        JOIN staff_designations designation ON designation.id = employment.staff_designation_id
                          AND designation.organisation_id = employment.organisation_id
                          AND designation.school_id = employment.school_id
                          AND designation.status = 'ACTIVE' AND designation.teaching_role
                        WHERE staff.user_account_id = teacher.user_id
                          AND staff.organisation_id = entry.organisation_id
                          AND staff.school_id = entry.school_id AND staff.status = 'ACTIVE'
                    )
                )
                """, parameters.addValue("coverageStart", plan.coverageStart())
                .addValue("coverageEnd", plan.coverageEnd()), Integer.class);
        int overlap = jdbc.queryForObject("""
                SELECT count(*) FROM timetable_entries first_entry
                JOIN timetable_entries second_entry ON second_entry.timetable_plan_id = first_entry.timetable_plan_id
                  AND second_entry.id > first_entry.id
                  AND (second_entry.class_section_id = first_entry.class_section_id
                    OR second_entry.teacher_membership_id = first_entry.teacher_membership_id)
                JOIN bell_schedule_periods first_period ON first_period.id = first_entry.bell_schedule_period_id
                JOIN bell_schedule_periods second_period ON second_period.id = second_entry.bell_schedule_period_id
                WHERE first_entry.timetable_plan_id = :planId
                  AND first_period.day_of_week = second_period.day_of_week
                  AND first_period.starts_at < second_period.ends_at
                  AND first_period.ends_at > second_period.starts_at
                """, parameters, Integer.class);
        return new PublicationCheck(entries, invalid, overlap);
    }

    private List<String> workloadWarnings(Scope scope, UUID planId) {
        return jdbc.query("""
                SELECT section.display_name, subject.display_name,
                       offering.weekly_target_periods, count(entry.id) AS scheduled
                FROM section_subject_offerings offering
                JOIN class_sections section ON section.id = offering.class_section_id
                JOIN school_subjects subject ON subject.id = offering.subject_id
                LEFT JOIN timetable_entries entry ON entry.section_subject_offering_id = offering.id
                  AND entry.timetable_plan_id = :planId
                WHERE offering.organisation_id = :organisationId AND offering.school_id = :schoolId
                  AND offering.status = 'ACTIVE'
                GROUP BY offering.id, section.display_name, subject.display_name
                HAVING count(entry.id) <> offering.weekly_target_periods
                ORDER BY section.display_name, subject.display_name
                """, scope.parameters().addValue("planId", planId), (result, row) ->
                result.getString("display_name") + " · " + result.getString(2) + ": target "
                        + result.getInt("weekly_target_periods") + ", scheduled " + result.getInt("scheduled"));
    }

    private StoredPlan lockDraft(Scope scope, UUID planId, long expectedVersion) {
        List<StoredPlan> plans = jdbc.query("""
                SELECT plan.id, plan.campus_id, plan.academic_session_id, plan.status, plan.row_version,
                       COALESCE(term.starts_on, session.starts_on) AS coverage_start,
                       COALESCE(term.ends_on, session.ends_on) AS coverage_end
                FROM timetable_plans plan
                JOIN academic_sessions session ON session.id = plan.academic_session_id
                  AND session.organisation_id = plan.organisation_id AND session.school_id = plan.school_id
                LEFT JOIN academic_terms term ON term.id = plan.academic_term_id
                  AND term.organisation_id = plan.organisation_id AND term.school_id = plan.school_id
                WHERE plan.id = :planId AND plan.organisation_id = :organisationId
                  AND plan.school_id = :schoolId
                FOR UPDATE OF plan
                """, scope.parameters().addValue("planId", planId), (result, row) -> new StoredPlan(
                result.getObject("id", UUID.class), result.getObject("campus_id", UUID.class),
                result.getObject("academic_session_id", UUID.class), result.getString("status"),
                result.getLong("row_version"), result.getObject("coverage_start", LocalDate.class),
                result.getObject("coverage_end", LocalDate.class)));
        if (plans.isEmpty()) throw notFound("TIMETABLE_NOT_FOUND", "The timetable plan was not found.");
        StoredPlan plan = plans.getFirst();
        if (!"DRAFT".equals(plan.status())) throw conflict("TIMETABLE_IMMUTABLE", "Published timetables cannot be edited. Create a new draft instead.");
        if (plan.rowVersion() != expectedVersion) throw conflict("STALE_TIMETABLE", "The timetable changed. Refresh before continuing.");
        return plan;
    }

    private long touchPlan(Scope scope, UUID planId, long version) {
        int changed = jdbc.update("""
                UPDATE timetable_plans SET updated_at = :now, row_version = row_version + 1
                WHERE id = :planId AND organisation_id = :organisationId AND school_id = :schoolId
                  AND status = 'DRAFT' AND row_version = :expectedVersion
                """, scope.parameters().addValue("planId", planId).addValue("expectedVersion", version)
                .addValue("now", Instant.now(clock)));
        requireChanged(changed, "STALE_TIMETABLE", "The timetable changed. Refresh before continuing.");
        return version + 1;
    }

    private int copyEntries(Scope scope, UUID publishedId, UUID draftId) {
        List<EntryCopy> entries = jdbc.query("""
                SELECT class_section_id, section_subject_offering_id, bell_schedule_period_id,
                       teacher_membership_id, subject_name_snapshot, subject_short_name_snapshot,
                       period_name_snapshot, day_of_week_snapshot, sequence_number_snapshot,
                       starts_at_snapshot, ends_at_snapshot, room_label, note
                FROM timetable_entries
                WHERE timetable_plan_id = :publishedId AND organisation_id = :organisationId
                  AND school_id = :schoolId
                """, scope.parameters().addValue("publishedId", publishedId), (result, row) -> new EntryCopy(
                result.getObject("class_section_id", UUID.class),
                result.getObject("section_subject_offering_id", UUID.class),
                result.getObject("bell_schedule_period_id", UUID.class),
                result.getObject("teacher_membership_id", UUID.class),
                result.getString("subject_name_snapshot"), result.getString("subject_short_name_snapshot"),
                result.getString("period_name_snapshot"), result.getInt("day_of_week_snapshot"),
                result.getInt("sequence_number_snapshot"), result.getObject("starts_at_snapshot", LocalTime.class),
                result.getObject("ends_at_snapshot", LocalTime.class),
                result.getString("room_label"), result.getString("note")));
        Instant now = Instant.now(clock);
        for (EntryCopy entry : entries) {
            jdbc.update("""
                    INSERT INTO timetable_entries (
                        id, organisation_id, school_id, timetable_plan_id, class_section_id,
                        section_subject_offering_id, bell_schedule_period_id, teacher_membership_id,
                        subject_name_snapshot, subject_short_name_snapshot, period_name_snapshot,
                        day_of_week_snapshot, sequence_number_snapshot, starts_at_snapshot, ends_at_snapshot,
                        room_label, note, created_at, updated_at, row_version
                    ) VALUES (:id, :organisationId, :schoolId, :draftId, :sectionId,
                        :offeringId, :periodId, :teacherId, :subjectName, :subjectShortName,
                        :periodName, :dayOfWeek, :sequenceNumber, :startsAt, :endsAt,
                        :roomLabel, :note, :now, :now, 0)
                    """, scope.parameters().addValue("id", UUID.randomUUID()).addValue("draftId", draftId)
                    .addValue("sectionId", entry.sectionId()).addValue("offeringId", entry.offeringId())
                    .addValue("periodId", entry.periodId()).addValue("teacherId", entry.teacherId())
                    .addValue("subjectName", entry.subjectName()).addValue("subjectShortName", entry.subjectShortName())
                    .addValue("periodName", entry.periodName()).addValue("dayOfWeek", entry.dayOfWeek())
                    .addValue("sequenceNumber", entry.sequenceNumber()).addValue("startsAt", entry.startsAt())
                    .addValue("endsAt", entry.endsAt())
                    .addValue("roomLabel", entry.roomLabel()).addValue("note", entry.note()).addValue("now", now));
        }
        return entries.size();
    }

    private StoredCurriculumPublication lockCurriculumDraft(
            Scope scope, UUID publicationId, long expectedVersion) {
        List<StoredCurriculumPublication> publications = jdbc.query("""
                SELECT id, academic_session_id, grade_code, publication_version, status, row_version
                FROM curriculum_publications
                WHERE id = :publicationId AND organisation_id = :organisationId
                  AND school_id = :schoolId
                FOR UPDATE
                """, scope.parameters().addValue("publicationId", publicationId), (result, row) ->
                new StoredCurriculumPublication(
                        result.getObject("id", UUID.class),
                        result.getObject("academic_session_id", UUID.class),
                        result.getString("grade_code"), result.getInt("publication_version"),
                        result.getString("status"), result.getLong("row_version")));
        if (publications.isEmpty()) {
            throw notFound("CURRICULUM_PUBLICATION_NOT_FOUND", "The curriculum publication was not found.");
        }
        StoredCurriculumPublication publication = publications.getFirst();
        if (!"DRAFT".equals(publication.status())) {
            throw conflict("CURRICULUM_IMMUTABLE", "Published curriculum cannot be edited. Create a superseding draft instead.");
        }
        if (publication.rowVersion() != expectedVersion) {
            throw conflict("STALE_CURRICULUM", "The curriculum draft changed. Refresh before continuing.");
        }
        return publication;
    }

    private Scope requireScope(AuthenticatedUser user, UUID requestedSchoolId, String capability) {
        UUID organisationId = user.activeOrganisationId();
        if (organisationId == null) throw conflict("ORGANISATION_SELECTION_REQUIRED", "Select a school organisation before opening academics.");
        List<Scope> scopes = jdbc.query("""
                SELECT school.id AS school_id, school.display_name AS school_name, membership.id AS membership_id
                FROM memberships membership
                JOIN schools school ON school.id = membership.school_id
                  AND school.organisation_id = membership.organisation_id
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
                ORDER BY school.display_name, membership.created_at, membership.id
                LIMIT 1
                """, new MapSqlParameterSource().addValue("userId", user.userId())
                .addValue("organisationId", organisationId).addValue("membershipIds", user.membershipIds())
                .addValue("requestedSchoolId", requestedSchoolId).addValue("capability", capability),
                (result, row) -> new Scope(organisationId, result.getObject("school_id", UUID.class),
                        result.getString("school_name"), result.getObject("membership_id", UUID.class)));
        if (scopes.isEmpty()) throw new ApiException(HttpStatus.FORBIDDEN, "ACADEMIC_SCOPE_DENIED", "Academic access is not available for the selected school.");
        return scopes.getFirst();
    }

    private MapSqlParameterSource subjectParameters(Scope scope, UUID id, SubjectRequest request, Long version) {
        return scope.parameters().addValue("id", id).addValue("code", upper(request.code()))
                .addValue("name", required(request.name(), "subject name")).addValue("shortName", clean(request.shortName()))
                .addValue("category", enumValue(request.category(), SUBJECT_CATEGORIES, "subject category"))
                .addValue("languageCode", lower(request.languageCode())).addValue("boardCode", upperNullable(request.boardSubjectCode()))
                .addValue("status", enumValue(request.status(), ACTIVE_STATUSES, "subject status"))
                .addValue("expectedVersion", version).addValue("now", Instant.now(clock));
    }

    private MapSqlParameterSource resourceParameters(Scope scope, UUID id, ResourceRequest request, Long version) {
        return scope.parameters().addValue("id", id).addValue("sessionId", request.academicSessionId())
                .addValue("subjectId", request.subjectId()).addValue("gradeCode", upper(request.gradeCode()))
                .addValue("resourceType", enumValue(request.resourceType(), RESOURCE_TYPES, "resource type"))
                .addValue("title", required(request.title(), "resource title")).addValue("description", clean(request.description()))
                .addValue("author", clean(request.author())).addValue("publisher", clean(request.publisher()))
                .addValue("edition", clean(request.edition())).addValue("isbn", clean(request.isbn()))
                .addValue("sourceUrl", clean(request.sourceUrl())).addValue("displayOrder", request.displayOrder())
                .addValue("status", enumValue(request.status(), ACTIVE_STATUSES, "resource status"))
                .addValue("expectedVersion", version).addValue("now", Instant.now(clock));
    }

    private MapSqlParameterSource offeringParameters(Scope scope, UUID id, OfferingRequest request, Long version) {
        return scope.parameters().addValue("id", id).addValue("sectionId", request.sectionId())
                .addValue("subjectId", request.subjectId()).addValue("target", request.weeklyTargetPeriods())
                .addValue("status", enumValue(request.status(), ACTIVE_STATUSES, "offering status"))
                .addValue("expectedVersion", version).addValue("now", Instant.now(clock));
    }

    private MapSqlParameterSource allocationParameters(Scope scope, UUID id, AllocationRequest request, Long version) {
        return scope.parameters().addValue("id", id).addValue("offeringId", request.offeringId())
                .addValue("teacherId", request.teacherMembershipId())
                .addValue("role", enumValue(request.allocationRole(), ALLOCATION_ROLES, "allocation role"))
                .addValue("startsOn", request.startsOn()).addValue("endsOn", request.endsOn())
                .addValue("status", enumValue(request.status(), ACTIVE_STATUSES, "allocation status"))
                .addValue("expectedVersion", version).addValue("now", Instant.now(clock));
    }

    private MapSqlParameterSource periodParameters(Scope scope, UUID id, PeriodRequest request, Long version) {
        return scope.parameters().addValue("id", id).addValue("campusId", request.campusId())
                .addValue("sessionId", request.academicSessionId()).addValue("dayOfWeek", request.dayOfWeek())
                .addValue("sequence", request.sequenceNumber()).addValue("code", upper(request.code()))
                .addValue("name", required(request.name(), "period name")).addValue("startsAt", request.startsAt())
                .addValue("endsAt", request.endsAt()).addValue("kind", enumValue(request.kind(), PERIOD_KINDS, "period kind"))
                .addValue("status", enumValue(request.status(), ACTIVE_STATUSES, "period status"))
                .addValue("expectedVersion", version).addValue("now", Instant.now(clock));
    }

    private void audit(AuthenticatedUser user, Scope scope, String action, String type, UUID id, String reason, Map<String, ?> metadata) {
        audit.recordScoped(user, scope.actorMembershipId(), scope.schoolId(), action, type, id.toString(),
                AuditOutcome.SUCCEEDED, clean(reason), metadata);
    }

    private static TermItem mapTerm(ResultSet result, int row) throws SQLException {
        return new TermItem(result.getObject("id", UUID.class), result.getObject("academic_session_id", UUID.class),
                result.getString("code"), result.getString("display_name"), result.getObject("starts_on", LocalDate.class),
                result.getObject("ends_on", LocalDate.class), result.getString("status"), result.getLong("row_version"));
    }

    private static SubjectItem mapSubject(ResultSet result, int row) throws SQLException {
        return new SubjectItem(result.getObject("id", UUID.class), result.getString("code"), result.getString("display_name"),
                result.getString("short_name"), result.getString("subject_category"), result.getString("language_code"),
                result.getString("board_subject_code"), result.getString("status"), result.getLong("row_version"));
    }

    private static ResourceItem mapResource(ResultSet result, int row) throws SQLException {
        return new ResourceItem(result.getObject("id", UUID.class), result.getObject("academic_session_id", UUID.class),
                result.getObject("subject_id", UUID.class), result.getString("grade_code"), result.getString("resource_type"),
                result.getString("title"), result.getString("description"), result.getString("author"), result.getString("publisher"),
                result.getString("edition"), result.getString("isbn"), result.getString("source_url"), result.getInt("display_order"),
                result.getString("status"), result.getLong("row_version"));
    }

    private static CurriculumPublicationItem mapCurriculumPublication(ResultSet result, int row)
            throws SQLException {
        return new CurriculumPublicationItem(
                result.getObject("id", UUID.class), result.getObject("academic_session_id", UUID.class),
                result.getString("grade_code"), result.getInt("publication_version"),
                result.getString("status"), result.getObject("supersedes_publication_id", UUID.class),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("published_at") == null ? null : result.getTimestamp("published_at").toInstant(),
                result.getInt("item_count"), result.getLong("row_version"));
    }

    private static CurriculumSnapshotItem mapCurriculumItem(ResultSet result, int row)
            throws SQLException {
        return new CurriculumSnapshotItem(
                result.getObject("id", UUID.class),
                result.getObject("curriculum_publication_id", UUID.class),
                result.getObject("source_resource_id", UUID.class), result.getString("subject_code_snapshot"),
                result.getString("subject_name_snapshot"), result.getString("subject_short_name_snapshot"),
                result.getString("resource_type_snapshot"), result.getString("title_snapshot"),
                result.getString("description_snapshot"), result.getString("author_snapshot"),
                result.getString("publisher_snapshot"), result.getString("edition_snapshot"),
                result.getString("isbn_snapshot"), result.getString("source_url_snapshot"),
                result.getInt("display_order_snapshot"));
    }

    private static CurriculumSource mapCurriculumSource(ResultSet result, int row) throws SQLException {
        return new CurriculumSource(
                result.getObject("resource_id", UUID.class), result.getString("subject_code"),
                result.getString("subject_name"), result.getString("subject_short_name"),
                result.getString("resource_type"), result.getString("title"),
                result.getString("description"), result.getString("author"),
                result.getString("publisher"), result.getString("edition"),
                result.getString("isbn"), result.getString("source_url"), result.getInt("display_order"));
    }

    private static OfferingItem mapOffering(ResultSet result, int row) throws SQLException {
        return new OfferingItem(result.getObject("id", UUID.class), result.getObject("class_section_id", UUID.class),
                result.getObject("subject_id", UUID.class), result.getInt("weekly_target_periods"),
                result.getString("status"), result.getLong("row_version"));
    }

    private static AllocationItem mapAllocation(ResultSet result, int row) throws SQLException {
        return new AllocationItem(result.getObject("id", UUID.class), result.getObject("section_subject_offering_id", UUID.class),
                result.getObject("teacher_membership_id", UUID.class), result.getString("allocation_role"),
                result.getObject("starts_on", LocalDate.class), result.getObject("ends_on", LocalDate.class),
                result.getString("status"), result.getLong("row_version"));
    }

    private static PeriodItem mapPeriod(ResultSet result, int row) throws SQLException {
        return new PeriodItem(result.getObject("id", UUID.class), result.getObject("campus_id", UUID.class),
                result.getObject("academic_session_id", UUID.class), result.getInt("day_of_week"),
                result.getInt("sequence_number"), result.getString("code"), result.getString("display_name"),
                result.getObject("starts_at", LocalTime.class), result.getObject("ends_at", LocalTime.class),
                result.getString("period_kind"), result.getString("status"), result.getLong("row_version"));
    }

    private static PlanItem mapPlan(ResultSet result, int row) throws SQLException {
        return new PlanItem(result.getObject("id", UUID.class), result.getObject("campus_id", UUID.class),
                result.getObject("academic_session_id", UUID.class), result.getObject("academic_term_id", UUID.class),
                result.getString("plan_name"), result.getString("status"), result.getObject("supersedes_plan_id", UUID.class),
                result.getTimestamp("published_at") == null ? null : result.getTimestamp("published_at").toInstant(),
                result.getLong("row_version"));
    }

    private static EntryItem mapEntry(ResultSet result, int row) throws SQLException {
        return new EntryItem(result.getObject("id", UUID.class), result.getObject("timetable_plan_id", UUID.class),
                result.getObject("class_section_id", UUID.class), result.getObject("section_subject_offering_id", UUID.class),
                result.getObject("bell_schedule_period_id", UUID.class), result.getObject("teacher_membership_id", UUID.class),
                result.getString("room_label"), result.getString("note"), result.getLong("row_version"));
    }

    private static TeachingGroup mapTeachingGroup(ResultSet result, int row) throws SQLException {
        return new TeachingGroup(
                result.getObject("allocation_id", UUID.class),
                result.getObject("offering_id", UUID.class),
                result.getObject("academic_session_id", UUID.class),
                result.getString("academic_session_name"),
                result.getObject("section_id", UUID.class), result.getString("grade_code"),
                result.getString("section_code"), result.getString("section_name"),
                result.getObject("subject_id", UUID.class), result.getString("subject_name"),
                result.getString("subject_short_name"), result.getInt("weekly_target_periods"),
                result.getString("allocation_role"), result.getObject("starts_on", LocalDate.class),
                result.getObject("ends_on", LocalDate.class));
    }

    private static PublishedScheduleEntry mapPublishedScheduleEntry(
            ResultSet result, int row) throws SQLException {
        return new PublishedScheduleEntry(
                result.getObject("entry_id", UUID.class),
                result.getObject("timetable_plan_id", UUID.class),
                result.getString("timetable_name"), result.getTimestamp("published_at").toInstant(),
                result.getObject("campus_id", UUID.class), result.getString("campus_name"),
                result.getObject("academic_session_id", UUID.class),
                result.getString("academic_session_name"),
                result.getObject("academic_term_id", UUID.class),
                result.getString("academic_term_name"),
                result.getObject("section_id", UUID.class), result.getString("grade_code"),
                result.getString("section_code"), result.getString("section_name"),
                result.getInt("day_of_week_snapshot"),
                result.getInt("sequence_number_snapshot"),
                result.getString("period_name_snapshot"),
                result.getObject("starts_at_snapshot", LocalTime.class),
                result.getObject("ends_at_snapshot", LocalTime.class),
                result.getString("subject_name_snapshot"),
                result.getString("subject_short_name_snapshot"),
                result.getString("room_label"), result.getString("note"));
    }

    private static void validateHttpsUrl(String value) {
        String cleaned = clean(value);
        if (cleaned == null) return;
        try {
            URI uri = new URI(cleaned);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) throw new URISyntaxException(cleaned, "HTTPS required");
        } catch (URISyntaxException exception) {
            throw badRequest("INVALID_RESOURCE_URL", "Use a complete HTTPS link for the resource.");
        }
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
        String result = required(value, "value");
        return result.toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static String upperNullable(String value) {
        String result = clean(value);
        return result == null ? null : result.toUpperCase(Locale.ROOT);
    }

    private static String lower(String value) {
        String result = clean(value);
        return result == null ? null : result.toLowerCase(Locale.ROOT);
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

    private record Scope(UUID organisationId, UUID schoolId, String schoolName, UUID actorMembershipId) {
        MapSqlParameterSource parameters() {
            return new MapSqlParameterSource().addValue("organisationId", organisationId)
                    .addValue("schoolId", schoolId).addValue("actorMembershipId", actorMembershipId);
        }
    }

    private record StoredPlan(
            UUID id, UUID campusId, UUID sessionId, String status, long rowVersion,
            LocalDate coverageStart, LocalDate coverageEnd) {}
    private record StoredCurriculumPublication(
            UUID id, UUID sessionId, String gradeCode, int publicationVersion,
            String status, long rowVersion) {}
    private record CurriculumSource(
            UUID resourceId, String subjectCode, String subjectName, String subjectShortName,
            String resourceType, String title, String description, String author, String publisher,
            String edition, String isbn, String sourceUrl, int displayOrder) {}
    private record SessionDates(LocalDate startsOn, LocalDate endsOn) {}
    private record PublicationCheck(int entryCount, int invalidCount, int overlapCount) {}
    private record EntryCopy(
            UUID sectionId, UUID offeringId, UUID periodId, UUID teacherId,
            String subjectName, String subjectShortName, String periodName,
            int dayOfWeek, int sequenceNumber, LocalTime startsAt, LocalTime endsAt,
            String roomLabel, String note) {}

    @FunctionalInterface
    private interface Work<T> {
        T run();
    }
}
