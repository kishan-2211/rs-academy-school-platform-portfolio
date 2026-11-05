package com.educore.api.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.educore.api.config.ApplicationProperties;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SensitiveValueCipherTest {

    private final SensitiveValueCipher cipher = new SensitiveValueCipher(properties(), new SecureRandom());

    @Test
    void roundTripsASecretOnlyWithTheSameAssociatedIdentity() {
        byte[] plaintext = "private-authenticator-secret".getBytes(StandardCharsets.UTF_8);

        SensitiveValueCipher.EncryptedValue encrypted = cipher.encrypt(plaintext, "user:factor");

        assertThat(encrypted.ciphertext()).doesNotContain("private-authenticator-secret");
        assertThat(cipher.decrypt(encrypted, "user:factor")).isEqualTo(plaintext);
        assertThatThrownBy(() -> cipher.decrypt(encrypted, "another-user:factor"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsOnlyAThirtyTwoByteBase64Key() {
        assertThat(SensitiveValueCipher.isValidKey(Base64.getEncoder().encodeToString(new byte[32]))).isTrue();
        assertThat(SensitiveValueCipher.isValidKey(Base64.getEncoder().encodeToString(new byte[16]))).isFalse();
        assertThat(SensitiveValueCipher.isValidKey("not-base64")).isFalse();
    }

    @Test
    void rejectsUnsafeGuardianInvitationLifetimes() {
        assertThatThrownBy(() -> new ApplicationProperties.Security(
                "educore_session",
                false,
                Duration.ofHours(8),
                Duration.ofDays(30),
                Duration.ofMinutes(45),
                "",
                Base64.getEncoder().encodeToString(new byte[32]),
                Duration.ofMinutes(5),
                Duration.ofMinutes(10),
                Duration.ofDays(8),
                "EduCore"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no longer than seven days");
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
