package com.educore.api.admissions;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record GuardianMatchInput(
        @NotNull UUID applicationGuardianId,
        UUID existingGuardianId) {
}
