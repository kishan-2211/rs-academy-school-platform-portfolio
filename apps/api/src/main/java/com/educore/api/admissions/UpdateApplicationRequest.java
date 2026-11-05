package com.educore.api.admissions;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

public record UpdateApplicationRequest(
        UUID targetCampusId,
        UUID targetAcademicSessionId,
        @NotBlank @Size(max = 80) String intakeSessionLabel,
        @NotBlank @Size(max = 40) String targetGradeCode,
        @NotBlank @Size(max = 120) String applicantLegalName,
        @Size(max = 120) String applicantPreferredName,
        @NotNull @Past LocalDate birthDate,
        @NotNull ApplicantGender gender,
        @Size(max = 80) String genderDescription,
        UUID assignedMembershipId,
        @NotNull @PositiveOrZero Long expectedVersion,
        AdmissionType admissionType,
        @Size(max = 100) String rteReference) {
    public UpdateApplicationRequest(UUID targetCampusId, UUID targetAcademicSessionId,
            String intakeSessionLabel, String targetGradeCode, String applicantLegalName,
            String applicantPreferredName, LocalDate birthDate, ApplicantGender gender,
            String genderDescription, UUID assignedMembershipId, Long expectedVersion) {
        this(targetCampusId, targetAcademicSessionId, intakeSessionLabel, targetGradeCode,
                applicantLegalName, applicantPreferredName, birthDate, gender, genderDescription,
                assignedMembershipId, expectedVersion, null, null);
    }
}
