package com.educore.api.accounting;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
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
@RequestMapping("/api/v1/accounting")
public class AccountingController {
    private final AccountingService accounting;

    public AccountingController(AccountingService accounting) {
        this.accounting = accounting;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CAPABILITY_accounting.read')")
    AccountingSnapshot snapshot(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId,
            @RequestParam(required = false) UUID financialYearId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return accounting.snapshot(user, schoolId, financialYearId, from, to);
    }

    @PostMapping("/financial-years")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_accounting.configure')")
    AccountingMutationResponse createFinancialYear(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody FinancialYearRequest request) {
        return accounting.createFinancialYear(user, request);
    }

    @PostMapping("/financial-years/{id}/open")
    @PreAuthorize("hasAuthority('CAPABILITY_accounting.close')")
    AccountingMutationResponse openFinancialYear(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody VersionedReasonRequest request) {
        return accounting.openFinancialYear(user, id, request);
    }

    @PostMapping("/financial-years/{id}/close")
    @PreAuthorize("hasAuthority('CAPABILITY_accounting.close')")
    AccountingMutationResponse closeFinancialYear(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody VersionedReasonRequest request) {
        return accounting.closeFinancialYear(user, id, request);
    }

    @PostMapping("/periods/{id}/transition")
    @PreAuthorize("hasAuthority('CAPABILITY_accounting.close')")
    AccountingMutationResponse transitionPeriod(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody PeriodTransitionRequest request) {
        return accounting.transitionPeriod(user, id, request);
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_accounting.configure')")
    AccountingMutationResponse createAccount(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody AccountRequest request) {
        return accounting.createAccount(user, request);
    }

    @PutMapping("/accounts/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_accounting.configure')")
    AccountingMutationResponse updateAccount(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody AccountRequest request) {
        return accounting.updateAccount(user, id, request);
    }

    @PostMapping("/vendors")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_accounting.configure')")
    AccountingMutationResponse createVendor(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody VendorRequest request) {
        return accounting.createVendor(user, request);
    }

    @PutMapping("/vendors/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_accounting.configure')")
    AccountingMutationResponse updateVendor(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody VendorRequest request) {
        return accounting.updateVendor(user, id, request);
    }

    @PostMapping("/journals")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_accounting.prepare')")
    AccountingMutationResponse createJournal(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 100) String idempotencyKey,
            @Valid @RequestBody JournalRequest request) {
        return accounting.createJournal(user, idempotencyKey, request);
    }

    @PostMapping("/journals/{id}/submit")
    @PreAuthorize("hasAuthority('CAPABILITY_accounting.prepare')")
    AccountingMutationResponse submitJournal(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody VersionedReasonRequest request) {
        return accounting.submitJournal(user, id, request);
    }

    @PostMapping("/journals/{id}/decision")
    @PreAuthorize("hasAuthority('CAPABILITY_accounting.post')")
    AccountingMutationResponse decideJournal(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
        return accounting.decideJournal(user, id, request);
    }

    @PostMapping("/journals/{id}/reversal-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_accounting.reverse.request')")
    AccountingMutationResponse requestReversal(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody ReversalRequest request) {
        return accounting.requestReversal(user, id, request);
    }

    @PostMapping("/reversal-requests/{id}/decision")
    @PreAuthorize("hasAuthority('CAPABILITY_accounting.reverse.approve')")
    AccountingMutationResponse decideReversal(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
        return accounting.decideReversal(user, id, request);
    }

    public record FinancialYearRequest(@NotNull UUID schoolId,
            @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_-]{1,29}") String code,
            @NotBlank @Size(max = 120) String name, @NotNull LocalDate startsOn,
            @NotNull LocalDate endsOn, @NotBlank String accountingBasis,
            @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currencyCode,
            @NotBlank @Size(max = 500) String reason) {}

    public record VersionedReasonRequest(@NotNull UUID schoolId, @Min(0) long expectedVersion,
            @NotBlank @Size(max = 500) String reason) {}

    public record PeriodTransitionRequest(@NotNull UUID schoolId, @NotBlank String transition,
            @Min(0) long expectedVersion, @NotBlank @Size(max = 500) String reason) {}

    public record AccountRequest(@NotNull UUID schoolId, UUID parentAccountId,
            @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_.-]{1,29}") String code,
            @NotBlank @Size(max = 160) String name, @Size(max = 500) String description,
            @NotBlank String accountType, @NotBlank String category,
            boolean allowDirectPosting, @NotBlank String status, @Min(0) Long expectedVersion,
            @Size(max = 500) String reason) {}

    public record VendorRequest(@NotNull UUID schoolId,
            @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_-]{1,39}") String code,
            @NotBlank @Size(max = 180) String name, @Size(max = 120) String contactName,
            @Size(max = 30) String phone, @jakarta.validation.constraints.Email @Size(max = 254) String email,
            @Size(max = 500) String address, @NotBlank String status,
            @Min(0) Long expectedVersion, @Size(max = 500) String reason) {}

    public record JournalLineRequest(@NotNull UUID accountId, UUID vendorId,
            UUID learnerFeeAccountId,
            @NotNull @DecimalMin("0.00") @DecimalMax("1000000000000") BigDecimal debitAmount,
            @NotNull @DecimalMin("0.00") @DecimalMax("1000000000000") BigDecimal creditAmount,
            @Size(max = 240) String memo) {}

    public record JournalRequest(@NotNull UUID schoolId, @NotNull UUID financialYearId,
            @NotNull UUID accountingPeriodId, @NotBlank String voucherType,
            @NotNull LocalDate postingDate, @Size(max = 120) String reference,
            @NotBlank @Size(max = 500) String narration,
            @NotEmpty @Size(min = 2, max = 500) List<@Valid JournalLineRequest> lines) {}

    public record DecisionRequest(@NotNull UUID schoolId, @NotBlank String decision,
            @NotBlank @Size(max = 500) String reason, @Min(0) long expectedVersion) {}

    public record ReversalRequest(@NotNull UUID schoolId,
            @NotNull UUID requestedAccountingPeriodId, @NotNull LocalDate requestedPostingDate,
            @NotBlank @Size(max = 500) String reason) {}
}
