package com.educore.api.students;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateSectionRequest(
        @NotNull UUID campusId,
        @NotNull UUID academicSessionId,
        @NotBlank @Size(max = 40) String gradeCode,
        @NotBlank @Size(max = 30) String code,
        @NotBlank @Size(max = 100) String displayName,
        @Min(1) @Max(500) Integer capacity) {
}
