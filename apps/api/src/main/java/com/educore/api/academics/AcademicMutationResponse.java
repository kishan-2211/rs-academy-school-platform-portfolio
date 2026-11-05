package com.educore.api.academics;

import java.util.List;
import java.util.UUID;

public record AcademicMutationResponse(UUID id, long rowVersion, List<String> warnings) {
    public AcademicMutationResponse {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public AcademicMutationResponse(UUID id, long rowVersion) {
        this(id, rowVersion, List.of());
    }
}
