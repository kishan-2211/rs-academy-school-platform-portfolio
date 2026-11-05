package com.educore.api.workforce;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record WorkforceSnapshot(
        UUID schoolId,
        String schoolName,
        boolean canReadAll,
        boolean canRecordAttendance,
        boolean canCorrectAttendance,
        boolean canApproveLeave,
        boolean canConfigure,
        UUID selfStaffProfileId,
        List<SessionItem> sessions,
        List<CampusItem> campuses,
        List<StaffItem> staff,
        List<CalendarItem> calendarDays,
        List<PolicyItem> policies,
        List<LeaveTypeItem> leaveTypes,
        List<LeaveBalanceItem> leaveBalances,
        List<LeaveRequestItem> leaveRequests,
        List<RegisterItem> registers,
        List<CorrectionItem> corrections,
        List<AttendanceSummaryItem> attendanceSummaries) {

    public WorkforceSnapshot {
        sessions = List.copyOf(sessions);
        campuses = List.copyOf(campuses);
        staff = List.copyOf(staff);
        calendarDays = List.copyOf(calendarDays);
        policies = List.copyOf(policies);
        leaveTypes = List.copyOf(leaveTypes);
        leaveBalances = List.copyOf(leaveBalances);
        leaveRequests = List.copyOf(leaveRequests);
        registers = List.copyOf(registers);
        corrections = List.copyOf(corrections);
        attendanceSummaries = List.copyOf(attendanceSummaries);
    }

    public record SessionItem(UUID id, String name, LocalDate startsOn, LocalDate endsOn, String status) {}
    public record CampusItem(UUID id, String name, String timeZone) {}
    public record StaffItem(UUID id, String employeeCode, String displayName, UUID campusId,
            String designation, LocalDate joinedOn, String status) {}
    public record CalendarItem(UUID id, UUID campusId, UUID sessionId, LocalDate date,
            String dayType, String name, boolean attendanceRequired, String status) {}
    public record PolicyItem(UUID id, UUID sessionId, UUID campusId, String name,
            List<String> enabledStates, Map<String, BigDecimal> stateWeights,
            int lockAfterHours, BigDecimal warningThresholdPercent,
            int consecutiveAbsenceThreshold, String status, long rowVersion) {
        public PolicyItem {
            enabledStates = List.copyOf(enabledStates);
            stateWeights = Map.copyOf(stateWeights);
        }
    }
    public record LeaveTypeItem(UUID id, String code, String name, boolean paid,
            boolean balanceRequired, BigDecimal maxDaysPerRequest, String status, long rowVersion) {}
    public record LeaveBalanceItem(UUID staffProfileId, UUID leaveTypeId, UUID sessionId,
            BigDecimal availableDays) {}
    public record LeaveRequestItem(UUID id, UUID staffProfileId, UUID leaveTypeId,
            UUID sessionId, LocalDate startsOn, LocalDate endsOn, String duration,
            BigDecimal requestedDays, String reason, String status, String requesterName,
            String deciderName, String decisionReason, Instant createdAt, long rowVersion) {}
    public record RegisterItem(UUID id, UUID campusId, UUID sessionId, UUID calendarDayId,
            LocalDate date, String dayName, List<String> enabledStates, String status,
            int expectedStaffCount, int recordedCount, int absentCount, Instant submittedAt,
            long rowVersion, List<MarkItem> marks) {
        public RegisterItem {
            enabledStates = List.copyOf(enabledStates);
            marks = List.copyOf(marks);
        }
    }
    public record MarkItem(UUID id, UUID staffProfileId, String staffName, String employeeCode,
            String stateCode, LocalTime checkInTime, LocalTime checkOutTime, String note,
            long rowVersion) {}
    public record CorrectionItem(UUID id, UUID markId, UUID staffProfileId, String staffName,
            String currentState, String requestedState, LocalTime requestedCheckInTime,
            LocalTime requestedCheckOutTime, String requestReason, String status,
            String requesterName, Instant createdAt, long rowVersion) {}
    public record AttendanceSummaryItem(UUID staffProfileId, UUID sessionId, int recordedDays,
            BigDecimal attendancePercent, int consecutiveAbsences, boolean belowThreshold,
            boolean consecutiveAbsenceAlert) {}
}
