package com.educore.api.students;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

public record PlaceStudentRequest(
        @NotNull UUID sectionId,
        @Size(max = 30) String rollNumber,
        @NotNull LocalDate startsOn,
        @NotNull @PositiveOrZero Long expectedEnrolmentVersion,
        @NotBlank @Size(max = 240) String changeReason) {
}
