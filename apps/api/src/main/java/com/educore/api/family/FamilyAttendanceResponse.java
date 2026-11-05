package com.educore.api.family;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record FamilyAttendanceResponse(
        UUID learnerId,
        LocalDate from,
        LocalDate to,
        Summary summary,
        List<AttendanceRecord> records) {

    public FamilyAttendanceResponse {
        records = List.copyOf(records);
    }

    public record Summary(
            int recordedMarks,
            BigDecimal earnedCredits,
            BigDecimal attendancePercent,
            BigDecimal warningThresholdPercent,
            boolean belowConfiguredWarning,
            Map<String, Integer> stateCounts) {

        public Summary {
            stateCounts = Map.copyOf(stateCounts);
        }
    }

    public record AttendanceRecord(
            UUID markId,
            LocalDate date,
            String registerMode,
            String periodName,
            LocalTime startsAt,
            LocalTime endsAt,
            String stateCode,
            BigDecimal creditWeight,
            LocalDate explanationAllowedUntil,
            boolean canExplain,
            UUID explanationId,
            String explanationStatus) {}
}
