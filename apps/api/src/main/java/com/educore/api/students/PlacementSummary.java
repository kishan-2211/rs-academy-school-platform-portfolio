package com.educore.api.students;

import java.time.LocalDate;
import java.util.UUID;

public record PlacementSummary(
        UUID placementId,
        UUID sectionId,
        String sectionCode,
        String sectionName,
        String rollNumber,
        LocalDate startsOn,
        LocalDate endsOn,
        String status,
        String placementReason,
        String endReason,
        long version) {
}
