package com.educore.api.admissions;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record UpdateApplicationStatusRequest(
        @NotNull ApplicationStatus status,
        @NotNull @PositiveOrZero Long expectedVersion) {
}
