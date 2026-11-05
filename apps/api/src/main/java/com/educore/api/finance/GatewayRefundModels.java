package com.educore.api.finance;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Staff-only minimal intent/evidence projections; never provider credentials or execution permission. */
public final class GatewayRefundModels {
    private GatewayRefundModels() {}
    public record Selection(@NotNull UUID chargeId,@NotNull @DecimalMin("0.01") @DecimalMax("100000000") @Digits(integer=9,fraction=2) BigDecimal amount) {}
    public record Submit(@NotNull UUID schoolId,@NotNull UUID clientRequestId,
            @NotEmpty @Size(max=100) List<@NotNull @Valid Selection> allocations,@NotBlank @Size(min=3,max=500) String reason) {}
    public record DecisionCommand(@NotNull UUID schoolId,@NotNull @Pattern(regexp="APPROVE|REJECT|CANCEL") String decision,
            @NotBlank @Size(min=3,max=500) String reason) {}
    public record Result(UUID id,UUID paymentId,String status) {}
    public record Allocation(UUID chargeId,String label,BigDecimal amount) {}
    public record Decision(UUID id,String decision,String reason,Instant decidedAt,String decidedBy) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Processing(String state,boolean requiresReview,Instant lastCheckedAt,Instant nextCheckAt) {
        @Override public String toString(){return "Processing[private refund processing summary]";}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Request(UUID id,UUID paymentId,String receiptNumber,String learnerName,BigDecimal amount,String currencyCode,
            String status,String reason,Instant requestedAt,String requestedBy,boolean isMaker,boolean canReview,boolean canCancel,
            List<Allocation> allocations,Decision decision,Processing processing) {
        public Request(UUID id,UUID paymentId,String receiptNumber,String learnerName,BigDecimal amount,String currencyCode,
                String status,String reason,Instant requestedAt,String requestedBy,boolean isMaker,boolean canReview,boolean canCancel,
                List<Allocation> allocations,Decision decision){
            this(id,paymentId,receiptNumber,learnerName,amount,currencyCode,status,reason,requestedAt,requestedBy,isMaker,canReview,canCancel,allocations,decision,null);
        }
        public Request { allocations=List.copyOf(allocations); }
    }
    public record SourceAllocation(UUID chargeId,String label,boolean refundable,BigDecimal originalPaid,BigDecimal reserved,BigDecimal available) {}
    public record Payment(UUID schoolId,UUID paymentId,String receiptNumber,UUID accountId,String learnerName,String currencyCode,
            BigDecimal amount,BigDecimal reserved,BigDecimal available,boolean canRequest,boolean executionEnabled,List<SourceAllocation> allocations) {
        public Payment { allocations=List.copyOf(allocations); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Queue(UUID schoolId,String status,String query,int page,int pageSize,long total,boolean hasMore,
            boolean executionEnabled,List<Request> items) {
        public Queue { items=List.copyOf(items); }
    }
}
