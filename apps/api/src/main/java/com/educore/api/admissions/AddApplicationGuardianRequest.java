package com.educore.api.admissions;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record AddApplicationGuardianRequest(
        @NotNull @Valid GuardianInput guardian,
        @NotNull @PositiveOrZero Long expectedVersion) {
}
