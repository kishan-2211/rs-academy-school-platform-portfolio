package com.educore.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class UserSessionTest {

    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");

    @Test
    void respectsIdleAndAbsoluteExpiryAndCapturesMfaState() {
        UserSession session = UserSession.create(
                UserAccount.active("admin@example.com", "Admin", "hash"),
                "a".repeat(64),
                NOW,
                Duration.ofHours(8),
                Duration.ofMinutes(45),
                "Mozilla/5.0 (Windows NT 10.0) Chrome/140.0",
                true);

        assertThat(session.isValidAt(NOW.plus(Duration.ofMinutes(44)))).isTrue();
        assertThat(session.isValidAt(NOW.plus(Duration.ofMinutes(46)))).isFalse();
        assertThat(session.isMfaVerified()).isTrue();
        assertThat(session.getDeviceName()).isEqualTo("Chrome on Windows");
    }

    @Test
    void touchExtendsIdleExpiryButNeverPastTheAbsoluteExpiry() {
        UserSession session = UserSession.create(
                UserAccount.active("admin@example.com", "Admin", "hash"),
                "b".repeat(64),
                NOW,
                Duration.ofHours(1),
                Duration.ofMinutes(45),
                null,
                false);

        session.touch(NOW.plus(Duration.ofMinutes(30)), Duration.ofMinutes(45));

        assertThat(session.isValidAt(NOW.plusSeconds(3599))).isTrue();
        assertThat(session.isValidAt(NOW.plusSeconds(3601))).isFalse();
    }
}
