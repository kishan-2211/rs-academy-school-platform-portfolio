package com.educore.api.guardians;

import java.time.Instant;
import java.util.UUID;

public record GuardianPortalInvitationResponse(
        UUID id,
        UUID learnerId,
        UUID guardianId,
        String maskedDestination,
        String status,
        String deliveryStatus,
        Instant expiresAt,
        Instant acceptedAt,
        Instant revokedAt,
        Instant createdAt,
        long version) {
}
