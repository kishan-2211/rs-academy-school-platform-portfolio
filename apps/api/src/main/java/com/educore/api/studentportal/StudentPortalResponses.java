package com.educore.api.studentportal;

import com.educore.api.files.PrivateFileSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class StudentPortalResponses {

    private StudentPortalResponses() {}

    public record Overview(
            UUID learnerId, String learnerReference, String legalName, String preferredName,
            String schoolName, String campusName, String academicSessionName, String gradeCode,
            String sectionName, String rollNumber, String preferredLanguage, Instant accessActivatedAt) {}

    public record Schedule(
            UUID learnerId, UUID timetablePlanId, String timetableName,
            Instant publishedAt, List<ScheduleEntry> entries) {
        public Schedule { entries = List.copyOf(entries); }
    }

    public record ScheduleEntry(
            UUID id, int dayOfWeek, int sequenceNumber, String periodName,
            LocalTime startsAt, LocalTime endsAt, String subjectName,
            String subjectShortName, String roomLabel) {}

    public record Attendance(
            UUID learnerId, LocalDate from, LocalDate to,
            AttendanceSummary summary, List<AttendanceRecord> records) {
        public Attendance { records = List.copyOf(records); }
    }

    public record AttendanceSummary(
            int recordedMarks, BigDecimal earnedCredits, BigDecimal attendancePercent,
            BigDecimal warningThresholdPercent, boolean belowConfiguredWarning,
            Map<String, Integer> stateCounts) {
        public AttendanceSummary { stateCounts = Map.copyOf(stateCounts); }
    }

    public record AttendanceRecord(
            UUID markId, LocalDate date, String registerMode, String periodName,
            LocalTime startsAt, LocalTime endsAt, String stateCode, BigDecimal creditWeight) {}

    public record Assignments(UUID learnerId, List<Assignment> assignments) {
        public Assignments { assignments = List.copyOf(assignments); }
    }

    public record Assignment(
            UUID assignmentId, String title, String instructions, String subjectName,
            String categoryName, String resourceLabel, String resourceUrl,
            Instant availableFrom, Instant dueAt, String submissionMode,
            BigDecimal maximumPoints, boolean allowAttachments, int maxAttachmentCount,
            boolean attachmentRequired, boolean allowLate, Instant lateUntil,
            String status, int publicationRevision, List<PrivateFileSummary> attachments,
            Submission submission) {
        public Assignment { attachments = List.copyOf(attachments); }
    }

    public record Submission(
            UUID submissionId, String state, String responseText, String responseLink,
            Instant submittedAt, String teacherFeedback, BigDecimal pointsAwarded,
            Instant reviewedAt, long rowVersion, List<PrivateFileSummary> attachments) {
        public Submission { attachments = List.copyOf(attachments); }
    }

    public record Results(UUID learnerId, List<ReportCard> reportCards) {
        public Results { reportCards = List.copyOf(reportCards); }
    }

    public record ReportCard(
            UUID reportCardId, UUID publicationId, int publicationVersion,
            String reportNumber, String sessionName, String termName, String sectionName,
            BigDecimal overallPercentage, String overallGradeCode, String overallOutcome,
            Instant publishedAt, String snapshotSha256, Map<String, Object> report) {
        public ReportCard { report = Collections.unmodifiableMap(new LinkedHashMap<>(report)); }
    }
}
