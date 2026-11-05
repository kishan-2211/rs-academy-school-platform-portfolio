package com.educore.api.guardians;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/guardian-invitations")
public class PublicGuardianPortalInvitationController {

    private final GuardianPortalInvitationService invitations;

    public PublicGuardianPortalInvitationController(GuardianPortalInvitationService invitations) {
        this.invitations = invitations;
    }

    @PostMapping("/previews")
    GuardianInvitationPreviewResponse preview(
            @Valid @RequestBody GuardianInvitationTokenRequest request) {
        return invitations.preview(request);
    }

    @PostMapping("/acceptances")
    GuardianInvitationAcceptanceResponse accept(
            @Valid @RequestBody AcceptGuardianPortalInvitationRequest request) {
        return invitations.accept(request);
    }
}
