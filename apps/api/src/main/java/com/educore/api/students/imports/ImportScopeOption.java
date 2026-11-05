package com.educore.api.students.imports;

import java.time.LocalDate;
import java.util.UUID;

public record ImportScopeOption(
        UUID schoolId,
        String schoolName,
        UUID campusId,
        String campusCode,
        String campusName,
        UUID academicSessionId,
        String academicSessionName,
        LocalDate sessionStartsOn,
        LocalDate sessionEndsOn) {
}
