package com.educore.api.payroll;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
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
@RequestMapping("/api/v1/payroll")
public class PayrollRunController {
    private final PayrollRunService runs;

    public PayrollRunController(PayrollRunService runs) { this.runs = runs; }

    @GetMapping("/runs")
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.read')")
    PayrollRunWorkspace workspace(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId) {
        return runs.workspace(user, schoolId);
    }

    @GetMapping("/run-preview")
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.run.prepare')")
    PayrollRunPreview preview(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam UUID schoolId, @RequestParam LocalDate payrollMonth) {
        return runs.preview(user, schoolId, payrollMonth, "payroll.run.prepare");
    }

    @PostMapping("/run-policies")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.configure')")
    PayrollMutationResponse createPolicy(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody RunPolicyRequest request) {
        return runs.createPolicy(user, request);
    }

    @PutMapping("/run-policies/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.configure')")
    PayrollMutationResponse updatePolicy(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody RunPolicyRequest request) {
        return runs.updatePolicy(user, id, request);
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.run.prepare')")
    PayrollMutationResponse prepare(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody PrepareRunRequest request) {
        return runs.prepare(user, request);
    }

    @PostMapping("/runs/{id}/submit")
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.run.prepare')")
    PayrollMutationResponse submit(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody RunTransitionRequest request) {
        return runs.submit(user, id, request);
    }

    @PostMapping("/runs/{id}/cancel")
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.run.prepare')")
    PayrollMutationResponse cancel(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody RunTransitionRequest request) {
        return runs.cancel(user, id, request);
    }

    @PostMapping("/runs/{id}/decision")
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.run.approve')")
    PayrollMutationResponse decide(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody RunDecisionRequest request) {
        return runs.decide(user, id, request);
    }

    @PostMapping("/runs/{id}/reversal-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.run.reverse.request')")
    PayrollMutationResponse requestReversal(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RunReversalRequest request) {
        return runs.requestReversal(user, id, idempotencyKey, request);
    }

    @PostMapping("/run-reversal-requests/{id}/decision")
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.run.reverse.approve')")
    PayrollMutationResponse decideReversal(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody RunReversalDecisionRequest request) {
        return runs.decideReversal(user, id, request);
    }

    public record RunPolicyRequest(@NotNull UUID schoolId,
            @NotBlank @Size(min = 2, max = 120) String displayName,
            @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currencyCode,
            @NotBlank String zeroScheduledDaysAction,
            @Min(2) @Max(6) int prorationScale, @NotBlank String status,
            @Min(0) Long expectedVersion,
            @NotBlank @Size(min = 3, max = 500) String reason) {}

    public record PrepareRunRequest(@NotNull UUID schoolId, @NotNull LocalDate payrollMonth,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String previewFingerprint,
            @NotBlank @Size(min = 8, max = 100) String idempotencyKey,
            @NotBlank @Size(min = 3, max = 500) String reason) {}

    public record RunTransitionRequest(@NotNull UUID schoolId,
            @NotNull @Min(0) Long expectedVersion,
            @NotBlank @Size(min = 3, max = 500) String reason) {}

    public record RunDecisionRequest(@NotNull UUID schoolId, @NotBlank String decision,
            @NotNull @Min(0) Long expectedVersion,
            @NotBlank @Size(min = 3, max = 500) String reason) {}

    public record RunReversalRequest(@NotNull UUID schoolId,
            @NotNull @Min(0) Long expectedRunVersion,
            @NotBlank @Size(min = 3, max = 500) String reason) {}

    public record RunReversalDecisionRequest(@NotNull UUID schoolId,
            @NotBlank String decision, @NotNull @Min(0) Long expectedVersion,
            @NotBlank @Size(min = 3, max = 500) String reason) {}
}
