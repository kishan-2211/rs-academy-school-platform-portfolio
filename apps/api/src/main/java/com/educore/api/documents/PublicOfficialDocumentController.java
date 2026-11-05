package com.educore.api.documents;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/documents")
public class PublicOfficialDocumentController {

    private final OfficialDocumentService documents;

    public PublicOfficialDocumentController(OfficialDocumentService documents) {
        this.documents = documents;
    }

    @GetMapping("/verify/{token}")
    ResponseEntity<PublicDocumentVerificationResponse> verify(@PathVariable String token) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(documents.verify(token));
    }
}
