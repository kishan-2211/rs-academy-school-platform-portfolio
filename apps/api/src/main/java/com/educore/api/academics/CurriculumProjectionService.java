package com.educore.api.academics;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class CurriculumProjectionService {

    private final NamedParameterJdbcTemplate jdbc;

    public CurriculumProjectionService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PublishedCurriculumResponse published(
            UUID organisationId,
            UUID schoolId,
            UUID learnerId,
            UUID academicSessionId,
            String sessionName,
            String gradeCode) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("organisationId", organisationId)
                .addValue("schoolId", schoolId)
                .addValue("sessionId", academicSessionId)
                .addValue("gradeCode", normalizeGrade(gradeCode));
        List<Publication> publications = jdbc.query("""
                SELECT id, publication_version, published_at
                FROM curriculum_publications
                WHERE organisation_id = :organisationId AND school_id = :schoolId
                  AND academic_session_id = :sessionId AND grade_code = :gradeCode
                  AND status = 'PUBLISHED'
                ORDER BY publication_version DESC, published_at DESC, id DESC
                LIMIT 1
                """, parameters, (result, row) -> new Publication(
                result.getObject("id", UUID.class), result.getInt("publication_version"),
                result.getTimestamp("published_at").toInstant()));
        if (publications.isEmpty()) {
            return new PublishedCurriculumResponse(
                    learnerId, null, null, sessionName, gradeCode, null, List.of());
        }
        Publication publication = publications.getFirst();
        List<PublishedCurriculumResponse.Item> items = jdbc.query("""
                SELECT id, subject_code_snapshot, subject_name_snapshot,
                       subject_short_name_snapshot, resource_type_snapshot, title_snapshot,
                       description_snapshot, author_snapshot, publisher_snapshot, edition_snapshot,
                       isbn_snapshot, source_url_snapshot, display_order_snapshot
                FROM curriculum_publication_items
                WHERE organisation_id = :organisationId AND school_id = :schoolId
                  AND curriculum_publication_id = :publicationId
                ORDER BY subject_name_snapshot, display_order_snapshot, title_snapshot, id
                """, parameters.addValue("publicationId", publication.id()), (result, row) ->
                new PublishedCurriculumResponse.Item(
                        result.getObject("id", UUID.class), result.getString("subject_code_snapshot"),
                        result.getString("subject_name_snapshot"), result.getString("subject_short_name_snapshot"),
                        result.getString("resource_type_snapshot"), result.getString("title_snapshot"),
                        result.getString("description_snapshot"), result.getString("author_snapshot"),
                        result.getString("publisher_snapshot"), result.getString("edition_snapshot"),
                        result.getString("isbn_snapshot"), result.getString("source_url_snapshot"),
                        result.getInt("display_order_snapshot")));
        return new PublishedCurriculumResponse(
                learnerId, publication.id(), publication.version(), sessionName, gradeCode,
                publication.publishedAt(), items);
    }

    private static String normalizeGrade(String value) {
        return value.toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private record Publication(UUID id, int version, Instant publishedAt) {}
}
