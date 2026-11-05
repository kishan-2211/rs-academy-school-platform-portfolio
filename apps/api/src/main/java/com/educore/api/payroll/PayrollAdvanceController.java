package com.educore.api.payroll;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/v1/payroll/advances")
public class PayrollAdvanceController {
    private final PayrollAdvanceService advances;
    private final PayrollAdvanceStatementService statements;

    public PayrollAdvanceController(PayrollAdvanceService advances,
            PayrollAdvanceStatementService statements) {
        this.advances = advances;
        this.statements = statements;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.read') and hasAuthority('CAPABILITY_accounting.read')")
    ResponseEntity<PayrollAdvanceWorkspace> workspace(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID staffProfileId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(advances.workspace(user, schoolId, new Query(page, size, search, status, staffProfileId)));
    }

    @GetMapping("/{id}/statement")
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.read') and hasAuthority('CAPABILITY_accounting.read')")
    ResponseEntity<PayrollAdvanceStatement> statement(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @RequestParam(required = false) UUID schoolId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(statements.read(user, schoolId, id));
    }

    @GetMapping(value = "/{id}/statement.csv", produces = "text/csv")
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.read') and hasAuthority('CAPABILITY_accounting.read')")
    ResponseEntity<byte[]> statementCsv(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @RequestParam(required = false) UUID schoolId) {
        var export = statements.export(user, schoolId, id);
        String filename = "employee-advance-" + export.advanceNumber().toLowerCase(java.util.Locale.ROOT) + ".csv";
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-store, private")
                .header(HttpHeaders.PRAGMA, "no-cache").header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(export.csv().getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.advance.prepare') and hasAuthority('CAPABILITY_accounting.prepare')")
    PayrollMutationResponse create(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody AdvanceRequest request) {
        return advances.create(user, idempotencyKey, request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.advance.prepare') and hasAuthority('CAPABILITY_accounting.prepare')")
    PayrollMutationResponse update(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody UpdateAdvanceRequest request) {
        return advances.update(user, id, request);
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.advance.prepare') and hasAuthority('CAPABILITY_accounting.prepare')")
    PayrollMutationResponse submit(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody VersionedReasonRequest request) {
        return advances.submit(user, id, request);
    }

    @PostMapping("/{id}/decision")
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.advance.approve') and hasAuthority('CAPABILITY_accounting.post')")
    PayrollMutationResponse decide(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
        return advances.decide(user, id, request);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.advance.prepare') and hasAuthority('CAPABILITY_accounting.prepare')")
    PayrollMutationResponse cancel(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @Valid @RequestBody VersionedReasonRequest request) {
        return advances.cancel(user, id, request);
    }

    @PostMapping("/{id}/disbursements")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.advance.prepare') and hasAuthority('CAPABILITY_accounting.prepare')")
    PayrollMutationResponse prepareDisbursement(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID id, @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody DisbursementRequest request) {
        return advances.prepareDisbursement(user, id, idempotencyKey, request);
    }

    public record AdvanceRequest(@NotNull UUID schoolId, @NotNull UUID staffProfileId,
            @NotBlank String advanceType, @NotBlank @Size(min = 3, max = 500) String purpose,
            @NotNull UUID financialYearId, @NotNull LocalDate issueDate,
            @NotNull @DecimalMin("0.01") @DecimalMax("1000000000000") BigDecimal principalAmount,
            @NotNull LocalDate recoveryStartMonth, @Min(1) @Max(120) int installmentCount,
            @NotNull UUID receivableAccountId, @NotNull UUID disbursementAccountId,
            @NotBlank String paymentMethod, @Size(max = 120) String paymentReference,
            @NotBlank @Size(min = 3, max = 500) String reason) {}

    public record UpdateAdvanceRequest(@NotNull @Valid AdvanceRequest advance,
            @NotNull @Min(0) Long expectedVersion) {}

    public record VersionedReasonRequest(@NotNull UUID schoolId,
            @NotNull @Min(0) Long expectedVersion,
            @NotBlank @Size(min = 3, max = 500) String reason) {}

    public record DecisionRequest(@NotNull UUID schoolId, @NotBlank String decision,
            @NotNull @Min(0) Long expectedVersion,
            @NotBlank @Size(min = 3, max = 500) String reason) {}

    public record DisbursementRequest(@NotNull UUID schoolId,
            @NotBlank @Size(min = 3, max = 500) String reason) {}

    public record Query(int page, int size, String search, String status, UUID staffProfileId) {}
}
