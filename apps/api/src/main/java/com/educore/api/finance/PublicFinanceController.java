package com.educore.api.finance;

import com.educore.api.common.api.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/schools")
public class PublicFinanceController {
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    public PublicFinanceController(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @GetMapping("/{schoolSlug}/fees")
    ResponseEntity<PublicFeeDisclosureResponse> fees(@PathVariable String schoolSlug) {
        List<School> schools = jdbc.query("""
                SELECT id,organisation_id,display_name,currency_code FROM schools
                WHERE slug=:slug AND status='ACTIVE'
                """, new MapSqlParameterSource("slug", schoolSlug), (result, row) -> new School(
                result.getObject("id", UUID.class), result.getObject("organisation_id", UUID.class),
                result.getString("display_name"), result.getString("currency_code")));
        if (schools.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "SCHOOL_NOT_FOUND",
                "The requested school was not found.");
        School school = schools.getFirst();
        var parameters = new MapSqlParameterSource().addValue("organisationId", school.organisationId())
                .addValue("schoolId", school.id());
        List<PublicFeeDisclosureResponse.Plan> plans = jdbc.query("""
                SELECT plan.id,plan.display_name,plan.grade_code,plan.revision_number,session.name AS session_name
                FROM fee_plans plan JOIN academic_sessions session ON session.id=plan.academic_session_id
                WHERE plan.organisation_id=:organisationId AND plan.school_id=:schoolId
                  AND plan.status='ACTIVE' AND plan.public_disclosure=true
                ORDER BY session.starts_on DESC,plan.grade_code NULLS FIRST,plan.display_name,plan.id
                """, parameters, (result, row) -> {
                    UUID planId = result.getObject("id", UUID.class);
                    var lines = jdbc.query("""
                            SELECT head.display_name AS fee_head,item.label,item.due_on,item.amount,item.allow_partial
                            FROM fee_plan_items item JOIN fee_heads head ON head.id=item.fee_head_id
                            WHERE item.organisation_id=:organisationId AND item.school_id=:schoolId
                              AND item.fee_plan_id=:planId ORDER BY item.sequence_number,item.id
                            """, parameters.addValue("planId", planId), (lineResult, lineRow) ->
                            new PublicFeeDisclosureResponse.Line(lineResult.getString("fee_head"),
                                    lineResult.getString("label"), lineResult.getObject("due_on", LocalDate.class),
                                    lineResult.getBigDecimal("amount"), lineResult.getBoolean("allow_partial")));
                    return new PublicFeeDisclosureResponse.Plan(result.getString("session_name"),
                            result.getString("display_name"), result.getString("grade_code"),
                            result.getInt("revision_number"), lines);
                });
        return ResponseEntity.ok().cacheControl(CacheControl.noCache().cachePublic())
                .body(new PublicFeeDisclosureResponse(school.name(), school.currency(), Instant.now(clock), plans));
    }

    private record School(UUID id, UUID organisationId, String name, String currency) {}
}
