package com.educore.api.students.imports;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record ResolveStudentImportRowRequest(
        @NotBlank @Size(max = 20) String learnerResolution,
        @Size(max = 20) String guardianResolution,
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotBlank @Size(max = 240) String reason) {
}
