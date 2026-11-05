package com.educore.api.communication;

import com.educore.api.communication.PublicAnnouncementsResponse.PublicAnnouncementItem;
import com.educore.api.common.api.ApiException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/public/schools/{schoolSlug}/announcements")
public class PublicCommunicationController {
    private final NamedParameterJdbcTemplate jdbc;

    public PublicCommunicationController(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    ResponseEntity<PublicAnnouncementsResponse> announcements(@PathVariable String schoolSlug) {
        var parameters = new MapSqlParameterSource().addValue("slug", schoolSlug);
        List<String> schools = jdbc.query("""
                SELECT display_name FROM schools WHERE slug=:slug AND status='ACTIVE'
                """, parameters, (result, row) -> result.getString("display_name"));
        if (schools.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SCHOOL_NOT_FOUND", "The school was not found.");
        }
        String schoolName = schools.getFirst();
        List<PublicAnnouncementItem> items = jdbc.query("""
                SELECT announcement.id,announcement.category,announcement.priority,
                  announcement.language_mode,announcement.title_en,announcement.title_hi,
                  announcement.body_en,announcement.body_hi,announcement.scheduled_for,announcement.expires_at
                FROM communication_announcements announcement
                JOIN schools school ON school.id=announcement.school_id
                WHERE school.slug=:slug AND school.status='ACTIVE'
                  AND announcement.status='APPROVED' AND announcement.audience_kind='PUBLIC'
                  AND announcement.scheduled_for<=CURRENT_TIMESTAMP
                  AND (announcement.expires_at IS NULL OR announcement.expires_at>CURRENT_TIMESTAMP)
                ORDER BY announcement.scheduled_for DESC,announcement.id DESC LIMIT 50
                """, parameters, (result, row) -> new PublicAnnouncementItem(
                result.getObject("id", java.util.UUID.class), result.getString("category"),
                result.getString("priority"), result.getString("language_mode"),
                result.getString("title_en"), result.getString("title_hi"),
                result.getString("body_en"), result.getString("body_hi"),
                result.getTimestamp("scheduled_for").toInstant(), nullable(result, "expires_at")));
        return ResponseEntity.ok().cacheControl(CacheControl.noCache().cachePublic())
                .body(new PublicAnnouncementsResponse(schoolName, items));
    }

    private static Instant nullable(java.sql.ResultSet result, String name) throws java.sql.SQLException {
        var value = result.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }
}
