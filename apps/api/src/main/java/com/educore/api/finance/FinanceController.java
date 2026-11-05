package com.educore.api.finance;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/finance")
public class FinanceController {
    private final FinanceService finance;
    public FinanceController(FinanceService finance) { this.finance = finance; }

    @GetMapping("/collections")
    @PreAuthorize("hasAuthority('CAPABILITY_fees.read')")
    org.springframework.http.ResponseEntity<CollectionRegisterResponse> collections(
            @AuthenticationPrincipal AuthenticatedUser user, @RequestParam UUID schoolId,
            @RequestParam LocalDate from, @RequestParam LocalDate to,
            @RequestParam(required=false) @Pattern(regexp="[A-Z]{3}") String currencyCode,
            @RequestParam(required=false) @Pattern(regexp="CASH|SCHOOL_UPI|BANK_TRANSFER|CHEQUE|ONLINE_PROVIDER") String paymentMethod,
            @RequestParam(required=false) @Size(max=100) String q,
            @RequestParam(defaultValue="0") @Min(0) @Max(10000) int page,
            @RequestParam(defaultValue="25") @Min(1) @Max(100) int pageSize) {
        return org.springframework.http.ResponseEntity.ok().header("Cache-Control","private, no-store")
                .header("Vary","Cookie").header("X-Content-Type-Options","nosniff")
                .body(finance.collections(user,schoolId,from,to,currencyCode,paymentMethod,q,page,pageSize));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CAPABILITY_fees.read')")
    FinanceSnapshot snapshot(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId) {
        return finance.snapshot(user, schoolId);
    }

    @GetMapping("/collections/export")
    @PreAuthorize("hasAuthority('CAPABILITY_fees.read')")
    org.springframework.http.ResponseEntity<CollectionRegisterExportResponse> exportCollections(
            @AuthenticationPrincipal AuthenticatedUser user, @RequestParam UUID schoolId,
            @RequestParam LocalDate from, @RequestParam LocalDate to,
            @RequestParam(required=false) @Pattern(regexp="[A-Z]{3}") String currencyCode,
            @RequestParam(required=false) @Pattern(regexp="CASH|SCHOOL_UPI|BANK_TRANSFER|CHEQUE|ONLINE_PROVIDER") String paymentMethod,
            @RequestParam(required=false) @Size(max=100) String q) {
        return org.springframework.http.ResponseEntity.ok().header("Cache-Control","private, no-store")
                .header("Vary","Cookie").header("X-Content-Type-Options","nosniff")
                .body(finance.exportCollections(user,schoolId,from,to,currencyCode,paymentMethod,q));
    }

    @GetMapping("/gateway/captures")
    @PreAuthorize("hasAuthority('CAPABILITY_fees.read')")
    GatewayCaptureQueueResponse gatewayCaptures(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam UUID schoolId,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "HOLD")
            @Pattern(regexp = "ALL|HOLD|POSTED|REFUND_REQUIRED|UNRESOLVED") String status,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int pageSize) {
        return finance.gatewayCaptures(user, schoolId, q, status, page, pageSize);
    }

    @PostMapping("/gateway/captures/{captureId}/posting")
    @PreAuthorize("hasAuthority('CAPABILITY_fees.collect')")
    FinanceService.GatewayPostingResult postReviewedGatewayCapture(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID captureId,
            @Valid @RequestBody GatewayCapturePostingRequest request) {
        return finance.postReviewedGatewayCapture(user, captureId, request);
    }

    @PostMapping("/fee-heads") @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_fees.configure')")
    FinanceMutationResponse createFeeHead(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody FeeHeadRequest request) { return finance.createFeeHead(user, request); }

    @PutMapping("/fee-heads/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_fees.configure')")
    FinanceMutationResponse updateFeeHead(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody FeeHeadRequest request) {
        return finance.updateFeeHead(user, id, request);
    }

