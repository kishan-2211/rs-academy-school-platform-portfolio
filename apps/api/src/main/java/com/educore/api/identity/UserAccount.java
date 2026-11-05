package com.educore.api.identity;

import com.educore.api.common.persistence.AuditedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "user_accounts")
public class UserAccount extends AuditedEntity {

    @Id
    private UUID id;

    @Column(name = "login_identifier_normalized", nullable = false, unique = true, length = 254)
    private String loginIdentifierNormalized;

    @Column(name = "email_normalized", unique = true, length = 254)
    private String emailNormalized;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "preferred_language", length = 16)
    private String preferredLanguage;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    protected UserAccount() {
    }

    private UserAccount(
            UUID id,
            String loginIdentifierNormalized,
            String emailNormalized,
            String displayName,
            String passwordHash) {
        this.id = id;
        this.loginIdentifierNormalized = normalizeLoginIdentifier(loginIdentifierNormalized);
        this.emailNormalized = emailNormalized == null ? null : normalizeEmail(emailNormalized);
        this.displayName = displayName.trim();
        this.passwordHash = passwordHash;
        this.status = UserStatus.ACTIVE;
    }

    public static UserAccount active(String email, String displayName, String passwordHash) {
        String normalizedEmail = normalizeEmail(email);
        return new UserAccount(UUID.randomUUID(), normalizedEmail, normalizedEmail, displayName, passwordHash);
    }

    public static UserAccount activeWithLoginIdentifier(
            String loginIdentifier, String displayName, String passwordHash) {
        return new UserAccount(UUID.randomUUID(), loginIdentifier, null, displayName, passwordHash);
    }

    public boolean canAuthenticateAt(Instant now) {
        return status == UserStatus.ACTIVE && (lockedUntil == null || !lockedUntil.isAfter(now));
    }

    public void recordFailedLogin(Instant now) {
        failedLoginCount += 1;
        if (failedLoginCount >= 5) {
            lockedUntil = now.plusSeconds(15 * 60L);
            failedLoginCount = 0;
        }
    }

    public void recordSuccessfulLogin(Instant now) {
        recordSuccessfulPassword();
        lastLoginAt = now;
    }

    public void recordSuccessfulPassword() {
        failedLoginCount = 0;
        lockedUntil = null;
    }

    public static String normalizeEmail(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    public static String normalizeLoginIdentifier(String identifier) {
        return identifier == null ? "" : identifier.strip().toLowerCase(Locale.ROOT);
    }

    public UUID getId() {
        return id;
    }

    public String getEmailNormalized() {
        return emailNormalized;
    }

    public String getLoginIdentifierNormalized() {
        return loginIdentifierNormalized;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getPreferredLanguage() {
        return preferredLanguage;
    }

    public void choosePreferredLanguage(String language) {
        if (!"en".equals(language) && !"hi".equals(language)) {
            throw new IllegalArgumentException("Unsupported language");
        }
        preferredLanguage = language;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public UserStatus getStatus() {
        return status;
    }
}
