package com.educore.api.finance;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Private human attestation, never provider confirmation, reconciliation or permission to post money. */
public final class GatewaySettlementBankEvidenceModels {
    static final Set<String> REVIEW_REASONS = Set.of("NOT_RECONCILED", "SUMMARY_CURRENCY_UNCONFIRMED",
            "SOURCE_RECONCILIATION_PENDING", "SUMMARY_AMOUNT_UNKNOWN", "SUMMARY_AMOUNT_MISMATCH",
            "TRANSFER_REFERENCE_UNKNOWN", "TRANSFER_REFERENCE_MISMATCH", "SUMMARY_STATUS_REVIEW");
    private GatewaySettlementBankEvidenceModels() {}

    public record Command(@NotNull UUID schoolId, @NotNull UUID clientRequestId, @NotNull UUID bankAccountId,
            @NotNull @Pattern(regexp="INR") String currencyCode, @NotBlank @Size(max=12) String creditedAmount,
            @NotNull Instant actualCreditedAt,
            @NotNull @Pattern(regexp="BANK_APP_CREDIT|BANK_STATEMENT_CREDIT") String verificationKind,
            @NotBlank @Size(max=120) String bankCreditReference, @AssertTrue boolean confirmedReceived,
            @NotBlank @Size(min=3,max=240) String reason) {
        @Override public String toString() { return "Command[private unposted bank evidence]"; }
    }
    public record RejectionCommand(@NotNull UUID schoolId, @NotNull UUID clientRequestId,
            @NotBlank @Size(min=3,max=240) String reason, @AssertTrue boolean confirmedMistakenEvidence) {
        @Override public String toString() { return "RejectionCommand[private mistaken-evidence attestation]"; }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Summary(UUID sourceReadId, int rowIndex, Instant observedAt, String reportedStatus,
            String transferReference, String reportedAmountSettled) {
        public Summary { Objects.requireNonNull(sourceReadId); Objects.requireNonNull(observedAt);
            Objects.requireNonNull(reportedStatus); if(rowIndex<0||rowIndex>=10)throw invalid(); }
        @Override public String toString() { return "Summary[private retained statement, currency unconfirmed]"; }
    }
    public record BankAccount(UUID id, String code, String name, String currencyCode) {
        public BankAccount { Objects.requireNonNull(id); Objects.requireNonNull(code); Objects.requireNonNull(name);
            if(!"INR".equals(currencyCode))throw invalid(); }
        @Override public String toString() { return "BankAccount[private scoped chart identity]"; }
    }
    public record Rejection(UUID id, String reason, Instant rejectedAt, String rejectedBy) {
        public Rejection { Objects.requireNonNull(id); Objects.requireNonNull(reason);
            Objects.requireNonNull(rejectedAt); Objects.requireNonNull(rejectedBy); }
        @Override public String toString() { return "Rejection[private independent correction, original evidence retained]"; }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Evidence(UUID id, UUID clientRequestId, UUID bankAccountId, String bankReference, String currencyCode,
            String creditedAmount, String actualCreditedAt, LocalDate creditedOn, String verificationKind,
            Instant recordedAt, String recordedBy, String reason, String status, List<String> reviewReasons,
            Rejection rejection, boolean canReject) {
        public Evidence {
            for(Object required:new Object[]{id,clientRequestId,bankAccountId,bankReference,creditedAmount,actualCreditedAt,
                    creditedOn,recordedAt,recordedBy,reason})Objects.requireNonNull(required);
            reviewReasons=List.copyOf(reviewReasons);
            if(!"INR".equals(currencyCode)||!Set.of("BANK_APP_CREDIT","BANK_STATEMENT_CREDIT").contains(verificationKind==null?"":verificationKind)
                    ||!REVIEW_REASONS.containsAll(reviewReasons)||reviewReasons.stream().distinct().count()!=reviewReasons.size()
                    ||!reviewReasons.containsAll(List.of("NOT_RECONCILED","SUMMARY_CURRENCY_UNCONFIRMED","SOURCE_RECONCILIATION_PENDING"))
                    ||!("BANK_EVIDENCE_RECORDED".equals(status)&&rejection==null||"REJECTED".equals(status)&&rejection!=null&&!canReject))throw invalid();
        }
        @Override public String toString() { return "Evidence[private retained bank attestation, not reconciled or posted]"; }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Context(UUID schoolId, UUID requestId, String settlementReference, String schoolTimeZone,
            boolean canRecord, boolean canReject, Summary summary, List<BankAccount> bankAccounts,
            List<Evidence> evidence, boolean hasMoreEvidence) {
        public Context {
            Objects.requireNonNull(schoolId);Objects.requireNonNull(requestId);Objects.requireNonNull(settlementReference);
            Objects.requireNonNull(schoolTimeZone);Objects.requireNonNull(summary);
            bankAccounts=List.copyOf(bankAccounts);evidence=List.copyOf(evidence);
            if(bankAccounts.size()>1000||evidence.size()>25)throw invalid();
        }
        @Override public String toString() { return "Context[private statement and non-posting bank-evidence review]"; }
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Explicit non-posting bank evidence state required");}
}
