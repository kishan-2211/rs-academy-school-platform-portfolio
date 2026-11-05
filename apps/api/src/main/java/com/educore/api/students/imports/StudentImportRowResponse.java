package com.educore.api.students.imports;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record StudentImportRowResponse(
        UUID id,
        int sourceRowNumber,
        String legalName,
        String preferredName,
        LocalDate birthDate,
        String genderCode,
        String genderDescription,
        String gradeCode,
        String admissionNumber,
        String guardianName,
        String guardianExternalKey,
        String guardianRelationshipCode,
        String guardianEmail,
        String guardianPhone,
        boolean primaryContact,
        boolean pickupAuthorized,
        String status,
        List<String> validationErrors,
        DuplicateLearnerCandidate learnerCandidate,
        DuplicateGuardianCandidate guardianCandidate,
        String learnerResolution,
        String guardianResolution,
        String resolutionReason,
        UUID resolvedGuardianId,
        UUID createdLearnerId,
        UUID createdEnrolmentId,
        long version) {

    public StudentImportRowResponse {
        validationErrors = List.copyOf(validationErrors);
    }
}
