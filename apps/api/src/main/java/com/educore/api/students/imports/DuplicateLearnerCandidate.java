package com.educore.api.students.imports;

import java.time.LocalDate;
import java.util.UUID;

public record DuplicateLearnerCandidate(
        UUID learnerId,
        String learnerReference,
        String legalName,
        LocalDate birthDate,
        String admissionNumber,
        String matchReason) {
}
