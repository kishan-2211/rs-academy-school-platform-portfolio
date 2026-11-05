package com.educore.api.admissions;

import java.util.List;

public record ApplicationPageResponse(
        List<ApplicationListItem> items,
        int page,
        int size,
        long total,
        ApplicationMetrics metrics) {

    public ApplicationPageResponse {
        items = List.copyOf(items);
    }
}
