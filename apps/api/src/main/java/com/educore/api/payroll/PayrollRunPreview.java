package com.educore.api.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PayrollRunPreview(UUID schoolId, LocalDate payrollMonth,
        LocalDate periodStart, LocalDate periodEnd, boolean ready, String inputFingerprint,
        PayrollRunWorkspace.RunPolicyItem policy, List<PreviewBlocker> blockers,
        int staffCount, int scheduledWorkingDays, int recordedDays, BigDecimal payableDays,
        BigDecimal fullGross, BigDecimal payableGross, BigDecimal deductions,
        BigDecimal employerContributions, BigDecimal net,
        List<PayrollRunWorkspace.RunStaffItem> staff) {
    public PayrollRunPreview {
        blockers = List.copyOf(blockers);
        staff = List.copyOf(staff);
    }

    public record PreviewBlocker(UUID staffProfileId, String staffName,
            String code, String message) {}
}
