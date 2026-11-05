package com.educore.api.admissions;

import com.educore.api.common.api.ApiException;
import com.educore.api.config.ApplicationProperties;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import com.educore.api.tenancy.TenantRequestContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EnquiryService {

    private static final String PRIVACY_NOTICE_VERSION = "prelaunch-2026-08";
    private final NamedParameterJdbcTemplate jdbc;
    private final ApplicationProperties properties;
    private final SecurityAuditService audit;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public EnquiryService(
            NamedParameterJdbcTemplate jdbc,
            ApplicationProperties properties,
            SecurityAuditService audit,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    EnquiryResponse create(CreateEnquiryRequest request, String idempotencyKey) {
        UUID organisationId = properties.publicOrganisationId().orElseThrow(() -> new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "PUBLIC_SCHOOL_NOT_CONFIGURED",
                "Public enquiries are temporarily unavailable."));
        UUID schoolId = properties.publicSchoolId().orElseThrow(() -> new ApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "PUBLIC_SCHOOL_NOT_CONFIGURED",
                "Public enquiries are temporarily unavailable."));

        try (TenantRequestContext.Scope ignored = TenantRequestContext.open(null, organisationId)) {
            return Objects.requireNonNull(transactions.execute(status ->
                    createWithinTransaction(request, idempotencyKey, organisationId, schoolId)));
        }
    }

    private EnquiryResponse createWithinTransaction(
            CreateEnquiryRequest request,
            String idempotencyKey,
            UUID organisationId,
            UUID schoolId) {
        String normalizedKey = validateIdempotencyKey(idempotencyKey);
        Instant now = Instant.now(clock);
        UUID proposedId = UUID.randomUUID();
        String requestFingerprint = fingerprint(request);

        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("id", proposedId)
                .addValue("organisationId", organisationId)
                .addValue("schoolId", schoolId)
                .addValue("idempotencyKey", normalizedKey)
                .addValue("fingerprint", requestFingerprint)
                .addValue("name", clean(request.name()))
                .addValue("email", request.email().strip().toLowerCase(Locale.ROOT))
                .addValue("phone", nullableClean(request.phone()))
                .addValue("topic", nullableClean(request.topic()))
                .addValue("message", clean(request.message()))
                .addValue("intent", request.intent() == null ? EnquiryIntent.GENERAL.name() : request.intent().name())
                .addValue("privacyVersion", PRIVACY_NOTICE_VERSION)
                .addValue("now", now);

        StoredEnquiry stored = jdbc.queryForObject("""
                INSERT INTO public_enquiries (
                    id, organisation_id, school_id, idempotency_key, request_fingerprint,
                    name, email_normalized, phone, topic, message, intent, source,
                    privacy_notice_version, consented_at, status, created_at, updated_at, row_version
                ) VALUES (
                    :id, :organisationId, :schoolId, :idempotencyKey, :fingerprint,
                    :name, :email, :phone, :topic, :message, :intent, 'WEBSITE',
                    :privacyVersion, :now, 'NEW', :now, :now, 0
                )
                ON CONFLICT (school_id, idempotency_key) DO UPDATE
                    SET idempotency_key = EXCLUDED.idempotency_key
                RETURNING id, request_fingerprint, created_at
                """, parameters, EnquiryService::mapStoredEnquiry);

        if (!stored.requestFingerprint().equals(requestFingerprint)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_KEY_REUSED",
                    "This retry key was already used for a different enquiry.");
        }

        if (stored.id().equals(proposedId)) {
            appendOutbox(stored.id(), "admissions.enquiry.received.v1", "NEW", 0, now);
        }

        return new EnquiryResponse(reference(stored.id()), stored.createdAt(), "received");
    }

    @Transactional(readOnly = true)
    public EnquiryPageResponse list(
            AuthenticatedUser user,
            EnquiryStatus status,
            EnquiryIntent intent,
            String query,
            int page,
            int size) {
        UUID organisationId = requireOrganisation(user);
        Instant now = Instant.now(clock);
        String search = nullableClean(query);
        MapSqlParameterSource parameters = actorParameters(user, organisationId, now)
                .addValue("status", status == null ? null : status.name())
                .addValue("intent", intent == null ? null : intent.name())
                .addValue("query", search == null ? null : "%" + escapeLike(search.toLowerCase(Locale.ROOT)) + "%")
                .addValue("limit", size)
                .addValue("offset", Math.multiplyExact(page, size));

        String filteredWhere = """
                enquiry.organisation_id = :organisationId
                AND EXISTS (
                    SELECT 1
                    FROM memberships actor_membership
                    JOIN membership_roles actor_role ON actor_role.membership_id = actor_membership.id
                    JOIN role_capabilities actor_capability ON actor_capability.role_id = actor_role.role_id
                    WHERE actor_membership.user_id = :actorUserId
                      AND actor_membership.organisation_id = enquiry.organisation_id
                      AND actor_membership.school_id = enquiry.school_id
                      AND actor_membership.status = 'ACTIVE'
                      AND actor_membership.valid_from <= :now
                      AND (actor_membership.valid_until IS NULL OR actor_membership.valid_until > :now)
                      AND actor_capability.capability_code = 'admissions.enquiry.read'
                )
                AND (CAST(:status AS varchar) IS NULL OR enquiry.status = :status)
                AND (CAST(:intent AS varchar) IS NULL OR enquiry.intent = :intent)
                AND (
                    CAST(:query AS text) IS NULL
                    OR lower(enquiry.name) LIKE :query ESCAPE '\\'
                    OR enquiry.email_normalized LIKE :query ESCAPE '\\'
                    OR lower(coalesce(enquiry.phone, '')) LIKE :query ESCAPE '\\'
                    OR lower(coalesce(enquiry.topic, '')) LIKE :query ESCAPE '\\'
                )
                """;

        List<EnquiryListItem> items = jdbc.query("""
                SELECT enquiry.id, enquiry.school_id, enquiry.name, enquiry.email_normalized, enquiry.phone,
                       enquiry.topic, enquiry.intent, enquiry.status, enquiry.source,
                       enquiry.assigned_membership_id, assignee.display_name AS assignee_name,
                       enquiry.follow_up_at, enquiry.created_at, enquiry.updated_at, enquiry.row_version
                FROM public_enquiries enquiry
                LEFT JOIN memberships assigned ON assigned.id = enquiry.assigned_membership_id
                LEFT JOIN user_accounts assignee ON assignee.id = assigned.user_id
                WHERE
                """ + filteredWhere + """
                ORDER BY enquiry.created_at DESC, enquiry.id DESC
                LIMIT :limit OFFSET :offset
                """, parameters, EnquiryService::mapListItem);

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM public_enquiries enquiry WHERE " + filteredWhere,
                parameters,
                Long.class);
        EnquiryMetrics metrics = jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE enquiry.status = 'NEW') AS new_count,
                       count(*) FILTER (WHERE enquiry.status = 'OPEN') AS open_count,
                       count(*) FILTER (WHERE enquiry.status = 'QUALIFIED') AS qualified_count,
                       count(*) FILTER (
                           WHERE enquiry.follow_up_at < :now AND enquiry.status NOT IN ('CLOSED', 'SPAM')
                       ) AS overdue_count,
                       count(*) FILTER (
                           WHERE enquiry.assigned_membership_id IS NULL AND enquiry.status NOT IN ('CLOSED', 'SPAM')
                       ) AS unassigned_count
                FROM public_enquiries enquiry
                WHERE enquiry.organisation_id = :organisationId
                  AND EXISTS (
                      SELECT 1 FROM memberships actor_membership
                      JOIN membership_roles actor_role ON actor_role.membership_id = actor_membership.id
                      JOIN role_capabilities actor_capability ON actor_capability.role_id = actor_role.role_id
                      WHERE actor_membership.user_id = :actorUserId
                        AND actor_membership.organisation_id = enquiry.organisation_id
                        AND actor_membership.school_id = enquiry.school_id
                        AND actor_membership.status = 'ACTIVE'
                        AND actor_membership.valid_from <= :now
                        AND (actor_membership.valid_until IS NULL OR actor_membership.valid_until > :now)
                        AND actor_capability.capability_code = 'admissions.enquiry.read'
                  )
                """, parameters, (result, row) -> new EnquiryMetrics(
                result.getLong("new_count"),
                result.getLong("open_count"),
                result.getLong("qualified_count"),
                result.getLong("overdue_count"),
                result.getLong("unassigned_count")));
        return new EnquiryPageResponse(items, page, size, total == null ? 0 : total, metrics);
    }

    @Transactional(readOnly = true)
    EnquiryDetailResponse get(AuthenticatedUser user, UUID enquiryId) {
        return toDetail(findAccessible(user, enquiryId, "admissions.enquiry.read"));
    }

    @Transactional
    EnquiryDetailResponse update(AuthenticatedUser user, UUID enquiryId, UpdateEnquiryRequest request) {
        StoredDetail existing = findAccessible(user, enquiryId, "admissions.enquiry.manage");
        if (request.expectedVersion() == null || request.expectedVersion().longValue() != existing.version()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "ENQUIRY_VERSION_CONFLICT",
                    "This enquiry changed after it was opened. Refresh before saving again.");
        }
        if (!existing.status().canTransitionTo(request.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "ENQUIRY_STATUS_TRANSITION_INVALID",
                    "The requested enquiry status transition is not allowed.");
        }
        if (request.assignedMembershipId() != null) {
            validateAssignee(existing, request.assignedMembershipId());
        }

        Instant now = Instant.now(clock);
        Instant followUpAt = request.status() == EnquiryStatus.CLOSED || request.status() == EnquiryStatus.SPAM
                ? null
                : request.followUpAt();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("id", existing.id())
                .addValue("organisationId", existing.organisationId())
                .addValue("schoolId", existing.schoolId())
                .addValue("status", request.status().name())
                .addValue("assignedMembershipId", request.assignedMembershipId())
                .addValue("followUpAt", followUpAt)
                .addValue("recordContact", request.recordContact())
                .addValue("expectedVersion", request.expectedVersion())
                .addValue("now", now);
        int updated = jdbc.update("""
                UPDATE public_enquiries
                SET status = :status,
                    assigned_membership_id = :assignedMembershipId,
                    follow_up_at = :followUpAt,
                    last_contacted_at = CASE WHEN :recordContact THEN :now ELSE last_contacted_at END,
                    closed_at = CASE WHEN :status = 'CLOSED' THEN coalesce(closed_at, :now) ELSE NULL END,
                    updated_at = :now,
                    row_version = row_version + 1
                WHERE id = :id
                  AND organisation_id = :organisationId
                  AND school_id = :schoolId
                  AND row_version = :expectedVersion
                """, parameters);
        if (updated != 1) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "ENQUIRY_VERSION_CONFLICT",
                    "This enquiry changed after it was opened. Refresh before saving again.");
        }

        String note = nullableClean(request.note());
        if (note != null) appendNote(existing, note, now);
        long nextVersion = existing.version() + 1;
        appendOutbox(existing.id(), "admissions.enquiry.updated.v1", request.status().name(), nextVersion, now);
        audit.recordScoped(
                user,
                existing.actorMembershipId(),
                existing.schoolId(),
                "admissions.enquiry.updated",
                "PUBLIC_ENQUIRY",
                existing.id().toString(),
                AuditOutcome.SUCCEEDED,
                null,
                Map.of(
                        "previousStatus", existing.status().name(),
                        "status", request.status().name(),
                        "assigned", request.assignedMembershipId() != null,
                        "contactRecorded", request.recordContact(),
                        "noteAdded", note != null,
                        "version", nextVersion));
        return toDetail(findAccessible(user, enquiryId, "admissions.enquiry.manage"));
    }

    @Transactional(readOnly = true)
    List<EnquiryAssignee> listAssignees(AuthenticatedUser user, UUID enquiryId) {
        StoredDetail enquiry = findAccessible(user, enquiryId, "admissions.enquiry.manage");
        Instant now = Instant.now(clock);
        return jdbc.query("""
                SELECT DISTINCT candidate.id, candidate.school_id, account.display_name
                FROM memberships candidate
                JOIN user_accounts account ON account.id = candidate.user_id
                JOIN membership_roles membership_role ON membership_role.membership_id = candidate.id
                JOIN role_capabilities capability ON capability.role_id = membership_role.role_id
                WHERE candidate.organisation_id = :organisationId
                  AND candidate.school_id = :schoolId
                  AND candidate.status = 'ACTIVE'
                  AND candidate.valid_from <= :now
                  AND (candidate.valid_until IS NULL OR candidate.valid_until > :now)
                  AND account.status = 'ACTIVE'
                  AND capability.capability_code = 'admissions.enquiry.read'
                ORDER BY account.display_name, candidate.id
                """, new MapSqlParameterSource()
                .addValue("organisationId", enquiry.organisationId())
                .addValue("schoolId", enquiry.schoolId())
                .addValue("now", now), (result, row) -> new EnquiryAssignee(
                result.getObject("id", UUID.class),
                result.getObject("school_id", UUID.class),
                result.getString("display_name")));
    }

    private StoredDetail findAccessible(AuthenticatedUser user, UUID enquiryId, String requiredCapability) {
        UUID organisationId = requireOrganisation(user);
        Instant now = Instant.now(clock);
        List<StoredDetail> results = jdbc.query("""
                SELECT enquiry.id, enquiry.organisation_id, enquiry.school_id,
                       enquiry.name, enquiry.email_normalized, enquiry.phone, enquiry.topic,
                       enquiry.message, enquiry.intent, enquiry.status, enquiry.source,
                       enquiry.assigned_membership_id, assignee.display_name AS assignee_name,
                       enquiry.follow_up_at, enquiry.last_contacted_at, enquiry.closed_at,
                       enquiry.privacy_notice_version, enquiry.consented_at,
                       enquiry.created_at, enquiry.updated_at, enquiry.row_version,
                       actor_membership.id AS actor_membership_id
                FROM public_enquiries enquiry
                JOIN LATERAL (
                    SELECT membership.id, membership.created_at
                    FROM memberships membership
                    JOIN membership_roles membership_role ON membership_role.membership_id = membership.id
                    JOIN role_capabilities capability ON capability.role_id = membership_role.role_id
                    WHERE membership.user_id = :actorUserId
                      AND membership.organisation_id = enquiry.organisation_id
                      AND membership.school_id = enquiry.school_id
                      AND membership.status = 'ACTIVE'
                      AND membership.valid_from <= :now
                      AND (membership.valid_until IS NULL OR membership.valid_until > :now)
                      AND capability.capability_code = :requiredCapability
                    ORDER BY membership.created_at, membership.id
                    LIMIT 1
                ) actor_membership ON true
                LEFT JOIN memberships assigned ON assigned.id = enquiry.assigned_membership_id
                LEFT JOIN user_accounts assignee ON assignee.id = assigned.user_id
                WHERE enquiry.id = :enquiryId
                  AND enquiry.organisation_id = :organisationId
                ORDER BY actor_membership.created_at, actor_membership.id
                LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("actorUserId", user.userId())
                .addValue("organisationId", organisationId)
                .addValue("enquiryId", enquiryId)
                .addValue("requiredCapability", requiredCapability)
                .addValue("now", now), EnquiryService::mapStoredDetail);
        if (results.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ENQUIRY_NOT_FOUND", "The enquiry was not found.");
        }
        return results.getFirst();
    }

    private EnquiryDetailResponse toDetail(StoredDetail stored) {
        List<EnquiryNoteResponse> notes = jdbc.query("""
                SELECT note.id, note.body, author.display_name AS author_name, note.created_at
                FROM admissions_enquiry_notes note
                JOIN memberships membership ON membership.id = note.author_membership_id
                JOIN user_accounts author ON author.id = membership.user_id
                WHERE note.enquiry_id = :enquiryId
                  AND note.organisation_id = :organisationId
                  AND note.school_id = :schoolId
                ORDER BY note.created_at DESC, note.id DESC
                """, new MapSqlParameterSource()
                .addValue("enquiryId", stored.id())
                .addValue("organisationId", stored.organisationId())
                .addValue("schoolId", stored.schoolId()), (result, row) -> new EnquiryNoteResponse(
                result.getObject("id", UUID.class),
                result.getString("body"),
                result.getString("author_name"),
                timestamp(result, "created_at")));
        return new EnquiryDetailResponse(
                stored.id(),
                reference(stored.id()),
                stored.name(),
                stored.email(),
                stored.phone(),
                stored.topic(),
                stored.message(),
                stored.intent(),
                stored.status(),
                stored.source(),
                stored.assignee(),
                stored.followUpAt(),
                stored.lastContactedAt(),
                stored.closedAt(),
                stored.privacyNoticeVersion(),
                stored.consentedAt(),
                stored.createdAt(),
                stored.updatedAt(),
                stored.version(),
                notes);
    }

    private void validateAssignee(StoredDetail enquiry, UUID membershipId) {
        Boolean eligible = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM memberships membership
                    JOIN membership_roles membership_role ON membership_role.membership_id = membership.id
                    JOIN role_capabilities capability ON capability.role_id = membership_role.role_id
                    WHERE membership.id = :membershipId
                      AND membership.organisation_id = :organisationId
                      AND membership.school_id = :schoolId
                      AND membership.status = 'ACTIVE'
                      AND membership.valid_from <= :now
                      AND (membership.valid_until IS NULL OR membership.valid_until > :now)
                      AND capability.capability_code = 'admissions.enquiry.read'
                )
                """, new MapSqlParameterSource()
                .addValue("membershipId", membershipId)
                .addValue("organisationId", enquiry.organisationId())
                .addValue("schoolId", enquiry.schoolId())
                .addValue("now", Instant.now(clock)), Boolean.class);
        if (!Boolean.TRUE.equals(eligible)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "ENQUIRY_ASSIGNEE_INVALID",
                    "The selected assignee is not an active admissions user for this school.");
        }
    }

    private void appendNote(StoredDetail enquiry, String note, Instant now) {
        jdbc.update("""
                INSERT INTO admissions_enquiry_notes (
                    id, organisation_id, school_id, enquiry_id, author_membership_id, body, created_at
                ) VALUES (
                    :id, :organisationId, :schoolId, :enquiryId, :authorMembershipId, :body, :createdAt
                )
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("organisationId", enquiry.organisationId())
                .addValue("schoolId", enquiry.schoolId())
                .addValue("enquiryId", enquiry.id())
                .addValue("authorMembershipId", enquiry.actorMembershipId())
                .addValue("body", note)
                .addValue("createdAt", now));
    }

    private void appendOutbox(UUID enquiryId, String eventType, String status, long version, Instant now) {
        jdbc.update("""
                INSERT INTO outbox_events (
                    id, aggregate_type, aggregate_id, event_type, payload, occurred_at,
                    available_at, attempt_count, created_at
                ) VALUES (
                    :eventId, 'PublicEnquiry', :aggregateId, :eventType,
                    CAST(:payload AS jsonb), :now, :now, 0, :now
                )
                """, new MapSqlParameterSource()
                .addValue("eventId", UUID.randomUUID())
                .addValue("aggregateId", enquiryId.toString())
                .addValue("eventType", eventType)
                .addValue("payload", "{\"enquiryId\":\"" + enquiryId + "\",\"status\":\"" + status
                        + "\",\"version\":" + version + "}")
                .addValue("now", now));
    }

    private static MapSqlParameterSource actorParameters(
            AuthenticatedUser user,
            UUID organisationId,
            Instant now) {
        return new MapSqlParameterSource()
                .addValue("actorUserId", user.userId())
                .addValue("organisationId", organisationId)
                .addValue("now", now);
    }

    private static EnquiryListItem mapListItem(ResultSet result, int rowNumber) throws SQLException {
        UUID assignedMembershipId = result.getObject("assigned_membership_id", UUID.class);
        EnquiryAssignee assignee = assignedMembershipId == null
                ? null
                : new EnquiryAssignee(
                        assignedMembershipId,
                        result.getObject("school_id", UUID.class),
                        result.getString("assignee_name"));
        UUID id = result.getObject("id", UUID.class);
        return new EnquiryListItem(
                id,
                reference(id),
                result.getString("name"),
                result.getString("email_normalized"),
                result.getString("phone"),
                result.getString("topic"),
                EnquiryIntent.valueOf(result.getString("intent")),
                EnquiryStatus.valueOf(result.getString("status")),
                result.getString("source"),
                assignee,
                timestamp(result, "follow_up_at"),
                timestamp(result, "created_at"),
                timestamp(result, "updated_at"),
                result.getLong("row_version"));
    }

    private static StoredDetail mapStoredDetail(ResultSet result, int rowNumber) throws SQLException {
        UUID assignedMembershipId = result.getObject("assigned_membership_id", UUID.class);
        EnquiryAssignee assignee = assignedMembershipId == null
                ? null
                : new EnquiryAssignee(
                        assignedMembershipId,
                        result.getObject("school_id", UUID.class),
                        result.getString("assignee_name"));
        return new StoredDetail(
                result.getObject("id", UUID.class),
                result.getObject("organisation_id", UUID.class),
                result.getObject("school_id", UUID.class),
                result.getObject("actor_membership_id", UUID.class),
                result.getString("name"),
                result.getString("email_normalized"),
                result.getString("phone"),
                result.getString("topic"),
                result.getString("message"),
                EnquiryIntent.valueOf(result.getString("intent")),
                EnquiryStatus.valueOf(result.getString("status")),
                result.getString("source"),
                assignee,
                timestamp(result, "follow_up_at"),
                timestamp(result, "last_contacted_at"),
                timestamp(result, "closed_at"),
                result.getString("privacy_notice_version"),
                timestamp(result, "consented_at"),
                timestamp(result, "created_at"),
                timestamp(result, "updated_at"),
                result.getLong("row_version"));
    }

    private static StoredEnquiry mapStoredEnquiry(ResultSet result, int rowNumber) throws SQLException {
        return new StoredEnquiry(
                result.getObject("id", UUID.class),
                result.getString("request_fingerprint"),
                timestamp(result, "created_at"));
    }

    private static Instant timestamp(ResultSet result, String column) throws SQLException {
        var value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static UUID requireOrganisation(AuthenticatedUser user) {
        if (user.activeOrganisationId() == null) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "ORGANISATION_SELECTION_REQUIRED",
                    "Select a school organisation before opening admissions.");
        }
        return user.activeOrganisationId();
    }

    private static String validateIdempotencyKey(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{16,100}")) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must contain 16 to 100 safe characters.");
        }
        return value;
    }

    private static String fingerprint(CreateEnquiryRequest request) {
        Map<String, String> canonical = Map.of(
                "name", clean(request.name()),
                "email", request.email().strip().toLowerCase(Locale.ROOT),
                "phone", nullableClean(request.phone()) == null ? "" : nullableClean(request.phone()),
                "topic", nullableClean(request.topic()) == null ? "" : nullableClean(request.topic()),
                "message", clean(request.message()),
                "intent", request.intent() == null ? EnquiryIntent.GENERAL.name() : request.intent().name());
        String input = canonical.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String clean(String value) {
        return value.strip().replaceAll("[\\p{Cc}&&[^\\r\\n\\t]]", "");
    }

    private static String nullableClean(String value) {
        return value == null || value.isBlank() ? null : clean(value);
    }

    static String reference(UUID id) {
        return "ENQ-" + id.toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
    }

    private record StoredEnquiry(UUID id, String requestFingerprint, Instant createdAt) {
    }

    private record StoredDetail(
            UUID id,
            UUID organisationId,
            UUID schoolId,
            UUID actorMembershipId,
            String name,
            String email,
            String phone,
            String topic,
            String message,
            EnquiryIntent intent,
            EnquiryStatus status,
            String source,
            EnquiryAssignee assignee,
            Instant followUpAt,
            Instant lastContactedAt,
            Instant closedAt,
            String privacyNoticeVersion,
            Instant consentedAt,
            Instant createdAt,
            Instant updatedAt,
            long version) {
    }
}
