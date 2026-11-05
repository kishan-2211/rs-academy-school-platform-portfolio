package com.educore.api.academics;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record TeacherAcademicSnapshot(
        UUID schoolId,
        String schoolName,
        List<TeachingGroup> teachingGroups,
        List<PublishedScheduleEntry> scheduleEntries) {

    public TeacherAcademicSnapshot {
        teachingGroups = List.copyOf(teachingGroups);
        scheduleEntries = List.copyOf(scheduleEntries);
    }

    public record TeachingGroup(
            UUID allocationId,
            UUID offeringId,
            UUID academicSessionId,
            String academicSessionName,
            UUID sectionId,
            String gradeCode,
            String sectionCode,
            String sectionName,
            UUID subjectId,
            String subjectName,
            String subjectShortName,
            int weeklyTargetPeriods,
            String allocationRole,
            LocalDate startsOn,
            LocalDate endsOn) {}

    public record PublishedScheduleEntry(
            UUID entryId,
            UUID timetablePlanId,
            String timetableName,
            Instant publishedAt,
            UUID campusId,
            String campusName,
            UUID academicSessionId,
            String academicSessionName,
            UUID academicTermId,
            String academicTermName,
            UUID sectionId,
            String gradeCode,
            String sectionCode,
            String sectionName,
            int dayOfWeek,
            int sequenceNumber,
            String periodName,
            LocalTime startsAt,
            LocalTime endsAt,
            String subjectName,
            String subjectShortName,
            String roomLabel,
            String note) {}
}
