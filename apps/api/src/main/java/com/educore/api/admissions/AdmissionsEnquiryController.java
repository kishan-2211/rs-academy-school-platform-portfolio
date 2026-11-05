package com.educore.api.admissions;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admissions/enquiries")
public class AdmissionsEnquiryController {

    private final EnquiryService enquiries;

    public AdmissionsEnquiryController(EnquiryService enquiries) {
        this.enquiries = enquiries;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CAPABILITY_admissions.enquiry.read')")
    EnquiryPageResponse list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) EnquiryStatus status,
            @RequestParam(required = false) EnquiryIntent intent,
            @RequestParam(required = false) @Size(max = 100) String query,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return enquiries.list(user, status, intent, query, page, size);
    }

    @GetMapping("/{enquiryId}")
    @PreAuthorize("hasAuthority('CAPABILITY_admissions.enquiry.read')")
    EnquiryDetailResponse get(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID enquiryId) {
        return enquiries.get(user, enquiryId);
    }

    @GetMapping("/{enquiryId}/assignees")
    @PreAuthorize("hasAuthority('CAPABILITY_admissions.enquiry.manage')")
    java.util.List<EnquiryAssignee> listAssignees(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID enquiryId) {
        return enquiries.listAssignees(user, enquiryId);
    }

    @PatchMapping("/{enquiryId}")
    @PreAuthorize("hasAuthority('CAPABILITY_admissions.enquiry.manage')")
    EnquiryDetailResponse update(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID enquiryId,
            @Valid @RequestBody UpdateEnquiryRequest request) {
        return enquiries.update(user, enquiryId, request);
    }
}
