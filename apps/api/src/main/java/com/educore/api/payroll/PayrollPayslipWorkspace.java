package com.educore.api.payroll;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PayrollPayslipWorkspace(
        UUID schoolId, String schoolName, UUID selfStaffProfileId,
        boolean canReadAll, boolean canGenerate,
        List<BatchItem> batches, List<PayslipItem> payslips) {
    public PayrollPayslipWorkspace {
        batches = List.copyOf(batches);
        payslips = List.copyOf(payslips);
    }

    public record BatchItem(
            UUID id, UUID payrollRunId, String runCode, LocalDate payrollMonth, String locale,
            String runStatus, int totalCount, String generationReason,
            String generatedBy, Instant generatedAt) {}

    public record PayslipItem(
            UUID id, UUID batchId, UUID payrollRunId, UUID payrollRunStaffResultId,
            UUID staffProfileId, String payslipNumber, String employeeCode, String staffName,
            LocalDate payrollMonth, String currencyCode, String runStatus,
            Instant reversedAt, String reversalReason,
            int scheduledWorkingDays, int recordedDays,
            BigDecimal payableDays, BigDecimal payableGross, BigDecimal deductions,
            BigDecimal employerContributions, BigDecimal net, Instant generatedAt, String pdfSha256) {}
}
