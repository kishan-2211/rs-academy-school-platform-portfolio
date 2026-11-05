package com.educore.api.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VerifyMfaChallengeRequest(
        @NotBlank @Size(min = 32, max = 128) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String challengeToken,
        @NotBlank @Size(max = 64) @Pattern(regexp = "^(?:\\d{6}|[A-Za-z2-9-]{20,40})$") String code) {
}
