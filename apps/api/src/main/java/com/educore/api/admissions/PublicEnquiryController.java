package com.educore.api.admissions;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/enquiries")
public class PublicEnquiryController {

    private final EnquiryService enquiries;

    public PublicEnquiryController(EnquiryService enquiries) {
        this.enquiries = enquiries;
    }

    @PostMapping
    ResponseEntity<EnquiryResponse> create(
            @Valid @RequestBody CreateEnquiryRequest request,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        EnquiryResponse response = enquiries.create(request, idempotencyKey);
        return ResponseEntity.accepted().body(response);
    }
}
