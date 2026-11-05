package com.educore.api.students.imports;

import java.util.List;

public record StudentImportPageResponse(
        List<StudentImportBatchSummary> items,
        int page,
        int size,
        long total) {

    public StudentImportPageResponse {
        items = List.copyOf(items);
    }
}
