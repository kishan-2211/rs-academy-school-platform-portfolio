package com.educore.api.admissions;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CreateApplicationRequest(
        UUID schoolId,
        UUID enquiryId,
        UUID targetCampusId,
        UUID targetAcademicSessionId,
        @NotBlank @Size(max = 80) String intakeSessionLabel,
        @NotBlank @Size(max = 40) String targetGradeCode,
        @NotBlank @Size(max = 120) String applicantLegalName,
        @Size(max = 120) String applicantPreferredName,
        @NotNull @Past LocalDate birthDate,
        @NotNull ApplicantGender gender,
        @Size(max = 80) String genderDescription,
        @Size(max = 10) List<@Valid GuardianInput> guardians,
        AdmissionType admissionType,
        @Size(max = 100) String rteReference) {
    public CreateApplicationRequest(UUID schoolId, UUID enquiryId, UUID targetCampusId,
            UUID targetAcademicSessionId, String intakeSessionLabel, String targetGradeCode,
            String applicantLegalName, String applicantPreferredName, LocalDate birthDate,
            ApplicantGender gender, String genderDescription, List<GuardianInput> guardians) {
        this(schoolId, enquiryId, targetCampusId, targetAcademicSessionId, intakeSessionLabel,
                targetGradeCode, applicantLegalName, applicantPreferredName, birthDate, gender,
                genderDescription, guardians, AdmissionType.REGULAR, null);
    }
}
