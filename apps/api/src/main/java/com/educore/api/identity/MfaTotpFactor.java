package com.educore.api.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "mfa_totp_factors")
public class MfaTotpFactor {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 80)
    private String label;

    @Column(name = "secret_ciphertext", nullable = false, length = 200)
    private String secretCiphertext;

    @Column(name = "secret_nonce", nullable = false, length = 60)
    private String secretNonce;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MfaFactorStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "last_used_time_step")
    private Long lastUsedTimeStep;

    protected MfaTotpFactor() {
    }

    private MfaTotpFactor(
            UUID id,
            UUID userId,
            String label,
            SensitiveValueCipher.EncryptedValue secret,
            Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.label = sanitizeLabel(label);
        this.secretCiphertext = secret.ciphertext();
        this.secretNonce = secret.nonce();
        this.status = MfaFactorStatus.PENDING;
        this.createdAt = createdAt;
    }

    static MfaTotpFactor pending(
            UUID id,
            UUID userId,
            String label,
            SensitiveValueCipher.EncryptedValue secret,
            Instant createdAt) {
        return new MfaTotpFactor(id, userId, label, secret, createdAt);
    }

    boolean canVerifyEnrollmentAt(Instant now, Duration enrollmentTtl) {
        return status == MfaFactorStatus.PENDING && createdAt.plus(enrollmentTtl).isAfter(now);
    }

    void activate(Instant now, long verifiedTimeStep) {
        if (status != MfaFactorStatus.PENDING) throw new IllegalStateException("Only a pending MFA factor can be activated");
        status = MfaFactorStatus.ACTIVE;
        verifiedAt = now;
        lastUsedTimeStep = verifiedTimeStep;
    }

    boolean markUsed(long verifiedTimeStep) {
        if (status != MfaFactorStatus.ACTIVE) return false;
        if (lastUsedTimeStep != null && verifiedTimeStep <= lastUsedTimeStep) return false;
        lastUsedTimeStep = verifiedTimeStep;
        return true;
    }

    void revoke() {
        if (status == MfaFactorStatus.ACTIVE) status = MfaFactorStatus.REVOKED;
    }

    void supersedePending() {
        if (status != MfaFactorStatus.PENDING) throw new IllegalStateException("Only pending MFA setup can be superseded");
        status = MfaFactorStatus.REVOKED;
    }

    SensitiveValueCipher.EncryptedValue encryptedSecret() {
        return new SensitiveValueCipher.EncryptedValue(secretCiphertext, secretNonce);
    }

    String associatedData() {
        return userId + ":" + id;
    }

    private static String sanitizeLabel(String value) {
        String labelValue = value == null || value.isBlank() ? "Authenticator app" : value.strip();
        String safe = labelValue.replaceAll("[\\p{Cntrl}]", " ").strip();
        return safe.substring(0, Math.min(safe.length(), 80));
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getLabel() {
        return label;
    }

    public MfaFactorStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }
}
