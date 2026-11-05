package com.educore.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.educore.api.config.ApplicationProperties;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InvitationTokenProtectorTest {

    private final InvitationTokenProtector protector = new InvitationTokenProtector(
            new SensitiveValueCipher(properties(), new SecureRandom()));

    @Test
    void protectsDeliveryMaterialForExactlyOneInvitation() {
        String rawToken = "guardian-invitation-token-that-must-not-appear-in-storage";

        InvitationTokenProtector.ProtectedToken protectedToken =
                protector.protect(rawToken, "invitation-one");

        assertThat(protectedToken.ciphertext()).doesNotContain(rawToken);
        assertThat(protector.revealForDelivery(protectedToken, "invitation-one"))
                .isEqualTo(rawToken);
        assertThatThrownBy(() -> protector.revealForDelivery(protectedToken, "invitation-two"))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ApplicationProperties properties() {
        var security = new ApplicationProperties.Security(
                "educore_session",
                false,
                Duration.ofHours(8),
                Duration.ofDays(30),
                Duration.ofMinutes(45),
                "",
                Base64.getEncoder().encodeToString(new byte[32]),
                Duration.ofMinutes(5),
                Duration.ofMinutes(10),
                Duration.ofHours(72),
                "EduCore");
        return new ApplicationProperties(
                "http://localhost:3000",
                List.of("http://localhost:3000"),
                Optional.empty(),
                Optional.empty(),
                security,
                new ApplicationProperties.Bootstrap(false, "", ""));
    }
}
