package com.educore.api.guardians;

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
public class GuardianPortalInvitationController {

    private static final String STAFF_AUTHORITY =
            "hasAuthority('CAPABILITY_guardian.portal.invite')"
                    + " and hasAuthority('CAPABILITY_guardian.manage')";

    private final GuardianPortalInvitationService invitations;

    public GuardianPortalInvitationController(GuardianPortalInvitationService invitations) {
        this.invitations = invitations;
    }

    @GetMapping("/students/{learnerId}/guardians/{guardianId}/portal-invitations")
    @PreAuthorize(STAFF_AUTHORITY)
    GuardianPortalInvitationPageResponse list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId,
            @PathVariable UUID guardianId) {
        return invitations.list(user, learnerId, guardianId);
    }

    @PostMapping("/students/{learnerId}/guardians/{guardianId}/portal-invitations")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(STAFF_AUTHORITY)
    GuardianPortalInvitationResponse issue(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID learnerId,
            @PathVariable UUID guardianId,
            @Valid @RequestBody IssueGuardianPortalInvitationRequest request) {
        return invitations.issue(user, learnerId, guardianId, request);
    }

    @PostMapping("/guardian-portal-invitations/{invitationId}/revocations")
    @PreAuthorize(STAFF_AUTHORITY)
    GuardianPortalInvitationResponse revoke(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID invitationId,
            @Valid @RequestBody RevokeGuardianPortalInvitationRequest request) {
        return invitations.revoke(user, invitationId, request);
    }
}
