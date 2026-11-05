package com.educore.api.admissions;

import java.util.List;

public record ApplicationOptionsResponse(
        List<ApplicationCampusOption> campuses,
        List<ApplicationSessionOption> academicSessions,
        List<EnquiryAssignee> assignees,
        List<ApplicationGuardianMatches> guardianMatches) {

    public ApplicationOptionsResponse {
        campuses = List.copyOf(campuses);
        academicSessions = List.copyOf(academicSessions);
        assignees = List.copyOf(assignees);
        guardianMatches = List.copyOf(guardianMatches);
    }
}
