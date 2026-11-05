package com.educore.api.finance;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Staff-only financial recording, never a command to return money at a provider. */
public final class GatewayRefundPostingModels {
    private GatewayRefundPostingModels() {}

    public record Command(@NotNull UUID schoolId, @NotNull UUID clientRequestId,
            @NotNull UUID financialYearId, @NotNull LocalDate postingOn,
            @NotBlank @Size(min = 3, max = 500) String reason) {
        @Override public String toString() { return "Command[private reviewed refund posting]"; }
    }

    public record JournalRepairCommand(@NotNull UUID schoolId, @NotNull UUID clientRequestId,
            @NotNull UUID expectedBatchId, @NotNull UUID expectedJournalId,
            @NotNull UUID financialYearId, @NotNull LocalDate postingOn,
            @NotBlank @Size(min = 3, max = 500) String reason) {
        @Override public String toString() { return "JournalRepairCommand[private accounting-only correction]"; }
    }

    public record JournalRepair(UUID refundPostingId, UUID refundId, UUID previousBatchId,
            UUID previousJournalId, UUID accountingBatchId, UUID journalId, String journalStatus,
            UUID financialYearId, LocalDate postingOn) {
        @Override public String toString() { return "JournalRepair[private retained accounting-only correction]"; }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Posting(UUID id, UUID refundId, UUID paymentId, UUID accountId,
            BigDecimal amount, String currencyCode, LocalDate postingOn, Instant postedAt,
            UUID ledgerEntryId, UUID accountingBatchId, UUID journalId, String journalStatus,
            UUID journalFinancialYearId, LocalDate journalPostingOn) {
        public Posting(UUID id, UUID refundId, UUID paymentId, UUID accountId, BigDecimal amount,
                String currencyCode, LocalDate postingOn, Instant postedAt, UUID ledgerEntryId,
                UUID accountingBatchId, UUID journalId, String journalStatus) {
            this(id, refundId, paymentId, accountId, amount, currencyCode, postingOn, postedAt,
                    ledgerEntryId, accountingBatchId, journalId, journalStatus, null, null);
        }
        @Override public String toString() { return "Posting[private retained refund financial record]"; }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Context(UUID schoolId, UUID refundId, UUID paymentId, String receiptNumber,
            UUID accountId, String learnerName, String academicSessionName, String accountStatus,
            String currencyCode, BigDecimal balance, BigDecimal verifiedRefundAmount,
            String sourceState, String processedAtExact, LocalDate processedOn,
            String schoolTimeZone, boolean originalDayClosed, boolean canPost, Posting posting,
            boolean canRepairJournal) {
        public Context(UUID schoolId, UUID refundId, UUID paymentId, String receiptNumber,
                UUID accountId, String learnerName, String academicSessionName, String accountStatus,
                String currencyCode, BigDecimal balance, BigDecimal verifiedRefundAmount, String sourceState,
                String processedAtExact, LocalDate processedOn, String schoolTimeZone, boolean originalDayClosed,
                boolean canPost, Posting posting) {
            this(schoolId, refundId, paymentId, receiptNumber, accountId, learnerName, academicSessionName,
                    accountStatus, currencyCode, balance, verifiedRefundAmount, sourceState, processedAtExact,
                    processedOn, schoolTimeZone, originalDayClosed, canPost, posting, false);
        }
        @Override public String toString() { return "Context[private reviewed refund posting context]"; }
    }
}
