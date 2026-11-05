package com.educore.api.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Scoped financial-review evidence only; provider/customer credentials and contacts are never exposed. */
public record GatewayCaptureQueueResponse(
        UUID schoolId,
        LocalDate schoolToday,
        LocalDate latestApprovedCloseDate,
        List<CaptureItem> items,
        int page,
        int pageSize,
        long total,
        boolean hasMore) {

    public GatewayCaptureQueueResponse {
        items = List.copyOf(items);
    }

    public record CaptureItem(
            UUID id,
            UUID attemptId,
            UUID learnerId,
            String learnerName,
            String admissionNumber,
            BigDecimal amount,
            String currencyCode,
            Instant paidAt,
            LocalDate originalBusinessDate,
            String classification,
            String attemptStatus,
            String status,
            String holdReason,
            UUID closedDayCloseId,
            UUID paymentId,
            String receiptNumber,
            LocalDate postedOn,
            LocalDate earliestOpenDate,
            boolean canReviewedPost) {}
}
