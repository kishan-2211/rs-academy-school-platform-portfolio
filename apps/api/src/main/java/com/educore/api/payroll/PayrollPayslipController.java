package com.educore.api.payroll;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/payroll")
public class PayrollPayslipController {
    private final PayrollPayslipService payslips;

    public PayrollPayslipController(PayrollPayslipService payslips) {
        this.payslips = payslips;
    }

    @GetMapping("/payslips")
    @PreAuthorize("hasAnyAuthority('CAPABILITY_payroll.read','CAPABILITY_payroll.payslip.self')")
    PayrollPayslipWorkspace workspace(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId) {
        return payslips.workspace(user, schoolId);
    }

    @PostMapping("/runs/{runId}/payslips")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_payroll.payslip.generate')")
    PayrollPayslipMutationResponse generate(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID runId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody GeneratePayslipsRequest request) {
        return payslips.generate(user, runId, idempotencyKey, request);
    }

    @GetMapping("/payslips/{payslipId}/download")
    @PreAuthorize("hasAnyAuthority('CAPABILITY_payroll.read','CAPABILITY_payroll.payslip.self')")
    ResponseEntity<StreamingResponseBody> download(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID payslipId,
            @RequestParam(required = false) UUID schoolId) {
        PayrollPayslipDownload file = payslips.download(user, schoolId, payslipId);
        StreamingResponseBody body = output -> file.writer().writeTo(output);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(file.sizeBytes())
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.VARY, HttpHeaders.COOKIE)
                .body(body);
    }

    public record GeneratePayslipsRequest(
            @NotNull UUID schoolId,
            @NotBlank @Pattern(regexp = "^(en-IN|hi-IN)$") String locale,
            @NotBlank @Size(min = 3, max = 500) String reason) {}
}
