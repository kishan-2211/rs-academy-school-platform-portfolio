package com.educore.api.communication;

import com.educore.api.common.api.ApiException;
import com.educore.api.communication.PushNotificationController.RegisterPushSubscriptionRequest;
import com.educore.api.communication.PushNotificationController.RevokePushSubscriptionRequest;
import com.educore.api.identity.AuthenticatedUser;
import com.educore.api.identity.SecurityAuditService;
import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PushNotificationService {

    private static final Set<String> RESERVED_HOST_SUFFIXES = Set.of(
            ".localhost", ".local", ".internal", ".test", ".invalid", ".example");
    private final NamedParameterJdbcTemplate jdbc;
    private final PushRegistrationConfiguration configuration;
    private final PushSubscriptionProtector protector;
    private final SecurityAuditService audit;
    private final Clock clock;

    public PushNotificationService(
            NamedParameterJdbcTemplate jdbc,
            PushRegistrationConfiguration configuration,
            PushSubscriptionProtector protector,
            SecurityAuditService audit,
            Clock clock) {
        this.jdbc = jdbc;
        this.configuration = configuration;
        this.protector = protector;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PushNotificationResponse status(AuthenticatedUser user, UUID schoolId) {
        return snapshot(requireScope(user, schoolId));
    }

    @Transactional
    public PushNotificationResponse register(
            AuthenticatedUser user, RegisterPushSubscriptionRequest request) {
        Scope scope = requireScope(user, request.schoolId());
        if (!configuration.available()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PUSH_REGISTRATION_UNAVAILABLE",
                    "Phone notifications are not enabled for this deployment yet. In-app alerts remain available.");
        }
        String endpoint = validEndpoint(request.endpoint());
        validateBrowserKey(request.p256dh(), 65, "PUSH_P256DH_INVALID");
        validateBrowserKey(request.auth(), 16, "PUSH_AUTH_INVALID");
        String endpointHash = sha256(endpoint);
        MapSqlParameterSource parameters = scope.params().addValue("endpointHash", endpointHash);
        lock(scope.schoolId(), endpointHash);
        List<StoredSubscription> existing = jdbc.query("""
                SELECT id,user_account_id,row_version FROM communication_push_subscriptions
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND endpoint_hash=:endpointHash AND status='ACTIVE'
                FOR UPDATE
                """, parameters, (result, row) -> new StoredSubscription(
                result.getObject("id", UUID.class), result.getObject("user_account_id", UUID.class),
                result.getLong("row_version")));
        Instant now = Instant.now(clock);
        UUID subscriptionId;
        String eventType;
        if (existing.isEmpty()) {
            subscriptionId = UUID.randomUUID();
            var protectedValue = protector.protect(
                    subscriptionId, endpoint, request.p256dh(), request.auth());
            jdbc.update("""
                    INSERT INTO communication_push_subscriptions(
                      id,organisation_id,school_id,user_account_id,endpoint_hash,
                      endpoint_ciphertext,endpoint_nonce,p256dh_ciphertext,p256dh_nonce,
                      auth_ciphertext,auth_nonce,device_label,status,registered_by_membership_id,
                      created_at,last_confirmed_at,row_version)
                    VALUES(:id,:organisationId,:schoolId,:actorUserId,:endpointHash,
                      :endpointCiphertext,:endpointNonce,:p256dhCiphertext,:p256dhNonce,
                      :authCiphertext,:authNonce,:deviceLabel,'ACTIVE',:actorMembershipId,
                      :now,:now,0)
                    """, protectedParameters(parameters, protectedValue)
                    .addValue("id", subscriptionId).addValue("now", now)
                    .addValue("deviceLabel", label(request.deviceLabel())));
            event(scope, subscriptionId, "REGISTERED", "Self-service browser registration", now);
            eventType = "REGISTERED";
        } else {
            StoredSubscription stored = existing.getFirst();
            if (!stored.userAccountId().equals(scope.actorUserId())) {
                throw new ApiException(HttpStatus.CONFLICT, "PUSH_SUBSCRIPTION_ACCOUNT_CONFLICT",
                        "This browser subscription is already linked to another account in this school. Sign out there before linking it here.");
            }
            subscriptionId = stored.id();
            var protectedValue = protector.protect(
                    subscriptionId, endpoint, request.p256dh(), request.auth());
            int updated = jdbc.update("""
                    UPDATE communication_push_subscriptions SET
                      endpoint_ciphertext=:endpointCiphertext,endpoint_nonce=:endpointNonce,
                      p256dh_ciphertext=:p256dhCiphertext,p256dh_nonce=:p256dhNonce,
                      auth_ciphertext=:authCiphertext,auth_nonce=:authNonce,
                      device_label=:deviceLabel,last_confirmed_at=:now,row_version=row_version+1
                    WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                      AND user_account_id=:actorUserId AND status='ACTIVE' AND row_version=:version
                    """, protectedParameters(parameters, protectedValue)
                    .addValue("id", subscriptionId).addValue("now", now)
                    .addValue("deviceLabel", label(request.deviceLabel()))
                    .addValue("version", stored.version()));
            if (updated != 1) throw stale();
            event(scope, subscriptionId, "REFRESHED", "Self-service browser refresh", now);
            eventType = "REFRESHED";
        }
        lockPreference(scope);
        boolean consentChanged = enablePreference(scope, subscriptionId, now);
        audit.recordScoped(user, scope.actorMembershipId(), scope.schoolId(),
                "communication.push.subscription." + eventType.toLowerCase(Locale.ROOT),
                "COMMUNICATION_PUSH_SUBSCRIPTION", subscriptionId.toString(),
                AuditOutcome.SUCCEEDED, null,
                Map.of("consentChanged", consentChanged));
        return snapshot(scope);
    }

    @Transactional
    public PushNotificationResponse revoke(
            AuthenticatedUser user, RevokePushSubscriptionRequest request) {
        Scope scope = requireScope(user, request.schoolId());
        String endpoint = validEndpoint(request.endpoint());
        String endpointHash = sha256(endpoint);
        lock(scope.schoolId(), endpointHash);
        MapSqlParameterSource parameters = scope.params().addValue("endpointHash", endpointHash);
        List<StoredSubscription> existing = jdbc.query("""
                SELECT id,user_account_id,row_version FROM communication_push_subscriptions
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND user_account_id=:actorUserId AND endpoint_hash=:endpointHash
                  AND status='ACTIVE' FOR UPDATE
                """, parameters, (result, row) -> new StoredSubscription(
                result.getObject("id", UUID.class), result.getObject("user_account_id", UUID.class),
                result.getLong("row_version")));
        if (existing.isEmpty()) return snapshot(scope);
        StoredSubscription stored = existing.getFirst();
        Instant now = Instant.now(clock);
        int updated = jdbc.update("""
                UPDATE communication_push_subscriptions SET
                  endpoint_ciphertext=NULL,endpoint_nonce=NULL,p256dh_ciphertext=NULL,p256dh_nonce=NULL,
                  auth_ciphertext=NULL,auth_nonce=NULL,status='REVOKED',revoked_at=:now,
                  revoked_reason='Self-service device removal',row_version=row_version+1
                WHERE id=:id AND organisation_id=:organisationId AND school_id=:schoolId
                  AND user_account_id=:actorUserId AND status='ACTIVE' AND row_version=:version
                """, parameters.addValue("id", stored.id()).addValue("now", now)
                .addValue("version", stored.version()));
        if (updated != 1) throw stale();
        event(scope, stored.id(), "REVOKED", "Self-service device removal", now);
        lockPreference(scope);
        boolean consentChanged = disablePreferenceWhenLastDevice(scope, stored.id(), now);
        audit.recordScoped(user, scope.actorMembershipId(), scope.schoolId(),
                "communication.push.subscription.revoked", "COMMUNICATION_PUSH_SUBSCRIPTION",
                stored.id().toString(), AuditOutcome.SUCCEEDED, null,
                Map.of("consentChanged", consentChanged));
        return snapshot(scope);
    }

    private PushNotificationResponse snapshot(Scope scope) {
        MapSqlParameterSource parameters = scope.params();
        boolean allowPush = jdbc.query("""
                SELECT allow_push FROM communication_contact_preferences
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND user_account_id=:actorUserId
                """, parameters, result -> result.next() && result.getBoolean(1));
        List<PushNotificationResponse.Device> devices = jdbc.query("""
                SELECT id,device_label,created_at,last_confirmed_at
                FROM communication_push_subscriptions
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND user_account_id=:actorUserId AND status='ACTIVE'
                ORDER BY last_confirmed_at DESC,id
                """, parameters, (result, row) -> new PushNotificationResponse.Device(
                result.getObject("id", UUID.class), result.getString("device_label"),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("last_confirmed_at").toInstant()));
        return new PushNotificationResponse(
                scope.schoolId(), configuration.available(), configuration.applicationServerKey(),
                allowPush, devices.size(), devices);
    }

    private Scope requireScope(AuthenticatedUser user, UUID schoolId) {
        if (user == null || user.activeOrganisationId() == null || user.membershipIds().isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "ORGANISATION_SELECTION_REQUIRED",
                    "Select your school before managing phone notifications.");
        }
        List<Scope> scopes = jdbc.query("""
                SELECT membership.id,membership.school_id
                FROM memberships membership
                WHERE membership.user_id=:actorUserId
                  AND membership.organisation_id=:organisationId
                  AND membership.school_id=:schoolId
                  AND membership.id IN (:membershipIds)
                  AND membership.status='ACTIVE'
                  AND membership.valid_from<=CURRENT_TIMESTAMP
                  AND (membership.valid_until IS NULL OR membership.valid_until>CURRENT_TIMESTAMP)
                  AND EXISTS (
                    SELECT 1 FROM membership_roles membership_role
                    JOIN roles role ON role.id=membership_role.role_id
                    JOIN role_capabilities capability ON capability.role_id=membership_role.role_id
                    WHERE membership_role.membership_id=membership.id
                      AND (role.organisation_id IS NULL
                           OR role.organisation_id=membership.organisation_id)
                      AND capability.capability_code IN ('communication.read','portal.guardian','portal.student')
                  )
                ORDER BY membership.created_at,membership.id LIMIT 1
                """, new MapSqlParameterSource()
                .addValue("actorUserId", user.userId())
                .addValue("organisationId", user.activeOrganisationId())
                .addValue("schoolId", schoolId)
                .addValue("membershipIds", user.membershipIds()),
                (result, row) -> new Scope(
                        user.activeOrganisationId(), result.getObject("school_id", UUID.class),
                        result.getObject("id", UUID.class), user.userId()));
        if (scopes.isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PUSH_NOTIFICATION_SCOPE_DENIED",
                    "Phone notification settings are unavailable for this school account.");
        }
        return scopes.getFirst();
    }

    private boolean enablePreference(Scope scope, UUID subscriptionId, Instant now) {
        List<Boolean> current = jdbc.query("""
                SELECT allow_push FROM communication_contact_preferences
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND user_account_id=:actorUserId FOR UPDATE
                """, scope.params(), (result, row) -> result.getBoolean(1));
        boolean changed = current.isEmpty() || !current.getFirst();
        if (current.isEmpty()) {
            jdbc.update("""
                    INSERT INTO communication_contact_preferences(
                      id,organisation_id,school_id,user_account_id,preferred_language,time_zone,
                      allow_email,allow_sms,allow_whatsapp,allow_push,safe_contact_status,
                      created_at,updated_at,row_version)
                    SELECT :id,:organisationId,:schoolId,:actorUserId,'EN',school.time_zone,
                      true,true,false,true,'ALLOWED',:now,:now,0
                    FROM schools school WHERE school.id=:schoolId AND school.organisation_id=:organisationId
                    """, scope.params().addValue("id", UUID.randomUUID()).addValue("now", now));
        } else if (!current.getFirst()) {
            jdbc.update("""
                    UPDATE communication_contact_preferences SET allow_push=true,updated_at=:now,
                      row_version=row_version+1
                    WHERE organisation_id=:organisationId AND school_id=:schoolId
                      AND user_account_id=:actorUserId AND allow_push=false
                    """, scope.params().addValue("now", now));
        }
        if (changed) consent(scope, "GRANTED", "subscription:" + subscriptionId, now);
        return changed;
    }

    private boolean disablePreferenceWhenLastDevice(Scope scope, UUID subscriptionId, Instant now) {
        Integer active = jdbc.queryForObject("""
                SELECT count(*) FROM communication_push_subscriptions
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND user_account_id=:actorUserId AND status='ACTIVE'
                """, scope.params(), Integer.class);
        if (active != null && active > 0) return false;
        int updated = jdbc.update("""
                UPDATE communication_contact_preferences SET allow_push=false,updated_at=:now,
                  row_version=row_version+1
                WHERE organisation_id=:organisationId AND school_id=:schoolId
                  AND user_account_id=:actorUserId AND allow_push=true
                """, scope.params().addValue("now", now));
        if (updated > 0) consent(scope, "REVOKED", "subscription:" + subscriptionId, now);
        return updated > 0;
    }

    private void consent(Scope scope, String action, String evidenceReference, Instant now) {
        jdbc.update("""
                INSERT INTO communication_consent_events(
                  id,organisation_id,school_id,user_account_id,channel,purpose_code,action,
                  source,notice_version,evidence_reference,occurred_at,actor_user_id)
                VALUES(:id,:organisationId,:schoolId,:actorUserId,'PUSH','SCHOOL_OPERATIONS',
                  :action,'SELF_SERVICE','push-notifications-v1',:evidenceReference,:now,:actorUserId)
                """, scope.params().addValue("id", UUID.randomUUID()).addValue("action", action)
                .addValue("evidenceReference", evidenceReference).addValue("now", now));
    }

    private void event(Scope scope, UUID subscriptionId, String eventType, String reason, Instant now) {
        jdbc.update("""
                INSERT INTO communication_push_subscription_events(
                  id,organisation_id,school_id,communication_push_subscription_id,event_type,
                  actor_user_id,actor_membership_id,occurred_at,safe_reason)
                VALUES(:id,:organisationId,:schoolId,:subscriptionId,:eventType,
                  :actorUserId,:actorMembershipId,:now,:reason)
                """, scope.params().addValue("id", UUID.randomUUID())
                .addValue("subscriptionId", subscriptionId).addValue("eventType", eventType)
                .addValue("now", now).addValue("reason", reason));
    }

    private void lock(UUID schoolId, String endpointHash) {
        jdbc.queryForObject(
                "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key,0))) locked",
                new MapSqlParameterSource("key", "push:" + schoolId + ":" + endpointHash), Integer.class);
    }

    private void lockPreference(Scope scope) {
        jdbc.queryForObject(
                "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key,0))) locked",
                new MapSqlParameterSource("key", "push-preference:" + scope.schoolId()
                        + ":" + scope.actorUserId()), Integer.class);
    }

    private static MapSqlParameterSource protectedParameters(
            MapSqlParameterSource parameters,
            PushSubscriptionProtector.ProtectedSubscription protectedValue) {
        return parameters
                .addValue("endpointCiphertext", protectedValue.endpoint().ciphertext())
                .addValue("endpointNonce", protectedValue.endpoint().nonce())
                .addValue("p256dhCiphertext", protectedValue.p256dh().ciphertext())
                .addValue("p256dhNonce", protectedValue.p256dh().nonce())
                .addValue("authCiphertext", protectedValue.auth().ciphertext())
                .addValue("authNonce", protectedValue.auth().nonce());
    }

    private static String validEndpoint(String value) {
        String endpoint = value == null ? "" : value.strip();
        try {
            URI uri = new URI(endpoint);
            String host = uri.getHost();
            String lowerHost = host == null ? "" : host.toLowerCase(Locale.ROOT);
            boolean reserved = lowerHost.equals("localhost") || lowerHost.indexOf('.') < 1
                    || lowerHost.matches("^[0-9.]+$") || lowerHost.contains(":")
                    || RESERVED_HOST_SUFFIXES.stream().anyMatch(lowerHost::endsWith);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || reserved
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return endpoint;
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PUSH_ENDPOINT_INVALID",
                    "The browser supplied an invalid secure push endpoint.");
        }
    }

    private static void validateBrowserKey(String value, int expectedBytes, String code) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (decoded.length != expectedBytes || (expectedBytes == 65 && decoded[0] != 0x04)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, code,
                    "The browser supplied invalid push encryption material.");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String label(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static ApiException stale() {
        return new ApiException(HttpStatus.CONFLICT, "PUSH_SUBSCRIPTION_STALE",
                "The phone notification setting changed in another request. Refresh and try again.");
    }

    private record StoredSubscription(UUID id, UUID userAccountId, long version) {}

    private record Scope(
            UUID organisationId, UUID schoolId, UUID actorMembershipId, UUID actorUserId) {
        MapSqlParameterSource params() {
            return new MapSqlParameterSource()
                    .addValue("organisationId", organisationId)
                    .addValue("schoolId", schoolId)
                    .addValue("actorMembershipId", actorMembershipId)
                    .addValue("actorUserId", actorUserId);
        }
    }
}
