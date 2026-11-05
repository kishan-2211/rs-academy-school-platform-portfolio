package com.educore.api.payroll;

import com.educore.api.common.api.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public final class PayrollReports {
    private PayrollReports() {}

    public enum GroupBy {
        DEPARTMENT, DESIGNATION, DEDUCTION_COMPONENT;

        public static GroupBy parse(String value) {
            if (value == null) throw invalid("Choose a payroll summary grouping.");
            try {
                return valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw invalid("Choose DEPARTMENT, DESIGNATION, or DEDUCTION_COMPONENT for the payroll summary.");
            }
        }
    }

    public record Query(LocalDate from, LocalDate to, UUID staffProfileId, String search,
            int page, int size) {
        public Query {
            if (from == null || to == null || from.getYear() < 1 || to.getYear() > 9999
                    || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 3660
                    || page < 0 || page > 10000 || size < 1 || size > 100) {
                throw invalid("Choose an ordered date range of at most ten years and a page size from 1 to 100.");
            }
            search = search == null ? "" : search.strip();
            if (search.length() > 100 || search.chars().anyMatch(Character::isISOControl)) {
                throw invalid("Use at most 100 printable characters for the employee search.");
            }
        }
    }
    public record AdvanceQuery(LocalDate dueThrough, UUID staffProfileId, String search,
            int page, int size) {
        public AdvanceQuery {
            if (dueThrough == null || dueThrough.getYear() < 1 || dueThrough.getYear() > 9999
                    || page < 0 || page > 10000 || size < 1 || size > 100) {
                throw invalid("Choose a valid due-through date and a page size from 1 to 100.");
            }
            search = search == null ? "" : search.strip();
            if (search.length() > 100 || search.chars().anyMatch(Character::isISOControl)) {
                throw invalid("Use at most 100 printable characters for the employee or advance search.");
            }
        }
    }
    public record Page<T>(UUID schoolId, String schoolName, Instant generatedAt,
            LocalDate from, LocalDate to, int page, int size, long total, List<T> items) {
        public Page { items = List.copyOf(items); }
    }
    public record AdvancePage<T>(UUID schoolId, String schoolName, Instant generatedAt,
            LocalDate dueThrough, int page, int size, long total, List<T> items) {
        public AdvancePage { items = List.copyOf(items); }
    }
    public record RegisterRow(UUID id, UUID payrollRunId, String runCode, LocalDate payrollMonth,
            String runStatus, UUID staffProfileId, String employeeCode, String staffName,
            String currencyCode, BigDecimal gross, BigDecimal deductions, BigDecimal employerContributions,
            BigDecimal net, String accrualStatus, String paymentStatus,
            BigDecimal paid, BigDecimal pending, BigDecimal unsettled,
            UUID accrualJournalId, UUID paymentJournalId, String assignmentSnapshotStatus,
            LocalDate assignmentSnapshotDate, UUID assignmentId, String designationName, String departmentName) {}

    public record ReconciliationRow(UUID id, UUID payrollRunId, String runCode, String postingType,
            LocalDate postingDate, String currencyCode, int sourceCount, BigDecimal sourceAmount,
            int actualSourceCount, BigDecimal actualSourceAmount, BigDecimal frozenPayrollAmount,
            UUID journalId, String journalStatus, BigDecimal journalDebit, BigDecimal journalCredit,
            UUID reversalJournalId, String reversalStatus, String reconciliationStatus,
            List<String> issues) {
        public ReconciliationRow { issues = List.copyOf(issues); }
    }
    public record SummaryRow(String groupBy, String dimensionStatus, String dimensionCode,
            String dimensionLabel, String runStatus, String currencyCode,
            long payrollRunCount, long staffResultCount, BigDecimal gross,
            BigDecimal deductions, BigDecimal employerContributions, BigDecimal net) {}

    public record AdvanceReconciliationRow(UUID id, String advanceNumber, String advanceType,
            UUID staffProfileId, String employeeCode, String staffName,
            LocalDate issueDate, LocalDate recoveryStartMonth, String currencyCode,
            BigDecimal principalAmount, String advanceStatus, int declaredInstallmentCount,
            int actualInstallmentCount, BigDecimal scheduledAmount,
            BigDecimal scheduledDueAmount, String disbursementStatus,
            BigDecimal postedDisbursementAmount, BigDecimal recoveredAmount,
            BigDecimal outstandingAmount, BigDecimal overdueAmount,
            LocalDate nextUnrecoveredMonth, int pendingRecoveryCount,
            BigDecimal pendingRecoveryAmount, String reconciliationStatus,
            List<String> issues) {
        public AdvanceReconciliationRow { issues = List.copyOf(issues); }
    }

    static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "PAYROLL_REPORT_INPUT_INVALID", message);
    }
}
