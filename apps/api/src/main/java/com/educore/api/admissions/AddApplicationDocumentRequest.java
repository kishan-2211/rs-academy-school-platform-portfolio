package com.educore.api.admissions;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AddApplicationDocumentRequest(
        @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,39}$") String documentCode,
        @NotBlank @Size(max = 120) String displayName,
        boolean required) {
}
