package com.educore.api.admissions;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record RecordAdmissionDecisionRequest(
        @NotNull AdmissionDecision decision,
        @NotBlank @Size(max = 60) String reasonCode,
        @Size(max = 1000) String internalSummary,
        @Future LocalDate offerExpiresOn,
        @NotNull @PositiveOrZero Long expectedVersion) {
}
