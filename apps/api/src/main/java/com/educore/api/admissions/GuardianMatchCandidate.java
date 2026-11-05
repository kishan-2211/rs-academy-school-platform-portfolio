package com.educore.api.admissions;

import java.util.UUID;

public record GuardianMatchCandidate(
        UUID guardianId,
        String displayName,
        String maskedEmail,
        String maskedPhone) {
}
