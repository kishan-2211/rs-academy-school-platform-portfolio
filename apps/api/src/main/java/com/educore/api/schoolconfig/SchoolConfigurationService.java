package com.educore.api.schoolconfig;

import static com.educore.api.schoolconfig.SchoolConfigurationController.CampusRequest;
import static com.educore.api.schoolconfig.SchoolConfigurationController.SchoolProfileRequest;
import static com.educore.api.schoolconfig.SchoolConfigurationSnapshot.CampusItem;

import com.educore.api.common.api.ApiException;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import java.sql.Array;
import java.sql.SQLException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Currency;
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
public class SchoolConfigurationService {
    private static final Set<String> CAMPUS_STATUSES = Set.of("ACTIVE", "INACTIVE");

    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final Clock clock;

    public SchoolConfigurationService(
            NamedParameterJdbcTemplate jdbc,
            SecurityAuditService audit,
            Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SchoolConfigurationSnapshot snapshot(AuthenticatedUser user, UUID requestedSchoolId) {
        Scope scope = requireScope(user, requestedSchoolId);
        List<CampusItem> campusItems = campuses(scope);
        List<SchoolConfigurationSnapshot> schools = jdbc.query("""
                SELECT school.id,school.slug,school.legal_name,school.display_name,school.time_zone,
                  school.currency_code,school.status,school.row_version,
                  coalesce(profile.board_code,'') AS board_code,
                  coalesce(profile.grade_range,'') AS grade_range,profile.motto,
                  coalesce(profile.supported_languages,ARRAY[]::varchar(20)[]) AS supported_languages,
                  profile.public_email,profile.public_phone,profile.principal_display_name,
                  coalesce(profile.address_line,'') AS address_line,profile.locality,
                  coalesce(profile.city,'') AS city,coalesce(profile.state_name,'') AS state_name,
                  coalesce(profile.country_code,'') AS country_code,
                  profile.learner_count_min,profile.learner_count_max,
                  profile.teaching_staff_estimate,profile.operations_staff_estimate,
                  profile.verified_at,profile.updated_at
                FROM schools school
                LEFT JOIN school_profiles profile ON profile.school_id=school.id
                  AND profile.organisation_id=school.organisation_id
                WHERE school.id=:schoolId AND school.organisation_id=:organisationId
                """, scope.params(), (result, row) -> new SchoolConfigurationSnapshot(
                result.getObject("id", UUID.class), result.getString("slug"),
                result.getString("legal_name"), result.getString("display_name"),
                result.getString("time_zone"), result.getString("currency_code"),
                result.getString("status"), result.getString("board_code"),
                result.getString("grade_range"), result.getString("motto"),
                strings(result.getArray("supported_languages")), result.getString("public_email"),
                result.getString("public_phone"), result.getString("principal_display_name"),
                result.getString("address_line"), result.getString("locality"),
                result.getString("city"), result.getString("state_name"),
                result.getString("country_code"), (Integer) result.getObject("learner_count_min"),
                (Integer) result.getObject("learner_count_max"),
                (Integer) result.getObject("teaching_staff_estimate"),
                (Integer) result.getObject("operations_staff_estimate"),
                instant(result.getTimestamp("verified_at")), instant(result.getTimestamp("updated_at")),
                result.getLong("row_version"), campusItems));
        if (schools.isEmpty()) {
            throw notFound("SCHOOL_CONFIGURATION_NOT_FOUND", "The school configuration was not found.");
        }
        return schools.getFirst();
    }

    @Transactional
    public SchoolConfigurationMutationResponse updateProfile(
            AuthenticatedUser user,
            SchoolProfileRequest request) {
        Scope scope = requireScope(user, request.schoolId());
        if (request.expectedVersion() == null) {
            throw bad("EXPECTED_VERSION_REQUIRED", "Refresh the school and include its current version.");
        }
        if (!request.accuracyConfirmed()) {
            throw bad("SCHOOL_FACTS_CONFIRMATION_REQUIRED",
                    "Confirm that the school facts are accurate before saving.");
        }
        validateProfile(request);
        Instant now = Instant.now(clock);
        long version = request.expectedVersion();
        MapSqlParameterSource parameters = profileParams(scope, request, now)
                .addValue("version", version);
        int changed = jdbc.update("""
                UPDATE schools SET legal_name=:legalName,display_name=:displayName,
                  time_zone=:timeZone,currency_code=:currencyCode,updated_at=:now,
                  row_version=row_version+1
                WHERE id=:schoolId AND organisation_id=:organisationId AND row_version=:version
                """, parameters);
        changed(changed);
        int profileChanged = safely(() -> jdbc.update("""
                INSERT INTO school_profiles(
                  school_id,organisation_id,board_code,grade_range,motto,supported_languages,
                  public_email,public_phone,principal_display_name,address_line,locality,city,
                  state_name,country_code,learner_count_min,learner_count_max,
                  teaching_staff_estimate,operations_staff_estimate,verified_at,updated_at)
                VALUES(:schoolId,:organisationId,:boardCode,:gradeRange,:motto,:languages,
                  :publicEmail,:publicPhone,:principalName,:addressLine,:locality,:city,
                  :stateName,:countryCode,:learnerMin,:learnerMax,:teachingEstimate,
                  :operationsEstimate,:now,:now)
                ON CONFLICT (school_id) DO UPDATE SET
                  board_code=EXCLUDED.board_code,grade_range=EXCLUDED.grade_range,
                  motto=EXCLUDED.motto,supported_languages=EXCLUDED.supported_languages,
                  public_email=EXCLUDED.public_email,public_phone=EXCLUDED.public_phone,
                  principal_display_name=EXCLUDED.principal_display_name,
                  address_line=EXCLUDED.address_line,locality=EXCLUDED.locality,
                  city=EXCLUDED.city,state_name=EXCLUDED.state_name,
                  country_code=EXCLUDED.country_code,learner_count_min=EXCLUDED.learner_count_min,
                  learner_count_max=EXCLUDED.learner_count_max,
                  teaching_staff_estimate=EXCLUDED.teaching_staff_estimate,
                  operations_staff_estimate=EXCLUDED.operations_staff_estimate,
                  verified_at=EXCLUDED.verified_at,updated_at=EXCLUDED.updated_at
                WHERE school_profiles.organisation_id=EXCLUDED.organisation_id
                """, parameters));
        changed(profileChanged);
        recordAudit(user, scope, "school.configuration.profile.updated", "SCHOOL", scope.schoolId(),
                request.reason(), Map.of("profileVerified", true));
        return new SchoolConfigurationMutationResponse(scope.schoolId(), version + 1);
    }

    @Transactional
    public SchoolConfigurationMutationResponse createCampus(
            AuthenticatedUser user,
            CampusRequest request) {
        Scope scope = requireScope(user, request.schoolId());
        String status = campusStatus(request.status());
        if ("INACTIVE".equals(status) && activeCampusCount(scope, null) == 0) {
            throw conflict("ACTIVE_CAMPUS_REQUIRED",
                    "Keep at least one active campus so school operations remain available.");
        }
        String code = campusCode(request.code());
        Integer duplicates = jdbc.queryForObject("""
                SELECT count(*) FROM campuses
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND code=:code
                """, scope.params().addValue("code", code), Integer.class);
        if (duplicates != null && duplicates > 0) {
            throw conflict("SCHOOL_CONFIGURATION_CONFLICT",
                    "A conflicting school or campus configuration already exists.");
        }
        UUID id = UUID.randomUUID();
        MapSqlParameterSource parameters = campusParams(scope, id, request)
                .addValue("status", status)
                .addValue("now", Instant.now(clock));
        safely(() -> jdbc.update("""
                INSERT INTO campuses(
                  id,organisation_id,school_id,code,display_name,time_zone,status,
                  created_at,updated_at,row_version)
                VALUES(:id,:organisationId,:schoolId,:code,:displayName,:timeZone,:status,
                  :now,:now,0)
                """, parameters));
        recordAudit(user, scope, "school.configuration.campus.created", "CAMPUS", id,
                request.reason(), Map.of("campusCode", code));
        return new SchoolConfigurationMutationResponse(id, 0);
    }

    @Transactional
    public SchoolConfigurationMutationResponse updateCampus(
            AuthenticatedUser user,
            UUID campusId,
            CampusRequest request) {
        Scope scope = requireScope(user, request.schoolId());
        if (request.expectedVersion() == null) {
            throw bad("EXPECTED_VERSION_REQUIRED", "Refresh the campus and include its current version.");
        }
        StoredCampus stored = campus(scope, campusId);
        if (stored.rowVersion() != request.expectedVersion()) {
            throw conflict("STALE_VERSION",
                    "The configuration changed or is no longer editable. Refresh and try again.");
        }
        String status = campusStatus(request.status());
        if ("INACTIVE".equals(status) && "ACTIVE".equals(stored.status())
                && activeCampusCount(scope, campusId) == 0) {
            throw conflict("ACTIVE_CAMPUS_REQUIRED",
                    "Keep at least one active campus so school operations remain available.");
        }
        long version = request.expectedVersion();
        MapSqlParameterSource parameters = campusParams(scope, campusId, request)
                .addValue("status", status)
                .addValue("version", version)
                .addValue("now", Instant.now(clock));
        int changed = safely(() -> jdbc.update("""
                UPDATE campuses SET code=:code,display_name=:displayName,time_zone=:timeZone,
                  status=:status,updated_at=:now,row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND row_version=:version
                """, parameters));
        changed(changed);
        recordAudit(user, scope, "school.configuration.campus.updated", "CAMPUS", campusId,
                request.reason(), Map.of("campusCode", campusCode(request.code()), "status", status));
        return new SchoolConfigurationMutationResponse(campusId, version + 1);
    }

    private Scope requireScope(AuthenticatedUser user, UUID schoolId) {
        if (user == null || user.activeOrganisationId() == null) {
            throw conflict("ORGANISATION_SELECTION_REQUIRED", "Select a school organisation first.");
        }
        if (user.membershipIds() == null || user.membershipIds().isEmpty()) {
            throw forbidden("SCHOOL_CONFIGURATION_SCOPE_DENIED",
                    "School configuration is unavailable for this school.");
        }
        List<Scope> scopes = jdbc.query("""
                SELECT DISTINCT school.id,membership.id AS membership_id
                FROM memberships membership
                JOIN membership_roles membership_role ON membership_role.membership_id=membership.id
                JOIN role_capabilities role_capability ON role_capability.role_id=membership_role.role_id
                JOIN schools school ON school.organisation_id=membership.organisation_id
                  AND (school.id=membership.school_id OR
                    (membership.school_id IS NULL AND role_capability.capability_code='platform.configure'))
                WHERE membership.user_id=:userId
                  AND membership.organisation_id=:organisationId
                  AND membership.id IN(:membershipIds)
                  AND (CAST(:schoolId AS uuid) IS NULL OR school.id=:schoolId)
                  AND membership.status='ACTIVE'
                  AND membership.valid_from<=CURRENT_TIMESTAMP
                  AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                  AND role_capability.capability_code IN ('school.configure','platform.configure')
                ORDER BY school.id,membership.id
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("userId", user.userId())
                .addValue("organisationId", user.activeOrganisationId())
                .addValue("membershipIds", user.membershipIds())
                .addValue("schoolId", schoolId), (result, row) -> new Scope(
                user.activeOrganisationId(), result.getObject("id", UUID.class),
                result.getObject("membership_id", UUID.class)));
        if (scopes.isEmpty()) {
            throw forbidden("SCHOOL_CONFIGURATION_SCOPE_DENIED",
                    "School configuration is unavailable for this school.");
        }
        return scopes.getFirst();
    }

    private List<CampusItem> campuses(Scope scope) {
        return jdbc.query("""
                SELECT id,code,display_name,time_zone,status,row_version
                FROM campuses
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                ORDER BY CASE status WHEN 'ACTIVE' THEN 0 ELSE 1 END,display_name,id
                """, scope.params(), (result, row) -> new CampusItem(
                result.getObject("id", UUID.class), result.getString("code"),
                result.getString("display_name"), result.getString("time_zone"),
                result.getString("status"), result.getLong("row_version")));
    }

    private StoredCampus campus(Scope scope, UUID campusId) {
        List<StoredCampus> campuses = jdbc.query("""
                SELECT status,row_version FROM campuses
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                """, scope.params().addValue("id", campusId), (result, row) -> new StoredCampus(
                result.getString("status"), result.getLong("row_version")));
        if (campuses.isEmpty()) {
            throw notFound("CAMPUS_NOT_FOUND", "The campus was not found in this school.");
        }
        return campuses.getFirst();
    }

    private int activeCampusCount(Scope scope, UUID excludedCampusId) {
        String exclusion = excludedCampusId == null ? "" : " AND id<>:excludedCampusId";
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM campuses
                WHERE organisation_id=:organisationId AND school_id=:schoolId AND status='ACTIVE'
                """ + exclusion, scope.params().addValue("excludedCampusId", excludedCampusId), Integer.class);
        return count == null ? 0 : count;
    }

    private void validateProfile(SchoolProfileRequest request) {
        timeZone(request.timeZone());
        currency(request.currencyCode());
        if (request.learnerCountMin() != null && request.learnerCountMax() != null
                && request.learnerCountMax() < request.learnerCountMin()) {
            throw bad("LEARNER_COUNT_RANGE_INVALID",
                    "The maximum learner estimate cannot be lower than the minimum.");
        }
        long uniqueLanguages = request.supportedLanguages().stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .distinct()
                .count();
        if (uniqueLanguages != request.supportedLanguages().size()) {
            throw bad("SUPPORTED_LANGUAGE_DUPLICATE", "Choose each supported language only once.");
        }
    }

    private MapSqlParameterSource profileParams(
            Scope scope,
            SchoolProfileRequest request,
            Instant now) {
        return scope.params()
                .addValue("legalName", required(request.legalName()))
                .addValue("displayName", required(request.displayName()))
                .addValue("timeZone", timeZone(request.timeZone()))
                .addValue("currencyCode", currency(request.currencyCode()))
                .addValue("boardCode", required(request.boardCode()))
                .addValue("gradeRange", required(request.gradeRange()))
                .addValue("motto", clean(request.motto()))
                .addValue("languages", request.supportedLanguages().toArray(String[]::new))
                .addValue("publicEmail", lower(request.publicEmail()))
                .addValue("publicPhone", clean(request.publicPhone()))
                .addValue("principalName", clean(request.principalDisplayName()))
                .addValue("addressLine", required(request.addressLine()))
                .addValue("locality", clean(request.locality()))
                .addValue("city", required(request.city()))
                .addValue("stateName", required(request.stateName()))
                .addValue("countryCode", required(request.countryCode()).toUpperCase(Locale.ROOT))
                .addValue("learnerMin", request.learnerCountMin())
                .addValue("learnerMax", request.learnerCountMax())
                .addValue("teachingEstimate", request.teachingStaffEstimate())
                .addValue("operationsEstimate", request.operationsStaffEstimate())
                .addValue("now", now);
    }

    private MapSqlParameterSource campusParams(Scope scope, UUID id, CampusRequest request) {
        return scope.params()
                .addValue("id", id)
                .addValue("code", campusCode(request.code()))
                .addValue("displayName", required(request.displayName()))
                .addValue("timeZone", timeZone(request.timeZone()))
                .addValue("status", campusStatus(request.status()));
    }

    private void recordAudit(
            AuthenticatedUser user,
            Scope scope,
            String action,
            String subjectType,
            UUID subjectId,
            String reason,
            Map<String, ?> metadata) {
        audit.recordScoped(user, scope.actorMembershipId(), scope.schoolId(), action,
                subjectType, subjectId.toString(), AuditOutcome.SUCCEEDED,
                required(reason), metadata);
    }

    private static List<String> strings(Array array) throws SQLException {
        if (array == null) return List.of();
        try {
            return List.of((String[]) array.getArray());
        } finally {
            array.free();
        }
    }

    private static Instant instant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static String timeZone(String value) {
        String cleaned = required(value);
        try {
            return ZoneId.of(cleaned).getId();
        } catch (DateTimeException error) {
            throw bad("TIME_ZONE_INVALID", "Choose a valid IANA time zone.");
        }
    }

    private static String currency(String value) {
        String cleaned = required(value).toUpperCase(Locale.ROOT);
        try {
            return Currency.getInstance(cleaned).getCurrencyCode();
        } catch (IllegalArgumentException error) {
            throw bad("CURRENCY_INVALID", "Choose a valid three-letter currency code.");
        }
    }

    private static String campusStatus(String value) {
        String status = required(value).toUpperCase(Locale.ROOT);
        if (!CAMPUS_STATUSES.contains(status)) {
            throw bad("CAMPUS_STATUS_INVALID", "Choose an active or inactive campus status.");
        }
        return status;
    }

    private static String campusCode(String value) {
        return required(value).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_-]+", "_");
    }

    private static String clean(String value) {
        return value == null || value.isBlank()
                ? null
                : value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ").strip();
    }

    private static String required(String value) {
        String cleaned = clean(value);
        if (cleaned == null) throw bad("REQUIRED_VALUE_MISSING", "Enter the required value.");
        return cleaned;
    }

    private static String lower(String value) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.toLowerCase(Locale.ROOT);
    }

    private static void changed(int count) {
        if (count != 1) {
            throw conflict("STALE_VERSION",
                    "The configuration changed or is no longer editable. Refresh and try again.");
        }
    }

    private static <T> T safely(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (DataIntegrityViolationException error) {
            throw conflict("SCHOOL_CONFIGURATION_CONFLICT",
                    "A conflicting school or campus configuration already exists.");
        }
    }

    private static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    private static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    private static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    private record Scope(UUID organisationId, UUID schoolId, UUID actorMembershipId) {
        MapSqlParameterSource params() {
            return new MapSqlParameterSource()
                    .addValue("organisationId", organisationId)
                    .addValue("schoolId", schoolId)
                    .addValue("actorMembershipId", actorMembershipId);
        }
    }

    private record StoredCampus(String status, long rowVersion) {}
}
