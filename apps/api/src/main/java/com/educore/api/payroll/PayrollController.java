package com.educore.api.payroll;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payroll")
public class PayrollController {
    private final PayrollService payroll;

    public PayrollController(PayrollService payroll) {
        this.payroll = payroll;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.read')")
    PayrollSnapshot snapshot(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId) {
        return payroll.snapshot(user, schoolId);
    }

    @PostMapping("/components")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.configure')")
    PayrollMutationResponse createComponent(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ComponentRequest request) {
        return payroll.createComponent(user, request);
    }

    @PutMapping("/components/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.configure')")
    PayrollMutationResponse updateComponent(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody ComponentRequest request) {
        return payroll.updateComponent(user, id, request);
    }

    @PostMapping("/salary-profiles")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.configure')")
    PayrollMutationResponse createSalaryProfile(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody SalaryProfileRequest request) {
        return payroll.createSalaryProfile(user, request);
    }

    @PutMapping("/salary-profiles/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.configure')")
    PayrollMutationResponse updateSalaryProfile(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody SalaryProfileRequest request) {
        return payroll.updateSalaryProfile(user, id, request);
    }

    @PostMapping("/salary-profiles/{id}/submit")
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.configure')")
    PayrollMutationResponse submitSalaryProfile(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody VersionedReasonRequest request) {
        return payroll.submitSalaryProfile(user, id, request);
    }

    @PostMapping("/salary-profiles/{id}/decision")
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.approve')")
    PayrollMutationResponse decideSalaryProfile(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
        return payroll.decideSalaryProfile(user, id, request);
    }

    public record ComponentRequest(@NotNull UUID schoolId,
            @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{1,39}") String code,
            @NotBlank @Size(min = 2, max = 120) String displayName,
            @NotBlank String componentKind, @NotBlank String calculationMethod,
            boolean basic, boolean attendanceProrated, boolean statutory,
            @Size(max = 500) String description, @Min(0) @Max(1000) int displayOrder,
            @NotBlank String status, @Min(0) Long expectedVersion,
            @NotBlank @Size(min = 3, max = 500) String reason) {}

    public record SalaryLineRequest(@NotNull UUID payrollComponentId,
            @DecimalMin(value = "0.01") @DecimalMax(value = "1000000000000") BigDecimal fixedAmount,
            @DecimalMin(value = "0.0001") @DecimalMax(value = "1000") BigDecimal percentageRate) {}

    public record SalaryProfileRequest(@NotNull UUID schoolId, @NotNull UUID staffProfileId,
            UUID supersedesProfileId, @Min(1) @Max(10000) int revisionNumber,
            @NotNull LocalDate effectiveFrom,
            @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currencyCode,
            @NotBlank String payFrequency,
            @NotEmpty @Size(max = 100) List<@Valid SalaryLineRequest> lines,
            @Min(0) Long expectedVersion,
            @NotBlank @Size(min = 3, max = 500) String reason) {
        public SalaryProfileRequest {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    public record VersionedReasonRequest(@NotNull UUID schoolId, @NotNull @Min(0) Long expectedVersion,
            @NotBlank @Size(min = 3, max = 500) String reason) {}

    public record DecisionRequest(@NotNull UUID schoolId, @NotBlank String decision,
            @NotBlank @Size(min = 3, max = 500) String reason,
            @NotNull @Min(0) Long expectedVersion) {}
}
