package com.educore.api.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VerifyMfaCodeRequest(
        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "^(?:\\d{6}|[A-Za-z2-9-]{20,40})$")
        String code) {
}
