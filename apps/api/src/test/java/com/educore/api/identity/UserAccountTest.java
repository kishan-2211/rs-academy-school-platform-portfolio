package com.educore.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class UserAccountTest {

    @Test
    void normalizesEmailAndStartsActive() {
        UserAccount user = UserAccount.active("  ADMIN@Example.COM ", "Admin", "hash");

        assertThat(user.getLoginIdentifierNormalized()).isEqualTo("admin@example.com");
        assertThat(user.getEmailNormalized()).isEqualTo("admin@example.com");
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.canAuthenticateAt(Instant.parse("2026-08-20T00:00:00Z"))).isTrue();
    }

    @Test
    void supportsASchoolIssuedLearnerLoginWithoutInventingAnEmailAddress() {
        UserAccount user = UserAccount.activeWithLoginIdentifier("  RSA.STU-0042 ", "Learner", "hash");

        assertThat(user.getLoginIdentifierNormalized()).isEqualTo("rsa.stu-0042");
        assertThat(user.getEmailNormalized()).isNull();
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void locksAfterFiveFailedLoginsAndClearsAfterTheWindow() {
        UserAccount user = UserAccount.active("admin@example.com", "Admin", "hash");
        Instant now = Instant.parse("2026-08-20T00:00:00Z");

        for (int attempt = 0; attempt < 5; attempt += 1) {
            user.recordFailedLogin(now);
        }

        assertThat(user.canAuthenticateAt(now.plusSeconds(899))).isFalse();
        assertThat(user.canAuthenticateAt(now.plusSeconds(901))).isTrue();
    }

    @Test
    void successfulLoginClearsPreviousFailures() {
        UserAccount user = UserAccount.active("admin@example.com", "Admin", "hash");
        Instant now = Instant.parse("2026-08-20T00:00:00Z");
        user.recordFailedLogin(now);
        user.recordSuccessfulLogin(now.plusSeconds(1));

        for (int attempt = 0; attempt < 4; attempt += 1) {
            user.recordFailedLogin(now.plusSeconds(2));
        }

        assertThat(user.canAuthenticateAt(now.plusSeconds(3))).isTrue();
    }
}
