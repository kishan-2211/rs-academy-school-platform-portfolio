package com.educore.api.students.imports;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record StudentImportRowInput(
        @Min(2) @Max(100000) int sourceRowNumber,
        @Size(max = 120) String legalName,
        @Size(max = 120) String preferredName,
        @Size(max = 20) String birthDate,
        @Size(max = 20) String genderCode,
        @Size(max = 80) String genderDescription,
        @Size(max = 40) String gradeCode,
        @Size(max = 80) String admissionNumber,
        @Size(max = 120) String guardianName,
        @Size(max = 80) String guardianExternalKey,
        @Size(max = 40) String guardianRelationshipCode,
        @Size(max = 254) String guardianEmail,
        @Size(max = 30) String guardianPhone,
        @NotNull Boolean primaryContact,
        @NotNull Boolean pickupAuthorized) {
}
