package com.educore.api.config;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record ApplicationProperties(
        String publicUrl,
        List<String> allowedOrigins,
        Optional<UUID> publicOrganisationId,
        Optional<UUID> publicSchoolId,
        Security security,
        Bootstrap bootstrap) {

    public record Security(
            String sessionCookieName,
            boolean cookieSecure,
            Duration sessionTtl,
            Duration rememberedSessionTtl,
            Duration idleTimeout,
            String cookieDomain,
            String encryptionKey,
            Duration mfaChallengeTtl,
            Duration mfaEnrollmentTtl,
            Duration guardianInvitationTtl,
            String totpIssuer) {

        public Security {
            if (guardianInvitationTtl == null
                    || guardianInvitationTtl.isZero()
                    || guardianInvitationTtl.isNegative()
                    || guardianInvitationTtl.compareTo(Duration.ofDays(7)) > 0) {
                throw new IllegalArgumentException(
                        "Guardian invitation TTL must be greater than zero and no longer than seven days");
            }
        }
    }

    public record Bootstrap(
            boolean enabled,
            String adminEmail,
            String adminPassword) {
    }
}
