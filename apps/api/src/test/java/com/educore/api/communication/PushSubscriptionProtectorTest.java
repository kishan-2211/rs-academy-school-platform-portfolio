package com.educore.api.communication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.educore.api.config.ApplicationProperties;
import com.educore.api.identity.SensitiveValueCipher;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PushSubscriptionProtectorTest {

    private final PushSubscriptionProtector protector = new PushSubscriptionProtector(
            new SensitiveValueCipher(properties(), new SecureRandom()));

    @Test
    void bindsEveryEncryptedBrowserCapabilityToOneSubscriptionAndPart() {
        UUID subscriptionId = UUID.randomUUID();
        String endpoint = "https://push.example.org/subscriptions/private-capability";
        var protectedValue = protector.protect(subscriptionId, endpoint, "p256dh-value", "auth-value");

        assertThat(protectedValue.endpoint().ciphertext()).doesNotContain(endpoint);
        assertThat(protector.reveal(subscriptionId, "endpoint", protectedValue.endpoint()))
                .isEqualTo(endpoint);
        assertThatThrownBy(() -> protector.reveal(
                UUID.randomUUID(), "endpoint", protectedValue.endpoint()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> protector.reveal(
                subscriptionId, "auth", protectedValue.endpoint()))
                .isInstanceOf(IllegalStateException.class);
    }

    private static ApplicationProperties properties() {
        var security = new ApplicationProperties.Security(
                "educore_session", false, Duration.ofHours(8), Duration.ofDays(30),
                Duration.ofMinutes(45), "",
                Base64.getEncoder().encodeToString(new byte[32]),
                Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofHours(72), "EduCore");
        return new ApplicationProperties(
                "http://localhost:3000", List.of("http://localhost:3000"),
                Optional.empty(), Optional.empty(), security,
                new ApplicationProperties.Bootstrap(false, "", ""));
    }
}
