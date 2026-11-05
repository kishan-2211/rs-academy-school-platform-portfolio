package com.educore.api.finance;

import com.educore.api.accounting.AccountingModuleBridge.SettlementAccountingOptions;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Private reviewed settlement terms. Payment identities and journal lines are server-owned. */
public final class GatewaySettlementPreparationModels {
    private GatewaySettlementPreparationModels() {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ComponentReview(@NotBlank @Size(max=30) String key,
            @NotNull @Pattern(regexp="REPORTED|ROUNDING_REVIEW|CONFIRMED_ZERO|EXTERNAL_REVIEW") String mode,
            @NotNull @Pattern(regexp="DEBIT|CREDIT") String direction, UUID accountId,
            @NotBlank @Size(max=16) String postedAmount,@Size(max=120) String evidenceReference,
            @NotBlank @Size(min=3,max=240) String reason) {
        @Override public String toString(){return "ComponentReview[private reviewed component evidence]";}
    }
    public record RefundAssociation(@NotNull UUID sourceReadId,@Min(0) @Max(9) int rowIndex,
            @NotNull UUID refundPostingId,@NotBlank @Size(min=3,max=240) String reason) {
        @Override public String toString(){return "RefundAssociation[private explicit historical source choice]";}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Command(@NotNull UUID schoolId,@NotNull UUID clientRequestId,
            @NotBlank @Pattern(regexp="[a-f0-9]{64}") String contextFingerprint,
            @NotNull UUID bankEvidenceId,@NotNull UUID financialYearId,@NotNull LocalDate postingOn,
            @NotNull @Pattern(regexp="INR") String summaryCurrencyCode,@AssertTrue boolean confirmedSummaryCurrency,
            @NotNull @Pattern(regexp="SUMMARY|EVENTS") String componentBasis,
            @NotNull @Size(min=8,max=8) List<@Valid ComponentReview> componentReviews,
            @NotNull @Size(max=10000) List<@Valid RefundAssociation> refundAssociations,
            UUID previousPreparationId,@AssertTrue boolean confirmedReviewed,
            @NotBlank @Size(min=3,max=240) String reason) {
        public Command {componentReviews=componentReviews==null?null:List.copyOf(componentReviews);
            refundAssociations=refundAssociations==null?null:List.copyOf(refundAssociations);}
        @Override public String toString(){return "Command[private immutable settlement preparation]";}
    }
    public record AbandonmentCommand(@NotNull UUID schoolId,@NotNull UUID clientRequestId,
            @NotBlank @Size(min=3,max=240) String reason,@AssertTrue boolean confirmedReleaseRejectedPreparation) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ComponentFact(String key,String summaryAmount,String eventsAmount,boolean eventsComplete) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Row(UUID sourceReadId,int rowIndex,GatewaySettlementModels.Event event,String status,
            List<String> reasons,GatewaySettlementSourceReviewModels.Source source) {
        public Row {reasons=List.copyOf(reasons);}
        @Override public String toString(){return "Row[private exact retained settlement source context]";}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Context(UUID schoolId,UUID requestId,String settlementReference,String schoolTimeZone,
            String contextFingerprint,boolean canPrepare,SettlementAccountingOptions accountingOptions,
            List<GatewaySettlementBankEvidenceModels.Evidence> bankEvidence,GatewaySettlementModels.Summary summary,
            List<ComponentFact> componentFacts,String paymentTotal,String refundTotal,int page,int pageSize,long totalEvents,long totalRefundEvents,
            boolean hasMore,List<Row> rows,List<String> blockers,List<Preparation> preparations) {
        public Context {bankEvidence=List.copyOf(bankEvidence);componentFacts=List.copyOf(componentFacts);
            rows=List.copyOf(rows);blockers=List.copyOf(blockers);preparations=List.copyOf(preparations);}
        @Override public String toString(){return "Context[private whole-statement preparation context]";}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Preparation(UUID id,UUID clientRequestId,UUID rootId,UUID requestId,UUID bankEvidenceId,int revision,
            UUID previousPreparationId,UUID journalId,String status,long rowVersion,String voucherNumber,
            LocalDate postingOn,String currencyCode,String bankAmount,String paymentTotal,String refundTotal,
            String componentDebitTotal,String componentCreditTotal,int sourceCount,Instant preparedAt,
            String preparedBy,String reason) {
        @Override public String toString(){return "Preparation[private retained settlement and common journal status]";}
    }
    public record AbandonmentResult(UUID clientRequestId,Preparation preparation) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SourceIdentity(String kind,UUID paymentId,UUID refundPostingId,UUID feeLedgerEntryId,
            UUID postingItemId,UUID postingBatchId,UUID accountingJournalId,UUID clearingAccountId,String amount,
            UUID sourceReadId,int rowIndex,String associationReason) {
        @Override public String toString(){return "SourceIdentity[private frozen settlement claim]";}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PreparationDetail(Preparation preparation,String schoolTimeZone,GatewaySettlementModels.Summary summary,
            GatewaySettlementBankEvidenceModels.Evidence bankEvidence,String componentBasis,List<ComponentFact> componentFacts,
            List<ComponentReview> componentReviews,List<RefundAssociation> refundAssociations,List<SourceIdentity> sources) {
        public PreparationDetail {componentFacts=List.copyOf(componentFacts);componentReviews=List.copyOf(componentReviews);
            refundAssociations=List.copyOf(refundAssociations);sources=List.copyOf(sources);}
        @Override public String toString(){return "PreparationDetail[private immutable reviewed posting provenance]";}
    }
}
