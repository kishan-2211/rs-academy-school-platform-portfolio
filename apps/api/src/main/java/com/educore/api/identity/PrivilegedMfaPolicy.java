package com.educore.api.identity;

import com.educore.api.identity.SecurityAuditService.AuditOutcome;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Request-boundary assurance, not a replacement for scoped domain authorization. */
@Service
public class PrivilegedMfaPolicy {
    private final Environment environment;
    private final NamedParameterJdbcTemplate jdbc;
    private final SecurityAuditService audit;
    private final Clock clock;

    public PrivilegedMfaPolicy(Environment environment, NamedParameterJdbcTemplate jdbc,
            SecurityAuditService audit, Clock clock) {
        this.environment = environment;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public boolean isRequired(AuthenticatedUser user) {
        if (!environment.matchesProfiles("prod") || user.mfaVerified() || user.activeOrganisationId() == null) return false;
        // Use live grants and the same validity/role-organisation rules as AccessContextService.
        // Neither an inactive membership nor authority in another organisation can impose/grant access here.
        return !Boolean.FALSE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM memberships m
                    JOIN membership_roles mr ON mr.membership_id = m.id
                    JOIN roles r ON r.id = mr.role_id
                    JOIN role_capabilities rc ON rc.role_id = r.id
                    JOIN capabilities c ON c.code = rc.capability_code
                    WHERE m.user_id = :user AND m.organisation_id = :organisation
                      AND m.status = 'ACTIVE' AND m.valid_from <= :now
                      AND (m.valid_until IS NULL OR m.valid_until > :now)
                      AND (r.organisation_id IS NULL OR r.organisation_id = :organisation)
                      AND c.risk_level = 'RESTRICTED'
                )
                """, Map.of("user", user.userId(), "organisation", user.activeOrganisationId(), "now", Instant.now(clock)), Boolean.class));
    }

    @Transactional
    public void recordDenied(AuthenticatedUser user) {
        audit.record(user.userId(), "identity.mfa.privileged_access.denied", AuditOutcome.DENIED,
                "Production privileged access requires a verified second factor",
                Map.of("sessionId", user.sessionId(), "organisationId", user.activeOrganisationId()));
    }

    static boolean allowsWithoutVerification(String method, String path) {
        // Preserve the existing public/authentication boundaries, including when a cookie accompanies a public request.
        if (path.startsWith("/api/v1/public/") || (method.equals("GET") &&
                (path.startsWith("/actuator/health") || path.equals("/api/v1/system/status")))) return true;
        if (method.equals("POST") && (path.equals("/api/v1/auth/sessions") || path.equals("/api/v1/auth/mfa/challenges/verify"))) return true;
        return switch (method) {
            case "GET" -> path.equals("/api/v1/context") || path.equals("/api/v1/auth/sessions/current")
                    || path.equals("/api/v1/auth/sessions") || path.equals("/api/v1/auth/mfa") || path.equals("/api/v1/me/preferences");
            case "PUT" -> path.equals("/api/v1/context/organisation") || path.equals("/api/v1/me/preferences");
            case "DELETE" -> path.equals("/api/v1/auth/sessions/current")
                    || path.matches("/api/v1/auth/sessions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
            // The enrollment service rechecks ACTIVE factors under a user-row lock: initial setup only for weak sessions.
            case "POST" -> path.equals("/api/v1/auth/mfa/totp/enrollments")
                    || path.matches("/api/v1/auth/mfa/totp/enrollments/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/verify");
            default -> false;
        };
    }
}
