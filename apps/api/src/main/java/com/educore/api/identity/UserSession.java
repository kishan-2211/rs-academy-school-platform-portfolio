package com.educore.api.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "user_sessions")
public class UserSession {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount user;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, unique = true, length = 64, columnDefinition = "char(64)")
    private String tokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "idle_expires_at", nullable = false)
    private Instant idleExpiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "user_agent_summary", length = 200)
    private String userAgentSummary;

    @Column(name = "mfa_verified_at")
    private Instant mfaVerifiedAt;

    @Column(name = "active_organisation_id")
    private UUID activeOrganisationId;

    @Column(name = "device_name", length = 120)
    private String deviceName;

    protected UserSession() {
    }

    private UserSession(
            UUID id,
            UserAccount user,
            String tokenHash,
            Instant now,
            Instant expiresAt,
            Instant idleExpiresAt,
            String userAgentSummary,
            boolean mfaVerified) {
        this.id = id;
        this.user = user;
        this.tokenHash = tokenHash;
        this.createdAt = now;
        this.lastSeenAt = now;
        this.expiresAt = expiresAt;
        this.idleExpiresAt = idleExpiresAt;
        this.userAgentSummary = userAgentSummary;
        this.deviceName = describeDevice(userAgentSummary);
        this.mfaVerifiedAt = mfaVerified ? now : null;
    }

    public static UserSession create(
            UserAccount user,
            String tokenHash,
            Instant now,
            Duration absoluteTtl,
            Duration idleTimeout,
            String userAgentSummary,
            boolean mfaVerified) {
        return new UserSession(
                UUID.randomUUID(),
                user,
                tokenHash,
                now,
                now.plus(absoluteTtl),
                now.plus(idleTimeout),
                sanitizeUserAgent(userAgentSummary),
                mfaVerified);
    }

    public boolean isValidAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now) && idleExpiresAt.isAfter(now)
                && user.canAuthenticateAt(now);
    }

    public void touch(Instant now, Duration idleTimeout) {
        if (lastSeenAt.plusSeconds(300).isBefore(now)) {
            lastSeenAt = now;
            Instant proposedIdleExpiry = now.plus(idleTimeout);
            idleExpiresAt = proposedIdleExpiry.isBefore(expiresAt) ? proposedIdleExpiry : expiresAt;
        }
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }

    private static String sanitizeUserAgent(String value) {
        if (value == null || value.isBlank()) return null;
        String safe = value.replaceAll("[\\p{Cntrl}]", " ").strip();
        return safe.substring(0, Math.min(safe.length(), 200));
    }

    private static String describeDevice(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return "Unknown device";
        String browser = userAgent.contains("Edg/") ? "Edge"
                : userAgent.contains("Chrome/") ? "Chrome"
                : userAgent.contains("Firefox/") ? "Firefox"
                : userAgent.contains("Safari/") ? "Safari"
                : "Browser";
        String platform = userAgent.contains("Windows") ? "Windows"
                : userAgent.contains("Android") ? "Android"
                : userAgent.contains("iPhone") || userAgent.contains("iPad") ? "iOS"
                : userAgent.contains("Macintosh") ? "macOS"
                : userAgent.contains("Linux") ? "Linux"
                : "device";
        return browser + " on " + platform;
    }

    public UUID getId() {
        return id;
    }

    public UserAccount getUser() {
        return user;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public boolean isMfaVerified() {
        return mfaVerifiedAt != null;
    }

    public UUID getActiveOrganisationId() {
        return activeOrganisationId;
    }

    public void selectOrganisation(UUID organisationId) {
        this.activeOrganisationId = organisationId;
    }
}
