package com.educore.api.payroll;

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
@RequestMapping("/api/v1/payroll/accounting")
public class PayrollAccountingController {
    private final PayrollAccountingService payrollAccounting;

    public PayrollAccountingController(PayrollAccountingService payrollAccounting) {
        this.payrollAccounting = payrollAccounting;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.read') and hasAuthority('CAPABILITY_accounting.read')")
    PayrollAccountingWorkspace workspace(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId) {
        return payrollAccounting.workspace(user, schoolId);
    }

    @PostMapping("/configurations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.configure') and hasAuthority('CAPABILITY_accounting.configure')")
    PayrollMutationResponse createConfiguration(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ConfigurationRequest request) {
        return payrollAccounting.createConfiguration(user, request);
    }

    @PutMapping("/configurations/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.configure') and hasAuthority('CAPABILITY_accounting.configure')")
    PayrollMutationResponse updateConfiguration(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody UpdateConfigurationRequest request) {
        return payrollAccounting.updateConfiguration(user, id, request);
    }

    @PostMapping("/configurations/{id}/submit")
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.configure') and hasAuthority('CAPABILITY_accounting.configure')")
    PayrollMutationResponse submitConfiguration(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody VersionedReasonRequest request) {
        return payrollAccounting.submitConfiguration(user, id, request);
    }

    @PostMapping("/configurations/{id}/decision")
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.approve') and hasAuthority('CAPABILITY_accounting.post')")
    PayrollMutationResponse decideConfiguration(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody ConfigurationDecisionRequest request) {
        return payrollAccounting.decideConfiguration(user, id, request);
    }

    @PostMapping("/runs/{id}/accruals")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.run.prepare') and hasAuthority('CAPABILITY_accounting.prepare')")
    PayrollMutationResponse prepareAccrual(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AccrualRequest request) {
        return payrollAccounting.prepareAccrual(user, id, idempotencyKey, request);
    }

    @PostMapping("/runs/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.run.prepare') and hasAuthority('CAPABILITY_accounting.prepare')")
    PayrollMutationResponse preparePayment(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {
        return payrollAccounting.preparePayment(user, id, idempotencyKey, request);
    }

    public record ComponentMappingRequest(@NotNull UUID payrollComponentId,
            UUID expenseAccountId, UUID payableAccountId) {}

    public record TenderMappingRequest(@NotBlank String paymentMethod,
            @NotNull UUID paymentAccountId) {}

    public record ConfigurationRequest(@NotNull UUID schoolId,
            @NotNull UUID financialYearId, UUID supersedesConfigurationId,
            @Min(1) @Max(1000) int revisionNumber, @NotNull LocalDate effectiveFrom,
            @NotNull UUID salaryPayableAccountId,
            @NotEmpty @Size(max = 100) List<@Valid ComponentMappingRequest> componentMappings,
            @NotEmpty @Size(max = 4) List<@Valid TenderMappingRequest> tenderMappings,
            @NotBlank @Size(min = 3, max = 500) String reason) {
        public ConfigurationRequest {
            componentMappings = componentMappings == null ? List.of() : List.copyOf(componentMappings);
            tenderMappings = tenderMappings == null ? List.of() : List.copyOf(tenderMappings);
        }
    }

    public record UpdateConfigurationRequest(@NotNull @Valid ConfigurationRequest configuration,
            @NotNull @Min(0) Long expectedVersion) {}

    public record VersionedReasonRequest(@NotNull UUID schoolId,
            @NotNull @Min(0) Long expectedVersion,
            @NotBlank @Size(min = 3, max = 500) String reason) {}

    public record ConfigurationDecisionRequest(@NotNull UUID schoolId,
            @NotBlank String decision, @NotNull @Min(0) Long expectedVersion,
            @NotBlank @Size(min = 3, max = 500) String reason) {}

    public record AccrualRequest(@NotNull UUID schoolId, @NotNull UUID financialYearId,
            @NotNull LocalDate postingDate,
            @NotBlank @Size(min = 3, max = 500) String reason) {}

    public record PaymentRequest(@NotNull UUID schoolId, @NotNull UUID financialYearId,
            @NotNull LocalDate postingDate, @NotBlank String paymentMethod,
            @Size(max = 120) String paymentReference,
            @NotEmpty @Size(max = 500) List<@NotNull UUID> payrollRunStaffResultIds,
            @NotBlank @Size(min = 3, max = 500) String reason) {
        public PaymentRequest {
            payrollRunStaffResultIds = payrollRunStaffResultIds == null
                    ? List.of() : List.copyOf(payrollRunStaffResultIds);
        }
    }
}
