package com.educore.api.identity;

import static com.educore.api.identity.StaffAccountInvitationModels.*;

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
public class StaffAccountInvitationController {
    private static final String ADMIN = "hasAuthority('CAPABILITY_identity.manage') and hasAuthority('CAPABILITY_people.manage')";
    private static final String BASE = "/schools/{schoolId}/staff/{staffId}/account-invitations";
    private final StaffAccountInvitationService service;
    public StaffAccountInvitationController(StaffAccountInvitationService service) { this.service = service; }

    @GetMapping(BASE) @PreAuthorize(ADMIN)
    ListResponse list(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID schoolId, @PathVariable UUID staffId) {
        return service.list(user, schoolId, staffId);
    }
    @PostMapping(BASE) @PreAuthorize(ADMIN) @ResponseStatus(HttpStatus.CREATED)
    Invitation issue(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID schoolId, @PathVariable UUID staffId,
            @Valid @RequestBody IssueRequest request) { return service.issue(user, schoolId, staffId, request); }
    @PostMapping(BASE + "/{invitationId}/revocations") @PreAuthorize(ADMIN)
    Invitation revoke(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID schoolId, @PathVariable UUID staffId,
            @PathVariable UUID invitationId, @Valid @RequestBody RevokeRequest request) {
        return service.revoke(user, schoolId, staffId, invitationId, request);
    }
    @PostMapping(BASE + "/{invitationId}/handoffs") @PreAuthorize(ADMIN)
    HandoffResponse handoff(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID schoolId, @PathVariable UUID staffId,
            @PathVariable UUID invitationId, @Valid @RequestBody HandoffRequest request) {
        return service.revealHandoff(user, schoolId, staffId, invitationId, request);
    }
    @PostMapping("/public/staff-account-invitations/previews")
    PreviewResponse preview(@Valid @RequestBody PreviewRequest request) { return service.preview(request); }
    @PostMapping("/public/staff-account-invitations/acceptances")
    AcceptanceResponse acceptNew(@Valid @RequestBody NewAcceptanceRequest request) { return service.acceptNew(request); }
    // Must remain outside /public/**: enabled MFA and the normal session/CSRF boundary apply.
    @PostMapping("/staff-account-invitations/acceptances") @PreAuthorize("isAuthenticated()")
    AcceptanceResponse acceptExisting(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody ExistingAcceptanceRequest request) { return service.acceptExisting(user, request); }
}
