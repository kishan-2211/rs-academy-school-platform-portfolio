package com.educore.api.students;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record UpdateStudentProfileRequest(
        @NotBlank @Size(max = 120) String legalName,
        @Size(max = 120) String preferredName,
        @NotNull @Past LocalDate birthDate,
        @NotBlank @Size(max = 20) String genderCode,
        @Size(max = 80) String genderDescription,
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotBlank @Size(max = 240) String correctionReason) {
}
