package com.educore.api.payroll;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PayrollRunWorkspace(UUID schoolId, String schoolName,
        boolean canConfigurePolicy, boolean canPrepareRuns, boolean canApproveRuns,
        boolean canRequestReversals, boolean canApproveReversals,
        RunPolicyItem activePolicy, List<RunItem> runs) {
    public PayrollRunWorkspace { runs = List.copyOf(runs); }

    public record RunPolicyItem(UUID id, String displayName, String currencyCode,
            String zeroScheduledDaysAction, int prorationScale, String status, long rowVersion) {}

    public record RunLineItem(UUID id, UUID payrollComponentId, String componentCode,
            String componentName, String componentKind, String calculationMethod,
            boolean attendanceProrated, boolean statutory, BigDecimal fullAmount,
            BigDecimal payableAmount) {}

    public record RunRecoveryItem(UUID id, UUID advanceId, String advanceNumber,
            UUID installmentId, LocalDate dueMonth, BigDecimal amount, String reservationStatus) {}

    public record RunStaffItem(UUID id, UUID staffProfileId, UUID salaryProfileId,
            String employeeCode, String staffName, int salaryRevision, int scheduledWorkingDays,
            int recordedDays, BigDecimal payableDays, BigDecimal paidLeaveDays,
            BigDecimal unpaidLeaveDays, int missingAttendanceDays, BigDecimal prorationFactor,
            BigDecimal fullGross, BigDecimal payableGross, BigDecimal deductions,
            BigDecimal employerContributions, BigDecimal net, List<String> warnings,
            List<RunLineItem> lines, List<RunRecoveryItem> recoveries) {
        public RunStaffItem {
            warnings = List.copyOf(warnings);
            lines = List.copyOf(lines);
            recoveries = List.copyOf(recoveries);
        }
    }

    public record RunHistoryItem(UUID id, String eventType, String previousStatus,
            String newStatus, String reason, String actorName, Instant occurredAt) {}

    public record RunReversalItem(UUID id, String status, long requestedRunVersion,
            String requestReason, String requesterName, Instant requestedAt,
            String deciderName, Instant decidedAt, String decisionReason,
            boolean canDecide, long rowVersion) {}

    public record RunItem(UUID id, LocalDate payrollMonth, LocalDate periodStart,
            LocalDate periodEnd, int attemptNumber, String runCode, String inputFingerprint,
            String currencyCode, String status, int staffCount, int scheduledWorkingDays,
            int recordedDays, BigDecimal payableDays, BigDecimal fullGross,
            BigDecimal payableGross, BigDecimal deductions, BigDecimal employerContributions,
            BigDecimal net, String creationReason, String creatorName, String submitterName,
            Instant submittedAt, String deciderName, Instant decidedAt, String decisionReason,
            boolean canSubmit, boolean canCancel, boolean canDecide,
            boolean canRequestReversal, boolean canDecideReversal, long rowVersion,
            List<RunStaffItem> staff, List<RunHistoryItem> history,
            List<RunReversalItem> reversals) {
        public RunItem {
            staff = List.copyOf(staff);
            history = List.copyOf(history);
            reversals = List.copyOf(reversals);
        }
    }
}
