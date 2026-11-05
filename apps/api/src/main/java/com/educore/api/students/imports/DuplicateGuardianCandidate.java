package com.educore.api.students.imports;

import java.util.UUID;

public record DuplicateGuardianCandidate(
        UUID guardianId,
        String displayName,
        String maskedEmail,
        String maskedPhone,
        String matchReason) {
}
