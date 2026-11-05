package com.educore.api.students.imports;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record StudentImportBatchSummary(
        UUID id,
        UUID schoolId,
        String schoolName,
        UUID campusId,
        String campusName,
        UUID academicSessionId,
        String academicSessionName,
        LocalDate startsOn,
        String sourceFileName,
        String fileSha256,
        String status,
        int totalRows,
        int readyRows,
        int reviewRows,
        int invalidRows,
        int skippedRows,
        int committedRows,
        Instant createdAt,
        Instant committedAt,
        long version) {
}
