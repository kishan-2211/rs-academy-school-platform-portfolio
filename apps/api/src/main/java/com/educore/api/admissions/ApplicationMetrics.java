package com.educore.api.admissions;

public record ApplicationMetrics(
        long draftCount,
        long reviewCount,
        long offeredCount,
        long acceptedCount,
        long incompleteDocumentCount) {
}
