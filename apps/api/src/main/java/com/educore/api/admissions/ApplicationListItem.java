package com.educore.api.admissions;

import java.time.Instant;
import java.util.UUID;

public record ApplicationListItem(
        UUID id,
        String reference,
        String applicantLegalName,
        String applicantPreferredName,
        String targetGradeCode,
        String intakeSessionLabel,
        ApplicationStatus status,
        EnquiryAssignee assignee,
        String enquiryReference,
        long requiredDocumentCount,
        long verifiedDocumentCount,
        Instant createdAt,
        Instant updatedAt,
        long version,
        AdmissionType admissionType) {
}
