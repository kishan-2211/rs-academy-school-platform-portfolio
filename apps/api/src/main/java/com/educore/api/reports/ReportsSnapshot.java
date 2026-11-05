package com.educore.api.reports;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReportsSnapshot(
        UUID schoolId,
        String schoolName,
        String currencyCode,
        Instant generatedAt,
        List<ReportSection> sections,
        List<DataQualityIssue> dataQualityIssues) {

    public record ReportSection(
            String code,
            String title,
            String description,
            List<ReportMetric> metrics) {}

    public record ReportMetric(
            String code,
            String label,
            BigDecimal value,
            String unit,
            String definition) {}

    public record DataQualityIssue(
            String code,
            String severity,
            long count,
            String title,
            String description,
            String destination) {}
}
