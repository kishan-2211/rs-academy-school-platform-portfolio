package com.educore.api.students.imports;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record CommitStudentImportRequest(@NotNull @PositiveOrZero Long expectedVersion) {
}
