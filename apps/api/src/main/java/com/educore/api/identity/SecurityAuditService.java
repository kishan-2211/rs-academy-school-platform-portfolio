package com.educore.api.identity;

import com.educore.api.common.api.RequestIdFilter;
import com.educore.api.tenancy.TenantRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class SecurityAuditService {

    private static final Logger log = LoggerFactory.getLogger(SecurityAuditService.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SecurityAuditService(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void record(
            UUID actorUserId,
            String action,
            AuditOutcome outcome,
            String reason,
            Map<String, ?> safeMetadata) {
        if (actorUserId == null) {
            log.warn("Skipped an unbound identity audit event for action {} and outcome {}", action, outcome);
            return;
        }
        Instant occurredAt = Instant.now(clock);
        UUID eventId = UUID.randomUUID();
        String streamKey = "platform:identity:" + actorUserId;
        jdbc.queryForObject(
                "SELECT set_config('app.current_user_id', ?, true)",
                String.class,
                actorUserId.toString());
        jdbc.queryForObject(
                "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?, 0))) AS locked",
                Integer.class,
                streamKey);
        String previousHash = jdbc.query(
                """
                SELECT event_hash
                FROM audit_events
                WHERE organisation_id IS NULL AND actor_user_id = ?
                ORDER BY occurred_at DESC, id DESC
                LIMIT 1
                """,
                result -> result.next() ? result.getString(1) : null,
                actorUserId);
        String metadataJson = json(safeMetadata == null ? Map.of() : new TreeMap<>(safeMetadata));
        String requestId = requestId();
        String safeReason = truncate(reason, 500);
        String eventHash = sha256(String.join("|",
                eventId.toString(),
                occurredAt.toString(),
                value(actorUserId),
                requestId,
                action,
                outcome.name(),
                value(safeReason),
                metadataJson,
                value(previousHash)));

        jdbc.update(
                """
                INSERT INTO audit_events (
                    id, occurred_at, organisation_id, school_id, actor_user_id, actor_membership_id,
                    request_id, action, subject_type, subject_id, outcome, reason, safe_metadata,
                    previous_hash, event_hash
                ) VALUES (?, ?, NULL, NULL, ?, NULL, ?, ?, 'USER_ACCOUNT', ?, ?, ?, CAST(? AS jsonb), ?, ?)
                """,
                eventId,
                occurredAt,
                actorUserId,
                requestId,
                action,
                actorUserId == null ? null : actorUserId.toString(),
                outcome.name(),
                safeReason,
                metadataJson,
                previousHash,
                eventHash);
    }

    public void recordScoped(
            AuthenticatedUser actor,
            UUID actorMembershipId,
            UUID schoolId,
            String action,
            String subjectType,
            String subjectId,
            AuditOutcome outcome,
            String reason,
            Map<String, ?> safeMetadata) {
        if (actor == null
                || actor.userId() == null
                || actor.activeOrganisationId() == null
                || actorMembershipId == null
                || schoolId == null) {
            throw new IllegalArgumentException("Scoped audit events require an authenticated school context");
        }

        UUID organisationId = actor.activeOrganisationId();
        Instant occurredAt = Instant.now(clock);
        UUID eventId = UUID.randomUUID();
        String streamKey = "organisation:audit:" + organisationId;
        jdbc.queryForObject(
                "SELECT set_config('app.current_user_id', ?, true)",
                String.class,
                actor.userId().toString());
        jdbc.queryForObject(
                "SELECT set_config('app.current_organisation_id', ?, true)",
                String.class,
                organisationId.toString());
        jdbc.queryForObject(
                "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?, 0))) AS locked",
                Integer.class,
                streamKey);
        String previousHash = jdbc.query(
                """
                SELECT event_hash
                FROM audit_events
                WHERE organisation_id = ?
                ORDER BY occurred_at DESC, id DESC
                LIMIT 1
                """,
                result -> result.next() ? result.getString(1) : null,
                organisationId);
        String metadataJson = json(safeMetadata == null ? Map.of() : new TreeMap<>(safeMetadata));
        String requestId = requestId();
        String safeReason = truncate(reason, 500);
        String eventHash = sha256(String.join("|",
                eventId.toString(),
                occurredAt.toString(),
                value(organisationId),
                value(schoolId),
                value(actor.userId()),
                value(actorMembershipId),
                requestId,
                action,
                value(subjectType),
                value(subjectId),
                outcome.name(),
                value(safeReason),
                metadataJson,
                value(previousHash)));

        jdbc.update(
                """
                INSERT INTO audit_events (
                    id, occurred_at, organisation_id, school_id, actor_user_id, actor_membership_id,
                    request_id, action, subject_type, subject_id, outcome, reason, safe_metadata,
                    previous_hash, event_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                """,
                eventId,
                occurredAt,
                organisationId,
                schoolId,
                actor.userId(),
                actorMembershipId,
                requestId,
                action,
                truncate(subjectType, 120),
                truncate(subjectId, 120),
                outcome.name(),
                safeReason,
                metadataJson,
                previousHash,
                eventHash);
    }

    /** Only persisted gateway verification results may use an honest background system actor. */
    public void recordGatewaySystemScoped(
            UUID organisationId,
            UUID schoolId,
            UUID captureId,
            UUID paymentId,
            String disposition) {
        if (organisationId == null || schoolId == null || captureId == null
                || !("HOLD".equals(disposition) || "POSTED".equals(disposition))
                || ("POSTED".equals(disposition) ? paymentId == null : paymentId != null)) {
            throw new IllegalArgumentException("Gateway system audit requires a scoped capture and a valid disposition");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Gateway system audit requires a writable transaction");
        }
        var context = TenantRequestContext.current().orElseThrow(
                () -> new IllegalStateException("Gateway system audit requires a background organisation context"));
        if (context.userId() != null || !organisationId.equals(context.organisationId())) {
            throw new IllegalStateException("Gateway system audit requires a background organisation context");
        }
        Boolean databaseScoped = jdbc.queryForObject(
                "SELECT current_app_organisation_id() = ? AND current_app_user_id() IS NULL",
                Boolean.class,
                organisationId);
        if (!Boolean.TRUE.equals(databaseScoped)) {
            throw new IllegalStateException("Gateway system audit requires matching background database scope");
        }
        Boolean captureScoped = jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM fee_gateway_captures
                    WHERE id = ? AND organisation_id = ? AND school_id = ?
                )
                """,
                Boolean.class,
                captureId,
                organisationId,
                schoolId);
        if (!Boolean.TRUE.equals(captureScoped)) {
            throw new IllegalStateException("Gateway system audit requires a persisted capture in the school scope");
        }
        boolean posted = "POSTED".equals(disposition);
        Boolean resolutionScoped = jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM fee_gateway_capture_resolutions
                    WHERE capture_id = ? AND organisation_id = ? AND school_id = ?
                        AND fee_payment_id IS NOT DISTINCT FROM ? AND resolution_type = ? AND actor_kind = 'SYSTEM'
                        AND reviewed_by_user_id IS NULL AND reviewed_by_membership_id IS NULL
                )
                """,
                Boolean.class,
                captureId,
                organisationId,
                schoolId,
                paymentId,
                disposition);
        if (!Boolean.TRUE.equals(resolutionScoped)) {
            throw new IllegalStateException("Gateway system audit requires a matching persisted system capture resolution");
        }

        String streamKey = "organisation:audit:" + organisationId;
        jdbc.queryForObject(
                "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?, 0))) AS locked",
                Integer.class,
                streamKey);
        String previousHash = jdbc.query(
                """
                SELECT event_hash
                FROM audit_events
                WHERE organisation_id = ?
                ORDER BY occurred_at DESC, id DESC
                LIMIT 1
                """,
                result -> result.next() ? result.getString(1) : null,
                organisationId);
        UUID eventId = UUID.randomUUID();
        Instant occurredAt = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        String requestId = "system-" + UUID.randomUUID();
        String action = posted ? "finance.gateway.payment.posted" : "finance.gateway.capture.held";
        String subjectType = posted ? "FEE_PAYMENT" : "FEE_GATEWAY_CAPTURE";
        String subjectId = (posted ? paymentId : captureId).toString();
        String reason = posted ? "Server-verified gateway capture posted" : "Gateway capture retained for review";
        Map<String, Object> safeMetadata = new TreeMap<>();
        safeMetadata.put("actorKind", "SYSTEM");
        safeMetadata.put("component", "GATEWAY_VERIFICATION");
        safeMetadata.put("captureId", captureId.toString());
        if (posted) safeMetadata.put("paymentId", paymentId.toString());
        String metadataJson = json(safeMetadata);
        String eventHash = sha256(String.join("|",
                eventId.toString(), occurredAt.toString(), value(organisationId), value(schoolId),
                "", "", requestId, action, subjectType, subjectId, AuditOutcome.SUCCEEDED.name(),
                reason, metadataJson, value(previousHash)));
        jdbc.update(
                """
                INSERT INTO audit_events (
                    id, occurred_at, organisation_id, school_id, actor_user_id, actor_membership_id,
                    request_id, action, subject_type, subject_id, outcome, reason, safe_metadata,
                    previous_hash, event_hash
                ) VALUES (?, ?, ?, ?, NULL, NULL, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                """,
                eventId,
                Timestamp.from(occurredAt),
                organisationId,
                schoolId,
                requestId,
                action,
                subjectType,
                subjectId,
                AuditOutcome.SUCCEEDED.name(),
                reason,
                metadataJson,
                previousHash,
                eventHash);
    }

    private String requestId() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            Object value = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
            if (value instanceof String id && !id.isBlank()) return id;
        }
        return "system-" + UUID.randomUUID();
    }

    /** Only the one-use owner CLI: honest system actor, same transaction and organisation hash stream. */
    static void recordFirstAdministrator(Connection connection, String database, String operator,
            UUID organisation, UUID school, UUID user, UUID membership, String approvalReference,
            ObjectMapper objectMapper, Clock clock) throws Exception {
        FirstAdministratorTool.requireOperator(connection, database, operator);
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        var audit = new SecurityAuditService(jdbc, objectMapper, clock);
        jdbc.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(?,0))) AS locked",
                Integer.class, "organisation:audit:" + organisation);
        String previousHash = jdbc.query("""
                SELECT event_hash FROM public.audit_events WHERE organisation_id=?
                ORDER BY occurred_at DESC,id DESC LIMIT 1
                """, result -> result.next() ? result.getString(1) : null, organisation);
        UUID event = UUID.randomUUID(); Instant occurredAt = Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        String request = "operator-" + UUID.randomUUID();
        String action = "identity.first_administrator.created";
        String reason = "Owner-approved first-use administrator provisioning";
        String metadata = audit.json(new TreeMap<>(Map.of("operatorType", "MIGRATION_DATABASE_LOGIN",
                "databaseLogin", operator, "approvalReference", approvalReference, "membershipId", membership.toString())));
        String hash = sha256(String.join("|", event.toString(), occurredAt.toString(), value(organisation), value(school),
                "", "", request, action, "USER_ACCOUNT", user.toString(), AuditOutcome.SUCCEEDED.name(), reason, metadata, value(previousHash)));
        jdbc.update("""
                INSERT INTO public.audit_events(id,occurred_at,organisation_id,school_id,actor_user_id,actor_membership_id,
                  request_id,action,subject_type,subject_id,outcome,reason,safe_metadata,previous_hash,event_hash)
                VALUES(?,?,?,?,NULL,NULL,?,?,'USER_ACCOUNT',?,'SUCCEEDED',?,CAST(? AS jsonb),?,?)
                """, event, java.sql.Timestamp.from(occurredAt), organisation, school, request, action, user.toString(), reason, metadata, previousHash, hash);
    }

    private String json(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Audit metadata must be JSON serializable", exception);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String truncate(String value, int maximumLength) {
        if (value == null || value.isBlank()) return null;
        String safe = value.replaceAll("[\\p{Cntrl}]", " ").strip();
        return safe.substring(0, Math.min(safe.length(), maximumLength));
    }

    private static String value(Object value) {
        return value == null ? "" : value.toString();
    }

    public enum AuditOutcome {
        SUCCEEDED,
        DENIED,
        FAILED
    }
}
