package com.educore.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MfaStateTest {

    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    @Test
    void locksAChallengeAfterFiveFailuresAndNeverReopensIt() {
        MfaLoginChallenge challenge = MfaLoginChallenge.create(
                UUID.randomUUID(), "a".repeat(64), false, "Browser", NOW, Duration.ofMinutes(5));

        for (int attempt = 0; attempt < 5; attempt += 1) challenge.recordFailure(NOW.plusSeconds(attempt));

        assertThat(challenge.isUsableAt(NOW.plusSeconds(10))).isFalse();
        assertThat(challenge.isUsableAt(NOW.minusSeconds(1))).isFalse();
    }

    @Test
    void preventsTotpReplayAndAcceptsOnlyNewerTimeSteps() {
        UUID userId = UUID.randomUUID();
        UUID factorId = UUID.randomUUID();
        var encrypted = new SensitiveValueCipher.EncryptedValue("ciphertext", "nonce");
        MfaTotpFactor factor = MfaTotpFactor.pending(factorId, userId, "Phone", encrypted, NOW);

        factor.activate(NOW, 100L);

        assertThat(factor.markUsed(100L)).isFalse();
        assertThat(factor.markUsed(99L)).isFalse();
        assertThat(factor.markUsed(101L)).isTrue();
    }

    @Test
    void expiresPendingEnrollmentAtTheConfiguredBoundary() {
        var factor = MfaTotpFactor.pending(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Phone",
                new SensitiveValueCipher.EncryptedValue("ciphertext", "nonce"),
                NOW);

        assertThat(factor.canVerifyEnrollmentAt(NOW.plusSeconds(599), Duration.ofMinutes(10))).isTrue();
        assertThat(factor.canVerifyEnrollmentAt(NOW.plusSeconds(600), Duration.ofMinutes(10))).isFalse();
    }
}
