package com.educore.api.finance;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/finance/accounting")
public class FeeAccountingController {
    private final FeeAccountingService feeAccounting;

    public FeeAccountingController(FeeAccountingService feeAccounting) {
        this.feeAccounting = feeAccounting;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CAPABILITY_fees.read') and hasAuthority('CAPABILITY_accounting.read')")
    FeeAccountingSnapshot snapshot(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId) {
        return feeAccounting.snapshot(user, schoolId);
    }

    @PostMapping("/configurations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_fees.close.propose') and hasAuthority('CAPABILITY_accounting.configure')")
    FinanceMutationResponse createConfiguration(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ConfigurationRequest request) {
        return feeAccounting.createConfiguration(user, request);
    }

    @PutMapping("/configurations/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_fees.close.propose') and hasAuthority('CAPABILITY_accounting.configure')")
    FinanceMutationResponse updateConfiguration(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
            @Valid @RequestBody ConfigurationRequest request) {
        return feeAccounting.updateConfiguration(user, id, request);
    }

    @PostMapping("/configurations/{id}/submit")
    @PreAuthorize("hasAuthority('CAPABILITY_fees.close.propose') and hasAuthority('CAPABILITY_accounting.configure')")
    FinanceMutationResponse submitConfiguration(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
            @Valid @RequestBody VersionedReasonRequest request) {
        return feeAccounting.submitConfiguration(user, id, request);
    }

    @PostMapping("/configurations/{id}/decision")
    @PreAuthorize("hasAuthority('CAPABILITY_fees.close.approve') and hasAuthority('CAPABILITY_accounting.post')")
    FinanceMutationResponse decideConfiguration(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
            @Valid @RequestBody ConfigurationDecisionRequest request) {
        return feeAccounting.decideConfiguration(user, id, request);
    }

    @PostMapping("/postings")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_fees.close.propose') and hasAuthority('CAPABILITY_accounting.prepare')")
    FinanceMutationResponse preparePosting(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 100) String idempotencyKey,
            @Valid @RequestBody PostingRequest request) {
        return feeAccounting.preparePosting(user, idempotencyKey, request);
    }

    public record HeadMappingRequest(@NotNull UUID feeHeadId,
            @NotNull UUID incomeAccountId) {}

    public record TenderMappingRequest(@NotBlank String paymentMethod,
            @NotNull UUID depositAccountId) {}

    public record ConfigurationRequest(@NotNull UUID schoolId,
            @NotNull UUID financialYearId, UUID supersedesConfigurationId,
            @Min(1) @Max(1000) int revisionNumber, @NotNull LocalDate effectiveFrom,
            UUID receivableAccountId, UUID concessionAccountId,
            @NotEmpty List<@Valid HeadMappingRequest> headMappings,
            @NotEmpty @Size(min = 5, max = 5) List<@Valid TenderMappingRequest> tenderMappings,
            @Min(0) Long expectedVersion,
            @NotBlank @Size(max = 500) String reason) {}

    public record VersionedReasonRequest(@NotNull UUID schoolId,
            @Min(0) long expectedVersion, @NotBlank @Size(max = 500) String reason) {}

    public record ConfigurationDecisionRequest(@NotNull UUID schoolId,
            @NotBlank String decision, @Min(0) long expectedVersion,
            @NotBlank @Size(max = 500) String reason) {}

    public record PostingRequest(@NotNull UUID schoolId,
            @NotNull UUID financialYearId, @NotNull LocalDate businessDate,
            @NotBlank String eventType, @Min(1) @Max(200) int maxEntries,
            @NotBlank @Size(max = 500) String reason) {}
}
