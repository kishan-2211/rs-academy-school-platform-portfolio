package com.educore.api.family;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import java.util.List;
import java.util.Map;
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
public class FamilyResultService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public FamilyResultService(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public FamilyResultsResponse results(AuthenticatedUser user, UUID learnerId) {
        UUID organisationId = user.activeOrganisationId();
        if (organisationId == null) {
            throw new ApiException(HttpStatus.CONFLICT, "ORGANISATION_SELECTION_REQUIRED", "Select a school organisation before opening family results.");
        }
        List<FamilyResultsResponse.ReportCard> cards = jdbc.query("""
                SELECT card.id AS report_card_id, publication.id AS publication_id,
                       publication.publication_version, card.report_number,
                       session.name AS session_name, term.display_name AS term_name,
                       card.section_name_snapshot, card.overall_percentage,
                       card.overall_grade_code, card.overall_outcome,
                       card.published_at, card.snapshot_sha256, card.report_snapshot::text
                FROM guardians guardian
                JOIN learner_guardian_relationships relationship
                  ON relationship.guardian_id = guardian.id
                 AND relationship.organisation_id = guardian.organisation_id
                 AND relationship.school_id = guardian.school_id
                 AND relationship.learner_id = :learnerId
                 AND relationship.portal_access_status = 'ACTIVE'
                JOIN learner_report_cards card
                  ON card.learner_id = relationship.learner_id
                 AND card.organisation_id = relationship.organisation_id
                 AND card.school_id = relationship.school_id
                JOIN result_publications publication
                  ON publication.id = card.result_publication_id
                 AND publication.organisation_id = card.organisation_id
                 AND publication.school_id = card.school_id
                 AND publication.status = 'PUBLISHED'
                JOIN academic_sessions session ON session.id = publication.academic_session_id
                JOIN academic_terms term ON term.id = publication.academic_term_id
                WHERE guardian.user_account_id = :actorUserId
                  AND guardian.organisation_id = :organisationId
                  AND guardian.status = 'ACTIVE'
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
                      AND capability.capability_code = 'portal.guardian')
                ORDER BY card.published_at DESC, card.id
                """, new MapSqlParameterSource().addValue("learnerId", learnerId)
                .addValue("actorUserId", user.userId()).addValue("membershipIds", user.membershipIds())
                .addValue("organisationId", organisationId), (result, row) -> new FamilyResultsResponse.ReportCard(
                result.getObject("report_card_id", UUID.class), result.getObject("publication_id", UUID.class),
                result.getInt("publication_version"), result.getString("report_number"),
                result.getString("session_name"), result.getString("term_name"),
                result.getString("section_name_snapshot"), result.getBigDecimal("overall_percentage"),
                result.getString("overall_grade_code"), result.getString("overall_outcome"),
                result.getTimestamp("published_at").toInstant(), result.getString("snapshot_sha256"),
                report(result.getString("report_snapshot"))));
        if (cards.isEmpty()) {
            Integer relationship = jdbc.queryForObject("""
                    SELECT count(*) FROM guardians guardian
                    JOIN learner_guardian_relationships relation ON relation.guardian_id = guardian.id
                    WHERE guardian.user_account_id = :actorUserId AND guardian.organisation_id = :organisationId
                      AND relation.learner_id = :learnerId AND relation.portal_access_status = 'ACTIVE'
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
                          AND capability.capability_code = 'portal.guardian')
                    """, new MapSqlParameterSource().addValue("actorUserId", user.userId())
                    .addValue("organisationId", organisationId).addValue("learnerId", learnerId)
                    .addValue("membershipIds", user.membershipIds()), Integer.class);
            if (relationship == null || relationship == 0) {
                throw new ApiException(HttpStatus.NOT_FOUND, "FAMILY_CHILD_NOT_FOUND", "This learner is not available through your active family relationship.");
            }
        }
        return new FamilyResultsResponse(learnerId, cards);
    }

    private Map<String, Object> report(String value) {
        try { return objectMapper.readValue(value, new TypeReference<>() {}); }
        catch (JacksonException exception) { throw new IllegalStateException("Stored report-card evidence is invalid", exception); }
    }
}
