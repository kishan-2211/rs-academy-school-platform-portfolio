package com.educore.api.guardians;

import java.util.List;

public record GuardianPortalInvitationPageResponse(List<GuardianPortalInvitationResponse> items) {
    public GuardianPortalInvitationPageResponse {
        items = List.copyOf(items);
    }
}
