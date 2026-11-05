package com.educore.api.students.imports;

import java.util.List;

public record StudentImportBatchDetail(
        StudentImportBatchSummary batch,
        List<StudentImportRowResponse> rows) {

    public StudentImportBatchDetail {
        rows = List.copyOf(rows);
    }
}
