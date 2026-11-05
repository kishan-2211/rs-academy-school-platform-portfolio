package com.educore.api.admissions;

import java.util.List;

public record EnquiryPageResponse(
        List<EnquiryListItem> items,
        int page,
        int size,
        long total,
        EnquiryMetrics metrics) {

    public EnquiryPageResponse {
        items = List.copyOf(items);
    }
}
