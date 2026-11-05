package com.educore.api.documents;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/documents")
public class OfficialDocumentController {

    private final OfficialDocumentService documents;

    public OfficialDocumentController(OfficialDocumentService documents) {
        this.documents = documents;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CAPABILITY_documents.read')")
    OfficialDocumentSnapshot snapshot(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId) {
        return documents.snapshot(user, schoolId);
    }

    @GetMapping("/fee-receipts/{paymentId}")
    @PreAuthorize("hasAuthority('CAPABILITY_documents.read') and hasAuthority('CAPABILITY_fees.read')")
    ResponseEntity<OfficialDocumentSnapshot.FeeReceiptPage> feeReceipts(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID paymentId,
            @RequestParam UUID schoolId,
            @RequestParam(defaultValue = "0") int page) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.VARY, HttpHeaders.COOKIE)
                .body(documents.feeReceipts(user, schoolId, paymentId, page));
    }

    @GetMapping("/certificate-sources")
    @PreAuthorize("hasAuthority('CAPABILITY_documents.read') and hasAuthority('CAPABILITY_student.read')")
    OfficialDocumentSnapshot.SourcePage certificateSources(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int page) {
        return documents.certificateSources(user, schoolId, query, page);
    }

    @GetMapping("/{documentId}/review")
    @PreAuthorize("hasAuthority('CAPABILITY_documents.read')")
    ResponseEntity<OfficialDocumentSnapshot.Review> review(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) UUID schoolId,
            @PathVariable UUID documentId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(documents.review(user, schoolId, documentId));
    }

    @PostMapping("/templates")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_documents.configure')")
    OfficialDocumentMutationResponse createTemplate(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody TemplateRequest request) {
        return documents.createTemplate(user, request);
    }

    @PutMapping("/templates/{templateId}")
    @PreAuthorize("hasAuthority('CAPABILITY_documents.configure')")
    OfficialDocumentMutationResponse updateTemplate(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID templateId,
            @Valid @RequestBody TemplateRequest request) {
        return documents.updateTemplate(user, templateId, request);
    }

    @PostMapping("/templates/{templateId}/publish")
    @PreAuthorize("hasAuthority('CAPABILITY_documents.approve')")
    OfficialDocumentMutationResponse publishTemplate(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID templateId,
            @Valid @RequestBody TransitionRequest request) {
        return documents.publishTemplate(user, templateId, request);
    }

    @PostMapping("/issues")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_documents.issue')")
    OfficialDocumentMutationResponse requestIssue(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody IssueRequest request) {
        return documents.requestIssue(user, idempotencyKey, request);
    }

    @PostMapping("/{documentId}/decision")
    @PreAuthorize("hasAuthority('CAPABILITY_documents.approve')")
    OfficialDocumentMutationResponse decideIssue(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID documentId,
            @Valid @RequestBody DecisionRequest request) {
        return documents.decideIssue(user, documentId, request);
    }

    @PostMapping("/{documentId}/cancel")
    @PreAuthorize("hasAuthority('CAPABILITY_documents.revoke')")
    OfficialDocumentMutationResponse cancel(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID documentId,
            @Valid @RequestBody TransitionRequest request) {
        return documents.cancel(user, documentId, request);
    }

    @GetMapping("/{documentId}/download")
    @PreAuthorize("hasAuthority('CAPABILITY_documents.read')")
    ResponseEntity<StreamingResponseBody> download(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID documentId,
            @RequestParam UUID schoolId) {
        OfficialDocumentDownload file = documents.download(user, schoolId, documentId);
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

    public record TemplateRequest(
            @NotNull UUID schoolId,
            @NotBlank @Size(max = 40) @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,39}$") String templateCode,
            @Min(1) int revisionNumber,
            UUID supersedesTemplateId,
            @NotBlank String documentKind,
            @NotBlank @Size(max = 140) String displayName,
            @NotBlank String locale,
            @NotBlank @Size(max = 180) String title,
            @Size(max = 240) String subtitle,
            @NotBlank @Size(max = 500) String footerText,
            @NotBlank @Size(max = 120) String signatoryRole,
            @NotBlank @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String accentColour,
            boolean approvalRequired,
            @Size(max = 40) Map<@NotBlank @Size(max = 40) String, @NotBlank @Size(max = 100) String> labelOverrides,
            @Min(0) Long expectedVersion,
            @NotBlank @Size(max = 240) String reason) {}

    public record IssueRequest(
            @NotNull UUID schoolId,
            @NotNull UUID templateId,
            @NotBlank String sourceType,
            @NotNull UUID sourceId,
            UUID supersedesDocumentId,
            @NotBlank @Size(max = 240) String reason) {}

    public record DecisionRequest(
            @NotNull UUID schoolId,
            @NotBlank String decision,
            @NotBlank @Size(max = 240) String reason,
            @Min(0) long expectedVersion) {}

    public record TransitionRequest(
            @NotNull UUID schoolId,
            @Min(0) long expectedVersion,
            @NotBlank @Size(max = 240) String reason) {}
}
