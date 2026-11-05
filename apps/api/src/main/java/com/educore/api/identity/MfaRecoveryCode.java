package com.educore.api.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "mfa_recovery_codes")
public class MfaRecoveryCode {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "code_hash", nullable = false, unique = true, length = 64, columnDefinition = "char(64)")
    private String codeHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected MfaRecoveryCode() {
    }

    private MfaRecoveryCode(UUID userId, String codeHash, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.codeHash = codeHash;
        this.createdAt = createdAt;
    }

    static MfaRecoveryCode create(UUID userId, String codeHash, Instant createdAt) {
        return new MfaRecoveryCode(userId, codeHash, createdAt);
    }

    void use(Instant now) {
        if (usedAt != null || revokedAt != null) throw new IllegalStateException("Recovery code is no longer available");
        usedAt = now;
    }

    void revokeUnused(Instant now) {
        if (usedAt == null && revokedAt == null) revokedAt = now;
    }
}
