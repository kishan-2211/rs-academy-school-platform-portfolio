package com.educore.api.family;

import java.util.UUID;

public record FamilyChildSummary(
        UUID learnerId,
        String learnerReference,
        String legalName,
        String preferredName,
        String schoolName,
        String campusName,
        String academicSessionName,
        String gradeCode,
        String sectionName,
        String rollNumber,
        String relationshipCode) {
}
