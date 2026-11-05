package com.educore.api.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TotpServiceTest {

    private final TotpService service = new TotpService(new SecureRandom());

    @Test
    void matchesTheRfc6238Sha1VectorUsingSixDigits() {
        byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

        var match = service.matchingTimeStep(secret, "287082", Instant.ofEpochSecond(59));

        assertThat(match).isPresent();
        assertThat(match.getAsLong()).isEqualTo(1L);
        assertThat(TotpService.encodeBase32(secret)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
    }

    @Test
    void rejectsMalformedAndOutOfWindowCodes() {
        byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

        assertThat(service.matchingTimeStep(secret, "not-a-code", Instant.ofEpochSecond(59))).isEmpty();
        assertThat(service.matchingTimeStep(secret, "287082", Instant.ofEpochSecond(300))).isEmpty();
    }

    @Test
    void createsAnInteroperableProvisioningUriWithoutLeakingSpaces() {
        String uri = service.provisioningUri("EduCore School", "Admin@Example.com", "ABC234");

        assertThat(uri)
                .startsWith("otpauth://totp/EduCore%20School%3Aadmin%40example.com")
                .contains("secret=ABC234")
                .contains("issuer=EduCore%20School")
                .contains("algorithm=SHA1&digits=6&period=30");
    }
}
