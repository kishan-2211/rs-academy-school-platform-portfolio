package com.educore.api.admissions;

import java.util.UUID;

public record ApplicationGuardianResponse(
        UUID id,
        String displayName,
        String relationshipCode,
        String email,
        String phone,
        boolean primaryContact,
        boolean pickupAuthorized,
        UUID matchedGuardianId,
        long version) {
}
