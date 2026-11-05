package com.educore.api.admissions;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record EnrolApplicationRequest(
        @NotNull UUID campusId,
        @NotNull UUID academicSessionId,
        @NotNull LocalDate startsOn,
        @Size(max = 80) String admissionNumber,
        @Size(max = 10) List<@Valid GuardianMatchInput> guardianMatches,
        @NotNull @PositiveOrZero Long expectedVersion) {
}
