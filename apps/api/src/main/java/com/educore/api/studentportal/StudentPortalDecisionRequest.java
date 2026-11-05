package com.educore.api.studentportal;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record StudentPortalDecisionRequest(
        @NotBlank @Pattern(regexp = "APPROVE|DECLINE") String decision,
        String password,
        @Min(0) long expectedVersion,
        @NotBlank @Size(min = 3, max = 500) String reason) {

    @AssertTrue(message = "A 12–200 character password is required only when approving access")
    public boolean isPasswordValidForDecision() {
        if ("APPROVE".equals(decision)) {
            return password != null && password.length() >= 12 && password.length() <= 200;
        }
        return !"DECLINE".equals(decision) || password == null || password.isBlank();
    }
}
