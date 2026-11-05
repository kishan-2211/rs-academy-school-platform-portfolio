package com.educore.api.people;

import com.educore.api.identity.AuthenticatedUser;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Narrow read boundary for payroll. Payroll can resolve eligible staff without
 * querying people-domain tables or repositories directly.
 */
public interface PeoplePayrollBridge {
    List<PayrollStaff> payrollStaff(AuthenticatedUser user, UUID schoolId, String requiredCapability);

    Map<UUID, PayrollAssignmentSnapshot> payrollAssignmentSnapshots(AuthenticatedUser user, UUID schoolId,
            LocalDate periodEnd, String requiredCapability);

    record PayrollAssignmentSnapshot(LocalDate asOfDate, String status, UUID assignmentId,
            String designationName, String departmentName) {}

    default PayrollStaff requirePayrollStaff(AuthenticatedUser user, UUID schoolId, UUID staffProfileId,
            String requiredCapability) {
        return payrollStaff(user, schoolId, requiredCapability).stream()
                .filter(staff -> staff.id().equals(staffProfileId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Payroll staff is unavailable in this school."));
    }

    record PayrollStaff(UUID id, String employeeCode, String displayName, LocalDate joinedOn,
            LocalDate endedOn, String status, String designation, String department) {}
}
