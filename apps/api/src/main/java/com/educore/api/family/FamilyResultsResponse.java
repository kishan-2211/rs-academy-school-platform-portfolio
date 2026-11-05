package com.educore.api.family;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record FamilyResultsResponse(UUID learnerId, List<ReportCard> reportCards) {
    public FamilyResultsResponse { reportCards = List.copyOf(reportCards); }

    public record ReportCard(
            UUID reportCardId,
            UUID publicationId,
            int publicationVersion,
            String reportNumber,
            String sessionName,
            String termName,
            String sectionName,
            BigDecimal overallPercentage,
            String overallGradeCode,
            String overallOutcome,
            Instant publishedAt,
            String snapshotSha256,
            Map<String, Object> report) {
        public ReportCard { report = Collections.unmodifiableMap(new LinkedHashMap<>(report)); }
    }
}
