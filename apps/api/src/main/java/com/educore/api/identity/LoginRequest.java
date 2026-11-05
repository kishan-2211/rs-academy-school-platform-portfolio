package com.educore.api.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(min = 3, max = 254) String identifier,
        @NotBlank @Size(min = 8, max = 200) String password,
        boolean remember) {
}
