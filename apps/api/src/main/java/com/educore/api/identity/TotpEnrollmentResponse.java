package com.educore.api.identity;

import java.time.Instant;
import java.util.UUID;

public record TotpEnrollmentResponse(
        UUID factorId,
        String secret,
        String provisioningUri,
        Instant expiresAt) {
}
