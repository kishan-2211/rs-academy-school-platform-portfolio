package com.educore.api.admissions;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admissions/applications")
public class AdmissionApplicationController {

    private final AdmissionApplicationService applications;

    public AdmissionApplicationController(AdmissionApplicationService applications) {
        this.applications = applications;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CAPABILITY_admissions.application.read')")
    ApplicationPageResponse list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) ApplicationStatus status,
            @RequestParam(required = false) @Size(max = 100) String query,
            @RequestParam(required = false) AdmissionType admissionType,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return applications.list(user, status, query, page, size, admissionType);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_admissions.application.manage')")
    ApplicationDetailResponse create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateApplicationRequest request,
            @RequestHeader(name = "Idempotency-Key") String idempotencyKey) {
        return applications.create(user, request, idempotencyKey);
    }

    @GetMapping("/{applicationId}")
    @PreAuthorize("hasAuthority('CAPABILITY_admissions.application.read')")
    ApplicationDetailResponse get(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID applicationId) {
        return applications.get(user, applicationId);
    }

    @PatchMapping("/{applicationId}")
    @PreAuthorize("hasAuthority('CAPABILITY_admissions.application.manage')")
    ApplicationDetailResponse update(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID applicationId,
            @Valid @RequestBody UpdateApplicationRequest request) {
        return applications.update(user, applicationId, request);
    }

    @PatchMapping("/{applicationId}/status")
    @PreAuthorize("hasAuthority('CAPABILITY_admissions.application.manage')")
    ApplicationDetailResponse updateStatus(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID applicationId,
            @Valid @RequestBody UpdateApplicationStatusRequest request) {
        return applications.updateStatus(user, applicationId, request);
    }

    @PostMapping("/{applicationId}/guardians")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_admissions.application.manage')")
    ApplicationDetailResponse addGuardian(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID applicationId,
            @Valid @RequestBody AddApplicationGuardianRequest request) {
        return applications.addGuardian(user, applicationId, request);
    }

    @PatchMapping("/{applicationId}/guardians/{guardianId}")
    @PreAuthorize("hasAuthority('CAPABILITY_admissions.application.manage')")
    ApplicationDetailResponse updateGuardian(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID applicationId,
            @PathVariable UUID guardianId,
            @Valid @RequestBody UpdateApplicationGuardianRequest request) {
        return applications.updateGuardian(user, applicationId, guardianId, request);
    }

    @PostMapping("/{applicationId}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_admissions.application.manage')")
    ApplicationDetailResponse addDocument(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID applicationId,
            @Valid @RequestBody AddApplicationDocumentRequest request) {
        return applications.addDocument(user, applicationId, request);
    }

    @PatchMapping("/{applicationId}/documents/{documentId}")
    @PreAuthorize("hasAuthority('CAPABILITY_admissions.application.manage')")
    ApplicationDetailResponse reviewDocument(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID applicationId,
            @PathVariable UUID documentId,
            @Valid @RequestBody ReviewApplicationDocumentRequest request) {
        return applications.reviewDocument(user, applicationId, documentId, request);
    }

    @PostMapping("/{applicationId}/decisions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CAPABILITY_admissions.application.decide')")
    ApplicationDetailResponse decide(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID applicationId,
            @Valid @RequestBody RecordAdmissionDecisionRequest request) {
        return applications.decide(user, applicationId, request);
    }

    @GetMapping("/{applicationId}/options")
    @PreAuthorize("hasAuthority('CAPABILITY_admissions.application.manage') and hasAuthority('CAPABILITY_guardian.read')")
    ApplicationOptionsResponse options(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID applicationId) {
        return applications.options(user, applicationId);
    }

    @PostMapping("/{applicationId}/enrolment-handoffs")
    @PreAuthorize("hasAuthority('CAPABILITY_admissions.application.enrol')"
            + " and hasAuthority('CAPABILITY_student.manage')"
            + " and hasAuthority('CAPABILITY_guardian.manage')")
    EnrolmentHandoffResponse enrol(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID applicationId,
            @Valid @RequestBody EnrolApplicationRequest request) {
        return applications.enrol(user, applicationId, request);
    }
}
