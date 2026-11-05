package com.educore.api.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "mfa_login_challenges")
public class MfaLoginChallenge {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, unique = true, length = 64, columnDefinition = "char(64)")
    private String tokenHash;

    @Column(name = "remember_session", nullable = false)
    private boolean rememberSession;

    @Column(name = "user_agent_summary", length = 200)
    private String userAgentSummary;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "attempt_count", nullable = false)
    private short attemptCount;

    protected MfaLoginChallenge() {
    }

    private MfaLoginChallenge(
            UUID userId,
            String tokenHash,
            boolean rememberSession,
            String userAgentSummary,
            Instant now,
            Duration ttl) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.rememberSession = rememberSession;
        this.userAgentSummary = sanitizeUserAgent(userAgentSummary);
        this.createdAt = now;
        this.expiresAt = now.plus(ttl);
    }

    static MfaLoginChallenge create(
            UUID userId,
            String tokenHash,
            boolean rememberSession,
            String userAgentSummary,
            Instant now,
            Duration ttl) {
        return new MfaLoginChallenge(userId, tokenHash, rememberSession, userAgentSummary, now, ttl);
    }

    boolean isUsableAt(Instant now) {
        return consumedAt == null && expiresAt.isAfter(now) && attemptCount < 5;
    }

    void recordFailure(Instant now) {
        if (attemptCount < 5) attemptCount += 1;
        if (attemptCount >= 5) consumedAt = now;
    }

    void consume(Instant now) {
        if (consumedAt != null) throw new IllegalStateException("MFA challenge has already been consumed");
        consumedAt = now;
    }

    private static String sanitizeUserAgent(String value) {
        if (value == null || value.isBlank()) return null;
        String safe = value.replaceAll("[\\p{Cntrl}]", " ").strip();
        return safe.substring(0, Math.min(safe.length(), 200));
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public boolean isRememberSession() {
        return rememberSession;
    }

    public String getUserAgentSummary() {
        return userAgentSummary;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