    @PostMapping("/plans") @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_fees.configure')")
    FinanceMutationResponse createPlan(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody FeePlanRequest request) { return finance.createPlan(user, request); }

    @PutMapping("/plans/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_fees.configure')")
    FinanceMutationResponse updatePlan(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody FeePlanRequest request) {
        return finance.updatePlan(user, id, request);
    }

    @PostMapping("/plans/{planId}/items") @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_fees.configure')")
    FinanceMutationResponse addPlanItem(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID planId, @Valid @RequestBody PlanItemRequest request) {
        return finance.addPlanItem(user, planId, request);
    }

    @DeleteMapping("/plans/{planId}/items/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('CAPABILITY_fees.configure')")
    void deletePlanItem(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID planId, @PathVariable UUID itemId,
            @RequestParam UUID schoolId, @RequestParam @NotBlank @Size(max = 240) String reason) {
        finance.deletePlanItem(user, schoolId, planId, itemId, reason);
    }

    @PostMapping("/plans/{id}/activate")
    @PreAuthorize("hasAuthority('CAPABILITY_fees.approve')")
    FinanceMutationResponse activatePlan(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody PlanActivationRequest request) {
        return finance.activatePlan(user, id, request);
    }

    @PostMapping("/plan-assignments") @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_fees.configure')")
    FinanceMutationResponse assignPlan(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody PlanAssignmentRequest request) { return finance.assignPlan(user, request); }

    @PostMapping("/concessions") @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_fees.configure')")
    FinanceMutationResponse requestConcession(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ConcessionRequest request) { return finance.requestConcession(user, request); }

    @PostMapping("/concessions/{id}/decision")
    @PreAuthorize("hasAuthority('CAPABILITY_fees.approve')")
    FinanceMutationResponse decideConcession(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody ConcessionDecisionRequest request) {
        return finance.decideConcession(user, id, request);
    }

    @PostMapping("/payments") @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_fees.collect')")
    FinanceMutationResponse recordPayment(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 100) String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {
        return finance.recordPayment(user, idempotencyKey, request);
    }

    @PostMapping("/payments/{id}/reverse")
    @PreAuthorize("hasAuthority('CAPABILITY_fees.refund')")
    FinanceMutationResponse reversePayment(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody PaymentReversalRequest request) {
        return finance.reversePayment(user, id, request);
    }

    @PostMapping("/day-closes") @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_fees.close.propose')")
    FinanceMutationResponse proposeDayClose(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody DayCloseRequest request) {
        return finance.proposeDayClose(user, request);
    }

    @PostMapping("/day-closes/{id}/decision")
    @PreAuthorize("hasAuthority('CAPABILITY_fees.close.approve')")
    FinanceMutationResponse decideDayClose(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody DayCloseDecisionRequest request) {
        return finance.decideDayClose(user, id, request);
    }

