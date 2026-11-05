package com.educore.api.admissions;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EnquiryDetailResponse(
        UUID id,
        String reference,
        String name,
        String email,
        String phone,
        String topic,
        String message,
        EnquiryIntent intent,
        EnquiryStatus status,
        String source,
        EnquiryAssignee assignee,
        Instant followUpAt,
        Instant lastContactedAt,
        Instant closedAt,
        String privacyNoticeVersion,
        Instant consentedAt,
        Instant createdAt,
        Instant updatedAt,
        long version,
        List<EnquiryNoteResponse> notes) {

    public EnquiryDetailResponse {
        notes = List.copyOf(notes);
    }
}
