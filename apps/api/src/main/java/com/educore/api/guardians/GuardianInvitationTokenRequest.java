package com.educore.api.guardians;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GuardianInvitationTokenRequest(
        @NotBlank @Size(min = 32, max = 128) String token) {
}