    public record FeeHeadRequest(@NotNull UUID schoolId, @NotBlank @Pattern(regexp="[A-Za-z][A-Za-z0-9_ ]{1,29}") String code,
            @NotBlank @Size(max=120) String name, @Size(max=500) String description,
            @NotBlank String category, boolean refundable, @NotBlank String status,
            @Min(0) Long expectedVersion, @Size(max=240) String reason) {}
    public record FeePlanRequest(@NotNull UUID schoolId, @NotNull UUID academicSessionId,
            UUID supersedesPlanId, @NotBlank @Pattern(regexp="[A-Za-z][A-Za-z0-9_ ]{1,39}") String code,
            @Min(1) @Max(1000) int revisionNumber, @NotBlank @Size(max=140) String name,
            @Size(max=40) String gradeCode, @NotBlank @Pattern(regexp="[A-Za-z]{3}") String currencyCode,
            @NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9/\\-]{1,23}") String receiptPrefix,
            @Size(max=1000) String notes, @Min(0) Long expectedVersion, @Size(max=240) String reason) {}
    public record PlanItemRequest(@NotNull UUID schoolId, @NotNull UUID feeHeadId,
            @Min(1) @Max(1000) int sequenceNumber, @NotBlank @Size(max=140) String label,
            @NotNull LocalDate dueOn, @NotNull @DecimalMin("0.01") @DecimalMax("100000000") BigDecimal amount,
            boolean allowPartial, @Size(max=240) String reason) {}
    public record PlanActivationRequest(@NotNull UUID schoolId, @Min(0) long expectedVersion,
            boolean publicDisclosure, @NotBlank @Size(max=240) String reason) {}
    public record PlanAssignmentRequest(@NotNull UUID schoolId, @NotNull UUID feePlanId,
            @NotNull UUID enrolmentId, @NotBlank @Size(max=240) String reason) {}
    public record ConcessionRequest(@NotNull UUID schoolId, @NotNull UUID accountId,
            @NotNull UUID chargeId, @NotBlank String concessionType,
            @NotNull @DecimalMin("0.01") @DecimalMax("100000000") BigDecimal amount,
            @NotBlank @Size(max=500) String reason) {}
    public record ConcessionDecisionRequest(@NotNull UUID schoolId, @NotBlank String decision,
            @NotBlank @Size(max=500) String reason, @Min(0) long expectedVersion) {}
    public record PaymentAllocation(@NotNull UUID chargeId,
            @NotNull @DecimalMin("0.01") @DecimalMax("100000000") BigDecimal amount) {}
    public record PaymentRequest(@NotNull UUID schoolId, @NotNull UUID accountId,
            @NotBlank String paymentMethod, @Size(max=120) String externalReference,
            @NotNull @DecimalMin("0.01") @DecimalMax("100000000") BigDecimal amount,
            @NotNull LocalDate receivedOn, @Size(max=500) String notes,
            @Size(max=1000) List<@Valid PaymentAllocation> allocations,
            Instant actualReceivedAt, LocalDate postingOn,
            @Valid ManualCollectionEvidence.BankCreditVerification bankCreditVerification,
            @Size(min=3, max=240) String reviewReason) {
        /** Preserve existing cash clients without inventing a historical receipt time. */
        public PaymentRequest(UUID schoolId, UUID accountId, String paymentMethod, String externalReference,
                BigDecimal amount, LocalDate receivedOn, String notes, List<PaymentAllocation> allocations) {
            this(schoolId, accountId, paymentMethod, externalReference, amount, receivedOn,
                    notes, allocations, null, null, null, null);
        }
        @Override public String toString() { return "PaymentRequest[scoped manual collection]"; }
    }
    public record PaymentReversalRequest(@NotNull UUID schoolId, @Min(0) long expectedVersion,
            @NotBlank @Size(max=240) String reason) {}
    public record GatewayCapturePostingRequest(@NotNull UUID schoolId, @NotNull LocalDate openDate,
            @NotBlank @Size(max=240) String reason) {}
    public record TenderDeclaration(@NotBlank String paymentMethod,
            @NotNull @DecimalMin("-100000000") @DecimalMax("100000000") @Digits(integer=9,fraction=2) BigDecimal declaredAmount) {
        @AssertTrue(message="Only online gateway declarations can be signed refund movements")
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean isNonnegativeUnlessOnline() {
            return declaredAmount==null || declaredAmount.signum()>=0 || "ONLINE_PROVIDER".equals(paymentMethod);
        }
    }
    public record DayCloseRequest(@NotNull UUID schoolId, @NotNull LocalDate businessDate,
            @NotNull @Size(min=5, max=5) List<@Valid TenderDeclaration> declarations,
            @NotBlank @Size(max=500) String reason, @Size(max=500) String varianceReason) {}
    public record DayCloseDecisionRequest(@NotNull UUID schoolId, @NotBlank String decision,
            @NotBlank @Size(max=500) String reason, @Min(0) long expectedVersion) {}
}
