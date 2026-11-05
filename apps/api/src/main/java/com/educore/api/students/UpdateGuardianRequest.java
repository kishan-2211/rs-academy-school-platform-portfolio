package com.educore.api.students;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record UpdateGuardianRequest(
        @NotBlank @Size(max = 120) String displayName,
        @Email @Size(max = 254) String email,
        @Size(max = 30) String phone,
        @NotBlank @Size(max = 40) String relationshipCode,
        boolean primaryContact,
        boolean pickupAuthorized,
        @NotNull @PositiveOrZero Long expectedGuardianVersion,
        @NotNull @PositiveOrZero Long expectedRelationshipVersion,
        @NotBlank @Size(max = 240) String correctionReason) {
}
