package com.educore.api.workforce;

import com.educore.api.identity.AuthenticatedUser;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Narrow payroll-facing read boundary. Payroll receives frozen daily facts but
 * never queries workforce repositories or tables directly.
 */
public interface WorkforcePayrollBridge {
    List<PayrollAttendanceDay> payrollAttendanceDays(AuthenticatedUser user, UUID schoolId,
            LocalDate periodStart, LocalDate periodEnd, String requiredCapability);

    record PayrollAttendanceDay(UUID staffProfileId, UUID campusId, UUID calendarDayId,
            LocalDate attendanceDate, UUID registerId, Long registerRowVersion, UUID markId,
            Long markRowVersion, String stateCode, BigDecimal policyCredit, UUID leaveRequestId,
            String leaveTypeCode, Boolean paidLeave, BigDecimal leaveFraction,
            BigDecimal payrollCredit, boolean complete) {}
}
