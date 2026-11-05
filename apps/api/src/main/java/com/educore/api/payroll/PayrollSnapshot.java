package com.educore.api.payroll;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PayrollSnapshot(UUID schoolId, String schoolName, boolean canConfigure,
        boolean canApprove, List<StaffItem> staff, List<ComponentItem> components,
        List<SalaryProfileItem> salaryProfiles) {
    public PayrollSnapshot {
        staff = List.copyOf(staff);
        components = List.copyOf(components);
        salaryProfiles = List.copyOf(salaryProfiles);
    }

    public record StaffItem(UUID id, String employeeCode, String displayName, LocalDate joinedOn,
            LocalDate endedOn, String status, String designation, String department) {}

    public record ComponentItem(UUID id, String code, String displayName, String componentKind,
            String calculationMethod, boolean basic, boolean attendanceProrated, boolean statutory,
            String description, int displayOrder, String status, long rowVersion) {}

    public record SalaryLineItem(UUID id, UUID payrollComponentId, String componentCode,
            String componentName, String componentKind, String calculationMethod, boolean basic,
            boolean attendanceProrated, boolean statutory, BigDecimal fixedAmount,
            BigDecimal percentageRate, BigDecimal estimatedAmount) {}

    public record HistoryItem(UUID id, String eventType, String previousStatus, String newStatus,
            String reason, String actorName, Instant occurredAt) {}

    public record SalaryProfileItem(UUID id, UUID staffProfileId, UUID supersedesProfileId,
            int revisionNumber, LocalDate effectiveFrom, String currencyCode, String payFrequency,
            String status, String creationReason, String creatorName, String submitterName,
            Instant submittedAt, String deciderName, Instant decidedAt, String decisionReason,
            BigDecimal estimatedGross, BigDecimal estimatedDeductions,
            BigDecimal estimatedEmployerContributions, BigDecimal estimatedNet,
            long rowVersion, List<SalaryLineItem> lines, List<HistoryItem> history) {
        public SalaryProfileItem {
            lines = List.copyOf(lines);
            history = List.copyOf(history);
        }
    }
}
