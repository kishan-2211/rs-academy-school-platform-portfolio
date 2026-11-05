package com.educore.api.finance;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Human-scoped minimal report DTOs. Reported settlement is never bank receipt or journal authority. */
public final class GatewaySettlementModels {
    private GatewaySettlementModels(){}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Submit(@NotNull UUID schoolId,@NotNull UUID clientRequestId,@NotBlank @Size(max=60) String schoolTimeZone,@NotNull @Pattern(regexp="SUMMARY|EVENTS") String kind,
            Instant from,Instant until,UUID sourceSummaryReadId,@Size(max=100) String settlementReference){}
    public record Result(UUID id,UUID schoolId,String kind){}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Request(UUID id,String kind,String schoolTimeZone,String status,Instant from,Instant until,UUID sourceSummaryReadId,String settlementReference,
            Instant requestedAt,String requestedBy,int pagesCompleted,int pagesPending,int pagesReview,Instant lastCheckedAt,Instant nextCheckAt){}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Queue(UUID schoolId,String schoolTimeZone,boolean syncEnabled,boolean canSync,String status,String kind,String query,
            int page,int pageSize,long total,boolean hasMore,List<Request> items){public Queue{items=List.copyOf(items);}}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Summary(String settlementReference,String reportedStatus,String transferReference,Instant paymentsFrom,Instant paymentsThrough,
            Instant initiatedAt,Instant processedAt,String reportedPaymentAmount,String reportedServiceCharge,String reportedServiceTax,
            String reportedAdjustment,String reportedSettlementCharge,String reportedSettlementTax,String reportedSplitCharge,String reportedSplitTax,
            String reportedVendorCommission,String reportedAmountSettled,String reportedChargeType,String reportedSettlementType){}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Event(String settlementReference,String eventReference,String reportedType,String reportedDirection,String reportedStatus,
            String currencyCode,String reportedAmount,String reportedServiceCharge,String reportedServiceTax,String reportedSettlementAmount,
            Instant occurredAt,String orderReference,String paymentReference,List<String> refundReferences,String chargesCurrencyCode){public Event{refundReferences=List.copyOf(refundReferences);}}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Row(UUID sourceReadId,int rowIndex,Instant observedAt,Summary summary,Event event){}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Report(UUID schoolId,Request request,String query,int page,int pageSize,long total,boolean hasMore,List<Row> items){public Report{items=List.copyOf(items);}}
}
