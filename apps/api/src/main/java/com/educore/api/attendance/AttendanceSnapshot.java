package com.educore.api.attendance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.educore.api.studentleave.ApprovedStudentLeaveLookup.AttendanceEvidence;

public record AttendanceSnapshot(
        UUID schoolId, String schoolName, boolean canConfigure, boolean canCorrect,
        List<SessionItem> sessions, List<CampusItem> campuses, List<SectionItem> sections,
        List<PeriodItem> periods, List<PolicyItem> policies, List<CalendarItem> calendarDays,
        List<RegisterItem> registers, List<CorrectionItem> corrections, List<ExplanationItem> explanations) {
    public AttendanceSnapshot {
        sessions = List.copyOf(sessions); campuses = List.copyOf(campuses); sections = List.copyOf(sections);
        periods = List.copyOf(periods); policies = List.copyOf(policies); calendarDays = List.copyOf(calendarDays);
        registers = List.copyOf(registers); corrections = List.copyOf(corrections);
        explanations = List.copyOf(explanations);
    }
    public record SessionItem(UUID id, String name, LocalDate startsOn, LocalDate endsOn, String status) {}
    public record CampusItem(UUID id, String name) {}
    public record SectionItem(UUID id, UUID campusId, UUID sessionId, String gradeCode, String name) {}
    public record PeriodItem(UUID id, UUID campusId, UUID sessionId, int dayOfWeek, String name, LocalTime startsAt, LocalTime endsAt) {}
    public record PolicyItem(UUID id, UUID sessionId, String name, String registerMode, List<String> enabledStates,
            Map<String, BigDecimal> stateWeights,
            LocalTime submissionCutoff, int lockAfterHours, int guardianExplanationDays,
            BigDecimal warningThresholdPercent, String status, long rowVersion) {
        public PolicyItem { enabledStates = List.copyOf(enabledStates); stateWeights = Map.copyOf(stateWeights); }
    }
    public record CalendarItem(UUID id, UUID campusId, UUID sessionId, LocalDate date, String dayType,
            String name, boolean attendanceRequired, String status, long rowVersion) {}
    public record RegisterItem(UUID id, UUID sectionId, UUID calendarDayId, UUID periodId, String mode,
            List<String> enabledStates,
            String status, int expectedLearnerCount, int recordedCount, int absentCount,
            Instant submittedAt, long rowVersion, List<MarkItem> marks) {
        public RegisterItem { enabledStates = List.copyOf(enabledStates); marks = List.copyOf(marks); }
    }
    public record MarkItem(UUID id, UUID learnerId, String learnerName, String rollNumber,
            String stateCode, String note, long rowVersion, List<AttendanceEvidence> leaveEvidence) {
        public MarkItem { leaveEvidence = List.copyOf(leaveEvidence); }
    }
    public record CorrectionItem(UUID id, UUID markId, String learnerName, String currentState,
            String requestedState, String requestReason, String status, String requesterName,
            Instant createdAt, long rowVersion, AttendanceController.LeaveCorrectionSource leaveSource) {}
    public record ExplanationItem(UUID id, UUID markId, String learnerName, String stateCode,
            String guardianName, String explanation, String status, Instant createdAt, long rowVersion) {}
}
