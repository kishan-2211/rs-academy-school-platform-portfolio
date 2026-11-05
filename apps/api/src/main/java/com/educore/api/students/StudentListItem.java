package com.educore.api.students;

import java.time.LocalDate;
import java.util.UUID;

public record StudentListItem(
        UUID learnerId,
        String learnerReference,
        String legalName,
        String preferredName,
        LocalDate birthDate,
        String learnerStatus,
        UUID enrolmentId,
        String enrolmentReference,
        String admissionNumber,
        UUID campusId,
        String campusName,
        UUID academicSessionId,
        String academicSessionName,
        String gradeCode,
        String placementStatus,
        UUID sectionId,
        String sectionCode,
        String sectionName,
        String rollNumber,
        long guardianCount,
        long portalReadyGuardianCount,
        long learnerVersion,
        long enrolmentVersion,
        String admissionType,
        UUID rteAdmissionSessionId,
        String rteAdmissionSessionName,
        String rteReference) {
}
