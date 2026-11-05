package com.educore.api.documents;

import com.educore.api.identity.AuthenticatedUser;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/family/children/{learnerId}/documents")
@PreAuthorize("hasAuthority('CAPABILITY_portal.guardian')")
public class FamilyDocumentController {
    private final FamilyDocumentService documents;
    public FamilyDocumentController(FamilyDocumentService documents) { this.documents=documents; }

    @GetMapping
    ResponseEntity<FamilyDocumentService.Page> list(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId,@RequestParam(defaultValue="0") int page,
            @RequestParam(required=false) UUID paymentId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.VARY,HttpHeaders.COOKIE).body(documents.list(user,learnerId,page,paymentId));
    }

    @GetMapping("/{documentId}/download")
    ResponseEntity<StreamingResponseBody> download(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId,@PathVariable UUID documentId) {
        var file=documents.download(user,learnerId,documentId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).contentLength(file.sizeBytes())
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment()
                        .filename(file.filename(),StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options","nosniff").header(HttpHeaders.VARY,HttpHeaders.COOKIE)
                .body(output->file.writer().writeTo(output));
    }
}
