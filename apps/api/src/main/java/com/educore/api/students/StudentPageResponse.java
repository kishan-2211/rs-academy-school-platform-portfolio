package com.educore.api.students;

import java.util.List;

public record StudentPageResponse(
        List<StudentListItem> items,
        int page,
        int size,
        long total,
        StudentMetrics metrics) {

    public StudentPageResponse {
        items = List.copyOf(items);
    }
}
