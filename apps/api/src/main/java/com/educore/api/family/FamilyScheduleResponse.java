package com.educore.api.family;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record FamilyScheduleResponse(
        UUID learnerId,
        UUID timetablePlanId,
        String timetableName,
        Instant publishedAt,
        List<ScheduleEntry> entries) {

    public FamilyScheduleResponse {
        entries = List.copyOf(entries);
    }

    public record ScheduleEntry(
            UUID id,
            int dayOfWeek,
            int sequenceNumber,
            String periodName,
            LocalTime startsAt,
            LocalTime endsAt,
            String subjectName,
            String subjectShortName,
            String roomLabel) {}
}
