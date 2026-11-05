package com.educore.api.schoolcalendar;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PublishedSchoolCalendarResponse(
        UUID learnerId,
        UUID schoolId,
        UUID campusId,
        UUID academicSessionId,
        String academicSessionName,
        LocalDate sessionStartsOn,
        LocalDate sessionEndsOn,
        String timeZone,
        LocalDate today,
        List<PublishedDay> days) {

    public record PublishedDay(
            UUID calendarDayId,
            UUID noticeRevisionId,
            LocalDate calendarDate,
            String dayType,
            String noticeKind,
            String titleEn,
            String titleHi,
            String bodyEn,
            String bodyHi,
            boolean attendanceRequired,
            Instant publishedAt) {}
}
