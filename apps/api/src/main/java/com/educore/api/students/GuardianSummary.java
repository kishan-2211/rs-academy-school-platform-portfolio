package com.educore.api.students;

import java.util.UUID;

public record GuardianSummary(
        UUID guardianId,
        String displayName,
        String relationshipCode,
        String email,
        String phone,
        boolean primaryContact,
        boolean pickupAuthorized,
        String portalAccessStatus,
        String portalReadiness,
        long linkedLearnerCount,
        long guardianVersion,
        long relationshipVersion) {
}
