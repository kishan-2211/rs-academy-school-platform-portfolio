package com.educore.api.finance;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Staff review only. None of these responses means a refund or restored fee was posted. */
public final class RefundAccountReopenModels {
    private RefundAccountReopenModels() {}

    public record Submit(@NotNull UUID schoolId, @NotNull UUID clientRequestId,
            @NotNull @PositiveOrZero Long expectedAccountVersion,
            @NotBlank @Size(min = 3, max = 500) String reason) {
        @Override public String toString() { return "Submit[private account reopening terms]"; }
    }
    public record DecisionCommand(@NotNull UUID schoolId,
            @NotNull @Pattern(regexp = "APPROVE|REJECT|CANCEL") String decision,
            @NotBlank @Size(min = 3, max = 500) String reason) {
        @Override public String toString() { return "DecisionCommand[private account reopening review]"; }
    }
    public record Closure(Instant closedAt, String reason, String closedByName) {
        @Override public String toString() { return "Closure[private preserved account closure]"; }
    }
    public record Decision(UUID id, String decision, String reason, Instant decidedAt, String reviewerName) {
        @Override public String toString() { return "Decision[private immutable account review]"; }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Review(UUID id, UUID refundId, UUID paymentId, UUID accountId, String learnerName,
            String sessionName, String state, String reason, Instant requestedAt, String makerName,
            boolean isMaker, boolean canApprove, boolean canReject, boolean canCancel,
            long closedAccountVersion, Closure originalClosure, Decision decision) {
        @Override public String toString() { return "Review[private original account reopening review]"; }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Context(UUID schoolId, UUID refundId, UUID paymentId, UUID accountId, String learnerName,
            String sessionName, String currencyCode, String schoolTimeZone, String sourceState,
            String accountState, long accountVersion, Closure currentClosure, BigDecimal currentBalance,
            BigDecimal confirmedUnpostedRefundAmount, boolean reopeningRequired, boolean canRequest,
            boolean refundPosted, boolean hasMoreHistory, List<Review> requests) {
        public Context { requests = List.copyOf(requests); }
        @Override public String toString() { return "Context[private account and unposted refund context]"; }
    }
    /** accountReopened describes this review's original applied transition, never the account's current status. */
    public record Result(UUID id, UUID refundId, UUID accountId, String state,
            boolean accountReopened, boolean refundPosted) {
        @Override public String toString() { return "Result[private retained account review; refund not posted]"; }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Queue(UUID schoolId, String status, String query, int page, int pageSize,
            long total, boolean hasMore, boolean refundPosted, List<Review> items) {
        public Queue { items = List.copyOf(items); }
        @Override public String toString() { return "Queue[private account review queue]"; }
    }
}
