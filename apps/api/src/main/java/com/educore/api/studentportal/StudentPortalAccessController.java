package com.educore.api.studentportal;

import com.educore.api.identity.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class StudentPortalAccessController {

    private static final String STAFF = "hasAuthority('CAPABILITY_student.portal.manage')";
    private static final String GUARDIAN = "hasAuthority('CAPABILITY_portal.guardian')";

    private final StudentPortalAccessService access;

    public StudentPortalAccessController(StudentPortalAccessService access) {
        this.access = access;
    }

    @GetMapping("/students/{learnerId}/portal-access")
    @PreAuthorize(STAFF)
    StudentPortalAccessSnapshot snapshot(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId) {
        return access.snapshot(user, learnerId);
    }

    @PostMapping("/students/{learnerId}/portal-access/consent-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(STAFF)
    StudentPortalMutationResponse prepare(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId,
            @Valid @RequestBody PrepareStudentPortalAccessRequest request) {
        return access.prepare(user, learnerId, request);
    }

    @PostMapping("/students/{learnerId}/portal-access/consent-requests/{requestId}/revocation")
    @PreAuthorize(STAFF)
    StudentPortalMutationResponse revokePending(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId,
            @PathVariable UUID requestId,
            @Valid @RequestBody StudentPortalVersionedReason request) {
        return access.revokePending(user, learnerId, requestId, request);
    }

    @PostMapping("/students/{learnerId}/portal-access/suspension")
    @PreAuthorize(STAFF)
    StudentPortalMutationResponse suspend(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId,
            @Valid @RequestBody StudentPortalVersionedReason request) {
        return access.suspend(user, learnerId, request);
    }

    @PostMapping("/students/{learnerId}/portal-access/resumption")
    @PreAuthorize(STAFF)
    StudentPortalMutationResponse resume(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId,
            @Valid @RequestBody StudentPortalVersionedReason request) {
        return access.resume(user, learnerId, request);
    }

    @PostMapping("/students/{learnerId}/portal-access/ending")
    @PreAuthorize(STAFF)
    StudentPortalMutationResponse end(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId,
            @Valid @RequestBody StudentPortalVersionedReason request) {
        return access.end(user, learnerId, request);
    }

    @GetMapping("/family/children/{learnerId}/student-portal-access")
    @PreAuthorize(GUARDIAN)
    FamilyStudentPortalAccessResponse familyAccess(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId) {
        return access.familyAccess(user, learnerId);
    }

    @PostMapping("/family/children/{learnerId}/student-portal-access/consent-requests/{requestId}/decision")
    @PreAuthorize(GUARDIAN)
    StudentPortalMutationResponse decide(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId,
            @PathVariable UUID requestId,
            @Valid @RequestBody StudentPortalDecisionRequest request) {
        return access.decide(user, learnerId, requestId, request);
    }

    @PostMapping("/family/children/{learnerId}/student-portal-access/withdrawal")
    @PreAuthorize(GUARDIAN)
    StudentPortalMutationResponse withdraw(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId,
            @Valid @RequestBody StudentPortalVersionedReason request) {
        return access.withdraw(user, learnerId, request);
    }
}
