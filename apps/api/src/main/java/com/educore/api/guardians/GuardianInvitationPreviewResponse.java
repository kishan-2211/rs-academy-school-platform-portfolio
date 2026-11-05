package com.educore.api.guardians;

import java.time.Instant;

public record GuardianInvitationPreviewResponse(
        String schoolName,
        String maskedDestination,
        Instant expiresAt,
        boolean existingAccount,
        String status) {
}
