package com.educore.api.admissions;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ApplicationDetailResponse(
        UUID id,
        UUID schoolId,
        String reference,
        UUID enquiryId,
        String enquiryReference,
        UUID targetCampusId,
        String targetCampusName,
        UUID targetAcademicSessionId,
        String targetAcademicSessionName,
        String intakeSessionLabel,
        String targetGradeCode,
        String applicantLegalName,
        String applicantPreferredName,
        LocalDate birthDate,
        ApplicantGender gender,
        String genderDescription,
        ApplicationStatus status,
        EnquiryAssignee assignee,
        Instant submittedAt,
        Instant acceptedAt,
        Instant withdrawnAt,
        Instant enrolledAt,
        Instant createdAt,
        Instant updatedAt,
        long version,
        List<ApplicationGuardianResponse> guardians,
        List<ApplicationDocumentResponse> documents,
        List<AdmissionDecisionResponse> decisions,
        EnrolmentSummary enrolment,
        AdmissionType admissionType,
        String rteReference) {

    public ApplicationDetailResponse {
        guardians = List.copyOf(guardians);
        documents = List.copyOf(documents);
        decisions = List.copyOf(decisions);
    }
}
