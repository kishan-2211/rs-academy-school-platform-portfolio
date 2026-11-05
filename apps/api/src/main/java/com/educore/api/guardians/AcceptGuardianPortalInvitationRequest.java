package com.educore.api.guardians;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AcceptGuardianPortalInvitationRequest(
        @NotBlank @Size(min = 32, max = 128) String token,
        @NotBlank @Size(min = 12, max = 200) String password) {
}
