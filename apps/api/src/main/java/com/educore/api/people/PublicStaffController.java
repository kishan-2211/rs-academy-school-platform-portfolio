package com.educore.api.people;

import com.educore.api.common.api.ApiException;
import com.educore.api.people.PublicStaffResponse.PublicStaffItem;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/public/schools/{schoolSlug}/staff")
public class PublicStaffController {
    private final NamedParameterJdbcTemplate jdbc;

    public PublicStaffController(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping
    ResponseEntity<PublicStaffResponse> staff(@PathVariable String schoolSlug) {
        var parameters = new MapSqlParameterSource().addValue("slug", schoolSlug);
        List<String> schools = jdbc.query("""
                SELECT display_name FROM schools WHERE slug=:slug AND status='ACTIVE'
                """, parameters, (result, row) -> result.getString("display_name"));
        if (schools.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,
                "SCHOOL_NOT_FOUND", "The school was not found.");
        List<PublicStaffItem> items = jdbc.query("""
                SELECT profile.id,profile.public_display_name,profile.designation_snapshot,
                  profile.qualification_summary,profile.short_bio,profile.cbse_staff_category_snapshot,
                  profile.display_order,profile.published_at
                FROM staff_public_profile_revisions profile
                JOIN schools school ON school.id=profile.school_id
                WHERE school.slug=:slug AND school.status='ACTIVE' AND profile.status='PUBLISHED'
                ORDER BY profile.display_order,profile.public_display_name,profile.id
                """, parameters, (result, row) -> new PublicStaffItem(
                result.getObject("id", java.util.UUID.class), result.getString("public_display_name"),
                result.getString("designation_snapshot"), result.getString("qualification_summary"),
                result.getString("short_bio"), result.getString("cbse_staff_category_snapshot"),
                result.getInt("display_order"), result.getTimestamp("published_at").toInstant()));
        return ResponseEntity.ok().cacheControl(CacheControl.noCache().cachePublic())
                .body(new PublicStaffResponse(schools.getFirst(), items));
    }
}
