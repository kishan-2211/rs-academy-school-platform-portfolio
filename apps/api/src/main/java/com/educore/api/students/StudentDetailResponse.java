package com.educore.api.students;

import java.util.List;

public record StudentDetailResponse(
        StudentListItem student,
        String genderCode,
        String genderDescription,
        List<GuardianSummary> guardians,
        List<PlacementSummary> placementHistory) {

    public StudentDetailResponse {
        guardians = List.copyOf(guardians);
        placementHistory = List.copyOf(placementHistory);
    }
}
