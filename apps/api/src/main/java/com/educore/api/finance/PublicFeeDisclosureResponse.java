package com.educore.api.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record PublicFeeDisclosureResponse(
        String schoolName,
        String currencyCode,
        Instant retrievedAt,
        List<Plan> plans) {
    public PublicFeeDisclosureResponse { plans = List.copyOf(plans); }
    public record Plan(String academicSession, String planName, String gradeCode,
            int revisionNumber, List<Line> lines) {
        public Plan { lines = List.copyOf(lines); }
    }
    public record Line(String feeHead, String label, LocalDate dueOn, BigDecimal amount,
            boolean allowPartial) {}
}
