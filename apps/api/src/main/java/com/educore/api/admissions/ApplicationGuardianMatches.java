package com.educore.api.admissions;

import java.util.List;
import java.util.UUID;

public record ApplicationGuardianMatches(
        UUID applicationGuardianId,
        String applicationGuardianName,
        List<GuardianMatchCandidate> candidates) {

    public ApplicationGuardianMatches {
        candidates = List.copyOf(candidates);
    }
}
