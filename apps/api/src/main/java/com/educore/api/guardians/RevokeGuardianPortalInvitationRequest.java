package com.educore.api.guardians;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record RevokeGuardianPortalInvitationRequest(
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotBlank @Size(max = 240) String reason) {
}
