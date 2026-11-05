package com.educore.api.family;

import com.educore.api.academics.CurriculumProjectionService;
import com.educore.api.academics.PublishedCurriculumResponse;
import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.schoolcalendar.PublishedSchoolCalendarResponse;
import com.educore.api.schoolcalendar.PublishedSchoolCalendarService;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FamilyService {

    private final NamedParameterJdbcTemplate jdbc;
    private final CurriculumProjectionService curriculum;
    private final PublishedSchoolCalendarService schoolCalendar;

    public FamilyService(
            NamedParameterJdbcTemplate jdbc,
            CurriculumProjectionService curriculum,
            PublishedSchoolCalendarService schoolCalendar) {
        this.jdbc = jdbc;
        this.curriculum = curriculum;
        this.schoolCalendar = schoolCalendar;
    }

    @Transactional(readOnly = true)
    public FamilyChildrenResponse children(AuthenticatedUser user) {
        UUID organisationId = requireOrganisation(user);
        List<FamilyChildSummary> children = jdbc.query("""
                SELECT learner.id, learner.legal_name, learner.preferred_name,
                       school.display_name AS school_name,
                       campus.display_name AS campus_name,
                       session.name AS session_name,
                       enrolment.grade_code, section.display_name AS section_name,
                       placement.roll_number, relationship.relationship_code
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
                JOIN schools school
                  ON school.id = learner.school_id
                 AND school.organisation_id = learner.organisation_id
                JOIN LATERAL (
                    SELECT candidate.*
                    FROM student_enrolments candidate
                    WHERE candidate.learner_id = learner.id
                      AND candidate.organisation_id = learner.organisation_id
                      AND candidate.school_id = learner.school_id
                      AND candidate.status = 'ACTIVE'
                    ORDER BY candidate.starts_on DESC, candidate.id DESC
                    LIMIT 1
                ) enrolment ON true
                JOIN campuses campus
                  ON campus.id = enrolment.campus_id
                 AND campus.organisation_id = enrolment.organisation_id
                 AND campus.school_id = enrolment.school_id
                JOIN academic_sessions session
                  ON session.id = enrolment.academic_session_id
                 AND session.organisation_id = enrolment.organisation_id
                 AND session.school_id = enrolment.school_id
                LEFT JOIN student_section_placements placement
                  ON placement.enrolment_id = enrolment.id
                 AND placement.organisation_id = enrolment.organisation_id
                 AND placement.school_id = enrolment.school_id
                 AND placement.status = 'ACTIVE'
                LEFT JOIN class_sections section
                  ON section.id = placement.class_section_id
                 AND section.organisation_id = placement.organisation_id
                 AND section.school_id = placement.school_id
                WHERE guardian.user_account_id = :actorUserId
                  AND guardian.organisation_id = :organisationId
                  AND guardian.status = 'ACTIVE'
                  AND EXISTS (
                    SELECT 1
                    FROM memberships membership
                    JOIN membership_roles membership_role
                      ON membership_role.membership_id = membership.id
                    JOIN roles role ON role.id = membership_role.role_id
                    JOIN role_capabilities capability
                      ON capability.role_id = membership_role.role_id
                    WHERE membership.user_id = :actorUserId
                      AND membership.id IN (:membershipIds)
                      AND membership.organisation_id = guardian.organisation_id
                       AND membership.school_id = guardian.school_id
                       AND membership.status = 'ACTIVE'
                       AND membership.valid_from <= CURRENT_TIMESTAMP
                       AND (membership.valid_until IS NULL OR membership.valid_until > CURRENT_TIMESTAMP)
                       AND (role.organisation_id IS NULL OR role.organisation_id = membership.organisation_id)
                      AND capability.capability_code = 'portal.guardian'
                  )
                ORDER BY learner.legal_name, learner.id
                """, new MapSqlParameterSource()
                .addValue("actorUserId", user.userId())
                .addValue("membershipIds", user.membershipIds())
                .addValue("organisationId", organisationId), (result, row) -> {
                    UUID learnerId = result.getObject("id", UUID.class);
                    return new FamilyChildSummary(
                            learnerId,
                            reference(learnerId),
                            result.getString("legal_name"),
                            result.getString("preferred_name"),
                            result.getString("school_name"),
                            result.getString("campus_name"),
                            result.getString("session_name"),
                            result.getString("grade_code"),
                            result.getString("section_name"),
                            result.getString("roll_number"),
                            result.getString("relationship_code"));
                });
        return new FamilyChildrenResponse(children);
    }

    @Transactional(readOnly = true)
    public FamilyScheduleResponse schedule(AuthenticatedUser user, UUID learnerId) {
        UUID organisationId = requireOrganisation(user);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("actorUserId", user.userId())
                .addValue("membershipIds", user.membershipIds())
                .addValue("organisationId", organisationId)
                .addValue("learnerId", learnerId);
        List<FamilyScheduleContext> contexts = jdbc.query("""
                SELECT learner.id AS learner_id, learner.school_id, enrolment.campus_id, enrolment.academic_session_id,
                       placement.class_section_id
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
                JOIN LATERAL (
                    SELECT candidate.* FROM student_enrolments candidate
                    WHERE candidate.learner_id = learner.id
                      AND candidate.organisation_id = learner.organisation_id
                      AND candidate.school_id = learner.school_id
                      AND candidate.status = 'ACTIVE'
                    ORDER BY candidate.starts_on DESC, candidate.id DESC LIMIT 1
                ) enrolment ON true
                LEFT JOIN student_section_placements placement
                  ON placement.enrolment_id = enrolment.id
                 AND placement.organisation_id = enrolment.organisation_id
                 AND placement.school_id = enrolment.school_id
                 AND placement.status = 'ACTIVE'
                WHERE guardian.user_account_id = :actorUserId
                  AND guardian.organisation_id = :organisationId
                  AND guardian.status = 'ACTIVE'
                  AND learner.id = :learnerId
                  AND EXISTS (
                    SELECT 1 FROM memberships membership
                    JOIN membership_roles membership_role ON membership_role.membership_id = membership.id
                    JOIN roles role ON role.id = membership_role.role_id
                    JOIN role_capabilities capability ON capability.role_id = membership_role.role_id
                    WHERE membership.user_id = :actorUserId
                      AND membership.id IN (:membershipIds)
                      AND membership.organisation_id = guardian.organisation_id
                      AND membership.school_id = guardian.school_id
                      AND membership.status = 'ACTIVE'
                      AND membership.valid_from <= CURRENT_TIMESTAMP
                      AND (membership.valid_until IS NULL OR membership.valid_until > CURRENT_TIMESTAMP)
                      AND (role.organisation_id IS NULL OR role.organisation_id = membership.organisation_id)
                      AND capability.capability_code = 'portal.guardian'
                  )
                """, parameters, (result, row) -> new FamilyScheduleContext(
                result.getObject("learner_id", UUID.class), result.getObject("school_id", UUID.class),
                result.getObject("campus_id", UUID.class),
                result.getObject("academic_session_id", UUID.class), result.getObject("class_section_id", UUID.class)));
        if (contexts.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FAMILY_CHILD_NOT_FOUND", "This learner is not available to your verified relationship.");
        }
        FamilyScheduleContext context = contexts.getFirst();
        // A verified child can be enrolled before section placement. Do not expose
        // another class's plan metadata while this child's placement is pending.
        if (context.sectionId() == null) {
            return new FamilyScheduleResponse(learnerId, null, null, null, List.of());
        }
        List<FamilyPublishedPlan> plans = jdbc.query("""
                SELECT id, plan_name, published_at
                FROM timetable_plans
                WHERE organisation_id = :organisationId AND school_id = :schoolId AND campus_id = :campusId
                  AND academic_session_id = :sessionId AND status = 'PUBLISHED'
                ORDER BY published_at DESC, id DESC LIMIT 1
                """, parameters.addValue("schoolId", context.schoolId()).addValue("campusId", context.campusId())
                .addValue("sessionId", context.sessionId()),
                (result, row) -> new FamilyPublishedPlan(
                        result.getObject("id", UUID.class), result.getString("plan_name"),
                        result.getTimestamp("published_at").toInstant()));
        if (plans.isEmpty()) return new FamilyScheduleResponse(learnerId, null, null, null, List.of());
        FamilyPublishedPlan plan = plans.getFirst();
        var entries = jdbc.query("""
                SELECT entry.id, entry.day_of_week_snapshot, entry.sequence_number_snapshot,
                       entry.period_name_snapshot, entry.starts_at_snapshot, entry.ends_at_snapshot,
                       entry.subject_name_snapshot, entry.subject_short_name_snapshot, entry.room_label
                FROM timetable_entries entry
                WHERE entry.timetable_plan_id = :planId AND entry.class_section_id = :sectionId
                  AND entry.organisation_id = :organisationId AND entry.school_id = :schoolId
                ORDER BY entry.day_of_week_snapshot, entry.starts_at_snapshot,
                         entry.sequence_number_snapshot, entry.id
                """, parameters.addValue("planId", plan.id()).addValue("sectionId", context.sectionId()),
                (result, row) -> new FamilyScheduleResponse.ScheduleEntry(
                        result.getObject("id", UUID.class), result.getInt("day_of_week_snapshot"),
                        result.getInt("sequence_number_snapshot"), result.getString("period_name_snapshot"),
                        result.getObject("starts_at_snapshot", java.time.LocalTime.class),
                        result.getObject("ends_at_snapshot", java.time.LocalTime.class),
                        result.getString("subject_name_snapshot"), result.getString("subject_short_name_snapshot"),
                        result.getString("room_label")));
        return new FamilyScheduleResponse(learnerId, plan.id(), plan.name(), plan.publishedAt(), entries);
    }

    @Transactional(readOnly = true)
    public PublishedCurriculumResponse curriculum(AuthenticatedUser user, UUID learnerId) {
        UUID organisationId = requireOrganisation(user);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("actorUserId", user.userId())
                .addValue("membershipIds", user.membershipIds())
                .addValue("organisationId", organisationId)
                .addValue("learnerId", learnerId);
        List<FamilyCurriculumContext> contexts = jdbc.query("""
                SELECT learner.id AS learner_id, learner.school_id,
                       enrolment.academic_session_id, session.name AS session_name,
                       enrolment.grade_code
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
                JOIN LATERAL (
                    SELECT candidate.* FROM student_enrolments candidate
                    WHERE candidate.learner_id = learner.id
                      AND candidate.organisation_id = learner.organisation_id
                      AND candidate.school_id = learner.school_id
                      AND candidate.status = 'ACTIVE'
                    ORDER BY candidate.starts_on DESC, candidate.id DESC LIMIT 1
                ) enrolment ON true
                JOIN academic_sessions session
                  ON session.id = enrolment.academic_session_id
                 AND session.organisation_id = enrolment.organisation_id
                 AND session.school_id = enrolment.school_id
                WHERE guardian.user_account_id = :actorUserId
                  AND guardian.organisation_id = :organisationId
                  AND guardian.status = 'ACTIVE' AND learner.id = :learnerId
                  AND EXISTS (
                    SELECT 1 FROM memberships membership
                    JOIN membership_roles membership_role ON membership_role.membership_id = membership.id
                    JOIN roles role ON role.id = membership_role.role_id
                    JOIN role_capabilities capability ON capability.role_id = membership_role.role_id
                    WHERE membership.user_id = :actorUserId
                      AND membership.id IN (:membershipIds)
                      AND membership.organisation_id = guardian.organisation_id
                      AND membership.school_id = guardian.school_id
                      AND membership.status = 'ACTIVE'
                      AND membership.valid_from <= CURRENT_TIMESTAMP
                      AND (membership.valid_until IS NULL OR membership.valid_until > CURRENT_TIMESTAMP)
                      AND (role.organisation_id IS NULL OR role.organisation_id = membership.organisation_id)
                      AND capability.capability_code = 'portal.guardian'
                  )
                """, parameters, (result, row) -> new FamilyCurriculumContext(
                result.getObject("learner_id", UUID.class), result.getObject("school_id", UUID.class),
                result.getObject("academic_session_id", UUID.class), result.getString("session_name"),
                result.getString("grade_code")));
        if (contexts.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "FAMILY_CHILD_NOT_FOUND",
                    "This learner is not available to your verified relationship.");
        }
        FamilyCurriculumContext context = contexts.getFirst();
        return curriculum.published(
                organisationId, context.schoolId(), context.learnerId(), context.sessionId(),
                context.sessionName(), context.gradeCode());
    }

    @Transactional(readOnly = true)
    public PublishedSchoolCalendarResponse calendar(AuthenticatedUser user, UUID learnerId) {
        UUID organisationId = requireOrganisation(user);
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("actorUserId", user.userId())
                .addValue("membershipIds", user.membershipIds())
                .addValue("organisationId", organisationId)
                .addValue("learnerId", learnerId);
        List<FamilyCalendarContext> contexts = jdbc.query("""
                SELECT learner.id AS learner_id,learner.school_id,
                       enrolment.campus_id,enrolment.academic_session_id
                FROM guardians guardian
                JOIN learner_guardian_relationships relationship
                  ON relationship.guardian_id=guardian.id
                 AND relationship.organisation_id=guardian.organisation_id
                 AND relationship.school_id=guardian.school_id
                 AND relationship.portal_access_status='ACTIVE'
                JOIN learners learner
                  ON learner.id=relationship.learner_id
                 AND learner.organisation_id=relationship.organisation_id
                 AND learner.school_id=relationship.school_id
                 AND learner.status='ACTIVE'
                JOIN LATERAL (
                  SELECT candidate.* FROM student_enrolments candidate
                  WHERE candidate.learner_id=learner.id
                    AND candidate.organisation_id=learner.organisation_id
                    AND candidate.school_id=learner.school_id
                    AND candidate.status='ACTIVE'
                  ORDER BY candidate.starts_on DESC,candidate.id DESC LIMIT 1
                ) enrolment ON true
                WHERE guardian.user_account_id=:actorUserId
                  AND guardian.organisation_id=:organisationId
                  AND guardian.status='ACTIVE'
                  AND learner.id=:learnerId
                  AND EXISTS (
                    SELECT 1 FROM memberships membership
                    JOIN membership_roles membership_role
                      ON membership_role.membership_id=membership.id
                    JOIN roles role ON role.id=membership_role.role_id
                    JOIN role_capabilities capability
                      ON capability.role_id=membership_role.role_id
                    WHERE membership.user_id=:actorUserId
                      AND membership.id IN (:membershipIds)
                      AND membership.organisation_id=guardian.organisation_id
                      AND membership.school_id=guardian.school_id
                      AND membership.status='ACTIVE'
                      AND membership.valid_from<=CURRENT_TIMESTAMP
                      AND (membership.valid_until IS NULL
                           OR membership.valid_until>CURRENT_TIMESTAMP)
                      AND (role.organisation_id IS NULL
                           OR role.organisation_id=membership.organisation_id)
                      AND capability.capability_code='portal.guardian'
                  )
                """, parameters, (result, row) -> new FamilyCalendarContext(
                result.getObject("learner_id", UUID.class),
                result.getObject("school_id", UUID.class),
                result.getObject("campus_id", UUID.class),
                result.getObject("academic_session_id", UUID.class)));
        if (contexts.isEmpty()) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "FAMILY_CHILD_NOT_FOUND",
                    "This learner is not available to your verified relationship.");
        }
        FamilyCalendarContext context = contexts.getFirst();
        return schoolCalendar.published(
                organisationId, context.schoolId(), context.campusId(), context.sessionId(),
                context.learnerId());
    }

    private static UUID requireOrganisation(AuthenticatedUser user) {
        if (user.activeOrganisationId() == null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "ORGANISATION_SELECTION_REQUIRED",
                    "Select a school organisation before opening the family portal.");
        }
        return user.activeOrganisationId();
    }

    private static String reference(UUID id) {
        return "STU-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private record FamilyScheduleContext(UUID learnerId, UUID schoolId, UUID campusId, UUID sessionId, UUID sectionId) {}

    private record FamilyCurriculumContext(
            UUID learnerId, UUID schoolId, UUID sessionId, String sessionName, String gradeCode) {}

    private record FamilyCalendarContext(
            UUID learnerId, UUID schoolId, UUID campusId, UUID sessionId) {}

    private record FamilyPublishedPlan(UUID id, String name, java.time.Instant publishedAt) {}
}
