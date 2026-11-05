package com.educore.api.schoolcalendar;

import java.time.Instant;
import java.util.UUID;

public record SchoolCalendarMutationResponse(
        UUID id,
        UUID noticeSeriesId,
        int revisionNumber,
        long rowVersion,
        String status,
        Instant notificationScheduledFor) {}
