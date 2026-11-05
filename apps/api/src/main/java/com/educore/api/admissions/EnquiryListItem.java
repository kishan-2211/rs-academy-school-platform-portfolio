package com.educore.api.admissions;

import java.time.Instant;
import java.util.UUID;

public record EnquiryListItem(
        UUID id,
        String reference,
        String name,
        String email,
        String phone,
        String topic,
        EnquiryIntent intent,
        EnquiryStatus status,
        String source,
        EnquiryAssignee assignee,
        Instant followUpAt,
        Instant createdAt,
        Instant updatedAt,
        long version) {
}
