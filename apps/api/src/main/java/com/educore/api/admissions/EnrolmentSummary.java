package com.educore.api.admissions;

import java.time.LocalDate;
import java.util.UUID;

public record EnrolmentSummary(
        UUID learnerId,
        UUID enrolmentId,
        String learnerReference,
        String enrolmentReference,
        String admissionNumber,
        UUID campusId,
        String campusName,
        UUID academicSessionId,
        String academicSessionName,
        String gradeCode,
        String placementStatus,
        LocalDate startsOn) {
}
