package com.educore.api.students.imports;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PreviewStudentImportRequest(
        @NotNull UUID campusId,
        @NotNull UUID academicSessionId,
        @NotNull LocalDate startsOn,
        @NotBlank @Size(max = 255) String sourceFileName,
        @NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String fileSha256,
        @NotNull @Size(min = 1, max = 2000) List<@Valid StudentImportRowInput> rows) {

    public PreviewStudentImportRequest {
        rows = rows == null ? null : List.copyOf(rows);
    }
}
