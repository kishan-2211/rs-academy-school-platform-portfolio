package com.educore.api.payroll;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PayrollAccountingWorkspace(UUID schoolId, String schoolName,
        boolean canConfigure, boolean canPrepare, boolean canApprove,
        List<FinancialYearOption> financialYears, List<AccountOption> accounts,
        List<ComponentOption> components, List<ConfigurationItem> configurations,
        List<RunOption> runs, List<PostingBatchItem> postingBatches) {
    public PayrollAccountingWorkspace {
        financialYears = List.copyOf(financialYears);
        accounts = List.copyOf(accounts);
        components = List.copyOf(components);
        configurations = List.copyOf(configurations);
        runs = List.copyOf(runs);
        postingBatches = List.copyOf(postingBatches);
    }

    public record FinancialYearOption(UUID id, String code, String displayName,
            LocalDate startsOn, LocalDate endsOn, String accountingBasis,
            String currencyCode, String status) {}

    public record AccountOption(UUID id, String code, String displayName,
            String accountType, String category, String status) {}

    public record ComponentOption(UUID id, String code, String displayName,
            String componentKind, String status) {}

    public record ComponentMappingItem(UUID payrollComponentId, String componentCode,
            String componentName, String componentKind, UUID expenseAccountId,
            UUID payableAccountId) {}

    public record TenderMappingItem(String paymentMethod, UUID paymentAccountId) {}

    public record ConfigurationItem(UUID id, UUID financialYearId,
            UUID supersedesConfigurationId, int revisionNumber, LocalDate effectiveFrom,
            String accountingBasis, String currencyCode, UUID salaryPayableAccountId,
            String status, String creatorName, Instant createdAt, String submitterName,
            Instant submittedAt, String deciderName, Instant decidedAt, long rowVersion,
            boolean canEdit, boolean canSubmit, boolean canDecide,
            List<ComponentMappingItem> componentMappings,
            List<TenderMappingItem> tenderMappings) {
        public ConfigurationItem {
            componentMappings = List.copyOf(componentMappings);
            tenderMappings = List.copyOf(tenderMappings);
        }
    }

    public record RunStaffOption(UUID id, UUID staffProfileId, String employeeCode,
            String staffName, BigDecimal net, String paymentStatus) {}

    public record RunOption(UUID id, String runCode, LocalDate payrollMonth, LocalDate periodEnd,
            String currencyCode, String status, int staffCount, BigDecimal payableGross,
            BigDecimal deductions, BigDecimal employerContributions, BigDecimal net,
            String accrualStatus, List<RunStaffOption> staff) {
        public RunOption { staff = List.copyOf(staff); }
    }

    public record PostingBatchItem(UUID id, UUID configurationId, UUID payrollRunId,
            UUID accrualBatchId, UUID financialYearId, UUID accountingPeriodId,
            String postingType, LocalDate postingDate, int sequenceNumber,
            String paymentMethod, String paymentReference, int sourceCount,
            int activeSourceCount, BigDecimal sourceAmount, UUID accountingJournalId,
            String journalStatus, String operationalStatus, String preparerName,
            Instant preparedAt, long rowVersion) {}
}
