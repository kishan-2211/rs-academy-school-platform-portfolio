package com.educore.api.students.imports;

import java.util.List;

public record StudentImportOptionsResponse(List<ImportScopeOption> scopes) {
    public StudentImportOptionsResponse {
        scopes = List.copyOf(scopes);
    }
}
