package com.educore.api.admissions;

public record EnquiryMetrics(
        long newCount,
        long openCount,
        long qualifiedCount,
        long overdueCount,
        long unassignedCount) {
}
