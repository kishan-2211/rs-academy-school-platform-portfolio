package com.educore.api.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record FeeAccountingSnapshot(
        UUID schoolId,
        String schoolName,
        boolean canConfigure,
        boolean canPrepare,
        boolean canApprove,
        List<FeeHeadOption> feeHeads,
        List<ConfigurationItem> configurations,
        List<PostingBatchItem> postingBatches,
        List<UnpreparedEventSummary> unpreparedEvents,
        ReconciliationSummary reconciliation) {

    public FeeAccountingSnapshot {
        feeHeads = List.copyOf(feeHeads);
        configurations = List.copyOf(configurations);
        postingBatches = List.copyOf(postingBatches);
        unpreparedEvents = List.copyOf(unpreparedEvents);
    }

    public record FeeHeadOption(UUID id, String code, String name, String status) {}

    public record HeadMappingItem(UUID feeHeadId, String feeHeadCode,
            String feeHeadName, UUID incomeAccountId) {}

    public record TenderMappingItem(String paymentMethod, UUID depositAccountId) {}

    public record ConfigurationItem(UUID id, UUID financialYearId,
            UUID supersedesConfigurationId, int revisionNumber, LocalDate effectiveFrom,
            String accountingBasis, String currencyCode, UUID receivableAccountId,
            UUID concessionAccountId, String status, String creatorName,
            Instant createdAt, String submitterName, Instant submittedAt,
            String deciderName, Instant decidedAt, long rowVersion,
            List<HeadMappingItem> headMappings,
            List<TenderMappingItem> tenderMappings) {
        public ConfigurationItem {
            headMappings = List.copyOf(headMappings);
            tenderMappings = List.copyOf(tenderMappings);
        }
    }

    public record PostingBatchItem(UUID id, UUID configurationId,
            UUID financialYearId, UUID accountingPeriodId, LocalDate businessDate,
            String eventType, String disposition, int sequenceNumber,
            int sourceCount, int activeSourceCount, BigDecimal sourceAmount, UUID accountingJournalId,
            String journalStatus, String reconciliationStatus, String preparerName,
            Instant preparedAt, long rowVersion) {}

    public record UnpreparedEventSummary(String eventType, int sourceCount,
            BigDecimal sourceAmount) {}

    public record ReconciliationSummary(int reconciledBatches, int awaitingApprovalBatches,
            int noGlEffectBatches, int repreparedBatches, int requiresRepreparationBatches,
            int unpreparedSourceCount, BigDecimal unpreparedSourceAmount) {}
}
