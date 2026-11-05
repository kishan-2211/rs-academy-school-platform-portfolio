package com.educore.api.admissions;

import java.time.LocalDate;
import java.util.UUID;

public record ApplicationSessionOption(
        UUID id,
        String name,
        LocalDate startsOn,
        LocalDate endsOn,
        String status,
        int rteSeatLimit,
        long rteSeatsUsed,
        int rteMinAgeYears,
        int rteMaxAgeYears) {
}
